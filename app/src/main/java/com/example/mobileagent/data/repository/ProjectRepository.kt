package com.example.mobileagent.data.repository

import android.content.Context
import com.example.mobileagent.data.local.AgentDatabase
import com.example.mobileagent.data.local.ProjectEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class ProjectRepository(context: Context) {
    private val dao = AgentDatabase.get(context).projectDao()
    private val projectsRoot = File(context.filesDir, "workspace/projects").apply { mkdirs() }

    val projects: Flow<List<ProjectEntity>> = dao.observeAll()

    suspend fun ensureDefaultProject(): ProjectEntity {
        val defaultPath = File(projectsRoot, "default").canonicalFile
        defaultPath.mkdirs()
        dao.findByPath(defaultPath.path)?.let { return it }
        return ProjectEntity(UUID.randomUUID().toString(), "My first project", defaultPath.path,
            System.currentTimeMillis(), System.currentTimeMillis()).also { dao.insert(it) }
    }

    suspend fun createProject(name: String): ProjectEntity {
        val cleanName = name.trim().take(60)
        require(cleanName.isNotBlank()) { "Project name cannot be empty." }
        val folderName = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            .ifBlank { "project" } + "-" + UUID.randomUUID().toString().take(8)
        val folder = File(projectsRoot, folderName).canonicalFile
        require(folder.toPath().startsWith(projectsRoot.canonicalFile.toPath())) { "Invalid project path." }
        check(folder.mkdirs()) { "Could not create project workspace." }
        return ProjectEntity(UUID.randomUUID().toString(), cleanName, folder.path,
            System.currentTimeMillis(), System.currentTimeMillis()).also { dao.insert(it) }
    }
}
