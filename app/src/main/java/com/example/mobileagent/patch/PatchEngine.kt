package com.example.mobileagent.patch

import com.example.mobileagent.security.PendingEdit
import com.example.mobileagent.security.WorkspaceFiles
import java.util.UUID

/**
 * Generates previews without mutating files. Applying a patch requires an explicit caller
 * approval and rechecks the original file hash immediately before writing.
 */
class PatchEngine(private val files: WorkspaceFiles) {

    fun preview(unifiedDiff: String): PatchPreview {
        val patch = UnifiedPatchParser.parse(unifiedDiff)
        val snapshot = files.snapshot(patch.relativePath)
        val proposed = applyHunks(snapshot.content, patch.hunks)
        return PatchPreview(
            relativePath = patch.relativePath,
            beforeSha256 = snapshot.sha256,
            beforeExists = snapshot.exists,
            originalText = snapshot.content,
            proposedText = proposed,
            rows = PatchDiffEngine.rows(snapshot.content, proposed)
        )
    }

    /** Must only be called after the user approves the exact preview. */
    fun applyApproved(unifiedDiff: String, expectedBeforeSha256: String): PatchApplyResult {
        val patch = preview(unifiedDiff)
        require(patch.beforeSha256 == expectedBeforeSha256) {
            "File changed after preview. Generate a fresh preview before approval."
        }
        val change = files.applyApprovedEdit(
            PendingEdit(patch.relativePath, expectedBeforeSha256, patch.proposedText)
        )
        val transaction = AppliedPatchTransaction(
            id = UUID.randomUUID().toString(),
            files = listOf(
                AppliedPatchFile(
                    relativePath = change.relativePath,
                    backupPath = change.backupPath,
                    beforeSha256 = expectedBeforeSha256,
                    afterSha256 = change.newSha256
                )
            )
        )
        return PatchApplyResult(patch, transaction)
    }

    /** Undo refuses to overwrite any edits made after the patch was applied. */
    fun undo(transaction: AppliedPatchTransaction): String {
        require(transaction.files.isNotEmpty()) { "Nothing to undo." }
        transaction.files.asReversed().forEach { change ->
            files.restoreBackup(
                backupPath = change.backupPath,
                relativePath = change.relativePath,
                expectedCurrentSha256 = change.afterSha256
            )
        }
        return "Undid patch transaction ${transaction.id}."
    }

    private fun applyHunks(original: String, hunks: List<UnifiedHunk>): String {
        val lines = original.split("\n").toMutableList()
        var offset = 0
        var previousEnd = 0
        for (hunk in hunks) {
            val expected = (if (hunk.oldStart == 0) 0 else hunk.oldStart - 1) + offset
            require(expected >= previousEnd && expected <= lines.size) {
                "Hunks are out of order or outside the file."
            }
            val oldLines = hunk.lines.filter { it.kind != DiffKind.ADD }.map { it.text }
            val newLines = hunk.lines.filter { it.kind != DiffKind.REMOVE }.map { it.text }
            val position = if (oldLines.isEmpty()) {
                expected
            } else {
                val exactAtExpected = expected + oldLines.size <= lines.size &&
                    lines.subList(expected, expected + oldLines.size) == oldLines
                if (exactAtExpected) expected else {
                    val matches = (0..(lines.size - oldLines.size).coerceAtLeast(-1))
                        .filter { candidate ->
                            candidate >= previousEnd &&
                                lines.subList(candidate, candidate + oldLines.size) == oldLines
                        }
                    require(matches.size == 1) {
                        "Patch context did not match uniquely. Re-read the file and regenerate the patch."
                    }
                    matches.single()
                }
            }
            require(position >= previousEnd) { "Patch hunks overlap." }
            repeat(oldLines.size) { lines.removeAt(position) }
            lines.addAll(position, newLines)
            offset += newLines.size - oldLines.size
            previousEnd = position + newLines.size
        }
        return lines.joinToString("\n")
    }
}
