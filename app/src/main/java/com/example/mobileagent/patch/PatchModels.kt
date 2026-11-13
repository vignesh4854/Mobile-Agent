package com.example.mobileagent.patch

enum class DiffKind { CONTEXT, ADD, REMOVE }

data class HunkLine(val kind: DiffKind, val text: String)

data class UnifiedHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<HunkLine>
)

data class UnifiedFilePatch(
    val relativePath: String,
    val hunks: List<UnifiedHunk>
)

data class PatchPreviewRow(
    val leftLineNumber: Int?,
    val leftText: String?,
    val rightLineNumber: Int?,
    val rightText: String?,
    val kind: DiffKind
)

data class PatchPreview(
    val relativePath: String,
    val beforeSha256: String,
    val beforeExists: Boolean,
    val originalText: String,
    val proposedText: String,
    val rows: List<PatchPreviewRow>
)

data class AppliedPatchFile(
    val relativePath: String,
    val backupPath: String,
    val beforeSha256: String,
    val afterSha256: String
)

data class AppliedPatchTransaction(
    val id: String,
    val files: List<AppliedPatchFile>
)

data class PatchApplyResult(
    val preview: PatchPreview,
    val transaction: AppliedPatchTransaction
)
