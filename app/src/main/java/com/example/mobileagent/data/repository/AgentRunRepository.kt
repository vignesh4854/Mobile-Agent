package com.example.mobileagent.data.repository

import android.content.Context
import com.example.mobileagent.agent.multi.AgentTraceEvent
import com.example.mobileagent.data.local.AgentDatabase
import com.example.mobileagent.data.local.AgentRunEntity
import com.example.mobileagent.data.local.AgentTraceEntity
import kotlinx.coroutines.flow.Flow

class AgentRunRepository(context: Context) {
    private val dao = AgentDatabase.get(context.applicationContext).agentRunDao()

    fun observeRecentRuns(): Flow<List<AgentRunEntity>> = dao.observeRecentRuns()
    fun observeTraces(runId: String): Flow<List<AgentTraceEntity>> = dao.observeTraces(runId)

    suspend fun start(runId: String, task: String) {
        dao.insertRun(AgentRunEntity(runId, task.take(20_000), "running", System.currentTimeMillis()))
        dao.pruneOldTraces()
        dao.pruneOldRuns()
    }

    suspend fun append(event: AgentTraceEvent) {
        dao.insertTrace(AgentTraceEntity(
            runId = event.runId,
            role = event.role?.name,
            eventType = event.type.name,
            detail = event.detail.take(8_000),
            estimatedPromptTokens = event.estimatedPromptTokens,
            estimatedCompletionTokens = event.estimatedCompletionTokens,
            durationMillis = event.durationMillis,
            timestamp = event.timestamp
        ))
    }

    suspend fun finish(runId: String, status: String, promptTokens: Long,
                       completionTokens: Long, durationMillis: Long) {
        dao.finishRun(runId, status, System.currentTimeMillis(), promptTokens,
            completionTokens, durationMillis)
    }
}
