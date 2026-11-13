package com.example.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ContextManager
import com.example.ai.GeminiProvider
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.browser.BrowserPageState
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.DeviceActionLogEntity
import com.example.data.local.SecureConfigStore
import com.example.data.model.AgentStepTrace
import com.example.data.model.AquaCoreState
import com.example.data.model.AquaSkill
import com.example.data.model.BrowsingTab
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.ImportedAttachment
import com.example.data.model.MemoryItem
import com.example.data.model.PendingAuthorization
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.data.repository.AquaFirestoreRepository
import com.example.data.repository.DriveAuthOutcome
import com.example.data.repository.DriveBackupMetadata
import com.example.data.repository.FileImportRepository
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.device.AndroidDeviceController
import com.example.device.DeviceTelemetry
import com.example.skills.SkillParserAndValidator
import com.example.voice.AquaWakeWordService
import com.example.voice.VoiceAssistantManager
import com.example.wallet.FlashWalletConnector
import com.example.wallet.PreparedTransferRequest
import com.example.wallet.WalletBalanceSnapshot
import com.example.wallet.WalletIntegrationDiscovery
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class AquaMainViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    private val firestoreRepo: AquaFirestoreRepository,
    val secureConfigStore: SecureConfigStore,
    val fileImportRepository: FileImportRepository,
    val deviceController: AndroidDeviceController,
    val browserController: AquaBrowserController,
    val walletConnector: FlashWalletConnector,
    private val cacheDb: AquaCacheDatabase,
    val driveHistoryRepo: GoogleDriveHistoryRepository
) : ViewModel() {

    private val toolCallingEngine = ToolCallingEngine(
        deviceController = deviceController,
        browserController = browserController,
        walletConnector = walletConnector,
        firestoreRepo = firestoreRepo,
        driveHistoryRepo = driveHistoryRepo
    )

    private val geminiProvider = GeminiProvider(
        secureConfigStore = secureConfigStore,
        fileImportRepository = fileImportRepository,
        toolCallingEngine = toolCallingEngine
    )

    val voiceManager = VoiceAssistantManager(
        context = appContext,
        geminiProvider = geminiProvider,
        toolCallingEngine = toolCallingEngine
    )

    val settings: StateFlow<UserSettings> = firestoreRepo.observeSettings()
        .catch { handleFlowError("settings", it); emit(UserSettings(userId = currentUserId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserSettings(userId = currentUserId))

    val conversations: StateFlow<List<Conversation>> = firestoreRepo.observeConversations()
        .catch { handleFlowError("conversations", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId.isNullOrBlank()) flowOf(emptyList())
            else firestoreRepo.observeMessages(convId)
                .catch { handleFlowError("messages", it); emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val memories: StateFlow<List<MemoryItem>> = firestoreRepo.observeMemories()
        .catch { handleFlowError("memories", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val skills: StateFlow<List<AquaSkill>> = firestoreRepo.observeSkills()
        .catch { handleFlowError("skills", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val wallets: StateFlow<List<WalletConfig>> = firestoreRepo.observeWalletConfigs()
        .catch { handleFlowError("wallets", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val browserTabs: StateFlow<List<BrowsingTab>> = firestoreRepo.observeBrowserTabs()
        .catch { handleFlowError("browser_tabs", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val deviceActionLogs: StateFlow<List<DeviceActionLogEntity>> =
        cacheDb.deviceActionLogDao().observeLogsForUser(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val browserPageState: StateFlow<BrowserPageState> = browserController.pageState
    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val isLiveSessionActive: StateFlow<Boolean> = voiceManager.isLiveSessionActive
    val partialTranscript: StateFlow<String> = voiceManager.partialTranscript
    val waveformBars: StateFlow<FloatArray> = voiceManager.waveformBars
    val wakeWordRunning: StateFlow<Boolean> = AquaWakeWordService.isRunning

    private val _coreState = MutableStateFlow(AquaCoreState.IDLE)
    val coreState: StateFlow<AquaCoreState> = _coreState.asStateFlow()

    private val _telemetry = MutableStateFlow(deviceController.getTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _agentTraces = MutableStateFlow<List<AgentStepTrace>>(emptyList())
    val agentTraces: StateFlow<List<AgentStepTrace>> = _agentTraces.asStateFlow()

    private val _pendingAttachment = MutableStateFlow<ImportedAttachment?>(null)
    val pendingAttachment: StateFlow<ImportedAttachment?> = _pendingAttachment.asStateFlow()

    private val _attachmentAuthorizedForAi = MutableStateFlow(false)

    private val _pendingAuthorization = MutableStateFlow<PendingAuthorization?>(null)
    val pendingAuthorization: StateFlow<PendingAuthorization?> = _pendingAuthorization.asStateFlow()
    private var authDeferred: CompletableDeferred<Boolean>? = null

    private val _liveStatus = MutableStateFlow("Ready for Gemini Live Voice")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()

    private val _walletDiscovery = MutableStateFlow<WalletIntegrationDiscovery?>(null)
    val walletDiscovery: StateFlow<WalletIntegrationDiscovery?> = _walletDiscovery.asStateFlow()

    private val _walletBalance = MutableStateFlow<WalletBalanceSnapshot?>(null)
    val walletBalance: StateFlow<WalletBalanceSnapshot?> = _walletBalance.asStateFlow()

    private val _walletBusy = MutableStateFlow(false)
    val walletBusy: StateFlow<Boolean> = _walletBusy.asStateFlow()

    private val _preparedTransfer = MutableStateFlow<PreparedTransferRequest?>(null)
    val preparedTransfer: StateFlow<PreparedTransferRequest?> = _preparedTransfer.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _navigateToTabEvent = MutableStateFlow<String?>(null)
    val navigateToTabEvent: StateFlow<String?> = _navigateToTabEvent.asStateFlow()

    private val _driveBusy = MutableStateFlow(false)
    val driveBusy: StateFlow<Boolean> = _driveBusy.asStateFlow()

    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    private val _driveBackups = MutableStateFlow<List<DriveBackupMetadata>>(emptyList())
    val driveBackups: StateFlow<List<DriveBackupMetadata>> = _driveBackups.asStateFlow()

    private val _driveStatusLabel = MutableStateFlow<String?>(null)
    val driveStatusLabel: StateFlow<String?> = _driveStatusLabel.asStateFlow()

    private val _driveAuthPendingIntent = MutableStateFlow<PendingIntent?>(null)
    val driveAuthPendingIntent: StateFlow<PendingIntent?> = _driveAuthPendingIntent.asStateFlow()

    private var pendingDriveAction: (suspend (String) -> Unit)? = null

    init {
        viewModelScope.launch {
            voiceManager.isListening.collect { listening ->
                if (listening) _coreState.value = AquaCoreState.LISTENING
                else if (_coreState.value == AquaCoreState.LISTENING) _coreState.value = AquaCoreState.IDLE
            }
        }
        viewModelScope.launch {
            voiceManager.isSpeaking.collect { speaking ->
                if (speaking) _coreState.value = AquaCoreState.SPEAKING
                else if (_coreState.value == AquaCoreState.SPEAKING) _coreState.value = AquaCoreState.IDLE
            }
        }
        viewModelScope.launch {
            AquaWakeWordService.wakeEvents.collect { cmd ->
                if (cmd.isNotBlank()) {
                    sendMessage(cmd, speakReply = true)
                } else {
                    startVoiceCapture(autoSendAndSpeak = true)
                }
            }
        }
        viewModelScope.launch {
            val silentToken = driveHistoryRepo.tryGetSilentAccessToken()
            if (!silentToken.isNullOrBlank()) {
                _driveConnected.value = true
                driveHistoryRepo.listBackups(silentToken)
                    .onSuccess { _driveBackups.value = it }
            }
        }
    }

    fun refreshTelemetry() {
        _telemetry.value = deviceController.getTelemetry()
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun consumeNavigationEvent() {
        _navigateToTabEvent.value = null
    }

    fun selectConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
    }

    fun startNewConversation() {
        _activeConversationId.value = null
        _streamingReply.value = ""
        _agentTraces.value = emptyList()
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                _activeConversationId.value = null
            }
            firestoreRepo.deleteConversation(conversationId)
                .onFailure { _bannerMessage.value = "Delete failed: ${it.localizedMessage}" }
        }
    }

    fun resolvePendingAuthorization(approved: Boolean) {
        _pendingAuthorization.value = null
        authDeferred?.complete(approved)
        authDeferred = null
    }

    private suspend fun requestUserAuthorization(auth: PendingAuthorization): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        authDeferred = deferred
        _pendingAuthorization.value = auth
        return deferred.await()
    }

    // --- CHAT & AUTONOMOUS AGENT ---

    fun sendMessage(promptText: String, speakReply: Boolean = false) {
        val trimmed = promptText.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return

        viewModelScope.launch {
            if (attachment != null && !_attachmentAuthorizedForAi.value) {
                val approved = requestUserAuthorization(
                    PendingAuthorization(
                        id = "file_upload_${System.currentTimeMillis()}",
                        actionTitle = "Authorize File Upload to Gemini AI",
                        actionType = "External AI File Processing",
                        description = "Confirm sending the imported file contents to Gemini AI for analysis.",
                        parametersSummary = listOf(
                            "File Name" to attachment.displayName,
                            "MIME Type" to attachment.mimeType,
                            "Size" to "${attachment.sizeBytes} bytes"
                        ),
                        isFinancialOrSecurity = false,
                        onApprove = { "Authorized file upload" },
                        onDeny = { "Cancelled file upload" }
                    )
                )
                if (!approved) {
                    _bannerMessage.value = "File upload cancelled. Attachment remains local."
                    return@launch
                }
                _attachmentAuthorizedForAi.value = true
            }

            voiceManager.stopSpeaking()
            refreshTelemetry()
            val currentSettings = settings.value
            val convId = _activeConversationId.value ?: run {
                val title = trimmed.ifBlank { attachment?.displayName ?: "Aqua Session" }.take(60)
                val created = firestoreRepo.createConversation(title, currentSettings.preferredModel)
                if (created.isFailure) {
                    _bannerMessage.value = "Could not create conversation: ${created.exceptionOrNull()?.localizedMessage}"
                    return@launch
                }
                val newId = created.getOrThrow()
                _activeConversationId.value = newId
                newId
            }

            // Persist user message
            firestoreRepo.addMessage(
                ChatMessage(
                    conversationId = convId,
                    role = "user",
                    content = trimmed.ifBlank { "Analyze attached file: ${attachment?.displayName}" },
                    modelUsed = currentSettings.preferredModel,
                    attachmentName = attachment?.displayName.orEmpty(),
                    attachmentMime = attachment?.mimeType.orEmpty()
                )
            )
            firestoreRepo.updateConversationMeta(
                conversationId = convId,
                incrementMessageCountBy = 1L
            )

            _pendingAttachment.value = null
            _attachmentAuthorizedForAi.value = false
            _coreState.value = AquaCoreState.THINKING
            _streamingReply.value = ""
            _agentTraces.value = emptyList()

            val sysInstruction = ContextManager.buildSystemInstruction(
                settings = currentSettings,
                memories = memories.value,
                enabledSkills = skills.value,
                activeWallet = wallets.value.firstOrNull(),
                telemetry = _telemetry.value,
                browserUrl = browserPageState.value.currentUrl,
                browserTitle = browserPageState.value.currentTitle
            )

            val result = geminiProvider.executeAgentTurn(
                userPrompt = trimmed,
                history = messages.value,
                attachment = attachment,
                settings = currentSettings,
                systemInstructionText = sysInstruction,
                activeWallet = wallets.value.firstOrNull(),
                onNavigateToBrowser = { _ -> _navigateToTabEvent.value = "browser" },
                requestAuthorization = { req -> requestUserAuthorization(req) },
                onStreamChunk = { partial -> _streamingReply.value = partial },
                onTrace = { trace ->
                    _coreState.value = AquaCoreState.EXECUTING_TOOL
                    _agentTraces.value = _agentTraces.value + trace
                    viewModelScope.launch(Dispatchers.IO) {
                        cacheDb.deviceActionLogDao().insertLog(
                            DeviceActionLogEntity(
                                userId = currentUserId,
                                toolName = trace.toolName,
                                status = trace.status,
                                detail = trace.detail
                            )
                        )
                    }
                }
            )

            _streamingReply.value = ""
            result.onSuccess { exec ->
                val tracesJson = JSONArray().apply {
                    exec.traces.forEach { t ->
                        put(JSONObject().apply {
                            put("step", t.stepName)
                            put("tool", t.toolName)
                            put("status", t.status)
                            put("detail", t.detail)
                        })
                    }
                }.toString()
                val sourcesJson = JSONArray(exec.groundingSources).toString()

                firestoreRepo.addMessage(
                    ChatMessage(
                        conversationId = convId,
                        role = "model",
                        content = exec.text,
                        modelUsed = exec.modelUsed,
                        toolActionsJson = if (exec.traces.isEmpty()) "" else tracesJson,
                        groundingSourcesJson = if (exec.groundingSources.isEmpty()) "" else sourcesJson
                    )
                )
                firestoreRepo.updateConversationMeta(
                    conversationId = convId,
                    summary = exec.text.take(240),
                    modelUsed = exec.modelUsed,
                    incrementMessageCountBy = 1L
                )
                _coreState.value = AquaCoreState.IDLE
                if (speakReply) {
                    voiceManager.speak(exec.text, currentSettings, viewModelScope)
                }
            }.onFailure { err ->
                _coreState.value = AquaCoreState.ERROR
                val errMsg = err.localizedMessage ?: "Aqua AI failed to respond."
                _bannerMessage.value = errMsg
                firestoreRepo.addMessage(
                    ChatMessage(
                        conversationId = convId,
                        role = "agent",
                        content = "Error: $errMsg",
                        modelUsed = currentSettings.preferredModel
                    )
                )
            }
        }
    }

    // --- VOICE & LIVE API ---

    fun startVoiceCapture(autoSendAndSpeak: Boolean = true) {
        voiceManager.startSpeechRecognition(settings.value.voiceLanguage) { spokenText ->
            if (autoSendAndSpeak) {
                sendMessage(spokenText, speakReply = true)
            }
        }
    }

    fun stopVoiceCapture() = voiceManager.stopListening()

    fun speakMessage(text: String) = voiceManager.speak(text, settings.value, viewModelScope)

    fun stopSpeaking() = voiceManager.stopSpeaking()

    fun toggleLiveVoiceSession() {
        if (voiceManager.isLiveSessionActive.value) {
            voiceManager.stopLiveConversation()
            _liveStatus.value = "Live voice session disconnected"
            return
        }
        refreshTelemetry()
        val currentSettings = settings.value
        val sysInstruction = ContextManager.buildSystemInstruction(
            settings = currentSettings,
            memories = memories.value,
            enabledSkills = skills.value,
            activeWallet = wallets.value.firstOrNull(),
            telemetry = _telemetry.value,
            browserUrl = browserPageState.value.currentUrl,
            browserTitle = browserPageState.value.currentTitle
        )
        voiceManager.startLiveConversation(
            scope = viewModelScope,
            systemInstruction = sysInstruction,
            settings = currentSettings,
            activeWallet = wallets.value.firstOrNull(),
            onNavigateToBrowser = { _navigateToTabEvent.value = "browser" },
            requestAuthorization = { req -> requestUserAuthorization(req) },
            onTrace = { trace ->
                _agentTraces.value = _agentTraces.value + trace
            },
            onStatusChanged = { status ->
                _liveStatus.value = status
            }
        )
    }

    fun setWakeWordServiceEnabled(enabled: Boolean) {
        updateSettings(settings.value.copy(wakeWordEnabled = enabled))
        val serviceIntent = Intent(appContext, AquaWakeWordService::class.java).apply {
            if (enabled) {
                putExtra(AquaWakeWordService.EXTRA_WAKE_PHRASE, settings.value.wakeWordPhrase)
            } else {
                action = AquaWakeWordService.ACTION_STOP
            }
        }
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } else {
            appContext.startService(serviceIntent)
        }
    }

    // --- FILE IMPORT ---

    fun importAttachmentUri(uri: Uri) {
        viewModelScope.launch {
            fileImportRepository.importUri(uri)
                .onSuccess { attachment ->
                    _pendingAttachment.value = attachment
                    _attachmentAuthorizedForAi.value = false
                    _bannerMessage.value = "Attached ${attachment.displayName}. Tap Send to authorize AI inspection."
                }
                .onFailure { err ->
                    _bannerMessage.value = "File import failed: ${err.localizedMessage}"
                }
        }
    }

    fun clearPendingAttachment() {
        _pendingAttachment.value = null
        _attachmentAuthorizedForAi.value = false
    }

    // --- SKILLS ENGINE ---

    fun importSkillFromUri(uri: Uri, sourceLabel: String = "Android File Manager") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val att = fileImportRepository.importUri(uri).getOrThrow()
                val bytes = att.rawBytes ?: throw IllegalStateException("No readable bytes in skill file.")
                val skill = SkillParserAndValidator.parseAndValidateFromBytes(
                    fileName = att.displayName,
                    bytes = bytes,
                    sourceLabel = sourceLabel
                ).getOrThrow()
                firestoreRepo.saveSkill(skill).getOrThrow()
                _bannerMessage.value = "Imported skill '${skill.name}' (${skill.securityStatus.uppercase()}). Review before enabling."
            } catch (e: Exception) {
                _bannerMessage.value = "Skill import failed: ${e.localizedMessage}"
            }
        }
    }

    fun saveSkill(skill: AquaSkill) {
        viewModelScope.launch {
            val validated = SkillParserAndValidator.validateSecurity(skill)
            firestoreRepo.saveSkill(validated)
                .onSuccess { _bannerMessage.value = "Saved skill '${validated.name}'." }
                .onFailure { _bannerMessage.value = "Failed to save skill: ${it.localizedMessage}" }
        }
    }

    fun toggleSkillEnabled(skill: AquaSkill) {
        if (skill.securityStatus == "blocked") {
            _bannerMessage.value = "Cannot enable a skill blocked by the security inspector."
            return
        }
        viewModelScope.launch {
            firestoreRepo.toggleSkillEnabled(skill.id, !skill.enabled)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun deleteSkill(skillId: String) {
        viewModelScope.launch {
            firestoreRepo.deleteSkill(skillId)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    // --- BROWSER ---

    fun navigateBrowser(urlOrQuery: String) {
        val resolved = browserController.navigateTo(urlOrQuery)
        viewModelScope.launch {
            firestoreRepo.saveBrowserTab(
                BrowsingTab(
                    title = resolved.removePrefix("https://").take(60),
                    url = resolved,
                    isActive = true
                )
            )
        }
    }

    fun summarizeActiveWebpage() {
        viewModelScope.launch {
            browserController.extractCurrentPageText()
                .onSuccess { pageText ->
                    _navigateToTabEvent.value = "home"
                    sendMessage("Summarize and extract key facts from this webpage:\n\n${pageText.take(6500)}")
                }
                .onFailure { err ->
                    _bannerMessage.value = "Could not extract webpage: ${err.localizedMessage}"
                }
        }
    }

    fun deleteBrowserTab(tabId: String) {
        viewModelScope.launch { firestoreRepo.deleteBrowserTab(tabId) }
    }

    // --- FLASH WALLET CONNECTOR ---

    fun saveAndDiscoverWallet(config: WalletConfig) {
        viewModelScope.launch {
            val discovery = walletConnector.discoverIntegration(config)
            _walletDiscovery.value = discovery
            val resolvedStatus = when {
                config.publicAddress.isNotBlank() && config.rpcEndpoint.isNotBlank() -> "read_only"
                discovery.packageInstalled && discovery.supportsWalletConnectScheme -> "connected"
                config.packageName.isBlank() && config.publicAddress.isBlank() -> "disconnected"
                else -> "unsupported"
            }
            val updated = config.copy(connectionStatus = resolvedStatus)
            firestoreRepo.saveWalletConfig(updated)
                .onFailure { _bannerMessage.value = "Failed to save wallet profile: ${it.localizedMessage}" }
        }
    }

    fun refreshWalletBalances(config: WalletConfig) {
        viewModelScope.launch {
            _walletBusy.value = true
            _walletDiscovery.value = walletConnector.discoverIntegration(config)
            walletConnector.fetchLiveReadOnlySnapshot(config)
                .onSuccess { snap -> _walletBalance.value = snap }
                .onFailure { err -> _bannerMessage.value = "Blockchain query error: ${err.localizedMessage}" }
            _walletBusy.value = false
        }
    }

    fun prepareWalletTransfer(
        config: WalletConfig,
        destination: String,
        asset: String,
        amount: String
    ) {
        walletConnector.prepareTransferRequest(config, destination, asset, amount)
            .onSuccess { _preparedTransfer.value = it }
            .onFailure { _bannerMessage.value = it.localizedMessage }
    }

    fun authorizeAndLaunchPreparedTransfer(config: WalletConfig) {
        val prep = _preparedTransfer.value ?: return
        viewModelScope.launch {
            val approved = requestUserAuthorization(
                PendingAuthorization(
                    id = "tx_${System.currentTimeMillis()}",
                    actionTitle = "Confirm Wallet Signing Handoff",
                    actionType = "Cryptocurrency Transfer Authorization",
                    description = "Aqua will open your external wallet's official signing handler. Transaction success is never assumed until confirmed on-chain.",
                    parametersSummary = listOf(
                        "Destination" to prep.destinationAddress,
                        "Network" to prep.networkName,
                        "Asset & Amount" to "${prep.amount} ${prep.assetSymbol}",
                        "Fee" to prep.estimatedFeeDisplay
                    ),
                    isFinancialOrSecurity = true,
                    onApprove = {
                        walletConnector.launchSigningIntent(prep.eip681Uri, config.packageName).getOrThrow()
                    },
                    onDeny = { "Cancelled wallet transfer." }
                )
            )
            if (approved) {
                walletConnector.launchSigningIntent(prep.eip681Uri, config.packageName)
                    .onSuccess { _bannerMessage.value = it; _preparedTransfer.value = null }
                    .onFailure { _bannerMessage.value = it.localizedMessage }
            }
        }
    }

    fun disconnectWallet(wallet: WalletConfig) {
        viewModelScope.launch {
            _walletBalance.value = null
            _preparedTransfer.value = null
            _walletDiscovery.value = null
            firestoreRepo.deleteWalletConfig(wallet.id)
                .onSuccess { _bannerMessage.value = "Wallet disconnected and session cleared." }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    // --- MEMORIES & SETTINGS ---

    fun saveMemory(memory: MemoryItem) {
        viewModelScope.launch {
            firestoreRepo.saveMemory(memory)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            firestoreRepo.deleteMemory(memoryId)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun updateSettings(newSettings: UserSettings) {
        viewModelScope.launch {
            firestoreRepo.saveSettings(newSettings)
                .onFailure { _bannerMessage.value = "Settings sync error: ${it.localizedMessage}" }
        }
    }

    fun setWindowBrightness(activity: Activity?, percent: Int) {
        deviceController.setAppWindowBrightness(activity, percent)
            .onSuccess { _bannerMessage.value = it }
            .onFailure { _bannerMessage.value = it.localizedMessage }
    }

    // --- GOOGLE DRIVE HISTORY STORAGE ---

    fun consumeDriveAuthPendingIntent() {
        _driveAuthPendingIntent.value = null
    }

    fun handleDriveConsentResult(intent: Intent?) {
        val actionToRun = pendingDriveAction
        pendingDriveAction = null
        driveHistoryRepo.extractTokenFromIntent(intent)
            .onSuccess { token ->
                _driveConnected.value = true
                if (actionToRun != null) {
                    viewModelScope.launch {
                        _driveBusy.value = true
                        try {
                            actionToRun(token)
                        } finally {
                            _driveBusy.value = false
                        }
                    }
                } else {
                    refreshDriveBackupsWithToken(token)
                }
            }
            .onFailure { err ->
                _driveBusy.value = false
                _bannerMessage.value = err.localizedMessage ?: "Google Drive consent cancelled."
            }
    }

    private fun runWithDriveToken(
        activity: Activity?,
        block: suspend (String) -> Unit
    ) {
        viewModelScope.launch {
            _driveBusy.value = true
            when (val auth = driveHistoryRepo.authorizeDriveAccess(activity)) {
                is DriveAuthOutcome.Authorized -> {
                    _driveConnected.value = true
                    try {
                        block(auth.accessToken)
                    } finally {
                        _driveBusy.value = false
                    }
                }
                is DriveAuthOutcome.NeedsResolution -> {
                    pendingDriveAction = block
                    _driveBusy.value = false
                    _driveAuthPendingIntent.value = auth.pendingIntent
                }
                is DriveAuthOutcome.Error -> {
                    _driveBusy.value = false
                    _bannerMessage.value = auth.message
                }
            }
        }
    }

    fun refreshDriveBackups(activity: Activity?) {
        runWithDriveToken(activity) { token ->
            refreshDriveBackupsWithToken(token)
        }
    }

    private fun refreshDriveBackupsWithToken(token: String) {
        viewModelScope.launch {
            _driveBusy.value = true
            driveHistoryRepo.listBackups(token)
                .onSuccess { list ->
                    _driveBackups.value = list
                    _driveStatusLabel.value = if (list.isEmpty()) {
                        "Connected • No backups stored yet"
                    } else {
                        "Connected • ${list.size} backup file(s) found"
                    }
                }
                .onFailure { err ->
                    _bannerMessage.value = err.localizedMessage ?: "Failed to list Google Drive backups."
                }
            _driveBusy.value = false
        }
    }

    fun backupHistoryToGoogleDrive(activity: Activity?, exportVisibleCopy: Boolean = false) {
        runWithDriveToken(activity) { token ->
            val convsWithMsgs = firestoreRepo.fetchAllConversationsWithMessagesOnce().getOrElse {
                _bannerMessage.value = "Could not load conversations: ${it.localizedMessage}"
                return@runWithDriveToken
            }
            val memories = firestoreRepo.fetchMemoriesOnce().getOrElse { emptyList() }
            if (convsWithMsgs.isEmpty() && memories.isEmpty()) {
                _bannerMessage.value = "No conversations or memories to back up yet."
                return@runWithDriveToken
            }

            val snapshot = GoogleDriveHistoryRepository.buildSnapshot(
                userId = currentUserId,
                conversationsWithMessages = convsWithMsgs,
                memories = memories
            )
            val uploadRes = if (exportVisibleCopy) {
                driveHistoryRepo.exportVisibleHistoryFile(token, snapshot)
            } else {
                driveHistoryRepo.backupHistoryToAppData(token, snapshot)
            }
            uploadRes
                .onSuccess { meta ->
                    _driveStatusLabel.value = "Synced ${snapshot.conversations.size} chats & ${snapshot.memories.size} memories (${meta.name})"
                    _bannerMessage.value = if (exportVisibleCopy) {
                        "Exported ${meta.name} to Google Drive."
                    } else {
                        "History backed up safely to Google Drive AppData."
                    }
                    driveHistoryRepo.listBackups(token).onSuccess { _driveBackups.value = it }
                }
                .onFailure { err ->
                    _bannerMessage.value = err.localizedMessage ?: "Google Drive backup failed."
                }
        }
    }

    fun restoreHistoryFromGoogleDrive(activity: Activity?, fileId: String) {
        runWithDriveToken(activity) { token ->
            val snap = driveHistoryRepo.downloadAndParseSnapshot(token, fileId).getOrElse {
                _bannerMessage.value = it.localizedMessage ?: "Failed to download Drive backup."
                return@runWithDriveToken
            }
            firestoreRepo.importDriveSnapshot(snap)
                .onSuccess { (convsAdded, memoriesAdded) ->
                    _driveStatusLabel.value = "Restored $convsAdded new conversation(s) & $memoriesAdded memory item(s)"
                    _bannerMessage.value = "Restored $convsAdded conversation(s) and $memoriesAdded memory item(s) from Google Drive."
                }
                .onFailure { err ->
                    _bannerMessage.value = "Restore merge failed: ${err.localizedMessage}"
                }
        }
    }

    private fun handleFlowError(source: String, error: Throwable) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w("AquaMainVM", "Error observing $source", root)
        _bannerMessage.value = root.localizedMessage ?: "Failed to sync $source"
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
    }
}
