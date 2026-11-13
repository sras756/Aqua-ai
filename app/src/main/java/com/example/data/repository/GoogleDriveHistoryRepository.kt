package com.example.data.repository

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class NeedsResolution(val pendingIntent: PendingIntent) : DriveAuthOutcome
    data class Error(val message: String) : DriveAuthOutcome
}

data class DriveBackupMetadata(
    val fileId: String,
    val name: String,
    val modifiedTime: String,
    val sizeBytes: Long,
    val space: String
)

data class DriveConversationSnapshot(
    val id: String,
    val title: String,
    val summary: String,
    val modelUsed: String,
    val messageCount: Long,
    val messages: List<DriveMessageSnapshot>
)

data class DriveMessageSnapshot(
    val id: String,
    val role: String,
    val content: String,
    val modelUsed: String,
    val attachmentName: String,
    val attachmentMime: String,
    val toolActionsJson: String,
    val groundingSourcesJson: String
)

data class DriveMemorySnapshot(
    val id: String,
    val category: String,
    val title: String,
    val content: String
)

data class DriveHistorySnapshot(
    val exportedAtIso: String,
    val userId: String,
    val conversations: List<DriveConversationSnapshot>,
    val memories: List<DriveMemorySnapshot>
)

/**
 * Real Google Drive API v3 history backup & restore repository.
 * Uses Android Google Identity Services AuthorizationClient (Credential Manager companion)
 * with least-privilege Drive scopes:
 * - https://www.googleapis.com/auth/drive.appdata (private AppData sync folder)
 * - https://www.googleapis.com/auth/drive.file (user-visible exported JSON files created by Aqua)
 */
class GoogleDriveHistoryRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private val driveScopes = listOf(
        Scope(SCOPE_DRIVE_APPDATA),
        Scope(SCOPE_DRIVE_FILE)
    )

    suspend fun authorizeDriveAccess(activity: Activity?): DriveAuthOutcome {
        return try {
            val targetContext: Context = activity ?: context
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(driveScopes)
                .build()
            val result = Identity.getAuthorizationClient(targetContext)
                .authorize(request)
                .await()

            val token = result.accessToken
            val pending = result.pendingIntent
            when {
                result.hasResolution() && pending != null -> DriveAuthOutcome.NeedsResolution(pending)
                !token.isNullOrBlank() -> DriveAuthOutcome.Authorized(token)
                else -> DriveAuthOutcome.Error("Google Drive authorization returned no access token.")
            }
        } catch (e: Exception) {
            DriveAuthOutcome.Error(e.localizedMessage ?: "Failed to authorize Google Drive access.")
        }
    }

    /**
     * Silent token check (succeeds only if user has already consented to Drive scopes).
     */
    suspend fun tryGetSilentAccessToken(): String? {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(driveScopes)
                .build()
            val result = Identity.getAuthorizationClient(context)
                .authorize(request)
                .await()
            if (!result.hasResolution()) result.accessToken else null
        } catch (_: Exception) {
            null
        }
    }

    fun extractTokenFromIntent(intent: Intent?): Result<String> = runCatching {
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(intent)
        result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google Drive authorization was not granted.")
    }

    suspend fun listBackups(accessToken: String): Result<List<DriveBackupMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appDataFiles = queryFiles(
                    accessToken = accessToken,
                    spaces = "appDataFolder",
                    query = "name = '$APPDATA_BACKUP_FILENAME' and trashed = false"
                )
                val visibleFiles = queryFiles(
                    accessToken = accessToken,
                    spaces = "drive",
                    query = "name contains 'Aqua_AI_History_' and mimeType = 'application/json' and trashed = false"
                )
                (appDataFiles + visibleFiles).distinctBy { it.fileId }
            }
        }

    suspend fun backupHistoryToAppData(
        accessToken: String,
        snapshot: DriveHistorySnapshot
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val payloadJson = serializeSnapshot(snapshot)
            val existing = queryFiles(
                accessToken = accessToken,
                spaces = "appDataFolder",
                query = "name = '$APPDATA_BACKUP_FILENAME' and trashed = false"
            ).firstOrNull()

            if (existing != null) {
                patchMultipartFile(
                    accessToken = accessToken,
                    fileId = existing.fileId,
                    fileName = APPDATA_BACKUP_FILENAME,
                    contentJson = payloadJson,
                    spaceLabel = "appDataFolder"
                )
            } else {
                createMultipartFile(
                    accessToken = accessToken,
                    fileName = APPDATA_BACKUP_FILENAME,
                    parentFolder = "appDataFolder",
                    contentJson = payloadJson,
                    spaceLabel = "appDataFolder"
                )
            }
        }
    }

    suspend fun exportVisibleHistoryFile(
        accessToken: String,
        snapshot: DriveHistorySnapshot
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "Aqua_AI_History_$stamp.json"
            val payloadJson = serializeSnapshot(snapshot)
            createMultipartFile(
                accessToken = accessToken,
                fileName = fileName,
                parentFolder = null,
                contentJson = payloadJson,
                spaceLabel = "My Drive"
            )
        }
    }

    suspend fun downloadAndParseSnapshot(
        accessToken: String,
        fileId: String
    ): Result<DriveHistorySnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$DRIVE_API_BASE/files/${URLEncoder.encode(fileId, "UTF-8")}?alt=media"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val bodyStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Google Drive download failed (HTTP ${resp.code}).")
                }
                resp.body?.string() ?: throw IllegalStateException("Empty Google Drive backup file.")
            }
            deserializeSnapshot(bodyStr)
        }
    }

    private fun queryFiles(
        accessToken: String,
        spaces: String,
        query: String
    ): List<DriveBackupMetadata> {
        val qEnc = URLEncoder.encode(query, "UTF-8")
        val fieldsEnc = URLEncoder.encode("files(id,name,modifiedTime,size)", "UTF-8")
        val url = "$DRIVE_API_BASE/files?spaces=$spaces&q=$qEnc&fields=$fieldsEnc&orderBy=modifiedTime%20desc&pageSize=20"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Google Drive list failed (HTTP ${resp.code}): ${resp.body?.string().orEmpty()}")
            }
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("files") ?: JSONArray()
            return (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                DriveBackupMetadata(
                    fileId = obj.getString("id"),
                    name = obj.optString("name", "backup.json"),
                    modifiedTime = obj.optString("modifiedTime", ""),
                    sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L,
                    space = if (spaces == "appDataFolder") "appDataFolder" else "My Drive"
                )
            }
        }
    }

    private fun createMultipartFile(
        accessToken: String,
        fileName: String,
        parentFolder: String?,
        contentJson: String,
        spaceLabel: String
    ): DriveBackupMetadata {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "application/json")
            if (!parentFolder.isNullOrBlank()) {
                put("parents", JSONArray().put(parentFolder))
            }
        }.toString()

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(contentJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,modifiedTime,size")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        return executeUploadRequest(req, fileName, contentJson.length.toLong(), spaceLabel)
    }

    private fun patchMultipartFile(
        accessToken: String,
        fileId: String,
        fileName: String,
        contentJson: String,
        spaceLabel: String
    ): DriveBackupMetadata {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "application/json")
        }.toString()

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(contentJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("$DRIVE_UPLOAD_BASE/files/${URLEncoder.encode(fileId, "UTF-8")}?uploadType=multipart&fields=id,name,modifiedTime,size")
            .header("Authorization", "Bearer $accessToken")
            .patch(body)
            .build()

        return executeUploadRequest(req, fileName, contentJson.length.toLong(), spaceLabel)
    }

    private fun executeUploadRequest(
        request: Request,
        fallbackName: String,
        fallbackSize: Long,
        spaceLabel: String
    ): DriveBackupMetadata {
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Google Drive upload failed (HTTP ${resp.code}): $raw")
            }
            val obj = JSONObject(raw)
            return DriveBackupMetadata(
                fileId = obj.getString("id"),
                name = obj.optString("name", fallbackName),
                modifiedTime = obj.optString("modifiedTime", ""),
                sizeBytes = obj.optString("size", fallbackSize.toString()).toLongOrNull() ?: fallbackSize,
                space = spaceLabel
            )
        }
    }

    fun serializeSnapshot(snapshot: DriveHistorySnapshot): String {
        val convArray = JSONArray()
        snapshot.conversations.forEach { conv ->
            val msgArray = JSONArray()
            conv.messages.forEach { msg ->
                msgArray.put(JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("modelUsed", msg.modelUsed)
                    put("attachmentName", msg.attachmentName)
                    put("attachmentMime", msg.attachmentMime)
                    put("toolActionsJson", msg.toolActionsJson)
                    put("groundingSourcesJson", msg.groundingSourcesJson)
                })
            }
            convArray.put(JSONObject().apply {
                put("id", conv.id)
                put("title", conv.title)
                put("summary", conv.summary)
                put("modelUsed", conv.modelUsed)
                put("messageCount", conv.messageCount)
                put("messages", msgArray)
            })
        }

        val memArray = JSONArray()
        snapshot.memories.forEach { mem ->
            memArray.put(JSONObject().apply {
                put("id", mem.id)
                put("category", mem.category)
                put("title", mem.title)
                put("content", mem.content)
            })
        }

        return JSONObject().apply {
            put("schemaVersion", 1)
            put("app", "Aqua AI")
            put("exportedAtIso", snapshot.exportedAtIso)
            put("userId", snapshot.userId)
            put("conversations", convArray)
            put("memories", memArray)
        }.toString(2)
    }

    fun deserializeSnapshot(rawJson: String): DriveHistorySnapshot {
        val root = JSONObject(rawJson)
        val convs = mutableListOf<DriveConversationSnapshot>()
        val convArray = root.optJSONArray("conversations") ?: JSONArray()
        for (i in 0 until convArray.length()) {
            val c = convArray.getJSONObject(i)
            val msgs = mutableListOf<DriveMessageSnapshot>()
            val msgArray = c.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until msgArray.length()) {
                val m = msgArray.getJSONObject(j)
                msgs.add(
                    DriveMessageSnapshot(
                        id = m.optString("id", ""),
                        role = m.optString("role", "user"),
                        content = m.optString("content", ""),
                        modelUsed = m.optString("modelUsed", ""),
                        attachmentName = m.optString("attachmentName", ""),
                        attachmentMime = m.optString("attachmentMime", ""),
                        toolActionsJson = m.optString("toolActionsJson", ""),
                        groundingSourcesJson = m.optString("groundingSourcesJson", "")
                    )
                )
            }
            convs.add(
                DriveConversationSnapshot(
                    id = c.optString("id", ""),
                    title = c.optString("title", "Restored Conversation"),
                    summary = c.optString("summary", ""),
                    modelUsed = c.optString("modelUsed", "gemini-4-hydrogen"),
                    messageCount = c.optLong("messageCount", msgs.size.toLong()),
                    messages = msgs
                )
            )
        }

        val mems = mutableListOf<DriveMemorySnapshot>()
        val memArray = root.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until memArray.length()) {
            val m = memArray.getJSONObject(i)
            mems.add(
                DriveMemorySnapshot(
                    id = m.optString("id", ""),
                    category = m.optString("category", "instruction"),
                    title = m.optString("title", ""),
                    content = m.optString("content", "")
                )
            )
        }

        return DriveHistorySnapshot(
            exportedAtIso = root.optString("exportedAtIso", ""),
            userId = root.optString("userId", ""),
            conversations = convs,
            memories = mems
        )
    }

    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val APPDATA_BACKUP_FILENAME = "aqua_ai_history_backup.json"
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

        fun isoNow(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }

        fun buildSnapshot(
            userId: String,
            conversationsWithMessages: List<Pair<Conversation, List<ChatMessage>>>,
            memories: List<MemoryItem>
        ): DriveHistorySnapshot {
            return DriveHistorySnapshot(
                exportedAtIso = isoNow(),
                userId = userId,
                conversations = conversationsWithMessages.map { (conv, msgs) ->
                    DriveConversationSnapshot(
                        id = conv.id,
                        title = conv.title,
                        summary = conv.summary,
                        modelUsed = conv.modelUsed,
                        messageCount = conv.messageCount,
                        messages = msgs.map { m ->
                            DriveMessageSnapshot(
                                id = m.id,
                                role = m.role,
                                content = m.content,
                                modelUsed = m.modelUsed,
                                attachmentName = m.attachmentName,
                                attachmentMime = m.attachmentMime,
                                toolActionsJson = m.toolActionsJson,
                                groundingSourcesJson = m.groundingSourcesJson
                            )
                        }
                    )
                },
                memories = memories.map { mem ->
                    DriveMemorySnapshot(
                        id = mem.id,
                        category = mem.category,
                        title = mem.title,
                        content = mem.content
                    )
                }
            )
        }
    }
}
