package com.example.mobileagent.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects", indices = [Index(value = ["rootPath"], unique = true)])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootPath: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "sessions", indices = [Index("projectId"), Index("updatedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String = "active"
)

@Entity(tableName = "messages", indices = [Index("sessionId"), Index("createdAt")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val createdAt: Long
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1") suspend fun findById(id: String): ProjectEntity?
    @Query("SELECT * FROM projects WHERE rootPath = :path LIMIT 1") suspend fun findByPath(path: String): ProjectEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(project: ProjectEntity)
    @Update suspend fun update(project: ProjectEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC") fun observeForProject(projectId: String): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1") suspend fun findById(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(projectId: String): SessionEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(session: SessionEntity)
    @Update suspend fun update(session: SessionEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC") fun observeForSession(sessionId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC") suspend fun listForSession(sessionId: String): List<MessageEntity>
    @Insert suspend fun insert(message: MessageEntity)
    @Query("DELETE FROM messages WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: String)
}

@Database(entities = [ProjectEntity::class, SessionEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AgentDatabase? = null
        fun get(context: Context): AgentDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "mobile-agent-v3.db")
                .build().also { INSTANCE = it }
        }
    }
}
