package com.example.mobileaicoding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.mobileaicoding.data.AppPrefs
import com.example.mobileaicoding.files.ProjectFiles
import com.example.mobileaicoding.model.*
import com.example.mobileaicoding.network.ProviderClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPrefs(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2563EB), background = Color(0xFFF7F8FA))) {
            MainScreen(prefs)
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(prefs: AppPrefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ProviderClient() }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(prefs.loadMessages()) } }
    var config by remember { mutableStateOf(prefs.loadConfig()) }
    var projectUri by remember { mutableStateOf(prefs.projectUri()?.let(Uri::parse)) }
    var projectName by remember { mutableStateOf(prefs.projectName()) }
    var input by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingApproval?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val listState = rememberLazyListState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            projectUri = uri
            projectName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected project"
            prefs.saveProject(uri.toString(), projectName)
            status = "Project folder selected"
        }
    }

    LaunchedEffect(messages.size, streamingText) { if (messages.isNotEmpty() || streamingText.isNotEmpty()) listState.animateScrollToItem((messages.size - 1 + if (streamingText.isNotEmpty()) 1 else 0).coerceAtLeast(0)) }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val previous = messages.toList()
        messages.add(ChatMessage("user", text))
        prefs.saveMessages(messages)
        input = ""
        streamingText = ""
        sending = true
        status = "Connecting to ${config.mode.name.lowercase().replaceFirstChar { it.uppercase() }} model…"
        scope.launch {
            try {
                val files = projectUri?.let { ProjectFiles(context, it) }
                val answer = client.runTurn(
                    config = config,
                    history = previous,
                    userText = text,
                    files = files,
                    onDelta = { chunk -> handler.post { streamingText += chunk } },
                    onApproval = { request ->
                        val gate = CompletableDeferred<Boolean>()
                        withContext(Dispatchers.Main) { pending = request; approval = gate }
                        val allowed = gate.await()
                        withContext(Dispatchers.Main) { pending = null; approval = null }
                        allowed
                    }
                )
                messages.add(ChatMessage("assistant", answer))
                prefs.saveMessages(messages)
                status = "Ready"
            } catch (e: Exception) {
                val error = "Error: ${e.message ?: e.javaClass.simpleName}"
                messages.add(ChatMessage("assistant", error))
                prefs.saveMessages(messages)
                status = "Request failed"
            } finally {
                streamingText = ""
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Mobile AI Coding", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${config.mode.name} · ${config.model.ifBlank { "No model selected" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
                actions = {
                    IconButton(onClick = { folderPicker.launch(projectUri) }) { Icon(Icons.Default.FolderOpen, "Select project folder") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(projectName, modifier = Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(status, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                decorationBox = { inner -> Box { if (input.isEmpty()) Text("Ask about your project…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { sendMessage() }, enabled = !sending && input.isNotBlank()) { Icon(Icons.Default.Send, "Send") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (projectUri == null) {
                Surface(color = Color(0xFFEFF6FF), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF2563EB))
                        Spacer(Modifier.width(8.dp))
                        Text("Select a project folder to enable local file tools.", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { folderPicker.launch(null) }) { Text("Select") }
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && streamingText.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your project, with an AI assistant", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Configure an API provider, select a folder, and ask the assistant to inspect or edit your files.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(messages) { _, message -> MessageBubble(message) }
                if (streamingText.isNotEmpty()) item { MessageBubble(ChatMessage("assistant", streamingText + " ▍")) }
                if (sending && streamingText.isEmpty()) item { Text("Thinking…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(config = config, onDismiss = { showSettings = false }, onSave = { config = it; prefs.saveConfig(it); showSettings = false; status = "Settings saved" }, onTest = { candidate ->
            scope.launch { status = "Testing connection…"; runCatching { client.test(candidate) }.onSuccess { status = it }.onFailure { status = "Test failed: ${it.message}" } }
        })
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { approval?.complete(false) },
            title = { Text(if (request.operation == "delete") "Confirm deletion" else "Approve file change") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("${request.name}: ${request.path}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(request.preview, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { approval?.complete(true) }) { Text(if (request.operation == "delete") "Delete" else "Apply") } },
            dismissButton = { TextButton(onClick = { approval?.complete(false) }) { Text("Deny") } }
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) Color(0xFFE8F0FE) else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (user) "You" else "Assistant", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(message.content, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SettingsDialog(config: ProviderConfig, onDismiss: () -> Unit, onSave: (ProviderConfig) -> Unit, onTest: (ProviderConfig) -> Unit) {
    var mode by remember(config) { mutableStateOf(config.mode) }
    var base by remember(config) { mutableStateOf(config.baseUrl) }
    var endpoint by remember(config) { mutableStateOf(config.endpoint) }
    var key by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var headers by remember(config) { mutableStateOf(config.customHeaders) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Provider settings") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("API mode", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == ApiMode.CLAUDE, onClick = { mode = ApiMode.CLAUDE; base = "https://api.anthropic.com"; endpoint = "/v1/messages" })
                    Text("Claude")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(selected = mode == ApiMode.OPENAI, onClick = { mode = ApiMode.OPENAI; base = "https://api.openai.com/v1"; endpoint = "/chat/completions" })
                    Text("OpenAI")
                }
                Field("Base URL", base, { base = it })
                Field("Endpoint path", endpoint, { endpoint = it })
                Field("Model ID", model, { model = it })
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text("Custom headers (Name: value, one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Claude uses Anthropic Messages. OpenAI uses Chat Completions. Use the endpoint format required by your API gateway.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onTest(ProviderConfig(mode, base, endpoint, key, model, headers)) }) { Text("Test") }
                TextButton(onClick = { onSave(ProviderConfig(mode, base, endpoint, key, model, headers)) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
