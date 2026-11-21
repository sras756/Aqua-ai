package com.example.core.connectors

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class GoogleServiceId(
    val title: String,
    val description: String,
    val oauthScope: String,
    val apiProbeUrl: String
) {
    GMAIL(
        title = "Gmail",
        description = "Read-only access to recent email subjects and snippets upon explicit user approval.",
        oauthScope = "https://www.googleapis.com/auth/gmail.readonly",
        apiProbeUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=5"
    ),
    CALENDAR(
        title = "Google Calendar",
        description = "Read-only access to upcoming calendar events and agenda summaries.",
        oauthScope = "https://www.googleapis.com/auth/calendar.readonly",
        apiProbeUrl = "https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=5"
    ),
    CONTACTS(
        title = "Google Contacts",
        description = "Read-only access to People API contacts when looking up a recipient.",
        oauthScope = "https://www.googleapis.com/auth/contacts.readonly",
        apiProbeUrl = "https://people.googleapis.com/v1/people/me/connections?personFields=names,emailAddresses&pageSize=5"
    ),
    PHOTOS(
        title = "Google Photos",
        description = "Read-only access to media items library metadata when explicitly requested.",
        oauthScope = "https://www.googleapis.com/auth/photoslibrary.readonly",
        apiProbeUrl = "https://photoslibrary.googleapis.com/v1/mediaItems?pageSize=5"
    )
}

data class ConnectorStatus(
    val service: GoogleServiceId,
    val enabledInPrefs: Boolean = false,
    val scopeGranted: Boolean = false,
    val lastSyncSummary: String = "Not connected"
)

data class ConnectorAuthLaunchResult(
    val authorized: Boolean,
    val pendingIntent: PendingIntent? = null,
    val accessToken: String? = null,
    val statusMessage: String = ""
)

/**
 * Modular Google Ecosystem Connectors (`Gmail`, `Calendar`, `Contacts`, `Photos`).
 * Enforces independent per-connector OAuth 2.0 scopes, toggle state, and one-tap revocation.
 */
class GoogleConnectorManager(
    context: Context,
    userId: String = "default_user",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "aqua_connectors_${userId.ifBlank { "default_user" }}",
        Context.MODE_PRIVATE
    )

    private val _statuses = MutableStateFlow(loadStatuses())
    val statuses: StateFlow<Map<GoogleServiceId, ConnectorStatus>> = _statuses.asStateFlow()

    fun isConnectorEnabled(service: GoogleServiceId): Boolean =
        prefs.getBoolean(prefEnabledKey(service), false)

    fun setConnectorEnabled(service: GoogleServiceId, enabled: Boolean) {
        prefs.edit().putBoolean(prefEnabledKey(service), enabled).apply()
        if (!enabled) {
            prefs.edit().putBoolean(prefGrantedKey(service), false).apply()
        }
        _statuses.value = loadStatuses()
    }

    suspend fun authorizeConnector(service: GoogleServiceId): ConnectorAuthLaunchResult {
        if (!isConnectorEnabled(service)) {
            setConnectorEnabled(service, true)
        }
        return runCatching {
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope(service.oauthScope)))
                .build()
            val result = Identity.getAuthorizationClient(appContext).authorize(request).await()
            if (result.hasResolution()) {
                ConnectorAuthLaunchResult(
                    authorized = false,
                    pendingIntent = result.pendingIntent,
                    statusMessage = "Approve ${service.title} OAuth scope in consent sheet."
                )
            } else {
                val token = result.accessToken
                val ok = !token.isNullOrBlank()
                prefs.edit().putBoolean(prefGrantedKey(service), ok).apply()
                _statuses.value = loadStatuses()
                ConnectorAuthLaunchResult(
                    authorized = ok,
                    accessToken = token,
                    statusMessage = if (ok) "${service.title} OAuth scope active." else "No OAuth token returned."
                )
            }
        }.getOrElse { err ->
            ConnectorAuthLaunchResult(
                authorized = false,
                statusMessage = "${service.title} authorization error: ${err.localizedMessage}"
            )
        }
    }

    fun handleConsentResult(service: GoogleServiceId, data: Intent?): Result<String> = runCatching {
        val authResult = Identity.getAuthorizationClient(appContext)
            .getAuthorizationResultFromIntent(data)
        val token = authResult.accessToken
        require(!token.isNullOrBlank()) { "No OAuth token returned for ${service.title}." }
        prefs.edit()
            .putBoolean(prefEnabledKey(service), true)
            .putBoolean(prefGrantedKey(service), true)
            .apply()
        _statuses.value = loadStatuses()
        token
    }

    /**
     * One-tap Scope Revocation calling Google OAuth2 revoke endpoint + clearing local connector state.
     */
    suspend fun revokeConnectorScope(service: GoogleServiceId, accessToken: String? = null) = withContext(Dispatchers.IO) {
        if (!accessToken.isNullOrBlank()) {
            runCatching {
                val form = FormBody.Builder().add("token", accessToken).build()
                val req = Request.Builder()
                    .url("https://oauth2.googleapis.com/revoke")
                    .post(form)
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }
        prefs.edit()
            .putBoolean(prefEnabledKey(service), false)
            .putBoolean(prefGrantedKey(service), false)
            .remove(prefSummaryKey(service))
            .apply()
        _statuses.value = loadStatuses()
    }

    /**
     * Fetches real API preview metadata using ONLY the connector's own granted OAuth 2.0 scope.
     */
    suspend fun fetchLivePreview(
        service: GoogleServiceId,
        accessToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConnectorEnabled(service)) {
            return@withContext Result.failure(
                SecurityException("${service.title} connector is disabled. Enable it in Settings -> Google Connectors first.")
            )
        }
        runCatching {
            val req = Request.Builder()
                .url(service.apiProbeUrl)
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("${service.title} API returned HTTP ${resp.code}: ${raw.take(240)}")
                }
                val summary = when (service) {
                    GoogleServiceId.GMAIL -> "Messages listed: ${JSONObject(raw).optJSONArray("messages")?.length() ?: 0}"
                    GoogleServiceId.CALENDAR -> "Upcoming events: ${JSONObject(raw).optJSONArray("items")?.length() ?: 0}"
                    GoogleServiceId.CONTACTS -> "Contacts loaded: ${JSONObject(raw).optJSONArray("connections")?.length() ?: 0}"
                    GoogleServiceId.PHOTOS -> "Media items loaded: ${JSONObject(raw).optJSONArray("mediaItems")?.length() ?: 0}"
                }
                prefs.edit().putString(prefSummaryKey(service), summary).apply()
                _statuses.value = loadStatuses()
                summary
            }
        }
    }

    private fun loadStatuses(): Map<GoogleServiceId, ConnectorStatus> =
        GoogleServiceId.entries.associateWith { svc ->
            ConnectorStatus(
                service = svc,
                enabledInPrefs = prefs.getBoolean(prefEnabledKey(svc), false),
                scopeGranted = prefs.getBoolean(prefGrantedKey(svc), false),
                lastSyncSummary = prefs.getString(prefSummaryKey(svc), null)
                    ?: if (prefs.getBoolean(prefEnabledKey(svc), false)) "Enabled (tap Authorize)" else "Disabled"
            )
        }

    private fun prefEnabledKey(s: GoogleServiceId) = "conn_enabled_${s.name}"
    private fun prefGrantedKey(s: GoogleServiceId) = "conn_granted_${s.name}"
    private fun prefSummaryKey(s: GoogleServiceId) = "conn_summary_${s.name}"
}
