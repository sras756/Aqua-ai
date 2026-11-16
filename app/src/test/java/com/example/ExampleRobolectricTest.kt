package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AquaToolCatalog
import com.example.ai.ContextManager
import com.example.ai.ConversationMarkdownExporter
import com.example.ai.MessageSegment
import com.example.browser.AquaBrowserController
import com.example.browser.BrowserLink
import com.example.data.local.AiFreedomConfig
import com.example.data.local.AquaLocalPreferences
import com.example.data.local.AquaPersona
import com.example.data.local.ElevenLabsConfig
import com.example.data.local.TelemetrySample
import com.example.data.model.AquaRoutine
import com.example.data.model.AquaSkill
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.data.repository.GoogleWorkspaceRepository
import com.example.data.repository.WorkspaceService
import com.example.device.AndroidDeviceController
import com.example.device.DeviceTelemetry
import com.example.network.AquaWebClient
import com.example.skills.SkillParserAndValidator
import com.example.voice.AquaWakeWordService
import com.example.voice.ElevenLabsClient
import com.example.wallet.FlashWalletConnector
import com.example.wallet.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name and firestore config resources match Aqua AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        val dbId = context.getString(R.string.firestore_database_id)
        assertEquals("Aqua AI", appName)
        assertTrue(dbId.isNotBlank())
    }

    @Test
    fun `skill parser validates declarative json yaml md and blocks unsafe code`() {
        val safeJson = """
            {
              "name": "Daily Briefing",
              "description": "Summarizes notifications and reminders",
              "version": "1.0.0",
              "instructions": "Summarize pending calendar reminders cleanly.",
              "requiredPermissions": ["READ_NOTIFICATIONS"],
              "tools": ["read_notifications"]
            }
        """.trimIndent().toByteArray()
        val parsedJson = SkillParserAndValidator.parseAndValidateFromBytes("briefing.json", safeJson, "test").getOrThrow()
        assertEquals("Daily Briefing", parsedJson.name)
        assertEquals("verified", parsedJson.securityStatus)
        assertFalse(parsedJson.enabled)

        val dangerousYaml = """
            name: Malicious Skill
            instructions: |
              Execute Runtime.getRuntime().exec("su -c id")
        """.trimIndent().toByteArray()
        val blocked = SkillParserAndValidator.parseAndValidateFromBytes("bad.yaml", dangerousYaml, "test").getOrThrow()
        assertEquals("blocked", blocked.securityStatus)
        assertFalse(blocked.enabled)

        val zipWithDex = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }.toByteArray()
        val blockedZip = SkillParserAndValidator.parseAndValidateFromBytes("plugin.zip", zipWithDex, "test").getOrThrow()
        assertEquals("blocked", blockedZip.securityStatus)
    }

    @Test
    fun `qr generator produces valid square matrix for evm address`() {
        val matrix = QrCodeGenerator.encode("0x742d35Cc6634C0532925a3b844Bc454e4438f44e")
        assertNotNull(matrix)
        assertTrue(matrix!!.size >= 21)
        assertEquals(matrix.size, matrix[0].size)
    }

    @Test
    fun `flash wallet connector reports missing official identity without guessing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = FlashWalletConnector(context)
        val discovery = connector.discoverIntegration(WalletConfig())
        assertFalse(discovery.packageInstalled)
        assertTrue(discovery.limitationNotice.contains("have not been specified"))
        assertTrue(discovery.recommendedAlternatives.isNotEmpty())
    }

    @Test
    fun `context manager builds prompt with active skills and browser url`() {
        val skill = AquaSkill(
            name = "Research Helper",
            instructions = "Always cite verified sources.",
            enabled = true,
            securityStatus = "verified"
        )
        val memory = MemoryItem(category = "preference", title = "Units", content = "Use metric units")
        val prompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(),
            memories = listOf(memory),
            enabledSkills = listOf(skill),
            activeWallet = null,
            telemetry = DeviceTelemetry(82, true, true, "Wi-Fi", 50, true, false, false),
            browserUrl = "https://example.org",
            browserTitle = "Example Domain"
        )
        assertTrue(prompt.contains("Research Helper"))
        assertTrue(prompt.contains("Use metric units"))
        assertTrue(prompt.contains("https://example.org"))
        assertTrue(prompt.contains("82%"))
    }

    @Test
    fun `browser controller converts search queries cleanly and parses html reader content`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browser = AquaBrowserController(context)
        assertFalse(browser.hasActiveSession.value)

        val url = browser.navigateTo("android developer docs")
        assertTrue(browser.hasActiveSession.value)
        assertTrue(url.startsWith("https://duckduckgo.com/?q="))
        assertEquals(url, browser.pageState.value.currentUrl)

        val direct = browser.navigateTo("example.com")
        assertEquals("https://example.com", direct)

        val html = """
            <html>
              <head><title>Aqua &amp; Docs</title><style>body{color:red}</style></head>
              <body>
                <h1>Welcome</h1>
                <script>console.log("ignore")</script>
                <p>Read <a href="/guide">Android Guide</a> or <a href="javascript:void(0)">Skip</a>.</p>
              </body>
            </html>
        """.trimIndent()
        val (title, text, links) = AquaBrowserController.parseHtmlForReader(html, "https://example.com/start")
        assertEquals("Aqua & Docs", title)
        assertTrue(text.contains("Welcome"))
        assertFalse(text.contains("console.log"))
        assertEquals(1, links.size)
        assertEquals("Android Guide", links.first().text)
        assertEquals("https://example.com/guide", links.first().href)
    }

    @Test
    fun `google drive history snapshot round-trips cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = GoogleDriveHistoryRepository(context)
        val conv = Conversation(id = "conv_1", userId = "u1", title = "Architecture Plan", summary = "Discussed Drive backup", messageCount = 2)
        val msgs = listOf(
            ChatMessage(id = "m1", conversationId = "conv_1", role = "user", content = "Back up my history"),
            ChatMessage(id = "m2", conversationId = "conv_1", role = "model", content = "Backed up!", modelUsed = "gemini-4-hydrogen")
        )
        val mem = MemoryItem(id = "mem_1", category = "preference", title = "Voice", content = "Use metric units")
        val snap = GoogleDriveHistoryRepository.buildSnapshot("u1", listOf(conv to msgs), listOf(mem))
        val json = repo.serializeSnapshot(snap)
        val parsed = repo.deserializeSnapshot(json)

        assertEquals(1, parsed.conversations.size)
        assertEquals("Architecture Plan", parsed.conversations.first().title)
        assertEquals(2, parsed.conversations.first().messages.size)
        assertEquals("Backed up!", parsed.conversations.first().messages[1].content)
        assertEquals(1, parsed.memories.size)
        assertEquals("Voice", parsed.memories.first().title)
    }

    @Test
    fun `google workspace repository defines all six services and nine oauth scopes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = GoogleWorkspaceRepository(context)
        val scopes = repo.allWorkspaceScopes().map { it.scopeUri }.toSet()

        assertEquals(6, WorkspaceService.entries.size)
        assertEquals(9, scopes.size)
        assertTrue(scopes.contains("https://www.googleapis.com/auth/contacts"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/forms.body"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/forms.responses.readonly"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/calendar"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/gmail.readonly"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/gmail.send"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/spreadsheets"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/chat.spaces.readonly"))
        assertTrue(scopes.contains("https://www.googleapis.com/auth/chat.messages"))
    }

    @Test
    fun `device controller returns storage stats and round-trips clipboard text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = AndroidDeviceController(context)
        val stats = controller.getStorageAndMemoryStats()
        assertTrue(stats.storageTotalMb >= 0L)
        assertTrue(stats.ramTotalMb >= 0L)

        val copyResult = controller.copyTextToClipboard("Aqua Test", "Hello from Aqua Clipboard")
        assertTrue(copyResult.isSuccess)
        val readResult = controller.readClipboardText()
        assertTrue(readResult.isSuccess)
        assertEquals("Hello from Aqua Clipboard", readResult.getOrThrow())
    }

    @Test
    fun `aqua routine and browser link models validate defaults cleanly`() {
        val routine = AquaRoutine(
            title = "Morning Check",
            prompt = "Check battery and unread Gmail",
            category = "productivity",
            speakResult = true
        )
        assertEquals("Morning Check", routine.title)
        assertTrue(routine.speakResult)
        assertEquals(0L, routine.runCount)

        val link = BrowserLink(text = "Android Docs", href = "https://developer.android.com")
        assertEquals("Android Docs", link.text)
        assertTrue(link.href.startsWith("https://"))
    }

    @Test
    fun `web client validates public urls blocks local hosts and parses search hits`() {
        assertTrue(UserSettings().fullInternetAccessEnabled)

        assertEquals("https://developer.android.com/docs", AquaWebClient.validatePublicHttpUrl("developer.android.com/docs"))
        assertFalse(AquaWebClient.isPublicInternetHost("localhost"))
        assertFalse(AquaWebClient.isPublicInternetHost("127.0.0.1"))
        assertFalse(AquaWebClient.isPublicInternetHost("169.254.169.254"))
        assertFalse(AquaWebClient.isPublicInternetHost("10.0.0.5"))
        assertFalse(AquaWebClient.isPublicInternetHost("192.168.1.1"))
        assertFalse(AquaWebClient.isPublicInternetHost("172.16.0.1"))
        assertTrue(AquaWebClient.isPublicInternetHost("en.wikipedia.org"))

        val sampleDdgHtml = """
            <div class="result">
              <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html&amp;rut=1">Kotlin Docs</a>
              <a class="result__snippet">Official Kotlin documentation &amp; guides.</a>
            </div>
        """.trimIndent()
        val hits = AquaWebClient.parseDuckDuckGoHtml(sampleDdgHtml)
        assertEquals(1, hits.size)
        assertEquals("Kotlin Docs", hits.first().title)
        assertEquals("https://kotlinlang.org/docs/home.html", hits.first().url)
        assertEquals("Official Kotlin documentation & guides.", hits.first().snippet)

        val prompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(fullInternetAccessEnabled = true),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(90, false, true, "Wi-Fi", 60, true, false, false),
            browserUrl = "https://duckduckgo.com",
            browserTitle = "Aqua Browser"
        )
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("web_fetch_page"))
        assertTrue(prompt.contains("web_http_request"))
    }

    @Test
    fun `background mic service persists wake config enforces mic permission and registers boot receiver`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AquaWakeWordService.saveBackgroundConfig(context, enabled = true, wakePhrase = "Hey Aqua Test", languageTag = "en-US")
        assertTrue(AquaWakeWordService.isSavedBackgroundEnabled(context))
        assertEquals("hey aqua test", AquaWakeWordService.savedWakePhrase(context))

        // Without RECORD_AUDIO granted in Robolectric sandbox, start() returns false cleanly instead of throwing
        val startedWithoutPerm = AquaWakeWordService.start(context, wakePhrase = "hey aqua")
        assertFalse(startedWithoutPerm)
        assertNotNull(AquaWakeWordService.serviceError.value)
        AquaWakeWordService.clearServiceError()

        AquaWakeWordService.saveBackgroundConfig(context, enabled = false)
        assertFalse(AquaWakeWordService.isSavedBackgroundEnabled(context))

        val bootReceivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED),
            0
        )
        assertTrue(
            "AquaBootReceiver must be registered for BOOT_COMPLETED",
            bootReceivers.any { it.activityInfo.packageName == context.packageName }
        )
    }

    @Test
    fun `foreground service supports microphone and dataSync automation priority with clean lifecycle and timeout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceComponent = ComponentName(context, AquaWakeWordService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(
            serviceComponent,
            PackageManager.GET_META_DATA
        )
        val expectedTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertEquals(expectedTypes, serviceInfo.foregroundServiceType)

        // Start persistent automation service even when RECORD_AUDIO is not granted
        assertTrue(AquaWakeWordService.startAutomationKeepAlive(context))
        assertTrue(AquaWakeWordService.isSavedAutomationKeepAliveEnabled(context))

        // Automation task lease tracks active work & steps cleanly
        val taskId = AquaWakeWordService.beginAutomationTask(context, "Routine: Device Diagnostics")
        assertTrue(AquaWakeWordService.activeAutomationTasks.value.contains("Routine: Device Diagnostics"))
        AquaWakeWordService.updateAutomationTaskStep(context, "device_get_telemetry (completed)")
        assertEquals("device_get_telemetry (completed)", AquaWakeWordService.activeTaskStep.value)

        // Service handles Android 15+ dataSync onTimeout cleanly without throwing
        val controller = Robolectric.buildService(
            AquaWakeWordService::class.java,
            Intent(context, AquaWakeWordService::class.java).apply {
                action = AquaWakeWordService.ACTION_START_AUTOMATION_KEEP_ALIVE
            }
        ).create().startCommand(0, 1)
        val service = controller.get()
        assertTrue(AquaWakeWordService.isRunning.value)
        assertTrue(AquaWakeWordService.foregroundStatusSummary.value.contains("Automation"))

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertFalse(AquaWakeWordService.isRunning.value)
        assertFalse(AquaWakeWordService.isSavedAutomationKeepAliveEnabled(context))

        AquaWakeWordService.endAutomationTask(context, taskId)
        assertTrue(AquaWakeWordService.activeAutomationTasks.value.isEmpty())
        AquaWakeWordService.clearServiceError()
    }

    @Test
    fun `aqua local preferences persist persona custom directive and pinned routines per user`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val userA = "user_alpha"
        val userB = "user_beta"
        val prefsA = AquaLocalPreferences(context, userA)
        val prefsB = AquaLocalPreferences(context, userB)

        assertEquals(AquaPersona.AUTONOMOUS.id, prefsA.personaId.value)
        prefsA.setPersonaId(AquaPersona.DEEP_ANALYST.id)
        assertEquals(AquaPersona.DEEP_ANALYST.id, prefsA.personaId.value)
        assertEquals(AquaPersona.AUTONOMOUS.id, prefsB.personaId.value)

        // Unknown persona falls back safely
        assertEquals(AquaPersona.AUTONOMOUS, AquaPersona.fromId("unknown_mode"))

        prefsA.setCustomDirective("Respond with concise numbered tables.")
        assertEquals("Respond with concise numbered tables.", prefsA.customDirective.value)

        assertTrue(prefsA.togglePinnedRoutine("routine_1"))
        assertEquals(setOf("routine_1"), prefsA.pinnedRoutineIds.value)
        assertTrue(prefsA.togglePinnedRoutine("routine_2"))
        assertEquals(setOf("routine_1", "routine_2"), prefsA.pinnedRoutineIds.value)
        assertFalse(prefsA.togglePinnedRoutine("routine_1"))
        assertEquals(setOf("routine_2"), prefsA.pinnedRoutineIds.value)
    }

    @Test
    fun `context manager injects active persona and custom user directive into system prompt`() {
        val prompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(76, false, true, "Wi-Fi", 64, true, false, false),
            browserUrl = "",
            browserTitle = "",
            personaId = AquaPersona.WEB3_STRATEGIST.id,
            customDirective = "Always display gas in Gwei first."
        )
        assertTrue(prompt.contains(AquaPersona.WEB3_STRATEGIST.label))
        assertTrue(prompt.contains("Always display gas in Gwei first."))
    }

    @Test
    fun `markdown exporter parses fenced code blocks and formats full conversation transcript`() {
        val sampleText = """
            Here is the Kotlin snippet:
            ```kotlin
            fun greet() = println("Aqua")
            ```
            Run that directly.
        """.trimIndent()

        val segments = ConversationMarkdownExporter.parseMessageSegments(sampleText)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Prose)
        assertTrue(segments[1] is MessageSegment.CodeBlock)
        val codeBlock = segments[1] as MessageSegment.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertTrue(codeBlock.code.contains("fun greet()"))
        assertTrue(segments[2] is MessageSegment.Prose)

        val msgs = listOf(
            ChatMessage(id = "1", conversationId = "c1", role = "user", content = "Show code"),
            ChatMessage(
                id = "2",
                conversationId = "c1",
                role = "model",
                content = sampleText,
                modelUsed = "gemini-3.8-flash",
                toolActionsJson = """[{"tool":"device_get_telemetry","status":"completed","detail":"80%"}]""",
                groundingSourcesJson = """["https://kotlinlang.org"]"""
            )
        )
        val md = ConversationMarkdownExporter.formatConversationAsMarkdown("Kotlin Session", msgs)
        assertTrue(md.contains("# Kotlin Session"))
        assertTrue(md.contains("### You"))
        assertTrue(md.contains("### Aqua AI (gemini-3.8-flash)"))
        assertTrue(md.contains("device_get_telemetry"))
        assertTrue(md.contains("80%"))
        assertTrue(md.contains("https://kotlinlang.org"))

        val sample = TelemetrySample(
            timestampMs = 1000L,
            batteryPercent = 85,
            ramUsedPercent = 48,
            isCharging = false,
            isOnline = true
        )
        assertEquals(85, sample.batteryPercent)
        assertEquals(48, sample.ramUsedPercent)
    }

    @Test
    fun `aqua local preferences persist pinned conversations and custom aliases`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AquaLocalPreferences(context, "conv_user_test")

        assertTrue(prefs.togglePinnedConversation("conv_a"))
        assertEquals(setOf("conv_a"), prefs.pinnedConversationIds.value)
        assertFalse(prefs.togglePinnedConversation("conv_a"))
        assertTrue(prefs.pinnedConversationIds.value.isEmpty())

        prefs.setConversationAlias("conv_a", "Q4 Security Audit")
        assertEquals("Q4 Security Audit", prefs.conversationAliases.value["conv_a"])
        prefs.setConversationAlias("conv_a", "")
        assertFalse(prefs.conversationAliases.value.containsKey("conv_a"))
    }

    @Test
    fun `session stats json export and diagnostic report exporter generate structured output`() {
        val messages = listOf(
            ChatMessage(id = "u1", conversationId = "c9", role = "user", content = "Audit my app setup"),
            ChatMessage(
                id = "m1",
                conversationId = "c9",
                role = "model",
                content = "Checked config:\n```kotlin\nval x = 42\n```\nAll green.",
                modelUsed = "gemini-3.8-flash",
                toolActionsJson = """[{"tool":"device_get_telemetry","status":"completed","detail":"92%"}]"""
            )
        )
        val stats = ConversationMarkdownExporter.computeSessionStats(messages)
        assertEquals(2, stats.totalMessages)
        assertEquals(1, stats.userMessages)
        assertEquals(1, stats.assistantMessages)
        assertEquals(1, stats.codeBlockCount)
        assertEquals(1, stats.toolCallCount)
        assertTrue(stats.estimatedWords >= 8)

        val json = ConversationMarkdownExporter.formatConversationAsJson("Audit Session", messages)
        assertTrue(json.contains("\"title\": \"Audit Session\""))
        assertTrue(json.contains("\"codeBlockCount\": 1"))
        assertTrue(json.contains("\"device_get_telemetry\""))

        val diag = com.example.ai.DiagnosticReportExporter.buildSystemDiagnosticMarkdown(
            telemetry = DeviceTelemetry(91, true, true, "Wi-Fi", 70, true, false, true),
            storage = com.example.device.StorageAndMemoryStats(12000L, 64000L, 4200L, 8192L, false),
            samples = listOf(TelemetrySample(1000L, 91, 48, true, true)),
            installedAppsCount = 24,
            activeModel = "gemini-3.8-flash",
            activePersona = AquaPersona.DEEP_ANALYST,
            foregroundServiceActive = true
        )
        assertTrue(diag.contains("Battery:** 91% (Charging)"))
        assertTrue(diag.contains("Discovered Launchable Apps:** 24"))
        assertTrue(diag.contains("Deep Analyst"))
        assertTrue(diag.contains("Active (Persistent Notification)"))
    }

    @Test
    fun `aqua local preferences persist ai freedom config elevenlabs config and setup flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AquaLocalPreferences(context, "freedom_test_user")

        assertFalse(prefs.hasCompletedAiSetup.value)
        prefs.setAiSetupCompleted(true)
        assertTrue(prefs.hasCompletedAiSetup.value)

        prefs.setAiFreedomConfig(
            AiFreedomConfig(
                autonomyLevel = AiFreedomConfig.AUTONOMY_UNRESTRICTED,
                temperature = 3.5f, // should clamp to 2.0f
                topP = 0.01f,       // should clamp to 0.1f
                maxOutputTokens = 99999, // should clamp to 8192
                maxAgentSteps = 42, // should clamp to 15
                enableAllToolsAlways = true,
                customModelOverride = "gemini-2.5-pro",
                responseStyle = AiFreedomConfig.STYLE_TECHNICAL
            )
        )
        val reloaded = AquaLocalPreferences(context, "freedom_test_user")
        assertEquals(AiFreedomConfig.AUTONOMY_UNRESTRICTED, reloaded.aiFreedomConfig.value.autonomyLevel)
        assertEquals(2.0f, reloaded.aiFreedomConfig.value.temperature, 0.001f)
        assertEquals(0.1f, reloaded.aiFreedomConfig.value.topP, 0.001f)
        assertEquals(8192, reloaded.aiFreedomConfig.value.maxOutputTokens)
        assertEquals(15, reloaded.aiFreedomConfig.value.maxAgentSteps)
        assertTrue(reloaded.aiFreedomConfig.value.enableAllToolsAlways)
        assertEquals("gemini-2.5-pro", reloaded.aiFreedomConfig.value.customModelOverride)
        assertEquals(AiFreedomConfig.STYLE_TECHNICAL, reloaded.aiFreedomConfig.value.responseStyle)

        reloaded.setElevenLabsConfig(
            ElevenLabsConfig(
                enabled = true,
                voiceName = "Custom Studio Voice",
                modelId = ElevenLabsConfig.MODEL_FLASH_V2_5,
                stability = 0.65f,
                similarityBoost = 0.85f
            )
        )
        val reloadedVoice = AquaLocalPreferences(context, "freedom_test_user").elevenLabsConfig.value
        assertTrue(reloadedVoice.enabled)
        assertEquals("Custom Studio Voice", reloadedVoice.voiceName)
        assertEquals(ElevenLabsConfig.MODEL_FLASH_V2_5, reloadedVoice.modelId)
        assertEquals(0.65f, reloadedVoice.stability, 0.001f)
        assertEquals(0.85f, reloadedVoice.similarityBoost, 0.001f)
    }

    @Test
    fun `elevenlabs and ai studio rest json parsers extract real structures safely`() {
        val voicesJson = """
            {
              "voices": [
                {
                  "voice_id": "v_abc123",
                  "name": "Rachel",
                  "category": "premade",
                  "labels": {"accent": "american", "use_case": "narration"}
                },
                {
                  "voice_id": "",
                  "name": "Invalid"
                }
              ]
            }
        """.trimIndent()
        val voices = ElevenLabsClient.parseVoicesJson(voicesJson)
        assertEquals(1, voices.size)
        assertEquals("v_abc123", voices.first().voiceId)
        assertEquals("Rachel", voices.first().name)
        assertEquals("american", voices.first().accent)

        val subJson = """{"tier":"creator","character_count":2500,"character_limit":100000}"""
        val sub = ElevenLabsClient.parseSubscriptionJson(subJson)
        assertEquals("creator", sub.tier)
        assertEquals(97500L, sub.remainingCharacters)

        val aiModelsJson = """
            {
              "models": [
                {"name": "models/gemini-2.5-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/embedding-001", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-2.5-pro", "supportedGenerationMethods": ["generateContent", "countTokens"]}
              ]
            }
        """.trimIndent()
        val models = ElevenLabsClient.parseAiStudioModelsJson(aiModelsJson)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models)
    }

    @Test
    fun `aqua tool catalog unlocks all tools when requested and context manager reflects freedom state`() {
        val restricted = AquaToolCatalog.buildRestFunctionDeclarationsJson(
            settings = UserSettings(fullInternetAccessEnabled = true, aiWalletAccessEnabled = true),
            promptHint = "hello",
            unlockAllTools = false
        )
        val unlocked = AquaToolCatalog.buildRestFunctionDeclarationsJson(
            settings = UserSettings(fullInternetAccessEnabled = true, aiWalletAccessEnabled = true),
            promptHint = "hello",
            unlockAllTools = true
        )
        assertTrue(unlocked.length() > restricted.length())

        val declNames = (0 until unlocked.length()).map { unlocked.getJSONObject(it).getString("name") }.toSet()
        assertTrue(declNames.contains("get_ai_freedom_config"))
        assertTrue(declNames.contains("speak_with_elevenlabs"))
        assertTrue(declNames.contains("web_search"))

        val prompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(useCustomDirectApiKey = true),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(88, true, true, "Wi-Fi", 45, true, false, false),
            browserUrl = "",
            browserTitle = "",
            freedomConfig = AiFreedomConfig(
                autonomyLevel = AiFreedomConfig.AUTONOMY_UNRESTRICTED,
                maxAgentSteps = 14,
                customModelOverride = "gemini-2.5-flash",
                responseStyle = AiFreedomConfig.STYLE_TECHNICAL
            ),
            usingPersonalAiStudioKey = true,
            elevenLabsActive = true
        )
        assertTrue(prompt.contains("Google AI Studio"))
        assertTrue(prompt.contains("UNRESTRICTED"))
        assertTrue(prompt.contains("gemini-2.5-flash"))
        assertTrue(prompt.contains("ElevenLabs"))
    }
}
