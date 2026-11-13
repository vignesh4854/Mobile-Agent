package com.example.mobileagent.agent.runtime

/** A single source of truth for tool names, descriptions and permission classes. */
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
            ToolDefinition("git_status", "Inspect Git status", ToolPermission.READ_ONLY),
            ToolDefinition("git_diff_summary", "Inspect a Git diff summary", ToolPermission.READ_ONLY),
            ToolDefinition("propose_write_file", "Propose a file change for approval", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_git_commit", "Propose a Git commit for approval", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_git_pull", "Propose pulling remote changes", ToolPermission.USER_APPROVAL),
            ToolDefinition("propose_git_push", "Propose publishing commits", ToolPermission.USER_APPROVAL),
            ToolDefinition("execute_command", "Execute a local command", ToolPermission.BLOCKED)
        ))
    }
}
