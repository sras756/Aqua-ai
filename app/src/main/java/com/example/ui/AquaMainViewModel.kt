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
import com.example.ai.ConversationMarkdownExporter
import com.example.ai.DiagnosticReportExporter
import com.example.ai.GeminiProvider
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.browser.BrowserLink
import com.example.browser.BrowserPageState
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.AquaLocalPreferences
import com.example.data.local.AquaPersona
import com.example.data.local.DeviceActionLogEntity
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
import com.example.data.repository.DriveAuthOutcome
import com.example.data.repository.DriveBackupMetadata
import com.example.data.repository.FileImportRepository
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.data.repository.GoogleWorkspaceRepository
import com.example.data.repository.WorkspaceAuthOutcome
import com.example.data.repository.WorkspaceCalendarEvent
import com.example.data.repository.WorkspaceChatMessage
import com.example.data.repository.WorkspaceChatSpace
import com.example.data.repository.WorkspaceContact
import com.example.data.repository.WorkspaceEmailMessage
import com.example.data.repository.WorkspaceFormInfo
import com.example.data.repository.WorkspaceFormResponseItem
import com.example.data.repository.WorkspaceService
import com.example.data.repository.WorkspaceSheetCreated
import com.example.data.repository.WorkspaceSheetReadResult
import com.example.device.AndroidDeviceController
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import com.example.skills.SkillParserAndValidator
import com.example.voice.AquaWakeWordService
import com.example.voice.VoiceAssistantManager
import com.example.wallet.FlashWalletConnector
import com.example.wallet.PreparedTransferRequest
import com.example.wallet.WalletBalanceSnapshot
import com.example.wallet.WalletIntegrationDiscovery
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.AquaWalletEngine
import com.example.wallet.security.WalletSecurityVault
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.LauncherAiController
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
    val workspaceRepo: GoogleWorkspaceRepository,
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
    val personaId: StateFlow<String> = localPrefs.personaId
    val customDirective: StateFlow<String> = localPrefs.customDirective
    val pinnedRoutineIds: StateFlow<Set<String>> = localPrefs.pinnedRoutineIds
    val pinnedConversationIds: StateFlow<Set<String>> = localPrefs.pinnedConversationIds
    val conversationAliases: StateFlow<Map<String, String>> = localPrefs.conversationAliases

    val launcherEngine: AquaLauncherEngine = AquaLauncherEngine.getInstance(appContext)
    val launcherAiController: LauncherAiController = LauncherAiController(launcherEngine)

    private val toolCallingEngine = ToolCallingEngine(
        deviceController = deviceController,
        browserController = browserController,
        walletConnector = walletConnector,
        firestoreRepo = firestoreRepo,
        driveHistoryRepo = driveHistoryRepo,
        workspaceRepo = workspaceRepo,
        aiWalletBridge = walletCoordinator.aiBridge,
        onWalletMutated = { viewModelScope.launch { walletCoordinator.reloadAll() } },
        launcherEngine = launcherEngine
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

    val routines: StateFlow<List<AquaRoutine>> = firestoreRepo.observeRoutines()
        .catch { handleFlowError("routines", it); emit(emptyList()) }
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

    // --- GOOGLE WORKSPACE STATE ---
    private val _workspaceBusy = MutableStateFlow(false)
    val workspaceBusy: StateFlow<Boolean> = _workspaceBusy.asStateFlow()

    private val _workspaceStatus = MutableStateFlow<String?>(null)
    val workspaceStatus: StateFlow<String?> = _workspaceStatus.asStateFlow()

    private val _workspaceConsentHelpAccount = MutableStateFlow<String?>(null)
    val workspaceConsentHelpAccount: StateFlow<String?> = _workspaceConsentHelpAccount.asStateFlow()

    private val _workspaceAuthPendingIntent = MutableStateFlow<PendingIntent?>(null)
    val workspaceAuthPendingIntent: StateFlow<PendingIntent?> = _workspaceAuthPendingIntent.asStateFlow()

    private var pendingWorkspaceAction: (suspend (String) -> Unit)? = null

    private val _workspaceContacts = MutableStateFlow<List<WorkspaceContact>>(emptyList())
    val workspaceContacts: StateFlow<List<WorkspaceContact>> = _workspaceContacts.asStateFlow()

    private val _workspaceEvents = MutableStateFlow<List<WorkspaceCalendarEvent>>(emptyList())
    val workspaceEvents: StateFlow<List<WorkspaceCalendarEvent>> = _workspaceEvents.asStateFlow()

    private val _workspaceEmails = MutableStateFlow<List<WorkspaceEmailMessage>>(emptyList())
    val workspaceEmails: StateFlow<List<WorkspaceEmailMessage>> = _workspaceEmails.asStateFlow()

    private val _workspaceSheetCreated = MutableStateFlow<WorkspaceSheetCreated?>(null)
    val workspaceSheetCreated: StateFlow<WorkspaceSheetCreated?> = _workspaceSheetCreated.asStateFlow()

    private val _workspaceSheetRead = MutableStateFlow<WorkspaceSheetReadResult?>(null)
    val workspaceSheetRead: StateFlow<WorkspaceSheetReadResult?> = _workspaceSheetRead.asStateFlow()

    private val _workspaceFormInfo = MutableStateFlow<WorkspaceFormInfo?>(null)
    val workspaceFormInfo: StateFlow<WorkspaceFormInfo?> = _workspaceFormInfo.asStateFlow()

    private val _workspaceFormResponses = MutableStateFlow<List<WorkspaceFormResponseItem>>(emptyList())
    val workspaceFormResponses: StateFlow<List<WorkspaceFormResponseItem>> = _workspaceFormResponses.asStateFlow()

    private val _workspaceChatSpaces = MutableStateFlow<List<WorkspaceChatSpace>>(emptyList())
    val workspaceChatSpaces: StateFlow<List<WorkspaceChatSpace>> = _workspaceChatSpaces.asStateFlow()

    private val _workspaceChatMessages = MutableStateFlow<List<WorkspaceChatMessage>>(emptyList())
    val workspaceChatMessages: StateFlow<List<WorkspaceChatMessage>> = _workspaceChatMessages.asStateFlow()

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
        viewModelScope.launch {
            val silentToken = driveHistoryRepo.tryGetSilentAccessToken()
            if (!silentToken.isNullOrBlank()) {
                _driveConnected.value = true
                driveHistoryRepo.listBackups(silentToken)
                    .onSuccess { _driveBackups.value = it }
            }
        }
        launcherEngine.attachCloudPushCallback { json ->
            firestoreRepo.saveLauncherSync(json)
        }
        viewModelScope.launch {
            firestoreRepo.observeLauncherSync()
                .catch { /* Non-fatal if offline or uninitialized */ }
                .collect { record ->
                    if (record != null && launcherEngine.state.value.cloudSyncEnabled) {
                        val remoteTs = record.updatedAt?.toDate()?.time ?: 0L
                        val localTs = launcherEngine.state.value.lastCloudSyncMs
                        if (remoteTs > localTs && record.configJson.isNotBlank()) {
                            launcherEngine.applySyncedCloudPreferences(record.configJson, remoteTs)
                        }
                    }
                }
        }
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
            installedAppsCount = launcherEngine.installedApps.value.size,
            activeModel = settings.value.preferredModel,
            activePersona = AquaPersona.fromId(personaId.value),
            foregroundServiceActive = activeFgs
        )
    }

    fun readClipboardPreview(): String {
        return deviceController.readClipboardText().getOrDefault("")
    }

    fun openStandaloneLauncher() {
        runCatching {
            val intent = Intent(appContext, AquaLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            appContext.startActivity(intent)
        }.onFailure { err ->
            _bannerMessage.value = "Could not open standalone launcher: ${err.localizedMessage}"
        }
    }

    fun pushLauncherCloudSyncNow() {
        viewModelScope.launch {
            val json = launcherEngine.state.value.toCloudSyncJson()
            firestoreRepo.saveLauncherSync(json)
                .onSuccess {
                    launcherEngine.postBanner("Synced Aqua Launcher theme, wallpaper & gesture preferences to cloud.")
                }
                .onFailure { err ->
                    launcherEngine.postBanner("Cloud sync failed: ${err.localizedMessage}")
                }
        }
    }

    private fun buildLauncherOverviewText(): String {
        val st = launcherEngine.state.value
        val appCount = launcherEngine.installedApps.value.size
        val defaultHome = launcherEngine.isDefaultLauncher.value
        return "- Discovered Installed Apps: $appCount | Default System Home Active: $defaultHome\n" +
            "- Theme Preset: ${st.theme.presetId} | Grid: ${st.theme.gridColumns}x${st.theme.gridRows} | Pages: ${st.pageCount}\n" +
            "- Wallpaper: ${st.wallpaper.label} (${st.wallpaper.typeId}) | Gaming Mode: ${st.gamingModeActive}\n" +
            "- Tools: launcher_get_overview, launcher_search_apps, launcher_set_theme_or_wallpaper, launcher_manage_layout, launcher_execute_command"
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

            val taskLabel = automationTaskLabel?.takeIf { it.isNotBlank() }
                ?: "AI: ${trimmed.ifBlank { attachment?.displayName ?: "Agent Turn" }.take(45)}"
            val taskId = AquaWakeWordService.beginAutomationTask(appContext, taskLabel)
            var taskOutcomeSummary = ""

            try {
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
                    launcherOverview = buildLauncherOverviewText(),
                    personaId = localPrefs.personaId.value,
                    customDirective = localPrefs.customDirective.value
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
                    taskOutcomeSummary = "Completed: ${exec.text.take(80)}"
                    onCompletedSummary?.invoke(exec.text)
                    if (speakReply) {
                        voiceManager.speak(exec.text, currentSettings, viewModelScope)
                    }
                }.onFailure { err ->
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

    fun speakMessage(text: String) = voiceManager.speak(text, settings.value, viewModelScope)

    fun stopSpeaking() = voiceManager.stopSpeaking()

    fun toggleLiveVoiceSession() {
        if (voiceManager.isLiveSessionActive.value) {
            voiceManager.stopLiveConversation()
            _liveStatus.value = "Live voice session disconnected"
            AquaWakeWordService.setExternalMicActive(appContext, false)
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
                launcherOverview = buildLauncherOverviewText(),
                personaId = localPrefs.personaId.value,
                customDirective = localPrefs.customDirective.value
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

    fun askAboutActiveWebpage(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            summarizeActiveWebpage()
            return
        }
        viewModelScope.launch {
            browserController.extractCurrentPageText()
                .onSuccess { pageText ->
                    _browserReaderData.value = null
                    _navigateToTabEvent.value = "home"
                    sendMessage(
                        "Regarding the active webpage below, answer this question: $trimmed\n\nWebpage content:\n${pageText.take(6500)}"
                    )
                }
                .onFailure { err ->
                    _bannerMessage.value = "Could not inspect webpage: ${err.localizedMessage}"
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
                _driveStatusLabel.value = "Drive consent cancelled/blocked — check OAuth Test Users in Google Cloud"
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

    // --- GOOGLE WORKSPACE (Contacts, Forms, Calendar, Gmail, Sheets, Chat) ---

    fun dismissWorkspaceConsentHelp() {
        _workspaceConsentHelpAccount.value = null
    }

    fun consumeWorkspaceAuthPendingIntent() {
        _workspaceAuthPendingIntent.value = null
    }

    fun handleWorkspaceConsentResult(intent: Intent?, resultCode: Int = Activity.RESULT_OK) {
        val actionToRun = pendingWorkspaceAction
        pendingWorkspaceAction = null
        val targetIntent = if (resultCode == Activity.RESULT_OK) intent else null
        workspaceRepo.extractTokenFromIntent(targetIntent)
            .onSuccess { token ->
                _workspaceConsentHelpAccount.value = null
                _workspaceStatus.value = "Google Workspace OAuth authorized"
                if (actionToRun != null) {
                    viewModelScope.launch {
                        _workspaceBusy.value = true
                        try {
                            actionToRun(token)
                        } finally {
                            _workspaceBusy.value = false
                        }
                    }
                }
            }
            .onFailure { err ->
                _workspaceBusy.value = false
                val signedInEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.orEmpty()
                _workspaceConsentHelpAccount.value = signedInEmail.ifBlank { "selected Google account" }
                _workspaceStatus.value = "OAuth blocked or cancelled (Error 403 / Testing mode)"
                _bannerMessage.value = err.localizedMessage ?: "Google Workspace consent cancelled."
            }
    }

    private fun runWithWorkspaceToken(
        activity: Activity?,
        scopes: List<String>,
        block: suspend (String) -> Unit
    ) {
        viewModelScope.launch {
            _workspaceBusy.value = true
            when (val auth = workspaceRepo.authorizeScopes(activity, scopes)) {
                is WorkspaceAuthOutcome.Authorized -> {
                    try {
                        block(auth.accessToken)
                    } finally {
                        _workspaceBusy.value = false
                    }
                }
                is WorkspaceAuthOutcome.NeedsResolution -> {
                    pendingWorkspaceAction = block
                    _workspaceBusy.value = false
                    _workspaceAuthPendingIntent.value = auth.pendingIntent
                }
                is WorkspaceAuthOutcome.Error -> {
                    _workspaceBusy.value = false
                    _bannerMessage.value = auth.message
                }
            }
        }
    }

    fun connectAllWorkspace(activity: Activity?) {
        val allScopes = WorkspaceService.entries.flatMap { it.scopes }.distinct()
        runWithWorkspaceToken(activity, allScopes) {
            _workspaceStatus.value = "All 6 Google Workspace integrations authorized"
            _bannerMessage.value = "Google Contacts, Forms, Calendar, Gmail, Sheets, and Chat authorized."
        }
    }

    fun loadContacts(activity: Activity?, query: String = "") {
        runWithWorkspaceToken(activity, WorkspaceService.CONTACTS.scopes) { token ->
            workspaceRepo.listContacts(token, query)
                .onSuccess { list ->
                    _workspaceContacts.value = list
                    _workspaceStatus.value = "Loaded ${list.size} contact(s)"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun createContact(activity: Activity?, fullName: String, email: String, phone: String) {
        runWithWorkspaceToken(activity, WorkspaceService.CONTACTS.scopes) { token ->
            workspaceRepo.createContact(token, fullName, email, phone)
                .onSuccess { created ->
                    _bannerMessage.value = "Saved Google Contact '${created.displayName}'."
                    workspaceRepo.listContacts(token).onSuccess { _workspaceContacts.value = it }
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun loadCalendarEvents(activity: Activity?) {
        runWithWorkspaceToken(activity, WorkspaceService.CALENDAR.scopes) { token ->
            workspaceRepo.listUpcomingEvents(token)
                .onSuccess { list ->
                    _workspaceEvents.value = list
                    _workspaceStatus.value = "Loaded ${list.size} upcoming calendar event(s)"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun createCalendarEvent(
        activity: Activity?,
        summary: String,
        startIso: String,
        endIso: String,
        description: String,
        location: String
    ) {
        runWithWorkspaceToken(activity, WorkspaceService.CALENDAR.scopes) { token ->
            workspaceRepo.createCalendarEvent(token, summary, startIso, endIso, description, location)
                .onSuccess { ev ->
                    _bannerMessage.value = "Created event '${ev.summary}'."
                    workspaceRepo.listUpcomingEvents(token).onSuccess { _workspaceEvents.value = it }
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun loadGmailMessages(activity: Activity?, query: String = "") {
        runWithWorkspaceToken(activity, WorkspaceService.GMAIL.scopes) { token ->
            workspaceRepo.listGmailMessages(token, query)
                .onSuccess { list ->
                    _workspaceEmails.value = list
                    _workspaceStatus.value = "Loaded ${list.size} Gmail message(s)"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun sendGmailEmail(activity: Activity?, to: String, subject: String, bodyText: String) {
        if (to.isBlank() || subject.isBlank()) {
            _bannerMessage.value = "Recipient email and subject are required."
            return
        }
        viewModelScope.launch {
            val approved = requestUserAuthorization(
                PendingAuthorization(
                    id = "gmail_${System.currentTimeMillis()}",
                    actionTitle = "Send Email via Gmail",
                    actionType = "Outbound Email Confirmation",
                    description = "Confirm sending this email through your authorized Gmail account.",
                    parametersSummary = listOf(
                        "To" to to.trim(),
                        "Subject" to subject.trim(),
                        "Body Preview" to bodyText.trim().take(160)
                    ),
                    isFinancialOrSecurity = true,
                    onApprove = { "Approved email send" },
                    onDeny = { "Cancelled email send" }
                )
            )
            if (!approved) {
                _bannerMessage.value = "Email sending cancelled."
                return@launch
            }
            runWithWorkspaceToken(activity, WorkspaceService.GMAIL.scopes) { token ->
                workspaceRepo.sendGmailMessage(token, to, subject, bodyText)
                    .onSuccess { id ->
                        _bannerMessage.value = "Gmail message sent (id: $id)."
                        workspaceRepo.listGmailMessages(token).onSuccess { _workspaceEmails.value = it }
                    }
                    .onFailure { _bannerMessage.value = it.localizedMessage }
            }
        }
    }

    fun createGoogleSheet(activity: Activity?, title: String) {
        runWithWorkspaceToken(activity, WorkspaceService.SHEETS.scopes) { token ->
            workspaceRepo.createSpreadsheet(token, title)
                .onSuccess { created ->
                    _workspaceSheetCreated.value = created
                    _workspaceStatus.value = "Created sheet '${created.title}'"
                    _bannerMessage.value = "Created Google Sheet '${created.title}'."
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun readGoogleSheet(activity: Activity?, spreadsheetId: String, range: String) {
        runWithWorkspaceToken(activity, WorkspaceService.SHEETS.scopes) { token ->
            workspaceRepo.readSheetRange(token, spreadsheetId, range)
                .onSuccess { res ->
                    _workspaceSheetRead.value = res
                    _workspaceStatus.value = "Read ${res.rows.size} row(s) from ${res.range}"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun appendGoogleSheetRow(activity: Activity?, spreadsheetId: String, range: String, valuesCsv: String) {
        val values = valuesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        runWithWorkspaceToken(activity, WorkspaceService.SHEETS.scopes) { token ->
            workspaceRepo.appendSheetRow(token, spreadsheetId, range, values)
                .onSuccess { msg ->
                    _bannerMessage.value = msg
                    workspaceRepo.readSheetRange(token, spreadsheetId, range)
                        .onSuccess { _workspaceSheetRead.value = it }
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun createGoogleForm(activity: Activity?, title: String, firstQuestion: String) {
        runWithWorkspaceToken(activity, WorkspaceService.FORMS.scopes) { token ->
            workspaceRepo.createForm(token, title, firstQuestion)
                .onSuccess { info ->
                    _workspaceFormInfo.value = info
                    _workspaceFormResponses.value = emptyList()
                    _workspaceStatus.value = "Created Google Form '${info.title}'"
                    _bannerMessage.value = "Created Google Form '${info.title}'."
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun loadGoogleFormAndResponses(activity: Activity?, formId: String) {
        runWithWorkspaceToken(activity, WorkspaceService.FORMS.scopes) { token ->
            workspaceRepo.getFormAndResponses(token, formId)
                .onSuccess { (info, responses) ->
                    _workspaceFormInfo.value = info
                    _workspaceFormResponses.value = responses
                    _workspaceStatus.value = "Loaded ${responses.size} response(s) for '${info.title}'"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun loadChatSpaces(activity: Activity?) {
        runWithWorkspaceToken(activity, WorkspaceService.CHAT.scopes) { token ->
            workspaceRepo.listChatSpaces(token)
                .onSuccess { spaces ->
                    _workspaceChatSpaces.value = spaces
                    _workspaceStatus.value = "Loaded ${spaces.size} Google Chat space(s)"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun loadChatMessages(activity: Activity?, spaceName: String) {
        runWithWorkspaceToken(activity, WorkspaceService.CHAT.scopes) { token ->
            workspaceRepo.listChatMessages(token, spaceName)
                .onSuccess { msgs ->
                    _workspaceChatMessages.value = msgs
                    _workspaceStatus.value = "Loaded ${msgs.size} message(s) in $spaceName"
                }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun sendChatMessage(activity: Activity?, spaceName: String, text: String) {
        if (spaceName.isBlank() || text.isBlank()) {
            _bannerMessage.value = "Space name and message text are required."
            return
        }
        viewModelScope.launch {
            val approved = requestUserAuthorization(
                PendingAuthorization(
                    id = "gchat_${System.currentTimeMillis()}",
                    actionTitle = "Send Google Chat Message",
                    actionType = "Outbound Google Chat Confirmation",
                    description = "Confirm posting this message into Google Chat space $spaceName.",
                    parametersSummary = listOf(
                        "Space" to spaceName.trim(),
                        "Message" to text.trim().take(160)
                    ),
                    isFinancialOrSecurity = true,
                    onApprove = { "Approved Chat message" },
                    onDeny = { "Cancelled Chat message" }
                )
            )
            if (!approved) {
                _bannerMessage.value = "Google Chat send cancelled."
                return@launch
            }
            runWithWorkspaceToken(activity, WorkspaceService.CHAT.scopes) { token ->
                workspaceRepo.sendChatMessage(token, spaceName, text)
                    .onSuccess {
                        _bannerMessage.value = "Message posted to $spaceName."
                        workspaceRepo.listChatMessages(token, spaceName)
                            .onSuccess { _workspaceChatMessages.value = it }
                    }
                    .onFailure { _bannerMessage.value = it.localizedMessage }
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
        launcherEngine.attachCloudPushCallback(null)
        voiceManager.release()
    }
}
