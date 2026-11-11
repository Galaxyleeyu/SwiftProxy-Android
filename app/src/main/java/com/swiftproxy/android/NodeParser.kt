package com.swiftproxy.android

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ProxyNode(
    val id: String,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val rawUri: String
)

object NodeParser {
    private val supported = setOf("vless", "vmess", "ss", "trojan")

    fun parseSubscription(content: String): List<ProxyNode> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        val plainNodes = parseLines(trimmed)
        if (plainNodes.isNotEmpty()) return plainNodes

        val decoded = decodeBase64(trimmed.replace("\\s".toRegex(), "")) ?: return emptyList()
        return parseLines(decoded)
    }

    private fun parseLines(content: String): List<ProxyNode> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull(::parseUri)
            .distinctBy { it.rawUri }
            .toList()

    fun parseUri(value: String): ProxyNode? {
        val scheme = value.substringBefore("://", "").lowercase()
        if (scheme !in supported) return null

        return try {
            when (scheme) {
                "vmess" -> parseVmess(value)
                "ss" -> parseShadowsocks(value)
                else -> parseStandardUri(value, scheme)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmess(value: String): ProxyNode? {
        val payload = value.substringAfter("://").substringBefore('#')
        val jsonText = decodeBase64(payload) ?: return null
        val json = JSONObject(jsonText)
        val server = json.optString("add").trim()
        val port = json.optString("port").trim().toIntOrNull() ?: return null
        if (server.isBlank() || port !in 1..65535) return null

        val name = json.optString("ps").takeIf { it.isNotBlank() }
            ?: "$server · VMess"
        return ProxyNode(
            id = value.hashCode().toString(),
            name = name,
            type = "VMESS",
            server = server,
            port = port,
            rawUri = value
        )
    }

    private fun parseShadowsocks(value: String): ProxyNode? {
        val uri = URI(value)
        val raw = uri.rawSchemeSpecificPart.removePrefix("//").substringBefore('#')
        val beforeQuery = raw.substringBefore('?')
        val at = beforeQuery.lastIndexOf('@')

        val serverPart: String
        val userInfo: String
        if (at >= 0) {
            userInfo = beforeQuery.substring(0, at)
            serverPart = beforeQuery.substring(at + 1)
        } else {
            val decoded = decodeBase64(beforeQuery) ?: return null
            val decodedAt = decoded.lastIndexOf('@')
            if (decodedAt < 0) return null
            userInfo = decoded.substring(0, decodedAt)
            serverPart = decoded.substring(decodedAt + 1)
        }

        val credentials = decodeBase64(userInfo) ?: userInfo
        if (!credentials.contains(':')) return null
        val endpoint = parseHostPort(serverPart) ?: return null
        val name = uri.rawFragment?.let(::decodeFragment)?.takeIf { it.isNotBlank() }
            ?: "${endpoint.first} · Shadowsocks"

        return ProxyNode(
            id = value.hashCode().toString(),
            name = name,
            type = "SS",
            server = endpoint.first,
            port = endpoint.second,
            rawUri = value
        )
    }

    private fun parseStandardUri(value: String, scheme: String): ProxyNode? {
        val uri = URI(value)
        val authority = uri.rawAuthority ?: return null
        val endpointAuthority = authority.substringAfterLast('@')
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: extractHost(endpointAuthority)
        if (host.isBlank()) return null

        val port = uri.port
        if (port !in 1..65535) return null

        val fallbackName = when (scheme) {
            "vless" -> "VLESS"
            else -> "Trojan"
        }
        val name = uri.rawFragment?.let(::decodeFragment)?.takeIf { it.isNotBlank() }
            ?: "$host · $fallbackName"

        return ProxyNode(
            id = value.hashCode().toString(),
            name = name,
            type = scheme.uppercase(),
            server = host,
            port = port,
            rawUri = value
        )
    }

    private fun parseHostPort(authority: String): Pair<String, Int>? {
        val value = authority.substringAfterLast('@')
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val closing = value.indexOf(']')
            if (closing <= 1 || value.getOrNull(closing + 1) != ':') return null
            host = value.substring(1, closing)
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            if (separator <= 0) return null
            host = value.substring(0, separator)
            portText = value.substring(separator + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun extractHost(authority: String): String {
        val value = authority.substringAfterLast('@')
        if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
        return value.substringBeforeLast(':')
    }

    private fun decodeFragment(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodeBase64(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}
