package com.example.mobileagent.security

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class PendingEdit(val relativePath: String, val expectedSha256: String, val replacementText: String)
data class FileChangeResult(val relativePath: String, val backupPath: String, val newSha256: String)

/** Only this service should perform model-requested workspace file operations. */
class WorkspaceFiles(
    private val workspace: WorkspaceManager,
    private val audit: AuditLog,
    private val maxReadBytes: Long = 512 * 1024,
    private val maxWriteBytes: Long = 1024 * 1024
) {
    private val backups = File(workspace.root, ".agent-backups").apply { mkdirs() }

    fun list(relativeDirectory: String = ""): List<String> {
        val dir = resolve(relativeDirectory, allowRoot = true)
        require(dir.isDirectory)
        return dir.listFiles()?.filterNot { it.name == ".agent-backups" }
            ?.map { it.name + if (it.isDirectory) "/" else "" }?.sorted().orEmpty()
    }

    fun read(path: String): String {
        SensitiveFilePolicy.requireAllowed(path)
        val file = resolve(path)
        require(file.isFile && file.length() <= maxReadBytes) { "Missing file or read limit exceeded" }
        audit.record("read_file", path, "allowed")
        return file.readText(Charsets.UTF_8)
    }

    fun readRange(path: String, startLine: Int, endLine: Int): String {
        require(startLine >= 1 && endLine >= startLine && endLine - startLine <= 2000)
        return read(path).lineSequence().drop(startLine - 1).take(endLine - startLine + 1)
            .mapIndexed { i, line -> "${startLine + i}: $line" }.joinToString("\n")
    }

    fun searchText(query: String, directory: String = ""): List<String> {
        require(query.isNotBlank() && query.length <= 200)
        val base = resolve(directory, allowRoot = true)
        val hits = mutableListOf<String>()
        base.walkTopDown()
            .onEnter { it == base || (it.name != ".git" && it.name != ".agent-backups") }
            .filter { it.isFile && !SensitiveFilePolicy.isSensitive(base.toPath().relativize(it.toPath()).toString()) }
            .take(5000).forEach { file ->
                if (file.length() <= maxReadBytes) runCatching {
                    file.useLines { lines -> lines.forEachIndexed { i, line ->
                        if (line.contains(query, true) && hits.size < 500) {
                            hits += "${base.toPath().relativize(file.toPath())}:${i + 1}:$line"
                        }
                    } }
                }
            }
        audit.record("search_text", directory, "matches=${hits.size}")
        return hits
    }

    /** Call only after the UI has approved this exact proposal. */
    fun applyApprovedEdit(edit: PendingEdit): FileChangeResult {
        SensitiveFilePolicy.requireAllowed(edit.relativePath)
        require(edit.replacementText.toByteArray(Charsets.UTF_8).size <= maxWriteBytes)
        val target = resolve(edit.relativePath)
        require(!target.exists() || target.isFile)
        val oldHash = if (target.exists()) sha256(target.readBytes()) else sha256(ByteArray(0))
        require(oldHash == edit.expectedSha256) { "File changed since proposal; re-read before applying." }

        val backup = File(backups, "${System.currentTimeMillis()}-${UUID.randomUUID()}.bak")
        if (target.exists()) Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        else backup.writeText("__AGENT_NEW_FILE__")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(edit.replacementText, Charsets.UTF_8)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val newHash = sha256(target.readBytes())
        audit.record("apply_approved_edit", edit.relativePath, "backup=${backup.name};sha256=$newHash")
        return FileChangeResult(edit.relativePath, backup.absolutePath, newHash)
    }


    /** Restore a previously created backup into a workspace-relative file path. */
    fun restoreBackup(backupPath: String, relativePath: String): String {
        SensitiveFilePolicy.requireAllowed(relativePath)
        val backup = File(backupPath).canonicalFile
        require(backup.parentFile == backups.canonicalFile) { "Backup is outside the backup store" }
        require(backup.isFile) { "Backup not found" }
        val target = resolve(relativePath)
        val marker = backup.readText(Charsets.UTF_8).takeIf { backup.length() < 64 } == "__AGENT_NEW_FILE__"
        if (marker) {
            if (target.exists()) require(target.delete()) { "Could not remove newly created file" }
            audit.record("restore_backup", relativePath, "removed newly created file")
            return "Restored: removed newly created file $relativePath"
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.restore.tmp")
        Files.copy(backup.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        audit.record("restore_backup", relativePath, "backup=${backup.name}")
        return "Restored $relativePath from backup"
    }

    private fun resolve(path: String, allowRoot: Boolean = false): File {
        require(!path.startsWith("/") && !path.startsWith("\\")) { "Absolute paths are not allowed" }
        val parts = path.replace('\\', '/').split('/')
        require(parts.none { it == ".." }) { "Parent traversal is not allowed" }
        val candidate = if (path.isBlank()) workspace.root else File(workspace.root, path)
        val rootPath = workspace.root.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        require(candidatePath.startsWith(rootPath)) { "Path escapes workspace" }
        require(allowRoot || candidatePath != rootPath) { "A file path is required" }
        var current = workspace.root
        parts.filter { it.isNotBlank() && it != "." }.forEach {
            current = File(current, it)
            if (Files.isSymbolicLink(current.toPath())) throw SecurityException("Symlinks are blocked")
        }
        return candidate
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
