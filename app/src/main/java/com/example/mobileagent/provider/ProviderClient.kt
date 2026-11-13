package com.example.mobileagent.provider

import com.example.mobileagent.model.ApiConfig
import com.example.mobileagent.model.ChatMessage
import com.example.mobileagent.model.ProviderReply

interface ProviderClient {
    suspend fun send(
        config: ApiConfig,
        history: List<ChatMessage>,
        onText: (String) -> Unit = {}
    ): ProviderReply
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
