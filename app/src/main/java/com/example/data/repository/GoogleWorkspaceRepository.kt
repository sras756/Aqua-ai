package com.example.data.repository

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

data class WorkspaceContact(
    val resourceName: String,
    val displayName: String,
    val email: String,
    val phone: String,
    val organization: String
)

data class WorkspaceCalendarEvent(
    val id: String,
    val summary: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    val htmlLink: String
)

data class WorkspaceEmailMessage(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val date: String,
    val snippet: String
)

data class WorkspaceSheetReadResult(
    val spreadsheetId: String,
    val range: String,
    val rows: List<List<String>>
)

data class WorkspaceSheetCreated(
    val spreadsheetId: String,
    val title: String,
    val spreadsheetUrl: String
)

data class WorkspaceFormInfo(
    val formId: String,
    val title: String,
    val responderUri: String,
    val questionTitles: List<String>
)

data class WorkspaceFormResponseItem(
    val responseId: String,
    val lastSubmittedTime: String,
    val respondentEmail: String,
    val answersSummary: String
)

data class WorkspaceChatSpace(
    val name: String,
    val displayName: String,
    val spaceType: String
)

data class WorkspaceChatMessage(
    val name: String,
    val senderName: String,
    val text: String,
    val createTime: String
)

sealed interface WorkspaceAuthOutcome {
    data class Authorized(val accessToken: String) : WorkspaceAuthOutcome
    data class NeedsResolution(val pendingIntent: PendingIntent) : WorkspaceAuthOutcome
    data class Error(val message: String) : WorkspaceAuthOutcome
}

enum class WorkspaceService(val label: String, val scopes: List<String>) {
    GMAIL(
        "Gmail",
        listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send"
        )
    ),
    CALENDAR(
        "Calendar",
        listOf("https://www.googleapis.com/auth/calendar")
    ),
    CONTACTS(
        "Contacts",
        listOf("https://www.googleapis.com/auth/contacts")
    ),
    SHEETS(
        "Sheets",
        listOf("https://www.googleapis.com/auth/spreadsheets")
    ),
    FORMS(
        "Forms",
        listOf(
            "https://www.googleapis.com/auth/forms.body",
            "https://www.googleapis.com/auth/forms.responses.readonly"
        )
    ),
    CHAT(
        "Chat",
        listOf(
            "https://www.googleapis.com/auth/chat.spaces.readonly",
            "https://www.googleapis.com/auth/chat.messages"
        )
    )
}

/**
 * Real Google Workspace REST API v1/v3/v4 repository for:
 * - Google Contacts (People API v1)
 * - Google Forms (Forms API v1)
 * - Google Calendar (Calendar API v3)
 * - Gmail (Gmail API v1)
 * - Google Sheets (Sheets API v4)
 * - Google Chat (Chat API v1)
 * Uses Android Google Identity Services AuthorizationClient for OAuth access tokens.
 */
class GoogleWorkspaceRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    fun allWorkspaceScopes(): List<Scope> =
        WorkspaceService.entries.flatMap { it.scopes }.distinct().map { Scope(it) }

    suspend fun authorizeScopes(
        activity: Activity?,
        scopeStrings: List<String>
    ): WorkspaceAuthOutcome {
        return try {
            val target: Context = activity ?: context
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopeStrings.distinct().map { Scope(it) })
                .build()
            val result = Identity.getAuthorizationClient(target)
                .authorize(request)
                .await()
            val token = result.accessToken
            val pending = result.pendingIntent
            when {
                result.hasResolution() && pending != null -> WorkspaceAuthOutcome.NeedsResolution(pending)
                !token.isNullOrBlank() -> WorkspaceAuthOutcome.Authorized(token)
                else -> WorkspaceAuthOutcome.Error("Authorization completed without an access token.")
            }
        } catch (e: Exception) {
            WorkspaceAuthOutcome.Error(e.localizedMessage ?: "Google Workspace OAuth authorization failed.")
        }
    }

    suspend fun tryGetSilentToken(scopeStrings: List<String>): String? {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopeStrings.distinct().map { Scope(it) })
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
        if (intent == null) {
            throw IllegalStateException(
                "Google Workspace consent was cancelled or blocked (Error 403: access_denied if the selected account is not listed under OAuth Test Users)."
            )
        }
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(intent)
        result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google Workspace consent was not granted.")
    }

    // --- 1. GOOGLE CONTACTS (People API v1) ---

    suspend fun listContacts(accessToken: String, query: String = ""): Result<List<WorkspaceContact>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = query.trim()
                val url = if (trimmed.isNotEmpty()) {
                    "https://people.googleapis.com/v1/people:searchContacts?query=${enc(trimmed)}&readMask=names,emailAddresses,phoneNumbers,organizations&pageSize=25"
                } else {
                    "https://people.googleapis.com/v1/people/me/connections?personFields=names,emailAddresses,phoneNumbers,organizations&pageSize=30&sortOrder=FIRST_NAME_ASCENDING"
                }
                val root = getJson(accessToken, url)
                val arr = if (trimmed.isNotEmpty()) {
                    root.optJSONArray("results")?.let { res ->
                        JSONArray().apply {
                            for (i in 0 until res.length()) {
                                res.optJSONObject(i)?.optJSONObject("person")?.let { put(it) }
                            }
                        }
                    } ?: JSONArray()
                } else {
                    root.optJSONArray("connections") ?: JSONArray()
                }
                (0 until arr.length()).map { idx ->
                    val p = arr.getJSONObject(idx)
                    val name = p.optJSONArray("names")?.optJSONObject(0)?.optString("displayName", "")
                        ?.ifBlank { "Unnamed Contact" } ?: "Unnamed Contact"
                    val email = p.optJSONArray("emailAddresses")?.optJSONObject(0)?.optString("value", "").orEmpty()
                    val phone = p.optJSONArray("phoneNumbers")?.optJSONObject(0)?.optString("value", "").orEmpty()
                    val org = p.optJSONArray("organizations")?.optJSONObject(0)?.optString("name", "").orEmpty()
                    WorkspaceContact(
                        resourceName = p.optString("resourceName", ""),
                        displayName = name,
                        email = email,
                        phone = phone,
                        organization = org
                    )
                }
            }
        }

    suspend fun createContact(
        accessToken: String,
        fullName: String,
        email: String,
        phone: String
    ): Result<WorkspaceContact> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedName = fullName.trim()
            require(trimmedName.isNotEmpty()) { "Contact name is required." }
            val parts = trimmedName.split(Regex("\\s+"), limit = 2)
            val body = JSONObject().apply {
                put(
                    "names",
                    JSONArray().put(
                        JSONObject().apply {
                            put("givenName", parts[0])
                            if (parts.size > 1) put("familyName", parts[1])
                        }
                    )
                )
                if (email.isNotBlank()) {
                    put("emailAddresses", JSONArray().put(JSONObject().put("value", email.trim())))
                }
                if (phone.isNotBlank()) {
                    put("phoneNumbers", JSONArray().put(JSONObject().put("value", phone.trim())))
                }
            }
            val created = postJson(
                accessToken,
                "https://people.googleapis.com/v1/people:createContact",
                body
            )
            WorkspaceContact(
                resourceName = created.optString("resourceName", ""),
                displayName = trimmedName,
                email = email.trim(),
                phone = phone.trim(),
                organization = ""
            )
        }
    }

    // --- 2. GOOGLE CALENDAR (Calendar API v3) ---

    suspend fun listUpcomingEvents(accessToken: String, maxResults: Int = 20): Result<List<WorkspaceCalendarEvent>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
                    "?timeMin=${enc(nowIso)}&singleEvents=true&orderBy=startTime&maxResults=${maxResults.coerceIn(1, 50)}"
                val root = getJson(accessToken, url)
                val items = root.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { i ->
                    val ev = items.getJSONObject(i)
                    val startObj = ev.optJSONObject("start")
                    val endObj = ev.optJSONObject("end")
                    val start = startObj?.optString("dateTime", "")
                        ?.ifBlank { startObj.optString("date", "") }.orEmpty()
                    val end = endObj?.optString("dateTime", "")
                        ?.ifBlank { endObj.optString("date", "") }.orEmpty()
                    WorkspaceCalendarEvent(
                        id = ev.optString("id", ""),
                        summary = ev.optString("summary", "(No Title)"),
                        startTime = start,
                        endTime = end,
                        location = ev.optString("location", ""),
                        description = ev.optString("description", ""),
                        htmlLink = ev.optString("htmlLink", "")
                    )
                }
            }
        }

    suspend fun createCalendarEvent(
        accessToken: String,
        summary: String,
        startIso: String,
        endIso: String,
        description: String = "",
        location: String = ""
    ): Result<WorkspaceCalendarEvent> = withContext(Dispatchers.IO) {
        runCatching {
            require(summary.isNotBlank()) { "Event title is required." }
            require(startIso.isNotBlank() && endIso.isNotBlank()) { "Start and end ISO timestamps are required." }
            val tz = TimeZone.getDefault().id
            val body = JSONObject().apply {
                put("summary", summary.trim())
                if (description.isNotBlank()) put("description", description.trim())
                if (location.isNotBlank()) put("location", location.trim())
                put("start", JSONObject().apply {
                    put("dateTime", startIso.trim())
                    put("timeZone", tz)
                })
                put("end", JSONObject().apply {
                    put("dateTime", endIso.trim())
                    put("timeZone", tz)
                })
            }
            val ev = postJson(
                accessToken,
                "https://www.googleapis.com/calendar/v3/calendars/primary/events",
                body
            )
            WorkspaceCalendarEvent(
                id = ev.optString("id", ""),
                summary = ev.optString("summary", summary),
                startTime = startIso.trim(),
                endTime = endIso.trim(),
                location = location.trim(),
                description = description.trim(),
                htmlLink = ev.optString("htmlLink", "")
            )
        }
    }

    // --- 3. GMAIL (Gmail API v1) ---

    suspend fun listGmailMessages(
        accessToken: String,
        query: String = "",
        maxResults: Int = 12
    ): Result<List<WorkspaceEmailMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val qParam = if (query.isNotBlank()) "&q=${enc(query.trim())}" else ""
            val listUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=${maxResults.coerceIn(1, 20)}$qParam"
            val listJson = getJson(accessToken, listUrl)
            val msgs = listJson.optJSONArray("messages") ?: JSONArray()
            val results = mutableListOf<WorkspaceEmailMessage>()
            for (i in 0 until minOf(msgs.length(), maxResults)) {
                val id = msgs.getJSONObject(i).getString("id")
                val msgUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata" +
                    "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date"
                val detail = getJson(accessToken, msgUrl)
                val headers = detail.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
                var subject = "(No Subject)"
                var from = ""
                var date = ""
                for (h in 0 until headers.length()) {
                    val hObj = headers.getJSONObject(h)
                    when (hObj.optString("name").lowercase()) {
                        "subject" -> subject = hObj.optString("value", subject)
                        "from" -> from = hObj.optString("value", "")
                        "date" -> date = hObj.optString("value", "")
                    }
                }
                results.add(
                    WorkspaceEmailMessage(
                        id = id,
                        threadId = detail.optString("threadId", ""),
                        from = from,
                        subject = subject,
                        date = date,
                        snippet = detail.optString("snippet", "")
                    )
                )
            }
            results
        }
    }

    suspend fun sendGmailMessage(
        accessToken: String,
        to: String,
        subject: String,
        bodyText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(to.isNotBlank()) { "Recipient email is required." }
            require(subject.isNotBlank()) { "Subject is required." }
            val rawMime = buildString {
                append("To: ${to.trim()}\r\n")
                append("Subject: ${subject.trim()}\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("MIME-Version: 1.0\r\n\r\n")
                append(bodyText)
            }
            val encoded = Base64.encodeToString(
                rawMime.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val payload = JSONObject().put("raw", encoded)
            val resp = postJson(
                accessToken,
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                payload
            )
            resp.optString("id", "sent")
        }
    }

    // --- 4. GOOGLE SHEETS (Sheets API v4) ---

    suspend fun createSpreadsheet(
        accessToken: String,
        title: String
    ): Result<WorkspaceSheetCreated> = withContext(Dispatchers.IO) {
        runCatching {
            require(title.isNotBlank()) { "Spreadsheet title is required." }
            val body = JSONObject().put(
                "properties",
                JSONObject().put("title", title.trim())
            )
            val json = postJson(
                accessToken,
                "https://sheets.googleapis.com/v4/spreadsheets",
                body
            )
            WorkspaceSheetCreated(
                spreadsheetId = json.getString("spreadsheetId"),
                title = json.optJSONObject("properties")?.optString("title", title) ?: title,
                spreadsheetUrl = json.optString("spreadsheetUrl", "")
            )
        }
    }

    suspend fun readSheetRange(
        accessToken: String,
        spreadsheetId: String,
        range: String = "Sheet1!A1:J25"
    ): Result<WorkspaceSheetReadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sid = spreadsheetId.trim()
            require(sid.isNotEmpty()) { "Spreadsheet ID is required." }
            val cleanRange = range.trim().ifBlank { "Sheet1!A1:J25" }
            val url = "https://sheets.googleapis.com/v4/spreadsheets/${enc(sid)}/values/${enc(cleanRange)}"
            val json = getJson(accessToken, url)
            val rowsArr = json.optJSONArray("values") ?: JSONArray()
            val rows = (0 until rowsArr.length()).map { r ->
                val rowArr = rowsArr.getJSONArray(r)
                (0 until rowArr.length()).map { c -> rowArr.optString(c, "") }
            }
            WorkspaceSheetReadResult(
                spreadsheetId = sid,
                range = json.optString("range", cleanRange),
                rows = rows
            )
        }
    }

    suspend fun appendSheetRow(
        accessToken: String,
        spreadsheetId: String,
        range: String,
        values: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sid = spreadsheetId.trim()
            require(sid.isNotEmpty()) { "Spreadsheet ID is required." }
            require(values.isNotEmpty()) { "At least one cell value is required." }
            val cleanRange = range.trim().ifBlank { "Sheet1!A1" }
            val body = JSONObject().apply {
                put("majorDimension", "ROWS")
                put("values", JSONArray().put(JSONArray(values)))
            }
            val url = "https://sheets.googleapis.com/v4/spreadsheets/${enc(sid)}/values/${enc(cleanRange)}:append?valueInputOption=USER_ENTERED"
            val json = postJson(accessToken, url, body)
            val updatedRange = json.optJSONObject("updates")?.optString("updatedRange", cleanRange) ?: cleanRange
            "Appended row to $updatedRange"
        }
    }

    // --- 5. GOOGLE FORMS (Forms API v1) ---

    suspend fun createForm(
        accessToken: String,
        title: String,
        firstQuestionTitle: String = ""
    ): Result<WorkspaceFormInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(title.isNotBlank()) { "Form title is required." }
            val createBody = JSONObject().put(
                "info",
                JSONObject().put("title", title.trim()).put("documentTitle", title.trim())
            )
            val created = postJson(accessToken, "https://forms.googleapis.com/v1/forms", createBody)
            val formId = created.getString("formId")
            val responderUri = created.optString("responderUri", "")

            val questions = mutableListOf<String>()
            if (firstQuestionTitle.isNotBlank()) {
                val batchBody = JSONObject().put(
                    "requests",
                    JSONArray().put(
                        JSONObject().put(
                            "createItem",
                            JSONObject().apply {
                                put("item", JSONObject().apply {
                                    put("title", firstQuestionTitle.trim())
                                    put(
                                        "questionItem",
                                        JSONObject().put(
                                            "question",
                                            JSONObject().apply {
                                                put("required", false)
                                                put("textQuestion", JSONObject().put("paragraph", false))
                                            }
                                        )
                                    )
                                })
                                put("location", JSONObject().put("index", 0))
                            }
                        )
                    )
                )
                postJson(accessToken, "https://forms.googleapis.com/v1/forms/$formId:batchUpdate", batchBody)
                questions.add(firstQuestionTitle.trim())
            }

            WorkspaceFormInfo(
                formId = formId,
                title = title.trim(),
                responderUri = responderUri,
                questionTitles = questions
            )
        }
    }

    suspend fun getFormAndResponses(
        accessToken: String,
        formId: String
    ): Result<Pair<WorkspaceFormInfo, List<WorkspaceFormResponseItem>>> = withContext(Dispatchers.IO) {
        runCatching {
            val fid = formId.trim()
            require(fid.isNotEmpty()) { "Form ID is required." }
            val formJson = getJson(accessToken, "https://forms.googleapis.com/v1/forms/${enc(fid)}")
            val title = formJson.optJSONObject("info")?.optString("title", "Google Form") ?: "Google Form"
            val responderUri = formJson.optString("responderUri", "")
            val itemsArr = formJson.optJSONArray("items") ?: JSONArray()
            val qMap = mutableMapOf<String, String>()
            val qTitles = mutableListOf<String>()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                val qTitle = item.optString("title", "Question ${i + 1}")
                qTitles.add(qTitle)
                val qId = item.optJSONObject("questionItem")?.optJSONObject("question")?.optString("questionId", "")
                if (!qId.isNullOrBlank()) qMap[qId] = qTitle
            }

            val respJson = getJson(accessToken, "https://forms.googleapis.com/v1/forms/${enc(fid)}/responses")
            val respArr = respJson.optJSONArray("responses") ?: JSONArray()
            val responses = (0 until respArr.length()).map { idx ->
                val r = respArr.getJSONObject(idx)
                val answersObj = r.optJSONObject("answers")
                val answerParts = mutableListOf<String>()
                if (answersObj != null) {
                    val keys = answersObj.keys()
                    while (keys.hasNext()) {
                        val qId = keys.next()
                        val ansObj = answersObj.optJSONObject(qId)
                        val textVals = ansObj?.optJSONObject("textAnswers")?.optJSONArray("answers")
                        val vals = if (textVals != null) {
                            (0 until textVals.length()).mapNotNull { textVals.optJSONObject(it)?.optString("value") }
                        } else emptyList()
                        val label = qMap[qId] ?: qId
                        answerParts.add("$label: ${vals.joinToString(", ")}")
                    }
                }
                WorkspaceFormResponseItem(
                    responseId = r.optString("responseId", "resp_$idx"),
                    lastSubmittedTime = r.optString("lastSubmittedTime", ""),
                    respondentEmail = r.optString("respondentEmail", ""),
                    answersSummary = answerParts.joinToString(" | ").ifBlank { "(No text answers)" }
                )
            }

            WorkspaceFormInfo(fid, title, responderUri, qTitles) to responses
        }
    }

    // --- 6. GOOGLE CHAT (Chat API v1) ---

    suspend fun listChatSpaces(accessToken: String): Result<List<WorkspaceChatSpace>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = getJson(accessToken, "https://chat.googleapis.com/v1/spaces?pageSize=25")
                val arr = root.optJSONArray("spaces") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val spaceName = s.optString("name", "")
                    WorkspaceChatSpace(
                        name = spaceName,
                        displayName = s.optString("displayName", "").ifBlank { spaceName },
                        spaceType = s.optString("spaceType", s.optString("type", "SPACE"))
                    )
                }
            }
        }

    suspend fun listChatMessages(
        accessToken: String,
        spaceName: String
    ): Result<List<WorkspaceChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSpace = normalizeSpaceName(spaceName)
            val root = getJson(accessToken, "https://chat.googleapis.com/v1/$cleanSpace/messages?pageSize=20")
            val arr = root.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val sender = m.optJSONObject("sender")
                WorkspaceChatMessage(
                    name = m.optString("name", ""),
                    senderName = sender?.optString("displayName", "")
                        ?.ifBlank { sender.optString("name", "Member") } ?: "Member",
                    text = m.optString("text", ""),
                    createTime = m.optString("createTime", "")
                )
            }
        }
    }

    suspend fun sendChatMessage(
        accessToken: String,
        spaceName: String,
        text: String
    ): Result<WorkspaceChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            require(text.isNotBlank()) { "Chat message text is required." }
            val cleanSpace = normalizeSpaceName(spaceName)
            val body = JSONObject().put("text", text.trim())
            val m = postJson(accessToken, "https://chat.googleapis.com/v1/$cleanSpace/messages", body)
            WorkspaceChatMessage(
                name = m.optString("name", ""),
                senderName = "You",
                text = m.optString("text", text.trim()),
                createTime = m.optString("createTime", "")
            )
        }
    }

    private fun normalizeSpaceName(spaceName: String): String {
        val trimmed = spaceName.trim().trimStart('/')
        require(trimmed.isNotEmpty()) { "Chat space resource name is required (e.g., spaces/AAAA...)." }
        return if (trimmed.startsWith("spaces/")) trimmed else "spaces/$trimmed"
    }

    private fun getJson(accessToken: String, url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractGoogleApiError(resp.code, raw))
            }
            return JSONObject(raw.ifBlank { "{}" })
        }
    }

    private fun postJson(accessToken: String, url: String, bodyJson: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractGoogleApiError(resp.code, raw))
            }
            return JSONObject(raw.ifBlank { "{}" })
        }
    }

    private fun extractGoogleApiError(code: Int, raw: String): String {
        val parsed = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) {
            "Google API HTTP $code: $parsed"
        } else {
            "Google API HTTP $code"
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
