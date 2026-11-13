package com.example.mobileagent.execution

import com.example.mobileagent.security.WorkspaceManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Resolves only explicit executable names or workspace-relative executable paths.
 * Commands are passed as argv directly; no shell is ever inserted.
 */
class CommandPolicy(private val workspace: WorkspaceManager) {
    private val systemCommands = setOf(
        "pwd", "ls", "cat", "head", "tail", "wc", "grep", "find", "env", "id",
        "uname", "which", "date", "git"
    )
    private val systemRoots = listOf(File("/system/bin"), File("/system/xbin"))

    fun createProposal(
        executable: String,
        arguments: List<String>,
        workingDirectory: String = "",
        timeoutSeconds: Long = 60,
        purpose: String
    ): CommandProposal {
        require(executable.isNotBlank() && executable.length <= 240)
        require(arguments.size <= 128 && arguments.sumOf { it.length } <= 32_000)
        require(arguments.none { it.indexOf('\u0000') >= 0 }) { "NUL bytes are not allowed." }
        require(timeoutSeconds in 1..120) { "Timeout must be between 1 and 120 seconds." }
        require(purpose.isNotBlank() && purpose.length <= 500)
        val cwd = resolveWorkingDirectory(workingDirectory)
        resolveExecutable(executable)
        return CommandProposal(
            id = UUID.randomUUID().toString(),
            executable = executable,
            arguments = arguments.toList(),
            workingDirectory = relative(cwd),
            timeoutSeconds = timeoutSeconds,
            purpose = purpose
        )
    }

    fun resolve(proposal: CommandProposal): List<String> {
        val cwd = resolveWorkingDirectory(proposal.workingDirectory)
        require(relative(cwd) == proposal.workingDirectory) { "Working directory changed." }
        return listOf(resolveExecutable(proposal.executable).absolutePath) + proposal.arguments
    }

    private fun resolveExecutable(name: String): File {
        require(!name.contains('\u0000')) { "Invalid executable." }
        val candidate = if (name.startsWith("./") || name.contains('/')) {
            val normalized = name.removePrefix("./")
            require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) {
                "Executable path must be workspace-relative."
            }
            val file = File(workspace.root, normalized).canonicalFile
            require(file.toPath().startsWith(workspace.root.canonicalFile.toPath())) {
                "Executable escapes workspace."
            }
            file
        } else {
            require(name.matches(Regex("[A-Za-z0-9_.+-]{1,80}"))) { "Invalid executable name." }
            require(name in systemCommands || File(workspace.root, ".agent-tools/bin/$name").exists()) {
                "Command is not in the permitted command registry: $name"
            }
            val local = File(workspace.root, ".agent-tools/bin/$name")
            if (local.exists()) local.canonicalFile
            else systemRoots.asSequence().map { File(it, name) }.firstOrNull { it.exists() }?.canonicalFile
                ?: throw IllegalArgumentException("Executable not installed: $name")
        }
        require(candidate.isFile && candidate.canExecute()) { "Executable is missing or not executable." }
        if (candidate.toPath().startsWith(workspace.root.canonicalFile.toPath())) {
            require(candidate.canonicalFile.toPath().startsWith(File(workspace.root, ".agent-tools").canonicalFile.toPath()) ||
                name.startsWith("./")) { "Workspace executables must be installed in .agent-tools/bin or be a project wrapper." }
        } else {
            require(systemRoots.any { candidate.toPath().startsWith(it.toPath()) }) {
                "Executable is outside permitted Android system directories."
            }
        }
        return candidate
    }

    private fun resolveWorkingDirectory(path: String): File {
        require(!path.startsWith("/") && !path.startsWith("\\\\")) { "Absolute working directories are blocked." }
        require(path.split('/', '\\').none { it == ".." }) { "Parent traversal is blocked." }
        val dir = if (path.isBlank()) workspace.root.canonicalFile else File(workspace.root, path).canonicalFile
        require(dir.toPath().startsWith(workspace.root.canonicalFile.toPath()) && dir.isDirectory) {
            "Working directory must exist inside the project workspace."
        }
        return dir
    }

    private fun relative(file: File): String =
        workspace.root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
}

internal fun proposalFingerprint(proposal: CommandProposal): String {
    val canonical = listOf(
        proposal.id, proposal.executable, proposal.workingDirectory,
        proposal.timeoutSeconds.toString(), proposal.purpose
    ).plus(proposal.arguments).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
