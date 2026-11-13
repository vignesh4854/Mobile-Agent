package com.example.mobileagent.patch

import org.junit.Assert.*
import org.junit.Test

class PatchDiffEngineTest {
    @Test fun identifiesChangedLines() {
        val rows = PatchDiffEngine.rows("one\ntwo\nthree", "one\nTWO\nthree")
        assertEquals(3, rows.size)
        assertEquals(DiffKind.CONTEXT, rows[0].kind)
        assertEquals("two", rows[1].leftText)
        assertEquals("TWO", rows[1].rightText)
        assertEquals(DiffKind.CONTEXT, rows[2].kind)
    }

    @Test fun handlesAddedAndRemovedLines() {
        val rows = PatchDiffEngine.rows("a\nb", "a\nx\nb")
        assertTrue(rows.any { it.kind == DiffKind.ADD && it.rightText == "x" })
    }
}
