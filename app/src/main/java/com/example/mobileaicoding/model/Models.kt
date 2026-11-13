package com.example.mobileaicoding.model

import org.json.JSONObject

enum class ApiMode { CLAUDE, OPENAI }
data class ProviderConfig(
    val mode: ApiMode = ApiMode.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val endpoint: String = "/chat/completions",
    val apiKey: String = "",
    val model: String = "",
    val customHeaders: String = ""
)
data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())
data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
data class ModelTurn(val text: String, val toolCalls: List<ToolCall>, val assistantPayload: JSONObject)
data class PendingApproval(val name: String, val path: String, val operation: String, val preview: String)
