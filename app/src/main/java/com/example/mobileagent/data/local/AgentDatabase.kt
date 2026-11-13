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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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


@Entity(tableName = "agent_runs", indices = [Index("createdAt")])
data class AgentRunEntity(
    @PrimaryKey val runId: String,
    val task: String,
    val status: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val estimatedPromptTokens: Long = 0,
    val estimatedCompletionTokens: Long = 0,
    val durationMillis: Long = 0
)

@Entity(tableName = "agent_traces", indices = [Index("runId"), Index("timestamp")])
data class AgentTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val role: String?,
    val eventType: String,
    val detail: String,
    val estimatedPromptTokens: Long,
    val estimatedCompletionTokens: Long,
    val durationMillis: Long,
    val timestamp: Long
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


@Dao
interface AgentRunDao {
    @Query("SELECT * FROM agent_runs ORDER BY createdAt DESC LIMIT 30")
    fun observeRecentRuns(): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_traces WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    fun observeTraces(runId: String): Flow<List<AgentTraceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: AgentRunEntity)

    @Insert
    suspend fun insertTrace(trace: AgentTraceEntity)

    @Query("UPDATE agent_runs SET status = :status, finishedAt = :finishedAt, estimatedPromptTokens = :promptTokens, estimatedCompletionTokens = :completionTokens, durationMillis = :durationMillis WHERE runId = :runId")
    suspend fun finishRun(runId: String, status: String, finishedAt: Long, promptTokens: Long,
                          completionTokens: Long, durationMillis: Long)

    @Query("DELETE FROM agent_traces WHERE runId NOT IN (SELECT runId FROM agent_runs ORDER BY createdAt DESC LIMIT 30)")
    suspend fun pruneOldTraces()

    @Query("DELETE FROM agent_runs WHERE runId NOT IN (SELECT runId FROM agent_runs ORDER BY createdAt DESC LIMIT 30)")
    suspend fun pruneOldRuns()
}

@Database(entities = [ProjectEntity::class, SessionEntity::class, MessageEntity::class, AgentRunEntity::class, AgentTraceEntity::class], version = 2, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentRunDao(): AgentRunDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_runs (runId TEXT NOT NULL PRIMARY KEY, task TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, finishedAt INTEGER, estimatedPromptTokens INTEGER NOT NULL, estimatedCompletionTokens INTEGER NOT NULL, durationMillis INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_runs_createdAt ON agent_runs(createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_traces (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId TEXT NOT NULL, role TEXT, eventType TEXT NOT NULL, detail TEXT NOT NULL, estimatedPromptTokens INTEGER NOT NULL, estimatedCompletionTokens INTEGER NOT NULL, durationMillis INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_traces_runId ON agent_traces(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_traces_timestamp ON agent_traces(timestamp)")
            }
        }
        @Volatile private var INSTANCE: AgentDatabase? = null
        fun get(context: Context): AgentDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "mobile-agent-v3.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
        }
    }
}
