package com.example.mobileagent.agent.multi

import com.example.mobileagent.model.ApiConfig
import java.util.concurrent.ConcurrentHashMap

enum class AgentRole(val label: String, val mission: String) {
    PLANNER("Planner", "Decompose the task, clarify requirements, define a safe implementation plan and acceptance criteria."),
    CODER("Coder", "Implement the planned solution. Inspect relevant files first, make focused changes, and explain every proposed modification."),
    REVIEWER("Reviewer", "Independently review the coder's result for correctness, security, maintainability, regressions and missing requirements."),
    DEBUGGER("Debugger", "Diagnose likely failures, edge cases and test gaps. Suggest concrete fixes and validation commands without claiming tests ran.")
}

data class AgentProfile(
    val role: AgentRole,
    val config: ApiConfig
)

enum class AgentTraceType { RUN_STARTED, AGENT_STARTED, HANDOFF, TOOL_STARTED, TOOL_FINISHED, APPROVAL_WAIT, AGENT_FINISHED, AGENT_FAILED, RUN_FINISHED }

data class AgentTraceEvent(
    val runId: String,
    val role: AgentRole?,
    val type: AgentTraceType,
    val detail: String,
    val estimatedPromptTokens: Long = 0,
    val estimatedCompletionTokens: Long = 0,
    val durationMillis: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentTokenUsage(
    val role: AgentRole,
    val estimatedPromptTokens: Long,
    val estimatedCompletionTokens: Long
) {
    val total: Long get() = estimatedPromptTokens + estimatedCompletionTokens
}

data class AgentResult(
    val role: AgentRole,
    val output: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val durationMillis: Long,
    val toolCalls: Int,
    val error: String? = null
)

data class MultiAgentRunResult(
    val runId: String,
    val finalOutput: String,
    val results: List<AgentResult>,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val durationMillis: Long,
    val failedRoles: List<AgentRole>
) {
    val totalEstimatedTokens: Long get() = totalPromptTokens + totalCompletionTokens
}

/** Shared, run-scoped context/artifact bus. Writes are thread-safe for parallel agents. */
class SharedProjectContext(
    val projectPath: String,
    val task: String,
    val userContext: String
) {
    private val artifacts = ConcurrentHashMap<String, String>()

    fun publish(key: String, value: String) {
        require(key.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(value.length <= 100_000)
        artifacts[key] = value
    }

    fun read(key: String): String? = artifacts[key]
    fun snapshot(): Map<String, String> = artifacts.toMap()
}
