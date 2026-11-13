package com.example.mobileagent.git

import android.content.Context
import com.example.mobileagent.security.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class WorkflowRun(
    val id: Long, val name: String, val status: String, val conclusion: String,
    val branch: String, val url: String, val createdAt: String
)
data class WorkflowJob(val id: Long, val name: String, val status: String, val conclusion: String)

class GitHubActionsClient(context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private val downloads = File(WorkspaceManager(context.filesDir).root, "github-artifacts").apply { mkdirs() }

    suspend fun listRuns(owner: String, repo: String, token: String): List<WorkflowRun> =
        withContext(Dispatchers.IO) {
            val json = getJson("repos/$owner/$repo/actions/runs?per_page=15", token)
            val runs = json.optJSONArray("workflow_runs") ?: return@withContext emptyList()
            (0 until runs.length()).map { i ->
                val item = runs.getJSONObject(i)
                WorkflowRun(item.optLong("id"), item.optString("name"), item.optString("status"),
                    item.optString("conclusion"), item.optString("head_branch"),
                    item.optString("html_url"), item.optString("created_at"))
            }
        }

    suspend fun listJobs(owner: String, repo: String, runId: Long, token: String): List<WorkflowJob> =
        withContext(Dispatchers.IO) {
            val json = getJson("repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100", token)
            val jobs = json.optJSONArray("jobs") ?: return@withContext emptyList()
            (0 until jobs.length()).map { i ->
                val item = jobs.getJSONObject(i)
                WorkflowJob(item.optLong("id"), item.optString("name"),
                    item.optString("status"), item.optString("conclusion"))
            }
        }

    suspend fun jobLogs(owner: String, repo: String, jobId: Long, token: String): String =
        withContext(Dispatchers.IO) {
            val request = apiRequest("repos/$owner/$repo/actions/jobs/$jobId/logs", token)
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Job logs HTTP ${response.code}")
                response.body?.string()?.take(150_000).orEmpty()
            }
        }

    suspend fun dispatchWorkflow(owner: String, repo: String, workflow: String, ref: String, token: String): String =
        withContext(Dispatchers.IO) {
            require(validRepoPart(owner) && validRepoPart(repo)) { "Invalid GitHub repository owner/name." }
            require(workflow.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "Workflow must be a workflow filename or numeric ID." }
            require(ref.matches(Regex("[A-Za-z0-9_./-]{1,200}")) && !ref.contains("..")) { "Invalid workflow ref." }
            require(token.isNotBlank()) { "GitHub token is required." }
            val body = JSONObject().put("ref", ref).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflow/dispatches")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer $token")
                .post(body).build()
            http.newCall(request).execute().use { response ->
                if (response.code != 204) {
                    val message = response.body?.string().orEmpty().take(700)
                    throw IllegalStateException("Workflow dispatch HTTP ${response.code}: $message")
                }
            }
            "Workflow dispatch accepted for $workflow on ref $ref. Check Actions runs for execution status."
        }

    private fun validRepoPart(value: String) = value.matches(Regex("[A-Za-z0-9_.-]{1,100}"))

    suspend fun listArtifacts(owner: String, repo: String, runId: Long, token: String): List<String> =
        withContext(Dispatchers.IO) {
            val json = getJson("repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100", token)
            val artifacts = json.optJSONArray("artifacts") ?: return@withContext emptyList()
            (0 until artifacts.length()).map { i ->
                val a = artifacts.getJSONObject(i)
                "${a.optLong("id")} | ${a.optString("name")} | ${a.optLong("size_in_bytes")} bytes | expired=${a.optBoolean("expired")}"
            }
        }

    suspend fun downloadArtifact(owner: String, repo: String, artifactId: Long, token: String): File =
        withContext(Dispatchers.IO) {
            val request = apiRequest("repos/$owner/$repo/actions/artifacts/$artifactId/zip", token)
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Artifact download HTTP ${response.code}")
                val zip = File(downloads, "artifact-$artifactId.zip")
                response.body?.byteStream()?.use { input -> zip.outputStream().use { output -> input.copyTo(output) } }
                val destination = File(downloads, "artifact-$artifactId").apply { mkdirs() }
                ZipInputStream(zip.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val out = File(destination, entry.name).canonicalFile
                        require(out.toPath().startsWith(destination.canonicalFile.toPath())) { "Unsafe ZIP entry path." }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                destination
            }
        }

    private fun getJson(path: String, token: String): JSONObject {
        val request = apiRequest(path, token)
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("GitHub API HTTP ${response.code}: ${body.take(800)}")
            return JSONObject(body)
        }
    }

    private fun apiRequest(path: String, token: String): Request {
        require(token.isNotBlank()) { "GitHub token is required." }
        require(path.matches(Regex("[A-Za-z0-9_./?=&-]+"))) { "Invalid API path." }
        return Request.Builder().url("https://api.github.com/$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token").build()
    }
}
