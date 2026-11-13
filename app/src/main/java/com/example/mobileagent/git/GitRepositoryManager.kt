package com.example.mobileagent.git

import android.content.Context
import com.example.mobileagent.security.WorkspaceManager
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

data class GitStatus(
    val branch: String,
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    val untracked: List<String>,
    val conflicting: List<String>
)

class GitRepositoryManager(context: Context) {
    private val workspace = WorkspaceManager(context.filesDir).root

    private fun repoDir(): File = File(workspace, "repository")

    fun cloneRepository(url: String, token: String): String {
        require(url.startsWith("https://", true)) { "Only HTTPS Git remotes are supported." }
        require(url.contains("github.com/")) { "V2.4 clone currently supports GitHub HTTPS repositories." }
        require(!repoDir().exists() || repoDir().listFiles().isNullOrEmpty()) {
            "Repository folder already exists and is not empty. Remove/export it before cloning another repository."
        }
        val credentials = credentials(token)
        Git.cloneRepository()
            .setURI(url)
            .setDirectory(repoDir())
            .setCredentialsProvider(credentials)
            .setCloneAllBranches(true)
            .call().use { git ->
                return "Cloned ${git.repository.branch} into app-private workspace."
            }
    }

    fun status(): GitStatus = withGit { git ->
        val s = git.status().call()
        GitStatus(
            branch = git.repository.branch ?: "(detached HEAD)",
            added = s.added().toList().sorted(),
            changed = (s.changed() + s.modified()).toList().distinct().sorted(),
            removed = (s.removed() + s.missing()).toList().distinct().sorted(),
            untracked = s.untracked().toList().sorted(),
            conflicting = s.conflicting().toList().sorted()
        )
    }

    fun diff(): String = withGit { git ->
        val output = StringBuilder()
        git.diff().call().forEach { entry ->
            output.append("diff -- ").append(entry.changeType).append(" ")
                .append(entry.oldPath).append(" -> ").append(entry.newPath).append('\n')
            output.append("Use `git diff` in a full Git client for complete patch formatting.\n")
        }
        if (output.isEmpty()) "No unstaged changes." else output.toString()
    }

    fun commit(message: String, authorName: String, authorEmail: String): String = withGit { git ->
        require(message.isNotBlank()) { "Commit message is required." }
        require(authorName.isNotBlank() && authorEmail.contains("@")) { "Enter a valid Git author name and email." }
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        val commit = git.commit().setMessage(message)
            .setAuthor(authorName, authorEmail)
            .setCommitter(authorName, authorEmail)
            .call()
        "Created commit ${commit.id.abbreviate(10)}"
    }

    fun pull(token: String): String = withGit { git ->
        val result = git.pull().setCredentialsProvider(credentials(token)).call()
        if (result.isSuccessful) "Pull completed." else "Pull did not complete cleanly: ${result.mergeResult?.mergeStatus}"
    }

    fun push(token: String): String = withGit { git ->
        val results = git.push().setCredentialsProvider(credentials(token)).setPushAll().call()
        val messages = results.map { it.messages }.filter { it.isNotBlank() }
        "Push completed. " + messages.joinToString(" ")
    }

    fun branches(): List<String> = withGit { git ->
        git.branchList().call().map { ref ->
            val name = RepositoryNames.shorten(ref.name)
            if (name == git.repository.branch) "* $name" else name
        }.sorted()
    }

    fun createBranch(name: String): String = withGit { git ->
        require(name.matches(Regex("[A-Za-z0-9._/-]{1,100}"))) { "Invalid branch name." }
        git.branchCreate().setName(name).call()
        "Created branch $name. Checkout is not automatic."
    }

    fun checkoutBranch(name: String): String = withGit { git ->
        require(name.matches(Regex("[A-Za-z0-9._/-]{1,100}"))) { "Invalid branch name." }
        git.checkout().setName(name).call()
        "Checked out $name."
    }

    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider(token.ifBlank { "x-access-token" }, token)

    private fun <T> withGit(block: (Git) -> T): T {
        val dir = repoDir()
        require(File(dir, ".git").exists()) { "No repository cloned yet." }
        return Git.open(dir).use(block)
    }
}

private object RepositoryNames {
    fun shorten(ref: String): String = ref.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_REMOTES)
}
