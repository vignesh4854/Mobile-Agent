package com.example.mobileagent.domain.model

data class AgentProject(val id: String, val name: String, val rootPath: String)

enum class AgentRole { PLANNER, CODER, REVIEWER, DEBUGGER }

data class AgentTask(val id: String, val description: String, val role: AgentRole,
                     val dependsOn: List<String> = emptyList())
