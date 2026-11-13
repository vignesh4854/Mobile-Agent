package com.example.mobileagent.git

import org.junit.Assert.*
import org.junit.Test

class GitOperationApprovalStoreTest {
    @Test fun approvalIsOneShotAndOperationBound() {
        val store = GitOperationApprovalStore()
        val proposal = store.propose(GitOperation.PUSH, "Push branch", listOf("main"))
        val token = store.approve(proposal.id)
        store.consume(proposal, token, GitOperation.PUSH)
        assertThrows(SecurityException::class.java) {
            store.consume(proposal, token, GitOperation.PUSH)
        }
    }

    @Test fun wrongOperationCannotConsumeApproval() {
        val store = GitOperationApprovalStore()
        val proposal = store.propose(GitOperation.PULL, "Pull branch", listOf("main"))
        val token = store.approve(proposal.id)
        assertThrows(IllegalArgumentException::class.java) {
            store.consume(proposal, token, GitOperation.PUSH)
        }
    }

    @Test fun rejectionRemovesProposal() {
        val store = GitOperationApprovalStore()
        val proposal = store.propose(GitOperation.COMMIT, "Commit staged", listOf("file.kt"))
        assertTrue(store.reject(proposal.id))
        assertThrows(IllegalStateException::class.java) { store.approve(proposal.id) }
    }
}
