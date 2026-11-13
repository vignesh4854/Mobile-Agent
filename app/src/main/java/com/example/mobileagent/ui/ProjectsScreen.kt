package com.example.mobileagent.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileagent.data.local.ProjectEntity
import com.example.mobileagent.data.repository.ProjectRepository
import com.example.mobileagent.security.SensitiveFilePolicy
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

private data class WorkspaceEntry(val file: File, val isDirectory: Boolean)

@Composable
fun ProjectsScreen(context: Context) {
    val repository = remember { ProjectRepository(context.applicationContext) }
    val projects by repository.projects.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Projects are stored in app-private workspace storage.") }
    var selectedProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var currentDirectory by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var filePreview by remember { mutableStateOf<String?>(null) }
    var exportSource by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not open destination for export.")
            }.onSuccess { message = "Exported ${source.name} successfully." }
                .onFailure { message = "Export failed: ${it.message}" }
        }
    }

    val project = selectedProject
    if (project == null) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Projects", style = MaterialTheme.typography.headlineMedium)
                Text("Open a project to browse, preview and export its files.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Project name") }, singleLine = true)
                Button(enabled = name.isNotBlank(), onClick = {
                    val requested = name
                    scope.launch {
                        runCatching { repository.createProject(requested) }
                            .onSuccess { name = ""; message = "Created ${it.name}." }
                            .onFailure { message = it.message ?: "Could not create project." }
                    }
                }) { Text("Create project") }
            }
            item { Text(message, style = MaterialTheme.typography.bodySmall) }
            if (projects.isEmpty()) item { Text("No projects yet. Create one to get started.") }
            items(projects, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable {
                    selectedProject = item
                    currentDirectory = ""
                    selectedFile = null
                    filePreview = null
                }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.rootPath, style = MaterialTheme.typography.bodySmall)
                        Text("Updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.updatedAt))}",
                            style = MaterialTheme.typography.labelSmall)
                        Text("Tap to browse files", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        return
    }

    val root = remember(project.rootPath, context.filesDir) {
        val projectsRoot = File(context.filesDir, "workspace/projects").canonicalFile
        File(project.rootPath).canonicalFile.also {
            require(it.toPath().startsWith(projectsRoot.toPath())) {
                "Project folder is outside the app-private projects workspace."
            }
        }
    }
    val currentFolder = remember(root, currentDirectory) {
        if (currentDirectory.isBlank()) root else File(root, currentDirectory).canonicalFile
    }
    val entries = remember(root, currentDirectory) {
        runCatching {
            require(currentFolder.toPath().startsWith(root.toPath())) { "Folder is outside this project." }
            require(currentFolder.isDirectory) { "Folder does not exist." }
            currentFolder.listFiles().orEmpty()
                .filter { child ->
                    !java.nio.file.Files.isSymbolicLink(child.toPath()) &&
                        child.name !in setOf(".agent-backups", ".git") &&
                        !SensitiveFilePolicy.isSensitive(
                            (if (currentDirectory.isBlank()) "" else "$currentDirectory/") + child.name
                        ) &&
                        (child.isDirectory || child.isFile)
                }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .map { WorkspaceEntry(it, it.isDirectory) }
        }.getOrDefault(emptyList())
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Project files", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = {
                    selectedProject = null
                    selectedFile = null
                    filePreview = null
                }) { Text("All projects") }
            }
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text("projects/${root.name}/${currentDirectory}".trimEnd('/'),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = currentDirectory.isNotBlank(), onClick = {
                    currentDirectory = currentDirectory.substringBeforeLast('/', "")
                    selectedFile = null; filePreview = null
                }) { Text("Up one folder") }
                OutlinedButton(onClick = { currentDirectory = ""; selectedFile = null; filePreview = null }) {
                    Text("Project root")
                }
            }
        }
        item { Text(message, style = MaterialTheme.typography.bodySmall) }
        if (entries.isEmpty()) item { Text("This folder is empty.") }
        items(entries, key = { it.file.absolutePath }) { entry ->
            Card(Modifier.fillMaxWidth().clickable {
                if (entry.isDirectory) {
                    currentDirectory = listOf(currentDirectory, entry.file.name).filter { it.isNotBlank() }.joinToString("/")
                    selectedFile = null
                    filePreview = null
                } else {
                    selectedFile = entry.file
                    filePreview = runCatching {
                        require(entry.file.length() <= 256 * 1024) { "Preview limited to 256 KB. Export to view the full file." }
                        entry.file.readText(Charsets.UTF_8)
                    }.getOrElse { "Preview unavailable: ${it.message}" }
                }
            }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.file.name,
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (entry.isDirectory) "Folder" else "${entry.file.length()} bytes",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (!entry.isDirectory) TextButton(onClick = {
                        exportSource = entry.file
                        exportLauncher.launch(entry.file.name)
                    }) { Text("Export") }
                }
            }
        }
        selectedFile?.let { file ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text("Preview", style = MaterialTheme.typography.labelMedium)
                        Text(filePreview.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                exportSource = file
                                exportLauncher.launch(file.name)
                            }) { Text("Export to phone") }
                            OutlinedButton(onClick = { selectedFile = null; filePreview = null }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }
}
