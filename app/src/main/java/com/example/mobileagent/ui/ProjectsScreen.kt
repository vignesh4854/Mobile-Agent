package com.example.mobileagent.ui

import android.content.Context
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileagent.data.repository.ProjectRepository
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(context: Context) {
    val repository = remember { ProjectRepository(context.applicationContext) }
    val projects by repository.projects.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Projects are stored in app-private workspace storage.") }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Projects", style = MaterialTheme.typography.headlineMedium)
            Text("Each project gets its own local workspace folder.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Project name") }, singleLine = true)
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
        items(projects, key = { it.id }) { project ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(project.rootPath, style = MaterialTheme.typography.bodySmall)
                    Text("Updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(project.updatedAt))}",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
