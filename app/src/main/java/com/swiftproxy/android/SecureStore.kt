package com.swiftproxy.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts node credentials and the subscription URL with an AES-GCM key
 * held by Android Keystore. No node URI or subscription URL is stored in
 * plaintext preferences.
 */
object SecureStore {
    private const val PREFS = "swiftproxy_secure"
    private const val URL_KEY = "subscription_url"
    private const val NODES_KEY = "nodes_encrypted"
    private const val SUBSCRIPTION_NODES_KEY = "subscription_nodes_encrypted"
    private const val KEY_ALIAS = "swiftproxy_local_data_aes"

    fun saveSubscriptionUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(URL_KEY, encrypt(value)).apply()
    }

    fun readSubscriptionUrl(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(URL_KEY, null) ?: return null
        return decrypt(encoded)
    }

    fun saveNodeUris(context: Context, values: Set<String>) =
        writeEncryptedSet(context, NODES_KEY, values)

    fun readNodeUris(context: Context): Set<String> {
        val encrypted = readEncryptedSet(context, NODES_KEY)
        if (encrypted.isNotEmpty()) return encrypted
        val legacyPrefs = context.getSharedPreferences("swiftproxy", Context.MODE_PRIVATE)
        val legacy = legacyPrefs.getStringSet("nodes", emptySet()).orEmpty().toSet()
        if (legacy.isNotEmpty()) {
            saveNodeUris(context, legacy)
            legacyPrefs.edit().remove("nodes").apply()
        }
        return legacy
    }

    fun saveSubscriptionNodeUris(context: Context, values: Set<String>) =
        writeEncryptedSet(context, SUBSCRIPTION_NODES_KEY, values)

    fun readSubscriptionNodeUris(context: Context): Set<String> {
        val encrypted = readEncryptedSet(context, SUBSCRIPTION_NODES_KEY)
        if (encrypted.isNotEmpty()) return encrypted
        val legacyPrefs = context.getSharedPreferences("swiftproxy", Context.MODE_PRIVATE)
        val legacy = legacyPrefs.getStringSet("subscription_nodes", emptySet()).orEmpty().toSet()
        if (legacy.isNotEmpty()) {
            saveSubscriptionNodeUris(context, legacy)
            legacyPrefs.edit().remove("subscription_nodes").apply()
        }
        return legacy
    }

    private fun writeEncryptedSet(context: Context, key: String, values: Set<String>) {
        val encoded = values.mapNotNull { value -> runCatching { encrypt(value) }.getOrNull() }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(key, encoded).apply()
    }

    private fun readEncryptedSet(context: Context, key: String): Set<String> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet()).orEmpty()
        return encoded.mapNotNull(::decrypt).toSet()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > 12) { "本地密文无效" }
        val iv = encrypted.copyOfRange(0, 12)
        val payload = encrypted.copyOfRange(12, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
