package com.example.mobileagent.execution

import org.junit.Assert.*
import org.junit.Test

class ExecutionApprovalStoreTest {
    private fun proposal() = CommandProposal(
        id = "p1", executable = "pwd", arguments = emptyList(),
        workingDirectory = "", timeoutSeconds = 10, purpose = "Inspect project directory"
    )

    @Test fun approvalIsOneShot() {
        val store = ExecutionApprovalStore()
        val proposal = proposal()
        store.register(proposal)
        val token = store.approve(proposal.id)
        store.consume(proposal, token)
        assertThrows(SecurityException::class.java) { store.consume(proposal, token) }
    }

    @Test fun changedArgumentsInvalidateApproval() {
        val store = ExecutionApprovalStore()
        val proposal = proposal()
        store.register(proposal)
        val token = store.approve(proposal.id)
        val changed = proposal.copy(arguments = listOf("secret"))
        assertThrows(IllegalArgumentException::class.java) { store.consume(changed, token) }
    }

    @Test fun rejectRemovesPendingProposal() {
        val store = ExecutionApprovalStore()
        val proposal = proposal()
        store.register(proposal)
        assertTrue(store.reject(proposal.id))
        assertThrows(IllegalStateException::class.java) { store.approve(proposal.id) }
    }
}
