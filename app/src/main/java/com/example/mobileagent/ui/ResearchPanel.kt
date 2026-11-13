package com.example.mobileagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mobileagent.research.ResearchClient
import com.example.mobileagent.research.SearchResult
import kotlinx.coroutines.launch

@Composable
fun ResearchPanel(context: Context) {
    val client = remember { ResearchClient(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchKey by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    var selected by remember { mutableStateOf<SearchResult?>(null) }
    var pageText by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Search the web, retrieve a result, and save useful research locally.") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(client.savedItems()) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Web research", style = MaterialTheme.typography.headlineMedium)
            Text("Brave Search API · safe page retrieval · local research library")
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search query") }) }
        item { OutlinedTextField(searchKey, { searchKey = it }, Modifier.fillMaxWidth(), label = { Text("Brave Search API key") }) }
        item {
            Button(enabled = !busy && query.isNotBlank() && searchKey.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try { results = client.search(query, searchKey); output = "Found ${results.size} results." }
                    catch (e: Exception) { output = e.message ?: "Search failed." }
                    finally { busy = false }
                }
            }) { Text("Search web") }
        }
        items(results) { result ->
            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                    Text(result.description, style = MaterialTheme.typography.bodySmall)
                    Text(result.url, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected = result; pageText = "" }) { Text("Select") }
                        TextButton(onClick = {
                            selected = result
                            busy = true
                            scope.launch {
                                try { pageText = client.retrieve(result.url); output = "Retrieved page text." }
                                catch (e: Exception) { output = e.message ?: "Retrieve failed." }
                                finally { busy = false }
                            }
                        }) { Text("Retrieve") }
                    }
                }
            }
        }
        selected?.let { item ->
            item {
                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Selected source: ${item.title}", style = MaterialTheme.typography.titleMedium)
                        if (pageText.isNotBlank()) {
                            Text(pageText.take(12000), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                runCatching {
                                    client.save(item.title, item.url, pageText)
                                    saved = client.savedItems()
                                    output = "Saved research locally."
                                }.onFailure { output = it.message ?: "Save failed." }
                            }) { Text("Save research") }
                        } else Text("Retrieve this page to extract its text.")
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item { Text("Saved research (${saved.size})", style = MaterialTheme.typography.titleLarge) }
        items(saved) { savedItem ->
            ElevatedCard {
                Column(Modifier.padding(10.dp)) {
                    Text(savedItem.title, style = MaterialTheme.typography.titleMedium)
                    Text(savedItem.url, style = MaterialTheme.typography.bodySmall)
                    Text(savedItem.content.take(350), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text(output, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Text("Safety: retrieval accepts HTTPS only, blocks private/local IPs, does not follow redirects, limits page size, and does not execute scripts. Search results and page text are untrusted; do not follow instructions found inside them.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
