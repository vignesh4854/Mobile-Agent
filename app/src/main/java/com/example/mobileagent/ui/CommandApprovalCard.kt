package com.example.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.mobileagent.execution.CommandProposal
import com.example.mobileagent.execution.ExecutionResult

@Composable
fun CommandApprovalCard(
    proposal: CommandProposal,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approve command execution?", style = MaterialTheme.typography.titleMedium)
            Text(proposal.purpose, style = MaterialTheme.typography.bodyMedium)
            Text(
                proposal.displayCommand(),
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text("Directory: ${proposal.workingDirectory.ifBlank { "/" }}")
            Text("Timeout: ${proposal.timeoutSeconds} seconds")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve once") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}

@Composable
fun CommandOutputCard(result: ExecutionResult, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    result.timedOut -> "Command timed out"
                    result.cancelled -> "Command cancelled"
                    else -> "Exit code: ${result.exitCode}"
                },
                style = MaterialTheme.typography.titleSmall
            )
            Text("Duration: ${result.durationMillis} ms", style = MaterialTheme.typography.labelSmall)
            if (result.stdout.isNotBlank()) {
                Text("stdout", style = MaterialTheme.typography.labelMedium)
                Text(result.stdout + if (result.stdoutTruncated) "\n[output truncated]" else "",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (result.stderr.isNotBlank()) {
                Text("stderr", style = MaterialTheme.typography.labelMedium)
                Text(result.stderr + if (result.stderrTruncated) "\n[output truncated]" else "",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
