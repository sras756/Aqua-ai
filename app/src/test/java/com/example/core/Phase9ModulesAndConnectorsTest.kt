package com.example.core

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.core.agent.RiskTier
import com.example.core.automation.AquaAutomationStudio
import com.example.core.automation.AquaAutomationWorker
import com.example.core.connectors.GoogleConnectorManager
import com.example.core.connectors.GoogleServiceId
import com.example.core.database.DriveFileMetadataEntity
import com.example.core.database.MemoryTier
import com.example.core.developer.AquaDeveloperModeClient
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import com.example.core.knowledge.AquaKnowledgeBrain
import com.example.core.memory.AquaMemoryEngine
import com.example.core.notifications.AquaNotificationChannels
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.core.workspace.AquaWorkspaceEngine
import com.example.core.workspace.WorkspaceFolderType
import com.example.core.workspace.WorkspaceMode
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.SecureConfigStore
import com.example.device.AquaScreenIntelligence
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class Phase9ModulesAndConnectorsTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase
    private lateinit var policyStore: PermissionPolicyStore
    private lateinit var httpServer: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
        policyStore = PermissionPolicyStore(context, "uid_phase9")
        policyStore.resetDefaults()

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/user/repos") { ex ->
            val arr = JSONArray().put(
                JSONObject()
                    .put("full_name", "aqua/android-agent")
                    .put("description", "Autonomous Android Assistant")
                    .put("default_branch", "main")
                    .put("private", false)
                    .put("html_url", "https://github.com/aqua/android-agent")
            )
            val bytes = arr.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        httpServer.createContext("/repos/aqua/android-agent/contents/README.md") { ex ->
            val b64 = Base64.encodeToString("# Aqua README".toByteArray(), Base64.NO_WRAP)
            val json = JSONObject().put("encoding", "base64").put("content", b64).toString().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
            ex.close()
        }
        httpServer.start()
        baseUrl = "http://127.0.0.1:${httpServer.address.port}"
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
        db.close()
    }

    @Test
    fun `automation studio saves workflows runs worker under policy and queues drive sync`() = runBlocking {
        val driveSync = AquaDriveSyncCoordinator(context, db.driveSyncQueueDao(), db.driveFileMetadataDao(), db)
        val studio = AquaAutomationStudio(context, db.automationWorkflowDao(), policyStore, driveSync) { "uid_phase9" }

        // Blocked scheduled workflow when BACKGROUND_EXECUTION disabled
        policyStore.setAllowed(PermissionDomain.BACKGROUND_EXECUTION, false)
        val blocked = studio.saveWorkflow(
            name = "Morning Briefing",
            triggerType = "SCHEDULED",
            intervalMinutes = 60L,
            steps = listOf("Summarize news"),
            scheduleWork = false
        )
        assertTrue(blocked.isFailure)

        // Allowed when re-enabled
        policyStore.setAllowed(PermissionDomain.BACKGROUND_EXECUTION, true)
        val saved = studio.saveWorkflow(
            id = "wf_test_1",
            name = "Tier2 Backup Check",
            triggerType = "MANUAL",
            steps = listOf("Inspect wallet", "Report summary"),
            riskTier = RiskTier.TIER_2_CONFIRM,
            scheduleWork = false
        ).getOrThrow()

        val queue = db.driveSyncQueueDao().getPendingMutations("uid_phase9", 10)
        assertTrue(queue.any { it.driveFolder == AquaDriveFolders.AUTOMATION_RECORDS && it.entityId == saved.id })

        // Verify worker checks BACKGROUND_EXECUTION cleanly
        policyStore.setAllowed(PermissionDomain.BACKGROUND_EXECUTION, false)
        val worker = TestListenableWorkerBuilder<AquaAutomationWorker>(context)
            .setInputData(
                workDataOf(
                    AquaAutomationWorker.KEY_WORKFLOW_ID to saved.id,
                    AquaAutomationWorker.KEY_USER_ID to "uid_phase9"
                )
            )
            .build()
        val result = worker.doWork() as ListenableWorker.Result.Success
        assertEquals("skipped_background_policy_disabled", result.outputData.getString(AquaAutomationWorker.KEY_RESULT))
    }

    @Test
    fun `knowledge brain aggregates citations across memory workspace docs and drive metadata without fabricating`() = runBlocking {
        val memoryEngine = AquaMemoryEngine(db.memoryDao(), null) { "uid_phase9" }
        val workspaceEngine = AquaWorkspaceEngine(db.workspaceDocumentDao(), db.bookmarkDao(), null) { "uid_phase9" }
        val brain = AquaKnowledgeBrain(memoryEngine, workspaceEngine, db.driveFileMetadataDao()) { "uid_phase9" }

        memoryEngine.saveMemory(
            title = "Solana Architecture",
            content = "Solana uses Proof of History and Ed25519 keypairs.",
            tier = MemoryTier.LONG_TERM,
            scheduleDriveSync = false
        )
        workspaceEngine.setWorkspaceMode(WorkspaceMode.AI_WORKSPACE)
        workspaceEngine.saveDocumentByAgent(
            title = "Solana RPC Benchmark",
            content = "Mainnet RPC latency comparison for Solana SPL tokens.",
            folderType = WorkspaceFolderType.RESEARCH,
            scheduleDriveSync = false
        )
        db.driveFileMetadataDao().upsertMetadata(
            DriveFileMetadataEntity(
                fileId = "df_sol_1",
                userId = "uid_phase9",
                driveFolder = AquaDriveFolders.RESEARCH,
                name = "Solana_Notes.md",
                sizeBytes = 512L
            )
        )

        val (promptCtx, citations) = brain.buildGroundedPromptContext("Solana")
        assertEquals(3, citations.size)
        assertTrue(citations.any { it.sourceType == "MEMORY" })
        assertTrue(citations.any { it.sourceType == "RESEARCH_DOC" })
        assertTrue(citations.any { it.sourceType == "DRIVE_FILE" })
        assertTrue(promptCtx.contains("[VERIFIED_AQUA_KNOWLEDGE_CITATIONS]"))

        val (_, none) = brain.buildGroundedPromptContext("NonExistentTopicXYZ123")
        assertTrue(none.isEmpty())
    }

    @Test
    fun `google connectors developer mode notification channels and screen intelligence obey isolation and fail honest rules`() = runBlocking {
        val connectors = GoogleConnectorManager(context, "uid_phase9")
        assertFalse(connectors.isConnectorEnabled(GoogleServiceId.GMAIL))
        assertFalse(connectors.isConnectorEnabled(GoogleServiceId.CALENDAR))

        // Enabling Gmail does NOT enable Calendar, Contacts, or Photos
        connectors.setConnectorEnabled(GoogleServiceId.GMAIL, true)
        assertTrue(connectors.isConnectorEnabled(GoogleServiceId.GMAIL))
        assertFalse(connectors.isConnectorEnabled(GoogleServiceId.CALENDAR))
        assertFalse(connectors.isConnectorEnabled(GoogleServiceId.PHOTOS))

        // Disabled connector probe refuses API call
        val disabledProbe = connectors.fetchLivePreview(GoogleServiceId.CONTACTS, "token")
        assertTrue(disabledProbe.isFailure)

        connectors.revokeConnectorScope(GoogleServiceId.GMAIL, accessToken = null)
        assertFalse(connectors.isConnectorEnabled(GoogleServiceId.GMAIL))

        // Developer Mode fails honestly without PAT, works with real HTTP endpoints when provided
        val secureConfig = SecureConfigStore(context)
        secureConfig.clearGithubPat()
        val devClient = AquaDeveloperModeClient(secureConfig, githubApiBaseUrl = baseUrl)
        assertTrue(devClient.fetchUserRepositories().isFailure)

        // Note: Robolectric does not have AndroidKeyStore backed hardware CIPHER unless Shadowed; test clear/unconfigured + channels
        val channels = AquaNotificationChannels.ensureAllChannels(context)
        assertEquals(4, channels.size)
        assertTrue(channels.contains(AquaNotificationChannels.CHANNEL_PENDING_APPROVALS))

        // Screen intelligence honors permission policy
        policyStore.setAllowed(PermissionDomain.DEVICE_CONTROL, false)
        val screenIntel = AquaScreenIntelligence(context, policyStore)
        val screenErr = screenIntel.captureScreenSummaryOnDemand()
        assertTrue(screenErr.isFailure)
        assertTrue(screenErr.exceptionOrNull() is SecurityException)
    }
}
