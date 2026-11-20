package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM ssot_conversations WHERE userId = :userId ORDER BY isPinned DESC, updatedAtMs DESC")
    fun observeConversations(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM ssot_conversations WHERE userId = :userId ORDER BY updatedAtMs DESC")
    suspend fun getConversations(userId: String): List<ConversationEntity>

    @Query("SELECT * FROM ssot_conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM ssot_conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM ssot_chat_messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    fun observeMessages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM ssot_chat_messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    suspend fun getMessages(conversationId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM ssot_chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM ssot_conversations WHERE userId = :userId")
    suspend fun clearAllConversationsForUser(userId: String)

    @Query("DELETE FROM ssot_chat_messages WHERE userId = :userId")
    suspend fun clearAllMessagesForUser(userId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM ssot_memories WHERE userId = :userId AND deletedLocally = 0 ORDER BY isPinned DESC, updatedAtMs DESC")
    fun observeMemories(userId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM ssot_memories WHERE userId = :userId AND deletedLocally = 0 ORDER BY isPinned DESC, updatedAtMs DESC")
    suspend fun getMemories(userId: String): List<MemoryEntity>

    @Query(
        """
        SELECT m.* FROM ssot_memories m
        JOIN ssot_memories_fts fts ON m.rowid = fts.rowid
        WHERE m.userId = :userId AND m.deletedLocally = 0 AND ssot_memories_fts MATCH :ftsQuery
        ORDER BY m.isPinned DESC, m.updatedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun searchMemoriesFts(userId: String, ftsQuery: String, limit: Int = 30): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("DELETE FROM ssot_memories WHERE id = :memoryId")
    suspend fun hardDeleteMemory(memoryId: String)

    @Query("DELETE FROM ssot_memories WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Dao
interface WorkspaceDocumentDao {
    @Query("SELECT * FROM ssot_workspace_documents WHERE userId = :userId ORDER BY updatedAtMs DESC")
    fun observeDocuments(userId: String): Flow<List<WorkspaceDocumentEntity>>

    @Query("SELECT * FROM ssot_workspace_documents WHERE userId = :userId ORDER BY updatedAtMs DESC")
    suspend fun getDocuments(userId: String): List<WorkspaceDocumentEntity>

    @Query(
        """
        SELECT d.* FROM ssot_workspace_documents d
        JOIN ssot_workspace_docs_fts fts ON d.rowid = fts.rowid
        WHERE d.userId = :userId AND ssot_workspace_docs_fts MATCH :ftsQuery
        ORDER BY d.updatedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun searchDocumentsFts(userId: String, ftsQuery: String, limit: Int = 20): List<WorkspaceDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: WorkspaceDocumentEntity)

    @Query("DELETE FROM ssot_workspace_documents WHERE id = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("DELETE FROM ssot_workspace_documents WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM ssot_bookmarks WHERE userId = :userId ORDER BY updatedAtMs DESC")
    fun observeBookmarks(userId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM ssot_bookmarks WHERE userId = :userId ORDER BY updatedAtMs DESC")
    suspend fun getBookmarks(userId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM ssot_bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: String)
}

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM ssot_agent_tasks WHERE userId = :userId ORDER BY updatedAtMs DESC LIMIT 50")
    fun observeTasks(userId: String): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM ssot_agent_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): AgentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AgentTaskEntity)

    @Query("DELETE FROM ssot_agent_tasks WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM ssot_audit_logs WHERE userId = :userId ORDER BY timestampMs DESC LIMIT :limit")
    fun observeAuditLogs(userId: String, limit: Int = 100): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM ssot_audit_logs WHERE userId = :userId ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentAuditLogs(userId: String, limit: Int = 100): List<AuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendLog(entry: AuditLogEntity)
}

@Dao
interface DriveSyncQueueDao {
    @Query("SELECT * FROM ssot_drive_sync_queue WHERE userId = :userId ORDER BY localUpdatedAtMs ASC")
    fun observeQueue(userId: String): Flow<List<DriveSyncQueueEntity>>

    @Query("SELECT * FROM ssot_drive_sync_queue WHERE userId = :userId AND status IN ('PENDING_OFFLINE', 'ERROR') ORDER BY localUpdatedAtMs ASC LIMIT :limit")
    suspend fun getPendingMutations(userId: String, limit: Int = 50): List<DriveSyncQueueEntity>

    @Query("SELECT COUNT(*) FROM ssot_drive_sync_queue WHERE userId = :userId AND status IN ('PENDING_OFFLINE', 'SYNCING', 'ERROR')")
    fun observePendingCount(userId: String): Flow<Int>

    @Query("SELECT * FROM ssot_drive_sync_queue WHERE userId = :userId AND status = 'CONFLICT'")
    fun observeConflicts(userId: String): Flow<List<DriveSyncQueueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueMutation(mutation: DriveSyncQueueEntity)

    @Query("DELETE FROM ssot_drive_sync_queue WHERE mutationId = :mutationId")
    suspend fun removeMutation(mutationId: String)

    @Query("DELETE FROM ssot_drive_sync_queue WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Dao
interface DriveFileMetadataDao {
    @Query("SELECT * FROM ssot_drive_file_metadata WHERE userId = :userId ORDER BY lastSyncedAtMs DESC")
    fun observeFiles(userId: String): Flow<List<DriveFileMetadataEntity>>

    @Query("SELECT * FROM ssot_drive_file_metadata WHERE userId = :userId AND driveFolder = :folder ORDER BY name COLLATE NOCASE ASC")
    suspend fun getFilesInFolder(userId: String, folder: String): List<DriveFileMetadataEntity>

    @Query("SELECT * FROM ssot_drive_file_metadata WHERE fileId = :fileId LIMIT 1")
    suspend fun getById(fileId: String): DriveFileMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: DriveFileMetadataEntity)

    @Query("DELETE FROM ssot_drive_file_metadata WHERE fileId = :fileId")
    suspend fun deleteMetadata(fileId: String)
}

@Dao
interface AiUsageMeterDao {
    @Query("SELECT * FROM ssot_ai_usage_meter WHERE userId = :userId ORDER BY timestampMs DESC LIMIT 100")
    fun observeUsage(userId: String): Flow<List<AiUsageMeterEntity>>

    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM ssot_ai_usage_meter WHERE userId = :userId")
    fun observeTotalTokens(userId: String): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordUsage(entry: AiUsageMeterEntity)
}

@Dao
interface AutomationWorkflowDao {
    @Query("SELECT * FROM ssot_automation_workflows WHERE userId = :userId ORDER BY updatedAtMs DESC")
    fun observeWorkflows(userId: String): Flow<List<AutomationWorkflowEntity>>

    @Query("SELECT * FROM ssot_automation_workflows WHERE id = :id LIMIT 1")
    suspend fun getWorkflow(id: String): AutomationWorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(workflow: AutomationWorkflowEntity)

    @Query("DELETE FROM ssot_automation_workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String)
}
