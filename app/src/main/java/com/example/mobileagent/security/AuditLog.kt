package com.example.mobileagent.security

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuditLog(context: Context) : AuditRecorder {
    private val file = File(context.filesDir, "agent-audit.jsonl")

    @Synchronized
    override fun record(tool: String, path: String, outcome: String) {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val line = "{\"time\":\"${esc(time)}\",\"tool\":\"${esc(tool)}\",\"path\":\"${esc(path)}\",\"outcome\":\"${esc(outcome.take(500))}\"}"
        file.appendText(line + "\n", Charsets.UTF_8)
    }
}
