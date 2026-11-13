package com.example.mobileagent.patch

import com.example.mobileagent.security.AuditRecorder
import com.example.mobileagent.security.WorkspaceFiles
import com.example.mobileagent.security.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatchEngineApplyTest {
    @Test fun previewDoesNotMutateAndApprovedApplyCanUndo() {
        val root = Files.createTempDirectory("patch-test").toFile()
        try {
            val workspace = WorkspaceManager(root)
            val audit = AuditRecorder { _, _, _ -> }
            val files = WorkspaceFiles(workspace, audit)
            File(workspace.root, "sample.txt").writeText("alpha\nbeta\ngamma")
            val engine = PatchEngine(files)
            val patch = """--- a/sample.txt
                |+++ b/sample.txt
                |@@ -1,3 +1,3 @@
                | alpha
                |-beta
                |+BETA
                | gamma
            """.trimMargin()
            val preview = engine.preview(patch)
            assertEquals("alpha\nbeta\ngamma", File(workspace.root, "sample.txt").readText())
            val applied = engine.applyApproved(patch, preview.beforeSha256)
            assertEquals("alpha\nBETA\ngamma", File(workspace.root, "sample.txt").readText())
            engine.undo(applied.transaction)
            assertEquals("alpha\nbeta\ngamma", File(workspace.root, "sample.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesStalePreview() {
        val root = Files.createTempDirectory("patch-stale-test").toFile()
        try {
            val workspace = WorkspaceManager(root)
            val files = WorkspaceFiles(workspace, AuditRecorder { _, _, _ -> })
            File(workspace.root, "sample.txt").writeText("before")
            val engine = PatchEngine(files)
            val patch = """--- a/sample.txt
                |+++ b/sample.txt
                |@@ -1 +1 @@
                |-before
                |+after
            """.trimMargin()
            val preview = engine.preview(patch)
            File(workspace.root, "sample.txt").writeText("changed elsewhere")
            assertThrows(IllegalArgumentException::class.java) {
                engine.applyApproved(patch, preview.beforeSha256)
            }
        } finally {
            root.deleteRecursively()
        }
    }

}
