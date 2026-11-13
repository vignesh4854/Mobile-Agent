package com.example.mobileagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.mobileagent.patch.DiffKind
import com.example.mobileagent.patch.PatchPreview

/** Reusable side-by-side preview. The parent must call applyApproved only after approval. */
@Composable
fun PatchReviewCard(
    preview: PatchPreview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review changes", style = MaterialTheme.typography.titleMedium)
            Text(preview.relativePath, style = MaterialTheme.typography.labelLarge)
            Text(
                if (preview.beforeExists) "Existing file · hash ${preview.beforeSha256.take(12)}"
                else "New file · empty baseline",
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text("Before", style = MaterialTheme.typography.labelMedium)
                    preview.rows.forEach { row ->
                        val removed = row.kind == DiffKind.REMOVE ||
                            (row.leftText != null && row.rightText != null && row.leftText != row.rightText)
                        Text(
                            "${row.leftLineNumber ?: ""}  ${row.leftText.orEmpty()}",
                            modifier = Modifier.background(
                                if (removed) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surface
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text("After", style = MaterialTheme.typography.labelMedium)
                    preview.rows.forEach { row ->
                        val added = row.kind == DiffKind.ADD &&
                            (row.leftText == null || row.rightText == null || row.leftText != row.rightText)
                        Text(
                            "${row.rightLineNumber ?: ""}  ${row.rightText.orEmpty()}",
                            modifier = Modifier.background(
                                if (added) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve patch") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}
