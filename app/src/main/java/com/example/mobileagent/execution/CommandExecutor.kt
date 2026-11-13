package com.example.mobileagent.execution

import com.example.mobileagent.security.AuditRecorder
import com.example.mobileagent.security.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class ExecutionStream { STDOUT, STDERR }

class CommandExecutor(
    private val workspace: WorkspaceManager,
    private val policy: CommandPolicy,
    private val approvals: ExecutionApprovalStore,
    private val audit: AuditRecorder,
    private val maxOutputBytes: Int = 256 * 1024
) {
    init { require(maxOutputBytes in 1024..2_000_000) }

    /**
     * Executes only an approved exact proposal. No shell is invoked.
     * Output chunks are delivered from IO threads; callers should dispatch UI updates
     * to the main thread. Captured output remains bounded even when callbacks are used.
     */
    suspend fun executeApproved(
        proposal: CommandProposal,
        approval: CommandApproval,
        onOutput: (ExecutionStream, String) -> Unit = { _, _ -> }
    ): ExecutionResult {
        approvals.consume(proposal, approval)
        val argv = withContext(Dispatchers.IO) { policy.resolve(proposal) }
        val cwd = if (proposal.workingDirectory.isBlank()) workspace.root
            else File(workspace.root, proposal.workingDirectory).canonicalFile
        require(cwd.toPath().startsWith(workspace.root.canonicalFile.toPath()) && cwd.isDirectory)

        val process = withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(argv).directory(cwd).redirectErrorStream(false)
            val environment = builder.environment()
            environment.clear()
            environment["PATH"] = "/system/bin:/system/xbin:${File(workspace.root, ".agent-tools/bin").absolutePath}"
            environment["HOME"] = workspace.root.absolutePath
            environment["TMPDIR"] = File(workspace.root, ".agent-tmp").apply { mkdirs() }.absolutePath
            environment["LANG"] = "C"
            environment["LC_ALL"] = "C"
            builder.start()
        }

        val started = System.nanoTime()
        try {
            return coroutineScope {
                val out = async(Dispatchers.IO) {
                    readBounded(process.inputStream, maxOutputBytes) { chunk ->
                        onOutput(ExecutionStream.STDOUT, chunk)
                    }
                }
                val err = async(Dispatchers.IO) {
                    readBounded(process.errorStream, maxOutputBytes) { chunk ->
                        onOutput(ExecutionStream.STDERR, chunk)
                    }
                }

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(proposal.timeoutSeconds)
                while (process.isAlive && System.nanoTime() < deadline) delay(50)
                val completed = !process.isAlive
                if (!completed) {
                    process.destroy()
                    if (!withContext(Dispatchers.IO) { process.waitFor(500, TimeUnit.MILLISECONDS) }) process.destroyForcibly()
                }
                val stdout = out.await()
                val stderr = err.await()
                val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val result = ExecutionResult(
                    proposalId = proposal.id,
                    exitCode = if (completed) process.exitValue() else null,
                    timedOut = !completed,
                    cancelled = false,
                    stdout = stdout.text,
                    stderr = stderr.text,
                    stdoutTruncated = stdout.truncated,
                    stderrTruncated = stderr.truncated,
                    durationMillis = duration
                )
                audit.record(
                    "execute_command",
                    proposal.workingDirectory,
                    "command=${proposal.executable};exit=${result.exitCode};timeout=${result.timedOut};durationMs=$duration"
                )
                result
            }
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            audit.record("cancel_command", proposal.workingDirectory, "command=${proposal.executable}")
            throw cancelled
        } catch (failure: Throwable) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            audit.record("execute_command_failed", proposal.workingDirectory, failure.message ?: "unknown")
            throw failure
        }
    }

    private data class BoundedText(val text: String, val truncated: Boolean)

    private fun readBounded(
        input: InputStream,
        limit: Int,
        onChunk: (String) -> Unit
    ): BoundedText {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(4096)
        var total = 0
        var truncated = false
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = limit - total
                if (remaining > 0) {
                    val kept = minOf(count, remaining)
                    output.write(buffer, 0, kept)
                    total += kept
                    if (kept > 0) onChunk(String(buffer, 0, kept, Charsets.UTF_8))
                }
                if (count > remaining) truncated = true
            }
        }
        return BoundedText(output.toString(Charsets.UTF_8.name()), truncated)
    }
}
