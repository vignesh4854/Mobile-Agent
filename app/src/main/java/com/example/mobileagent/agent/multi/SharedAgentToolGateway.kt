package com.example.mobileagent.agent.multi

import com.example.mobileagent.agent.ToolGateway
import com.example.mobileagent.agent.ToolOutcome
import com.example.mobileagent.agent.runtime.ToolPermission
import com.example.mobileagent.agent.runtime.ToolRegistry
import com.example.mobileagent.model.ToolCallRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentToolRequest(val call: ToolCallRequest, val role: AgentRole, val permission: ToolPermission)
data class AgentToolResult(val text: String, val approved: Boolean = false)

/**
 * Shared policy gateway for every agent. Read-only tools execute directly; mutation proposals
 * require a user approval callback. Tool execution is serialized to keep approvals unambiguous.
 */
class SharedAgentToolGateway(
    private val gateway: ToolGateway,
    private val registry: ToolRegistry,
    private val braveKey: () -> String,
    private val githubToken: () -> String,
    private val githubOwner: () -> String,
    private val githubRepo: () -> String,
    private val allowLocalGit: Boolean,
    private val requestApproval: suspend (AgentToolRequest, String) -> Boolean
) {
    private val mutex = Mutex()

    suspend fun execute(role: AgentRole, call: ToolCallRequest): AgentToolResult = mutex.withLock {
        val definition = registry.find(call.name)
            ?: return@withLock AgentToolResult("ERROR: Tool is not registered.")
        val localGitTools = setOf(
            "git_status", "git_diff_summary", "git_branches",
            "propose_git_pull", "propose_git_push", "propose_git_commit"
        )
        if (!allowLocalGit && call.name in localGitTools) {
            return@withLock AgentToolResult("ERROR: Local Git tools are disabled because this project is not the configured workspace/repository directory.")
        }
        when (definition.permission) {
            ToolPermission.BLOCKED ->
                AgentToolResult("ERROR: ${call.name} is blocked by the shared tool policy.")
            ToolPermission.READ_ONLY -> {
                val outcome = gateway.executeReadOnly(
                    call, braveKey(), githubToken(), githubOwner(), githubRepo()
                )
                when (outcome) {
                    is ToolOutcome.ReadResult -> AgentToolResult(outcome.text.take(12_000))
                    is ToolOutcome.Error -> AgentToolResult("ERROR: ${outcome.message}")
                    else -> AgentToolResult("ERROR: Unexpected read-only tool result.")
                }
            }
            ToolPermission.USER_APPROVAL -> executeApprovedMutation(role, call)
        }
    }

    private suspend fun executeApprovedMutation(role: AgentRole, call: ToolCallRequest): AgentToolResult {
        val proposal = when (call.name) {
            "propose_write_file" -> when (val prepared = gateway.prepareWrite(call)) {
                is ToolOutcome.WriteProposal -> prepared
                is ToolOutcome.Error -> return AgentToolResult("ERROR: ${prepared.message}")
                else -> return AgentToolResult("ERROR: Could not prepare file change.")
            }
            else -> when (val prepared = gateway.prepareAction(call)) {
                is ToolOutcome.ActionProposal -> prepared
                is ToolOutcome.Error -> return AgentToolResult("ERROR: ${prepared.message}")
                else -> return AgentToolResult("ERROR: Could not prepare GitHub action.")
            }
        }
        val summary = when (proposal) {
            is ToolOutcome.WriteProposal -> "Agent ${role.label} proposes replacing ${proposal.path}.\n\n${proposal.diff.take(5000)}"
            is ToolOutcome.ActionProposal -> "Agent ${role.label} proposes: ${proposal.summary}"
            else -> "Agent requests an operation."
        }
        val approved = requestApproval(AgentToolRequest(call, role, ToolPermission.USER_APPROVAL), summary)
        if (!approved) return AgentToolResult("User rejected the proposed operation. It was not executed.")
        return when (proposal) {
            is ToolOutcome.WriteProposal -> {
                val result = gateway.approve(proposal)
                AgentToolResult("User approved and applied ${result.relativePath}.", approved = true)
            }
            is ToolOutcome.ActionProposal -> {
                // The legacy stage-all commit tool is blocked in ToolRegistry before this point.
                val result = gateway.executeApprovedAction(
                    proposal, githubToken(), githubOwner(), githubRepo()
                )
                AgentToolResult("User approved action result: $result", approved = true)
            }
            else -> AgentToolResult("ERROR: Unsupported approval proposal.")
        }
    }
}
