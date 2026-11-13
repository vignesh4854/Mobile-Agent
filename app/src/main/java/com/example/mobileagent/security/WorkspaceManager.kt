package com.example.mobileagent.security

import java.io.File

class WorkspaceManager(filesDir: File, requestedRoot: File? = null) {
    private val allowedRoot = File(filesDir, "workspace").canonicalFile
    val root: File = (requestedRoot ?: allowedRoot).canonicalFile.apply {
        require(toPath().startsWith(allowedRoot.toPath())) {
            "Workspace must remain inside app-private workspace storage."
        }
        mkdirs()
    }

    init { require(root.isDirectory) { "Could not initialize workspace" } }
}
