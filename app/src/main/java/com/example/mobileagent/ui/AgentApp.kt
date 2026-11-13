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
import kotlinx.coroutines.launch
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
    val client = remember { HttpProviderClient() }
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
                messages += ChatMessage(MessageRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls)
            }
            if (reply.toolCalls.isEmpty()) {
                status = "Completed."
                return
            }
            var needsApproval = false
            reply.toolCalls.forEach { call ->
                if (needsApproval) {
                    messages += ChatMessage(MessageRole.TOOL, "Deferred because another action is awaiting user approval.",
                        toolCallId = call.id, toolName = call.name)
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
                        messages += ChatMessage(MessageRole.TOOL, result, toolCallId = call.id, toolName = call.name)
                    }
                    "propose_git_commit", "propose_git_pull", "propose_git_push", "propose_trigger_workflow" -> {
                        when (val outcome = gateway.prepareAction(call)) {
                            is ToolOutcome.ActionProposal -> {
                                pendingAction = outcome
                                needsApproval = true
                            }
                            is ToolOutcome.Error -> messages += ChatMessage(MessageRole.TOOL,
                                "ERROR: ${outcome.message}", toolCallId = call.id, toolName = call.name)
                            else -> Unit
                        }
                    }
                    "propose_write_file" -> {
                        when (val outcome = gateway.prepareWrite(call)) {
                            is ToolOutcome.WriteProposal -> {
                                pendingProposal = outcome
                                needsApproval = true
                            }
                            is ToolOutcome.Error -> messages += ChatMessage(MessageRole.TOOL,
                                "ERROR: ${outcome.message}", toolCallId = call.id, toolName = call.name)
                            else -> Unit
                        }
                    }
                    else -> messages += ChatMessage(MessageRole.TOOL, "ERROR: unknown tool",
                        toolCallId = call.id, toolName = call.name)
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

    if (selectedScreen != 0) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedScreen) {
                Tab(selected = selectedScreen == 0, onClick = { selectedScreen = 0 }, text = { Text("Agent") })
                Tab(selected = selectedScreen == 1, onClick = { selectedScreen = 1 }, text = { Text("Git") })
                Tab(selected = selectedScreen == 2, onClick = { selectedScreen = 2 }, text = { Text("Research") })
            }
            when (selectedScreen) {
                1 -> GitPanel(context)
                2 -> ResearchPanel(context)
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = { selectedScreen = 0 }, text = { Text("Agent") })
                Tab(selected = false, onClick = { selectedScreen = 1 }, text = { Text("Git") })
                Tab(selected = false, onClick = { selectedScreen = 2 }, text = { Text("Research") })
            }
        }
        item {
            Text("Mobile Agent V2.7", style = MaterialTheme.typography.headlineMedium)
            Text("Web research · Git approvals · workflow dispatch · approved file edits")
        }
        item {
            Text("Protocol", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(protocol == ProtocolMode.OPENAI, { mode(ProtocolMode.OPENAI) }, label = { Text("OpenAI") })
                FilterChip(protocol == ProtocolMode.ANTHROPIC, { mode(ProtocolMode.ANTHROPIC) }, label = { Text("Claude") })
            }
        }
        item { OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS base URL") }) }
        item { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Endpoint path") }) }
        item { OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }) }
        item { OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Model provider API key") }) }
        item { HorizontalDivider() }
        item { Text("Agent integrations (keys stay in screen memory)", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(braveKey, { braveKey = it }, Modifier.fillMaxWidth(), label = { Text("Brave Search API key") }) }
        item { OutlinedTextField(githubToken, { githubToken = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token — read-only Actions scope recommended") }) }
        item { OutlinedTextField(githubOwner, { githubOwner = it }, Modifier.fillMaxWidth(), label = { Text("GitHub repository owner") }) }
        item { OutlinedTextField(githubRepo, { githubRepo = it }, Modifier.fillMaxWidth(), label = { Text("GitHub repository name") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching {
                    store.save(ApiConfig(baseUrl, endpoint, apiKey, model, protocol)); status = "Settings saved."
                }.onFailure { status = it.message ?: "Save failed" } }) { Text("Save settings") }
                OutlinedButton(onClick = { messages.clear(); pendingProposal = null; pendingAction = null; lastBackup = null; status = "Chat cleared." }) { Text("Clear chat") }
            }
        }
        item { HorizontalDivider() }
        item { Text("Conversation", style = MaterialTheme.typography.titleLarge) }
        items(messages.filter { it.role != MessageRole.TOOL }) { m ->
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (m.role == MessageRole.USER) "You" else "Assistant", style = MaterialTheme.typography.labelMedium)
                    MarkdownText(m.content)
                }
            }
        }
        if (streamingText.isNotBlank() && busy) {
            item {
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text("Streaming response…", style = MaterialTheme.typography.labelMedium)
                        MarkdownText(streamingText)
                    }
                }
            }
        }
        item {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Message") }, minLines = 2)
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                messages += ChatMessage(MessageRole.USER, input.trim())
                input = ""
                startAgent()
            }) { Text(if (busy) "Working…" else "Send") }
        }
        pendingAction?.let { proposal ->
            item {
                ElevatedCard {
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
                                        val result = gateway.executeApprovedAction(
                                            proposal, githubToken, githubOwner.trim(), githubRepo.trim()
                                        )
                                        messages += ChatMessage(MessageRole.TOOL, "Approved action result: $result",
                                            toolCallId = proposal.callId, toolName = proposal.toolName)
                                        pendingAction = null
                                        status = "Action completed. Continue agent?"
                                    } catch (e: Exception) {
                                        messages += ChatMessage(MessageRole.TOOL, "Approved action failed: ${e.message}",
                                            toolCallId = proposal.callId, toolName = proposal.toolName)
                                        pendingAction = null
                                        status = e.message ?: "Approved action failed."
                                    } finally { busy = false }
                                }
                            }) { Text("Approve and execute") }
                            OutlinedButton(enabled = !busy, onClick = {
                                messages += ChatMessage(MessageRole.TOOL, "User rejected this proposed action; it was not executed.",
                                    toolCallId = proposal.callId, toolName = proposal.toolName)
                                pendingAction = null
                                status = "Action rejected. Continue agent?"
                            }) { Text("Reject") }
                        }
                    }
                }
            }
        }
        pendingProposal?.let { proposal ->
            item {
                ElevatedCard {
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
                                    messages += ChatMessage(MessageRole.TOOL, "Approved and applied: ${result.relativePath}",
                                        toolCallId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.firstOrNull { it.name == "propose_write_file" }?.id,
                                        toolName = "propose_write_file")
                                    pendingProposal = null
                                    status = "Write approved. Continue agent?"
                                }.onFailure { status = it.message ?: "Write failed" }
                            }) { Text("Approve") }
                            OutlinedButton(onClick = {
                                val callId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.firstOrNull { it.name == "propose_write_file" }?.id
                                messages += ChatMessage(MessageRole.TOOL, "User rejected the proposed change.",
                                    toolCallId = callId, toolName = "propose_write_file")
                                pendingProposal = null
                                status = "Write rejected. Continue agent?"
                            }) { Text("Reject") }
                        }
                    }
                }
            }
        }
        if (pendingProposal == null && pendingAction == null && messages.lastOrNull()?.role == MessageRole.TOOL && !busy) {
            item {
                OutlinedButton(onClick = { startAgent() }) { Text("Continue agent") }
            }
        }
        lastBackup?.let { backup ->
            item {
                OutlinedButton(onClick = {
                    runCatching {
                        status = gateway.restore(backup.first, backup.second)
                        lastBackup = null
                    }.onFailure { status = it.message ?: "Undo failed" }
                }) { Text("Undo last approved write") }
            }
        }
        item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item {
            Text("Safety: web pages are untrusted data; retrieval is HTTPS-only with redirects disabled. File writes require approval and hash recheck. Agent commit, pull, push, and workflow dispatch are proposal-only and require approval here. Checkout remains manual in the Git tab. Commit stages ALL repository changes. Keys entered here are memory-only and are never added to model prompts, but are sent to their respective providers.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
