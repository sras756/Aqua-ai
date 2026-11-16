package com.example.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.ai.GeminiProvider
import com.example.ai.ToolCallingEngine
import com.example.data.local.ElevenLabsConfig
import com.example.data.local.SecureConfigStore
import com.example.data.model.AgentStepTrace
import com.example.data.model.PendingAuthorization
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale
import java.util.UUID

class VoiceAssistantManager(
    private val context: Context,
    private val geminiProvider: GeminiProvider,
    private val toolCallingEngine: ToolCallingEngine,
    private val secureConfigStore: SecureConfigStore,
    val elevenLabsClient: ElevenLabsClient = ElevenLabsClient()
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentAudioTrack: AudioTrack? = null
    private var currentMediaPlayer: MediaPlayer? = null
    @OptIn(PublicPreviewAPI::class)
    private var liveSession: LiveSession? = null
    private var liveJob: Job? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _waveformBars = MutableStateFlow(FloatArray(24) { 0.12f })
    val waveformBars: StateFlow<FloatArray> = _waveformBars.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val _activeVoiceEngine = MutableStateFlow("Android TTS")
    val activeVoiceEngine: StateFlow<String> = _activeVoiceEngine.asStateFlow()

    init {
        initAndroidTts()
    }

    private fun initAndroidTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        _waveformBars.value = FloatArray(24) { 0.12f }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
            }
        }
    }

    fun startSpeechRecognition(
        languageTag: String,
        onFinalResult: (String) -> Unit
    ) {
        // Voice interruption: immediately stop any ongoing speech
        stopSpeaking()
        _voiceError.value = null

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _voiceError.value = "Speech recognition service is unavailable on this device."
                return@post
            }
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.1f, 1.0f)
                    val current = _waveformBars.value.copyOf()
                    for (i in 0 until current.size - 1) {
                        current[i] = current[i + 1]
                    }
                    current[current.size - 1] = normalized
                    _waveformBars.value = current
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _waveformBars.value = FloatArray(24) { 0.12f }
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_CLIENT) {
                        _voiceError.value = "Speech error code $error"
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    _waveformBars.value = FloatArray(24) { 0.12f }
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    _partialTranscript.value = ""
                    if (text.isNotEmpty()) {
                        onFinalResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    _partialTranscript.value = partial
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag.ifBlank { "en-US" })
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            speechRecognizer?.stopListening()
            _isListening.value = false
            _waveformBars.value = FloatArray(24) { 0.12f }
        }
    }

    fun speak(
        text: String,
        settings: UserSettings,
        scope: CoroutineScope,
        elevenLabsConfig: ElevenLabsConfig = ElevenLabsConfig()
    ) {
        val clean = text.replace(Regex("[*_`#>]"), " ").trim()
        if (clean.isEmpty()) return
        stopSpeaking()

        val elevenKey = secureConfigStore.getElevenLabsApiKey()
        val elevenVoiceId = secureConfigStore.getElevenLabsVoiceId()

        if (elevenLabsConfig.enabled && !elevenKey.isNullOrBlank() && elevenVoiceId.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                _isSpeaking.value = true
                _activeVoiceEngine.value = "ElevenLabs (${elevenLabsConfig.voiceName.ifBlank { elevenVoiceId.take(8) }})"
                val outFile = File(context.cacheDir, "aqua_eleven_${System.currentTimeMillis()}.mp3")
                val res = elevenLabsClient.synthesizeSpeechToFile(
                    apiKey = elevenKey,
                    voiceId = elevenVoiceId,
                    text = clean,
                    modelId = elevenLabsConfig.modelId,
                    stability = elevenLabsConfig.stability,
                    similarityBoost = elevenLabsConfig.similarityBoost,
                    outputFile = outFile
                )
                res.onSuccess { mp3File ->
                    playMp3File(mp3File)
                }.onFailure { err ->
                    Log.w(TAG, "ElevenLabs TTS failed, falling back: ${err.message}")
                    _voiceError.value = err.message
                    fallbackSpeakAfterElevenLabs(clean, settings)
                }
            }
            return
        }

        if (settings.useGeminiTts) {
            scope.launch(Dispatchers.IO) {
                _isSpeaking.value = true
                _activeVoiceEngine.value = "Gemini Native TTS"
                val audioRes = geminiProvider.synthesizeGeminiTtsAudio(
                    text = clean,
                    languageCode = settings.voiceLanguage
                )
                audioRes.onSuccess { pcmBytes ->
                    playPcmAudio(pcmBytes)
                }.onFailure { err ->
                    Log.w(TAG, "Gemini TTS fallback to Android TTS: ${err.message}")
                    _activeVoiceEngine.value = "Android TTS"
                    speakWithAndroidTts(clean, settings)
                }
            }
        } else {
            _activeVoiceEngine.value = "Android TTS"
            speakWithAndroidTts(clean, settings)
        }
    }

    suspend fun testElevenLabsSpeech(
        sampleText: String,
        apiKey: String,
        voiceId: String,
        elevenLabsConfig: ElevenLabsConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        stopSpeaking()
        _isSpeaking.value = true
        val outFile = File(context.cacheDir, "aqua_eleven_test_${System.currentTimeMillis()}.mp3")
        val res = elevenLabsClient.synthesizeSpeechToFile(
            apiKey = apiKey,
            voiceId = voiceId,
            text = sampleText,
            modelId = elevenLabsConfig.modelId,
            stability = elevenLabsConfig.stability,
            similarityBoost = elevenLabsConfig.similarityBoost,
            outputFile = outFile
        )
        res.fold(
            onSuccess = { file ->
                _activeVoiceEngine.value = "ElevenLabs (${elevenLabsConfig.voiceName.ifBlank { voiceId.take(8) }})"
                playMp3File(file)
                Result.success("Playing ElevenLabs sample audio.")
            },
            onFailure = { err ->
                _isSpeaking.value = false
                Result.failure(err)
            }
        )
    }

    private suspend fun fallbackSpeakAfterElevenLabs(clean: String, settings: UserSettings) {
        if (settings.useGeminiTts) {
            _activeVoiceEngine.value = "Gemini Native TTS"
            val audioRes = geminiProvider.synthesizeGeminiTtsAudio(
                text = clean,
                languageCode = settings.voiceLanguage
            )
            audioRes.onSuccess { pcmBytes ->
                playPcmAudio(pcmBytes)
                return
            }
        }
        _activeVoiceEngine.value = "Android TTS"
        speakWithAndroidTts(clean, settings)
    }

    private fun playMp3File(file: File) {
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (currentMediaPlayer === mp) currentMediaPlayer = null
                    _isSpeaking.value = false
                    runCatching { file.delete() }
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (currentMediaPlayer === mp) currentMediaPlayer = null
                    _isSpeaking.value = false
                    runCatching { file.delete() }
                    true
                }
                prepare()
                start()
            }
            currentMediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs MP3 playback failed", e)
            _isSpeaking.value = false
            runCatching { file.delete() }
        }
    }

    private fun speakWithAndroidTts(text: String, settings: UserSettings) {
        if (!ttsReady) {
            _isSpeaking.value = false
            return
        }
        val locale = Locale.forLanguageTag(settings.voiceLanguage.ifBlank { "en-US" })
        tts?.language = locale
        tts?.setSpeechRate(settings.voiceSpeed.toFloat().coerceIn(0.5f, 2.0f))
        tts?.setPitch(settings.voicePitch.toFloat().coerceIn(0.5f, 2.0f))
        _isSpeaking.value = true
        tts?.speak(text.take(2000), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun playPcmAudio(pcmBytes: ByteArray) {
        try {
            val sampleRate = 24000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcmBytes.size.coerceAtMost(32768)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentAudioTrack = track
            track.play()
            var offset = 0
            while (offset < pcmBytes.size && _isSpeaking.value) {
                val chunk = minOf(4096, pcmBytes.size - offset)
                track.write(pcmBytes, offset, chunk)
                offset += chunk
            }
            track.stop()
            track.release()
            currentAudioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback failed", e)
        } finally {
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        _isSpeaking.value = false
        tts?.stop()
        try {
            currentMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) {
        }
        currentMediaPlayer = null
        try {
            currentAudioTrack?.pause()
            currentAudioTrack?.flush()
            currentAudioTrack?.release()
        } catch (_: Exception) {
        }
        currentAudioTrack = null
    }

    @OptIn(PublicPreviewAPI::class)
    @SuppressLint("MissingPermission")
    fun startLiveConversation(
        scope: CoroutineScope,
        systemInstruction: String,
        settings: UserSettings,
        activeWallet: WalletConfig?,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onTrace: (AgentStepTrace) -> Unit,
        onStatusChanged: (String) -> Unit
    ) {
        stopSpeaking()
        stopLiveConversation()
        _voiceError.value = null

        liveJob = scope.launch(Dispatchers.IO) {
            try {
                onStatusChanged("Connecting to Gemini Live (${UserSettings.MODEL_LIVE})...")
                val liveModel = geminiProvider.buildLiveModel(systemInstruction)
                val session = liveModel.connect()
                liveSession = session
                _isLiveSessionActive.value = true
                onStatusChanged("Live Voice Active — Speak naturally with Aqua")

                session.startAudioConversation { functionCall: FunctionCallPart ->
                    runBlocking {
                        try {
                            val result = toolCallingEngine.executeFunctionCall(
                                call = functionCall,
                                settings = settings,
                                activeWallet = activeWallet,
                                onNavigateToBrowser = onNavigateToBrowser,
                                requestAuthorization = requestAuthorization,
                                onTrace = onTrace
                            )
                            FunctionResponsePart(functionCall.name, result)
                        } catch (e: Exception) {
                            FunctionResponsePart(
                                functionCall.name,
                                JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "Tool error")))
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Live API error", e)
                _voiceError.value = e.localizedMessage ?: "Gemini Live connection failed."
                _isLiveSessionActive.value = false
                onStatusChanged("Live session ended: ${e.localizedMessage ?: "Error"}")
            }
        }
    }

    @OptIn(PublicPreviewAPI::class)
    fun stopLiveConversation() {
        liveJob?.cancel()
        liveJob = null
        try {
            liveSession?.stopAudioConversation()
        } catch (_: Exception) {
        }
        liveSession = null
        _isLiveSessionActive.value = false
    }

    fun release() {
        stopLiveConversation()
        stopSpeaking()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "VoiceAssistantManager"
    }
}
