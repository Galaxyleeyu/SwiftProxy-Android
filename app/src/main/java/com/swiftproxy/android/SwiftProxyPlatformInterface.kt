package com.swiftproxy.android

import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as SystemNetworkInterface

/**
 * Typed Android adapter for sing-box's PlatformInterface.
 *
 * Unsupported optional facilities (root bridge, shell and neighbor monitor)
 * are explicitly disabled. VPN TUN, socket protection, interface discovery
 * and connection-owner lookup are implemented for normal non-root operation.
 */
internal class SwiftProxyPlatformInterface(
    private val vpn: VpnService
) : PlatformInterface {
    private val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
    private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var defaultNetwork: Network? = null
    private val localResolver = object : LocalDNSTransport {
        override fun raw(): Boolean = false

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val active = defaultNetwork ?: error("尚未获取底层网络")
            try {
                val addresses = active.getAllByName(domain).filter { address ->
                    when {
                        network.endsWith("4") -> address !is Inet6Address
                        network.endsWith("6") -> address is Inet6Address
                        else -> true
                    }
                }
                if (addresses.isEmpty()) {
                    ctx.errorCode(3)
                } else {
                    ctx.success(addresses.joinToString("\\n") { it.hostAddress.orEmpty() })
                }
            } catch (_: java.net.UnknownHostException) {
                ctx.errorCode(3)
            }
        }

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            error("当前设备不支持原始 DNS 数据包转发")
        }
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val callbackHandler by lazy {
        Handler(HandlerThread("swiftproxy-network").apply { start() }.looper)
    }
    private val networkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpn.protect(fd)) { "Android 无法保护代理核心的出站 socket" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpn) == null) { "缺少 Android VPN 授权" }
        val builder = vpn.Builder().setSession("SwiftProxy").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4Routes = options.inet4RouteAddress
                if (v4Routes.hasNext()) {
                    while (v4Routes.hasNext()) {
                        val route = v4Routes.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val v6Routes = options.inet6RouteAddress
                if (v6Routes.hasNext()) {
                    while (v6Routes.hasNext()) {
                        val route = v6Routes.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val excluded4 = options.inet4RouteExcludeAddress
                while (excluded4.hasNext()) {
                    val route = excluded4.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
                }
                val excluded6 = options.inet6RouteExcludeAddress
                while (excluded6.hasNext()) {
                    val route = excluded6.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
                }
            } else {
                val v4Routes = options.inet4RouteRange
                while (v4Routes.hasNext()) {
                    val route = v4Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val v6Routes = options.inet6RouteRange
                while (v6Routes.hasNext()) {
                    val route = v6Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }

            val includePackages = options.includePackage
            while (includePackages.hasNext()) {
                val packageName = includePackages.next()
                try {
                    builder.addAllowedApplication(packageName)
                } catch (error: NameNotFoundException) {
                    Log.w(TAG, "VPN include package not found: $packageName", error)
                }
            }
            val excludePackages = options.excludePackage
            while (excludePackages.hasNext()) {
                val packageName = excludePackages.next()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: NameNotFoundException) {
                    Log.w(TAG, "VPN exclude package not found: $packageName", error)
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                android.net.ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toList()
                )
            )
        }

        val established = builder.establish() ?: error("Android 未能建立 VPN TUN 接口")
        tunDescriptor?.close()
        tunDescriptor = established
        return established.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
        } else {
            val fallback = Libbox::class.java.methods.firstOrNull {
                it.name == "findConnectionOwner" && it.parameterCount == 5
            } ?: error("Android 10 以下无法通过公开 API 查询连接所属应用")
            return fallback.invoke(
                null, ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort
            ) as? ConnectionOwner ?: error("无法查询连接所属应用")
        }
        check(uid != Process.INVALID_UID) { "找不到连接所属应用" }
        val packages = vpn.packageManager.getPackagesForUid(uid)
        return ConnectionOwner().apply {
            userId = uid
            userName = packages?.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages?.toList().orEmpty().iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateDefaultInterface(listener, network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateDefaultInterface(listener, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateDefaultInterface(listener, network)
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) defaultNetwork = null
                runCatching { listener.updateDefaultInterface("", -1, false, false) }
            }
        }
        networkCallback = callback
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                connectivity.registerBestMatchingNetworkCallback(networkRequest, callback, callbackHandler)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                connectivity.requestNetwork(networkRequest, callback, callbackHandler)
            }
            else -> {
                connectivity.registerDefaultNetworkCallback(callback, callbackHandler)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        defaultNetwork = null
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        defaultNetwork = network
        val name = connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
        val index = runCatching { SystemNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        runCatching { listener.updateDefaultInterface(name, index, false, false) }
            .onFailure { Log.w(TAG, "更新默认网络接口失败", it) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val systemInterfaces = SystemNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val items = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        for (network in connectivity.allNetworks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val systemInterface = systemInterfaces.firstOrNull { it.name == name } ?: continue
            val item = io.nekohasekai.libbox.NetworkInterface().apply {
                this.name = name
                index = systemInterface.index
                mtu = runCatching { systemInterface.mtu }.getOrDefault(1500)
                addresses = StringArray(systemInterface.interfaceAddresses.map { it.toPrefix() }.iterator())
                dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress }.iterator())
                gateway = StringArray(
                    link.routes.filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.takeUnless { gateway -> gateway.isAnyLocalAddress }?.hostAddress }
                        .iterator()
                )
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                var interfaceFlags = 0
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    interfaceFlags = interfaceFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (systemInterface.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                if (systemInterface.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
                if (systemInterface.supportsMulticast()) interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                flags = interfaceFlags
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            items.add(item)
        }
        return NetworkInterfaceArray(items.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}
    override fun sendNotification(notification: Notification) {
        Log.i(TAG, "sing-box notification: ${notification.title}")
    }
    override fun cancelNotification(identifier: String, typeID: Int) {
        Log.d(TAG, "sing-box notification dismissed: $identifier ($typeID)")
    }
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport = localResolver
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {}
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = error("SwiftProxy 不提供系统 Shell 功能")

    override fun readSystemSSHHostKey(): String = error("SwiftProxy 不提供 SSH 主机密钥")
    override fun lookupSFTPServer(): String = ""
    override fun tailscaleHostname(): String = "SwiftProxy"

    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("SwiftProxy 不支持 root 网络桥接")

    override fun lookupUser(username: String?): PlatformUser = PlatformUser().apply {
        this.username = username?.takeIf { it.isNotBlank() } ?: vpn.packageName
        uid = Process.myUid()
        gid = Process.myUid()
        homeDir = vpn.filesDir.absolutePath
    }

    override fun registerMyInterface(name: String?) {}

    fun closeTun() {
        tunDescriptor?.close()
        tunDescriptor = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        defaultNetwork = null
    }

    private fun StringIterator.toList(): List<String> {
        val values = mutableListOf<String>()
        while (hasNext()) values.add(next())
        return values
    }

    private fun java.net.InterfaceAddress.toPrefix(): String {
        val address = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }
        return "$address/$networkPrefixLength"
    }

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class NetworkInterfaceArray(
        private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    companion object {
        private const val TAG = "SwiftProxyPlatform"
    }
}
