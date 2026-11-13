package com.example.mobileagent.agent.multi

import org.junit.Assert.*
import org.junit.Test

class SharedProjectContextTest {
    @Test fun artifactsAreVisibleAcrossHandoffs() {
        val context = SharedProjectContext("/workspace/repo", "Implement feature", "Kotlin")
        context.publish("plan", "Create repository and tests")
        assertEquals("Create repository and tests", context.read("plan"))
        assertEquals("Create repository and tests", context.snapshot()["plan"])
    }

    @Test fun invalidArtifactKeysAndOversizedValuesAreRejected() {
        val context = SharedProjectContext("/workspace", "Task", "")
        assertThrows(IllegalArgumentException::class.java) { context.publish("../secret", "value") }
        assertThrows(IllegalArgumentException::class.java) { context.publish("large", "x".repeat(100_001)) }
    }
}
