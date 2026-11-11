package com.swiftproxy.android

import android.content.Context
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.os.Process
import java.net.NetworkInterface
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Thin reflection adapter around libbox's generated Java API.
 * Reflection keeps the app source independent from generated Go method names,
 * while still using the official CommandServer/PlatformInterface contracts.
 */
internal class SingBoxEngine(
    private val service: VpnService,
    private val onStopped: () -> Unit
) {
    companion object { private const val TAG = "SwiftProxyCore" }

    private var commandServer: Any? = null
    private var handlerProxy: Any? = null
    private var platformProxy: Any? = null
    private var tunDescriptor: android.os.ParcelFileDescriptor? = null

    fun start(config: String) {
        check(commandServer == null) { "代理核心已经启动" }
        setupLibbox()
        val loader = service.classLoader
        val handlerClass = Class.forName("io.nekohasekai.libbox.CommandServerHandler", true, loader)
        val platformClass = Class.forName("io.nekohasekai.libbox.PlatformInterface", true, loader)
        handlerProxy = Proxy.newProxyInstance(loader, arrayOf(handlerClass)) { _, method, args ->
            when (method.name) {
                "serviceStop" -> stop()
                "serviceReload" -> Log.i(TAG, "Core requested reload")
                "writeDebugMessage" -> Log.d(TAG, args?.firstOrNull()?.toString().orEmpty())
                "triggerNativeCrash" -> Log.e(TAG, "Native crash callback received")
                "connectSSHAgent" -> -1
                else -> defaultValue(method.returnType)
            }
        }
        platformProxy = Proxy.newProxyInstance(loader, arrayOf(platformClass)) { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt()
                    if (fd != null) service.protect(fd)
                    null
                }
                "openTun" -> openTun(args?.firstOrNull() ?: error("缺少 TUN 参数"))
                "getInterfaces" -> getInterfaces()
                "findConnectionOwner" -> findConnectionOwner()
                "underNetworkExtension", "includeAllNetworks",
                "usePlatformShell", "usePlatformBridge", "usePlatformAutoRedirect" -> false
                "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor",
                "clearDNSCache", "startNeighborMonitor", "closeNeighborMonitor",
                "registerMyInterface" -> null
                "tailscaleHostname" -> "SwiftProxy"
                "lookupSFTPServer" -> ""
                "readSystemSSHHostKey" -> ""
                else -> defaultValue(method.returnType)
            }
        }

        val serverClass = Class.forName("io.nekohasekai.libbox.CommandServer", true, loader)
        val constructor = serverClass.constructors.firstOrNull { it.parameterCount == 2 }
            ?: error("当前 libbox 缺少 CommandServer(handler, platform) 构造函数")
        val server = constructor.newInstance(handlerProxy, platformProxy)
        server.javaClass.getMethod("start").invoke(server)
        commandServer = server
        val overrideClass = Class.forName("io.nekohasekai.libbox.OverrideOptions", true, loader)
        val overrideOptions = overrideClass.getDeclaredConstructor().newInstance()
        server.javaClass.getMethod("startOrReloadService", String::class.java, overrideClass)
            .invoke(server, config, overrideOptions)
        Log.i(TAG, "sing-box service started")
    }

    private fun setupLibbox() {
        val loader = service.classLoader
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox", true, loader)
        val setupClass = Class.forName("io.nekohasekai.libbox.SetupOptions", true, loader)
        val options = setupClass.getDeclaredConstructor().newInstance()
        val workDir = service.getExternalFilesDir(null) ?: service.filesDir
        setProperty(options, "basePath", service.filesDir.absolutePath)
        setProperty(options, "workingPath", workDir.absolutePath)
        setProperty(options, "tempPath", service.cacheDir.absolutePath)
        setProperty(options, "fixAndroidStack", true)
        setProperty(options, "logMaxLines", 3000)
        setProperty(options, "debug", false)
        setProperty(options, "appVersion", "4")
        setProperty(options, "appMarketingVersion", "0.5.0")
        val setup = libbox.methods.firstOrNull { it.name == "setup" && it.parameterCount == 1 }
            ?: error("libbox.setup API 不存在")
        setup.invoke(null, options)
    }

    private fun setProperty(target: Any, name: String, value: Any) {
        val setter = target.javaClass.methods.firstOrNull {
            it.name.equals("set" + name.replaceFirstChar(Char::uppercase), true) &&
                it.parameterCount == 1 && boxed(it.parameterTypes[0]).isAssignableFrom(boxed(value.javaClass))
        } ?: target.javaClass.methods.firstOrNull {
            it.name.equals("set" + name.replaceFirstChar(Char::uppercase), true) && it.parameterCount == 1
        }
        setter?.invoke(target, value)
    }

    private fun openTun(options: Any): Int {
        val builder = service.Builder().setSession("SwiftProxy")
        val mtu = call(options, "getMtu", "mtu") as? Number
        if (mtu != null && mtu.toInt() in 576..9000) builder.setMtu(mtu.toInt())

        addAddressIterator(builder, call(options, "getInet4Address", "inet4Address"))
        addAddressIterator(builder, call(options, "getInet6Address", "inet6Address"))

        val autoRoute = call(options, "getAutoRoute", "autoRoute") as? Boolean ?: true
        if (autoRoute) {
            var routeCount = addRouteIterator(builder, call(options, "getInet4RouteAddress", "inet4RouteAddress"))
            routeCount += addRouteIterator(builder, call(options, "getInet6RouteAddress", "inet6RouteAddress"))
            if (routeCount == 0) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            addStringIterator(builder, call(options, "getDnsServerAddress", "dnsServerAddress"))
        }

        val established = builder.establish() ?: error("Android 未能建立 VPN TUN 接口")
        tunDescriptor?.close()
        tunDescriptor = established
        return established.fd
    }

    private fun getInterfaces(): Any {
        val loader = service.classLoader
        val itemClass = Class.forName("io.nekohasekai.libbox.NetworkInterface", true, loader)
        val iteratorClass = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator", true, loader)
        val stringIteratorClass = Class.forName("io.nekohasekai.libbox.StringIterator", true, loader)
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val items = networkInterfaces.mapNotNull { networkInterface ->
            runCatching {
                val item = itemClass.getDeclaredConstructor().newInstance()
                setProperty(item, "name", networkInterface.name)
                setProperty(item, "index", networkInterface.index)
                setProperty(item, "mtu", runCatching { networkInterface.mtu }.getOrDefault(1500))
                val addresses = networkInterface.interfaceAddresses.map { it.address.hostAddress + "/" + it.networkPrefixLength }
                setProperty(item, "addresses", stringIterator(stringIteratorClass, addresses))
                var flags = 0
                if (networkInterface.isUp) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                setProperty(item, "flags", flags)
                val activeNetwork = connectivity.activeNetwork
                val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                val linkProperties = activeNetwork?.let { connectivity.getLinkProperties(it) }
                val typeField = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "InterfaceTypeWIFI"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "InterfaceTypeCellular"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "InterfaceTypeEthernet"
                    else -> "InterfaceTypeOther"
                }
                val libbox = Class.forName("io.nekohasekai.libbox.Libbox", true, loader)
                val type = runCatching { libbox.getField(typeField).get(null) }.getOrNull()
                if (type != null) setProperty(item, "type", type)
                setProperty(item, "dnsServer", stringIterator(stringIteratorClass, linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()))
                setProperty(item, "gateway", stringIterator(stringIteratorClass, linkProperties?.routes?.filter { it.destination.prefixLength == 0 }?.mapNotNull { it.gateway?.hostAddress }.orEmpty()))
                setProperty(item, "metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                item
            }.getOrNull()
        }
        var index = 0
        return Proxy.newProxyInstance(loader, arrayOf(iteratorClass)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> index < items.size
                "next" -> if (index < items.size) items[index++] else null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun stringIterator(iteratorClass: Class<*>, values: List<String>): Any {
        var index = 0
        return Proxy.newProxyInstance(service.classLoader, arrayOf(iteratorClass)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> index < values.size
                "next" -> if (index < values.size) values[index++] else ""
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun findConnectionOwner(): Any {
        val ownerClass = Class.forName("io.nekohasekai.libbox.ConnectionOwner", true, service.classLoader)
        val owner = ownerClass.getDeclaredConstructor().newInstance()
        setProperty(owner, "userId", Process.myUid())
        setProperty(owner, "userName", service.packageName)
        val iteratorClass = Class.forName("io.nekohasekai.libbox.StringIterator", true, service.classLoader)
        setProperty(owner, "androidPackageNames", stringIterator(iteratorClass, listOf(service.packageName)))
        return owner
    }
    private fun addAddressIterator(builder: VpnService.Builder, iterator: Any?) {
        forEachIterator(iterator) { address ->
            val ip = call(address, "address")?.toString().orEmpty()
            val prefix = (call(address, "prefix") as? Number)?.toInt() ?: 0
            if (ip.isNotBlank()) builder.addAddress(ip, prefix)
        }
    }

    private fun addRouteIterator(builder: VpnService.Builder, iterator: Any?): Int {
        var count = 0
        forEachIterator(iterator) { address ->
            val ip = call(address, "address")?.toString().orEmpty()
            val prefix = (call(address, "prefix") as? Number)?.toInt() ?: 0
            if (ip.isNotBlank()) {
                builder.addRoute(ip, prefix)
                count++
            }
        }
        return count
    }

    private fun addStringIterator(builder: VpnService.Builder, iterator: Any?) {
        forEachIterator(iterator) { value ->
            value.toString().takeIf { it.isNotBlank() }?.let { runCatching { builder.addDnsServer(it) } }
        }
    }

    private fun forEachIterator(iterator: Any?, action: (Any) -> Unit) {
        if (iterator == null) return
        val hasNext = iterator.javaClass.methods.firstOrNull { it.name == "hasNext" && it.parameterCount == 0 } ?: return
        val next = iterator.javaClass.methods.firstOrNull { it.name == "next" && it.parameterCount == 0 } ?: return
        var guard = 0
        while ((hasNext.invoke(iterator) as? Boolean == true) && guard++ < 256) {
            val value = next.invoke(iterator) ?: continue
            action(value)
        }
    }

    private fun call(target: Any, vararg names: String): Any? {
        for (name in names) {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) return method.invoke(target)
        }
        return null
    }

    fun stop() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.javaClass.getMethod("closeService").invoke(server) }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.javaClass.getMethod("close").invoke(server) }
                .onFailure { Log.w(TAG, "close failed", it) }
        }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        handlerProxy = null
        platformProxy = null
        onStopped()
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Void.TYPE -> null
        else -> null
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
