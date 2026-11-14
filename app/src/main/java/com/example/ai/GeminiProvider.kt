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
        val resolvedModel = normalizeSupportedModel(modelName)
        val useDeepThinking = resolvedModel == UserSettings.MODEL_ARGON && settings.thinkingEnabled
        val config = generationConfig {
            if (useDeepThinking) {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            }
        }

        val tools = mutableListOf<Tool>()
        if (enableFunctionTools) {
            tools.add(toolCallingEngine.buildToolDeclarations())
        }
        if (enableSearchGrounding && !enableFunctionTools && settings.fullInternetAccessEnabled) {
            tools.add(Tool.googleSearch())
        }
        if (enableMapsGrounding && !enableFunctionTools && settings.fullInternetAccessEnabled) {
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
            modelName = resolvedModel,
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
                enableSearchGrounding = settings.fullInternetAccessEnabled && settings.searchGroundingEnabled,
                onStreamChunk = onStreamChunk
            )
        }

        return try {
            executeWithFirebaseAi(
                modelToUse = selectedModel,
                userPrompt = userPrompt,
                history = history,
                attachment = attachment,
                settings = settings,
                systemInstructionText = systemInstructionText,
                activeWallet = activeWallet,
                userLatLng = userLatLng,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini request timed out on $selectedModel, attempting fast fallback", e)
            if (selectedModel != UserSettings.MODEL_FLASH_LITE) {
                try {
                    return executeWithFirebaseAi(
                        modelToUse = UserSettings.MODEL_FLASH_LITE,
                        userPrompt = userPrompt,
                        history = history,
                        attachment = attachment,
                        settings = settings.copy(thinkingEnabled = false),
                        systemInstructionText = systemInstructionText,
                        activeWallet = activeWallet,
                        userLatLng = userLatLng,
                        traces = traces,
                        groundingSources = groundingSources,
                        onNavigateToBrowser = onNavigateToBrowser,
                        requestAuthorization = requestAuthorization,
                        onStreamChunk = onStreamChunk,
                        onTrace = onTrace
                    )
                } catch (fallbackErr: CancellationException) {
                    if (fallbackErr is TimeoutCancellationException) {
                        Log.e(TAG, "Gemini fallback request timed out", fallbackErr)
                        return Result.failure(Exception("Aqua AI timed out after 60 seconds. Check network connectivity and retry."))
                    }
                    throw fallbackErr
                } catch (fallbackErr: Exception) {
                    Log.e(TAG, "Gemini fallback failed", fallbackErr)
                    return Result.failure(fallbackErr)
                }
            }
            Log.e(TAG, "Gemini request timed out", e)
            Result.failure(Exception("Aqua AI timed out after 60 seconds. Check network and retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini execution failed on $selectedModel", e)
            Result.failure(e)
        }
    }

    private suspend fun executeWithFirebaseAi(
        modelToUse: String,
        userPrompt: String,
        history: List<ChatMessage>,
        attachment: ImportedAttachment?,
        settings: UserSettings,
        systemInstructionText: String,
        activeWallet: WalletConfig?,
        userLatLng: Pair<Double, Double>?,
        traces: MutableList<AgentStepTrace>,
        groundingSources: MutableList<String>,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit
    ): Result<AgentExecutionResult> {
        val useGroundingInsteadOfTools = shouldUseWebOrMapsGrounding(userPrompt, settings)
        val model = buildModel(
            modelName = modelToUse,
            systemInstructionText = systemInstructionText,
            settings = settings,
            enableFunctionTools = !useGroundingInsteadOfTools && attachment == null,
            enableSearchGrounding = settings.fullInternetAccessEnabled && settings.searchGroundingEnabled && useGroundingInsteadOfTools,
            enableMapsGrounding = settings.fullInternetAccessEnabled && settings.mapsGroundingEnabled && useGroundingInsteadOfTools,
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
            return Result.success(AgentExecutionResult(finalText, modelToUse, traces, groundingSources))
        }

        // Streamed autonomous multi-step function-calling loop (low TTFT, prevents unary 60s hang)
        val chat = model.startChat(history = historyContents)
        var nextTurnContent: Content = userContent
        val aggregatedText = StringBuilder()
        var loopCount = 0

        while (loopCount < MAX_AGENT_STEPS) {
            loopCount++
            val turnText = StringBuilder()
            val pendingCalls = mutableListOf<com.google.firebase.ai.type.FunctionCallPart>()

            withTimeout(60_000L) {
                chat.sendMessageStream(nextTurnContent).collect { chunk ->
                    val t = chunk.text.orEmpty()
                    if (t.isNotEmpty()) {
                        turnText.append(t)
                        val combined = if (aggregatedText.isEmpty()) {
                            turnText.toString()
                        } else {
                            "${aggregatedText}\n${turnText}"
                        }
                        onStreamChunk(combined)
                    }
                    if (chunk.functionCalls.isNotEmpty()) {
                        pendingCalls.addAll(chunk.functionCalls)
                    }
                }
            }

            if (turnText.isNotBlank()) {
                if (aggregatedText.isNotEmpty()) aggregatedText.append("\n")
                aggregatedText.append(turnText)
            }

            if (pendingCalls.isEmpty()) break

            val responseParts = mutableListOf<FunctionResponsePart>()
            for (fnCall in pendingCalls) {
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
            nextTurnContent = Content(role = "user", parts = responseParts)
        }

        val finalText = aggregatedText.toString().trim().ifBlank {
            "Task execution finished (${traces.size} tool step(s))."
        }
        onStreamChunk(finalText)
        return Result.success(AgentExecutionResult(finalText, modelToUse, traces, groundingSources))
    }

    private fun selectOptimalModel(
        prompt: String,
        attachment: ImportedAttachment?,
        settings: UserSettings
    ): String {
        val preferred = normalizeSupportedModel(settings.preferredModel)
        if (attachment != null && (attachment.isImage || attachment.isVideo)) {
            return UserSettings.MODEL_FLASH_38
        }
        if (settings.thinkingEnabled && preferred == UserSettings.MODEL_ARGON) {
            return UserSettings.MODEL_ARGON
        }
        return preferred
    }

    private fun normalizeSupportedModel(modelName: String): String {
        return when (modelName.trim()) {
            "", "gemini-4-hydrogen", "gemini-4-carbon" -> UserSettings.MODEL_FLASH_38
            "gemini-3.1-flash-lite" -> UserSettings.MODEL_FLASH_LITE
            UserSettings.MODEL_FLASH_38,
            UserSettings.MODEL_FLASH_LITE,
            UserSettings.MODEL_ARGON -> modelName.trim()
            else -> UserSettings.MODEL_FLASH_38
        }
    }

    private fun shouldUseWebOrMapsGrounding(prompt: String, settings: UserSettings): Boolean {
        if (!settings.fullInternetAccessEnabled) return false
        if (!settings.searchGroundingEnabled && !settings.mapsGroundingEnabled) return false
        val lower = prompt.lowercase()

        // Any explicit URL, REST API call, or action combining web data with device/wallet/workspace stays in Function Calling
        // where web_search, web_fetch_page, and web_http_request run alongside all Android/Workspace/Wallet tools.
        val requiresFunctionTools = listOf(
            "http://", "https://", ".com", ".org", ".io", ".ai", ".dev", "api.", "fetch ", "read page",
            "battery", "volume", "flashlight", "torch", "brightness", "alarm", "reminder",
            "call ", "sms", "text ", "launch ", "open app", "settings", "notification", "clipboard", "share ",
            "wallet", "crypto", "balance", "portfolio", "tx", "transaction", "transfer", "send ",
            "receive", "my address", "bitcoin", "btc", "ethereum", "eth", "solana", "sol", "bnb",
            "polygon", "pol", "matic", "arbitrum", "base", "usdt", "usdc", "token", "coin",
            "address book", "contact", "ens", ".eth", "gas", "gwei", "nonce", "block height",
            "allowance", "revoke", "approve", "switch network", "switch account", "switch wallet",
            "create wallet", "new wallet", "add account", "watch wallet", "watch-only", "custom token",
            "custom network", "add token", "add network", "sign message", "verify signature",
            "payment uri", "testnet", "display currency", "seed", "mnemonic",
            "browser", "current page", "fill form", "remember", "save note", "memory",
            "gmail", "email", "inbox", "calendar", "schedule", "event",
            "sheet", "spreadsheet", "google form", "form response", "google chat", "space", "drive", "backup"
        ).any { lower.contains(it) }
        if (requiresFunctionTools) return false

        val looksLikeSearchOrMaps = listOf(
            "weather", "news", "latest", "today", "yesterday", "this week", "current", "now",
            "who won", "score", "match", "price of", "stock", "market cap", "release date", "version of",
            "who is", "what happened", "search", "look up", "find out", "online", "internet", "web",
            "near me", "nearby", "restaurant", "directions", "coffee shop", "hours", "address of", "map"
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
        enableSearchGrounding: Boolean,
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
                if (enableSearchGrounding) {
                    put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                }
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
                val firstCand = respJson.optJSONArray("candidates")?.optJSONObject(0)
                val text = firstCand
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    .orEmpty()
                if (text.isBlank()) throw IllegalStateException("Direct Gemini API returned an empty response.")
                val sources = mutableListOf<String>()
                val chunks = firstCand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                if (chunks != null) {
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val title = web.optString("title", "")
                        val uri = web.optString("uri", "")
                        val label = listOf(title, uri).filter { it.isNotBlank() }.joinToString(" — ")
                        if (label.isNotBlank() && label !in sources) sources.add(label)
                    }
                }
                onStreamChunk(text)
                AgentExecutionResult(text, modelName, emptyList(), sources)
            }
        }
    }

    private companion object {
        const val TAG = "GeminiProvider"
        const val MAX_AGENT_STEPS = 6
    }
}
