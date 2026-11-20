package com.example.data.repository

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.BuildConfig
import com.example.data.local.SecureConfigStore
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
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

data class DriveAboutInfo(
    val displayName: String,
    val emailAddress: String,
    val storageLimitBytes: Long,
    val storageUsageBytes: Long,
    val storageUsageInDriveBytes: Long
) {
    val usageFraction: Float
        get() = if (storageLimitBytes <= 0L) 0f else (storageUsageBytes.toFloat() / storageLimitBytes.toFloat()).coerceIn(0f, 1f)
}

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
 * Real Google Drive API v3 Vault repository.
 * Uses Android Google Identity Services AuthorizationClient (Credential Manager companion)
 * with least-privilege Drive scopes:
 * - https://www.googleapis.com/auth/drive.appdata (private AppData sync folder)
 * - https://www.googleapis.com/auth/drive.file (user-visible exported JSON files created by Aqua)
 * Plus optional approved Google Drive API key attribution (`GOOGLE_DRIVE_API_KEY` via BuildConfig
 * or hardware-encrypted Keystore override in `SecureConfigStore`).
 */
class GoogleDriveHistoryRepository(
    private val context: Context,
    private val secureConfigStore: SecureConfigStore? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private val driveScopes = listOf(
        Scope(SCOPE_DRIVE_APPDATA),
        Scope(SCOPE_DRIVE_FILE)
    )

    fun resolveEffectiveApiKey(overrideKey: String? = null): String {
        val explicit = overrideKey?.trim().orEmpty()
        if (isConfiguredKey(explicit)) return explicit
        val stored = runCatching { secureConfigStore?.getGoogleDriveApiKey().orEmpty().trim() }.getOrDefault("")
        if (isConfiguredKey(stored)) return stored
        val buildKey = runCatching { BuildConfig.GOOGLE_DRIVE_API_KEY.trim() }.getOrDefault("")
        return if (isConfiguredKey(buildKey)) buildKey else ""
    }

    fun getApiKeyStatusSummary(): String {
        val stored = runCatching { secureConfigStore?.getGoogleDriveApiKey().orEmpty().trim() }.getOrDefault("")
        if (isConfiguredKey(stored)) {
            return "Approved Drive API Key active (Keystore ${maskKey(stored)})"
        }
        val buildKey = runCatching { BuildConfig.GOOGLE_DRIVE_API_KEY.trim() }.getOrDefault("")
        if (isConfiguredKey(buildKey)) {
            return "Approved Drive API Key active (BuildConfig ${maskKey(buildKey)})"
        }
        return "OAuth 2.0 Bearer Active • Optional GOOGLE_DRIVE_API_KEY unset"
    }

    suspend fun verifyDriveApiKeyStatus(
        accessToken: String? = null,
        apiKeyOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = resolveEffectiveApiKey(apiKeyOverride)
            if (key.isBlank()) {
                throw IllegalStateException("No Google Drive API key provided in Keystore or Secrets.")
            }
            if (!key.startsWith("AIza") || key.length < 24) {
                throw IllegalStateException("Drive API key format looks invalid (expected Google Cloud AIza... key).")
            }
            if (!accessToken.isNullOrBlank()) {
                val info = fetchDriveAboutInfo(accessToken, key).getOrThrow()
                "Drive API key verified against ${info.emailAddress.ifBlank { info.displayName }}."
            } else {
                "Drive API key format validated (${maskKey(key)}). Connect Drive OAuth to probe live quota."
            }
        }
    }

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedTokenTimestampMs: Long = 0L

    fun getCurrentSignedInGoogleEmail(): String {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching ""
            val hasGoogleProvider = user.providerData.any { it.providerId == "google.com" }
            if (hasGoogleProvider) user.email.orEmpty().trim() else user.email.orEmpty().trim()
        }.getOrDefault("")
    }

    fun buildAuthorizationRequest(preferredEmail: String? = null): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(driveScopes)
        val cleanEmail = preferredEmail?.trim().orEmpty()
        if (cleanEmail.contains("@")) {
            builder.setAccount(Account(cleanEmail, "com.google"))
        }
        return builder.build()
    }

    fun cacheAccessToken(token: String) {
        if (token.isNotBlank()) {
            cachedAccessToken = token
            cachedTokenTimestampMs = SystemClock.elapsedRealtime()
        }
    }

    fun getValidCachedToken(): String? {
        val token = cachedAccessToken ?: return null
        val ageMs = SystemClock.elapsedRealtime() - cachedTokenTimestampMs
        return if (ageMs in 0..TOKEN_CACHE_TTL_MS) token else null
    }

    fun disconnectDrive() {
        cachedAccessToken = null
        cachedTokenTimestampMs = 0L
    }

    suspend fun authorizeDriveAccess(
        activity: Activity?,
        forceAccountPicker: Boolean = false
    ): DriveAuthOutcome {
        if (!forceAccountPicker) {
            getValidCachedToken()?.let { cached ->
                return DriveAuthOutcome.Authorized(cached)
            }
        } else {
            disconnectDrive()
        }

        val playCheck = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SUCCESS)
        if (playCheck != ConnectionResult.SUCCESS && playCheck != ConnectionResult.SERVICE_UPDATING) {
            return DriveAuthOutcome.Error(
                "Google Play Services is required for Drive sign-in (status $playCheck)."
            )
        }

        val targetContext: Context = activity ?: context
        val preferredEmail = if (forceAccountPicker) "" else getCurrentSignedInGoogleEmail()

        suspend fun requestAuth(emailHint: String): DriveAuthOutcome {
            val request = buildAuthorizationRequest(emailHint)
            val result = Identity.getAuthorizationClient(targetContext)
                .authorize(request)
                .await()
            val token = result.accessToken
            val pending = result.pendingIntent
            return when {
                result.hasResolution() && pending != null -> DriveAuthOutcome.NeedsResolution(pending)
                !token.isNullOrBlank() -> {
                    cacheAccessToken(token)
                    DriveAuthOutcome.Authorized(token)
                }
                else -> DriveAuthOutcome.Error("Google Drive authorization returned no access token.")
            }
        }

        return try {
            requestAuth(preferredEmail)
        } catch (e: Exception) {
            // If account-hinted authorization fails (e.g. account not synced in Play Services), retry cleanly without hint.
            if (preferredEmail.isNotBlank()) {
                try {
                    return requestAuth("")
                } catch (retryError: Exception) {
                    return DriveAuthOutcome.Error(formatReadableAuthError(retryError))
                }
            }
            DriveAuthOutcome.Error(formatReadableAuthError(e))
        }
    }

    /**
     * Silent token check (succeeds only if user has already consented to Drive scopes).
     */
    suspend fun tryGetSilentAccessToken(): String? {
        getValidCachedToken()?.let { return it }
        return try {
            val preferredEmail = getCurrentSignedInGoogleEmail()
            val result = Identity.getAuthorizationClient(context)
                .authorize(buildAuthorizationRequest(preferredEmail))
                .await()
            if (!result.hasResolution()) {
                result.accessToken?.takeIf { it.isNotBlank() }?.also { cacheAccessToken(it) }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun extractTokenFromIntent(intent: Intent?): Result<String> = runCatching {
        if (intent == null) {
            throw IllegalStateException("Google Drive sign-in was cancelled.")
        }
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(intent)
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google Drive authorization was not granted.")
        cacheAccessToken(token)
        token
    }

    suspend fun fetchDriveAboutInfo(
        accessToken: String,
        apiKeyOverride: String? = null
    ): Result<DriveAboutInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fields = URLEncoder.encode("user(displayName,emailAddress),storageQuota(limit,usage,usageInDrive)", "UTF-8")
            val apiKey = resolveEffectiveApiKey(apiKeyOverride)
            val url = appendApiKey("$DRIVE_API_BASE/about?fields=$fields", apiKey)
            val req = newAuthorizedRequestBuilder(url, accessToken, apiKey).build()
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Google Drive about check failed (HTTP ${resp.code}): $bodyStr")
                }
                parseDriveAboutInfo(bodyStr)
            }
        }
    }

    suspend fun listBackups(
        accessToken: String,
        apiKeyOverride: String? = null
    ): Result<List<DriveBackupMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = resolveEffectiveApiKey(apiKeyOverride)
                val appDataFiles = queryFiles(
                    accessToken = accessToken,
                    spaces = "appDataFolder",
                    query = "name = '$APPDATA_BACKUP_FILENAME' and trashed = false",
                    apiKey = apiKey
                )
                val visibleFiles = queryFiles(
                    accessToken = accessToken,
                    spaces = "drive",
                    query = "name contains 'Aqua_AI_History_' and mimeType = 'application/json' and trashed = false",
                    apiKey = apiKey
                )
                (appDataFiles + visibleFiles).distinctBy { it.fileId }
            }
        }

    suspend fun backupHistoryToAppData(
        accessToken: String,
        snapshot: DriveHistorySnapshot,
        apiKeyOverride: String? = null
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = resolveEffectiveApiKey(apiKeyOverride)
            val payloadJson = serializeSnapshot(snapshot)
            val existing = queryFiles(
                accessToken = accessToken,
                spaces = "appDataFolder",
                query = "name = '$APPDATA_BACKUP_FILENAME' and trashed = false",
                apiKey = apiKey
            ).firstOrNull()

            if (existing != null) {
                patchMultipartFile(
                    accessToken = accessToken,
                    fileId = existing.fileId,
                    fileName = APPDATA_BACKUP_FILENAME,
                    contentJson = payloadJson,
                    spaceLabel = "appDataFolder",
                    apiKey = apiKey
                )
            } else {
                createMultipartFile(
                    accessToken = accessToken,
                    fileName = APPDATA_BACKUP_FILENAME,
                    parentFolder = "appDataFolder",
                    contentJson = payloadJson,
                    spaceLabel = "appDataFolder",
                    apiKey = apiKey
                )
            }
        }
    }

    suspend fun exportVisibleHistoryFile(
        accessToken: String,
        snapshot: DriveHistorySnapshot,
        apiKeyOverride: String? = null
    ): Result<DriveBackupMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = resolveEffectiveApiKey(apiKeyOverride)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "Aqua_AI_History_$stamp.json"
            val payloadJson = serializeSnapshot(snapshot)
            createMultipartFile(
                accessToken = accessToken,
                fileName = fileName,
                parentFolder = null,
                contentJson = payloadJson,
                spaceLabel = "My Drive",
                apiKey = apiKey
            )
        }
    }

    suspend fun downloadAndParseSnapshot(
        accessToken: String,
        fileId: String,
        apiKeyOverride: String? = null
    ): Result<DriveHistorySnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = resolveEffectiveApiKey(apiKeyOverride)
            val url = appendApiKey("$DRIVE_API_BASE/files/${URLEncoder.encode(fileId, "UTF-8")}?alt=media", apiKey)
            val req = newAuthorizedRequestBuilder(url, accessToken, apiKey).build()

            val bodyStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Google Drive download failed (HTTP ${resp.code}).")
                }
                resp.body?.string() ?: throw IllegalStateException("Empty Google Drive backup file.")
            }
            deserializeSnapshot(bodyStr)
        }
    }

    suspend fun deleteBackupFile(
        accessToken: String,
        fileId: String,
        apiKeyOverride: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = resolveEffectiveApiKey(apiKeyOverride)
            val url = appendApiKey("$DRIVE_API_BASE/files/${URLEncoder.encode(fileId, "UTF-8")}", apiKey)
            val req = newAuthorizedRequestBuilder(url, accessToken, apiKey)
                .delete()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 204) {
                    throw IllegalStateException("Google Drive delete failed (HTTP ${resp.code}): ${resp.body?.string().orEmpty()}")
                }
            }
        }
    }

    private fun queryFiles(
        accessToken: String,
        spaces: String,
        query: String,
        apiKey: String
    ): List<DriveBackupMetadata> {
        val qEnc = URLEncoder.encode(query, "UTF-8")
        val fieldsEnc = URLEncoder.encode("files(id,name,modifiedTime,size)", "UTF-8")
        val baseUrl = "$DRIVE_API_BASE/files?spaces=$spaces&q=$qEnc&fields=$fieldsEnc&orderBy=modifiedTime%20desc&pageSize=20"
        val url = appendApiKey(baseUrl, apiKey)
        val req = newAuthorizedRequestBuilder(url, accessToken, apiKey).build()

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
        spaceLabel: String,
        apiKey: String
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

        val url = appendApiKey("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,modifiedTime,size", apiKey)
        val req = newAuthorizedRequestBuilder(url, accessToken, apiKey)
            .post(body)
            .build()

        return executeUploadRequest(req, fileName, contentJson.length.toLong(), spaceLabel)
    }

    private fun patchMultipartFile(
        accessToken: String,
        fileId: String,
        fileName: String,
        contentJson: String,
        spaceLabel: String,
        apiKey: String
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

        val url = appendApiKey(
            "$DRIVE_UPLOAD_BASE/files/${URLEncoder.encode(fileId, "UTF-8")}?uploadType=multipart&fields=id,name,modifiedTime,size",
            apiKey
        )
        val req = newAuthorizedRequestBuilder(url, accessToken, apiKey)
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

    private fun newAuthorizedRequestBuilder(
        url: String,
        accessToken: String,
        apiKey: String
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
        if (apiKey.isNotBlank()) {
            builder.header("X-Goog-Api-Key", apiKey)
        }
        return builder
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
        private const val PLACEHOLDER_BUILD_KEY = "GOOGLE_DRIVE_API_KEY_DEFAULT_VALUE"
        private const val TOKEN_CACHE_TTL_MS = 40L * 60L * 1000L
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

        fun formatReadableAuthError(error: Throwable): String {
            if (error is ApiException) {
                return when (error.statusCode) {
                    CommonStatusCodes.CANCELED ->
                        "Google Drive sign-in cancelled. Tap Connect whenever you're ready."
                    CommonStatusCodes.SIGN_IN_REQUIRED ->
                        "Sign in to a Google account on this device first to connect Drive."
                    CommonStatusCodes.DEVELOPER_ERROR ->
                        "Google OAuth configuration check needed: verify your APK SHA-1 and OAuth client in Google Cloud Console."
                    CommonStatusCodes.NETWORK_ERROR ->
                        "Network unavailable while contacting Google Drive. Check internet and retry."
                    CommonStatusCodes.INTERNAL_ERROR, CommonStatusCodes.TIMEOUT ->
                        "Temporary Google Play Services error (${error.statusCode}). Tap Connect again."
                    else ->
                        "Google Drive sign-in error (${error.statusCode}): ${error.localizedMessage ?: "Retry"}"
                }
            }
            val msg = error.localizedMessage.orEmpty()
            return if (msg.contains("16:") || msg.contains("CANCELED", ignoreCase = true)) {
                "Google Drive sign-in cancelled."
            } else {
                msg.ifBlank { "Failed to authorize Google Drive access." }
            }
        }

        fun isConfiguredKey(candidate: String): Boolean {
            val clean = candidate.trim()
            return clean.isNotEmpty() &&
                clean != PLACEHOLDER_BUILD_KEY &&
                !clean.startsWith("YOUR_")
        }

        fun appendApiKey(url: String, apiKey: String): String {
            val clean = apiKey.trim()
            if (!isConfiguredKey(clean)) return url
            val sep = if (url.contains("?")) "&" else "?"
            return "$url${sep}key=${URLEncoder.encode(clean, "UTF-8")}"
        }

        fun maskKey(key: String): String =
            if (key.length <= 8) "••••••••" else "${key.take(4)}••••${key.takeLast(4)}"

        fun parseDriveAboutInfo(rawJson: String): DriveAboutInfo {
            val root = JSONObject(rawJson)
            val user = root.optJSONObject("user")
            val quota = root.optJSONObject("storageQuota")
            return DriveAboutInfo(
                displayName = user?.optString("displayName", "Google User").orEmpty().ifBlank { "Google User" },
                emailAddress = user?.optString("emailAddress", "").orEmpty(),
                storageLimitBytes = quota?.optString("limit", "0")?.toLongOrNull() ?: 0L,
                storageUsageBytes = quota?.optString("usage", "0")?.toLongOrNull() ?: 0L,
                storageUsageInDriveBytes = quota?.optString("usageInDrive", "0")?.toLongOrNull() ?: 0L
            )
        }

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
