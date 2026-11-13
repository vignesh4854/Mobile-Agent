package com.example.mobileagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mobileagent.agent.ToolGateway
import com.example.mobileagent.agent.multi.*
import com.example.mobileagent.agent.runtime.ToolRegistry
import com.example.mobileagent.data.repository.AgentRunRepository
import com.example.mobileagent.model.ApiConfig
import com.example.mobileagent.model.ProtocolMode
import com.example.mobileagent.provider.HttpProviderClient
import com.example.mobileagent.security.AgentProfilesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

private data class PendingMultiApproval(
    val role: AgentRole,
    val summary: String,
    val deferred: CompletableDeferred<Boolean>
)

@Composable
fun MultiAgentPanel(context: Context) {
    val profileStore = remember { AgentProfilesStore(context.applicationContext) }
    val runRepository = remember { AgentRunRepository(context.applicationContext) }
    val provider = remember { HttpProviderClient() }
    val coordinator = remember { MultiAgentCoordinator(provider) }
    val profiles = remember { mutableStateMapOf<AgentRole, AgentProfile>() }
    val events = remember { mutableStateListOf<AgentTraceEvent>() }
    val recentRuns by runRepository.observeRecentRuns().collectAsState(initial = emptyList())
    var selectedTraceRunId by remember { mutableStateOf<String?>(null) }
    val traceFlow = remember(selectedTraceRunId) {
        selectedTraceRunId?.let { runRepository.observeTraces(it) }
            ?: flowOf(emptyList<com.example.mobileagent.data.local.AgentTraceEntity>())
    }
    val historicalTraces by traceFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var task by remember { mutableStateOf("") }
    var projectPath by remember { mutableStateOf("${context.filesDir.absolutePath}/workspace/repository") }
    var userContext by remember { mutableStateOf("") }
    var braveKey by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var githubOwner by remember { mutableStateOf("") }
    var githubRepo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Configure the four agent profiles, then start a collaborative run.") }
    var finalOutput by remember { mutableStateOf("") }
    var tokenSummary by remember { mutableStateOf("") }
    var activeRunId by remember { mutableStateOf<String?>(null) }
    var pendingApproval by remember { mutableStateOf<PendingMultiApproval?>(null) }
    var showProfiles by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { profileStore.load() }
        AgentRole.entries.forEach { role ->
            profiles[role] = loaded.firstOrNull { it.role == role } ?: defaultProfile(role)
        }
    }

    fun saveProfiles() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profileStore.save(AgentRole.entries.mapNotNull { profiles[it] }) }
                status = "Agent profiles saved with encrypted API keys."
            }.onFailure { status = it.message ?: "Could not save profiles." }
        }
    }

    fun startRun() {
        if (busy) return
        val currentProfiles = AgentRole.entries.mapNotNull { profiles[it] }.associateBy { it.role }
        if (currentProfiles.size != AgentRole.entries.size) {
            status = "All four agent profiles must be configured."
            return
        }
        if (currentProfiles.values.any { it.config.apiKey.isBlank() || it.config.model.isBlank() }) {
            status = "Each agent needs its own API key and model ID."
            return
        }
        if (task.isBlank()) {
            status = "Enter a task for the team."
            return
        }
        val workspaceRoot = java.io.File(context.filesDir, "workspace").canonicalFile
        val requestedProject = java.io.File(projectPath).canonicalFile
        if (!requestedProject.toPath().startsWith(workspaceRoot.toPath())) {
            status = "Project path must remain inside app-private workspace: ${workspaceRoot.path}"
            return
        }
        if (!requestedProject.exists() && !requestedProject.mkdirs()) {
            status = "Could not create project workspace."
            return
        }
        if (!requestedProject.isDirectory) {
            status = "Project path must be a directory."
            return
        }
        val repositoryRoot = java.io.File(workspaceRoot, "repository").canonicalFile
        val allowLocalGit = requestedProject == repositoryRoot
        busy = true
        finalOutput = ""
        tokenSummary = ""
        events.clear()
        activeRunId = null
        status = "Starting planner…"
        scope.launch {
            try {
                val scopedGateway = ToolGateway(context.applicationContext, requestedProject)
                val sharedTools = SharedAgentToolGateway(
                gateway = scopedGateway,
                registry = ToolRegistry.v3Defaults(),
                braveKey = { braveKey },
                githubToken = { githubToken },
                githubOwner = { githubOwner.trim() },
                githubRepo = { githubRepo.trim() },
                allowLocalGit = allowLocalGit,
                requestApproval = { request, summary ->
                    val deferred = CompletableDeferred<Boolean>()
                    withContext(Dispatchers.Main.immediate) {
                        pendingApproval = PendingMultiApproval(request.role, summary, deferred)
                        status = "${request.role.label} is waiting for your approval."
                    }
                    val approved = deferred.await()
                    withContext(Dispatchers.Main.immediate) {
                        pendingApproval = null
                        status = if (approved) "Approved ${request.call.name}." else "Rejected ${request.call.name}."
                    }
                    approved
                }
            )
                val result = coordinator.run(
                    profiles = currentProfiles,
                    task = task.trim(),
                    projectPath = projectPath.trim(),
                    userContext = userContext.trim(),
                    executeTool = { role, call -> sharedTools.execute(role, call).text },
                    onEvent = { event ->
                        if (event.type == AgentTraceType.RUN_STARTED) {
                            activeRunId = event.runId
                            runRepository.start(event.runId, task.trim())
                        }
                        runRepository.append(event)
                        events.add(event)
                        status = event.detail.take(180)
                    }
                )
                finalOutput = result.finalOutput
                val perAgent = result.results.joinToString(" · ") {
                    "${it.role.label}: ~${it.promptTokens + it.completionTokens}"
                }
                tokenSummary = "Estimated tokens: ${result.totalEstimatedTokens} total " +
                    "(${result.totalPromptTokens} prompt + ${result.totalCompletionTokens} completion). " +
                    "Per agent: $perAgent. Elapsed: ${result.durationMillis} ms. " +
                    "Failed agents: ${result.failedRoles.joinToString { it.label }.ifBlank { "none" }}."
                status = "Multi-agent run completed."
                runRepository.finish(result.runId,
                    if (result.failedRoles.isEmpty()) "completed" else "completed_with_errors",
                    result.totalPromptTokens, result.totalCompletionTokens, result.durationMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status = e.message ?: "Multi-agent run failed."
                activeRunId?.let { runCatching { runRepository.finish(it, "failed", 0, 0, 0) } }
            } finally {
                busy = false
                pendingApproval?.deferred?.complete(false)
                pendingApproval = null
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Multi-agent team", style = MaterialTheme.typography.headlineMedium)
            Text("Planner → Coder → parallel Reviewer + Debugger → Planner synthesis",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showProfiles = !showProfiles }) {
                    Text(if (showProfiles) "Hide profiles" else "Configure agents")
                }
                OutlinedButton(enabled = !busy, onClick = ::saveProfiles) { Text("Save profiles") }
            }
        }
        if (showProfiles) {
            AgentRole.entries.forEach { role ->
                item(key = "profile-${role.name}") {
                    profiles[role]?.let { profile ->
                        AgentProfileEditor(profile) { profiles[role] = it }
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item { Text("Shared project context", style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(projectPath, { projectPath = it }, Modifier.fillMaxWidth(),
                label = { Text("Project workspace path") }, singleLine = true)
        }
        item {
            OutlinedTextField(userContext, { userContext = it }, Modifier.fillMaxWidth(),
                label = { Text("Additional project context (optional)") }, minLines = 2)
        }
        item { Text("Shared tool credentials (kept in screen memory)", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(braveKey, { braveKey = it }, Modifier.fillMaxWidth(),
            label = { Text("Brave Search API key") }, visualTransformation = PasswordVisualTransformation()) }
        item { OutlinedTextField(githubToken, { githubToken = it }, Modifier.fillMaxWidth(),
            label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation()) }
        item { OutlinedTextField(githubOwner, { githubOwner = it }, Modifier.fillMaxWidth(), label = { Text("GitHub owner") }) }
        item { OutlinedTextField(githubRepo, { githubRepo = it }, Modifier.fillMaxWidth(), label = { Text("GitHub repository") }) }
        item { HorizontalDivider() }
        item { Text("Team task", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth(),
            label = { Text("What should the agents accomplish?") }, minLines = 3) }
        item {
            Button(enabled = !busy && task.isNotBlank(), onClick = ::startRun, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Team working…" else "Start multi-agent run")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (tokenSummary.isNotBlank()) item {
            ElevatedCard { Text(tokenSummary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        if (finalOutput.isNotBlank()) item {
            Text("Final team report", style = MaterialTheme.typography.titleLarge)
            ElevatedCard { MarkdownText(finalOutput, showCopy = true) }
        }
        if (events.isNotEmpty()) item {
            Text("Execution trace", style = MaterialTheme.typography.titleLarge)
            Text("Token figures are estimates based on text length; provider-reported usage is not available in this API adapter.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(events.takeLast(100)) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${event.role?.label ?: "Orchestrator"} · ${event.type}",
                        style = MaterialTheme.typography.labelMedium)
                    Text(event.detail, style = MaterialTheme.typography.bodySmall)
                    if (event.estimatedPromptTokens + event.estimatedCompletionTokens > 0) {
                        Text("~${event.estimatedPromptTokens + event.estimatedCompletionTokens} tokens · ${event.durationMillis} ms",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (recentRuns.isNotEmpty()) item {
            Text("Recent runs", style = MaterialTheme.typography.titleLarge)
            recentRuns.take(8).forEach { run ->
                TextButton(onClick = { selectedTraceRunId = run.runId }) {
                    Text("${run.status} · ${run.task.take(100)} · ~${run.estimatedPromptTokens + run.estimatedCompletionTokens} tokens")
                }
            }
        }
        if (selectedTraceRunId != null) item {
            Text("Saved execution trace · ${selectedTraceRunId!!.take(8)}", style = MaterialTheme.typography.titleLarge)
        }
        items(historicalTraces.takeLast(100)) { trace ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${trace.role ?: "Orchestrator"} · ${trace.eventType}", style = MaterialTheme.typography.labelMedium)
                    Text(trace.detail, style = MaterialTheme.typography.bodySmall)
                    Text("~${trace.estimatedPromptTokens + trace.estimatedCompletionTokens} tokens · ${trace.durationMillis} ms",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Text(
                "Security: all agents share one permission gateway. Read-only tools run automatically. File writes and selected remote actions require approval. Legacy stage-all commit and terminal execution are blocked in multi-agent mode.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.deferred.complete(false) },
            title = { Text("Agent approval required · ${pending.role.label}") },
            text = { Text(pending.summary) },
            confirmButton = {
                TextButton(onClick = { pending.deferred.complete(true) }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { pending.deferred.complete(false) }) { Text("Reject") }
            }
        )
    }
}

@Composable
private fun AgentProfileEditor(profile: AgentProfile, onChange: (AgentProfile) -> Unit) {
    val config = profile.config
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(profile.role.label, style = MaterialTheme.typography.titleMedium)
            Text(profile.role.mission, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(config.protocol == ProtocolMode.OPENAI, {
                    onChange(profile.copy(config = config.copy(
                        protocol = ProtocolMode.OPENAI,
                        baseUrl = "https://api.openai.com/v1",
                        endpoint = "chat/completions"
                    )))
                }, label = { Text("OpenAI-compatible") })
                FilterChip(config.protocol == ProtocolMode.ANTHROPIC, {
                    onChange(profile.copy(config = config.copy(
                        protocol = ProtocolMode.ANTHROPIC,
                        baseUrl = "https://api.anthropic.com",
                        endpoint = "v1/messages"
                    )))
                }, label = { Text("Anthropic") })
            }
            OutlinedTextField(config.baseUrl, { onChange(profile.copy(config = config.copy(baseUrl = it))) },
                Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
            OutlinedTextField(config.endpoint, { onChange(profile.copy(config = config.copy(endpoint = it))) },
                Modifier.fillMaxWidth(), label = { Text("Endpoint") }, singleLine = true)
            OutlinedTextField(config.model, { onChange(profile.copy(config = config.copy(model = it))) },
                Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
            OutlinedTextField(config.apiKey, { onChange(profile.copy(config = config.copy(apiKey = it))) },
                Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
        }
    }
}

private fun defaultProfile(role: AgentRole): AgentProfile = AgentProfile(
    role,
    ApiConfig(
        baseUrl = "https://api.openai.com/v1",
        endpoint = "chat/completions",
        apiKey = "",
        model = "",
        protocol = ProtocolMode.OPENAI
    )
)
