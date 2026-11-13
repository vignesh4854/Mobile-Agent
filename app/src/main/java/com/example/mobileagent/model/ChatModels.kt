package com.example.mobileagent.model

enum class ProtocolMode { OPENAI, ANTHROPIC }
data class ApiConfig(
    val baseUrl: String, val endpoint: String, val apiKey: String,
    val model: String, val protocol: ProtocolMode
)
enum class MessageRole { USER, ASSISTANT, TOOL }
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList()
)
data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)
data class ProviderReply(val text: String, val toolCalls: List<ToolCallRequest>)
