package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Play-Policy and Android 14/15/16 compliant unified Foreground Service
 * (`foregroundServiceType="microphone|dataSync"`).
 *
 * Ensures both:
 * 1. Aqua AI voice conversations (wake-word listening, voice capture, Gemini Live Voice, TTS)
 * 2. Background automation tasks (Autonomous Routines, multi-step Gemini + tool chains)
 * remain active and prioritized by the Android OS with an immediate persistent notification.
 */
class AquaWakeWordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var wakePhrase: String = DEFAULT_WAKE_PHRASE
    private var languageTag: String = DEFAULT_LANGUAGE
    private var micModeActive = false
    private var automationKeepAliveActive = false
    private var externalMicKeepAliveOnly = false
    private var lastTriggerTimeMs = 0L
    private var lastTriggerText = ""
    private var lastStatusLine = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Sticky null-intent OS restart: restore saved Voice & Automation modes safely
        if (intent == null) {
            val savedMic = isSavedBackgroundEnabled(this) && hasMicPermission(this)
            val savedAutomation = isSavedAutomationKeepAliveEnabled(this)
            if (!savedMic && !savedAutomation) {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            wakePhrase = prefs.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE)
                ?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WAKE_PHRASE
            languageTag = prefs.getString(KEY_LANGUAGE_TAG, DEFAULT_LANGUAGE)
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE
            micModeActive = savedMic
            automationKeepAliveActive = savedAutomation
            _isAutomationKeepAliveActive.value = savedAutomation
            externalMicKeepAliveOnly = false
            _isPaused.value = false
            if (!promoteToForeground()) return START_NOT_STICKY
            _isRunning.value = true
            if (micModeActive) {
                scheduleListenCycle(150L)
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                saveBackgroundConfig(this, enabled = false)
                saveAutomationKeepAliveConfig(this, enabled = false)
                activeTaskRegistry.clear()
                _activeAutomationTasks.value = emptyList()
                _activeTaskStep.value = ""
                stopSelfCleanly()
                return START_NOT_STICKY
            }

            ACTION_STOP_AUTOMATION_KEEP_ALIVE -> {
                automationKeepAliveActive = false
                _isAutomationKeepAliveActive.value = false
                saveAutomationKeepAliveConfig(this, enabled = false)
                if (!micModeActive && activeTaskRegistry.isEmpty()) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                if (!promoteToForeground()) return START_NOT_STICKY
                refreshWakeLockState()
                return START_STICKY
            }

            ACTION_PAUSE -> {
                _isPaused.value = true
                destroyRecognizerOnMain()
                if (_isRunning.value) {
                    updateForegroundNotification()
                }
                return START_STICKY
            }

            ACTION_RESUME -> {
                if (!hasMicPermission(this)) {
                    reportError("Microphone permission (RECORD_AUDIO) is required to resume voice listener.")
                    if (!automationKeepAliveActive && activeTaskRegistry.isEmpty()) {
                        stopSelfCleanly()
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }
                micModeActive = true
                externalMicKeepAliveOnly = false
                _isPaused.value = false
                if (!promoteToForeground()) return START_NOT_STICKY
                _isRunning.value = true
                scheduleListenCycle(150L)
                return START_STICKY
            }

            ACTION_ENTER_EXTERNAL_MIC_MODE -> {
                if (hasMicPermission(this)) {
                    micModeActive = true
                }
                externalMicKeepAliveOnly = true
                destroyRecognizerOnMain()
                if (_isRunning.value) {
                    promoteToForeground()
                }
                return START_STICKY
            }

            ACTION_EXIT_EXTERNAL_MIC_MODE -> {
                externalMicKeepAliveOnly = false
                val savedMic = isSavedBackgroundEnabled(this) && hasMicPermission(this)
                micModeActive = savedMic
                if (!micModeActive && !automationKeepAliveActive && activeTaskRegistry.isEmpty()) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                if (_isRunning.value) {
                    promoteToForeground()
                    if (micModeActive && !_isPaused.value) {
                        scheduleListenCycle(350L)
                    }
                }
                return START_STICKY
            }

            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS_TEXT).orEmpty().trim()
                if (status.isNotEmpty() && _isRunning.value) {
                    lastStatusLine = status
                    updateForegroundNotification()
                }
                return START_STICKY
            }

            ACTION_START_AUTOMATION_KEEP_ALIVE -> {
                automationKeepAliveActive = true
                _isAutomationKeepAliveActive.value = true
                saveAutomationKeepAliveConfig(this, enabled = true)
                micModeActive = micModeActive || (isSavedBackgroundEnabled(this) && hasMicPermission(this))
                _serviceError.value = null
                if (!promoteToForeground()) return START_NOT_STICKY
                _isRunning.value = true
                if (micModeActive && !_isPaused.value && !externalMicKeepAliveOnly) {
                    scheduleListenCycle(150L)
                }
                return START_STICKY
            }

            ACTION_SYNC_AUTOMATION_TASKS -> {
                val hasActiveTasks = activeTaskRegistry.isNotEmpty()
                automationKeepAliveActive = automationKeepAliveActive || isSavedAutomationKeepAliveEnabled(this)
                _isAutomationKeepAliveActive.value = automationKeepAliveActive
                micModeActive = micModeActive || (isSavedBackgroundEnabled(this) && hasMicPermission(this))
                if (!hasActiveTasks && !automationKeepAliveActive && !micModeActive) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                _serviceError.value = null
                if (!promoteToForeground()) return START_NOT_STICKY
                _isRunning.value = true
                refreshWakeLockState()
                return START_STICKY
            }
        }

        // Default ACTION_START (Voice Conversation / Wake-Word / Live Keep-Alive)
        if (!hasMicPermission(this)) {
            if (automationKeepAliveActive || activeTaskRegistry.isNotEmpty()) {
                reportError("Microphone permission (RECORD_AUDIO) required for voice mode; automation service remains active.")
                return START_STICKY
            }
            reportError("Microphone permission (RECORD_AUDIO) is required before starting voice foreground service.")
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val requestedPhrase = intent.getStringExtra(EXTRA_WAKE_PHRASE)?.lowercase()?.trim()
        val requestedLang = intent.getStringExtra(EXTRA_LANGUAGE_TAG)?.trim()
        val keepAliveOnly = intent.getBooleanExtra(EXTRA_KEEP_ALIVE_ONLY, false)

        wakePhrase = requestedPhrase?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE)?.lowercase()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_WAKE_PHRASE
        languageTag = requestedLang?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_LANGUAGE_TAG, DEFAULT_LANGUAGE)?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LANGUAGE

        if (!keepAliveOnly) {
            saveBackgroundConfig(this, enabled = true, wakePhrase = wakePhrase, languageTag = languageTag)
        }

        micModeActive = true
        automationKeepAliveActive = automationKeepAliveActive || isSavedAutomationKeepAliveEnabled(this)
        _isAutomationKeepAliveActive.value = automationKeepAliveActive
        externalMicKeepAliveOnly = keepAliveOnly
        _isPaused.value = false
        _serviceError.value = null

        if (!promoteToForeground()) {
            return START_NOT_STICKY
        }

        _isRunning.value = true
        refreshWakeLockState()
        if (!keepAliveOnly) {
            scheduleListenCycle(0L)
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ (API 35+) enforces a rolling 6-hour limit on dataSync foreground services.
        // Immediately release dataSync type or stop cleanly to avoid RemoteServiceException.
        Log.w(TAG, "Foreground service timeout reached for fgsType=$fgsType (startId=$startId)")
        automationKeepAliveActive = false
        _isAutomationKeepAliveActive.value = false
        saveAutomationKeepAliveConfig(this, enabled = false)
        activeTaskRegistry.clear()
        _activeAutomationTasks.value = emptyList()
        _activeTaskStep.value = ""
        releaseWakeLock()

        if (micModeActive && hasMicPermission(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            val downgraded = runCatching {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                true
            }.getOrDefault(false)
            if (downgraded) {
                _serviceError.value = "Automation dataSync daily OS quota reached; voice mode remains prioritized."
                return
            }
        }
        _serviceError.value = "Background automation OS time limit reached; open Aqua AI to reset priority window."
        stopSelfCleanly()
    }

    private fun computeActiveForegroundType(): Int {
        var types = 0
        if (micModeActive && hasMicPermission(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (automationKeepAliveActive || activeTaskRegistry.isNotEmpty()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (types == 0) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return types
    }

    private fun promoteToForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val notification = buildNotification()
        val fgsType = computeActiveForegroundType()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service (type=$fgsType)", e)
            // If microphone failed in edge state, retry with dataSync only when automation is active
            if ((fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 &&
                (automationKeepAliveActive || activeTaskRegistry.isNotEmpty()) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                micModeActive = false
                return runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    true
                }.onFailure {
                    reportError("Foreground permission denied by Android OS.")
                    stopSelfCleanly()
                }.getOrDefault(false)
            }
            reportError("Foreground microphone/service permission denied by Android.")
            stopSelfCleanly()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unable to promote to foreground service", e)
            reportError(e.localizedMessage ?: "Failed to start Aqua priority foreground service.")
            stopSelfCleanly()
            false
        }
    }

    private fun updateForegroundNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aqua AI Voice & Automation Priority",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification keeping Aqua AI voice conversations and automation tasks active in the background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AquaWakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tasks = _activeAutomationTasks.value
        val step = _activeTaskStep.value

        val voiceStatus = when {
            externalMicKeepAliveOnly -> "Voice: Live conversation active"
            micModeActive && _isPaused.value -> "Voice: Mic paused"
            micModeActive -> "Voice: Listening for \"$wakePhrase\""
            else -> "Voice: Standby"
        }

        val automationStatus = when {
            tasks.isNotEmpty() && step.isNotBlank() ->
                "Automation (${tasks.size}): ${tasks.first()} • $step"
            tasks.isNotEmpty() ->
                "Automation (${tasks.size} active): ${tasks.joinToString(", ").take(90)}"
            automationKeepAliveActive ->
                "Automation: Ready & OS-prioritized"
            lastStatusLine.isNotBlank() ->
                lastStatusLine
            else ->
                "Automation: On-demand task protection"
        }

        val title = when {
            micModeActive && (automationKeepAliveActive || tasks.isNotEmpty()) ->
                "Aqua AI Voice & Automation Active"
            tasks.isNotEmpty() ->
                "Aqua AI Running Background Task"
            automationKeepAliveActive ->
                "Aqua AI Automation Service Active"
            externalMicKeepAliveOnly ->
                "Aqua AI Live Voice Active"
            _isPaused.value ->
                "Aqua AI Service Active (Mic Paused)"
            else ->
                "Aqua AI Voice & Background Active"
        }

        val summary = "$voiceStatus\n$automationStatus"
        _foregroundStatusSummary.value = "$voiceStatus • $automationStatus"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$voiceStatus • $automationStatus")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)

        if (micModeActive && !externalMicKeepAliveOnly) {
            val pauseOrResumeIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, AquaWakeWordService::class.java).apply {
                    action = if (_isPaused.value) ACTION_RESUME else ACTION_PAUSE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                if (_isPaused.value) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (_isPaused.value) "Resume Mic" else "Pause Mic",
                pauseOrResumeIntent
            )
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
        return builder.build()
    }

    @SuppressLint("WakelockTimeout")
    private fun refreshWakeLockState() {
        val shouldHoldWakeLock = externalMicKeepAliveOnly || activeTaskRegistry.isNotEmpty()
        if (shouldHoldWakeLock) {
            if (wakeLock?.isHeld != true) {
                runCatching {
                    val pm = getSystemService(PowerManager::class.java)
                    val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::PriorityTaskLock")
                    lock?.setReferenceCounted(false)
                    // Bounded 10-minute lease guarantees CPU priority for live voice & automation tasks without battery drain leaks
                    lock?.acquire(10 * 60 * 1000L)
                    wakeLock = lock
                }
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun scheduleListenCycle(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        if (!micModeActive || _isPaused.value || externalMicKeepAliveOnly) return
        handler.postDelayed({
            if (!micModeActive || _isPaused.value || externalMicKeepAliveOnly) return@postDelayed
            startRecognizerCycle()
        }, delayMs)
    }

    private fun startRecognizerCycle() {
        if (!hasMicPermission(this)) {
            reportError("Microphone permission revoked.")
            micModeActive = false
            if (!automationKeepAliveActive && activeTaskRegistry.isEmpty()) {
                stopSelfCleanly()
            } else {
                promoteToForeground()
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _serviceError.value = "On-device SpeechRecognizer unavailable; keeping foreground process & automation priority active."
            lastStatusLine = "Device SpeechRecognizer unavailable"
            updateForegroundNotification()
            return
        }
        destroyRecognizerOnMain()
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull() ?: return
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        reportError("Microphone permission missing for background listening.")
                        micModeActive = false
                        if (!automationKeepAliveActive && activeTaskRegistry.isEmpty()) {
                            stopSelfCleanly()
                        } else {
                            promoteToForeground()
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        scheduleListenCycle(2500L)
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        scheduleListenCycle(4000L)
                    }
                    else -> {
                        scheduleListenCycle(650L)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase().orEmpty()
                if (text.contains(wakePhrase)) {
                    val cmd = text.substringAfter(wakePhrase).trim()
                    if (cmd.length >= 4) {
                        checkPhraseAndTrigger(text)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase().orEmpty()
                if (!checkPhraseAndTrigger(text)) {
                    scheduleListenCycle(450L)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { rec.startListening(intent) }.onFailure {
            scheduleListenCycle(2500L)
        }
    }

    private fun checkPhraseAndTrigger(heard: String): Boolean {
        if (!heard.contains(wakePhrase)) return false
        val commandAfterWake = heard.substringAfter(wakePhrase).trim()
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < 3000L && commandAfterWake == lastTriggerText) {
            return true
        }
        lastTriggerTimeMs = now
        lastTriggerText = commandAfterWake

        lastStatusLine = "Heard: \"${commandAfterWake.ifBlank { "Listening..." }}\""
        updateForegroundNotification()
        _wakeEvents.tryEmit(commandAfterWake)

        runCatching {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_WAKE_COMMAND, commandAfterWake)
            }
            startActivity(launchIntent)
        }
        scheduleListenCycle(2500L)
        return true
    }

    private fun destroyRecognizerOnMain() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun reportError(message: String) {
        _serviceError.value = message
    }

    private fun stopSelfCleanly() {
        micModeActive = false
        automationKeepAliveActive = false
        externalMicKeepAliveOnly = false
        _isRunning.value = false
        _isPaused.value = false
        _isAutomationKeepAliveActive.value = false
        _foregroundStatusSummary.value = ""
        handler.removeCallbacksAndMessages(null)
        destroyRecognizerOnMain()
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        micModeActive = false
        automationKeepAliveActive = false
        externalMicKeepAliveOnly = false
        _isRunning.value = false
        _isPaused.value = false
        _isAutomationKeepAliveActive.value = false
        _foregroundStatusSummary.value = ""
        handler.removeCallbacksAndMessages(null)
        destroyRecognizerOnMain()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AquaWakeWordService"
        const val ACTION_START = "com.example.voice.START_WAKE_WORD"
        const val ACTION_STOP = "com.example.voice.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.example.voice.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.example.voice.RESUME_WAKE_WORD"
        const val ACTION_ENTER_EXTERNAL_MIC_MODE = "com.example.voice.ENTER_EXTERNAL_MIC"
        const val ACTION_EXIT_EXTERNAL_MIC_MODE = "com.example.voice.EXIT_EXTERNAL_MIC"
        const val ACTION_UPDATE_STATUS = "com.example.voice.UPDATE_STATUS"
        const val ACTION_START_AUTOMATION_KEEP_ALIVE = "com.example.voice.START_AUTOMATION_KEEP_ALIVE"
        const val ACTION_STOP_AUTOMATION_KEEP_ALIVE = "com.example.voice.STOP_AUTOMATION_KEEP_ALIVE"
        const val ACTION_SYNC_AUTOMATION_TASKS = "com.example.voice.SYNC_AUTOMATION_TASKS"

        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"
        const val EXTRA_LANGUAGE_TAG = "extra_language_tag"
        const val EXTRA_WAKE_COMMAND = "extra_wake_command"
        const val EXTRA_KEEP_ALIVE_ONLY = "extra_keep_alive_only"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        private const val PREFS_NAME = "aqua_bg_mic_prefs"
        private const val KEY_BG_ENABLED = "bg_mic_enabled"
        private const val KEY_AUTOMATION_ENABLED = "automation_keep_alive_enabled"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
        private const val KEY_LANGUAGE_TAG = "language_tag"

        private const val DEFAULT_WAKE_PHRASE = "hey aqua"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val CHANNEL_ID = "aqua_wake_word_channel"
        private const val NOTIFICATION_ID = 4042

        private val activeTaskRegistry = ConcurrentHashMap<String, String>()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private val _isAutomationKeepAliveActive = MutableStateFlow(false)
        val isAutomationKeepAliveActive: StateFlow<Boolean> = _isAutomationKeepAliveActive.asStateFlow()

        private val _activeAutomationTasks = MutableStateFlow<List<String>>(emptyList())
        val activeAutomationTasks: StateFlow<List<String>> = _activeAutomationTasks.asStateFlow()

        private val _activeTaskStep = MutableStateFlow("")
        val activeTaskStep: StateFlow<String> = _activeTaskStep.asStateFlow()

        private val _foregroundStatusSummary = MutableStateFlow("")
        val foregroundStatusSummary: StateFlow<String> = _foregroundStatusSummary.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

        private val _wakeEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val wakeEvents: SharedFlow<String> = _wakeEvents.asSharedFlow()

        fun hasMicPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun saveBackgroundConfig(
            context: Context,
            enabled: Boolean,
            wakePhrase: String = DEFAULT_WAKE_PHRASE,
            languageTag: String = DEFAULT_LANGUAGE
        ) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BG_ENABLED, enabled)
                .putString(KEY_WAKE_PHRASE, wakePhrase.lowercase().trim().ifEmpty { DEFAULT_WAKE_PHRASE })
                .putString(KEY_LANGUAGE_TAG, languageTag.ifBlank { DEFAULT_LANGUAGE })
                .apply()
        }

        fun saveAutomationKeepAliveConfig(context: Context, enabled: Boolean) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTOMATION_ENABLED, enabled)
                .apply()
        }

        fun isSavedBackgroundEnabled(context: Context): Boolean {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_BG_ENABLED, false)
        }

        fun isSavedAutomationKeepAliveEnabled(context: Context): Boolean {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_AUTOMATION_ENABLED, false)
        }

        fun savedWakePhrase(context: Context): String {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE)
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_WAKE_PHRASE
        }

        fun start(
            context: Context,
            wakePhrase: String = DEFAULT_WAKE_PHRASE,
            languageTag: String = DEFAULT_LANGUAGE,
            keepAliveOnly: Boolean = false
        ): Boolean {
            if (!hasMicPermission(context)) {
                _serviceError.value = "Grant Microphone permission to keep Aqua AI and mic active in background."
                return false
            }
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WAKE_PHRASE, wakePhrase)
                putExtra(EXTRA_LANGUAGE_TAG, languageTag)
                putExtra(EXTRA_KEEP_ALIVE_ONLY, keepAliveOnly)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure { err ->
                _serviceError.value = err.localizedMessage ?: "Android blocked starting background microphone."
            }.getOrDefault(false)
        }

        fun startAutomationKeepAlive(context: Context): Boolean {
            saveAutomationKeepAliveConfig(context, enabled = true)
            _isAutomationKeepAliveActive.value = true
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_START_AUTOMATION_KEEP_ALIVE
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure { err ->
                _serviceError.value = err.localizedMessage ?: "Android blocked starting automation foreground service."
            }.getOrDefault(false)
        }

        fun stopAutomationKeepAlive(context: Context) {
            saveAutomationKeepAliveConfig(context, enabled = false)
            _isAutomationKeepAliveActive.value = false
            if (!_isRunning.value) return
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_STOP_AUTOMATION_KEEP_ALIVE
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Registers an active automation task or AI turn so the Android OS elevates and keeps
         * the process alive via a `dataSync` foreground service while executing tools/network calls.
         */
        fun beginAutomationTask(context: Context, taskLabel: String): String {
            val taskId = UUID.randomUUID().toString()
            val cleanLabel = taskLabel.trim().ifEmpty { "Aqua AI Task" }.take(60)
            activeTaskRegistry[taskId] = cleanLabel
            _activeAutomationTasks.value = activeTaskRegistry.values.toList()
            _activeTaskStep.value = "Initializing..."

            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_SYNC_AUTOMATION_TASKS
            }
            runCatching {
                if (_isRunning.value) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }
            return taskId
        }

        fun updateAutomationTaskStep(context: Context, stepLabel: String) {
            _activeTaskStep.value = stepLabel.trim().take(80)
            if (_isRunning.value) {
                updateStatusNotification(context, stepLabel)
            }
        }

        fun endAutomationTask(context: Context, taskId: String, completionSummary: String = "") {
            activeTaskRegistry.remove(taskId)
            val remaining = activeTaskRegistry.values.toList()
            _activeAutomationTasks.value = remaining
            if (remaining.isEmpty()) {
                _activeTaskStep.value = ""
            }
            if (!_isRunning.value) return

            if (completionSummary.isNotBlank()) {
                updateStatusNotification(context, completionSummary)
            }
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_SYNC_AUTOMATION_TASKS
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            saveBackgroundConfig(context, enabled = false)
            saveAutomationKeepAliveConfig(context, enabled = false)
            _isAutomationKeepAliveActive.value = false
            activeTaskRegistry.clear()
            _activeAutomationTasks.value = emptyList()
            _activeTaskStep.value = ""
            if (!_isRunning.value) return
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun setPaused(context: Context, paused: Boolean) {
            if (!_isRunning.value) return
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = if (paused) ACTION_PAUSE else ACTION_RESUME
            }
            runCatching { context.startService(intent) }
        }

        fun setExternalMicActive(context: Context, active: Boolean) {
            if (!_isRunning.value) return
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = if (active) ACTION_ENTER_EXTERNAL_MIC_MODE else ACTION_EXIT_EXTERNAL_MIC_MODE
            }
            runCatching { context.startService(intent) }
        }

        fun updateStatusNotification(context: Context, statusText: String) {
            if (!_isRunning.value || statusText.isBlank()) return
            val intent = Intent(context, AquaWakeWordService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS_TEXT, statusText.take(180))
            }
            runCatching { context.startService(intent) }
        }

        fun clearServiceError() {
            _serviceError.value = null
        }
    }
}
