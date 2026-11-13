package com.example.mobileagent.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.mobileagent.agent.multi.AgentProfile
import com.example.mobileagent.agent.multi.AgentRole
import com.example.mobileagent.model.ApiConfig
import com.example.mobileagent.model.ProtocolMode
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores each role's provider settings locally; API keys are encrypted with Android Keystore AES-GCM. */
class AgentProfilesStore(context: Context) {
    private val file = context.getFileStreamPath("multi-agent-profiles.json")
    private val alias = "mobile-agent-profiles-v1"

    @Synchronized
    fun save(profiles: List<AgentProfile>) {
        require(profiles.map { it.role }.distinct().size == profiles.size) { "Duplicate agent roles." }
        val array = JSONArray()
        profiles.forEach { profile ->
            val encrypted = encrypt(profile.config.apiKey)
            array.put(JSONObject()
                .put("role", profile.role.name)
                .put("baseUrl", profile.config.baseUrl)
                .put("endpoint", profile.config.endpoint)
                .put("model", profile.config.model)
                .put("protocol", profile.config.protocol.name)
                .put("keyIv", encrypted.first)
                .put("keyCiphertext", encrypted.second))
        }
        file.writeText(JSONObject().put("profiles", array).toString(), Charsets.UTF_8)
    }

    @Synchronized
    fun load(): List<AgentProfile> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("profiles")
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val key = decrypt(obj.getString("keyIv"), obj.getString("keyCiphertext"))
            AgentProfile(
                AgentRole.valueOf(obj.getString("role")),
                ApiConfig(
                    obj.getString("baseUrl"), obj.getString("endpoint"), key,
                    obj.getString("model"), ProtocolMode.valueOf(obj.getString("protocol"))
                )
            )
        }
    }.getOrDefault(emptyList())

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(iv: String, ciphertext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
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
