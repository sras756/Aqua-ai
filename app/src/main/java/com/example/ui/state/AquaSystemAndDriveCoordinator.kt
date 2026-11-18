package com.example.ui.state

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.data.local.SecureConfigStore
import com.example.data.repository.AquaFirestoreRepository
import com.example.data.repository.DriveAboutInfo
import com.example.data.repository.DriveAuthOutcome
import com.example.data.repository.DriveBackupMetadata
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.device.AndroidSdkInspector
import com.example.device.AndroidSdkReport
import com.example.security.GoogleSignatureVerifier
import com.example.security.SignatureCheckReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Clean modular coordinator handling:
 * 1. Google Drive Vault OAuth authorization, approved Drive API key management, quota telemetry, and history backups.
 * 2. Live Google Signature Check (APK SHA-1 / SHA-256, OAuth Web Client ID, Play Services, Auth linkage).
 * 3. Android SDK Inspector (API levels, security patch, ABIs, hardware features, JVM heap).
 */
class AquaSystemAndDriveCoordinator(
    private val appContext: Context,
    private val currentUserId: String,
    private val firestoreRepo: AquaFirestoreRepository,
    private val secureConfigStore: SecureConfigStore,
    private val driveHistoryRepo: GoogleDriveHistoryRepository,
    private val scope: CoroutineScope,
    private val onBanner: (String) -> Unit
) {
    private val _driveBusy = MutableStateFlow(false)
    val driveBusy: StateFlow<Boolean> = _driveBusy.asStateFlow()

    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    private val _driveBackups = MutableStateFlow<List<DriveBackupMetadata>>(emptyList())
    val driveBackups: StateFlow<List<DriveBackupMetadata>> = _driveBackups.asStateFlow()

    private val _driveAboutInfo = MutableStateFlow<DriveAboutInfo?>(null)
    val driveAboutInfo: StateFlow<DriveAboutInfo?> = _driveAboutInfo.asStateFlow()

    private val _driveStatusLabel = MutableStateFlow<String?>(null)
    val driveStatusLabel: StateFlow<String?> = _driveStatusLabel.asStateFlow()

    private val _driveApiKeyStatus = MutableStateFlow(driveHistoryRepo.getApiKeyStatusSummary())
    val driveApiKeyStatus: StateFlow<String> = _driveApiKeyStatus.asStateFlow()

    private val _driveApiKeyMasked = MutableStateFlow(secureConfigStore.getMaskedGoogleDriveApiKey())
    val driveApiKeyMasked: StateFlow<String> = _driveApiKeyMasked.asStateFlow()

    private val _hasCustomDriveApiKey = MutableStateFlow(secureConfigStore.hasGoogleDriveApiKey())
    val hasCustomDriveApiKey: StateFlow<Boolean> = _hasCustomDriveApiKey.asStateFlow()

    private val _driveAuthPendingIntent = MutableStateFlow<PendingIntent?>(null)
    val driveAuthPendingIntent: StateFlow<PendingIntent?> = _driveAuthPendingIntent.asStateFlow()

    private val _preferredDriveEmail = MutableStateFlow(driveHistoryRepo.getCurrentSignedInGoogleEmail())
    val preferredDriveEmail: StateFlow<String> = _preferredDriveEmail.asStateFlow()

    private var pendingDriveAction: (suspend (String) -> Unit)? = null

    private val _sdkReport = MutableStateFlow(AndroidSdkInspector.inspect(appContext))
    val sdkReport: StateFlow<AndroidSdkReport> = _sdkReport.asStateFlow()

    private val _signatureReport = MutableStateFlow(_sdkReport.value.signatureReport)
    val signatureReport: StateFlow<SignatureCheckReport> = _signatureReport.asStateFlow()

    fun bootstrap() {
        refreshSystemDiagnostics()
        probeSilentDriveSession()
    }

    fun probeSilentDriveSession() {
        scope.launch {
            if (_driveConnected.value && _driveAboutInfo.value != null) return@launch
            val silentToken = driveHistoryRepo.tryGetSilentAccessToken()
            if (!silentToken.isNullOrBlank()) {
                _driveConnected.value = true
                driveHistoryRepo.fetchDriveAboutInfo(silentToken)
                    .onSuccess { _driveAboutInfo.value = it }
                driveHistoryRepo.listBackups(silentToken)
                    .onSuccess { list ->
                        _driveBackups.value = list
                        _driveStatusLabel.value = if (list.isEmpty()) {
                            "Connected • Ready for 1-tap backup"
                        } else {
                            "Connected • ${list.size} backup file(s) synced"
                        }
                    }
            }
        }
    }

    fun refreshSystemDiagnostics() {
        val report = AndroidSdkInspector.inspect(appContext)
        _sdkReport.value = report
        _signatureReport.value = report.signatureReport
        _preferredDriveEmail.value = driveHistoryRepo.getCurrentSignedInGoogleEmail()
        _driveApiKeyStatus.value = driveHistoryRepo.getApiKeyStatusSummary()
        _driveApiKeyMasked.value = secureConfigStore.getMaskedGoogleDriveApiKey()
        _hasCustomDriveApiKey.value = secureConfigStore.hasGoogleDriveApiKey()
    }

    fun buildSdkAndSignatureMarkdown(): String {
        refreshSystemDiagnostics()
        return AndroidSdkInspector.formatMarkdownReport(_sdkReport.value)
    }

    fun saveGoogleDriveApiKey(rawKey: String) {
        val clean = rawKey.trim()
        if (clean.isBlank()) {
            onBanner("Enter a valid Google Drive API key before saving.")
            return
        }
        secureConfigStore.saveGoogleDriveApiKey(clean)
        _driveApiKeyMasked.value = secureConfigStore.getMaskedGoogleDriveApiKey()
        _hasCustomDriveApiKey.value = secureConfigStore.hasGoogleDriveApiKey()
        _driveApiKeyStatus.value = driveHistoryRepo.getApiKeyStatusSummary()
        onBanner("Approved Google Drive API key saved in Android Keystore.")
        verifyGoogleDriveApiKey(clean)
    }

    fun clearGoogleDriveApiKey() {
        secureConfigStore.clearGoogleDriveApiKey()
        _driveApiKeyMasked.value = ""
        _hasCustomDriveApiKey.value = false
        _driveApiKeyStatus.value = driveHistoryRepo.getApiKeyStatusSummary()
        onBanner("Custom Google Drive API key cleared.")
    }

    fun verifyGoogleDriveApiKey(overrideKey: String? = null) {
        scope.launch {
            _driveBusy.value = true
            val token = driveHistoryRepo.tryGetSilentAccessToken()
            driveHistoryRepo.verifyDriveApiKeyStatus(token, overrideKey)
                .onSuccess { msg ->
                    _driveApiKeyStatus.value = msg
                    onBanner(msg)
                }
                .onFailure { err ->
                    val errMsg = err.localizedMessage ?: "Drive API key verification failed."
                    _driveApiKeyStatus.value = errMsg
                    onBanner(errMsg)
                }
            _driveBusy.value = false
        }
    }

    fun consumeDriveAuthPendingIntent() {
        _driveAuthPendingIntent.value = null
    }

    fun handleDriveConsentResult(intent: Intent?, resultCode: Int = Activity.RESULT_OK) {
        val actionToRun = pendingDriveAction
        pendingDriveAction = null
        if (resultCode != Activity.RESULT_OK && intent == null) {
            _driveBusy.value = false
            _driveStatusLabel.value = "Sign-in paused • Tap Sign in with Google Drive anytime"
            return
        }
        driveHistoryRepo.extractTokenFromIntent(intent)
            .onSuccess { token ->
                _driveConnected.value = true
                if (actionToRun != null) {
                    scope.launch {
                        _driveBusy.value = true
                        try {
                            actionToRun(token)
                        } finally {
                            _driveBusy.value = false
                        }
                    }
                } else {
                    refreshDriveBackupsWithToken(token, announceConnected = true)
                }
            }
            .onFailure { err ->
                _driveBusy.value = false
                val msg = GoogleDriveHistoryRepository.formatReadableAuthError(err)
                _driveStatusLabel.value = msg
                onBanner(msg)
            }
    }

    fun connectGoogleDrive(activity: Activity?, forceAccountPicker: Boolean = false) {
        runWithDriveToken(activity, forceAccountPicker = forceAccountPicker) { token ->
            refreshDriveBackupsWithToken(token, announceConnected = true)
        }
    }

    fun disconnectGoogleDrive() {
        driveHistoryRepo.disconnectDrive()
        pendingDriveAction = null
        _driveConnected.value = false
        _driveAboutInfo.value = null
        _driveBackups.value = emptyList()
        _driveStatusLabel.value = "Disconnected • Tap Sign in with Google Drive to reconnect"
        onBanner("Google Drive session cleared for this device.")
    }

    private fun runWithDriveToken(
        activity: Activity?,
        forceAccountPicker: Boolean = false,
        block: suspend (String) -> Unit
    ) {
        scope.launch {
            _driveBusy.value = true
            when (val auth = driveHistoryRepo.authorizeDriveAccess(activity, forceAccountPicker = forceAccountPicker)) {
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
                    _driveStatusLabel.value = auth.message
                    onBanner(auth.message)
                }
            }
        }
    }

    fun refreshDriveBackups(activity: Activity?) {
        runWithDriveToken(activity) { token ->
            refreshDriveBackupsWithToken(token, announceConnected = false)
        }
    }

    private fun refreshDriveBackupsWithToken(token: String, announceConnected: Boolean = false) {
        scope.launch {
            _driveBusy.value = true
            var connectedEmail = _preferredDriveEmail.value
            driveHistoryRepo.fetchDriveAboutInfo(token)
                .onSuccess { info ->
                    _driveAboutInfo.value = info
                    if (info.emailAddress.isNotBlank()) connectedEmail = info.emailAddress
                }
                .onFailure { err ->
                    if (err.localizedMessage?.contains("401") == true) {
                        driveHistoryRepo.disconnectDrive()
                        _driveConnected.value = false
                    }
                }
            driveHistoryRepo.listBackups(token)
                .onSuccess { list ->
                    _driveBackups.value = list
                    _driveStatusLabel.value = if (list.isEmpty()) {
                        "Connected • Ready for 1-tap backup"
                    } else {
                        "Connected • ${list.size} backup file(s) synced"
                    }
                    if (announceConnected) {
                        val suffix = if (connectedEmail.isNotBlank()) " as $connectedEmail" else ""
                        onBanner("Google Drive connected$suffix.")
                    }
                }
                .onFailure { err ->
                    if (err.localizedMessage?.contains("401") == true) {
                        driveHistoryRepo.disconnectDrive()
                        _driveConnected.value = false
                    }
                    onBanner(err.localizedMessage ?: "Failed to list Google Drive backups.")
                }
            _driveBusy.value = false
        }
    }

    fun backupHistoryToGoogleDrive(activity: Activity?, exportVisibleCopy: Boolean = false) {
        runWithDriveToken(activity) { token ->
            val convsWithMsgs = firestoreRepo.fetchAllConversationsWithMessagesOnce().getOrElse {
                onBanner("Could not load conversations: ${it.localizedMessage}")
                return@runWithDriveToken
            }
            val memories = firestoreRepo.fetchMemoriesOnce().getOrElse { emptyList() }
            if (convsWithMsgs.isEmpty() && memories.isEmpty()) {
                onBanner("No conversations or memories to back up yet.")
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
                    onBanner(
                        if (exportVisibleCopy) "Exported ${meta.name} to Google Drive."
                        else "History backed up safely to Google Drive AppData."
                    )
                    driveHistoryRepo.fetchDriveAboutInfo(token).onSuccess { _driveAboutInfo.value = it }
                    driveHistoryRepo.listBackups(token).onSuccess { _driveBackups.value = it }
                }
                .onFailure { err ->
                    onBanner(err.localizedMessage ?: "Google Drive backup failed.")
                }
        }
    }

    fun restoreHistoryFromGoogleDrive(activity: Activity?, fileId: String) {
        runWithDriveToken(activity) { token ->
            val snap = driveHistoryRepo.downloadAndParseSnapshot(token, fileId).getOrElse {
                onBanner(it.localizedMessage ?: "Failed to download Drive backup.")
                return@runWithDriveToken
            }
            firestoreRepo.importDriveSnapshot(snap)
                .onSuccess { (convsAdded, memoriesAdded) ->
                    _driveStatusLabel.value = "Restored $convsAdded new conversation(s) & $memoriesAdded memory item(s)"
                    onBanner("Restored $convsAdded conversation(s) and $memoriesAdded memory item(s) from Google Drive.")
                }
                .onFailure { err ->
                    onBanner("Restore merge failed: ${err.localizedMessage}")
                }
        }
    }

    fun deleteDriveBackup(activity: Activity?, fileId: String) {
        runWithDriveToken(activity) { token ->
            driveHistoryRepo.deleteBackupFile(token, fileId)
                .onSuccess {
                    onBanner("Deleted Google Drive backup file.")
                    driveHistoryRepo.listBackups(token).onSuccess { _driveBackups.value = it }
                    driveHistoryRepo.fetchDriveAboutInfo(token).onSuccess { _driveAboutInfo.value = it }
                }
                .onFailure { err ->
                    onBanner(err.localizedMessage ?: "Failed to delete Google Drive backup.")
                }
        }
    }
}
