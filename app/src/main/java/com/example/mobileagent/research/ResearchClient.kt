package com.example.mobileagent.research

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Brave Search API client plus conservative webpage text retrieval.
 * Fetched pages are untrusted content and must never be treated as agent instructions.
 */
class ResearchClient(context: Context) {
    private val store = ResearchStore(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun search(query: String, apiKey: String): List<SearchResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 400) { "Enter a search query (max 400 characters)." }
        require(apiKey.isNotBlank()) { "Brave Search API key is required." }
        val url = "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=10"
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Search API HTTP ${response.code}: ${body.take(700)}")
            val results = JSONObject(body).optJSONObject("web")?.optJSONArray("results")
            if (results == null) return@withContext emptyList()
            (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                SearchResult(item.optString("title"), item.optString("url"), item.optString("description"))
            }
        }
    }

    suspend fun retrieve(url: String): String = withContext(Dispatchers.IO) {
        validatePublicHttpsUrl(url)
        val request = Request.Builder().url(url).header("User-Agent", "MobileAgentResearch/2.5").build()
        http.newCall(request).execute().use { response ->
            require(response.code in 200..299) {
                "Page returned HTTP ${response.code}. Redirects are not followed automatically."
            }
            val type = response.header("Content-Type").orEmpty()
            require(type.contains("text/html", true) || type.contains("text/plain", true)) {
                "Only HTML and plain-text pages can be retrieved."
            }
            val bytes = response.body?.bytes() ?: byteArrayOf()
            require(bytes.size <= 1_500_000) { "Page exceeds 1.5 MB retrieval limit." }
            val raw = String(bytes, Charsets.UTF_8)
            if (type.contains("text/plain", true)) raw.take(50_000) else HtmlText.extract(raw).take(50_000)
        }
    }

    fun save(title: String, url: String, content: String): SavedResearch =
        store.save(title, url, content)

    fun savedItems(): List<SavedResearch> = store.list()

    private fun validatePublicHttpsUrl(raw: String) {
        val uri = URI(raw)
        require(uri.scheme.equals("https", true)) { "Only HTTPS pages are allowed." }
        require(uri.userInfo == null) { "Credentials in URLs are not allowed." }
        val host = uri.host ?: throw IllegalArgumentException("Invalid URL host.")
        require(!host.equals("localhost", true) && !host.endsWith(".local", true)) { "Local hosts are blocked." }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }) { "Private or local network destinations are blocked." }
    }
}

/** Minimal HTML-to-text cleanup. No scripts are executed. */
private object HtmlText {
    fun extract(html: String): String {
        var text = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<",
            "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")
        entities.forEach { (k, v) -> text = text.replace(k, v, ignoreCase = true) }
        return text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
    }
}
