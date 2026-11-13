package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ContextManager
import com.example.browser.AquaBrowserController
import com.example.data.model.AquaSkill
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.device.DeviceTelemetry
import com.example.skills.SkillParserAndValidator
import com.example.wallet.FlashWalletConnector
import com.example.wallet.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
    fun `browser controller converts search queries cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browser = AquaBrowserController(context)
        val url = browser.navigateTo("android developer docs")
        assertTrue(url.startsWith("https://duckduckgo.com/?q="))
        val direct = browser.navigateTo("example.com")
        assertEquals("https://example.com", direct)
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
}
