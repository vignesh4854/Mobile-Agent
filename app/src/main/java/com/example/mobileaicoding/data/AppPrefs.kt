package com.example.mobileaicoding.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.mobileaicoding.model.ApiMode
import com.example.mobileaicoding.model.ChatMessage
import com.example.mobileaicoding.model.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject

class AppPrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "mobile_ai_secure", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun loadConfig(): ProviderConfig {
        val mode = runCatching { ApiMode.valueOf(prefs.getString("mode", "OPENAI")!!) }.getOrDefault(ApiMode.OPENAI)
        return ProviderConfig(
            mode = mode,
            baseUrl = prefs.getString("base_url", if (mode == ApiMode.CLAUDE) "https://api.anthropic.com" else "https://api.openai.com/v1") ?: "",
            endpoint = prefs.getString("endpoint", if (mode == ApiMode.CLAUDE) "/v1/messages" else "/chat/completions") ?: "",
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", "") ?: "",
            customHeaders = prefs.getString("headers", "") ?: ""
        )
    }

    fun saveConfig(c: ProviderConfig) {
        prefs.edit().putString("mode", c.mode.name).putString("base_url", c.baseUrl)
            .putString("endpoint", c.endpoint).putString("api_key", c.apiKey)
            .putString("model", c.model).putString("headers", c.customHeaders).apply()
    }

    fun saveProject(uri: String, name: String) { prefs.edit().putString("project_uri", uri).putString("project_name", name).apply() }
    fun projectUri(): String? = prefs.getString("project_uri", null)
    fun projectName(): String = prefs.getString("project_name", "No project selected") ?: "No project selected"

    fun saveMessages(messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.takeLast(100).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content).put("timestamp", it.timestamp)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
    fun loadMessages(): List<ChatMessage> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); ChatMessage(o.optString("role"), o.optString("content"), o.optLong("timestamp")) }
    }.getOrDefault(emptyList())
}
