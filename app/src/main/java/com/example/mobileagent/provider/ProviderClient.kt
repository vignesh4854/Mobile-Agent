package com.example.mobileagent.provider

import com.example.mobileagent.model.ApiConfig
import com.example.mobileagent.model.ChatMessage
import com.example.mobileagent.model.ProviderReply

/** Stable provider contract consumed by the V3 orchestration engine. */
interface ApiProvider {
    suspend fun send(
        config: ApiConfig,
        history: List<ChatMessage>,
        onText: (String) -> Unit = {}
    ): ProviderReply
}

/** Backwards-compatible name retained for the V2 UI during the staged migration. */
interface ProviderClient : ApiProvider

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ProviderRegistry(private val providers: Map<com.example.mobileagent.model.ProtocolMode, ApiProvider>) {
    fun resolve(mode: com.example.mobileagent.model.ProtocolMode): ApiProvider =
        providers[mode] ?: throw ProviderException("No provider is registered for $mode")
}
