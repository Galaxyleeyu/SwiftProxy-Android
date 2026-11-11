package com.swiftproxy.android

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Converts supported share links into a sing-box 1.14 outbound configuration.
 * The resulting JSON is embedded in the full service configuration when the
 * Android VPN service is connected.
 */
object SingBoxConfigBuilder {
    fun build(rawUri: String): String = buildForNodes(listOf(rawUri), rawUri, autoSwitch = false)

    fun buildForNodes(rawUris: List<String>, selectedRawUri: String? = null, autoSwitch: Boolean = true): String {
        val candidates = if (autoSwitch) rawUris.distinct() else listOfNotNull(selectedRawUri)
        require(candidates.isNotEmpty()) { "没有可用节点" }
        val nodeOutbounds = candidates.mapNotNull { raw ->
            runCatching {
                val scheme = raw.substringBefore("://", "").lowercase()
                when (scheme) {
                    "vmess" -> buildVmess(raw)
                    "vless" -> buildVless(raw)
                    "trojan" -> buildTrojan(raw)
                    "ss" -> buildShadowsocks(raw)
                    else -> error("不支持的节点协议：$scheme")
                }
            }.getOrNull()
        }.mapIndexed { index, outbound ->
            outbound.put("tag", "node-$index")
        }
        require(nodeOutbounds.isNotEmpty()) { "节点配置无法生成" }
        val outbounds = JSONArray()
        nodeOutbounds.forEach { outbounds.put(it) }
        val finalTag = if (autoSwitch && nodeOutbounds.size > 1) {
            val members = JSONArray()
            nodeOutbounds.indices.forEach { members.put("node-$it") }
            outbounds.put(JSONObject().put("type", "urltest").put("tag", "auto")
                .put("outbounds", members)
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "3m").put("tolerance", 50))
            "auto"
        } else "node-0"
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        outbounds.put(JSONObject().put("type", "block").put("tag", "block"))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu", 9000).put("auto_route", true).put("strict_route", true)
                .put("stack", "mixed").put("sniff", true)))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", finalTag))
            .toString(2)
    }
    private fun buildVless(raw: String): JSONObject {
        val uri = URI(raw)
        val query = queryMap(uri.rawQuery)
        val uuid = decode(uri.rawUserInfo.orEmpty())
        require(uuid.isNotBlank()) { "VLESS 缺少 UUID" }
        val outbound = baseOutbound("vless", uri)
            .put("uuid", uuid)
        query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        addTransport(outbound, query)
        addTls(outbound, query, defaultEnabled = false)
        return outbound
    }

    private fun buildTrojan(raw: String): JSONObject {
        val uri = URI(raw)
        val query = queryMap(uri.rawQuery)
        val password = decode(uri.rawUserInfo.orEmpty())
        require(password.isNotBlank()) { "Trojan 缺少密码" }
        val outbound = baseOutbound("trojan", uri).put("password", password)
        addTransport(outbound, query)
        addTls(outbound, query, defaultEnabled = true)
        return outbound
    }

    private fun buildVmess(raw: String): JSONObject {
        val payload = raw.substringAfter("://").substringBefore('#')
        val json = JSONObject(decodeBase64(payload) ?: error("VMess 分享链接无法解码"))
        val server = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: error("VMess 端口无效")
        val uuid = json.optString("id")
        require(server.isNotBlank() && port in 1..65535 && uuid.isNotBlank()) {
            "VMess 节点缺少服务器、端口或 UUID"
        }
        val outbound = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("alter_id", json.optInt("aid", 0))
            .put("security", json.optString("scy").ifBlank { "auto" })

        val query = mutableMapOf<String, String>()
        val tls = json.optString("tls").equals("tls", true)
        if (tls) {
            query["security"] = "tls"
            query["sni"] = json.optString("sni").ifBlank { json.optString("host") }
            query["fp"] = json.optString("fp")
        }
        val network = json.optString("net").lowercase()
        when (network) {
            "ws" -> {
                query["type"] = "ws"
                query["path"] = json.optString("path")
                query["host"] = json.optString("host")
            }
            "grpc" -> {
                query["type"] = "grpc"
                query["serviceName"] = json.optString("path")
            }
            "httpupgrade" -> {
                query["type"] = "httpupgrade"
                query["path"] = json.optString("path")
                query["host"] = json.optString("host")
            }
        }
        addTransport(outbound, query)
        addTls(outbound, query, defaultEnabled = false)
        return outbound
    }

    private fun buildShadowsocks(raw: String): JSONObject {
        val uri = URI(raw)
        val rawAuthority = uri.rawSchemeSpecificPart.removePrefix("//").substringBefore('?').substringBefore('#')
        val at = rawAuthority.lastIndexOf('@')
        val userInfo: String
        val serverPart: String
        if (at >= 0) {
            userInfo = rawAuthority.substring(0, at)
            serverPart = rawAuthority.substring(at + 1)
        } else {
            val decoded = decodeBase64(rawAuthority) ?: error("Shadowsocks 链接无法解码")
            val decodedAt = decoded.lastIndexOf('@')
            require(decodedAt > 0) { "Shadowsocks 链接格式无效" }
            userInfo = decoded.substring(0, decodedAt)
            serverPart = decoded.substring(decodedAt + 1)
        }
        val credentials = decodeBase64(userInfo) ?: userInfo
        val split = credentials.indexOf(':')
        require(split > 0 && split < credentials.lastIndex) { "Shadowsocks 缺少加密方式或密码" }
        val (server, port) = parseHostPort(serverPart)
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
            .put("method", credentials.substring(0, split))
            .put("password", credentials.substring(split + 1))
    }

    private fun baseOutbound(type: String, uri: URI): JSONObject {
        val server = uri.host?.takeIf { it.isNotBlank() }?.removePrefix("[")?.removeSuffix("]") ?: error("$type 服务器地址无效")
        val port = uri.port
        require(port in 1..65535) { "$type 端口无效" }
        return JSONObject()
            .put("type", type)
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
    }

    private fun addTls(outbound: JSONObject, query: Map<String, String>, defaultEnabled: Boolean) {
        val security = query["security"].orEmpty()
        val enabled = when {
            security.isBlank() -> defaultEnabled
            security.equals("none", true) -> false
            else -> true
        }
        if (!enabled) return

        val tls = JSONObject().put("enabled", true)
        query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
        query["alpn"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray(it.split(',').map(String::trim).filter(String::isNotBlank)))
        }
        query["fp"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
        if (security.equals("reality", true)) {
            val reality = JSONObject().put("enabled", true)
            query["pbk"]?.takeIf { it.isNotBlank() }?.let { reality.put("public_key", it) }
            query["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        outbound.put("tls", tls)
    }

    private fun addTransport(outbound: JSONObject, query: Map<String, String>) {
        when (query["type"]?.lowercase()) {
            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                query["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                query["serviceName"]?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                query["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
                outbound.put("transport", transport)
            }
        }
    }

    private fun queryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val key = decode(item.substringBefore('='))
            val value = decode(item.substringAfter('=', ""))
            key to value
        }.toMap()
    }

    private fun parseHostPort(value: String): Pair<String, Int> {
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 1 && value.getOrNull(end + 1) == ':') { "服务器地址无效" }
            val host = value.substring(1, end)
            val port = value.substring(end + 2).toIntOrNull() ?: error("端口无效")
            require(port in 1..65535) { "端口无效" }
            return host to port
        }
        val separator = value.lastIndexOf(':')
        require(separator > 0) { "服务器地址无效" }
        val host = value.substring(0, separator)
        val port = value.substring(separator + 1).toIntOrNull() ?: error("端口无效")
        require(port in 1..65535) { "端口无效" }
        return host to port
    }

    private fun decode(value: String): String =
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
