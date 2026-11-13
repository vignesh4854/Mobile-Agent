package com.example.mobileagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Handler
import android.os.Looper
import com.example.mobileagent.agent.ToolGateway
import com.example.mobileagent.agent.ToolOutcome
import com.example.mobileagent.model.*
import com.example.mobileagent.provider.HttpProviderClient
import com.example.mobileagent.security.ApiConfigStore
import com.example.mobileagent.data.repository.ProjectRepository
import com.example.mobileagent.data.repository.SessionRepository
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun AgentApp(context: Context) {
    var selectedScreen by rememberSaveable { mutableStateOf(0) }
    val store = remember { ApiConfigStore(context) }
    val saved = remember { store.load() }
    val gateway = remember { ToolGateway(context) }
    var protocol by remember { mutableStateOf(saved?.protocol ?: ProtocolMode.OPENAI) }
    var baseUrl by remember { mutableStateOf(saved?.baseUrl ?: "https://api.openai.com/v1") }
    var endpoint by remember { mutableStateOf(saved?.endpoint ?: "chat/completions") }
    var apiKey by remember { mutableStateOf(saved?.apiKey.orEmpty()) }
    var braveKey by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var githubOwner by remember { mutableStateOf("") }
    var githubRepo by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(saved?.model ?: "") }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Configure provider and model.") }
    var busy by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var pendingProposal by remember { mutableStateOf<ToolOutcome.WriteProposal?>(null) }
    var pendingAction by remember { mutableStateOf<ToolOutcome.ActionProposal?>(null) }
    var lastBackup by remember { mutableStateOf<Pair<String, String>?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val projectRepository = remember { ProjectRepository(context.applicationContext) }
    val sessionRepository = remember { SessionRepository(context.applicationContext) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var sessionReady by remember { mutableStateOf(false) }
    val client = remember { HttpProviderClient() }

    LaunchedEffect(Unit) {
        runCatching {
            val project = projectRepository.ensureDefaultProject()
            val session = sessionRepository.getOrCreateSession(project.id)
            activeSessionId = session.id
            sessionRepository.observeMessages(session.id).collect { stored ->
                if (!sessionReady) {
                    messages.clear()
                    messages.addAll(stored.map { entity ->
                        val calls = runCatching {
                            val array = JSONArray(entity.toolCallsJson)
                            (0 until array.length()).mapNotNull { index ->
                                array.optJSONObject(index)?.let { obj ->
                                    ToolCallRequest(obj.optString("id"), obj.optString("name"), obj.optString("argumentsJson"))
                                }
                            }
                        }.getOrDefault(emptyList())
                        ChatMessage(MessageRole.valueOf(entity.role), entity.content,
                            toolCallId = entity.toolCallId, toolName = entity.toolName, toolCalls = calls)
                    })
                    sessionReady = true
                }
            }
        }.onFailure { status = "Session storage unavailable: ${it.message}"; sessionReady = true }
    }

    fun appendMessage(message: ChatMessage) {
        messages.add(message)
        val sessionId = activeSessionId ?: return
        val calls = JSONArray().apply {
            message.toolCalls.forEach { call -> put(JSONObject().put("id", call.id).put("name", call.name)
                .put("argumentsJson", call.argumentsJson)) }
        }
        scope.launch {
            runCatching { sessionRepository.appendMessage(sessionId, message.role.name, message.content,
                calls.toString(), message.toolCallId, message.toolName) }
                .onFailure { status = "Message persistence failed: ${it.message}" }
        }
    }
    val maxToolRounds = 4

    fun mode(m: ProtocolMode) {
        protocol = m
        if (m == ProtocolMode.OPENAI) { baseUrl = "https://api.openai.com/v1"; endpoint = "chat/completions" }
        else { baseUrl = "https://api.anthropic.com"; endpoint = "v1/messages" }
    }

    suspend fun runAgentLoop() {
        var round = 0
        while (round < maxToolRounds) {
            streamingText = ""
            val mainHandler = Handler(Looper.getMainLooper())
            val reply = client.send(ApiConfig(baseUrl, endpoint, apiKey, model, protocol), messages.toList()) { part ->
                mainHandler.post { streamingText += part }
            }
            if (reply.text.isNotBlank() || reply.toolCalls.isNotEmpty()) {
                appendMessage(ChatMessage(MessageRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls))
            }
            if (reply.toolCalls.isEmpty()) {
                status = "Completed."
                return
            }
            var needsApproval = false
            reply.toolCalls.forEach { call ->
                if (needsApproval) {
                    appendMessage(ChatMessage(MessageRole.TOOL, "Deferred because another action is awaiting user approval.",
                        toolCallId = call.id, toolName = call.name))
                    return@forEach
                }
                when (call.name) {
                    "list_files", "read_file", "web_search", "fetch_webpage", "list_saved_research", "read_saved_research",
                    "git_status", "git_diff_summary", "git_branches", "github_actions_runs", "github_actions_jobs",
                    "github_actions_logs", "github_actions_artifacts" -> {
                        val outcome = gateway.executeReadOnly(call, braveKey, githubToken, githubOwner.trim(), githubRepo.trim())
                        val result = when (outcome) {
                            is ToolOutcome.ReadResult -> outcome.text.take(7000)
                            is ToolOutcome.Error -> "ERROR: ${outcome.message}"
                            else -> "Unsupported result"
                        }
                        appendMessage(ChatMessage(MessageRole.TOOL, result, toolCallId = call.id, toolName = call.name))
                    }
                    "propose_git_commit", "propose_git_pull", "propose_git_push", "propose_trigger_workflow" -> {
                        when (val outcome = gateway.prepareAction(call)) {
                            is ToolOutcome.ActionProposal -> {
                                pendingAction = outcome
                                needsApproval = true
                            }
                            is ToolOutcome.Error -> appendMessage(ChatMessage(MessageRole.TOOL,
                                "ERROR: ${outcome.message}", toolCallId = call.id, toolName = call.name))
                            else -> Unit
                        }
                    }
                    "propose_write_file" -> {
                        when (val outcome = gateway.prepareWrite(call)) {
                            is ToolOutcome.WriteProposal -> {
                                pendingProposal = outcome
                                needsApproval = true
                            }
                            is ToolOutcome.Error -> appendMessage(ChatMessage(MessageRole.TOOL,
                                "ERROR: ${outcome.message}", toolCallId = call.id, toolName = call.name))
                            else -> Unit
                        }
                    }
                    else -> appendMessage(ChatMessage(MessageRole.TOOL, "ERROR: unknown tool",
                        toolCallId = call.id, toolName = call.name))
                }
            }
            if (needsApproval) {
                status = "Waiting for your approval before continuing the agent loop."
                return
            }
            round++
        }
        status = "Stopped after $maxToolRounds tool rounds to prevent runaway loops."
    }

    fun startAgent() {
        if (busy) return
        busy = true
        status = "Working…"
        scope.launch {
            try {
                store.save(ApiConfig(baseUrl, endpoint, apiKey, model, protocol))
                runAgentLoop()
            } catch (e: Exception) { status = e.message ?: "Agent request failed." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
      Box(Modifier.weight(1f)) {
      when (selectedScreen) {
        1 -> ProjectsScreen(context)
        2 -> MultiAgentPanel(context)
        3 -> MoreScreen(
            onGit = { selectedScreen = 4 },
            onResearch = { selectedScreen = 5 },
            onSettings = { selectedScreen = 6 }
        )
        4 -> GitPanel(context)
        5 -> ResearchPanel(context)
        6 -> SettingsScreen(
            protocol = protocol, onProtocol = ::mode,
            baseUrl = baseUrl, onBaseUrl = { baseUrl = it },
            endpoint = endpoint, onEndpoint = { endpoint = it },
            model = model, onModel = { model = it },
            apiKey = apiKey, onApiKey = { apiKey = it },
            braveKey = braveKey, onBraveKey = { braveKey = it },
            githubToken = githubToken, onGithubToken = { githubToken = it },
            githubOwner = githubOwner, onGithubOwner = { githubOwner = it },
            githubRepo = githubRepo, onGithubRepo = { githubRepo = it },
            onSave = {
                runCatching {
                    store.save(ApiConfig(baseUrl, endpoint, apiKey, model, protocol))
                    status = "Settings saved."
                }.onFailure { status = it.message ?: "Save failed" }
            },
            onClearChat = {
                messages.clear(); pendingProposal = null; pendingAction = null; lastBackup = null
                activeSessionId?.let { id -> scope.launch { sessionRepository.clearMessages(id) } }
                status = "Chat cleared."
            },
            status = status
        )
        else -> Column(Modifier.fillMaxSize()) {
          LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                    Text("Mobile Agent", style = MaterialTheme.typography.headlineMedium)
                    Text("Your AI coding workspace", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!sessionReady) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (messages.isEmpty() && !busy) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("What would you like to build?", style = MaterialTheme.typography.titleLarge)
                            Text("Ask a question, plan a feature, debug code, or work on your selected project.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Configure your model provider in More → Settings.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(messages.filter { it.role != MessageRole.TOOL }) { m ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (m.role == MessageRole.USER) "You" else "Assistant",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        MarkdownText(m.content, showCopy = m.role == MessageRole.ASSISTANT)
                    }
                }
            }
            if (streamingText.isNotBlank() && busy) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Generating…", style = MaterialTheme.typography.labelMedium)
                            MarkdownText(streamingText, showCopy = true)
                        }
                    }
                }
            }
            pendingAction?.let { proposal ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Approval required", style = MaterialTheme.typography.titleMedium)
                            Text(proposal.toolName, style = MaterialTheme.typography.labelLarge)
                            Text(proposal.summary)
                            Text("Repository: ${githubOwner.ifBlank { "(owner not set)" }}/${githubRepo.ifBlank { "(repo not set)" }}")
                            Text("This operation will not run until you approve it. Review the scope carefully.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val result = gateway.executeApprovedAction(proposal, githubToken, githubOwner.trim(), githubRepo.trim())
                                            appendMessage(ChatMessage(MessageRole.TOOL, "Approved action result: $result",
                                                toolCallId = proposal.callId, toolName = proposal.toolName))
                                            pendingAction = null; status = "Action completed. Continue agent?"
                                        } catch (e: Exception) {
                                            appendMessage(ChatMessage(MessageRole.TOOL, "Approved action failed: ${e.message}",
                                                toolCallId = proposal.callId, toolName = proposal.toolName))
                                            pendingAction = null; status = e.message ?: "Approved action failed."
                                        } finally { busy = false }
                                    }
                                }) { Text("Approve") }
                                OutlinedButton(enabled = !busy, onClick = {
                                    appendMessage(ChatMessage(MessageRole.TOOL, "User rejected this proposed action; it was not executed.",
                                        toolCallId = proposal.callId, toolName = proposal.toolName))
                                    pendingAction = null; status = "Action rejected. Continue agent?"
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
            }
            pendingProposal?.let { proposal ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review proposed file change", style = MaterialTheme.typography.titleMedium)
                            Text(proposal.path, style = MaterialTheme.typography.labelLarge)
                            Text("Unified diff preview", style = MaterialTheme.typography.labelMedium)
                            Text(proposal.diff, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                            Text("Approval writes this complete replacement after checking the original file hash.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    runCatching {
                                        val result = gateway.approve(proposal)
                                        lastBackup = result.backupPath to proposal.path
                                        appendMessage(ChatMessage(MessageRole.TOOL, "Approved and applied: ${result.relativePath}",
                                            toolCallId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.firstOrNull { it.name == "propose_write_file" }?.id,
                                            toolName = "propose_write_file"))
                                        pendingProposal = null; status = "Write approved. Continue agent?"
                                    }.onFailure { status = it.message ?: "Write failed" }
                                }) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    val callId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.firstOrNull { it.name == "propose_write_file" }?.id
                                    appendMessage(ChatMessage(MessageRole.TOOL, "User rejected the proposed change.",
                                        toolCallId = callId, toolName = "propose_write_file"))
                                    pendingProposal = null; status = "Write rejected. Continue agent?"
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
            }
            if (pendingProposal == null && pendingAction == null && messages.lastOrNull()?.role == MessageRole.TOOL && !busy) {
                item { OutlinedButton(onClick = { startAgent() }) { Text("Continue agent") } }
            }
            lastBackup?.let { backup ->
                item {
                    OutlinedButton(onClick = {
                        runCatching { status = gateway.restore(backup.first, backup.second); lastBackup = null }
                            .onFailure { status = it.message ?: "Undo failed" }
                    }) { Text("Undo last approved write") }
                }
            }
            item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          }
          Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
              if (model.isBlank() || apiKey.isBlank()) {
                TextButton(onClick = { selectedScreen = 6 }) {
                  Text("Set up your model provider in Settings to start chatting")
                }
              }
              Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                  value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                  placeholder = { Text("Message your agent…") }, minLines = 1, maxLines = 5,
                  enabled = !busy, shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                  enabled = sessionReady && !busy && input.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank(),
                  onClick = {
                    appendMessage(ChatMessage(MessageRole.USER, input.trim()))
                    input = ""
                    startAgent()
                  }
                ) { Text("↑") }
              }
            }
          }
        }
      }

    }
    NavigationBar {
        NavigationBarItem(selected = selectedScreen == 0, onClick = { selectedScreen = 0 },
            icon = { Text("◉") }, label = { Text("Chat") })
        NavigationBarItem(selected = selectedScreen == 1, onClick = { selectedScreen = 1 },
            icon = { Text("▦") }, label = { Text("Projects") })
        NavigationBarItem(selected = selectedScreen == 2, onClick = { selectedScreen = 2 },
            icon = { Text("◎") }, label = { Text("Team") })
        NavigationBarItem(selected = selectedScreen in 3..6, onClick = { selectedScreen = 3 },
            icon = { Text("⋯") }, label = { Text("More") })
    }
    }

}


