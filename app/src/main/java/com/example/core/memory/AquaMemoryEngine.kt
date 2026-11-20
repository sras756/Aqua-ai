package com.example.core.memory

import com.example.core.database.MemoryDao
import com.example.core.database.MemoryEntity
import com.example.core.database.MemoryTier
import com.example.core.database.SyncStatus
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Aqua Memory Engine (`core:memory`) backed by encrypted Room + FTS4 full-text search
 * and queued Google Drive sync under `Aqua AI/Memory/`.
 */
class AquaMemoryEngine(
    private val memoryDao: MemoryDao,
    private val driveSyncCoordinator: AquaDriveSyncCoordinator? = null,
    private val userIdProvider: () -> String = { "default_user" }
) {
    fun observeMemories(userId: String = userIdProvider()): Flow<List<MemoryEntity>> =
        memoryDao.observeMemories(userId.ifBlank { "default_user" })

    suspend fun getAllMemories(
        userId: String = userIdProvider(),
        tierFilter: String? = null
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val all = memoryDao.getMemories(userId.ifBlank { "default_user" })
        if (tierFilter.isNullOrBlank()) all else all.filter { it.tier == tierFilter }
    }

    suspend fun saveMemory(
        id: String = "",
        title: String,
        content: String,
        category: String = "fact",
        tier: String = MemoryTier.LONG_TERM,
        confidence: Float = 1.0f,
        sourceProvenance: String = "user",
        isPinned: Boolean = false,
        approvedByUser: Boolean = true,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val cleanId = id.ifBlank { "mem_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val existing = memoryDao.getMemories(uid).firstOrNull { it.id == cleanId }
        val now = System.currentTimeMillis()
        val normalizedTier = when (tier.uppercase()) {
            MemoryTier.SHORT_TERM -> MemoryTier.SHORT_TERM
            MemoryTier.EPISODIC -> MemoryTier.EPISODIC
            else -> MemoryTier.LONG_TERM
        }
        val entity = MemoryEntity(
            id = cleanId,
            userId = uid,
            tier = normalizedTier,
            category = category.ifBlank { "fact" },
            title = title.trim().ifBlank { content.trim().take(48) },
            content = content.trim(),
            confidence = confidence.coerceIn(0f, 1f),
            sourceProvenance = sourceProvenance.ifBlank { "user" },
            isPinned = isPinned,
            approvedByUser = approvedByUser,
            deletedLocally = false,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            driveFileId = existing?.driveFileId.orEmpty(),
            syncStatus = SyncStatus.PENDING_OFFLINE
        )
        memoryDao.upsertMemory(entity)
        enqueueMemorySync(entity, operation = "UPSERT", scheduleWork = scheduleDriveSync)
        entity
    }

    suspend fun setPinned(
        memoryId: String,
        pinned: Boolean,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ): MemoryEntity? = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val existing = memoryDao.getMemories(uid).firstOrNull { it.id == memoryId } ?: return@withContext null
        val updated = existing.copy(
            isPinned = pinned,
            updatedAtMs = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING_OFFLINE
        )
        memoryDao.upsertMemory(updated)
        enqueueMemorySync(updated, operation = "UPSERT", scheduleWork = scheduleDriveSync)
        updated
    }

    /**
     * Searches memories using Room FTS4 full-text index with safe tokenization and fallback substring search.
     */
    suspend fun searchMemories(
        query: String,
        tierFilter: String? = null,
        limit: Int = 30,
        userId: String = userIdProvider()
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext getAllMemories(uid, tierFilter).take(limit)
        }

        val ftsQuery = buildFtsQuery(trimmed)
        val ftsHits = if (ftsQuery.isNotBlank()) {
            runCatching { memoryDao.searchMemoriesFts(uid, ftsQuery, limit) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val combined = if (ftsHits.isNotEmpty()) {
            ftsHits
        } else {
            val lower = trimmed.lowercase()
            memoryDao.getMemories(uid).filter { mem ->
                mem.title.lowercase().contains(lower) ||
                    mem.content.lowercase().contains(lower) ||
                    mem.category.lowercase().contains(lower) ||
                    mem.sourceProvenance.lowercase().contains(lower)
            }.take(limit)
        }

        if (tierFilter.isNullOrBlank()) combined else combined.filter { it.tier == tierFilter }
    }

    /**
     * Permanently deletes a memory locally and propagates DELETE mutation to Google Drive (`Aqua AI/Memory/`).
     */
    suspend fun deleteMemoryPermanently(
        memoryId: String,
        scheduleDriveSync: Boolean = true,
        userId: String = userIdProvider()
    ) = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        memoryDao.hardDeleteMemory(memoryId)
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "memory",
            entityId = memoryId,
            driveFolder = AquaDriveFolders.MEMORY,
            fileName = "memory_$memoryId.json",
            operation = "DELETE",
            serializedPayloadJson = JSONObject().put("id", memoryId).put("deleted", true).toString(),
            scheduleBackgroundWork = scheduleDriveSync
        )
    }

    suspend fun exportMemoriesJson(userId: String = userIdProvider()): String = withContext(Dispatchers.IO) {
        val list = getAllMemories(userId)
        val root = JSONObject()
            .put("exportedAtIso", isoFormat.format(Date()))
            .put("count", list.size)
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(serializeMemoryJson(m))
        }
        root.put("memories", arr).toString(2)
    }

    suspend fun exportMemoriesMarkdown(userId: String = userIdProvider()): String = withContext(Dispatchers.IO) {
        val list = getAllMemories(userId)
        if (list.isEmpty()) return@withContext "# Aqua AI Memories\n\nNo saved memories found.\n"
        buildString {
            appendLine("# Aqua AI Persistent Memory Export")
            appendLine("Exported: ${isoFormat.format(Date())} | Total: ${list.size}")
            appendLine()
            list.forEach { m ->
                val pinTag = if (m.isPinned) " [PINNED]" else ""
                appendLine("## ${m.title}$pinTag")
                appendLine("- **Tier:** ${m.tier} | **Category:** ${m.category} | **Confidence:** ${(m.confidence * 100).toInt()}%")
                appendLine("- **Provenance:** ${m.sourceProvenance} | **ID:** `${m.id}`")
                appendLine()
                appendLine(m.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    private suspend fun enqueueMemorySync(
        entity: MemoryEntity,
        operation: String,
        scheduleWork: Boolean
    ) {
        driveSyncCoordinator?.enqueueMutation(
            userId = entity.userId,
            entityType = "memory",
            entityId = entity.id,
            driveFolder = AquaDriveFolders.MEMORY,
            fileName = "memory_${entity.id}.json",
            operation = operation,
            serializedPayloadJson = serializeMemoryJson(entity).toString(),
            scheduleBackgroundWork = scheduleWork
        )
    }

    private fun serializeMemoryJson(m: MemoryEntity): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("userId", m.userId)
        put("tier", m.tier)
        put("category", m.category)
        put("title", m.title)
        put("content", m.content)
        put("confidence", m.confidence.toDouble())
        put("sourceProvenance", m.sourceProvenance)
        put("isPinned", m.isPinned)
        put("approvedByUser", m.approvedByUser)
        put("createdAtMs", m.createdAtMs)
        put("updatedAtMs", m.updatedAtMs)
    }

    private fun buildFtsQuery(raw: String): String {
        val tokens = raw.split(Regex("\\s+"))
            .map { token -> token.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.isNotBlank() }
        return tokens.joinToString(" AND ") { "$it*" }
    }

    companion object {
        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    }
}
