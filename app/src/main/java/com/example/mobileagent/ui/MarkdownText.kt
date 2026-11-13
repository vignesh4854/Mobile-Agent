package com.example.mobileagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablesPlugin

/** Shared Markdown renderer for normal chat, streaming output and multi-agent results. */
@Composable
fun MarkdownText(markdown: String, showCopy: Boolean = true) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablesPlugin.create(context))
            .build()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showCopy && markdown.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { copyToClipboard(context, markdown) }) {
                    Text("Copy response")
                }
            }
        }

        // A native TextView keeps Markwon's CommonMark rendering and Android text selection.
        // The surrounding horizontal scroll allows wide Markdown tables/code lines to remain usable.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { viewContext ->
                    TextView(viewContext).apply {
                        textSize = 16f
                        setTextColor(textColor)
                        setTextIsSelectable(true)
                        includeFontPadding = true
                        setLineSpacing(0f, 1.12f)
                    }
                },
                update = { textView ->
                    textView.setTextColor(textColor)
                    markwon.setMarkdown(textView, markdown)
                }
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService<ClipboardManager>()
    if (clipboard == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Assistant response", text))
    Toast.makeText(context, "Response copied", Toast.LENGTH_SHORT).show()
}
