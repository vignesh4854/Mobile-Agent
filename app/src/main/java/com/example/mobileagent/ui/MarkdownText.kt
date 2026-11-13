package com.example.mobileagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(markdown: String) {
    Column {
        var codeMode = false
        val code = StringBuilder()
        val normal = StringBuilder()
        fun flushNormal() {
            if (normal.isNotEmpty()) {
                Text(normal.toString().trimEnd(), style = MaterialTheme.typography.bodyMedium)
                normal.clear()
            }
        }
        markdown.lines().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (!codeMode) { flushNormal(); codeMode = true }
                else {
                    Text(code.toString().trimEnd(), modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFF0F1F3), RoundedCornerShape(8.dp)).padding(10.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    code.clear(); codeMode = false
                }
            } else if (codeMode) code.append(line).append('\n')
            else normal.append(line).append('\n')
        }
        if (codeMode) Text(code.toString(), modifier = Modifier.fillMaxWidth()
            .background(Color(0xFFF0F1F3), RoundedCornerShape(8.dp)).padding(10.dp),
            fontFamily = FontFamily.Monospace)
        flushNormal()
    }
}
