package com.swiftproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log

class SwiftProxyVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.swiftproxy.android.action.START"
        const val ACTION_STOP = "com.swiftproxy.android.action.STOP"
        const val ACTION_STATE = "com.swiftproxy.android.action.STATE"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "swiftproxy_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "SwiftProxyVpn"

        fun publishState(service: VpnService, state: String, message: String? = null) {
            service.sendBroadcast(
                Intent(ACTION_STATE).setPackage(service.packageName)
                    .putExtra("state", state)
                    .putExtra("message", message.orEmpty())
            )
        }
    }

    private var engine: SingBoxEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine?.stop()
                engine = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    publishState(this, "error", "代理配置为空")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("正在启动代理…"))
                publishState(this, "starting", "正在启动 sing-box 核心")
                runCatching {
                    val instance = SingBoxEngine(this) {
                        publishState(this, "disconnected", "代理已停止")
                    }
                    engine = instance
                    instance.start(config)
                    publishState(this, "connected", "代理核心已启动")
                    updateNotification("SwiftProxy 正在运行")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to start proxy core", error)
                    engine?.stop()
                    engine = null
                    publishState(this, "error", error.message ?: "代理启动失败")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        engine?.stop()
        engine = null
        publishState(this, "disconnected", "VPN 权限已撤销")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SwiftProxy VPN", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "显示代理连接状态" }
            )
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("SwiftProxy")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }
}
