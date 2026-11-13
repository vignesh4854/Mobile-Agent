package com.example.mobileaicoding.network

import com.example.mobileaicoding.files.ProjectFiles
import com.example.mobileaicoding.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProviderClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS).build()

    suspend fun test(config: ProviderConfig): String = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "Enter an API key in Settings" }
        require(config.model.isNotBlank()) { "Enter a model ID in Settings" }
        val url = buildUrl(config)
        val body = if (config.mode == ApiMode.CLAUDE) {
            JSONObject().put("model", config.model).put("max_tokens", 16).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK"))).toString()
        } else {
            JSONObject().put("model", config.model).put("max_tokens", 16).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK"))).toString()
        }
        val req = requestBuilder(config, url).post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(1200)}") else "Connection successful" }
    }

    suspend fun runTurn(
        config: ProviderConfig,
        history: List<ChatMessage>,
        userText: String,
        files: ProjectFiles?,
        onDelta: (String) -> Unit,
        onApproval: suspend (PendingApproval) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "API key is missing. Open Settings." }
        require(config.model.isNotBlank()) { "Model ID is missing. Open Settings." }
        val messages = if (config.mode == ApiMode.CLAUDE) buildClaudeHistory(history, userText) else buildOpenAiHistory(history, userText)
        val tools = toolDefinitions()
        val finalText = StringBuilder()
        var rounds = 0
        while (rounds++ < 12) {
            val turn = if (config.mode == ApiMode.CLAUDE) callClaude(config, messages, tools, onDelta) else callOpenAi(config, messages, tools, onDelta)
            if (turn.text.isNotBlank()) finalText.append(turn.text)
            if (turn.toolCalls.isEmpty()) break
            messages.put(turn.assistantPayload)
            val claudeResults = JSONArray()
            for (call in turn.toolCalls) {
                val result = executeTool(call, files, onApproval)
                if (config.mode == ApiMode.CLAUDE) {
                    claudeResults.put(JSONObject().put("type", "tool_result").put("tool_use_id", call.id).put("content", result))
                } else {
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result))
                }
            }
            if (config.mode == ApiMode.CLAUDE && claudeResults.length() > 0) messages.put(JSONObject().put("role", "user").put("content", claudeResults))
        }
        if (rounds > 12) finalText.append("\n\n[Stopped: maximum tool-call rounds reached]")
        finalText.toString().ifBlank { "The model returned no text. Check the provider's tool-calling compatibility." }
    }

    private fun buildClaudeHistory(history: List<ChatMessage>, userText: String): JSONArray {
        val arr = JSONArray()
        history.takeLast(30).filter { it.role == "user" || it.role == "assistant" }.forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        arr.put(JSONObject().put("role", "user").put("content", userText))
        return arr
    }
    private fun buildOpenAiHistory(history: List<ChatMessage>, userText: String): JSONArray {
        val arr = JSONArray()
        history.takeLast(30).filter { it.role == "user" || it.role == "assistant" }.forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        arr.put(JSONObject().put("role", "user").put("content", userText))
        return arr
    }

    private fun toolDefinitions(): JSONArray {
        val defs = listOf(
            Triple("list_files", "List files and folders. path may be empty for project root.", "path"),
            Triple("read_file", "Read a UTF-8 text file from the selected project.", "path"),
            Triple("search_files", "Find file names containing query.", "query"),
            Triple("search_text", "Search text in supported source/config files.", "query"),
            Triple("create_file", "Create a new UTF-8 text file. Requires user approval.", "path"),
            Triple("edit_file", "Replace the complete contents of an existing UTF-8 text file. Requires user approval.", "path"),
            Triple("apply_patch", "Apply a full-file replacement to a text file. Requires user approval.", "path"),
            Triple("delete_file", "Delete a file. Requires user approval. Directories cannot be deleted in V1.", "path")
        )
        return JSONArray().apply {
            defs.forEach { (name, desc, required) ->
                val props = JSONObject().put(required, JSONObject().put("type", "string").put("description", if (required == "path") "Path relative to selected project root" else "Search query"))
                if (name in setOf("create_file", "edit_file", "apply_patch")) props.put("content", JSONObject().put("type", "string").put("description", "Complete UTF-8 file content"))
                put(JSONObject().put("name", name).put("description", desc).put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", if (name in setOf("create_file", "edit_file", "apply_patch")) JSONArray().put(required).put("content") else JSONArray().put(required))))
            }
        }
    }

    private fun openAiTools(claudeTools: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until claudeTools.length()) {
            val t = claudeTools.getJSONObject(i)
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.getString("name")).put("description", t.getString("description")).put("parameters", t.getJSONObject("input_schema"))))
        }
    }

    private suspend fun callClaude(c: ProviderConfig, messages: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): ModelTurn = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", c.model).put("max_tokens", 4096).put("stream", true)
            .put("system", "You are a coding assistant working inside the user-selected project folder. Use the provided file tools to inspect relevant files before editing. Never claim a file was changed unless the tool confirms it. Keep edits focused and explain what changed.")
            .put("messages", messages).put("tools", tools)
        val req = requestBuilder(c, buildUrl(c)).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Claude API HTTP ${response.code}: ${response.body?.string()?.take(1600)}")
            val source = response.body?.source() ?: error("Empty response")
            val blocks = mutableMapOf<Int, JSONObject>()
            val inputBuffers = mutableMapOf<Int, StringBuilder>()
            val calls = mutableListOf<ToolCall>()
            val text = StringBuilder()
            var event = ""
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (event.ifBlank { obj.optString("type") }) {
                    "content_block_start" -> {
                        val idx = obj.optInt("index")
                        val block = obj.optJSONObject("content_block") ?: continue
                        blocks[idx] = block
                        if (block.optString("type") == "tool_use") inputBuffers[idx] = StringBuilder()
                    }
                    "content_block_delta" -> {
                        val idx = obj.optInt("index")
                        val delta = obj.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> { val s = delta.optString("text"); text.append(s); blocks[idx]?.put("text", (blocks[idx]?.optString("text", "") ?: "") + s); onDelta(s) }
                            "input_json_delta" -> inputBuffers.getOrPut(idx) { StringBuilder() }.append(delta.optString("partial_json"))
                        }
                    }
                    "content_block_stop" -> {
                        val idx = obj.optInt("index")
                        val block = blocks[idx] ?: continue
                        if (block.optString("type") == "tool_use") {
                            val args = runCatching { JSONObject(inputBuffers[idx]?.toString().orEmpty().ifBlank { "{}" }) }.getOrDefault(JSONObject())
                            calls += ToolCall(block.optString("id"), block.optString("name"), args)
                            block.put("input", args)
                        }
                    }
                }
                event = ""
            }
            val content = JSONArray()
            blocks.toSortedMap().values.forEach { content.put(it) }
            val assistant = JSONObject().put("role", "assistant").put("content", content)
            ModelTurn(text.toString(), calls, assistant)
        }
    }

    private suspend fun callOpenAi(c: ProviderConfig, messages: JSONArray, claudeTools: JSONArray, onDelta: (String) -> Unit): ModelTurn = withContext(Dispatchers.IO) {
        val withSystem = JSONArray().put(JSONObject().put("role", "system").put("content", "You are a coding assistant working inside the user-selected project folder. Use the provided file tools to inspect relevant files before editing. Never claim a file was changed unless the tool confirms it. Keep edits focused and explain what changed."))
        for (i in 0 until messages.length()) withSystem.put(messages.getJSONObject(i))
        val body = JSONObject().put("model", c.model).put("stream", true).put("messages", withSystem)
            .put("tools", openAiTools(claudeTools)).put("tool_choice", "auto")
        val req = requestBuilder(c, buildUrl(c)).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("OpenAI API HTTP ${response.code}: ${response.body?.string()?.take(1600)}")
            val source = response.body?.source() ?: error("Empty response")
            val text = StringBuilder()
            val toolParts = sortedMapOf<Int, JSONObject>()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty() && content != "null") { text.append(content); onDelta(content) }
                val chunks = delta.optJSONArray("tool_calls")
                if (chunks != null) for (i in 0 until chunks.length()) {
                    val chunk = chunks.getJSONObject(i)
                    val idx = chunk.optInt("index", i)
                    val part = toolParts.getOrPut(idx) { JSONObject().put("id", "").put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")) }
                    if (chunk.has("id")) part.put("id", part.optString("id") + chunk.optString("id"))
                    val fn = chunk.optJSONObject("function") ?: continue
                    val target = part.getJSONObject("function")
                    if (fn.has("name")) target.put("name", target.optString("name") + fn.optString("name"))
                    if (fn.has("arguments")) target.put("arguments", target.optString("arguments") + fn.optString("arguments"))
                }
            }
            val calls = toolParts.values.mapNotNull { part ->
                val fn = part.getJSONObject("function")
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrNull() ?: return@mapNotNull null
                ToolCall(part.optString("id"), fn.optString("name"), args)
            }
            val toolArray = JSONArray().apply { toolParts.values.forEach { put(it) } }
            val assistant = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
            if (toolArray.length() > 0) assistant.put("tool_calls", toolArray)
            ModelTurn(text.toString(), calls, assistant)
        }
    }

    private suspend fun executeTool(call: ToolCall, files: ProjectFiles?, approval: suspend (PendingApproval) -> Boolean): String {
        val fs = files ?: return "ERROR: Select a project folder before using file tools."
        val path = call.arguments.optString("path", "")
        return try {
            when (call.name) {
                "list_files" -> fs.list(path)
                "read_file" -> fs.read(path)
                "search_files" -> fs.searchFiles(call.arguments.optString("query"))
                "search_text" -> fs.searchText(call.arguments.optString("query"))
                "create_file", "edit_file", "apply_patch" -> {
                    val content = call.arguments.optString("content", "")
                    val old = if (call.name == "create_file") "" else runCatching { fs.read(path) }.getOrDefault("")
                    val preview = buildDiffPreview(old, content).take(7000)
                    val ok = approval(PendingApproval(call.name, path, "write", preview))
                    if (!ok) "User denied the file change." else {
                        fs.write(path, content, create = call.name == "create_file")
                        "Successfully saved $path (${content.length} characters)."
                    }
                }
                "delete_file" -> {
                    val ok = approval(PendingApproval(call.name, path, "delete", "Delete '$path'? This cannot be undone by the app."))
                    if (!ok) "User denied deletion." else { fs.delete(path); "Deleted $path." }
                }
                else -> "ERROR: Unknown tool '${call.name}'."
            }
        } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun buildDiffPreview(old: String, fresh: String): String {
        if (old.isBlank()) return "+++ new file\n" + fresh.lines().take(120).joinToString("\n") { "+ $it" }
        val a = old.lines().take(200)
        val b = fresh.lines().take(200)
        if (a.size * b.size > 40_000) {
            return "--- current file\n" + a.take(60).joinToString("\n") { "- $it" } +
                "\n+++ proposed file\n" + b.take(60).joinToString("\n") { "+ $it" } + "\n… preview truncated …"
        }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = StringBuilder("--- current file\n+++ proposed file\n")
        var i = 0; var j = 0; var emitted = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out.append("  ").append(a[i]).append('\n'); i++; j++ }
                i < a.size && (j == b.size || dp[i + 1][j] >= dp[i][j + 1]) -> { out.append("- ").append(a[i]).append('\n'); i++ }
                j < b.size -> { out.append("+ ").append(b[j]).append('\n'); j++ }
            }
            emitted++
            if (emitted >= 240) { out.append("… preview truncated …"); break }
        }
        if (old.lines().size > 200 || fresh.lines().size > 200) out.append("\n… file exceeds diff preview limit …")
        return out.toString()
    }

    private fun buildUrl(c: ProviderConfig): String {
        val base = c.baseUrl.trim().trimEnd('/')
        val endpoint = c.endpoint.trim()
        require(base.startsWith("https://")) { "Use HTTPS for API base URLs" }
        return if (endpoint.isBlank()) base else base + "/" + endpoint.trimStart('/')
    }

    private fun requestBuilder(c: ProviderConfig, url: String): Request.Builder {
        val b = Request.Builder().url(url).header("Content-Type", "application/json")
        if (c.mode == ApiMode.CLAUDE) b.header("x-api-key", c.apiKey).header("anthropic-version", "2023-06-01")
        else b.header("Authorization", "Bearer ${c.apiKey}")
        c.customHeaders.lines().map { it.trim() }.filter { it.contains(":") }.forEach {
            val name = it.substringBefore(':').trim(); val value = it.substringAfter(':').trim()
            if (name.isNotBlank() && name.lowercase() !in setOf("authorization", "x-api-key")) b.header(name, value)
        }
        return b
    }
}
