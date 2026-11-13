package com.example.mobileagent.patch

import org.junit.Assert.*
import org.junit.Test

class UnifiedPatchParserTest {
    @Test fun parsesSingleHunk() {
        val patch = """--- a/hello.txt
            |+++ b/hello.txt
            |@@ -1,2 +1,2 @@
            | hello
            |-world
            |+agent
        """.trimMargin()
        val parsed = UnifiedPatchParser.parse(patch)
        assertEquals("hello.txt", parsed.relativePath)
        assertEquals(1, parsed.hunks.size)
        assertEquals(2, parsed.hunks.single().oldCount)
        assertEquals(2, parsed.hunks.single().newCount)
    }

    @Test fun rejectsTraversal() {
        val patch = """--- a/../secret
            |+++ b/../secret
            |@@ -1 +1 @@
            |-old
            |+new
        """.trimMargin()
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedPatchParser.parse(patch)
        }
    }

    @Test fun rejectsCountMismatch() {
        val patch = """--- a/a.txt
            |+++ b/a.txt
            |@@ -1,2 +1,2 @@
            |-old
            |+new
        """.trimMargin()
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedPatchParser.parse(patch)
        }
    }
}
