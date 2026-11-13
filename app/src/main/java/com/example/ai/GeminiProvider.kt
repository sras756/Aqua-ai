package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.local.SecureConfigStore
import com.example.data.model.AgentStepTrace
import com.example.data.model.ChatMessage
import com.example.data.model.ImportedAttachment
import com.example.data.model.PendingAuthorization
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.data.repository.FileImportRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LatLng
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.retrievalConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AgentExecutionResult(
    val text: String,
    val modelUsed: String,
    val traces: List<AgentStepTrace>,
    val groundingSources: List<String>
)

class GeminiProvider(
    private val secureConfigStore: SecureConfigStore,
    private val fileImportRepository: FileImportRepository,
    private val toolCallingEngine: ToolCallingEngine
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun buildModel(
        modelName: String,
        systemInstructionText: String,
        settings: UserSettings,
        enableFunctionTools: Boolean,
        enableSearchGrounding: Boolean = false,
        enableMapsGrounding: Boolean = false,
        userLatLng: Pair<Double, Double>? = null
    ): GenerativeModel {
        val isCarbonThinking = modelName == UserSettings.MODEL_CARBON && settings.thinkingEnabled
        val config = generationConfig {
            if (isCarbonThinking) {
                // High thinking mode on gemini-4-carbon (do NOT set maxOutputTokens per skill requirement)
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            }
        }

        val tools = mutableListOf<Tool>()
        if (enableFunctionTools) {
            tools.add(toolCallingEngine.buildToolDeclarations())
        }
        if (enableSearchGrounding && !enableFunctionTools) {
            tools.add(Tool.googleSearch())
        }
        if (enableMapsGrounding && !enableFunctionTools) {
            tools.add(Tool.googleMaps())
        }

        val toolConfig = if (enableMapsGrounding && !enableFunctionTools && userLatLng != null) {
            ToolConfig(
                retrievalConfig = retrievalConfig {
                    latLng = LatLng(latitude = userLatLng.first, longitude = userLatLng.second)
                    languageCode = settings.voiceLanguage
                }
            )
        } else {
            null
        }

        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = config,
            tools = tools.ifEmpty { null },
            toolConfig = toolConfig,
            systemInstruction = content { text(systemInstructionText) }
        )
    }

    @OptIn(PublicPreviewAPI::class)
    fun buildLiveModel(systemInstructionText: String, voiceName: String = "Kore"): LiveGenerativeModel {
        return Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = UserSettings.MODEL_LIVE,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice(voiceName))
            },
            tools = listOf(toolCallingEngine.buildToolDeclarations()),
            systemInstruction = content { text(systemInstructionText) }
        )
    }

    /**
     * Generates natural speech PCM audio bytes via Gemini TTS model.
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun synthesizeGeminiTtsAudio(
        text: String,
        languageCode: String = "en-US"
    ): Result<ByteArray> = runCatching {
        val config = generationConfig {
            responseModalities = listOf(ResponseModality.AUDIO)
            speechConfig = SpeechConfig(
                voice = Voice("Kore"),
                languageCode = languageCode
            )
        }
        val ttsModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = UserSettings.MODEL_TTS,
            generationConfig = config
        )
        val response = withTimeout(45_000L) {
            ttsModel.generateContent(text.take(1500))
        }
        val part = response.candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<InlineDataPart>()
            ?.firstOrNull()
            ?: throw IllegalStateException("TTS model returned no inline audio payload.")
        part.inlineData
    }

    /**
     * Main Autonomous Agent & Multi-turn Chat Executor.
     * Supports streaming updates, multi-step tool calling, image/video multimodal input (gemini-4-argon),
     * deep reasoning (gemini-4-carbon with ThinkingLevel.HIGH), low-latency (gemini-3.1-flash-lite),
     * Search & Maps grounding (gemini-4-hydrogen), and optional user-supplied direct API key.
     */
    suspend fun executeAgentTurn(
        userPrompt: String,
        history: List<ChatMessage>,
        attachment: ImportedAttachment?,
        settings: UserSettings,
        systemInstructionText: String,
        activeWallet: WalletConfig?,
        userLatLng: Pair<Double, Double>? = null,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit
    ): Result<AgentExecutionResult> {
        val selectedModel = selectOptimalModel(userPrompt, attachment, settings)
        val traces = mutableListOf<AgentStepTrace>()
        val groundingSources = mutableListOf<String>()

        // Optional direct API key path if user explicitly configured & enabled their own Gemini API key
        val customKey = secureConfigStore.getGeminiApiKey()
        if (settings.useCustomDirectApiKey && !customKey.isNullOrBlank() && attachment == null) {
            return executeDirectRestFallback(
                apiKey = customKey,
                modelName = selectedModel,
                systemInstruction = systemInstructionText,
                history = history,
                userPrompt = userPrompt,
                onStreamChunk = onStreamChunk
            )
        }

        return try {
            val useGroundingInsteadOfTools = shouldUseWebOrMapsGrounding(userPrompt, settings)
            val model = buildModel(
                modelName = selectedModel,
                systemInstructionText = systemInstructionText,
                settings = settings,
                enableFunctionTools = !useGroundingInsteadOfTools && attachment == null,
                enableSearchGrounding = useGroundingInsteadOfTools && settings.searchGroundingEnabled,
                enableMapsGrounding = useGroundingInsteadOfTools && settings.mapsGroundingEnabled,
                userLatLng = userLatLng
            )

            val historyContents = history
                .filter { it.role == "user" || it.role == "model" }
                .takeLast(14)
                .map { msg ->
                    content(role = msg.role) { text(msg.content) }
                }

            val userContent = buildUserContent(userPrompt, attachment)

            if (useGroundingInsteadOfTools || attachment != null) {
                // Stream directly for grounding or multimodal analysis
                val sb = StringBuilder()
                withTimeout(60_000L) {
                    val chat = model.startChat(history = historyContents)
                    chat.sendMessageStream(userContent).collect { chunk ->
                        val t = chunk.text.orEmpty()
                        if (t.isNotEmpty()) {
                            sb.append(t)
                            onStreamChunk(sb.toString())
                        }
                        chunk.candidates.firstOrNull()?.groundingMetadata?.groundingChunks?.forEach { gChunk ->
                            val title = gChunk.web?.title ?: gChunk.maps?.title
                            val uri = gChunk.web?.uri ?: gChunk.maps?.uri
                            if (!title.isNullOrBlank() || !uri.isNullOrBlank()) {
                                val label = listOfNotNull(title, uri).joinToString(" — ")
                                if (label !in groundingSources) groundingSources.add(label)
                            }
                        }
                    }
                }
                val finalText = sb.toString().ifBlank { "I processed your input, but no text summary was returned." }
                Result.success(AgentExecutionResult(finalText, selectedModel, traces, groundingSources))
            } else {
                // Autonomous multi-step function-calling loop
                val chat = model.startChat(history = historyContents)
                var response = withTimeout(60_000L) {
                    chat.sendMessage(userContent)
                }

                var loopCount = 0
                while (response.functionCalls.isNotEmpty() && loopCount < MAX_AGENT_STEPS) {
                    loopCount++
                    val responseParts = mutableListOf<FunctionResponsePart>()
                    for (fnCall in response.functionCalls) {
                        val traceRecorder: (AgentStepTrace) -> Unit = { trace ->
                            traces.add(trace)
                            onTrace(trace)
                        }
                        val fnResult = toolCallingEngine.executeFunctionCall(
                            call = fnCall,
                            settings = settings,
                            activeWallet = activeWallet,
                            onNavigateToBrowser = onNavigateToBrowser,
                            requestAuthorization = requestAuthorization,
                            onTrace = traceRecorder
                        )
                        responseParts.add(FunctionResponsePart(fnCall.name, fnResult))
                    }

                    val toolContent = Content(role = "user", parts = responseParts)
                    response = withTimeout(60_000L) {
                        chat.sendMessage(toolContent)
                    }
                }

                val finalText = response.text?.takeIf { it.isNotBlank() }
                    ?: "Task execution finished (${traces.size} tool step(s))."
                onStreamChunk(finalText)
                Result.success(AgentExecutionResult(finalText, selectedModel, traces, groundingSources))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini request timed out", e)
            Result.failure(Exception("Aqua AI timed out after 60 seconds. Check network and retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini execution failed on $selectedModel", e)
            Result.failure(e)
        }
    }

    private fun selectOptimalModel(
        prompt: String,
        attachment: ImportedAttachment?,
        settings: UserSettings
    ): String {
        if (attachment != null && (attachment.isImage || attachment.isVideo)) {
            return UserSettings.MODEL_ARGON
        }
        val lower = prompt.lowercase()
        if (settings.thinkingEnabled &&
            (lower.contains("analyze") || lower.contains("plan") || lower.contains("debug") ||
                lower.contains("compare") || lower.contains("architecture") || lower.length > 260)
        ) {
            return UserSettings.MODEL_CARBON
        }
        return settings.preferredModel.ifBlank { UserSettings.MODEL_HYDROGEN }
    }

    private fun shouldUseWebOrMapsGrounding(prompt: String, settings: UserSettings): Boolean {
        if (!settings.searchGroundingEnabled && !settings.mapsGroundingEnabled) return false
        val lower = prompt.lowercase()
        val isDeviceOrWalletIntent = listOf(
            "battery", "volume", "flashlight", "torch", "brightness", "alarm", "reminder",
            "call ", "sms", "message ", "launch ", "open app", "settings", "notification",
            "wallet", "balance", "usdt", "transfer", "send crypto", "bitcoin price", "eth price",
            "browser", "current page", "fill form", "remember"
        ).any { lower.contains(it) }
        if (isDeviceOrWalletIntent) return false

        val looksLikeSearchOrMaps = listOf(
            "weather", "news", "latest", "today", "who won", "near me", "nearby",
            "restaurant", "directions", "coffee shop", "hours", "address of", "current score"
        ).any { lower.contains(it) }
        return looksLikeSearchOrMaps
    }

    private fun buildUserContent(prompt: String, attachment: ImportedAttachment?): Content {
        return content(role = "user") {
            if (attachment != null) {
                val bytes = attachment.rawBytes
                if (attachment.isImage && bytes != null) {
                    val bmp: Bitmap? = fileImportRepository.decodeBitmap(bytes)
                    if (bmp != null) {
                        image(bmp)
                    } else {
                        inlineData(bytes, attachment.mimeType)
                    }
                } else if ((attachment.isVideo || attachment.isAudio || attachment.mimeType == "application/pdf") && bytes != null) {
                    inlineData(bytes, attachment.mimeType)
                } else {
                    text("[Imported File: ${attachment.displayName} (${attachment.mimeType})]\n${attachment.extractedTextPreview}\n\n")
                }
            }
            text(prompt.ifBlank { "Analyze the attached content and summarize key insights." })
        }
    }

    private suspend fun executeDirectRestFallback(
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        onStreamChunk: (String) -> Unit
    ): Result<AgentExecutionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val contents = JSONArray()
            history.filter { it.role == "user" || it.role == "model" }.takeLast(10).forEach { msg ->
                contents.put(JSONObject().apply {
                    put("role", msg.role)
                    put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                })
            }
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            })

            val bodyJson = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
                put("contents", contents)
            }.toString()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Direct Gemini API returned HTTP ${resp.code}.")
                }
                val respJson = JSONObject(resp.body?.string() ?: "{}")
                val text = respJson.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    .orEmpty()
                if (text.isBlank()) throw IllegalStateException("Direct Gemini API returned an empty response.")
                onStreamChunk(text)
                AgentExecutionResult(text, modelName, emptyList(), emptyList())
            }
        }
    }

    private companion object {
        const val TAG = "GeminiProvider"
        const val MAX_AGENT_STEPS = 6
    }
}
