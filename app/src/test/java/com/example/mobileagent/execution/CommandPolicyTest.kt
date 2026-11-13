package com.example.mobileagent.execution

import com.example.mobileagent.security.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CommandPolicyTest {
    @Test fun rejectsShellAndUnknownCommands() {
        val root = Files.createTempDirectory("command-policy").toFile()
        try {
            val policy = CommandPolicy(WorkspaceManager(root))
            assertThrows(IllegalArgumentException::class.java) {
                policy.createProposal("sh", listOf("-c", "echo unsafe"), purpose = "test")
            }
            assertThrows(IllegalArgumentException::class.java) {
                policy.createProposal("made-up-command", emptyList(), purpose = "test")
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTraversalWorkingDirectory() {
        val root = Files.createTempDirectory("command-cwd").toFile()
        try {
            val policy = CommandPolicy(WorkspaceManager(root))
            assertThrows(IllegalArgumentException::class.java) {
                policy.createProposal("pwd", emptyList(), "../", purpose = "test")
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun enforcesTimeoutBounds() {
        val root = Files.createTempDirectory("command-timeout").toFile()
        try {
            val policy = CommandPolicy(WorkspaceManager(root))
            assertThrows(IllegalArgumentException::class.java) {
                policy.createProposal("pwd", emptyList(), timeoutSeconds = 1000, purpose = "test")
            }
        } finally { root.deleteRecursively() }
    }
}
