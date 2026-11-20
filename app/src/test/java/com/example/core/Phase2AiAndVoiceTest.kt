package com.example.core

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AiUsageMeterTracker
import com.example.core.ai.ContextCompactor
import com.example.core.ai.ExponentialBackoffExecutor
import com.example.core.ai.TaskPlanner
import com.example.core.voice.AquaAudioFocusController
import com.example.core.voice.AquaVoiceLanguageDetector
import com.example.core.voice.DetectedVoiceLanguage
import com.example.core.voice.SpeechErrorMapper
import com.example.data.local.AquaCacheDatabase
import com.example.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase2AiAndVoiceTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ai usage meter tracker persists token counts and latency into Room`() = runBlocking {
        val tracker = AiUsageMeterTracker(
            aiUsageMeterDao = db.aiUsageMeterDao(),
            userIdProvider = { "uid_phase2" }
        )
        tracker.record(
            sessionId = "chat_1",
            modelId = "gemini-2.5-flash",
            promptTokens = 420,
            outputTokens = 180,
            totalTokens = 600,
            latencyMs = 950L
        )
        val total = db.aiUsageMeterDao().observeTotalTokens("uid_phase2").first()
        val entries = db.aiUsageMeterDao().observeUsage("uid_phase2").first()
        assertEquals(600L, total)
        assertEquals(1, entries.size)
        assertEquals("gemini-2.5-flash", entries.first().modelId)
        assertEquals(950L, entries.first().latencyMs)
    }

    @Test
    fun `context compactor keeps short conversations verbatim and compacts long history`() {
        val shortHistory = (1..4).map { idx ->
            ChatMessage(
                id = "m$idx",
                role = if (idx % 2 == 1) "user" else "model",
                content = "Short turn $idx"
            )
        }
        val shortResult = ContextCompactor.compact(shortHistory)
        assertEquals(0, shortResult.compactedCount)
        assertEquals("", shortResult.summaryBlock)
        assertEquals(4, shortResult.retainedMessages.size)

        val longHistory = (1..24).map { idx ->
            ChatMessage(
                id = "m$idx",
                role = if (idx % 2 == 1) "user" else "model",
                content = "Detailed research note number $idx " + "x".repeat(900)
            )
        }
        val compactedResult = ContextCompactor.compact(longHistory, maxHistoryChars = 6_000, keepRecentCount = 6)
        assertEquals(6, compactedResult.retainedMessages.size)
        assertEquals(18, compactedResult.compactedCount)
        assertTrue(compactedResult.summaryBlock.contains("Compacted Prior Context"))
        assertTrue(compactedResult.estimatedTokensSaved > 500)
    }

    @Test
    fun `task planner parses fenced json into structured plan`() {
        val raw = """
            ```json
            {
              "objective": "Check battery and summarize webpage",
              "language": "en",
              "steps": [
                {"stepNumber": 1, "goal": "Inspect battery telemetry", "suggestedTool": "check_device_status", "riskLevel": "LOW"},
                {"stepNumber": 2, "goal": "Summarize page", "suggestedTool": "web_fetch_page", "riskLevel": "LOW"}
              ]
            }
            ```
        """.trimIndent()
        val parsed = TaskPlanner.parsePlanJson(raw)
        assertNotNull(parsed)
        assertEquals("Check battery and summarize webpage", parsed!!.objective)
        assertEquals(2, parsed.steps.size)
        assertEquals("check_device_status", parsed.steps[0].suggestedTool)
    }

    @Test
    fun `exponential backoff retries transient errors and propagates cancellation immediately`() = runBlocking {
        var attempts = 0
        val value = ExponentialBackoffExecutor.executeWithRetry(
            maxAttempts = 3,
            initialDelayMs = 1L,
            shouldRetry = { it.message?.contains("429") == true }
        ) { attempt ->
            attempts = attempt
            if (attempt < 3) throw IllegalStateException("HTTP 429")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, attempts)

        var cancelledCaught = false
        try {
            ExponentialBackoffExecutor.executeWithRetry(
                maxAttempts = 3,
                initialDelayMs = 1L,
                shouldRetry = { true }
            ) {
                throw CancellationException("job cancelled")
            }
        } catch (_: CancellationException) {
            cancelledCaught = true
        }
        assertTrue(cancelledCaught)
    }

    @Test
    fun `voice language detector recognizes english urdu script and roman urdu`() {
        assertEquals(
            DetectedVoiceLanguage.ENGLISH,
            AquaVoiceLanguageDetector.detectLanguage("Open my Google Drive workspace", "en-US")
        )
        assertEquals(
            DetectedVoiceLanguage.URDU,
            AquaVoiceLanguageDetector.detectLanguage("آج موسم کیسا ہے اور میری بیٹری چیک کریں", "en-US")
        )
        assertEquals(
            DetectedVoiceLanguage.URDU,
            AquaVoiceLanguageDetector.detectLanguage("Aaj mera kaam kaise chal raha hai", "en-US")
        )
        assertEquals("ur-PK", AquaVoiceLanguageDetector.normalizeRecognitionTag("ur"))
        assertEquals("en-US", AquaVoiceLanguageDetector.normalizeRecognitionTag("en"))
        assertEquals("ur", AquaVoiceLanguageDetector.resolveTtsLocale("en-US", "شکریہ").language)
    }

    @Test
    fun `audio focus controller acquires and abandons focus cleanly and speech errors map cleanly`() {
        val focus = AquaAudioFocusController(context)
        focus.requestSpeechFocus(exclusiveForCapture = true)
        focus.abandonFocus()
        assertFalse(focus.hasAudioFocus.value)
        assertTrue(SpeechErrorMapper.describe(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("Microphone"))
        assertTrue(SpeechErrorMapper.isSilentIgnore(SpeechRecognizer.ERROR_NO_MATCH))
    }
}
