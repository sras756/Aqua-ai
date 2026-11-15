package com.example.voice

import android.Manifest
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

/**
 * Play-Policy compliant foreground microphone service (`foregroundServiceType="microphone"`).
 * Keeps both the Aqua AI app process and microphone input active when the app is minimized
 * or the screen is off, supporting both continuous wake-word listening and active Live/Mic keep-alive.
 */
class AquaWakeWordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakePhrase: String = DEFAULT_WAKE_PHRASE
    private var languageTag: String = DEFAULT_LANGUAGE
    private var keepRunning = false
    private var externalMicKeepAliveOnly = false
    private var lastTriggerTimeMs = 0L
    private var lastTriggerText = ""
    private var lastStatusLine = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                saveBackgroundConfig(this, enabled = false)
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                _isPaused.value = true
                destroyRecognizerOnMain()
                if (_isRunning.value) {
                    updateForegroundNotification("Paused — tap Resume to listen for \"$wakePhrase\"")
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                _isPaused.value = false
                externalMicKeepAliveOnly = false
                if (!hasMicPermission(this)) {
                    reportErrorAndStop("Microphone permission (RECORD_AUDIO) is required for background mic.")
                    return START_NOT_STICKY
                }
                startForegroundWithMicType("Listening for \"$wakePhrase\"")
                keepRunning = true
                _isRunning.value = true
                scheduleListenCycle(150L)
                return START_STICKY
            }
            ACTION_ENTER_EXTERNAL_MIC_MODE -> {
                externalMicKeepAliveOnly = true
                destroyRecognizerOnMain()
                if (_isRunning.value) {
                    updateForegroundNotification("Live microphone active in background")
                }
                return START_STICKY
            }
            ACTION_EXIT_EXTERNAL_MIC_MODE -> {
                externalMicKeepAliveOnly = false
                if (_isRunning.value && !_isPaused.value && keepRunning) {
                    updateForegroundNotification("Listening for \"$wakePhrase\"")
                    scheduleListenCycle(350L)
                }
                return START_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS_TEXT).orEmpty().trim()
                if (status.isNotEmpty() && _isRunning.value) {
                    lastStatusLine = status
                    updateForegroundNotification(status)
                }
                return START_STICKY
            }
        }

        if (!hasMicPermission(this)) {
            reportErrorAndStop("Microphone permission (RECORD_AUDIO) is required before starting background mic.")
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val requestedPhrase = intent?.getStringExtra(EXTRA_WAKE_PHRASE)?.lowercase()?.trim()
        val requestedLang = intent?.getStringExtra(EXTRA_LANGUAGE_TAG)?.trim()
        val keepAliveOnly = intent?.getBooleanExtra(EXTRA_KEEP_ALIVE_ONLY, false) ?: false

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

        externalMicKeepAliveOnly = keepAliveOnly
        _isPaused.value = false
        _serviceError.value = null
        keepRunning = true

        val initialText = if (keepAliveOnly) {
            "Live microphone active in background"
        } else {
            "Running in background • Listening for \"$wakePhrase\""
        }
        val startedFg = startForegroundWithMicType(initialText)
        if (!startedFg) {
            return START_NOT_STICKY
        }

        _isRunning.value = true
        if (!keepAliveOnly) {
            scheduleListenCycle(0L)
        }
        return START_STICKY
    }

    private fun startForegroundWithMicType(contentText: String): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val notification = buildNotification(contentText)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting microphone foreground service", e)
            reportErrorAndStop("Microphone foreground permission denied by Android.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground service", e)
            reportErrorAndStop(e.localizedMessage ?: "Failed to start background microphone service.")
            false
        }
    }

    private fun updateForegroundNotification(contentText: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, buildNotification(contentText))
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aqua Background App & Mic",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aqua AI active and listening in the background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AquaWakeWordService::class.java).apply {
                action = if (_isPaused.value) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AquaWakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            externalMicKeepAliveOnly -> "Aqua AI Background Mic Active"
            _isPaused.value -> "Aqua AI Background Mic Paused"
            else -> "Aqua AI Running in Background"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                if (_isPaused.value) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (_isPaused.value) "Resume Mic" else "Pause Mic",
                pauseOrResumeIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun scheduleListenCycle(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        if (!keepRunning || _isPaused.value || externalMicKeepAliveOnly) return
        handler.postDelayed({
            if (!keepRunning || _isPaused.value || externalMicKeepAliveOnly) return@postDelayed
            startRecognizerCycle()
        }, delayMs)
    }

    private fun startRecognizerCycle() {
        if (!hasMicPermission(this)) {
            reportErrorAndStop("Microphone permission revoked.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _serviceError.value = "On-device SpeechRecognizer unavailable; keeping background process & Live mic keep-alive active."
            updateForegroundNotification("App running in background (device SpeechRecognizer unavailable)")
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
                        reportErrorAndStop("Microphone permission missing for background listening.")
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
                        // Normal silence / no speech timeout
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

        val displayCmd = commandAfterWake.ifBlank { "Listening for command..." }
        updateForegroundNotification("Heard: \"$displayCmd\"")
        _wakeEvents.tryEmit(commandAfterWake)

        // Best-effort bring-to-front only if permitted; background ViewModel still executes & speaks reply if OS blocks BAL
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

    private fun reportErrorAndStop(message: String) {
        _serviceError.value = message
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        keepRunning = false
        externalMicKeepAliveOnly = false
        _isRunning.value = false
        _isPaused.value = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizerOnMain()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        keepRunning = false
        externalMicKeepAliveOnly = false
        _isRunning.value = false
        _isPaused.value = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizerOnMain()
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

        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"
        const val EXTRA_LANGUAGE_TAG = "extra_language_tag"
        const val EXTRA_WAKE_COMMAND = "extra_wake_command"
        const val EXTRA_KEEP_ALIVE_ONLY = "extra_keep_alive_only"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        private const val PREFS_NAME = "aqua_bg_mic_prefs"
        private const val KEY_BG_ENABLED = "bg_mic_enabled"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
        private const val KEY_LANGUAGE_TAG = "language_tag"

        private const val DEFAULT_WAKE_PHRASE = "hey aqua"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val CHANNEL_ID = "aqua_wake_word_channel"
        private const val NOTIFICATION_ID = 4042

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

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

        fun isSavedBackgroundEnabled(context: Context): Boolean {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_BG_ENABLED, false)
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

        fun stop(context: Context) {
            saveBackgroundConfig(context, enabled = false)
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
