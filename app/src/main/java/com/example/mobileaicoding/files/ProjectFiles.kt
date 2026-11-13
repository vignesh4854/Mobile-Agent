package com.example.mobileaicoding.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader

class ProjectFiles(private val context: Context, private val treeUri: Uri) {
    private val root: DocumentFile get() = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("Selected project folder is no longer available")

    private fun clean(path: String): List<String> {
        val p = path.trim().replace('\\', '/')
        require(!p.startsWith("/")) { "Absolute paths are not allowed" }
        val parts = p.split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Parent-directory traversal is not allowed" }
        require(parts.all { !it.contains('\u0000') }) { "Invalid path" }
        return parts
    }

    private fun resolve(path: String, createParents: Boolean = false): DocumentFile {
        var current = root
        for ((index, part) in clean(path).withIndex()) {
            val last = index == clean(path).lastIndex
            var next = current.findFile(part)
            if (next == null && createParents && !last) next = current.createDirectory(part)
            current = next ?: throw IllegalArgumentException("Path not found: $path")
        }
        return current
    }

    fun list(path: String = ""): String {
        val dir = if (path.isBlank()) root else resolve(path)
        require(dir.isDirectory) { "Not a directory: $path" }
        return dir.listFiles().sortedBy { it.name?.lowercase() }.joinToString("\n") {
            val marker = if (it.isDirectory) "/" else ""
            "${it.name ?: "(unnamed)"}$marker"
        }.ifBlank { "(empty directory)" }
    }

    fun read(path: String): String {
        val file = resolve(path)
        require(file.isFile) { "Not a file: $path" }
        require(file.length() <= 1_000_000) { "File is larger than 1 MB; refusing to load into chat" }
        val input = context.contentResolver.openInputStream(file.uri) ?: error("Cannot open file")
        return input.bufferedReader().use(BufferedReader::readText)
    }

    fun write(path: String, content: String, create: Boolean = false) {
        val parts = clean(path)
        require(parts.isNotEmpty()) { "A file name is required" }
        require(content.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "File content exceeds 1 MB limit" }
        var parent = root
        parts.dropLast(1).forEach { segment ->
            var child = parent.findFile(segment)
            if (child == null) child = parent.createDirectory(segment)
            parent = child ?: error("Cannot create directory: $segment")
            require(parent.isDirectory) { "Not a directory: $segment" }
        }
        val name = parts.last()
        val existing = parent.findFile(name)
        if (existing == null && !create) throw IllegalArgumentException("File does not exist: $path")
        if (existing != null && !create) require(existing.isFile) { "Target is not a file" }
        if (existing != null && create) throw IllegalArgumentException("File already exists: $path")
        val target = existing ?: parent.createFile(mimeFor(name), name) ?: error("Cannot create file")
        context.contentResolver.openOutputStream(target.uri, "wt")?.bufferedWriter()?.use { it.write(content) }
            ?: error("Cannot write file")
    }

    fun delete(path: String) {
        val file = resolve(path)
        require(file.uri != root.uri) { "Cannot delete project root" }
        require(file.isFile) { "Only files can be deleted in V1" }
        require(file.delete()) { "Delete failed" }
    }

    fun searchFiles(query: String, max: Int = 100): String {
        require(query.isNotBlank()) { "Search query cannot be empty" }
        val results = mutableListOf<String>()
        fun walk(dir: DocumentFile, prefix: String) {
            if (results.size >= max) return
            dir.listFiles().forEach { f ->
                val name = f.name ?: return@forEach
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (name.contains(query, true)) results += relative
                if (f.isDirectory && !name.startsWith(".")) walk(f, relative)
            }
        }
        walk(root, "")
        return results.joinToString("\n").ifBlank { "No matching files" }
    }

    fun searchText(query: String, max: Int = 50): String {
        require(query.isNotBlank()) { "Search query cannot be empty" }
        val results = mutableListOf<String>()
        fun walk(dir: DocumentFile, prefix: String) {
            if (results.size >= max) return
            dir.listFiles().forEach { f ->
                val name = f.name ?: return@forEach
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (f.isDirectory && !name.startsWith(".")) walk(f, relative)
                else if (f.isFile && f.length() in 1..200_000 && name.substringAfterLast('.', "") in setOf("kt","java","xml","json","js","ts","tsx","jsx","py","md","txt","gradle","kts","html","css","yaml","yml")) {
                    runCatching { read(relative) }.getOrNull()?.lineSequence()?.forEachIndexed { i, line ->
                        if (line.contains(query, true) && results.size < max) results += "$relative:${i + 1}: ${line.take(240)}"
                    }
                }
            }
        }
        walk(root, "")
        return results.joinToString("\n").ifBlank { "No matching text found" }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"; "xml" -> "application/xml"; "html" -> "text/html"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "text/plain"
    }
}
