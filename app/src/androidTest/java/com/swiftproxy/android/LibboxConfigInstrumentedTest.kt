package com.swiftproxy.android

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibboxConfigInstrumentedTest {

    @Test
    fun generatedConfigsPassNativeLibboxValidation() {
        val uuid = "00000000-0000-0000-0000-000000000000"
        val vless = "vless://$uuid@example.com:443?security=tls&sni=example.com#vless"
        val vlessWs = "vless://$uuid@example.com:443?type=ws&host=cdn.example.com&path=%2Fws&security=tls&sni=example.com#vless-ws"
        val vlessReality = "vless://$uuid@example.com:443?type=grpc&serviceName=grpc-service&security=reality&sni=example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123456789abcdef#vless-reality"
        val trojan = "trojan://test-password@example.com:443?security=tls&sni=example.com#trojan"
        val vmessJson = """{"v":"2","ps":"vmess","add":"example.com","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com"}"""
        val vmessPayload = Base64.encodeToString(vmessJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val vmess = "vmess://$vmessPayload"
        val vmessUpgradeJson = """{"v":"2","ps":"vmess-upgrade","add":"example.com","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"httpupgrade","host":"example.com","path":"/upgrade","tls":"tls","sni":"example.com"}"""
        val vmessUpgradePayload = Base64.encodeToString(vmessUpgradeJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val vmessUpgrade = "vmess://$vmessUpgradePayload"
        val ssCredentials = Base64.encodeToString(
            "aes-128-gcm:test-password@example.com:8388".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val shadowsocks = "ss://$ssCredentials#shadowsocks"
        val parsedShadowsocks = NodeParser.parseUri(shadowsocks)
        assertNotNull(parsedShadowsocks)
        assertEquals(8388, parsedShadowsocks?.port)

        listOf(vless, vlessWs, vlessReality, vmess, vmessUpgrade, shadowsocks, trojan).forEach { shareLink ->
            val config = SingBoxConfigBuilder.build(shareLink)
            checkNativeConfig(config)
            if (shareLink == vmessUpgrade) {
                assertTrue(config.contains("\"type\": \"httpupgrade\""))
            }
        }

        val autoConfig = SingBoxConfigBuilder.buildForNodes(
            listOf(vless, trojan),
            selectedRawUri = vless,
            autoSwitch = true
        )
        checkNativeConfig(autoConfig)
        assertTrue(autoConfig.contains("\"type\": \"urltest\""))
    }

    private fun checkNativeConfig(config: String) {
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
        val check = libbox.methods.first { it.name == "checkConfig" && it.parameterCount == 1 }
        check.invoke(null, config)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeLibbox() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val loader = context.classLoader
            val libbox = Class.forName("io.nekohasekai.libbox.Libbox", true, loader)
            val setupClass = Class.forName("io.nekohasekai.libbox.SetupOptions", true, loader)
            val options = setupClass.getDeclaredConstructor().newInstance()
            val working = context.getExternalFilesDir(null) ?: context.filesDir
            setProperty(options, "basePath", context.filesDir.absolutePath)
            setProperty(options, "workingPath", working.absolutePath)
            setProperty(options, "tempPath", context.cacheDir.absolutePath)
            setProperty(options, "fixAndroidStack", true)
            setProperty(options, "logMaxLines", 1000)
            setProperty(options, "debug", false)
            setProperty(options, "appVersion", "5")
            setProperty(options, "appMarketingVersion", "0.6.0")
            val setup = libbox.methods.first { it.name == "setup" && it.parameterCount == 1 }
            setup.invoke(null, options)
        }

        private fun setProperty(target: Any, name: String, value: Any) {
            val setterName = "set" + name.replaceFirstChar(Char::uppercase)
            val setter = target.javaClass.methods.first {
                it.name == setterName && it.parameterCount == 1
            }
            setter.invoke(target, value)
        }
    }
}
