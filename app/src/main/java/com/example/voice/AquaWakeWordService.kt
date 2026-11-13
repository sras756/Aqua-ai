package com.example.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play-Policy compliant foreground microphone service (`foregroundServiceType="microphone"`)
 * for "Hey Aqua" wake-word detection when explicitly enabled by the user.
 */
class AquaWakeWordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakePhrase: String = "hey aqua"
    private var keepRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        wakePhrase = intent?.getStringExtra(EXTRA_WAKE_PHRASE)?.lowercase()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "hey aqua"

        startForegroundNotification()
        keepRunning = true
        _isRunning.value = true
        scheduleListenCycle(0L)
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aqua Wake-Word Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while listening for '$wakePhrase'"
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AquaWakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aqua AI Wake Word Active")
            .setContentText("Listening for \"$wakePhrase\"")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun scheduleListenCycle(delayMs: Long) {
        if (!keepRunning) return
        handler.postDelayed({
            if (!keepRunning) return@postDelayed
            startRecognizerCycle()
        }, delayMs)
    }

    private fun startRecognizerCycle() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                scheduleListenCycle(800L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase().orEmpty()
                checkPhraseAndTrigger(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase().orEmpty()
                if (!checkPhraseAndTrigger(text)) {
                    scheduleListenCycle(400L)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec.startListening(intent)
    }

    private fun checkPhraseAndTrigger(heard: String): Boolean {
        if (heard.contains(wakePhrase)) {
            val commandAfterWake = heard.substringAfter(wakePhrase).trim()
            _wakeEvents.tryEmit(commandAfterWake)
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_WAKE_COMMAND, commandAfterWake)
            }
            startActivity(launchIntent)
            scheduleListenCycle(2500L)
            return true
        }
        return false
    }

    private fun stopSelfCleanly() {
        keepRunning = false
        _isRunning.value = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        keepRunning = false
        _isRunning.value = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.voice.STOP_WAKE_WORD"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"
        const val EXTRA_WAKE_COMMAND = "extra_wake_command"
        private const val CHANNEL_ID = "aqua_wake_word_channel"
        private const val NOTIFICATION_ID = 4042

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _wakeEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val wakeEvents: SharedFlow<String> = _wakeEvents.asSharedFlow()
    }
}
