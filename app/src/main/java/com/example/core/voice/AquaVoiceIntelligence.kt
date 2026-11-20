package com.example.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class DetectedVoiceLanguage(val bcp47Tag: String, val displayName: String) {
    ENGLISH("en-US", "English"),
    URDU("ur-PK", "Urdu")
}

object AquaVoiceLanguageDetector {

    private val romanUrduUnambiguousMarkers = setOf(
        "assalam", "assalamu", "shukriya", "meherbani", "batao", "btao", "samjhao", "zaroorat"
    )

    private val romanUrduConversationalMarkers = setOf(
        "kya", "kaise", "kaisa", "kaisi", "kaam", "karo", "karna", "karni", "mera", "meri",
        "mere", "tum", "aap", "aaj", "kal", "abhi", "nahi", "nhi", "hain", "hoon", "hai",
        "theek", "acha", "achha", "waqt", "madad", "chahiye", "kahan", "kyun", "bhai", "yaar"
    )

    fun detectLanguage(input: String, fallbackTag: String = "en-US"): DetectedVoiceLanguage {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return if (fallbackTag.startsWith("ur", ignoreCase = true)) {
                DetectedVoiceLanguage.URDU
            } else {
                DetectedVoiceLanguage.ENGLISH
            }
        }

        // Urdu/Arabic/Nastaliq Unicode blocks
        val hasUrduScript = trimmed.any { ch ->
            val code = ch.code
            code in 0x0600..0x06FF ||
                code in 0x0750..0x077F ||
                code in 0xFB50..0xFDFF ||
                code in 0xFE70..0xFEFF
        }
        if (hasUrduScript) return DetectedVoiceLanguage.URDU

        val tokens = trimmed.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9']+"))
            .filter { it.isNotBlank() }
        if (tokens.any { it in romanUrduUnambiguousMarkers }) {
            return DetectedVoiceLanguage.URDU
        }
        val markerHits = tokens.count { it in romanUrduConversationalMarkers }
        if (markerHits >= 2) {
            return DetectedVoiceLanguage.URDU
        }

        return if (fallbackTag.startsWith("ur", ignoreCase = true) && tokens.size <= 1) {
            DetectedVoiceLanguage.URDU
        } else {
            DetectedVoiceLanguage.ENGLISH
        }
    }

    fun normalizeRecognitionTag(configuredTag: String): String {
        val trimmed = configuredTag.trim()
        return when {
            trimmed.startsWith("ur", ignoreCase = true) -> "ur-PK"
            trimmed.isBlank() -> "en-US"
            trimmed.equals("en", ignoreCase = true) -> "en-US"
            else -> trimmed
        }
    }

    fun resolveTtsLocale(languageTag: String, textToSpeak: String? = null): Locale {
        val detected = if (!textToSpeak.isNullOrBlank()) {
            detectLanguage(textToSpeak, fallbackTag = languageTag)
        } else {
            if (languageTag.startsWith("ur", ignoreCase = true)) {
                DetectedVoiceLanguage.URDU
            } else {
                DetectedVoiceLanguage.ENGLISH
            }
        }
        return Locale.forLanguageTag(detected.bcp47Tag)
    }
}

object SpeechErrorMapper {
    fun describe(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission required for voice input. You can type your message directly below."
            SpeechRecognizer.ERROR_AUDIO ->
                "Audio recording error detected. Check microphone availability or use text input."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition network timeout. Check connectivity or type your message."
            SpeechRecognizer.ERROR_NO_MATCH ->
                "Didn't catch that clearly. Please speak again or type below."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "No speech detected. Tap mic to retry."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Voice recognizer is busy. Retrying in a moment."
            SpeechRecognizer.ERROR_SERVER ->
                "Speech service unavailable right now. Use text input as fallback."
            else -> "Speech recognizer reported code $errorCode."
        }
    }

    fun isSilentIgnore(errorCode: Int): Boolean {
        return errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
            errorCode == SpeechRecognizer.ERROR_CLIENT
    }
}

class AquaAudioFocusController(
    context: Context,
    private val onFocusLost: () -> Unit = {}
) {
    private val audioManager: AudioManager? = runCatching {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    private val _hasAudioFocus = MutableStateFlow(false)
    val hasAudioFocus: StateFlow<Boolean> = _hasAudioFocus.asStateFlow()

    private var activeFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _hasAudioFocus.value = false
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                _hasAudioFocus.value = true
            }
        }
    }

    fun requestSpeechFocus(exclusiveForCapture: Boolean): Boolean {
        val manager = audioManager
        if (manager == null) {
            _hasAudioFocus.value = true
            return true
        }
        val focusGain = if (exclusiveForCapture) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { prev ->
                    manager.abandonAudioFocusRequest(prev)
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                activeFocusRequest = req
                manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    focusGain
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)

        _hasAudioFocus.value = granted
        return granted
    }

    fun abandonFocus() {
        val manager = audioManager
        if (manager != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeFocusRequest?.let { req ->
                        manager.abandonAudioFocusRequest(req)
                    }
                    activeFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    manager.abandonAudioFocus(focusChangeListener)
                }
            }
        }
        _hasAudioFocus.value = false
    }
}
