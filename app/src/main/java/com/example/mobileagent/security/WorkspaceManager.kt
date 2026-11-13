package com.example.mobileagent.security

import java.io.File

class WorkspaceManager(filesDir: File) {
    val root: File = File(filesDir, "workspace").apply { mkdirs() }
    init { require(root.isDirectory) { "Could not initialize workspace" } }
}
