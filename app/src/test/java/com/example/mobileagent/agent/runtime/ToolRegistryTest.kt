package com.example.mobileagent.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test fun readOnlyToolsDoNotRequireApproval() {
        val registry = ToolRegistry.v3Defaults()
        assertTrue(registry.allowedWithoutApproval("read_file"))
    }
    @Test fun writesRequireApproval() {
        val registry = ToolRegistry.v3Defaults()
        assertFalse(registry.allowedWithoutApproval("propose_write_file"))
        assertNotNull(registry.find("propose_write_file"))
    }
    @Test fun terminalIsBlockedUntilRuntimeExists() {
        val registry = ToolRegistry.v3Defaults()
        assertFalse(registry.allowedWithoutApproval("execute_command"))
        assertTrue(registry.find("execute_command")?.permission == ToolPermission.BLOCKED)
    }
}
