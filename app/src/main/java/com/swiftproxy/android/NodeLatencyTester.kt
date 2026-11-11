package com.swiftproxy.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures TCP connect time to the node endpoint. This is a reachability
 * indicator, not the full TLS/proxy handshake latency.
 */
object NodeLatencyTester {
    fun test(node: ProxyNode, callback: (String) -> Unit) {
        Thread {
            val result = runCatching {
                val started = SystemClock.elapsedRealtime()
                Socket().use { socket ->
                    check(SwiftProxyVpnService.protectSocket(socket)) { "无法绕过 VPN 测速" }
                    socket.connect(InetSocketAddress(node.server, node.port), 4000)
                }
                "${SystemClock.elapsedRealtime() - started} ms"
            }.getOrElse { "超时 / 不可达" }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }
}
