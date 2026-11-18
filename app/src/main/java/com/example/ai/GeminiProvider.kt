package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.local.AiFreedomConfig
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
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LatLng
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
        userLatLng: Pair<Double, Double>? = null,
        promptHint: String = "",
        freedomConfig: AiFreedomConfig = AiFreedomConfig()
    ): GenerativeModel {
        val resolvedModel = normalizeSupportedModel(modelName)
        val useDeepThinking = resolvedModel == UserSettings.MODEL_ARGON && settings.thinkingEnabled
        val config = generationConfig {
            temperature = freedomConfig.temperature.coerceIn(0f, 2f)
            topP = freedomConfig.topP.coerceIn(0.1f, 1f)
            maxOutputTokens = freedomConfig.maxOutputTokens.coerceIn(512, 8192)
            if (useDeepThinking) {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            }
        }

        val tools = mutableListOf<Tool>()
        if (enableFunctionTools) {
            tools.add(
                toolCallingEngine.buildToolDeclarations(
                    settings = settings,
                    promptHint = promptHint,
                    unlockAllTools = freedomConfig.enableAllToolsAlways
                )
            )
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
        freedomConfig: AiFreedomConfig = AiFreedomConfig(),
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit
    ): Result<AgentExecutionResult> {
        val selectedModel = selectOptimalModel(userPrompt, attachment, settings, freedomConfig)
        val promptHint = (history.takeLast(2).map { it.content } + userPrompt).joinToString(" ")
        val traces = mutableListOf<AgentStepTrace>()
        val groundingSources = mutableListOf<String>()

        // Personal Google AI Studio API Key path (with full multimodal + multi-step function calling)
        val customKey = secureConfigStore.getGeminiApiKey()
        if (settings.useCustomDirectApiKey && !customKey.isNullOrBlank()) {
            val directModel = freedomConfig.customModelOverride.trim().ifBlank { selectedModel }
            val directPrimary = executeDirectRestAgentTurn(
                apiKey = customKey,
                modelName = directModel,
                systemInstruction = systemInstructionText,
                history = history,
                userPrompt = userPrompt,
                attachment = attachment,
                settings = settings,
                activeWallet = activeWallet,
                promptHint = promptHint,
                freedomConfig = freedomConfig,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace
            )
            if (directPrimary.isSuccess) return directPrimary

            val primaryErr = directPrimary.exceptionOrNull()
            Log.w(TAG, "Direct Google AI Studio REST failed on $directModel: ${primaryErr?.message}")
            if (primaryErr != null && isRateLimitOrQuotaError(primaryErr)) {
                delay(900L)
                val retryModel = if (directModel != UserSettings.MODEL_FLASH_LITE) UserSettings.MODEL_FLASH_LITE else directModel
                traces.clear()
                groundingSources.clear()
                val directRetry = executeDirectRestAgentTurn(
                    apiKey = customKey,
                    modelName = retryModel,
                    systemInstruction = systemInstructionText,
                    history = history,
                    userPrompt = userPrompt,
                    attachment = attachment,
                    settings = settings,
                    activeWallet = activeWallet,
                    promptHint = promptHint,
                    freedomConfig = freedomConfig,
                    traces = traces,
                    groundingSources = groundingSources,
                    onNavigateToBrowser = onNavigateToBrowser,
                    requestAuthorization = requestAuthorization,
                    onStreamChunk = onStreamChunk,
                    onTrace = onTrace
                )
                if (directRetry.isSuccess) return directRetry
                Log.w(TAG, "Direct REST retry failed; falling back to Firebase AI Logic")
            }
            traces.clear()
            groundingSources.clear()
            return executeFirebaseAiWithResilience(
                primaryModel = selectedModel,
                userPrompt = userPrompt,
                history = history,
                attachment = attachment,
                settings = settings,
                systemInstructionText = systemInstructionText,
                activeWallet = activeWallet,
                userLatLng = userLatLng,
                promptHint = promptHint,
                freedomConfig = freedomConfig,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace,
                priorDirectError = primaryErr
            )
        }

        return executeFirebaseAiWithResilience(
            primaryModel = selectedModel,
            userPrompt = userPrompt,
            history = history,
            attachment = attachment,
            settings = settings,
            systemInstructionText = systemInstructionText,
            activeWallet = activeWallet,
            userLatLng = userLatLng,
            promptHint = promptHint,
            freedomConfig = freedomConfig,
            traces = traces,
            groundingSources = groundingSources,
            onNavigateToBrowser = onNavigateToBrowser,
            requestAuthorization = requestAuthorization,
            onStreamChunk = onStreamChunk,
            onTrace = onTrace,
            priorDirectError = null
        )
    }

    private suspend fun executeFirebaseAiWithResilience(
        primaryModel: String,
        userPrompt: String,
        history: List<ChatMessage>,
        attachment: ImportedAttachment?,
        settings: UserSettings,
        systemInstructionText: String,
        activeWallet: WalletConfig?,
        userLatLng: Pair<Double, Double>?,
        promptHint: String,
        freedomConfig: AiFreedomConfig,
        traces: MutableList<AgentStepTrace>,
        groundingSources: MutableList<String>,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit,
        priorDirectError: Throwable?
    ): Result<AgentExecutionResult> {
        return try {
            executeWithFirebaseAi(
                modelToUse = primaryModel,
                userPrompt = userPrompt,
                history = history,
                attachment = attachment,
                settings = settings,
                systemInstructionText = systemInstructionText,
                activeWallet = activeWallet,
                userLatLng = userLatLng,
                promptHint = promptHint,
                freedomConfig = freedomConfig,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini request timed out on $primaryModel, cascading to Flash-Lite", e)
            cascadeToFlashLite(
                failedModel = primaryModel,
                waitBeforeRetryMs = 300L,
                userPrompt = userPrompt,
                history = history,
                attachment = attachment,
                settings = settings,
                systemInstructionText = systemInstructionText,
                activeWallet = activeWallet,
                userLatLng = userLatLng,
                promptHint = promptHint,
                freedomConfig = freedomConfig,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace,
                rootCause = e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isRateLimitOrQuotaError(e)) {
                Log.w(TAG, "Gemini quota/rate-limit on $primaryModel, backing off and cascading to Flash-Lite", e)
                return cascadeToFlashLite(
                    failedModel = primaryModel,
                    waitBeforeRetryMs = if (primaryModel == UserSettings.MODEL_FLASH_LITE) 1500L else 900L,
                    userPrompt = userPrompt,
                    history = history,
                    attachment = attachment,
                    settings = settings,
                    systemInstructionText = systemInstructionText,
                    activeWallet = activeWallet,
                    userLatLng = userLatLng,
                    promptHint = promptHint,
                    freedomConfig = freedomConfig,
                    traces = traces,
                    groundingSources = groundingSources,
                    onNavigateToBrowser = onNavigateToBrowser,
                    requestAuthorization = requestAuthorization,
                    onStreamChunk = onStreamChunk,
                    onTrace = onTrace,
                    rootCause = e
                )
            }
            Log.e(TAG, "Gemini execution failed on $primaryModel", e)
            Result.failure(priorDirectError?.takeIf { e.message.isNullOrBlank() } ?: e)
        }
    }

    private suspend fun cascadeToFlashLite(
        failedModel: String,
        waitBeforeRetryMs: Long,
        userPrompt: String,
        history: List<ChatMessage>,
        attachment: ImportedAttachment?,
        settings: UserSettings,
        systemInstructionText: String,
        activeWallet: WalletConfig?,
        userLatLng: Pair<Double, Double>?,
        promptHint: String,
        freedomConfig: AiFreedomConfig,
        traces: MutableList<AgentStepTrace>,
        groundingSources: MutableList<String>,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit,
        rootCause: Exception
    ): Result<AgentExecutionResult> {
        delay(waitBeforeRetryMs)
        val fallbackModel = UserSettings.MODEL_FLASH_LITE
        traces.clear()
        groundingSources.clear()
        return try {
            executeWithFirebaseAi(
                modelToUse = fallbackModel,
                userPrompt = userPrompt,
                history = history,
                attachment = attachment,
                settings = settings.copy(thinkingEnabled = false),
                systemInstructionText = systemInstructionText,
                activeWallet = activeWallet,
                userLatLng = userLatLng,
                promptHint = promptHint,
                freedomConfig = freedomConfig,
                traces = traces,
                groundingSources = groundingSources,
                onNavigateToBrowser = onNavigateToBrowser,
                requestAuthorization = requestAuthorization,
                onStreamChunk = onStreamChunk,
                onTrace = onTrace
            )
        } catch (fallbackErr: TimeoutCancellationException) {
            Log.e(TAG, "Gemini fallback timed out on $fallbackModel", fallbackErr)
            Result.failure(Exception("Aqua AI timed out after 60 seconds. Check network connectivity and retry."))
        } catch (fallbackErr: CancellationException) {
            throw fallbackErr
        } catch (fallbackErr: Exception) {
            Log.e(TAG, "Gemini fallback failed on $fallbackModel (after $failedModel)", fallbackErr)
            if (isRateLimitOrQuotaError(fallbackErr) || isRateLimitOrQuotaError(rootCause)) {
                Result.failure(
                    IllegalStateException(
                        "Gemini rate limit reached (HTTP 429) on both $failedModel and $fallbackModel. Please wait 30–60 seconds before retrying, or verify your Google AI Studio API key in Settings."
                    )
                )
            } else {
                Result.failure(fallbackErr)
            }
        }
    }

    private fun isRateLimitOrQuotaError(t: Throwable): Boolean {
        if (t is QuotaExceededException) return true
        val msg = listOfNotNull(t.message, t.cause?.message).joinToString(" ").lowercase()
        return msg.contains("429") ||
            msg.contains("quota") ||
            msg.contains("rate limit") ||
            msg.contains("rate-limit") ||
            msg.contains("resource_exhausted") ||
            msg.contains("too many requests")
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
        promptHint: String,
        freedomConfig: AiFreedomConfig,
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
            userLatLng = userLatLng,
            promptHint = promptHint,
            freedomConfig = freedomConfig
        )

        val historyContents = history
            .filter { it.role == "user" || it.role == "model" }
            .takeLast(12)
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

        val maxSteps = freedomConfig.maxAgentSteps.coerceIn(2, 15)
        val chat = model.startChat(history = historyContents)
        var nextTurnContent: Content = userContent
        val aggregatedText = StringBuilder()
        var loopCount = 0

        while (loopCount < maxSteps) {
            loopCount++
            val turnText = StringBuilder()
            val pendingCalls = mutableListOf<FunctionCallPart>()

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
        settings: UserSettings,
        freedomConfig: AiFreedomConfig = AiFreedomConfig()
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

        // Any explicit URL, REST API call, or action combining web data with device/wallet/drive/sdk stays in Function Calling
        // where web_search, web_fetch_page, and web_http_request run alongside all Android, Drive Vault, SDK, and Wallet tools.
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
            "drive", "backup", "quota", "sdk", "compileSdk", "targetSdk", "sha-1", "sha-256", "fingerprint"
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

    private suspend fun executeDirectRestAgentTurn(
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        attachment: ImportedAttachment?,
        settings: UserSettings,
        activeWallet: WalletConfig?,
        promptHint: String,
        freedomConfig: AiFreedomConfig,
        traces: MutableList<AgentStepTrace>,
        groundingSources: MutableList<String>,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onStreamChunk: (String) -> Unit,
        onTrace: (AgentStepTrace) -> Unit
    ): Result<AgentExecutionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val useGroundingInsteadOfTools = shouldUseWebOrMapsGrounding(userPrompt, settings)
            val contents = JSONArray()
            history.filter { it.role == "user" || it.role == "model" }.takeLast(12).forEach { msg ->
                contents.put(JSONObject().apply {
                    put("role", msg.role)
                    put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                })
            }

            val userParts = JSONArray()
            if (attachment != null) {
                val bytes = attachment.rawBytes
                if (bytes != null && (attachment.isImage || attachment.isVideo || attachment.isAudio || attachment.mimeType == "application/pdf")) {
                    userParts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().apply {
                                put("mime_type", attachment.mimeType)
                                put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            }
                        )
                    )
                } else {
                    userParts.put(
                        JSONObject().put(
                            "text",
                            "[Imported File: ${attachment.displayName} (${attachment.mimeType})]\n${attachment.extractedTextPreview}\n\n"
                        )
                    )
                }
            }
            userParts.put(JSONObject().put("text", userPrompt.ifBlank { "Analyze the attached content and summarize key insights." }))
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            })

            val toolsArray = JSONArray()
            if (useGroundingInsteadOfTools && settings.fullInternetAccessEnabled) {
                toolsArray.put(JSONObject().put("google_search", JSONObject()))
            } else if (attachment == null) {
                val fnDecls = toolCallingEngine.buildRestToolDeclarations(
                    settings = settings,
                    promptHint = promptHint,
                    unlockAllTools = freedomConfig.enableAllToolsAlways
                )
                if (fnDecls.length() > 0) {
                    toolsArray.put(JSONObject().put("functionDeclarations", fnDecls))
                }
            }

            val genConfig = JSONObject().apply {
                put("temperature", freedomConfig.temperature.coerceIn(0f, 2f).toDouble())
                put("topP", freedomConfig.topP.coerceIn(0.1f, 1f).toDouble())
                put("maxOutputTokens", freedomConfig.maxOutputTokens.coerceIn(512, 8192))
            }

            val maxSteps = freedomConfig.maxAgentSteps.coerceIn(2, 15)
            val aggregatedText = StringBuilder()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"

            var step = 0
            while (step < maxSteps) {
                step++
                val bodyJson = JSONObject().apply {
                    put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
                    put("contents", contents)
                    put("generationConfig", genConfig)
                    if (toolsArray.length() > 0) {
                        put("tools", toolsArray)
                    }
                }.toString()

                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val respJson = httpClient.newCall(req).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val serverMsg = runCatching {
                            JSONObject(rawBody).optJSONObject("error")?.optString("message", "")
                        }.getOrNull().orEmpty()
                        val detail = if (serverMsg.isNotBlank()) "HTTP ${resp.code}: $serverMsg" else "HTTP ${resp.code}"
                        throw IllegalStateException("Direct Google AI Studio API returned $detail.")
                    }
                    JSONObject(rawBody.ifBlank { "{}" })
                }

                val firstCand = respJson.optJSONArray("candidates")?.optJSONObject(0)
                val modelContent = firstCand?.optJSONObject("content")
                val parts = modelContent?.optJSONArray("parts") ?: JSONArray()

                val stepText = StringBuilder()
                val functionCalls = mutableListOf<Pair<String, JSONObject>>()

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val partText = part.optString("text", "")
                    if (partText.isNotEmpty()) {
                        stepText.append(partText)
                    }
                    val fc = part.optJSONObject("functionCall")
                    if (fc != null) {
                        val fnName = fc.optString("name", "")
                        val fnArgs = fc.optJSONObject("args") ?: JSONObject()
                        if (fnName.isNotBlank()) {
                            functionCalls.add(fnName to fnArgs)
                        }
                    }
                }

                val chunks = firstCand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                if (chunks != null) {
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val title = web.optString("title", "")
                        val uri = web.optString("uri", "")
                        val label = listOf(title, uri).filter { it.isNotBlank() }.joinToString(" — ")
                        if (label.isNotBlank() && label !in groundingSources) groundingSources.add(label)
                    }
                }

                if (stepText.isNotBlank()) {
                    if (aggregatedText.isNotEmpty()) aggregatedText.append("\n")
                    aggregatedText.append(stepText)
                    onStreamChunk(aggregatedText.toString())
                }

                if (functionCalls.isEmpty()) break

                if (modelContent != null) {
                    contents.put(modelContent)
                }

                val fnResponseParts = JSONArray()
                for ((fnName, fnArgsJson) in functionCalls) {
                    val kArgs = runCatching {
                        Json.parseToJsonElement(fnArgsJson.toString()).jsonObject
                    }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
                    val fnResult = toolCallingEngine.executeFunctionCall(
                        call = FunctionCallPart(fnName, kArgs),
                        settings = settings,
                        activeWallet = activeWallet,
                        onNavigateToBrowser = onNavigateToBrowser,
                        requestAuthorization = requestAuthorization,
                        onTrace = { trace ->
                            traces.add(trace)
                            onTrace(trace)
                        }
                    )
                    val responseObj = runCatching { JSONObject(fnResult.toString()) }
                        .getOrDefault(JSONObject().put("message", fnResult.toString()))
                    fnResponseParts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().apply {
                                put("name", fnName)
                                put("response", responseObj)
                            }
                        )
                    )
                }
                contents.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", fnResponseParts)
                    }
                )
            }

            val finalText = aggregatedText.toString().trim().ifBlank {
                if (traces.isNotEmpty()) "Task execution finished (${traces.size} tool step(s))."
                else throw IllegalStateException("Direct Google AI Studio API returned an empty response.")
            }
            onStreamChunk(finalText)
            AgentExecutionResult(finalText, modelName, traces.toList(), groundingSources.toList())
        }
    }

    private companion object {
        const val TAG = "GeminiProvider"
    }
}
