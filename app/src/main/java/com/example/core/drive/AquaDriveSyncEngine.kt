package com.example.core.drive

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.core.common.CryptoHashing
import com.example.core.database.ChatMessageEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.DriveFileMetadataDao
import com.example.core.database.DriveFileMetadataEntity
import com.example.core.database.DriveSyncQueueDao
import com.example.core.database.DriveSyncQueueEntity
import com.example.core.database.MemoryEntity
import com.example.core.database.MemoryTier
import com.example.core.database.SyncStatus
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.core.security.SecretExclusionFilter
import com.example.data.local.AquaCacheDatabase
import com.example.data.repository.DriveHistorySnapshot
import com.example.data.repository.GoogleDriveHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object AquaDriveFolders {
    const val ROOT_FOLDER_NAME = "Aqua AI"

    const val ACCOUNT_DATA = "Account data"
    const val CONVERSATIONS = "Conversations"
    const val MEMORY = "Memory"
    const val AI_DOCUMENTS = "AI documents"
    const val RESEARCH = "Research"
    const val BROWSER_BOOKMARKS = "Browser bookmarks"
    const val TASK_HISTORY = "Task history"
    const val AUTOMATION_RECORDS = "Automation records"
    const val BACKUPS = "Backups"
    const val EXPORTS = "Exports"

    val ALL_SUBFOLDERS: List<String> = listOf(
        ACCOUNT_DATA,
        CONVERSATIONS,
        MEMORY,
        AI_DOCUMENTS,
        RESEARCH,
        BROWSER_BOOKMARKS,
        TASK_HISTORY,
        AUTOMATION_RECORDS,
        BACKUPS,
        EXPORTS
    )
}

enum class ConflictResolutionStrategy {
    KEEP_LOCAL,
    KEEP_CLOUD
}

data class DriveSyncBatchSummary(
    val syncedCount: Int = 0,
    val conflictCount: Int = 0,
    val errorCount: Int = 0,
    val foldersReady: Boolean = false,
    val message: String = ""
)

data class RemoteDriveFileInfo(
    val fileId: String,
    val name: String,
    val modifiedTime: String,
    val version: String,
    val md5Checksum: String,
    val sizeBytes: Long
)

