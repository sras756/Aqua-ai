package com.example.core.workspace

import com.example.core.common.CryptoHashing
import com.example.core.database.BookmarkDao
import com.example.core.database.BookmarkEntity
import com.example.core.database.SyncStatus
import com.example.core.database.WorkspaceDocumentDao
import com.example.core.database.WorkspaceDocumentEntity
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WorkspaceMode {
    USER_VIEW,
    AI_WORKSPACE
}

object WorkspaceFolderType {
    const val RESEARCH = "RESEARCH"
    const val AI_DOCUMENTS = "AI_DOCUMENTS"
}

/**
 * Dual-mode Workspace & Bookmark engine backed by encrypted Room + FTS4 search
 * and queued Google Drive sync (`Aqua AI/Research/`, `Aqua AI/AI documents/`, `Aqua AI/Browser bookmarks/`).
 */
class AquaWorkspaceEngine(
    private val workspaceDao: WorkspaceDocumentDao,
    private val bookmarkDao: BookmarkDao,
    private val driveSyncCoordinator: AquaDriveSyncCoordinator? = null,
    private val userIdProvider: () -> String = { "default_user" }
) {
    private val _mode = MutableStateFlow(WorkspaceMode.USER_VIEW)
    val mode: StateFlow<WorkspaceMode> = _mode.asStateFlow()

    fun setWorkspaceMode(newMode: WorkspaceMode) {
        _mode.value = newMode
    }

    fun observeDocuments(userId: String = userIdProvider()): Flow<List<WorkspaceDocumentEntity>> =
        workspaceDao.observeDocuments(userId.ifBlank { "default_user" })

    fun observeBookmarks(userId: String = userIdProvider()): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeBookmarks(userId.ifBlank { "default_user" })

    /**
     * AI/Agent creation or modification of workspace artifacts requires AI_WORKSPACE mode.
     * In USER_VIEW, AI write mutations are strictly blocked to prevent accidental corruption of structured records.
     */
    suspend fun saveDocumentByAgent(
        id: String = "",
        title: String,
        content: String,
        folderType: String = WorkspaceFolderType.RESEARCH,
        sourceUrls: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ): WorkspaceDocumentEntity = withContext(Dispatchers.IO) {
        if (_mode.value != WorkspaceMode.AI_WORKSPACE) {
            throw SecurityException("Workspace is in read-only USER VIEW. Switch to AI WORKSPACE to create or modify structured AI research documents.")
        }
        upsertWorkspaceDocInternal(
            id = id,
            title = title,
            content = content,
            folderType = folderType,
            sourceUrls = sourceUrls,
            tags = tags,
            scheduleDriveSync = scheduleDriveSync,
            userId = userId
        )
    }

    /**
     * Allows user-initiated annotation/edit even when returning from USER VIEW review.
     */
    suspend fun annotateDocumentByUser(
        docId: String,
        annotationLine: String,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ): WorkspaceDocumentEntity? = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val existing = workspaceDao.getDocuments(uid).firstOrNull { it.id == docId } ?: return@withContext null
        val updatedContent = buildString {
            append(existing.content.trimEnd())
            append("\n\n> [User Annotation]: ")
            append(annotationLine.trim())
        }
        upsertWorkspaceDocInternal(
            id = existing.id,
            title = existing.title,
            content = updatedContent,
            folderType = existing.folderType,
            sourceUrls = parseJsonStringList(existing.sourceUrlsJson),
            tags = parseJsonStringList(existing.tagsJson),
            scheduleDriveSync = scheduleDriveSync,
            userId = uid
        )
    }

    suspend fun searchDocuments(
        query: String,
        userId: String = userIdProvider()
    ): List<WorkspaceDocumentEntity> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val clean = query.trim()
        if (clean.isBlank()) return@withContext workspaceDao.getDocuments(uid)
        val ftsQuery = clean.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.isNotBlank() }
            .joinToString(" AND ") { "$it*" }
        val fts = if (ftsQuery.isNotBlank()) {
            runCatching { workspaceDao.searchDocumentsFts(uid, ftsQuery) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (fts.isNotEmpty()) return@withContext fts
        val lower = clean.lowercase()
        workspaceDao.getDocuments(uid).filter {
            it.title.lowercase().contains(lower) || it.content.lowercase().contains(lower)
        }
    }

    suspend fun exportDocumentMarkdown(docId: String, userId: String = userIdProvider()): String? = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val doc = workspaceDao.getDocuments(uid).firstOrNull { it.id == docId } ?: return@withContext null
        val urls = parseJsonStringList(doc.sourceUrlsJson)
        buildString {
            appendLine("# ${doc.title}")
            appendLine("Folder: ${doc.folderType} | SHA-256: `${doc.sha256Hash.take(16)}`")
            if (urls.isNotEmpty()) {
                appendLine("\n## Verifiable Sources")
                urls.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine(doc.content)
        }
    }

    suspend fun deleteDocumentPermanently(
        docId: String,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ) = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val existing = workspaceDao.getDocuments(uid).firstOrNull { it.id == docId }
        workspaceDao.deleteDocument(docId)
        val folder = if (existing?.folderType == WorkspaceFolderType.RESEARCH) {
            AquaDriveFolders.RESEARCH
        } else {
            AquaDriveFolders.AI_DOCUMENTS
        }
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "workspace_doc",
            entityId = docId,
            driveFolder = folder,
            fileName = "doc_$docId.json",
            operation = "DELETE",
            serializedPayloadJson = JSONObject().put("id", docId).put("deleted", true).toString(),
            scheduleBackgroundWork = scheduleDriveSync
        )
    }

    suspend fun saveBookmark(
        title: String,
        url: String,
        folder: String = "General",
        isIncognito: Boolean = false,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ): Result<BookmarkEntity> = withContext(Dispatchers.IO) {
        if (isIncognito) {
            return@withContext Result.failure(
                SecurityException("Incognito mode blocks saving bookmarks or browsing history.")
            )
        }
        val uid = userId.ifBlank { "default_user" }
        val bid = "bm_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val entity = BookmarkEntity(
            id = bid,
            userId = uid,
            title = title.trim().ifBlank { url },
            url = url.trim(),
            folder = folder.ifBlank { "General" },
            syncStatus = SyncStatus.PENDING_OFFLINE
        )
        bookmarkDao.upsertBookmark(entity)
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "bookmark",
            entityId = bid,
            driveFolder = AquaDriveFolders.BROWSER_BOOKMARKS,
            fileName = "bookmark_$bid.json",
            operation = "UPSERT",
            serializedPayloadJson = JSONObject()
                .put("id", bid)
                .put("title", entity.title)
                .put("url", entity.url)
                .put("folder", entity.folder)
                .toString(),
            scheduleBackgroundWork = scheduleDriveSync
        )
        Result.success(entity)
    }

    suspend fun deleteBookmarkPermanently(
        bookmarkId: String,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ) = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        bookmarkDao.deleteBookmark(bookmarkId)
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "bookmark",
            entityId = bookmarkId,
            driveFolder = AquaDriveFolders.BROWSER_BOOKMARKS,
            fileName = "bookmark_$bookmarkId.json",
            operation = "DELETE",
            serializedPayloadJson = JSONObject().put("id", bookmarkId).put("deleted", true).toString(),
            scheduleBackgroundWork = scheduleDriveSync
        )
    }

    private suspend fun upsertWorkspaceDocInternal(
        id: String,
        title: String,
        content: String,
        folderType: String,
        sourceUrls: List<String>,
        tags: List<String>,
        scheduleDriveSync: Boolean,
        userId: String
    ): WorkspaceDocumentEntity {
        val uid = userId.ifBlank { "default_user" }
        val cleanId = id.ifBlank { "doc_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val normalizedFolder = if (folderType.uppercase() == WorkspaceFolderType.RESEARCH) {
            WorkspaceFolderType.RESEARCH
        } else {
            WorkspaceFolderType.AI_DOCUMENTS
        }
        val sha = CryptoHashing.sha256Hex(content.toByteArray(Charsets.UTF_8))
        val urlsJson = JSONArray().apply { sourceUrls.forEach { put(it) } }.toString()
        val tagsJson = JSONArray().apply { tags.forEach { put(it) } }.toString()
        val entity = WorkspaceDocumentEntity(
            id = cleanId,
            userId = uid,
            title = title.trim().ifBlank { "Untitled Document" },
            folderType = normalizedFolder,
            format = "MARKDOWN",
            content = content,
            sourceUrlsJson = urlsJson,
            tagsJson = tagsJson,
            sha256Hash = sha,
            updatedAtMs = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING_OFFLINE
        )
        workspaceDao.upsertDocument(entity)

        val driveFolder = if (normalizedFolder == WorkspaceFolderType.RESEARCH) {
            AquaDriveFolders.RESEARCH
        } else {
            AquaDriveFolders.AI_DOCUMENTS
        }
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "workspace_doc",
            entityId = cleanId,
            driveFolder = driveFolder,
            fileName = "doc_$cleanId.json",
            operation = "UPSERT",
            serializedPayloadJson = JSONObject()
                .put("id", entity.id)
                .put("title", entity.title)
                .put("folderType", entity.folderType)
                .put("content", entity.content)
                .put("sourceUrlsJson", entity.sourceUrlsJson)
                .put("sha256Hash", entity.sha256Hash)
                .toString(),
            scheduleBackgroundWork = scheduleDriveSync
        )
        return entity
    }

    private fun parseJsonStringList(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}
