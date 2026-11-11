package com.swiftproxy.android

import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * Owns the native sing-box command server and its Android VPN platform adapter.
 * Calls are typed against libbox instead of relying on generated-API reflection.
 */
internal class SingBoxEngine(
    private val service: VpnService,
    private val onStopped: () -> Unit
) : CommandServerHandler {

    companion object {
        private const val TAG = "SwiftProxyCore"
        @Volatile private var setupCompleted = false

        private fun setupLibbox(service: VpnService) {
            if (setupCompleted) return
            synchronized(SingBoxEngine::class.java) {
                if (setupCompleted) return
                val workingDir = service.getExternalFilesDir(null) ?: service.filesDir
                workingDir.mkdirs()
                service.filesDir.mkdirs()
                service.cacheDir.mkdirs()
                Libbox.setup(
                    SetupOptions().apply {
                        basePath = service.filesDir.absolutePath
                        workingPath = workingDir.absolutePath
                        tempPath = service.cacheDir.absolutePath
                        fixAndroidStack = true
                        logMaxLines = 3000
                        debug = false
                        appVersion = "5"
                        appMarketingVersion = "0.6.0"
                    }
                )
                setupCompleted = true
            }
        }
    }

    private var commandServer: CommandServer? = null
    private var platform: SwiftProxyPlatformInterface? = null
    private var activeConfig: String? = null
    @Volatile private var stopping = false

    fun start(config: String) {
        check(commandServer == null) { "代理核心已经启动" }
        setupLibbox(service)
        Libbox.checkConfig(config)

        stopping = false
        val platformInterface = SwiftProxyPlatformInterface(service)
        val server = CommandServer(this, platformInterface)
        try {
            server.start()
            commandServer = server
            platform = platformInterface
            activeConfig = config
            server.startOrReloadService(config, OverrideOptions())
            Log.i(TAG, "sing-box service started")
        } catch (error: Throwable) {
            runCatching { server.closeService() }
            runCatching { server.close() }
            platformInterface.closeTun()
            commandServer = null
            platform = null
            activeConfig = null
            throw error
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        val server = commandServer
        commandServer = null
        activeConfig = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "close failed", it) }
        }
        platform?.closeTun()
        platform = null
        onStopped()
    }

    override fun serviceStop() {
        stop()
    }

    override fun serviceReload() {
        val server = commandServer ?: return
        val config = activeConfig ?: return
        if (stopping) return
        runCatching { server.startOrReloadService(config, OverrideOptions()) }
            .onFailure { Log.e(TAG, "sing-box reload failed", it) }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        // Android's VPN tunnel is the only proxy mode exposed by this app.
    }

    override fun sendNotification(notification: Notification) {
        Log.i(TAG, "sing-box notification: ${notification.title}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        Log.d(TAG, "sing-box notification dismissed: $identifier ($typeID)")
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("sing-box requested native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }

    override fun connectSSHAgent(): Int = -1
}
