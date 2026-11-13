package com.example.mobileagent.patch

/**
 * Bounded LCS diff. The limits prevent a large or adversarial file from exhausting memory.
 */
object PatchDiffEngine {
    private const val MAX_LINES = 2_000
    private const val MAX_CELLS = 1_500_000

    private data class Op(val kind: DiffKind, val text: String)

    fun rows(before: String, after: String): List<PatchPreviewRow> {
        val a = before.split("\n")
        val b = after.split("\n")
        require(a.size <= MAX_LINES && b.size <= MAX_LINES) {
            "Diff is limited to $MAX_LINES lines per file."
        }
        require(a.size.toLong() * b.size.toLong() <= MAX_CELLS) {
            "Diff is too large to compare safely."
        }
        val width = b.size + 1
        val dp = IntArray((a.size + 1) * width)
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i * width + j] = if (a[i] == b[j]) {
                    1 + dp[(i + 1) * width + j + 1]
                } else {
                    maxOf(dp[(i + 1) * width + j], dp[i * width + j + 1])
                }
            }
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops += Op(DiffKind.CONTEXT, a[i]); i++; j++ }
                dp[(i + 1) * width + j] >= dp[i * width + j + 1] -> {
                    ops += Op(DiffKind.REMOVE, a[i]); i++
                }
                else -> { ops += Op(DiffKind.ADD, b[j]); j++ }
            }
        }
        while (i < a.size) { ops += Op(DiffKind.REMOVE, a[i]); i++ }
        while (j < b.size) { ops += Op(DiffKind.ADD, b[j]); j++ }

        val result = mutableListOf<PatchPreviewRow>()
        var oldLine = 1
        var newLine = 1
        var cursor = 0
        while (cursor < ops.size) {
            val op = ops[cursor]
            if (op.kind == DiffKind.CONTEXT) {
                result += PatchPreviewRow(oldLine, op.text, newLine, op.text, DiffKind.CONTEXT)
                oldLine++; newLine++; cursor++
                continue
            }
            val removed = mutableListOf<String>()
            val added = mutableListOf<String>()
            while (cursor < ops.size && ops[cursor].kind != DiffKind.CONTEXT) {
                val current = ops[cursor++]
                if (current.kind == DiffKind.REMOVE) removed += current.text else added += current.text
            }
            val paired = maxOf(removed.size, added.size)
            for (k in 0 until paired) {
                val hasOld = k < removed.size
                val hasNew = k < added.size
                result += PatchPreviewRow(
                    if (hasOld) oldLine++ else null,
                    removed.getOrNull(k),
                    if (hasNew) newLine++ else null,
                    added.getOrNull(k),
                    if (hasOld && hasNew) DiffKind.ADD else if (hasOld) DiffKind.REMOVE else DiffKind.ADD
                )
            }
        }
        return result
    }
}
