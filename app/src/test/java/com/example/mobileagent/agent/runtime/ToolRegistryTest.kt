package com.example.mobileagent.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    @Test fun mutationAndExecutionToolsAreNotReadOnly() {
        val registry = ToolRegistry.v3Defaults()
        assertTrue(registry.allowedWithoutApproval("read_file"))
        assertFalse(registry.allowedWithoutApproval("propose_write_file"))
        assertEquals(ToolPermission.BLOCKED, registry.find("execute_command")?.permission)
        assertEquals(ToolPermission.BLOCKED, registry.find("propose_git_commit")?.permission)
    }
}
