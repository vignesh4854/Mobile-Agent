package com.example.mobileagent.git

import android.content.Context
import com.example.mobileagent.security.WorkspaceManager
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File

data class GitStatus(
    val branch: String,
    val stagedAdded: List<String>,
    val stagedChanged: List<String>,
    val stagedRemoved: List<String>,
    val unstagedModified: List<String>,
    val unstagedRemoved: List<String>,
    val untracked: List<String>,
    val conflicting: List<String>
) {
    val stagedPaths: List<String>
        get() = (stagedAdded + stagedChanged + stagedRemoved).distinct().sorted()
}

data class GitCommitResult(val abbreviatedId: String, val fullId: String, val message: String)

class GitRepositoryManager(context: Context) {
    private val workspace = WorkspaceManager(context.filesDir).root
    private fun repoDir(): File = File(workspace, "repository")

    fun cloneRepository(url: String, token: String): String {
        require(url.startsWith("https://", true)) { "Only HTTPS Git remotes are supported." }
        require(url.startsWith("https://github.com/", true)) { "Only GitHub HTTPS repositories are supported." }
        require(!repoDir().exists() || repoDir().listFiles().isNullOrEmpty()) {
            "Repository folder already exists and is not empty."
        }
        Git.cloneRepository()
            .setURI(url)
            .setDirectory(repoDir())
            .setCredentialsProvider(credentials(token))
            .setCloneAllBranches(true)
            .call().use { git -> return "Cloned ${git.repository.branch} into app-private workspace." }
    }

    fun status(): GitStatus = withGit { git ->
        val s = git.status().call()
        GitStatus(
            branch = git.repository.branch ?: "(detached HEAD)",
            stagedAdded = s.added.toList().sorted(),
            stagedChanged = s.changed.toList().sorted(),
            stagedRemoved = s.removed.toList().sorted(),
            unstagedModified = s.modified.toList().sorted(),
            unstagedRemoved = s.missing.toList().sorted(),
            untracked = s.untracked.toList().sorted(),
            conflicting = s.conflicting.toList().sorted()
        )
    }

    /** Explicitly stage only the supplied workspace-relative paths. */
    fun stage(paths: List<String>): String = withGit { git ->
        val safe = validatePaths(paths)
        require(safe.isNotEmpty()) { "Select at least one path to stage." }
        safe.forEach { git.add().addFilepattern(it).call() }
        "Staged ${safe.size} selected path(s)."
    }

    /** Remove selected paths from the index without changing their working-tree contents. */
    fun unstage(paths: List<String>): String = withGit { git ->
        val safe = validatePaths(paths)
        require(safe.isNotEmpty()) { "Select at least one path to unstage." }
        safe.forEach { git.reset().addPath(it).call() }
        "Unstaged ${safe.size} selected path(s)."
    }

    fun unstagedDiff(maxBytes: Int = 512 * 1024): String = diff(cached = false, maxBytes = maxBytes)
    fun stagedDiff(maxBytes: Int = 512 * 1024): String = diff(cached = true, maxBytes = maxBytes)

