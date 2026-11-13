package com.example.mobileagent.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceManagerTest {
    @Test fun requestedWorkspaceMustBeInsideAppWorkspace() {
        val filesDir = Files.createTempDirectory("workspace-root").toFile()
        try {
            val child = File(filesDir, "workspace/project")
            val manager = WorkspaceManager(filesDir, child)
            assertEquals(child.canonicalFile, manager.root)
            val outside = File(filesDir.parentFile, "outside-workspace")
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceManager(filesDir, outside)
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