@Composable
private fun MoreScreen(onGit: () -> Unit, onResearch: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Text("Developer tools and app configuration", color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoreAction("Git", "Branches, changes, commits and repository operations", onGit)
        MoreAction("Research", "Web search and saved research", onResearch)
        MoreAction("Settings", "Model provider, API keys, integrations and chat controls", onSettings)
    }
}

@Composable
private fun MoreAction(title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun SettingsScreen(
    protocol: ProtocolMode, onProtocol: (ProtocolMode) -> Unit,
    baseUrl: String, onBaseUrl: (String) -> Unit,
    endpoint: String, onEndpoint: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    apiKey: String, onApiKey: (String) -> Unit,
    braveKey: String, onBraveKey: (String) -> Unit,
    githubToken: String, onGithubToken: (String) -> Unit,
    githubOwner: String, onGithubOwner: (String) -> Unit,
    githubRepo: String, onGithubRepo: (String) -> Unit,
    onSave: () -> Unit, onClearChat: () -> Unit, status: String
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Model provider", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = protocol == ProtocolMode.OPENAI, onClick = { onProtocol(ProtocolMode.OPENAI) }, label = { Text("OpenAI-compatible") })
                FilterChip(selected = protocol == ProtocolMode.ANTHROPIC, onClick = { onProtocol(ProtocolMode.ANTHROPIC) }, label = { Text("Anthropic") })
            }
        }
        item { OutlinedTextField(baseUrl, onBaseUrl, Modifier.fillMaxWidth(), label = { Text("HTTPS base URL") }, singleLine = true) }
        item { OutlinedTextField(endpoint, onEndpoint, Modifier.fillMaxWidth(), label = { Text("Endpoint path") }, singleLine = true) }
        item { OutlinedTextField(model, onModel, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true) }
        item { OutlinedTextField(apiKey, onApiKey, Modifier.fillMaxWidth(), label = { Text("Model provider API key") }, singleLine = true) }
        item { HorizontalDivider() }
        item { Text("Integrations", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(braveKey, onBraveKey, Modifier.fillMaxWidth(), label = { Text("Brave Search API key") }, singleLine = true) }
        item { OutlinedTextField(githubToken, onGithubToken, Modifier.fillMaxWidth(), label = { Text("GitHub token") }, singleLine = true) }
        item { OutlinedTextField(githubOwner, onGithubOwner, Modifier.fillMaxWidth(), label = { Text("GitHub repository owner") }, singleLine = true) }
        item { OutlinedTextField(githubRepo, onGithubRepo, Modifier.fillMaxWidth(), label = { Text("GitHub repository name") }, singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Save settings") }
                OutlinedButton(onClick = onClearChat) { Text("Clear chat") }
            }
        }
        item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("API credentials are sent to their configured providers. Avoid sharing keys or screenshots containing them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
