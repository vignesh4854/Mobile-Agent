package com.example.mobileagent.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.mobileagent.model.ApiConfig
import com.example.mobileagent.model.ProtocolMode
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the API key with an Android Keystore AES-GCM key.
 * Non-secret settings are stored in a private app file.
 */
class ApiConfigStore(context: Context) {
    private val file = context.getFileStreamPath("api-config.json")
    private val alias = "mobile-agent-api-config-v1"

    fun save(config: ApiConfig) {
        val encrypted = encrypt(config.apiKey)
        val json = JSONObject()
            .put("baseUrl", config.baseUrl)
            .put("endpoint", config.endpoint)
            .put("model", config.model)
            .put("protocol", config.protocol.name)
            .put("iv", encrypted.first)
            .put("ciphertext", encrypted.second)
        file.writeText(json.toString(), Charsets.UTF_8)
    }

    fun load(): ApiConfig? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val key = decrypt(json.getString("iv"), json.getString("ciphertext"))
        ApiConfig(
            baseUrl = json.getString("baseUrl"),
            endpoint = json.getString("endpoint"),
            apiKey = key,
            model = json.getString("model"),
            protocol = ProtocolMode.valueOf(json.getString("protocol"))
        )
    }.getOrNull()

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(iv: String, ciphertext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
