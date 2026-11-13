package com.example.mobileagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileagent.git.GitHubActionsClient
import com.example.mobileagent.git.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitPanel(context: Context) {
    val git = remember { GitRepositoryManager(context) }
    val actions = remember { GitHubActionsClient(context) }
    val scope = rememberCoroutineScope()
    var repoUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("Update project") }
    var authorName by remember { mutableStateOf("") }
    var authorEmail by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Clone a repository or inspect an existing local repository.") }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var diffText by remember { mutableStateOf("") }
    var branchList by remember { mutableStateOf(emptyList<String>()) }
    var runList by remember { mutableStateOf(emptyList<String>()) }
    var selectedRunId by remember { mutableStateOf("") }
    var jobList by remember { mutableStateOf(emptyList<String>()) }
    var selectedJobId by remember { mutableStateOf("") }
    var artifactList by remember { mutableStateOf(emptyList<String>()) }
    var actionOutput by remember { mutableStateOf("") }

    fun launchTask(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { output = block() }
            catch (e: Exception) { output = e.message ?: "Git operation failed." }
            finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Text("Git & GitHub", style = MaterialTheme.typography.headlineMedium)
            Text("Local Git runs inside the app-private repository folder.")
        }
        item { OutlinedTextField(repoUrl, { repoUrl = it }, Modifier.fillMaxWidth(), label = { Text("GitHub HTTPS repository URL") }) }
        item { OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token (kept in screen memory only)") }) }
        item {
            Button(enabled = !busy && repoUrl.isNotBlank(), onClick = {
                launchTask { withContext(Dispatchers.IO) { git.cloneRepository(repoUrl.trim(), token) } }
            }) { Text("Clone repository") }
        }
        item { HorizontalDivider() }
        item { Text("Local repository", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) {
                        val s = git.status()
                        statusText = "Branch: ${s.branch}\nAdded: ${s.added.joinToString()}\nChanged: ${s.changed.joinToString()}\nRemoved: ${s.removed.joinToString()}\nUntracked: ${s.untracked.joinToString()}\nConflicts: ${s.conflicting.joinToString()}"
                        statusText
                    } }
                }) { Text("Status") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { diffText = git.diff(); diffText } }
                }) { Text("Diff summary") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { branchList = git.branches(); branchList.joinToString("\n") } }
                }) { Text("Branches") }
            }
        }
        if (statusText.isNotBlank()) item { Text(statusText, style = MaterialTheme.typography.bodySmall) }
        if (diffText.isNotBlank()) item { Text(diffText, style = MaterialTheme.typography.bodySmall) }
        if (branchList.isNotEmpty()) items(branchList) { Text(it) }
        item { OutlinedTextField(branchName, { branchName = it }, Modifier.fillMaxWidth(), label = { Text("New / checkout branch name") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && branchName.isNotBlank(), onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.createBranch(branchName) } }
                }) { Text("Create branch") }
                OutlinedButton(enabled = !busy && branchName.isNotBlank(), onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.checkoutBranch(branchName) } }
                }) { Text("Checkout") }
            }
        }
        item { HorizontalDivider() }
        item { Text("Commit / sync", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") }) }
        item { OutlinedTextField(authorName, { authorName = it }, Modifier.fillMaxWidth(), label = { Text("Git author name") }) }
        item { OutlinedTextField(authorEmail, { authorEmail = it }, Modifier.fillMaxWidth(), label = { Text("Git author email") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.commit(commitMessage, authorName, authorEmail) } }
                }) { Text("Commit all") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.pull(token) } }
                }) { Text("Pull") }
                OutlinedButton(enabled = !busy, onClick = {
                    launchTask { withContext(Dispatchers.IO) { git.push(token) } }
                }) { Text("Push") }
            }
        }
        item { HorizontalDivider() }
        item { Text("GitHub Actions", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(owner, { owner = it }, Modifier.fillMaxWidth(), label = { Text("Repository owner") }) }
        item { OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("Repository name") }) }
        item {
            Button(enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank(), onClick = {
                launchTask {
                    val runs = actions.listRuns(owner.trim(), repo.trim(), token)
                    runList = runs.map { "${it.id} | ${it.name} | ${it.status}/${it.conclusion} | ${it.branch} | ${it.createdAt}" }
                    "Loaded ${runs.size} workflow runs."
                }
            }) { Text("Load workflow runs") }
        }
        items(runList) { row ->
            TextButton(onClick = { selectedRunId = row.substringBefore(" | ") }) { Text(row) }
        }
        item {
            OutlinedTextField(selectedRunId, { selectedRunId = it }, Modifier.fillMaxWidth(), label = { Text("Selected workflow run ID") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && selectedRunId.toLongOrNull() != null && token.isNotBlank(), onClick = {
                    launchTask {
                        val jobs = actions.listJobs(owner.trim(), repo.trim(), selectedRunId.toLong(), token)
                        jobList = jobs.map { "${it.id} | ${it.name} | ${it.status}/${it.conclusion}" }
                        "Loaded ${jobs.size} jobs."
                    }
                }) { Text("Load jobs") }
                OutlinedButton(enabled = !busy && selectedRunId.toLongOrNull() != null && token.isNotBlank(), onClick = {
                    launchTask {
                        artifactList = actions.listArtifacts(owner.trim(), repo.trim(), selectedRunId.toLong(), token)
                        "Loaded ${artifactList.size} artifacts."
                    }
                }) { Text("List artifacts") }
            }
        }
        items(jobList) { row ->
            TextButton(onClick = { selectedJobId = row.substringBefore(" | ") }) { Text(row) }
        }
        item {
            OutlinedTextField(selectedJobId, { selectedJobId = it }, Modifier.fillMaxWidth(), label = { Text("Selected job ID") })
            OutlinedButton(enabled = !busy && selectedJobId.toLongOrNull() != null && token.isNotBlank(), onClick = {
                launchTask {
                    actionOutput = actions.jobLogs(owner.trim(), repo.trim(), selectedJobId.toLong(), token)
                    "Loaded job logs (${actionOutput.length} characters)."
                }
            }) { Text("Fetch job logs") }
        }
        items(artifactList) { row ->
            TextButton(onClick = {
                val id = row.substringBefore(" | ").toLongOrNull() ?: return@TextButton
                launchTask {
                    val folder = actions.downloadArtifact(owner.trim(), repo.trim(), id, token)
                    "Artifact downloaded and extracted to ${folder.absolutePath}"
                }
            }) { Text("Download $row") }
        }
        if (actionOutput.isNotBlank()) item {
            ElevatedCard { Text(actionOutput.take(12000), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Text(output, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Text("Caution: commit stages all workspace changes. Pull/push alter the remote repository. Review status and diff before committing or syncing. Token is not persisted by this panel.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
