package com.example.mobileagent.security

fun interface AuditRecorder {
    fun record(tool: String, path: String, outcome: String)
}
