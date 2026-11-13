package com.example.mobileagent.agent.runtime

/** One source of truth for tool names, descriptions and permission classes. */
enum class ToolPermission { READ_ONLY, USER_APPROVAL, BLOCKED }

data class ToolDefinition(val name: String, val description: String, val permission: ToolPermission)

class ToolRegistry(definitions: List<ToolDefinition>) {
    private val byName = definitions.associateBy { it.name }
    init { require(byName.size == definitions.size) { "Tool names must be unique." } }
    fun find(name: String): ToolDefinition? = byName[name]
    fun all(): List<ToolDefinition> = byName.values.sortedBy { it.name }
    fun allowedWithoutApproval(name: String): Boolean = byName[name]?.permission == ToolPermission.READ_ONLY

    companion object {
        fun v3Defaults() = ToolRegistry(listOf(
            ToolDefinition("list_files", "List project files", ToolPermission.READ_ONLY),
            ToolDefinition("read_file", "Read a project file", ToolPermission.READ_ONLY),
            ToolDefinition("web_search", "Search the web", ToolPermission.READ_ONLY),
            ToolDefinition("fetch_webpage", "Retrieve a webpage as untrusted content", ToolPermission.READ_ONLY),
            ToolDefinition("list_saved_research", "List saved research", ToolPermission.READ_ONLY),
            ToolDefinition("read_saved_research", "Read saved research as untrusted content", ToolPermission.READ_ONLY),
            ToolDefinition("git_status", "Inspect Git status", ToolPermission.READ_ONLY),
            ToolDefinition("git_diff_summary", "Inspect a Git diff summary", ToolPermission.READ_ONLY),
            ToolDefinition("git_branches", "List Git branches", ToolPermission.READ_ONLY),
            ToolDefinition("github_actions_runs", "Inspect workflow runs", ToolPermission.READ_ONLY),
            ToolDefinition("github_actions_jobs", "Inspect workflow jobs", ToolPermission.READ_ONLY),
            ToolDefinition("github_actions_logs", "Read workflow logs", ToolPermission.READ_ONLY),
            ToolDefinition("github_actions_artifacts", "List workflow artifacts", ToolPermission.READ_ONLY),
            ToolDefinition("propose_write_file", "Propose a file write for approval", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_git_pull", "Propose a pull for approval", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_git_push", "Propose a push for approval", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_trigger_workflow", "Propose workflow dispatch for approval", ToolPermission.USER_APPROVAL),
            // The legacy ToolGateway commit method stages all files. It is deliberately blocked here.
            ToolDefinition("propose_git_commit", "Legacy stage-all commit is blocked in multi-agent mode", ToolPermission.BLOCKED),
            ToolDefinition("execute_command", "Command execution is not exposed to multi-agent mode", ToolPermission.BLOCKED)
        ))
    }
}
