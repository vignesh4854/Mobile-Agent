package com.example.mobileagent.data.repository

import android.content.Context
import com.example.mobileagent.data.local.AgentDatabase
import com.example.mobileagent.data.local.MessageEntity
import com.example.mobileagent.data.local.SessionEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SessionRepository(context: Context) {
    private val db = AgentDatabase.get(context)
    private val sessions = db.sessionDao()
    private val messages = db.messageDao()

    fun observeSessions(projectId: String): Flow<List<SessionEntity>> = sessions.observeForProject(projectId)
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = messages.observeForSession(sessionId)

    suspend fun getOrCreateSession(projectId: String): SessionEntity {
        sessions.latest(projectId)?.let { return it }
        val now = System.currentTimeMillis()
        return SessionEntity(UUID.randomUUID().toString(), projectId, "New session", now, now)
            .also { sessions.insert(it) }
    }

    suspend fun createSession(projectId: String, title: String = "New session"): SessionEntity {
        val now = System.currentTimeMillis()
        return SessionEntity(UUID.randomUUID().toString(), projectId, title.trim().take(80).ifBlank { "New session" }, now, now)
            .also { sessions.insert(it) }
    }

    suspend fun appendMessage(sessionId: String, role: String, content: String,
                              toolCallsJson: String = "[]", toolCallId: String? = null,
                              toolName: String? = null) {
        require(role in setOf("USER", "ASSISTANT", "TOOL")) { "Unsupported message role." }
        require(content.length <= 250_000) { "Message exceeds the storage limit." }
        require(runCatching { JSONArray(toolCallsJson) }.isSuccess) { "Invalid tool-call JSON." }
        messages.insert(MessageEntity(sessionId = sessionId, role = role, content = content,
            toolCallsJson = toolCallsJson, toolCallId = toolCallId, toolName = toolName,
            createdAt = System.currentTimeMillis()))
        sessions.findById(sessionId)?.let { sessions.update(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun clearMessages(sessionId: String) = messages.deleteForSession(sessionId)
}
