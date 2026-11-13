package com.example.mobileagent.patch

import com.example.mobileagent.security.SensitiveFilePolicy

/**
 * Parses the common unified-diff form. File renames and file deletions are intentionally
 * rejected; a new file is supported when the old side is /dev/null.
 */
object UnifiedPatchParser {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun parse(patchText: String): UnifiedFilePatch {
        require(patchText.isNotBlank() && patchText.length <= 1_000_000) {
            "Patch is empty or exceeds the 1 MB limit."
        }
        val lines = patchText.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        require(lines.size >= 3 && lines[0].startsWith("--- ") && lines[1].startsWith("+++ ")) {
            "Expected unified diff headers (--- and +++)."
        }
        val oldPath = normalizePath(lines[0].removePrefix("--- ").substringBefore('\t'))
        val newPath = normalizePath(lines[1].removePrefix("+++ ").substringBefore('\t'))
        require(newPath != "/dev/null") { "File deletion patches are not supported." }
        val path = if (newPath == "/dev/null") oldPath else newPath
        require(path.isNotBlank() && path != "/dev/null") { "Patch must target a workspace file." }
        if (oldPath != "/dev/null") {
            require(oldPath == path) { "Rename patches are not supported; target path must match." }
        }
        SensitiveFilePolicy.requireAllowed(path)

        val hunks = mutableListOf<UnifiedHunk>()
        var index = 2
        while (index < lines.size) {
            if (lines[index].isBlank()) { index++; continue }
            val match = hunkHeader.matchEntire(lines[index])
                ?: throw IllegalArgumentException("Invalid hunk header at patch line ${index + 1}.")
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifBlank { "1" }.toInt()
            index++
            val body = mutableListOf<HunkLine>()
            var oldSeen = 0
            var newSeen = 0
            while (index < lines.size && !lines[index].startsWith("@@ ")) {
                val line = lines[index]
                if (line == "\\ No newline at end of file") {
                    index++
                    continue
                }
                if (line.isEmpty() && index == lines.lastIndex) {
                    index++
                    continue
                }
                require(line.isNotEmpty()) { "Unexpected empty patch line at ${index + 1}." }
                when (line[0]) {
                    ' ' -> { body += HunkLine(DiffKind.CONTEXT, line.substring(1)); oldSeen++; newSeen++ }
                    '+' -> { body += HunkLine(DiffKind.ADD, line.substring(1)); newSeen++ }
                    '-' -> { body += HunkLine(DiffKind.REMOVE, line.substring(1)); oldSeen++ }
                    else -> throw IllegalArgumentException("Invalid patch line prefix at ${index + 1}.")
                }
                index++
                if (oldSeen == oldCount && newSeen == newCount) {
                    // Continue only if next line is another hunk; trailing blank is ignored above.
                    break
                }
            }
            require(oldSeen == oldCount && newSeen == newCount) {
                "Hunk line counts do not match its header."
            }
            hunks += UnifiedHunk(oldStart, oldCount, newStart, newCount, body)
        }
        require(hunks.isNotEmpty()) { "Patch contains no hunks." }
        return UnifiedFilePatch(path, hunks)
    }

    private fun normalizePath(raw: String): String {
        if (raw == "/dev/null") return raw
        val stripped = raw.removePrefix("a/").removePrefix("b/")
        require(!stripped.startsWith("/") && !stripped.startsWith("\\\\")) {
            "Absolute paths are not allowed."
        }
        require(stripped.split('/').none { it == ".." }) { "Parent traversal is not allowed." }
        return stripped
    }
}
