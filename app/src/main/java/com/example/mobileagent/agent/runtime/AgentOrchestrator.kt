package com.example.mobileagent.agent.runtime

import com.example.mobileagent.model.ChatMessage
import com.example.mobileagent.model.ProviderReply
import com.example.mobileagent.provider.ApiProvider
import com.example.mobileagent.model.ApiConfig

/** Provider-independent agent loop. Tool execution is injected and must enforce its own approvals. */
class AgentOrchestrator(
    private val provider: ApiProvider,
    private val maxRounds: Int = 6
) {
    suspend fun run(config: ApiConfig, history: MutableList<ChatMessage>,
                     executeTool: suspend (String, String, String) -> String,
                     onText: (String) -> Unit = {}): String {
        require(maxRounds in 1..20) { "Agent round limit must be between 1 and 20." }
        repeat(maxRounds) {
            val reply: ProviderReply = provider.send(config, history, onText)
            history += ChatMessage(com.example.mobileagent.model.MessageRole.ASSISTANT,
                reply.text, toolCalls = reply.toolCalls)
            if (reply.toolCalls.isEmpty()) return reply.text
            for (call in reply.toolCalls) {
                val result = executeTool(call.name, call.argumentsJson, call.id)
                history += ChatMessage(com.example.mobileagent.model.MessageRole.TOOL, result,
                    toolCallId = call.id, toolName = call.name)
            }
        }
        return "Stopped after $maxRounds tool rounds."
    }
}
