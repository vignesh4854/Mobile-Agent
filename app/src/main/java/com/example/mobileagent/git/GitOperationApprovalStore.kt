package com.example.mobileagent.git

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GitOperation { COMMIT, PULL, PUSH, CHECKOUT }

data class GitOperationProposal(
    val id: String,
    val operation: GitOperation,
    val description: String,
    val parameters: List<String>
)

data class GitOperationApproval(
    val proposalId: String,
    val fingerprint: String,
    val nonce: String
)

/** In-memory, one-shot approvals tied to the exact operation and parameters. */
class GitOperationApprovalStore {
    private val pending = ConcurrentHashMap<String, GitOperationProposal>()
    private val approved = ConcurrentHashMap<String, GitOperationApproval>()

    fun propose(operation: GitOperation, description: String, parameters: List<String>): GitOperationProposal {
        require(description.isNotBlank())
        require(parameters.size <= 256 && parameters.sumOf { it.length } <= 32_000)
        return GitOperationProposal(UUID.randomUUID().toString(), operation, description, parameters.toList())
            .also { pending[it.id] = it }
    }

    fun approve(proposalId: String): GitOperationApproval {
        val proposal = pending.remove(proposalId)
            ?: throw IllegalStateException("Git proposal is no longer pending.")
        return GitOperationApproval(proposal.id, fingerprint(proposal), UUID.randomUUID().toString())
            .also { approved[it.nonce] = it }
    }

    fun reject(proposalId: String): Boolean = pending.remove(proposalId) != null

    fun consume(proposal: GitOperationProposal, approval: GitOperationApproval, expected: GitOperation) {
        val stored = approved.remove(approval.nonce)
            ?: throw SecurityException("Approval is missing or already used.")
        require(stored == approval && approval.proposalId == proposal.id) { "Approval does not match this operation." }
        require(proposal.operation == expected && approval.fingerprint == fingerprint(proposal)) {
            "Git operation changed after approval."
        }
    }

    private fun fingerprint(proposal: GitOperationProposal): String {
        val data = (listOf(proposal.id, proposal.operation.name, proposal.description) + proposal.parameters)
            .joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
