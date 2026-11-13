package com.example.mobileagent.execution

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One-shot approval bound to an immutable command proposal fingerprint. */
class ExecutionApprovalStore {
    private val pending = ConcurrentHashMap<String, CommandProposal>()
    private val approved = ConcurrentHashMap<String, CommandApproval>()

    fun register(proposal: CommandProposal) {
        pending[proposal.id] = proposal
    }

    fun approve(proposalId: String): CommandApproval {
        val proposal = pending.remove(proposalId)
            ?: throw IllegalStateException("Command proposal is no longer pending.")
        val token = CommandApproval(
            proposalId = proposal.id,
            fingerprint = proposalFingerprint(proposal),
            nonce = UUID.randomUUID().toString()
        )
        approved[token.nonce] = token
        return token
    }

    fun reject(proposalId: String): Boolean = pending.remove(proposalId) != null

    fun consume(proposal: CommandProposal, token: CommandApproval) {
        val stored = approved.remove(token.nonce)
            ?: throw SecurityException("Approval is missing, expired, or already used.")
        require(stored == token && token.proposalId == proposal.id) { "Approval does not match this command." }
        require(token.fingerprint == proposalFingerprint(proposal)) { "Command changed after approval." }
    }
}
