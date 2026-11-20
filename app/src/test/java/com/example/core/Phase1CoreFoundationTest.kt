package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.CryptoHashing
import com.example.core.database.AgentTaskEntity
import com.example.core.database.AiUsageMeterEntity
import com.example.core.database.AuditLogEntity
import com.example.core.database.AutomationWorkflowEntity
import com.example.core.database.ChatMessageEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.DriveSyncQueueEntity
import com.example.core.database.MemoryEntity
import com.example.core.database.MemoryTier
import com.example.core.database.SyncStatus
import com.example.core.database.WorkspaceDocumentEntity
import com.example.core.security.AquaDatabaseKeyManager
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.core.security.SecretExclusionFilter
import com.example.core.security.SecurityAuditRedactor
import com.example.data.local.AquaCacheDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1CoreFoundationTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `permission policy store manages all 6 domains and enforces denials`() {
        val store = PermissionPolicyStore(context, "phase1_user")
        store.resetDefaults()
        assertEquals(6, PermissionDomain.entries.size)
        PermissionDomain.entries.forEach { domain ->
            assertTrue("Expected default enabled for $domain", store.isAllowed(domain))
        }

        store.setAllowed(PermissionDomain.DEVICE_CONTROL, false)
        assertFalse(store.isAllowed(PermissionDomain.DEVICE_CONTROL))

        // Reload from preferences to verify persistence
        val reloaded = PermissionPolicyStore(context, "phase1_user")
        assertFalse(reloaded.isAllowed(PermissionDomain.DEVICE_CONTROL))
        assertTrue(reloaded.isAllowed(PermissionDomain.FINANCIAL_AUTHORIZATION))

        try {
            reloaded.requireAllowed(PermissionDomain.DEVICE_CONTROL, "launch_app")
            fail("Expected SecurityException when DEVICE_CONTROL is disabled")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("Device Control"))
        }
    }

    @Test
    fun `security audit redactor strips tokens keys mnemonics and labeled secrets`() {
        val raw = "Auth ya29.a0AfB_byTestToken123456 with AIzaSyDummyApiKeyValue1234567890 " +
            "pat=ghp_1234567890abcdefghijklmnop key=0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef " +
            "mnemonic: abandon"
        val redacted = SecurityAuditRedactor.redact(raw)

        assertFalse(redacted.contains("ya29.a0AfB"))
        assertFalse(redacted.contains("AIzaSyDummy"))
        assertFalse(redacted.contains("ghp_123456"))
        assertFalse(redacted.contains("0123456789abcdef0123456789abcdef"))
        assertTrue(redacted.contains("[REDACTED_OAUTH_TOKEN]"))
        assertTrue(redacted.contains("[REDACTED_GOOGLE_API_KEY]"))
        assertTrue(redacted.contains("[REDACTED_GITHUB_TOKEN]"))
        assertTrue(redacted.contains("[REDACTED_64HEX_KEY]"))
    }

    @Test
    fun `secret exclusion filter blocks wallet and credential uploads while allowing clean data`() {
        val vaultVerdict = SecretExclusionFilter.inspectForDriveUpload(
            fileName = "aqua_native_wallet_vault.xml",
            payloadContent = "{\"data\":\"ok\"}"
        )
        assertFalse(vaultVerdict.allowed)

        val mnemonicVerdict = SecretExclusionFilter.inspectForDriveUpload(
            fileName = "backup.json",
            payloadContent = "{\"mnemonic\":\"abandon ability able about above absent\"}"
        )
        assertFalse(mnemonicVerdict.allowed)

        val oauthVerdict = SecretExclusionFilter.inspectForDriveUpload(
            fileName = "session.json",
            payloadContent = "{\"token\":\"ya29.a0SecretBearerTokenValueHere\"}"
        )
        assertFalse(oauthVerdict.allowed)

        val cleanVerdict = SecretExclusionFilter.inspectForDriveUpload(
            fileName = "conversation_123.json",
            payloadContent = "{\"title\":\"Architecture Q&A\",\"messages\":[{\"role\":\"user\",\"content\":\"Summarize Jetpack Compose layouts\"}]}"
        )
        assertTrue(cleanVerdict.allowed)
    }

    @Test
    fun `database key manager persists 32-byte DEK across reads and wipes buffer`() {
        val k1 = AquaDatabaseKeyManager.getOrCreateDatabasePassphrase(context)
        val k2 = AquaDatabaseKeyManager.getOrCreateDatabasePassphrase(context)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)

        AquaDatabaseKeyManager.wipeKey(k1)
        assertTrue(k1.all { it == 0.toByte() })
        AquaDatabaseKeyManager.wipeKey(k2)
    }

    @Test
    fun `ssot encrypted database schema supports conversations fts4 memory workspace docs tasks audit and sync queue`() = runBlocking {
        val uid = "user_ssot_1"
        val convDao = db.conversationDao()
        convDao.upsertConversation(
            ConversationEntity(
                id = "c1",
                userId = uid,
                title = "Voice Planning",
                modelUsed = "gemini-3.8-flash",
                messageCount = 1L
            )
        )
        convDao.upsertMessage(
            ChatMessageEntity(
                id = "m1",
                conversationId = "c1",
                userId = uid,
                role = "user",
                content = "Organize my Android research notes",
                tokenCount = 12
            )
        )
        assertEquals(1, convDao.getConversations(uid).size)
        assertEquals(1, convDao.getMessages("c1").size)

        val memDao = db.memoryDao()
        memDao.upsertMemory(
            MemoryEntity(
                id = "mem_1",
                userId = uid,
                tier = MemoryTier.LONG_TERM,
                category = "preference",
                title = "Language Mode",
                content = "Respond in English and Urdu bilingual summaries when asked"
            )
        )
        memDao.upsertMemory(
            MemoryEntity(
                id = "mem_2",
                userId = uid,
                tier = MemoryTier.EPISODIC,
                category = "fact",
                title = "Coffee Habit",
                content = "User prefers dark roast coffee in the morning"
            )
        )
        val ftsHits = memDao.searchMemoriesFts(uid, "Urdu")
        assertEquals(1, ftsHits.size)
        assertEquals("mem_1", ftsHits.first().id)

        val docDao = db.workspaceDocumentDao()
        docDao.upsertDocument(
            WorkspaceDocumentEntity(
                id = "doc_1",
                userId = uid,
                title = "Solana Fee Research",
                folderType = "RESEARCH",
                content = "Solana base transaction signature fee is 5000 lamports plus priority compute units.",
                sha256Hash = CryptoHashing.sha256Hex("Solana research")
            )
        )
        val docHits = docDao.searchDocumentsFts(uid, "lamports")
        assertEquals(1, docHits.size)
        assertEquals("Solana Fee Research", docHits.first().title)

        val taskDao = db.agentTaskDao()
        taskDao.upsertTask(
            AgentTaskEntity(
                taskId = "task_1",
                userId = uid,
                title = "Sync notes to Drive",
                state = "COMPLETED",
                riskTier = 1,
                totalSteps = 2,
                currentStepIndex = 2
            )
        )
        assertEquals("COMPLETED", taskDao.getTask("task_1")?.state)

        val auditDao = db.auditLogDao()
        auditDao.appendLog(
            AuditLogEntity(
                userId = uid,
                taskId = "task_1",
                domain = PermissionDomain.CLOUD_STORAGE.id,
                action = "drive_sync",
                riskTier = 1,
                outcome = "ALLOWED",
                redactedDetail = "Synced research note cleanly"
            )
        )
        assertEquals(1, auditDao.getRecentAuditLogs(uid).size)

        val queueDao = db.driveSyncQueueDao()
        queueDao.enqueueMutation(
            DriveSyncQueueEntity(
                mutationId = "mut_1",
                userId = uid,
                entityType = "MEMORY",
                entityId = "mem_1",
                driveFolder = "Memory",
                fileName = "memory_mem_1.json",
                payloadSha256 = CryptoHashing.sha256Hex("{}"),
                serializedPayloadJson = "{}",
                status = SyncStatus.PENDING_OFFLINE
            )
        )
        assertEquals(1, queueDao.getPendingMutations(uid).size)

        db.aiUsageMeterDao().recordUsage(
            AiUsageMeterEntity(
                userId = uid,
                sessionId = "c1",
                modelId = "gemini-3.8-flash",
                promptTokens = 120,
                outputTokens = 45,
                totalTokens = 165,
                latencyMs = 420L
            )
        )
        db.automationWorkflowDao().upsertWorkflow(
            AutomationWorkflowEntity(
                id = "wf_1",
                userId = uid,
                name = "Nightly Drive Backup",
                triggerType = "PERIODIC",
                intervalMinutes = 720L
            )
        )
        assertEquals("Nightly Drive Backup", db.automationWorkflowDao().getWorkflow("wf_1")?.name)
    }
}
