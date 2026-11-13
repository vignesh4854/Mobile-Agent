package com.example.mobileagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileagent.git.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitPanel(context: Context) {
    val git = remember { GitRepositoryManager(context) }
    val approvals = remember { GitOperationApprovalStore() }
    val scope = rememberCoroutineScope()
    var repoUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("Update project") }
    var authorName by remember { mutableStateOf("") }
    var authorEmail by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Clone a repository or inspect the local repository.") }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var stagedDiff by remember { mutableStateOf("") }
    var unstagedDiff by remember { mutableStateOf("") }
    var branchList by remember { mutableStateOf(emptyList<String>()) }
    var pendingProposal by remember { mutableStateOf<GitOperationProposal?>(null) }

    fun launchTask(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { output = block() }
            catch (e: Exception) { output = e.message ?: "Git operation failed." }
            finally { busy = false }
        }
    }

    fun paths(): List<String> = selectedPaths.lines()
        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun propose(operation: GitOperation, description: String, parameters: List<String>) {
        pendingProposal = approvals.propose(operation, description, parameters)
    }

    fun refreshStatus(): String {
        val s = git.status()
        statusText = buildString {
            appendLine("Branch: ${s.branch}")
            appendLine("Staged additions: ${s.stagedAdded.joinToString().ifBlank { "—" }}")
            appendLine("Staged modifications: ${s.stagedChanged.joinToString().ifBlank { "—" }}")
            appendLine("Staged removals: ${s.stagedRemoved.joinToString().ifBlank { "—" }}")
            appendLine("Unstaged modifications: ${s.unstagedModified.joinToString().ifBlank { "—" }}")
            appendLine("Unstaged removals: ${s.unstagedRemoved.joinToString().ifBlank { "—" }}")
            appendLine("Untracked: ${s.untracked.joinToString().ifBlank { "—" }}")
            append("Conflicts: ${s.conflicting.joinToString().ifBlank { "—" }}")
        }
        return statusText
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Text("Git & GitHub", style = MaterialTheme.typography.headlineMedium)
            Text("Selective staging, staged diff review and explicit confirmation for remote operations.")
        }
        item { OutlinedTextField(repoUrl, { repoUrl = it }, Modifier.fillMaxWidth(), label = { Text("GitHub HTTPS repository URL") }) }
        item { OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token (screen memory only)") }) }
        item {
            Button(enabled = !busy && repoUrl.isNotBlank(), onClick = {
                launchTask { withContext(Dispatchers.IO) { git.cloneRepository(repoUrl.trim(), token) } }
            }) { Text("Clone repository") }
        }
        item { HorizontalDivider() }
        item { Text("Repository status", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { launchTask { withContext(Dispatchers.IO) { refreshStatus() } } }) { Text("Refresh status") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask {
                        val pair = withContext(Dispatchers.IO) { git.stagedDiff() to git.unstagedDiff() }
                        stagedDiff = pair.first
                        unstagedDiff = pair.second
                        "Diffs refreshed."
                    }
                }) { Text("Refresh diffs") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { branchList = git.branches(); branchList.joinToString("\n") } }
                }) { Text("Branches") }
            }
        }
        if (statusText.isNotBlank()) item { ElevatedCard { Text(statusText, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
        item { Text("Select paths", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                selectedPaths, { selectedPaths = it }, Modifier.fillMaxWidth(),
                minLines = 2, label = { Text("One path per line or comma-separated") },
                supportingText = { Text("Only listed paths are staged or unstaged. No automatic stage-all.") }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && paths().isNotEmpty(), onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.stage(paths()) } }
                }) { Text("Stage selected") }
                OutlinedButton(enabled = !busy && paths().isNotEmpty(), onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.unstage(paths()) } }
                }) { Text("Unstage selected") }
            }
        }
        if (stagedDiff.isNotBlank()) item {
            Text("Staged diff", style = MaterialTheme.typography.titleMedium)
            ElevatedCard { Text(stagedDiff.take(16000), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        if (unstagedDiff.isNotBlank()) item {
            Text("Unstaged diff", style = MaterialTheme.typography.titleMedium)
            ElevatedCard { Text(unstagedDiff.take(16000), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        item { HorizontalDivider() }
        item { Text("Commit staged changes", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") }) }
        item { OutlinedTextField(authorName, { authorName = it }, Modifier.fillMaxWidth(), label = { Text("Git author name") }) }
        item { OutlinedTextField(authorEmail, { authorEmail = it }, Modifier.fillMaxWidth(), label = { Text("Git author email") }) }
        item {
            Button(enabled = !busy && commitMessage.isNotBlank() && authorName.isNotBlank() && authorEmail.isNotBlank(), onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val staged = withContext(Dispatchers.IO) { git.status().stagedPaths }
                            if (staged.isEmpty()) output = "Nothing staged. Stage selected paths first."
                            else propose(
                                GitOperation.COMMIT,
                                "Create commit '${commitMessage.trim()}' with ${staged.size} staged path(s): ${staged.joinToString()}",
                                listOf(commitMessage.trim(), authorName.trim(), authorEmail.trim()) + staged
                            )
                        } catch (e: Exception) {
                            output = e.message ?: "Could not inspect staged changes."
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text("Review & commit") }
        }
        item { HorizontalDivider() }
        item { Text("Remote operations", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && token.isNotBlank(), onClick = {
                    propose(GitOperation.PULL, "Pull remote changes into the current branch. This may create merge conflicts.", listOf("origin", "current-branch"))
                }) { Text("Review pull") }
                OutlinedButton(enabled = !busy && token.isNotBlank(), onClick = {
                    propose(GitOperation.PUSH, "Push the current branch to its configured remote.", listOf("current-branch", "configured-remote"))
                }) { Text("Review push") }
            }
        }
        item { HorizontalDivider() }
        item { Text("Branches", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(branchName, { branchName = it }, Modifier.fillMaxWidth(), label = { Text("Branch name") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && branchName.isNotBlank(), onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.createBranch(branchName.trim()) } }
                }) { Text("Create branch") }
                OutlinedButton(enabled = !busy && branchName.isNotBlank(), onClick = {
                    propose(GitOperation.CHECKOUT, "Switch to branch ${branchName.trim()}. Working tree must be clean.", listOf(branchName.trim()))
                }) { Text("Review checkout") }
            }
        }
        branchList.forEach { branch -> item { Text(branch, style = MaterialTheme.typography.bodySmall) } }
        item {
            Text(output, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Text(
                "Commit only includes staged paths. Pull, push and checkout require confirmation. Resolve conflicts before committing. Git token remains in screen memory and is not persisted.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    pendingProposal?.let { proposal ->
        GitApprovalDialog(
            title = when (proposal.operation) {
                GitOperation.COMMIT -> "Confirm commit"
                GitOperation.PULL -> "Confirm pull"
                GitOperation.PUSH -> "Confirm push"
                GitOperation.CHECKOUT -> "Confirm branch checkout"
            },
            message = proposal.description,
            confirmLabel = when (proposal.operation) {
                GitOperation.COMMIT -> "Commit"
                GitOperation.PULL -> "Pull"
                GitOperation.PUSH -> "Push"
                GitOperation.CHECKOUT -> "Checkout"
            },
            onDismiss = {
                approvals.reject(proposal.id)
                pendingProposal = null
            },
            onConfirm = {
                val approval = approvals.approve(proposal.id)
                approvals.consume(proposal, approval, proposal.operation)
                pendingProposal = null
                launchTask {
                    withContext(Dispatchers.IO) {
                        when (proposal.operation) {
                            GitOperation.COMMIT -> {
                                val result = git.commitStaged(proposal.parameters[0], proposal.parameters[1], proposal.parameters[2], proposal.parameters.drop(3).toSet())
                                "Committed ${result.abbreviatedId}: ${result.message}"
                            }
                            GitOperation.PULL -> git.pull(token)
                            GitOperation.PUSH -> git.push(token)
                            GitOperation.CHECKOUT -> git.checkoutBranch(proposal.parameters.single())
                        }
                    }
                }
            }
        )
    }
}
