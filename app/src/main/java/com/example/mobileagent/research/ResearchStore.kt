package com.example.mobileagent.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ResearchStore(context: Context) {
    private val file = File(context.filesDir, "research-library.json")

    @Synchronized
    fun save(title: String, url: String, content: String): SavedResearch {
        require(title.isNotBlank() && url.startsWith("https://", true))
        val item = SavedResearch(title.take(300), url, System.currentTimeMillis(), content.take(50_000))
        val items = readAll().toMutableList()
        items.add(0, item)
        val json = JSONArray()
        items.take(100).forEach { saved ->
            json.put(JSONObject().put("title", saved.title).put("url", saved.url)
                .put("retrievedAt", saved.retrievedAt).put("content", saved.content))
        }
        val temp = File(file.parentFile, "research-library.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(json.toString(), Charsets.UTF_8)
            temp.delete()
        }
        return item
    }

    @Synchronized
    fun list(): List<SavedResearch> = readAll()

    private fun readAll(): List<SavedResearch> = runCatching {
        if (!file.exists()) return emptyList()
        val json = JSONArray(file.readText(Charsets.UTF_8))
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            SavedResearch(o.optString("title"), o.optString("url"),
                o.optLong("retrievedAt"), o.optString("content"))
        }
    }.getOrDefault(emptyList())
}
