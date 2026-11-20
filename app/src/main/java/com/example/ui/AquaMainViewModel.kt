package com.example.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ContextManager
import com.example.ai.ConversationMarkdownExporter
import com.example.ai.DiagnosticReportExporter
import com.example.ai.GeminiProvider
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.browser.BrowserLink
import com.example.browser.BrowserPageState
import com.example.browser.BrowserTabSession
import com.example.core.agent.AgentOrchestrator
import com.example.core.agent.RiskTier
import com.example.core.agent.TaskState
import com.example.core.database.AgentTaskEntity
import com.example.core.database.AiUsageMeterEntity
import com.example.core.database.AuditLogEntity
import com.example.core.database.BookmarkEntity
import com.example.core.database.DriveFileMetadataEntity
import com.example.core.database.DriveSyncQueueEntity
import com.example.core.database.MemoryEntity
import com.example.core.database.MemoryTier
import com.example.core.database.WorkspaceDocumentEntity
import com.example.core.drive.ConflictResolutionStrategy
import com.example.core.memory.AquaMemoryEngine
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.core.voice.DetectedVoiceLanguage
import com.example.core.workspace.AquaWorkspaceEngine
import com.example.core.workspace.WorkspaceFolderType
import com.example.core.workspace.WorkspaceMode
import com.example.data.local.AiFreedomConfig
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.AquaLocalPreferences
import com.example.data.local.AquaPersona
import com.example.data.local.DeviceActionLogEntity
import com.example.data.local.ElevenLabsConfig
import com.example.data.local.SecureConfigStore
import com.example.data.local.TelemetrySample
import com.example.data.model.AgentStepTrace
import com.example.data.model.AquaCoreState
import com.example.data.model.AquaRoutine
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
import com.example.data.repository.DriveAboutInfo
import com.example.data.repository.DriveBackupMetadata
import com.example.data.repository.FileImportRepository
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.device.AndroidDeviceController
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import com.example.skills.SkillParserAndValidator
import com.example.ui.state.AquaSystemAndDriveCoordinator
import com.example.voice.AquaWakeWordService
import com.example.voice.ElevenLabsSubscription
import com.example.voice.ElevenLabsVoice
import com.example.voice.VoiceAssistantManager
import com.example.wallet.FlashWalletConnector
import com.example.wallet.PreparedTransferRequest
import com.example.wallet.WalletBalanceSnapshot
import com.example.wallet.WalletIntegrationDiscovery
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.domain.UserProfilePolicy
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.AquaWalletEngine
import com.example.wallet.security.WalletSecurityVault
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
    val driveHistoryRepo: GoogleDriveHistoryRepository,
    val walletEngine: AquaWalletEngine = AquaWalletEngine(
        vault = WalletSecurityVault(appContext),
        walletCacheDao = cacheDb.walletCacheDao()
    )
) : ViewModel() {

    val walletCoordinator = AquaWalletCoordinator(
        engine = walletEngine,
        firestoreRepo = firestoreRepo,
        scope = viewModelScope,
        onBanner = { msg -> _bannerMessage.value = msg }
    )

    val localPrefs = AquaLocalPreferences(appContext, currentUserId)
    val permissionPolicyStore = PermissionPolicyStore(appContext, currentUserId)
    val agentOrchestrator = AgentOrchestrator(
        agentTaskDao = cacheDb.agentTaskDao(),
        auditLogDao = cacheDb.auditLogDao(),
        permissionPolicyStore = permissionPolicyStore,
        userIdProvider = { currentUserId }
    )

    val systemAndDriveCoordinator = AquaSystemAndDriveCoordinator(
        appContext = appContext,
        currentUserId = currentUserId,
        firestoreRepo = firestoreRepo,
        secureConfigStore = secureConfigStore,
        driveHistoryRepo = driveHistoryRepo,
        scope = viewModelScope,
        onBanner = { msg -> _bannerMessage.value = msg },
        cacheDb = cacheDb,
        permissionPolicyStore = permissionPolicyStore
    )

    val memoryEngine = AquaMemoryEngine(
        memoryDao = cacheDb.memoryDao(),
        driveSyncCoordinator = systemAndDriveCoordinator.syncCoordinator,
        userIdProvider = { currentUserId }
    )

    val workspaceEngine = AquaWorkspaceEngine(
        workspaceDao = cacheDb.workspaceDocumentDao(),
        bookmarkDao = cacheDb.bookmarkDao(),
        driveSyncCoordinator = systemAndDriveCoordinator.syncCoordinator,
        userIdProvider = { currentUserId }
    )

    val personaId: StateFlow<String> = localPrefs.personaId
    val customDirective: StateFlow<String> = localPrefs.customDirective
    val pinnedRoutineIds: StateFlow<Set<String>> = localPrefs.pinnedRoutineIds
    val pinnedConversationIds: StateFlow<Set<String>> = localPrefs.pinnedConversationIds
    val conversationAliases: StateFlow<Map<String, String>> = localPrefs.conversationAliases
    val aiFreedomConfig: StateFlow<AiFreedomConfig> = localPrefs.aiFreedomConfig
    val elevenLabsConfig: StateFlow<ElevenLabsConfig> = localPrefs.elevenLabsConfig
    val hasCompletedAiSetup: StateFlow<Boolean> = localPrefs.hasCompletedAiSetup

    private val toolCallingEngine: ToolCallingEngine = ToolCallingEngine(
        deviceController = deviceController,
        browserController = browserController,
        walletConnector = walletConnector,
        firestoreRepo = firestoreRepo,
        driveHistoryRepo = driveHistoryRepo,
        aiWalletBridge = walletCoordinator.aiBridge,
        onWalletMutated = { viewModelScope.launch { walletCoordinator.reloadAll() } },
        freedomConfigProvider = { UserProfilePolicy.clampFreedomForAge(localPrefs.aiFreedomConfig.value, settings.value) },
        aiStatusProvider = { buildAiEngineStatusSummary() },
        onSpeakWithElevenLabs = { textToSpeak ->
            voiceManager.speak(
                text = textToSpeak,
                settings = settings.value,
                scope = viewModelScope,
                elevenLabsConfig = localPrefs.elevenLabsConfig.value
            )
            if (isElevenLabsActive()) {
                "Synthesizing speech via ElevenLabs Voice (${localPrefs.elevenLabsConfig.value.voiceName.ifBlank { secureConfigStore.getElevenLabsVoiceId().take(8) }})."
            } else {
                "ElevenLabs Voice ID/Key is not configured (optional); speaking via ${if (settings.value.useGeminiTts) "Gemini Native TTS" else "Android TTS"}."
            }
        },
        agentOrchestrator = agentOrchestrator,
        activeTaskIdProvider = { agentOrchestrator.activeTaskId.value },
        driveSyncCoordinator = systemAndDriveCoordinator.syncCoordinator,
        userIdProvider = { currentUserId }
    )

    private val geminiProvider: GeminiProvider = GeminiProvider(
        secureConfigStore = secureConfigStore,
        fileImportRepository = fileImportRepository,
        toolCallingEngine = toolCallingEngine,
        aiUsageMeterDao = cacheDb.aiUsageMeterDao(),
        userIdProvider = { currentUserId }
    )

    val voiceManager: VoiceAssistantManager = VoiceAssistantManager(
        context = appContext,
        geminiProvider = geminiProvider,
        toolCallingEngine = toolCallingEngine,
        secureConfigStore = secureConfigStore
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

    val routines: StateFlow<List<AquaRoutine>> = firestoreRepo.observeRoutines()
        .catch { handleFlowError("routines", it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val deviceActionLogs: StateFlow<List<DeviceActionLogEntity>> =
        cacheDb.deviceActionLogDao().observeLogsForUser(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val aiUsageLogs: StateFlow<List<AiUsageMeterEntity>> =
        cacheDb.aiUsageMeterDao().observeUsage(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val totalAiTokensUsed: StateFlow<Long> =
        cacheDb.aiUsageMeterDao().observeTotalTokens(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0L)

    val agentTasks: StateFlow<List<AgentTaskEntity>> =
        cacheDb.agentTaskDao().observeTasks(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val auditLogs: StateFlow<List<AuditLogEntity>> =
        cacheDb.auditLogDao().observeAuditLogs(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val localMemories: StateFlow<List<MemoryEntity>> =
        memoryEngine.observeMemories(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val workspaceMode: StateFlow<WorkspaceMode> = workspaceEngine.mode
    val workspaceDocuments: StateFlow<List<WorkspaceDocumentEntity>> =
        workspaceEngine.observeDocuments(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val localBookmarks: StateFlow<List<BookmarkEntity>> =
        workspaceEngine.observeBookmarks(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val browserTabSessions: StateFlow<List<BrowserTabSession>> = browserController.tabSessions

    val permissionPolicies: StateFlow<Map<PermissionDomain, Boolean>> = permissionPolicyStore.domainStates
    val activeAgentTaskId: StateFlow<String?> = agentOrchestrator.activeTaskId
    val pausedAgentTaskIds: StateFlow<Set<String>> = agentOrchestrator.pausedTasks

    val browserPageState: StateFlow<BrowserPageState> = browserController.pageState
    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val isLiveSessionActive: StateFlow<Boolean> = voiceManager.isLiveSessionActive
    val detectedVoiceLanguage: StateFlow<DetectedVoiceLanguage> = voiceManager.detectedLanguage
    val partialTranscript: StateFlow<String> = voiceManager.partialTranscript
    val waveformBars: StateFlow<FloatArray> = voiceManager.waveformBars
    val wakeWordRunning: StateFlow<Boolean> = AquaWakeWordService.isRunning
    val wakeWordPaused: StateFlow<Boolean> = AquaWakeWordService.isPaused
    val automationKeepAliveActive: StateFlow<Boolean> = AquaWakeWordService.isAutomationKeepAliveActive
    val activeAutomationTasks: StateFlow<List<String>> = AquaWakeWordService.activeAutomationTasks
    val foregroundStatusSummary: StateFlow<String> = AquaWakeWordService.foregroundStatusSummary
    val backgroundMicError: StateFlow<String?> = AquaWakeWordService.serviceError

    private val _coreState = MutableStateFlow(AquaCoreState.IDLE)
    val coreState: StateFlow<AquaCoreState> = _coreState.asStateFlow()

    private val _telemetry = MutableStateFlow(deviceController.getTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _storageAndMemory = MutableStateFlow(deviceController.getStorageAndMemoryStats())
    val storageAndMemory: StateFlow<StorageAndMemoryStats> = _storageAndMemory.asStateFlow()

    private val _telemetryHistory = MutableStateFlow(
        listOf(buildTelemetrySample(_telemetry.value, _storageAndMemory.value))
    )
    val telemetryHistory: StateFlow<List<TelemetrySample>> = _telemetryHistory.asStateFlow()

    private val _browserReaderData = MutableStateFlow<Pair<String, List<BrowserLink>>?>(null)
    val browserReaderData: StateFlow<Pair<String, List<BrowserLink>>?> = _browserReaderData.asStateFlow()

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

    val activeVoiceEngine: StateFlow<String> = voiceManager.activeVoiceEngine

    private val _aiSetupSheetVisible = MutableStateFlow(!localPrefs.hasCompletedAiSetup.value)
    val aiSetupSheetVisible: StateFlow<Boolean> = _aiSetupSheetVisible.asStateFlow()

    private val _verifiedAiStudioModels = MutableStateFlow<List<String>>(emptyList())
    val verifiedAiStudioModels: StateFlow<List<String>> = _verifiedAiStudioModels.asStateFlow()

    private val _aiKeyVerificationStatus = MutableStateFlow<String?>(null)
    val aiKeyVerificationStatus: StateFlow<String?> = _aiKeyVerificationStatus.asStateFlow()

    private val _elevenLabsVoices = MutableStateFlow<List<ElevenLabsVoice>>(emptyList())
    val elevenLabsVoices: StateFlow<List<ElevenLabsVoice>> = _elevenLabsVoices.asStateFlow()

    private val _elevenLabsSubscription = MutableStateFlow<ElevenLabsSubscription?>(null)
    val elevenLabsSubscription: StateFlow<ElevenLabsSubscription?> = _elevenLabsSubscription.asStateFlow()

    private val _elevenLabsStatus = MutableStateFlow<String?>(null)
    val elevenLabsStatus: StateFlow<String?> = _elevenLabsStatus.asStateFlow()

    private val _elevenLabsBusy = MutableStateFlow(false)
    val elevenLabsBusy: StateFlow<Boolean> = _elevenLabsBusy.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _navigateToTabEvent = MutableStateFlow<String?>(null)
    val navigateToTabEvent: StateFlow<String?> = _navigateToTabEvent.asStateFlow()

    val driveBusy: StateFlow<Boolean> = systemAndDriveCoordinator.driveBusy
    val driveConnected: StateFlow<Boolean> = systemAndDriveCoordinator.driveConnected
    val driveBackups: StateFlow<List<DriveBackupMetadata>> = systemAndDriveCoordinator.driveBackups
    val driveAboutInfo: StateFlow<DriveAboutInfo?> = systemAndDriveCoordinator.driveAboutInfo
    val driveStatusLabel: StateFlow<String?> = systemAndDriveCoordinator.driveStatusLabel
    val driveApiKeyStatus: StateFlow<String> = systemAndDriveCoordinator.driveApiKeyStatus
    val driveApiKeyMasked: StateFlow<String> = systemAndDriveCoordinator.driveApiKeyMasked
    val hasCustomDriveApiKey: StateFlow<Boolean> = systemAndDriveCoordinator.hasCustomDriveApiKey
    val driveAuthPendingIntent: StateFlow<PendingIntent?> = systemAndDriveCoordinator.driveAuthPendingIntent
    val preferredDriveEmail: StateFlow<String> = systemAndDriveCoordinator.preferredDriveEmail
    val driveSyncPendingCount: StateFlow<Int> = systemAndDriveCoordinator.syncPendingCount
    val driveSyncConflicts: StateFlow<List<DriveSyncQueueEntity>> = systemAndDriveCoordinator.syncConflicts
    val driveHierarchyFiles: StateFlow<List<DriveFileMetadataEntity>> = systemAndDriveCoordinator.driveHierarchyFiles
    val driveSyncStateBadge: StateFlow<String> = systemAndDriveCoordinator.syncStateBadge

    fun syncDriveNow(activity: Activity?) = systemAndDriveCoordinator.syncNow(activity)

    fun resolveDriveConflict(activity: Activity?, mutationId: String, strategy: ConflictResolutionStrategy) =
        systemAndDriveCoordinator.resolveConflict(activity, mutationId, strategy)

    init {
        viewModelScope.launch {
            voiceManager.isListening.collect { listening ->
                if (listening) {
                    _coreState.value = AquaCoreState.LISTENING
                } else {
                    if (_coreState.value == AquaCoreState.LISTENING) _coreState.value = AquaCoreState.IDLE
                    if (!voiceManager.isLiveSessionActive.value && AquaWakeWordService.isRunning.value) {
                        AquaWakeWordService.setExternalMicActive(appContext, false)
                    }
                }
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
                    sendMessage(cmd, speakReply = true) { replySummary ->
                        AquaWakeWordService.updateStatusNotification(
                            appContext,
                            "Aqua: ${replySummary.take(110)}"
                        )
                    }
                } else {
                    startVoiceCapture(autoSendAndSpeak = true)
                }
            }
        }
        viewModelScope.launch {
            AquaWakeWordService.serviceError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _bannerMessage.value = err
                    AquaWakeWordService.clearServiceError()
                }
            }
        }
        viewModelScope.launch {
            settings.collect { s ->
                if (s.backgroundAutomationEnabled && !AquaWakeWordService.isAutomationKeepAliveActive.value) {
                    AquaWakeWordService.startAutomationKeepAlive(appContext)
                }
                if (s.wakeWordEnabled &&
                    AquaWakeWordService.hasMicPermission(appContext) &&
                    !AquaWakeWordService.isRunning.value
                ) {
                    AquaWakeWordService.start(
                        context = appContext,
                        wakePhrase = s.wakeWordPhrase,
                        languageTag = s.voiceLanguage
                    )
                }
            }
        }
        systemAndDriveCoordinator.bootstrap()
    }

    fun refreshTelemetry() {
        val t = deviceController.getTelemetry()
        val s = deviceController.getStorageAndMemoryStats()
        _telemetry.value = t
        _storageAndMemory.value = s
        val sample = buildTelemetrySample(t, s)
        val updated = (_telemetryHistory.value + sample).takeLast(24)
        _telemetryHistory.value = updated
    }

    private fun buildTelemetrySample(
        t: DeviceTelemetry,
        s: StorageAndMemoryStats
    ): TelemetrySample {
        val ramUsedPct = if (s.ramTotalMb > 0L) {
            (((s.ramTotalMb - s.ramAvailableMb).coerceAtLeast(0L) * 100L) / s.ramTotalMb).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            batteryPercent = t.batteryPercent.coerceIn(0, 100),
            ramUsedPercent = ramUsedPct,
            isCharging = t.isCharging,
            isOnline = t.isOnline
        )
    }

    fun setAiPersona(id: String) {
        localPrefs.setPersonaId(id)
        val persona = AquaPersona.fromId(id)
        _bannerMessage.value = "Switched Aqua AI Persona to ${persona.label}."
    }

    fun updateCustomDirective(directive: String) {
        localPrefs.setCustomDirective(directive)
        _bannerMessage.value = if (directive.isBlank()) {
            "Cleared custom AI directive."
        } else {
            "Saved persistent custom AI directive."
        }
    }

    fun togglePinRoutine(routineId: String) {
        val pinned = localPrefs.togglePinnedRoutine(routineId)
        _bannerMessage.value = if (pinned) {
            "Pinned routine to Aqua Core Quick Strip."
        } else {
            "Unpinned routine from Quick Strip."
        }
    }

    fun togglePinConversation(conversationId: String) {
        val pinned = localPrefs.togglePinnedConversation(conversationId)
        _bannerMessage.value = if (pinned) {
            "Pinned conversation to top of History."
        } else {
            "Unpinned conversation."
        }
    }

    fun renameConversationAlias(conversationId: String, newTitle: String) {
        localPrefs.setConversationAlias(conversationId, newTitle)
        _bannerMessage.value = if (newTitle.isBlank()) {
            "Reset conversation title."
        } else {
            "Renamed conversation to '${newTitle.trim()}'."
        }
    }

    fun activeConversationTitle(): String {
        val id = _activeConversationId.value
        if (!id.isNullOrBlank()) {
            localPrefs.conversationAliases.value[id]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return conversations.value.firstOrNull { it.id == id }?.title
            ?: "Aqua AI Session"
    }

    fun buildActiveConversationMarkdown(): String {
        return ConversationMarkdownExporter.formatConversationAsMarkdown(
            title = activeConversationTitle(),
            messages = messages.value
        )
    }

    fun buildActiveConversationJson(): String {
        return ConversationMarkdownExporter.formatConversationAsJson(
            title = activeConversationTitle(),
            messages = messages.value
        )
    }

    fun buildSystemDiagnosticsReport(): String {
        refreshTelemetry()
        val activeFgs = wakeWordRunning.value || automationKeepAliveActive.value
        return DiagnosticReportExporter.buildSystemDiagnosticMarkdown(
            telemetry = _telemetry.value,
            storage = _storageAndMemory.value,
            samples = _telemetryHistory.value,
            installedAppsCount = deviceController.getInstalledLaunchableApps().size,
            activeModel = settings.value.preferredModel,
            activePersona = AquaPersona.fromId(personaId.value),
            foregroundServiceActive = activeFgs
        )
    }

    fun readClipboardPreview(): String {
        return deviceController.readClipboardText().getOrDefault("")
    }

    fun isUsingPersonalAiStudioKey(): Boolean =
        settings.value.useCustomDirectApiKey && secureConfigStore.hasGeminiApiKey()

    fun isElevenLabsActive(): Boolean =
        localPrefs.elevenLabsConfig.value.enabled &&
            secureConfigStore.hasElevenLabsApiKey() &&
            secureConfigStore.hasElevenLabsVoiceId()

    private fun buildAiEngineStatusSummary(): String {
        val s = settings.value
        val cfg = localPrefs.aiFreedomConfig.value
        val elCfg = localPrefs.elevenLabsConfig.value
        val activeModel = if (isUsingPersonalAiStudioKey() && cfg.customModelOverride.isNotBlank()) {
            cfg.customModelOverride
        } else {
            s.preferredModel
        }
        return buildString {
            appendLine("AI Engine Provider: ${if (isUsingPersonalAiStudioKey()) "Personal Google AI Studio Key" else "Managed Firebase AI Logic"}")
            appendLine("Active Model: $activeModel")
            appendLine("Autonomy Level: ${cfg.autonomyLevel} | Max Tool Steps: ${cfg.maxAgentSteps} | All Tools Unlocked: ${cfg.enableAllToolsAlways}")
            appendLine("Sampling: temperature=${cfg.temperature}, topP=${cfg.topP}, maxOutputTokens=${cfg.maxOutputTokens}, style=${cfg.responseStyle}")
            append(
                if (isElevenLabsActive()) {
                    "ElevenLabs Voice: Active (${elCfg.voiceName.ifBlank { secureConfigStore.getElevenLabsVoiceId().take(10) }}, model=${elCfg.modelId})"
                } else {
                    "ElevenLabs Voice: Optional / Not Active (using ${if (s.useGeminiTts) "Gemini Native TTS" else "Android TTS"})"
                }
            )
        }
    }

    fun openAiSetupWizard() {
        _aiSetupSheetVisible.value = true
    }

    fun dismissAiSetupWizard(markCompleted: Boolean = true) {
        if (markCompleted) {
            localPrefs.setAiSetupCompleted(true)
        }
        _aiSetupSheetVisible.value = false
    }

    fun updateAiFreedomConfig(config: AiFreedomConfig) {
        val clamped = UserProfilePolicy.clampFreedomForAge(config, settings.value)
        if (clamped.autonomyLevel != config.autonomyLevel) {
            _bannerMessage.value = "Teen Guardrails (13–17): Unrestricted Tools mode is limited to Balanced Autonomy."
        }
        localPrefs.setAiFreedomConfig(clamped)
    }

    fun updateUserProfile(ageYears: Int, genderId: String) {
        val normalized = UserProfilePolicy.normalizeSettingsProfile(
            settings = settings.value,
            ageYears = ageYears,
            genderId = genderId
        )
        val clampedFreedom = UserProfilePolicy.clampFreedomForAge(localPrefs.aiFreedomConfig.value, normalized)
        if (clampedFreedom != localPrefs.aiFreedomConfig.value) {
            localPrefs.setAiFreedomConfig(clampedFreedom)
        }
        updateSettings(normalized)
        val tier = UserProfilePolicy.resolveTier(normalized)
        val gender = UserGender.fromId(normalized.gender)
        _bannerMessage.value = when (tier) {
            AgeTier.UNDER_13_BLOCKED ->
                "Age ${normalized.ageYears} saved — Aqua AI requires users to be at least 13 years old."
            AgeTier.TEEN_13_17 ->
                "Profile updated: Teen Mode (13–17) + Teen Guardrails active • Gender: ${gender.label}."
            AgeTier.ADULT_18_PLUS ->
                "Profile updated: Adult Mode (18+) • Gender: ${gender.label}."
            AgeTier.UNSET ->
                "Profile updated: Age unset • Gender: ${gender.label}."
        }
    }

    fun updateElevenLabsConfig(config: ElevenLabsConfig) {
        localPrefs.setElevenLabsConfig(config)
    }

    fun verifyGoogleAiStudioKey(overrideApiKey: String? = null) {
        val key = (overrideApiKey?.trim()?.takeIf { it.isNotEmpty() })
            ?: secureConfigStore.getGeminiApiKey().orEmpty()
        if (key.isBlank()) {
            _aiKeyVerificationStatus.value = "Enter a Google AI Studio API key to verify."
            return
        }
        viewModelScope.launch {
            _aiKeyVerificationStatus.value = "Verifying Google AI Studio key..."
            voiceManager.elevenLabsClient.verifyGoogleAiStudioKey(key)
                .onSuccess { models ->
                    _verifiedAiStudioModels.value = models
                    _aiKeyVerificationStatus.value = if (models.isEmpty()) {
                        "Key verified, but no generateContent Gemini models were listed."
                    } else {
                        "Verified! Loaded ${models.size} Gemini model(s) from Google AI Studio."
                    }
                }
                .onFailure { err ->
                    _aiKeyVerificationStatus.value = err.localizedMessage ?: "Google AI Studio verification failed."
                }
        }
    }

    fun saveGoogleAiStudioKey(apiKey: String, enableDirectRouting: Boolean = true) {
        val trimmed = apiKey.trim()
        if (trimmed.isNotEmpty()) {
            secureConfigStore.saveGeminiApiKey(trimmed)
        }
        val hasKey = secureConfigStore.hasGeminiApiKey()
        updateSettings(settings.value.copy(useCustomDirectApiKey = enableDirectRouting && hasKey))
        _bannerMessage.value = if (hasKey) {
            "Encrypted & saved Google AI Studio API key."
        } else {
            "Cleared personal Google AI Studio API key."
        }
    }

    fun clearGoogleAiStudioKey() {
        secureConfigStore.clearGeminiApiKey()
        _verifiedAiStudioModels.value = emptyList()
        _aiKeyVerificationStatus.value = null
        updateSettings(settings.value.copy(useCustomDirectApiKey = false))
        _bannerMessage.value = "Removed personal Google AI Studio API key (using Firebase AI Logic)."
    }

    fun saveElevenLabsCredentials(apiKey: String, voiceId: String, voiceName: String = "") {
        if (apiKey.isNotBlank()) {
            secureConfigStore.saveElevenLabsApiKey(apiKey)
        }
        if (voiceId.isNotBlank()) {
            secureConfigStore.saveElevenLabsVoiceId(voiceId)
        }
        if (voiceName.isNotBlank()) {
            localPrefs.setElevenLabsConfig(localPrefs.elevenLabsConfig.value.copy(voiceName = voiceName.trim()))
        }
        val configured = secureConfigStore.hasElevenLabsApiKey() && secureConfigStore.hasElevenLabsVoiceId()
        _elevenLabsStatus.value = if (configured) {
            "ElevenLabs Voice configured (${voiceName.ifBlank { secureConfigStore.getElevenLabsVoiceId().take(10) }})."
        } else {
            "Optional ElevenLabs credentials updated."
        }
        _bannerMessage.value = _elevenLabsStatus.value
    }

    fun clearElevenLabsCredentials() {
        secureConfigStore.clearElevenLabsAll()
        _elevenLabsVoices.value = emptyList()
        _elevenLabsSubscription.value = null
        _elevenLabsStatus.value = "Optional ElevenLabs credentials cleared."
        localPrefs.setElevenLabsConfig(localPrefs.elevenLabsConfig.value.copy(voiceName = ""))
        _bannerMessage.value = "Cleared ElevenLabs API key & Voice ID."
    }

    fun fetchElevenLabsVoicesAndQuota(overrideApiKey: String? = null) {
        val key = (overrideApiKey?.trim()?.takeIf { it.isNotEmpty() })
            ?: secureConfigStore.getElevenLabsApiKey().orEmpty()
        if (key.isBlank()) {
            _elevenLabsStatus.value = "ElevenLabs is optional — enter an ElevenLabs API key to load your voices."
            return
        }
        viewModelScope.launch {
            _elevenLabsBusy.value = true
            _elevenLabsStatus.value = "Loading your ElevenLabs voices & quota..."
            val voicesRes = voiceManager.elevenLabsClient.fetchAvailableVoices(key)
            val subRes = voiceManager.elevenLabsClient.fetchSubscription(key)
            subRes.onSuccess { _elevenLabsSubscription.value = it }
            voicesRes
                .onSuccess { voices ->
                    _elevenLabsVoices.value = voices
                    _elevenLabsStatus.value = "Loaded ${voices.size} ElevenLabs voice(s) from your account."
                }
                .onFailure { err ->
                    _elevenLabsStatus.value = err.localizedMessage ?: "Failed to load ElevenLabs voices."
                }
            _elevenLabsBusy.value = false
        }
    }

    fun testElevenLabsVoice(
        overrideApiKey: String? = null,
        overrideVoiceId: String? = null
    ) {
        val key = (overrideApiKey?.trim()?.takeIf { it.isNotEmpty() })
            ?: secureConfigStore.getElevenLabsApiKey().orEmpty()
        val voiceId = (overrideVoiceId?.trim()?.takeIf { it.isNotEmpty() })
            ?: secureConfigStore.getElevenLabsVoiceId()
        if (key.isBlank() || voiceId.isBlank()) {
            _elevenLabsStatus.value = "Enter both ElevenLabs API key and Voice ID to preview speech."
            return
        }
        viewModelScope.launch {
            _elevenLabsBusy.value = true
            _elevenLabsStatus.value = "Synthesizing ElevenLabs voice preview..."
            voiceManager.testElevenLabsSpeech(
                sampleText = "Aqua AI voice core ready. Your custom ElevenLabs voice is active.",
                apiKey = key,
                voiceId = voiceId,
                elevenLabsConfig = localPrefs.elevenLabsConfig.value
            ).onSuccess { msg ->
                _elevenLabsStatus.value = msg
            }.onFailure { err ->
                _elevenLabsStatus.value = err.localizedMessage ?: "ElevenLabs preview failed."
                _bannerMessage.value = _elevenLabsStatus.value
            }
            _elevenLabsBusy.value = false
        }
    }

    fun completePostLoginAiSetup(
        googleAiStudioKey: String,
        useGoogleAiStudioKey: Boolean,
        preferredModel: String,
        elevenLabsKey: String,
        elevenLabsVoiceId: String,
        elevenLabsVoiceName: String,
        elevenLabsModelId: String,
        enableElevenLabs: Boolean,
        freedomConfig: AiFreedomConfig,
        ageYears: Int = settings.value.ageYears,
        genderId: String = settings.value.gender
    ) {
        if (googleAiStudioKey.isNotBlank()) {
            secureConfigStore.saveGeminiApiKey(googleAiStudioKey)
        }
        if (elevenLabsKey.isNotBlank()) {
            secureConfigStore.saveElevenLabsApiKey(elevenLabsKey)
        }
        if (elevenLabsVoiceId.isNotBlank()) {
            secureConfigStore.saveElevenLabsVoiceId(elevenLabsVoiceId)
        }
        val profileSettings = UserProfilePolicy.normalizeSettingsProfile(
            settings = settings.value,
            ageYears = ageYears,
            genderId = genderId
        )
        val clampedFreedom = UserProfilePolicy.clampFreedomForAge(freedomConfig, profileSettings)
        localPrefs.setAiFreedomConfig(clampedFreedom)
        localPrefs.setElevenLabsConfig(
            localPrefs.elevenLabsConfig.value.copy(
                enabled = enableElevenLabs,
                voiceName = elevenLabsVoiceName.trim(),
                modelId = elevenLabsModelId.ifBlank { ElevenLabsConfig.MODEL_MULTILINGUAL_V2 }
            )
        )
        val effectiveDirect = useGoogleAiStudioKey && secureConfigStore.hasGeminiApiKey()
        updateSettings(
            profileSettings.copy(
                preferredModel = preferredModel.ifBlank { profileSettings.preferredModel },
                useCustomDirectApiKey = effectiveDirect
            )
        )
        localPrefs.setAiSetupCompleted(true)
        _aiSetupSheetVisible.value = false
        val tier = UserProfilePolicy.resolveTier(profileSettings)
        _bannerMessage.value = buildString {
            append("AI Setup saved (")
            append(if (effectiveDirect) "Google AI Studio Key" else "Managed Firebase AI")
            if (isElevenLabsActive()) append(" + ElevenLabs Voice")
            if (tier != AgeTier.UNSET) append(" • ${tier.shortBadge}")
            append(").")
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun openTab(tab: String) {
        _navigateToTabEvent.value = tab
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

    fun setPermissionDomainAllowed(domain: PermissionDomain, allowed: Boolean) {
        permissionPolicyStore.setAllowed(domain, allowed)
        viewModelScope.launch(Dispatchers.IO) {
            agentOrchestrator.recordSecurityEvent(
                domain = domain,
                action = "policy_updated",
                riskTier = RiskTier.TIER_2_CONFIRM,
                outcome = if (allowed) "ENABLED" else "DISABLED",
                detail = "${domain.title} set to ${if (allowed) "Allowed" else "Blocked"}"
            )
        }
        _bannerMessage.value = "${domain.title}: ${if (allowed) "Allowed" else "Blocked"}."
    }

    fun pauseActiveAgentTask(taskId: String) {
        agentOrchestrator.pauseTask(taskId)
        _bannerMessage.value = "Paused autonomous task at next safe checkpoint."
    }

    fun resumeActiveAgentTask(taskId: String) {
        agentOrchestrator.resumeTask(taskId)
        _bannerMessage.value = "Resumed autonomous task."
    }

    fun cancelActiveAgentTask(taskId: String) {
        viewModelScope.launch {
            agentOrchestrator.cancelTask(taskId, "Cancelled by user")
            _coreState.value = AquaCoreState.IDLE
            _streamingReply.value = ""
            _bannerMessage.value = "Autonomous task cancelled cleanly."
        }
    }

    // --- CHAT & AUTONOMOUS AGENT ---

    fun sendMessage(
        promptText: String,
        speakReply: Boolean = false,
        automationTaskLabel: String? = null,
        onCompletedSummary: ((String) -> Unit)? = null
    ) {
        val trimmed = promptText.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        if (UserProfilePolicy.isUnderAgeBlocked(settings.value)) {
            _coreState.value = AquaCoreState.ERROR
            _bannerMessage.value = "Aqua AI requires users to be at least 13 years old. Update your age in Settings -> Profile & Safety."
            return
        }

        lateinit var launchedJob: kotlinx.coroutines.Job
        launchedJob = viewModelScope.launch {
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

            val taskLabel = automationTaskLabel?.takeIf { it.isNotBlank() }
                ?: "AI: ${trimmed.ifBlank { attachment?.displayName ?: "Agent Turn" }.take(45)}"
            val taskId = AquaWakeWordService.beginAutomationTask(appContext, taskLabel)
            val agentTaskId = agentOrchestrator.createTask(
                title = taskLabel,
                source = if (automationTaskLabel != null) "automation" else "chat"
            )
            agentOrchestrator.registerJob(agentTaskId, launchedJob)
            agentOrchestrator.transitionTask(agentTaskId, TaskState.PLANNING)
            var taskOutcomeSummary = ""

            try {
                val currentFreedom = UserProfilePolicy.clampFreedomForAge(localPrefs.aiFreedomConfig.value, currentSettings)
                val nativeWalletOverview = runCatching { walletCoordinator.aiBridge.getPublicOverviewText() }.getOrNull()
                val sysInstruction = ContextManager.buildSystemInstruction(
                    settings = currentSettings,
                    memories = memories.value,
                    enabledSkills = skills.value,
                    activeWallet = wallets.value.firstOrNull(),
                    telemetry = _telemetry.value,
                    browserUrl = browserPageState.value.currentUrl,
                    browserTitle = browserPageState.value.currentTitle,
                    nativeWalletOverview = nativeWalletOverview,
                    personaId = localPrefs.personaId.value,
                    customDirective = localPrefs.customDirective.value,
                    freedomConfig = currentFreedom,
                    usingPersonalAiStudioKey = isUsingPersonalAiStudioKey(),
                    elevenLabsActive = isElevenLabsActive()
                )

                agentOrchestrator.transitionTask(agentTaskId, TaskState.EXECUTING)
                val result = geminiProvider.executeAgentTurn(
                    userPrompt = trimmed,
                    history = messages.value,
                    attachment = attachment,
                    settings = currentSettings,
                    systemInstructionText = sysInstruction,
                    activeWallet = wallets.value.firstOrNull(),
                    freedomConfig = currentFreedom,
                    onNavigateToBrowser = { _ -> _navigateToTabEvent.value = "browser" },
                    requestAuthorization = { req -> requestUserAuthorization(req) },
                    onStreamChunk = { partial -> _streamingReply.value = partial },
                    onTrace = { trace ->
                        _coreState.value = AquaCoreState.EXECUTING_TOOL
                        _agentTraces.value = _agentTraces.value + trace
                        AquaWakeWordService.updateAutomationTaskStep(
                            appContext,
                            "${trace.toolName} (${trace.status})"
                        )
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
                    agentOrchestrator.transitionTask(agentTaskId, TaskState.VERIFYING, summary = exec.text.take(240))
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
                    agentOrchestrator.transitionTask(
                        taskId = agentTaskId,
                        nextState = TaskState.COMPLETED,
                        stepIndex = exec.traces.size,
                        totalSteps = maxOf(1, exec.traces.size),
                        summary = exec.text.take(240)
                    )
                    _coreState.value = AquaCoreState.IDLE
                    taskOutcomeSummary = "Completed: ${exec.text.take(80)}"
                    onCompletedSummary?.invoke(exec.text)
                    if (speakReply) {
                        voiceManager.speak(
                            text = exec.text,
                            settings = currentSettings,
                            scope = viewModelScope,
                            elevenLabsConfig = localPrefs.elevenLabsConfig.value
                        )
                    }
                }.onFailure { err ->
                    agentOrchestrator.transitionTask(
                        taskId = agentTaskId,
                        nextState = TaskState.FAILED,
                        error = err.localizedMessage ?: "Agent execution error"
                    )
                    _coreState.value = AquaCoreState.ERROR
                    val errMsg = err.localizedMessage ?: "Aqua AI failed to respond."
                    taskOutcomeSummary = "Task error: ${errMsg.take(60)}"
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
            } catch (ce: CancellationException) {
                agentOrchestrator.transitionTask(agentTaskId, TaskState.CANCELLED, summary = ce.message ?: "Cancelled")
                _coreState.value = AquaCoreState.IDLE
                throw ce
            } finally {
                AquaWakeWordService.endAutomationTask(appContext, taskId, taskOutcomeSummary)
            }
        }
    }

    // --- VOICE & LIVE API ---

    fun startVoiceCapture(autoSendAndSpeak: Boolean = true) {
        if (AquaWakeWordService.isRunning.value) {
            AquaWakeWordService.setExternalMicActive(appContext, true)
        }
        voiceManager.startSpeechRecognition(settings.value.voiceLanguage) { spokenText ->
            if (autoSendAndSpeak) {
                sendMessage(spokenText, speakReply = true)
            }
        }
    }

    fun stopVoiceCapture() {
        voiceManager.stopListening()
        if (AquaWakeWordService.isRunning.value && !voiceManager.isLiveSessionActive.value) {
            AquaWakeWordService.setExternalMicActive(appContext, false)
        }
    }

    fun speakMessage(text: String) = voiceManager.speak(
        text = text,
        settings = settings.value,
        scope = viewModelScope,
        elevenLabsConfig = localPrefs.elevenLabsConfig.value
    )

    fun stopSpeaking() = voiceManager.stopSpeaking()

    fun toggleLiveVoiceSession() {
        if (voiceManager.isLiveSessionActive.value) {
            voiceManager.stopLiveConversation()
            _liveStatus.value = "Live voice session disconnected"
            AquaWakeWordService.setExternalMicActive(appContext, false)
            return
        }
        if (UserProfilePolicy.isUnderAgeBlocked(settings.value)) {
            _liveStatus.value = "Live voice restricted (13+ age policy)"
            _bannerMessage.value = "Aqua AI requires users to be at least 13 years old. Update your age in Settings -> Profile & Safety."
            return
        }
        // Keep foreground microphone service active so Live audio continues even if user minimizes the app or locks screen
        if (AquaWakeWordService.isRunning.value) {
            AquaWakeWordService.setExternalMicActive(appContext, true)
        } else if (AquaWakeWordService.hasMicPermission(appContext)) {
            AquaWakeWordService.start(
                context = appContext,
                wakePhrase = settings.value.wakeWordPhrase,
                languageTag = settings.value.voiceLanguage,
                keepAliveOnly = true
            )
        }
        refreshTelemetry()
        val currentSettings = settings.value
        val currentFreedom = UserProfilePolicy.clampFreedomForAge(localPrefs.aiFreedomConfig.value, currentSettings)
        viewModelScope.launch {
            val nativeWalletOverview = runCatching { walletCoordinator.aiBridge.getPublicOverviewText() }.getOrNull()
            val sysInstruction = ContextManager.buildSystemInstruction(
                settings = currentSettings,
                memories = memories.value,
                enabledSkills = skills.value,
                activeWallet = wallets.value.firstOrNull(),
                telemetry = _telemetry.value,
                browserUrl = browserPageState.value.currentUrl,
                browserTitle = browserPageState.value.currentTitle,
                nativeWalletOverview = nativeWalletOverview,
                personaId = localPrefs.personaId.value,
                customDirective = localPrefs.customDirective.value,
                freedomConfig = currentFreedom,
                usingPersonalAiStudioKey = isUsingPersonalAiStudioKey(),
                elevenLabsActive = isElevenLabsActive()
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
                    AquaWakeWordService.updateAutomationTaskStep(
                        appContext,
                        "Live Tool: ${trace.toolName} (${trace.status})"
                    )
                },
                onStatusChanged = { status ->
                    _liveStatus.value = status
                    AquaWakeWordService.updateStatusNotification(appContext, status)
                    if (status.startsWith("Live session ended")) {
                        AquaWakeWordService.setExternalMicActive(appContext, false)
                    }
                }
            )
        }
    }

    fun setWakeWordServiceEnabled(enabled: Boolean) {
        if (enabled) {
            val started = AquaWakeWordService.start(
                context = appContext,
                wakePhrase = settings.value.wakeWordPhrase,
                languageTag = settings.value.voiceLanguage,
                keepAliveOnly = false
            )
            if (started) {
                updateSettings(settings.value.copy(wakeWordEnabled = true))
                _bannerMessage.value = "Voice & Background Service active — listening for '${settings.value.wakeWordPhrase}'."
            } else {
                updateSettings(settings.value.copy(wakeWordEnabled = false))
            }
        } else {
            updateSettings(settings.value.copy(wakeWordEnabled = false))
            if (settings.value.backgroundAutomationEnabled) {
                // Keep automation priority running if enabled; only exit mic listener mode cleanly
                AquaWakeWordService.setExternalMicActive(appContext, false)
                AquaWakeWordService.saveBackgroundConfig(appContext, enabled = false)
            } else {
                AquaWakeWordService.stop(appContext)
            }
            _bannerMessage.value = "Background voice listener stopped."
        }
    }

    fun setAutomationKeepAliveEnabled(enabled: Boolean) {
        if (enabled) {
            val started = AquaWakeWordService.startAutomationKeepAlive(appContext)
            updateSettings(settings.value.copy(backgroundAutomationEnabled = started))
            if (started) {
                _bannerMessage.value = "Persistent Foreground Service active — Aqua AI voice & automation tasks prioritized."
            }
        } else {
            updateSettings(settings.value.copy(backgroundAutomationEnabled = false))
            AquaWakeWordService.stopAutomationKeepAlive(appContext)
            _bannerMessage.value = "Persistent automation priority service disabled."
        }
    }

    fun setVoiceAndAutomationServiceEnabled(voiceEnabled: Boolean, automationEnabled: Boolean) {
        val voiceStarted = if (voiceEnabled) {
            AquaWakeWordService.start(
                context = appContext,
                wakePhrase = settings.value.wakeWordPhrase,
                languageTag = settings.value.voiceLanguage,
                keepAliveOnly = false
            )
        } else {
            false
        }
        val autoStarted = if (automationEnabled) {
            AquaWakeWordService.startAutomationKeepAlive(appContext)
        } else {
            false
        }
        if (!voiceEnabled && !automationEnabled) {
            AquaWakeWordService.stop(appContext)
            _bannerMessage.value = "Voice & automation priority service stopped."
        } else {
            if (!voiceEnabled) {
                AquaWakeWordService.saveBackgroundConfig(appContext, enabled = false)
                AquaWakeWordService.setExternalMicActive(appContext, false)
            }
            if (!automationEnabled) {
                AquaWakeWordService.stopAutomationKeepAlive(appContext)
            }
            if (voiceStarted || autoStarted) {
                _bannerMessage.value = "Aqua AI priority foreground service active."
            }
        }
        updateSettings(
            settings.value.copy(
                wakeWordEnabled = if (voiceEnabled) voiceStarted else false,
                backgroundAutomationEnabled = if (automationEnabled) autoStarted else false
            )
        )
    }

    fun toggleBackgroundMicPaused() {
        val nextPaused = !AquaWakeWordService.isPaused.value
        AquaWakeWordService.setPaused(appContext, nextPaused)
    }

    fun updateWakePhrase(phrase: String) {
        val clean = phrase.lowercase().trim().ifBlank { "hey aqua" }.take(40)
        val updated = settings.value.copy(wakeWordPhrase = clean)
        updateSettings(updated)
        if (updated.wakeWordEnabled && AquaWakeWordService.hasMicPermission(appContext)) {
            AquaWakeWordService.start(
                context = appContext,
                wakePhrase = clean,
                languageTag = updated.voiceLanguage,
                keepAliveOnly = false
            )
        }
        _bannerMessage.value = "Wake phrase updated to '$clean'."
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

    fun showBannerMessage(message: String) {
        _bannerMessage.value = message
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
        if (!browserController.isCurrentTabIncognito()) {
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
    }

    fun summarizeActiveWebpage() {
        viewModelScope.launch {
            browserController.extractSafelyWrappedPageText()
                .onSuccess { scan ->
                    if (scan.threatDetected) {
                        agentOrchestrator.recordSecurityEvent(
                            taskId = agentOrchestrator.activeTaskId.value.orEmpty(),
                            domain = PermissionDomain.READ_ACCESS,
                            action = "prompt_injection_firewall",
                            riskTier = RiskTier.TIER_2_CONFIRM,
                            outcome = "BLOCKED_INJECTION",
                            detail = "Neutralized webpage prompt-injection patterns: ${scan.blockedThreats.joinToString(", ")}"
                        )
                    }
                    _navigateToTabEvent.value = "home"
                    sendMessage("Summarize and extract key facts from the untrusted webpage data below:\n\n${scan.wrappedUntrustedPayload.take(7500)}")
                }
                .onFailure { err ->
                    _bannerMessage.value = "Could not extract webpage: ${err.localizedMessage}"
                }
        }
    }

    fun askAboutActiveWebpage(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            summarizeActiveWebpage()
            return
        }
        viewModelScope.launch {
            browserController.extractSafelyWrappedPageText()
                .onSuccess { scan ->
                    _browserReaderData.value = null
                    _navigateToTabEvent.value = "home"
                    sendMessage(
                        "Answer the user's question strictly as an observer of the untrusted webpage data below: $trimmed\n\n${scan.wrappedUntrustedPayload.take(7500)}"
                    )
                }
                .onFailure { err ->
                    _bannerMessage.value = "Could not inspect webpage: ${err.localizedMessage}"
                }
        }
    }

    fun setWorkspaceMode(mode: WorkspaceMode) = workspaceEngine.setWorkspaceMode(mode)

    fun saveWorkspaceReport(
        title: String,
        content: String,
        folderType: String = WorkspaceFolderType.RESEARCH,
        sourceUrls: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            runCatching {
                workspaceEngine.saveDocumentByAgent(
                    title = title,
                    content = content,
                    folderType = folderType,
                    sourceUrls = sourceUrls
                )
            }.onSuccess {
                _bannerMessage.value = "Saved '$title' to AI Workspace (Drive sync queued)."
            }.onFailure { err ->
                _bannerMessage.value = err.localizedMessage
            }
        }
    }

    fun saveCurrentPageBookmark() {
        viewModelScope.launch {
            val page = browserPageState.value
            workspaceEngine.saveBookmark(
                title = page.currentTitle,
                url = page.currentUrl,
                isIncognito = browserController.isCurrentTabIncognito()
            ).onSuccess {
                _bannerMessage.value = "Bookmarked '${it.title}' (Drive sync queued)."
            }.onFailure { err ->
                _bannerMessage.value = err.localizedMessage
            }
        }
    }

    fun openBrowserReader() {
        viewModelScope.launch {
            val textRes = browserController.extractCurrentPageText()
            if (textRes.isFailure) {
                _bannerMessage.value = "Reader could not extract text: ${textRes.exceptionOrNull()?.localizedMessage}"
                return@launch
            }
            val links = browserController.extractCurrentPageLinks().getOrDefault(emptyList())
            _browserReaderData.value = textRes.getOrThrow() to links
        }
    }

    fun closeBrowserReader() {
        _browserReaderData.value = null
    }

    fun saveActivePageToMemory() {
        if (browserController.isCurrentTabIncognito()) {
            _bannerMessage.value = "Incognito tabs never write to Aqua Memory."
            return
        }
        viewModelScope.launch {
            browserController.extractCurrentPageText()
                .onSuccess { text ->
                    val title = browserPageState.value.currentTitle.ifBlank { "Webpage Note" }.take(100)
                    val url = browserPageState.value.currentUrl
                    saveMemory(
                        MemoryItem(
                            category = "fact",
                            title = title,
                            content = "URL: $url\n\n${text.take(3500)}",
                            approvedByUser = true
                        )
                    )
                    _bannerMessage.value = "Saved '$title' to Aqua Memory."
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun deleteBrowserTab(tabId: String) {
        viewModelScope.launch { firestoreRepo.deleteBrowserTab(tabId) }
    }

    fun openBrowserUrlExternally(url: String) {
        deviceController.openExternalBrowser(url)
            .onFailure { _bannerMessage.value = "Could not open external browser: ${it.localizedMessage}" }
    }

    // --- AUTONOMOUS ROUTINES & MESSAGE UTILITIES ---

    fun saveRoutine(routine: AquaRoutine) {
        viewModelScope.launch {
            firestoreRepo.saveRoutine(routine)
                .onSuccess { _bannerMessage.value = "Saved routine '${routine.title}'." }
                .onFailure { _bannerMessage.value = "Failed to save routine: ${it.localizedMessage}" }
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch {
            firestoreRepo.deleteRoutine(routineId)
                .onSuccess { _bannerMessage.value = "Routine deleted." }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun executeRoutine(routine: AquaRoutine) {
        _navigateToTabEvent.value = "home"
        sendMessage(
            promptText = routine.prompt,
            speakReply = routine.speakResult,
            automationTaskLabel = "Routine: ${routine.title}"
        ) { reply ->
            if (routine.id.isNotBlank()) {
                viewModelScope.launch {
                    firestoreRepo.recordRoutineRun(routine.id, reply.take(500))
                }
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        deviceController.copyTextToClipboard(label, text)
            .onSuccess { _bannerMessage.value = it }
            .onFailure { _bannerMessage.value = it.localizedMessage }
    }

    fun shareText(title: String, text: String) {
        deviceController.shareTextExternally(title, text)
            .onFailure { _bannerMessage.value = it.localizedMessage }
    }

    fun askAquaWithClipboard() {
        deviceController.readClipboardText()
            .onSuccess { clip ->
                if (clip.isBlank()) {
                    _bannerMessage.value = "Android clipboard is empty."
                } else {
                    _navigateToTabEvent.value = "home"
                    sendMessage("Analyze, summarize, or help me act on this clipboard text:\n\n${clip.take(4000)}")
                }
            }
            .onFailure { _bannerMessage.value = it.localizedMessage }
    }

    fun saveAssistantReplyToMemory(replyText: String) {
        val clean = replyText.trim()
        if (clean.isEmpty()) return
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Aqua Insight"
        viewModelScope.launch {
            firestoreRepo.saveMemory(
                MemoryItem(
                    category = "fact",
                    title = firstLine,
                    content = clean.take(3900),
                    approvedByUser = true
                )
            )
                .onSuccess { _bannerMessage.value = "Saved reply to Aqua Memory." }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
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
            memoryEngine.saveMemory(
                id = memory.id,
                title = memory.title,
                content = memory.content,
                category = memory.category,
                tier = MemoryTier.LONG_TERM,
                confidence = 1.0f,
                sourceProvenance = "user_ui",
                isPinned = false,
                approvedByUser = memory.approvedByUser
            )
            firestoreRepo.saveMemory(memory)
                .onFailure { _bannerMessage.value = "Saved locally (cloud sync queued): ${it.localizedMessage}" }
        }
    }

    fun saveStructuredMemory(
        title: String,
        content: String,
        category: String = "fact",
        tier: String = MemoryTier.LONG_TERM,
        confidence: Float = 1.0f,
        sourceProvenance: String = "user",
        isPinned: Boolean = false
    ) {
        viewModelScope.launch {
            memoryEngine.saveMemory(
                title = title,
                content = content,
                category = category,
                tier = tier,
                confidence = confidence,
                sourceProvenance = sourceProvenance,
                isPinned = isPinned
            )
        }
    }

    fun toggleMemoryPin(memoryId: String, pinned: Boolean) {
        viewModelScope.launch {
            memoryEngine.setPinned(memoryId, pinned)
        }
    }

    suspend fun searchMemoriesLocal(query: String, tierFilter: String? = null): List<MemoryEntity> =
        memoryEngine.searchMemories(query = query, tierFilter = tierFilter)

    suspend fun exportMemoriesJson(): String = memoryEngine.exportMemoriesJson()

    suspend fun exportMemoriesMarkdown(): String = memoryEngine.exportMemoriesMarkdown()

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            memoryEngine.deleteMemoryPermanently(memoryId)
            firestoreRepo.deleteMemory(memoryId)
                .onFailure { _bannerMessage.value = "Removed locally & queued Drive deletion: ${it.localizedMessage}" }
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

    // --- GOOGLE DRIVE VAULT & ANDROID SDK / SIGNATURE DIAGNOSTICS ---

    fun consumeDriveAuthPendingIntent() = systemAndDriveCoordinator.consumeDriveAuthPendingIntent()

    fun handleDriveConsentResult(intent: Intent?, resultCode: Int = Activity.RESULT_OK) =
        systemAndDriveCoordinator.handleDriveConsentResult(intent, resultCode)

    fun probeSilentDriveSession() = systemAndDriveCoordinator.probeSilentDriveSession()

    fun connectGoogleDrive(activity: Activity?, forceAccountPicker: Boolean = false) =
        systemAndDriveCoordinator.connectGoogleDrive(activity, forceAccountPicker)

    fun disconnectGoogleDrive() = systemAndDriveCoordinator.disconnectGoogleDrive()

    fun refreshDriveBackups(activity: Activity?) = systemAndDriveCoordinator.refreshDriveBackups(activity)

    fun backupHistoryToGoogleDrive(activity: Activity?, exportVisibleCopy: Boolean = false) =
        systemAndDriveCoordinator.backupHistoryToGoogleDrive(activity, exportVisibleCopy)

    fun restoreHistoryFromGoogleDrive(activity: Activity?, fileId: String) =
        systemAndDriveCoordinator.restoreHistoryFromGoogleDrive(activity, fileId)

    fun deleteDriveBackup(activity: Activity?, fileId: String) =
        systemAndDriveCoordinator.deleteDriveBackup(activity, fileId)

    fun saveGoogleDriveApiKey(apiKey: String) =
        systemAndDriveCoordinator.saveGoogleDriveApiKey(apiKey)

    fun clearGoogleDriveApiKey() =
        systemAndDriveCoordinator.clearGoogleDriveApiKey()

    fun verifyGoogleDriveApiKey() =
        systemAndDriveCoordinator.verifyGoogleDriveApiKey()

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
