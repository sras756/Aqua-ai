package com.example.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ssot_conversations",
    indices = [Index(value = ["userId", "updatedAtMs"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val summary: String = "",
    val modelUsed: String = "",
    val messageCount: Long = 0L,
    val isPinned: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val driveFileId: String = "",
    val syncStatus: String = SyncStatus.PENDING_OFFLINE
)

@Entity(
    tableName = "ssot_chat_messages",
    indices = [Index(value = ["conversationId", "createdAtMs"]), Index(value = ["userId"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userId: String,
    val role: String,
    val content: String,
    val modelUsed: String = "",
    val attachmentName: String = "",
    val attachmentMime: String = "",
    val toolActionsJson: String = "",
    val groundingSourcesJson: String = "",
    val tokenCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssot_memories",
    indices = [Index(value = ["userId", "tier", "updatedAtMs"])]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val tier: String = MemoryTier.LONG_TERM,
    val category: String = "fact",
    val title: String,
    val content: String,
    val confidence: Float = 1.0f,
    val sourceProvenance: String = "user",
    val isPinned: Boolean = false,
    val approvedByUser: Boolean = true,
    val deletedLocally: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val driveFileId: String = "",
    val syncStatus: String = SyncStatus.PENDING_OFFLINE
)

@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "ssot_memories_fts")
data class MemoryFtsEntity(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "category") val category: String
)

@Entity(
    tableName = "ssot_workspace_documents",
    indices = [Index(value = ["userId", "folderType", "updatedAtMs"])]
)
data class WorkspaceDocumentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val folderType: String = "AI_DOCUMENTS",
    val format: String = "MARKDOWN",
    val content: String,
    val sourceUrlsJson: String = "[]",
    val tagsJson: String = "[]",
    val sha256Hash: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val driveFileId: String = "",
    val syncStatus: String = SyncStatus.PENDING_OFFLINE
)

@Fts4(contentEntity = WorkspaceDocumentEntity::class)
@Entity(tableName = "ssot_workspace_docs_fts")
data class WorkspaceDocumentFtsEntity(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content") val content: String
)

@Entity(
    tableName = "ssot_bookmarks",
    indices = [Index(value = ["userId", "updatedAtMs"])]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val url: String,
    val folder: String = "General",
    val tagsJson: String = "[]",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val driveFileId: String = "",
    val syncStatus: String = SyncStatus.PENDING_OFFLINE
)

@Entity(
    tableName = "ssot_agent_tasks",
    indices = [Index(value = ["userId", "updatedAtMs"])]
)
data class AgentTaskEntity(
    @PrimaryKey val taskId: String,
    val userId: String,
    val title: String,
    val source: String = "chat",
    val state: String,
    val riskTier: Int = 0,
    val planJson: String = "[]",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val resultSummary: String = "",
    val errorMessage: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssot_audit_logs",
    indices = [Index(value = ["userId", "timestampMs"])]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val taskId: String = "",
    val domain: String,
    val action: String,
    val riskTier: Int,
    val outcome: String,
    val redactedDetail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssot_drive_sync_queue",
    indices = [Index(value = ["userId", "status", "localUpdatedAtMs"])]
)
data class DriveSyncQueueEntity(
    @PrimaryKey val mutationId: String,
    val userId: String,
    val entityType: String,
    val entityId: String,
    val driveFolder: String,
    val fileName: String,
    val operation: String = "UPSERT",
    val payloadSha256: String,
    val serializedPayloadJson: String,
    val localUpdatedAtMs: Long = System.currentTimeMillis(),
    val status: String = SyncStatus.PENDING_OFFLINE,
    val retryCount: Int = 0,
    val lastError: String = "",
    val conflictCopyFileId: String = ""
)

@Entity(
    tableName = "ssot_drive_file_metadata",
    indices = [Index(value = ["userId", "driveFolder", "name"])]
)
data class DriveFileMetadataEntity(
    @PrimaryKey val fileId: String,
    val userId: String,
    val driveFolder: String,
    val name: String,
    val mimeType: String = "application/json",
    val modifiedTimeIso: String = "",
    val versionTag: String = "",
    val localSha256: String = "",
    val sizeBytes: Long = 0L,
    val lastSyncedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssot_ai_usage_meter",
    indices = [Index(value = ["userId", "timestampMs"])]
)
data class AiUsageMeterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val sessionId: String,
    val modelId: String,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val latencyMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssot_automation_workflows",
    indices = [Index(value = ["userId", "updatedAtMs"])]
)
data class AutomationWorkflowEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val description: String = "",
    val triggerType: String = "MANUAL",
    val intervalMinutes: Long = 0L,
    val stepsJson: String = "[]",
    val enabled: Boolean = true,
    val riskTier: Int = 1,
    val lastRunAtMs: Long = 0L,
    val lastRunStatus: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

object MemoryTier {
    const val SHORT_TERM = "SHORT_TERM"
    const val LONG_TERM = "LONG_TERM"
    const val EPISODIC = "EPISODIC"
}

object SyncStatus {
    const val SYNCED = "SYNCED"
    const val SYNCING = "SYNCING"
    const val PENDING_OFFLINE = "PENDING_OFFLINE"
    const val CONFLICT = "CONFLICT"
    const val ERROR = "ERROR"
}
