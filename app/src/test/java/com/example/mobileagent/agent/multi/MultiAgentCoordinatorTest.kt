package com.example.mobileagent.agent.multi

import com.example.mobileagent.model.*
import com.example.mobileagent.provider.ApiProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MultiAgentCoordinatorTest {
    private fun profile(role: AgentRole) = AgentProfile(
        role,
        ApiConfig("https://example.test/v1", "chat/completions", "test-key", "test-model-${role.name}", ProtocolMode.OPENAI)
    )

    @Test fun runsPlannerCoderParallelReviewAndDebuggerThenFinalPlanner() = runBlocking {
        val activeParallel = AtomicInteger(0)
        val maxParallel = AtomicInteger(0)
        val provider = object : ApiProvider {
            override suspend fun send(config: ApiConfig, history: List<ChatMessage>, onText: (String) -> Unit): ProviderReply {
                val prompt = history.firstOrNull()?.content.orEmpty()
                val role = when {
                    prompt.contains("Produce the final consolidated report") -> "final"
                    prompt.contains("Mission: Decompose") -> "plan"
                    prompt.contains("Mission: Implement") -> "code"
                    prompt.contains("Mission: Independently review") -> "review"
                    prompt.contains("Mission: Diagnose") -> "debug"
                    else -> "final"
                }
                if (role == "review" || role == "debug") {
                    val active = activeParallel.incrementAndGet()
                    maxParallel.updateAndGet { old -> maxOf(old, active) }
                    delay(80)
                    activeParallel.decrementAndGet()
                }
                return ProviderReply("$role result", emptyList())
            }
        }
        val coordinator = MultiAgentCoordinator(provider)
        val events = mutableListOf<AgentTraceEvent>()
        val result = coordinator.run(
            AgentRole.entries.associateWith(::profile),
            "Build a feature",
            "/workspace/demo",
            "Kotlin project",
            executeTool = { _, _ -> "tool result" },
            onEvent = { events += it }
        )
        assertEquals(5, result.results.size)
        assertEquals("final result", result.finalOutput)
        assertTrue(maxParallel.get() >= 2)
        assertTrue(events.any { it.type == AgentTraceType.RUN_FINISHED })
        assertTrue(result.totalEstimatedTokens > 0)
    }

    @Test fun rejectsMissingProfiles() = runBlocking {
        val provider = object : ApiProvider {
            override suspend fun send(config: ApiConfig, history: List<ChatMessage>, onText: (String) -> Unit) =
                ProviderReply("unused", emptyList())
        }
        val profiles = AgentRole.entries.dropLast(1).associateWith(::profile)
        try {
            MultiAgentCoordinator(provider).run(profiles, "Task", "/workspace", "", { _, _ -> "" })
            fail("Expected missing-profile validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("four agent profiles"))
        }
    }
}
