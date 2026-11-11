package com.swiftproxy.android

import android.util.Base64
import java.net.URI
import java.net.URLDecoder

data class ProxyNode(
    val id: String,
    val name: String,
    val type: String,
    val server: String,
    val rawUri: String
)

object NodeParser {
    private val supported = setOf("vless", "vmess", "ss", "trojan")

    fun parseSubscription(content: String): List<ProxyNode> {
        val trimmed = content.trim()
        val decoded = if (trimmed.startsWith("vmess://") || trimmed.startsWith("vless://") ||
            trimmed.startsWith("ss://") || trimmed.startsWith("trojan://")) {
            trimmed
        } else {
            try {
                String(Base64.decode(trimmed.replace("\\s".toRegex(), ""), Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                trimmed
            }
        }
        return decoded.lineSequence().mapNotNull { line ->
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#")) null else parseUri(value)
        }.distinctBy { it.rawUri }
    }

    fun parseUri(value: String): ProxyNode? {
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in supported) return null

            val fragment = uri.rawFragment?.let {
                URLDecoder.decode(it, "UTF-8").takeIf(String::isNotBlank)
            }
            val authority = when (scheme) {
                "ss" -> uri.rawSchemeSpecificPart.substringBefore('?').substringBefore('#')
                    .substringAfterLast('@', "")
                else -> uri.rawAuthority.orEmpty()
            }
            val server = authority.substringAfterLast('@').substringBefore(':')
                .ifBlank { uri.host.orEmpty() }
            val fallbackName = when (scheme) {
                "vless" -> "VLESS"
                "vmess" -> "VMess"
                "ss" -> "Shadowsocks"
                else -> "Trojan"
            }
            ProxyNode(
                id = value.hashCode().toString(),
                name = fragment ?: if (server.isNotBlank()) "$server · $fallbackName" else fallbackName,
                type = scheme.uppercase(),
                server = server,
                rawUri = value
            )
        } catch (_: Exception) {
            null
        }
    }
}