    private fun diff(cached: Boolean, maxBytes: Int): String = withGit { git ->
        require(maxBytes in 1024..2_000_000)
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setDetectRenames(true)
            val entries = git.diff().setCached(cached).call()
            for (entry in entries) {
                if (output.size() >= maxBytes) break
                formatter.format(entry)
            }
            formatter.flush()
        }
        val bytes = output.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) {
            if (cached) "No staged changes." else "No unstaged tracked-file changes."
        } else if (bytes.size >= maxBytes) {
            text + "\n[Diff truncated at ${maxBytes} bytes]"
        } else text
    }

    /** Commits only the current index. This method never stages files. */
    fun commitStaged(message: String, authorName: String, authorEmail: String, expectedStagedPaths: Set<String>? = null): GitCommitResult = withGit { git ->
        require(message.isNotBlank() && message.length <= 500) { "Commit message must be 1–500 characters." }
        require(authorName.isNotBlank() && authorName.length <= 120) { "Git author name is required." }
        require(authorEmail.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(authorEmail).matches()) {
            "Enter a valid Git author email."
        }
        val status = git.status().call()
        require(status.conflicting.isEmpty()) { "Resolve merge conflicts before committing." }
        val actualStagedPaths = (status.added + status.changed + status.removed).toSet()
        require(actualStagedPaths.isNotEmpty()) {
            "Nothing is staged. Stage selected files before committing."
        }
        if (expectedStagedPaths != null) {
            require(actualStagedPaths == expectedStagedPaths) {
                "The staged file set changed after confirmation. Review status and confirm again."
            }
        }
        val commit = git.commit().setMessage(message.trim())
            .setAuthor(authorName.trim(), authorEmail.trim())
            .setCommitter(authorName.trim(), authorEmail.trim())
            .call()
        GitCommitResult(commit.id.abbreviate(10).name(), commit.id.name, commit.shortMessage)
    }

    fun pull(token: String): String = withGit { git ->
        require(git.status().call().isClean) {
            "Working tree must be clean before pulling. Commit or safely preserve local changes first."
        }
        val result = git.pull().setCredentialsProvider(credentials(token)).call()
        val status = git.status().call()
        when {
            status.conflicting.isNotEmpty() ->
                "Pull produced merge conflicts in: ${status.conflicting.sorted().joinToString()}"
            result.isSuccessful -> "Pull completed (${result.mergeResult?.mergeStatus ?: "up to date"})."
            else -> "Pull did not complete cleanly: ${result.mergeResult?.mergeStatus ?: "unknown status"}"
        }
    }

    fun push(token: String): String = withGit { git ->
        val results = git.push().setCredentialsProvider(credentials(token)).call()
        val rejected = results.flatMap { it.remoteUpdates }
            .filter { it.status.name !in setOf("OK", "UP_TO_DATE") }
        if (rejected.isNotEmpty()) {
            "Push was not fully successful: " + rejected.joinToString { "${it.remoteName}: ${it.status} ${it.message.orEmpty()}" }
        } else {
            "Push completed: " + results.joinToString { it.messages }.ifBlank { "remote accepted updates" }
        }
    }

    fun branches(): List<String> = withGit { git ->
        git.branchList().call().map { ref ->
            val name = RepositoryNames.shorten(ref.name)
            if (name == git.repository.branch) "* $name" else name
        }.sorted()
    }

    fun createBranch(name: String): String = withGit { git ->
        validateBranchName(name)
        git.branchCreate().setName(name).call()
        "Created branch $name."
    }

    fun checkoutBranch(name: String): String = withGit { git ->
        validateBranchName(name)
        val status = git.status().call()
        require(status.isClean) { "Working tree is not clean. Commit or safely stash changes before switching branches." }
        git.checkout().setName(name).call()
        "Checked out $name."
    }

    private fun validateBranchName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9._/-]{1,100}")) &&
            !name.startsWith("/") && !name.endsWith("/") && !name.contains("..") &&
            !name.contains("//") && !name.endsWith(".lock")) { "Invalid branch name." }
    }

    private fun validatePaths(paths: List<String>): List<String> {
        require(paths.size <= 200) { "At most 200 paths can be changed at once." }
        val normalized = paths.map { it.trim().replace('\\', '/') }.distinct()
        normalized.forEach { path ->
            require(path.isNotBlank() && path.length <= 500) { "Invalid empty or oversized path." }
            require(!path.startsWith("/") && !path.startsWith("../") && !path.split('/').contains("..")) {
                "Path escapes repository: $path"
            }
            require(path != "." && path != ".git" && !path.startsWith(".git/")) {
                "Repository metadata and stage-all pathspecs are blocked."
            }
            require(path.none { it in "*?[]" }) {
                "Wildcard pathspecs are blocked; provide an exact path."
            }
            require(!path.contains('\u0000')) { "Invalid path." }
        }
        return normalized
    }

    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider("x-access-token", token)

    private fun <T> withGit(block: (Git) -> T): T {
        val dir = repoDir()
        require(File(dir, ".git").exists()) { "No repository cloned yet." }
        return Git.open(dir).use(block)
    }
}

private object RepositoryNames {
    fun shorten(ref: String): String =
        ref.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_REMOTES)
}
