package com.example.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ElevenLabsVoice(
    val voiceId: String,
    val name: String,
    val category: String,
    val accent: String = "",
    val description: String = "",
    val useCase: String = "",
    val previewUrl: String = ""
)

data class ElevenLabsSubscription(
    val tier: String,
    val characterCount: Long,
    val characterLimit: Long
) {
    val remainingCharacters: Long get() = (characterLimit - characterCount).coerceAtLeast(0L)
}

/**
 * Pure OkHttp client for ElevenLabs Voices / Text-to-Speech API and Google AI Studio
 * model verification. Never hardcodes or logs secrets and returns real API responses only.
 */
class ElevenLabsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchAvailableVoices(apiKey: String): Result<List<ElevenLabsVoice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmedKey = apiKey.trim()
                require(trimmedKey.isNotEmpty()) { "Enter an ElevenLabs API key first." }

                val req = Request.Builder()
                    .url("$ELEVENLABS_BASE/voices")
                    .header("xi-api-key", trimmedKey)
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseElevenLabsError(resp.code, bodyStr))
                    }
                    parseVoicesJson(bodyStr)
                }
            }
        }

    suspend fun fetchSubscription(apiKey: String): Result<ElevenLabsSubscription> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmedKey = apiKey.trim()
                require(trimmedKey.isNotEmpty()) { "Enter an ElevenLabs API key first." }

                val req = Request.Builder()
                    .url("$ELEVENLABS_BASE/user/subscription")
                    .header("xi-api-key", trimmedKey)
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseElevenLabsError(resp.code, bodyStr))
                    }
                    parseSubscriptionJson(bodyStr)
                }
            }
        }

    suspend fun synthesizeSpeechToFile(
        apiKey: String,
        voiceId: String,
        text: String,
        modelId: String = "eleven_multilingual_v2",
        stability: Float = 0.50f,
        similarityBoost: Float = 0.75f,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            val trimmedVoice = voiceId.trim()
            require(trimmedKey.isNotEmpty()) { "ElevenLabs API key is not configured." }
            require(trimmedVoice.isNotEmpty()) { "ElevenLabs Voice ID is not configured." }
            require(text.isNotBlank()) { "Speech text cannot be empty." }

            val payload = JSONObject().apply {
                put("text", text.take(2500))
                put("model_id", modelId.ifBlank { "eleven_multilingual_v2" })
                put("voice_settings", JSONObject().apply {
                    put("stability", stability.coerceIn(0f, 1f).toDouble())
                    put("similarity_boost", similarityBoost.coerceIn(0f, 1f).toDouble())
                })
            }.toString()

            val req = Request.Builder()
                .url("$ELEVENLABS_BASE/text-to-speech/$trimmedVoice")
                .header("xi-api-key", trimmedKey)
                .header("Accept", "audio/mpeg")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    throw IllegalStateException(parseElevenLabsError(resp.code, errText))
                }
                val bytes = resp.body?.bytes()
                    ?: throw IllegalStateException("ElevenLabs returned an empty audio stream.")
                if (bytes.isEmpty()) {
                    throw IllegalStateException("ElevenLabs returned 0 audio bytes.")
                }
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(bytes)
                outputFile
            }
        }
    }

    /**
     * Verifies a user-supplied Google AI Studio API key against the official Gemini REST endpoint
     * and returns real available Gemini text/multimodal model IDs.
     */
    suspend fun verifyGoogleAiStudioKey(apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = apiKey.trim()
                require(trimmed.isNotEmpty()) { "Enter a Google AI Studio API key first." }

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models")
                    .header("x-goog-api-key", trimmed)
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val serverMsg = runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message", "")
                        }.getOrNull().orEmpty()
                        val detail = serverMsg.ifBlank { "HTTP ${resp.code}" }
                        throw IllegalStateException("Google AI Studio key verification failed: $detail")
                    }
                    parseAiStudioModelsJson(raw)
                }
            }
        }

    companion object {
        private const val ELEVENLABS_BASE = "https://api.elevenlabs.io/v1"

        fun parseVoicesJson(rawJson: String): List<ElevenLabsVoice> {
            val root = JSONObject(rawJson.ifBlank { "{}" })
            val arr = root.optJSONArray("voices") ?: return emptyList()
            val out = mutableListOf<ElevenLabsVoice>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("voice_id", "").trim()
                val name = obj.optString("name", "").trim()
                if (id.isBlank() || name.isBlank()) continue
                val labels = obj.optJSONObject("labels")
                out.add(
                    ElevenLabsVoice(
                        voiceId = id,
                        name = name,
                        category = obj.optString("category", "library").ifBlank { "library" },
                        accent = labels?.optString("accent", "").orEmpty(),
                        description = labels?.optString("description", "").orEmpty(),
                        useCase = labels?.optString("use_case", "").orEmpty(),
                        previewUrl = obj.optString("preview_url", "").orEmpty()
                    )
                )
            }
            return out
        }

        fun parseSubscriptionJson(rawJson: String): ElevenLabsSubscription {
            val obj = JSONObject(rawJson.ifBlank { "{}" })
            return ElevenLabsSubscription(
                tier = obj.optString("tier", "free").ifBlank { "free" },
                characterCount = obj.optLong("character_count", 0L),
                characterLimit = obj.optLong("character_limit", 0L)
            )
        }

        fun parseAiStudioModelsJson(rawJson: String): List<String> {
            val root = JSONObject(rawJson.ifBlank { "{}" })
            val models = root.optJSONArray("models") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val obj = models.optJSONObject(i) ?: continue
                val fullName = obj.optString("name", "")
                val shortName = fullName.removePrefix("models/").trim()
                val methods = obj.optJSONArray("supportedGenerationMethods")
                var supportsGenerate = false
                if (methods != null) {
                    for (m in 0 until methods.length()) {
                        if (methods.optString(m) == "generateContent") {
                            supportsGenerate = true
                            break
                        }
                    }
                }
                if (shortName.startsWith("gemini") && supportsGenerate && shortName !in list) {
                    list.add(shortName)
                }
            }
            return list
        }

        private fun parseElevenLabsError(code: Int, rawBody: String): String {
            val detailMsg = runCatching {
                val obj = JSONObject(rawBody)
                val detail = obj.opt("detail")
                when (detail) {
                    is JSONObject -> detail.optString("message", detail.optString("status", ""))
                    is String -> detail
                    else -> obj.optString("message", "")
                }
            }.getOrNull().orEmpty()
            return if (detailMsg.isNotBlank()) "ElevenLabs HTTP $code: $detailMsg"
            else "ElevenLabs HTTP $code"
        }
    }
}