class AquaDriveSyncCoordinator(
    private val context: Context,
    private val syncQueueDao: DriveSyncQueueDao,
    private val metadataDao: DriveFileMetadataDao,
    private val database: AquaCacheDatabase? = null,
    private val permissionPolicyStore: PermissionPolicyStore? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val driveApiBaseUrl: String = "https://www.googleapis.com/drive/v3",
    private val driveUploadBaseUrl: String = "https://www.googleapis.com/upload/drive/v3"
) {
    private val folderPrefs = context.applicationContext.getSharedPreferences(
        PREFS_FOLDERS,
        Context.MODE_PRIVATE
    )

    /**
     * Enqueues any local mutation cleanly into encrypted Room SSOT after running SecretExclusionFilter.
     * Never allows private keys, mnemonics, passwords, or auth secrets into the sync queue.
     */
    suspend fun enqueueMutation(
        userId: String,
        entityType: String,
        entityId: String,
        driveFolder: String,
        fileName: String,
        operation: String = "UPSERT",
        serializedPayloadJson: String,
        scheduleBackgroundWork: Boolean = true
    ): Result<DriveSyncQueueEntity> = withContext(Dispatchers.IO) {
        val inspection = SecretExclusionFilter.inspectForDriveUpload(
            fileName = "$driveFolder/$fileName",
            payloadContent = serializedPayloadJson
        )
        if (!inspection.allowed) {
            return@withContext Result.failure(
                SecurityException("Drive sync blocked: ${inspection.reason}")
            )
        }

        val normalizedFolder = if (driveFolder in AquaDriveFolders.ALL_SUBFOLDERS) {
            driveFolder
        } else {
            AquaDriveFolders.EXPORTS
        }
        val uid = userId.ifBlank { "default_user" }
        val sha256 = CryptoHashing.sha256Hex(serializedPayloadJson.toByteArray(Charsets.UTF_8))
        val mutation = DriveSyncQueueEntity(
            mutationId = "${entityType}_${entityId}",
            userId = uid,
            entityType = entityType,
            entityId = entityId,
            driveFolder = normalizedFolder,
            fileName = fileName,
            operation = operation,
            payloadSha256 = sha256,
            serializedPayloadJson = serializedPayloadJson,
            localUpdatedAtMs = System.currentTimeMillis(),
            status = SyncStatus.PENDING_OFFLINE,
            retryCount = 0,
            lastError = ""
        )
        syncQueueDao.enqueueMutation(mutation)

        if (scheduleBackgroundWork) {
            AquaDriveSyncWorker.schedule(context, uid)
        }
        Result.success(mutation)
    }

    /**
     * Ensures `Aqua AI/` root and all 10 required subfolders exist on the user's Google Drive.
     */
    suspend fun ensureTenFolderHierarchy(
        userId: String,
        accessToken: String,
        apiKey: String = ""
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = userId.ifBlank { "default_user" }
            val rootId = findOrCreateFolder(
                uid = uid,
                cacheKey = "root_${AquaDriveFolders.ROOT_FOLDER_NAME}",
                folderName = AquaDriveFolders.ROOT_FOLDER_NAME,
                parentId = null,
                accessToken = accessToken,
                apiKey = apiKey
            )

            val mapping = linkedMapOf<String, String>()
            AquaDriveFolders.ALL_SUBFOLDERS.forEach { subfolder ->
                val subId = findOrCreateFolder(
                    uid = uid,
                    cacheKey = "sub_$subfolder",
                    folderName = subfolder,
                    parentId = rootId,
                    accessToken = accessToken,
                    apiKey = apiKey
                )
                mapping[subfolder] = subId
            }
            mapping
        }
    }

    /**
     * Flushes pending offline mutations to the 10-folder Drive hierarchy with version/checksum conflict detection.
     */
    suspend fun flushQueue(
        userId: String,
        accessToken: String,
        apiKey: String = ""
    ): DriveSyncBatchSummary = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        if (permissionPolicyStore != null && !permissionPolicyStore.isAllowed(PermissionDomain.CLOUD_STORAGE)) {
            return@withContext DriveSyncBatchSummary(
                message = "Cloud Storage permission domain is disabled in Security Center."
            )
        }

        val hierarchy = ensureTenFolderHierarchy(uid, accessToken, apiKey).getOrElse { err ->
            return@withContext DriveSyncBatchSummary(
                errorCount = 1,
                foldersReady = false,
                message = "Failed to initialize Aqua AI Drive folders: ${err.localizedMessage}"
            )
        }

        val pending = syncQueueDao.getPendingMutations(uid, limit = 50)
        if (pending.isEmpty()) {
            return@withContext DriveSyncBatchSummary(
                foldersReady = true,
                message = "All 10 Aqua AI folders synchronized; queue empty."
            )
        }

        var synced = 0
        var conflicts = 0
        var errors = 0

        for (item in pending) {
            val folderId = hierarchy[item.driveFolder] ?: continue
            syncQueueDao.enqueueMutation(item.copy(status = SyncStatus.SYNCING))
            try {
                val remoteMatch = findRemoteFileInFolder(
                    folderId = folderId,
                    fileName = item.fileName,
                    accessToken = accessToken,
                    apiKey = apiKey
                )

                if (item.operation == "DELETE") {
                    if (remoteMatch != null) {
                        deleteRemoteFile(remoteMatch.fileId, accessToken, apiKey)
                        metadataDao.deleteMetadata(remoteMatch.fileId)
                    }
                    syncQueueDao.removeMutation(item.mutationId)
                    synced++
                    continue
                }

                // Re-check secret exclusion prior to wire transmission
                val guard = SecretExclusionFilter.inspectForDriveUpload(
                    fileName = "${item.driveFolder}/${item.fileName}",
                    payloadContent = item.serializedPayloadJson
                )
                if (!guard.allowed) {
                    syncQueueDao.removeMutation(item.mutationId)
                    continue
                }

                if (remoteMatch != null) {
                    val knownLocal = metadataDao.getById(remoteMatch.fileId)
                    val remoteChangedSinceLastSync = knownLocal != null &&
                        knownLocal.versionTag.isNotBlank() &&
                        remoteMatch.version.isNotBlank() &&
                        knownLocal.versionTag != remoteMatch.version &&
                        knownLocal.localSha256 != item.payloadSha256

                    if (remoteChangedSinceLastSync) {
                        val conflictName = buildConflictFileName(item.fileName)
                        val conflictUploaded = createFileInFolder(
                            folderId = folderId,
                            fileName = conflictName,
                            payloadJson = item.serializedPayloadJson,
                            accessToken = accessToken,
                            apiKey = apiKey
                        )
                        syncQueueDao.enqueueMutation(
                            item.copy(
                                status = SyncStatus.CONFLICT,
                                conflictCopyFileId = conflictUploaded.fileId,
                                lastError = "Version mismatch (local base v${knownLocal?.versionTag} vs remote v${remoteMatch.version}). Preserved conflict copy '$conflictName'."
                            )
                        )
                        conflicts++
                        continue
                    }

                    val updated = patchFileContent(
                        fileId = remoteMatch.fileId,
                        fileName = item.fileName,
                        payloadJson = item.serializedPayloadJson,
                        accessToken = accessToken,
                        apiKey = apiKey
                    )
                    metadataDao.upsertMetadata(
                        DriveFileMetadataEntity(
                            fileId = updated.fileId,
                            userId = uid,
                            driveFolder = item.driveFolder,
                            name = updated.name,
                            mimeType = "application/json",
                            modifiedTimeIso = updated.modifiedTime,
                            versionTag = updated.version,
                            localSha256 = item.payloadSha256,
                            sizeBytes = updated.sizeBytes
                        )
                    )
                } else {
                    val created = createFileInFolder(
                        folderId = folderId,
                        fileName = item.fileName,
                        payloadJson = item.serializedPayloadJson,
                        accessToken = accessToken,
                        apiKey = apiKey
                    )
                    metadataDao.upsertMetadata(
                        DriveFileMetadataEntity(
                            fileId = created.fileId,
                            userId = uid,
                            driveFolder = item.driveFolder,
                            name = created.name,
                            mimeType = "application/json",
                            modifiedTimeIso = created.modifiedTime,
                            versionTag = created.version,
                            localSha256 = item.payloadSha256,
                            sizeBytes = created.sizeBytes
                        )
                    )
                }
                syncQueueDao.removeMutation(item.mutationId)
                synced++
            } catch (e: Exception) {
                Log.w(TAG, "Drive sync failed for ${item.fileName}", e)
                syncQueueDao.enqueueMutation(
                    item.copy(
                        status = SyncStatus.ERROR,
                        retryCount = item.retryCount + 1,
                        lastError = e.localizedMessage ?: "Drive sync failed"
                    )
                )
                errors++
            }
        }

        DriveSyncBatchSummary(
            syncedCount = synced,
            conflictCount = conflicts,
            errorCount = errors,
            foldersReady = true,
            message = "Synced $synced file(s), $conflicts conflict(s), $errors error(s)."
        )
    }

    suspend fun resolveConflict(
        userId: String,
        mutationId: String,
        strategy: ConflictResolutionStrategy,
        accessToken: String,
        apiKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = userId.ifBlank { "default_user" }
            val current = syncQueueDao.getPendingMutations(uid, 100).firstOrNull { it.mutationId == mutationId }
            if (strategy == ConflictResolutionStrategy.KEEP_CLOUD) {
                syncQueueDao.removeMutation(mutationId)
                return@runCatching "Kept cloud copy and cleared local conflict state."
            }

            if (current != null) {
                val hierarchy = ensureTenFolderHierarchy(uid, accessToken, apiKey).getOrThrow()
                val folderId = hierarchy[current.driveFolder]
                    ?: throw IllegalStateException("Missing folder ${current.driveFolder}")
                val remote = findRemoteFileInFolder(folderId, current.fileName, accessToken, apiKey)
                val uploaded = if (remote != null) {
                    patchFileContent(remote.fileId, current.fileName, current.serializedPayloadJson, accessToken, apiKey)
                } else {
                    createFileInFolder(folderId, current.fileName, current.serializedPayloadJson, accessToken, apiKey)
                }
                metadataDao.upsertMetadata(
                    DriveFileMetadataEntity(
                        fileId = uploaded.fileId,
                        userId = uid,
                        driveFolder = current.driveFolder,
                        name = uploaded.name,
                        modifiedTimeIso = uploaded.modifiedTime,
                        versionTag = uploaded.version,
                        localSha256 = current.payloadSha256,
                        sizeBytes = uploaded.sizeBytes
                    )
                )
                syncQueueDao.removeMutation(mutationId)
                "Overwrote cloud version cleanly with local version (${uploaded.name})."
            } else {
                syncQueueDao.removeMutation(mutationId)
                "Conflict entry resolved."
            }
        }
    }

    /**
     * Restores a downloaded DriveHistorySnapshot directly into encrypted Room SSOT tables.
     */
    suspend fun restoreSnapshotIntoRoom(
        userId: String,
        snapshot: DriveHistorySnapshot
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext (0 to 0)
        val uid = userId.ifBlank { snapshot.userId.ifBlank { "default_user" } }
        var restoredConvs = 0
        var restoredMems = 0

        for (conv in snapshot.conversations) {
            val cid = conv.id.ifBlank { UUID.randomUUID().toString() }
            db.conversationDao().upsertConversation(
                ConversationEntity(
                    id = cid,
                    userId = uid,
                    title = conv.title,
                    summary = conv.summary,
                    modelUsed = conv.modelUsed,
                    messageCount = conv.messageCount,
                    syncStatus = SyncStatus.SYNCED
                )
            )
            restoredConvs++
            for (msg in conv.messages) {
                val mid = msg.id.ifBlank { UUID.randomUUID().toString() }
                db.conversationDao().upsertMessage(
                    ChatMessageEntity(
                        id = mid,
                        conversationId = cid,
                        userId = uid,
                        role = msg.role,
                        content = msg.content,
                        modelUsed = msg.modelUsed,
                        attachmentName = msg.attachmentName,
                        attachmentMime = msg.attachmentMime,
                        toolActionsJson = msg.toolActionsJson,
                        groundingSourcesJson = msg.groundingSourcesJson
                    )
                )
            }
        }

        for (mem in snapshot.memories) {
            val mid = mem.id.ifBlank { UUID.randomUUID().toString() }
            db.memoryDao().upsertMemory(
                MemoryEntity(
                    id = mid,
                    userId = uid,
                    tier = MemoryTier.LONG_TERM,
                    category = mem.category,
                    title = mem.title,
                    content = mem.content,
                    sourceProvenance = "drive_restore",
                    approvedByUser = true,
                    syncStatus = SyncStatus.SYNCED
                )
            )
            restoredMems++
        }

        restoredConvs to restoredMems
    }

    suspend fun searchDriveMetadataAndContent(
        userId: String,
        query: String,
        accessToken: String? = null,
        apiKey: String = ""
    ): List<DriveFileMetadataEntity> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val localAll = AquaDriveFolders.ALL_SUBFOLDERS.flatMap { folder ->
            metadataDao.getFilesInFolder(uid, folder)
        }
        val cleanQ = query.trim().lowercase()
        val localMatches = if (cleanQ.isBlank()) {
            localAll
        } else {
            localAll.filter {
                it.name.lowercase().contains(cleanQ) || it.driveFolder.lowercase().contains(cleanQ)
            }
        }
        if (accessToken.isNullOrBlank() || cleanQ.isBlank()) {
            return@withContext localMatches
        }

        val remoteHits = runCatching {
            val safeQ = cleanQ.replace("'", "\\'")
            val driveQuery = "fullText contains '$safeQ' and trashed = false"
            val qEnc = URLEncoder.encode(driveQuery, "UTF-8")
            val fieldsEnc = URLEncoder.encode("files(id,name,modifiedTime,version,size)", "UTF-8")
            val url = GoogleDriveHistoryRepository.appendApiKey(
                "$driveApiBaseUrl/files?spaces=drive&q=$qEnc&fields=$fieldsEnc&pageSize=25",
                apiKey
            )
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DriveFileMetadataEntity(
                        fileId = o.getString("id"),
                        userId = uid,
                        driveFolder = AquaDriveFolders.ROOT_FOLDER_NAME,
                        name = o.optString("name", ""),
                        modifiedTimeIso = o.optString("modifiedTime", ""),
                        versionTag = o.optString("version", ""),
                        sizeBytes = o.optString("size", "0").toLongOrNull() ?: 0L
                    )
                }
            }
        }.getOrDefault(emptyList())

        (localMatches + remoteHits).distinctBy { it.fileId }
    }

    private fun findOrCreateFolder(
        uid: String,
        cacheKey: String,
        folderName: String,
        parentId: String?,
        accessToken: String,
        apiKey: String
    ): String {
        val prefKey = "${uid}_$cacheKey"
        folderPrefs.getString(prefKey, null)?.takeIf { it.isNotBlank() }?.let { cached ->
            return cached
        }

        val parentClause = if (parentId.isNullOrBlank()) {
            "'root' in parents"
        } else {
            "'$parentId' in parents"
        }
        val query = "name = '${folderName.replace("'", "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and $parentClause and trashed = false"
        val qEnc = URLEncoder.encode(query, "UTF-8")
        val listUrl = GoogleDriveHistoryRepository.appendApiKey(
            "$driveApiBaseUrl/files?spaces=drive&q=$qEnc&fields=files(id,name)&pageSize=5",
            apiKey
        )
        val listReq = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(listReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Drive folder lookup failed (HTTP ${resp.code}): ${resp.body?.string().orEmpty()}")
            }
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
            if (arr != null && arr.length() > 0) {
                val existingId = arr.getJSONObject(0).getString("id")
                folderPrefs.edit().putString(prefKey, existingId).apply()
                return existingId
            }
        }

        val metaJson = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
            if (!parentId.isNullOrBlank()) {
                put("parents", JSONArray().put(parentId))
            }
        }.toString()

        val createUrl = GoogleDriveHistoryRepository.appendApiKey(
            "$driveApiBaseUrl/files?fields=id",
            apiKey
        )
        val createReq = Request.Builder()
            .url(createUrl)
            .header("Authorization", "Bearer $accessToken")
            .post(metaJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(createReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Drive folder create failed (HTTP ${resp.code}): ${resp.body?.string().orEmpty()}")
            }
            val createdId = JSONObject(resp.body?.string().orEmpty()).getString("id")
            folderPrefs.edit().putString(prefKey, createdId).apply()
            return createdId
        }
    }

    private fun findRemoteFileInFolder(
        folderId: String,
        fileName: String,
        accessToken: String,
        apiKey: String
    ): RemoteDriveFileInfo? {
        val q = "name = '${fileName.replace("'", "\\'")}' and '$folderId' in parents and trashed = false"
        val qEnc = URLEncoder.encode(q, "UTF-8")
        val fieldsEnc = URLEncoder.encode("files(id,name,modifiedTime,version,md5Checksum,size)", "UTF-8")
        val url = GoogleDriveHistoryRepository.appendApiKey(
            "$driveApiBaseUrl/files?spaces=drive&q=$qEnc&fields=$fieldsEnc&pageSize=1",
            apiKey
        )
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files") ?: return null
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            return RemoteDriveFileInfo(
                fileId = o.getString("id"),
                name = o.optString("name", fileName),
                modifiedTime = o.optString("modifiedTime", ""),
                version = o.optString("version", ""),
                md5Checksum = o.optString("md5Checksum", ""),
                sizeBytes = o.optString("size", "0").toLongOrNull() ?: 0L
            )
        }
    }

    private fun createFileInFolder(
        folderId: String,
        fileName: String,
        payloadJson: String,
        accessToken: String,
        apiKey: String
    ): RemoteDriveFileInfo {
        val meta = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "application/json")
            put("parents", JSONArray().put(folderId))
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val url = GoogleDriveHistoryRepository.appendApiKey(
            "$driveUploadBaseUrl/files?uploadType=multipart&fields=id,name,modifiedTime,version,md5Checksum,size",
            apiKey
        )
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").post(body).build()
        return executeFileResponse(req, fileName, payloadJson.length.toLong())
    }

    private fun patchFileContent(
        fileId: String,
        fileName: String,
        payloadJson: String,
        accessToken: String,
        apiKey: String
    ): RemoteDriveFileInfo {
        val meta = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "application/json")
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val url = GoogleDriveHistoryRepository.appendApiKey(
            "$driveUploadBaseUrl/files/${URLEncoder.encode(fileId, "UTF-8")}?uploadType=multipart&fields=id,name,modifiedTime,version,md5Checksum,size",
            apiKey
        )
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").patch(body).build()
        return executeFileResponse(req, fileName, payloadJson.length.toLong())
    }

    private fun deleteRemoteFile(fileId: String, accessToken: String, apiKey: String) {
        val url = GoogleDriveHistoryRepository.appendApiKey(
            "$driveApiBaseUrl/files/${URLEncoder.encode(fileId, "UTF-8")}",
            apiKey
        )
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").delete().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204 && resp.code != 404) {
                throw IllegalStateException("Drive delete failed HTTP ${resp.code}")
            }
        }
    }

    private fun executeFileResponse(
        req: Request,
        fallbackName: String,
        fallbackSize: Long
    ): RemoteDriveFileInfo {
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Drive file write failed (HTTP ${resp.code}): $raw")
            }
            val o = JSONObject(raw)
            return RemoteDriveFileInfo(
                fileId = o.getString("id"),
                name = o.optString("name", fallbackName),
                modifiedTime = o.optString("modifiedTime", ""),
                version = o.optString("version", "1"),
                md5Checksum = o.optString("md5Checksum", ""),
                sizeBytes = o.optString("size", fallbackSize.toString()).toLongOrNull() ?: fallbackSize
            )
        }
    }

    private fun buildConflictFileName(originalName: String): String {
        val dot = originalName.lastIndexOf('.')
        val stamp = System.currentTimeMillis()
        return if (dot > 0) {
            "${originalName.substring(0, dot)}_conflict_$stamp${originalName.substring(dot)}"
        } else {
            "${originalName}_conflict_$stamp"
        }
    }

    companion object {
        private const val TAG = "AquaDriveSync"
        private const val PREFS_FOLDERS = "aqua_drive_folder_ids"
    }
}

class AquaDriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID).orEmpty().ifBlank { "default_user" }
        val policyStore = PermissionPolicyStore(applicationContext, userId)
        if (!policyStore.isAllowed(PermissionDomain.CLOUD_STORAGE)) {
            return Result.success(workDataOf(KEY_STATUS to "skipped_cloud_policy_disabled"))
        }

        val driveRepo = GoogleDriveHistoryRepository(applicationContext)
        val accessToken = driveRepo.tryGetSilentAccessToken()
            ?: return Result.retry()

        val db = AquaCacheDatabase.getInstance(applicationContext)
        val coordinator = AquaDriveSyncCoordinator(
            context = applicationContext,
            syncQueueDao = db.driveSyncQueueDao(),
            metadataDao = db.driveFileMetadataDao(),
            database = db,
            permissionPolicyStore = policyStore
        )

        val summary = coordinator.flushQueue(
            userId = userId,
            accessToken = accessToken,
            apiKey = driveRepo.resolveEffectiveApiKey()
        )
        return if (summary.errorCount > 0 && summary.syncedCount == 0) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    KEY_STATUS to summary.message,
                    KEY_SYNCED to summary.syncedCount,
                    KEY_CONFLICTS to summary.conflictCount
                )
            )
        }
    }

    companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_STATUS = "sync_status"
        const val KEY_SYNCED = "synced_count"
        const val KEY_CONFLICTS = "conflict_count"
        private const val WORK_NAME_PREFIX = "aqua_drive_sync_"

        fun schedule(context: Context, userId: String) {
            val uid = userId.ifBlank { "default_user" }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<AquaDriveSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_USER_ID to uid))
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$uid",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
            }
        }
    }
}
