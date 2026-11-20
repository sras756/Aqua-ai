package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.core.agent.AgentOrchestrator
import com.example.core.agent.RiskTier
import com.example.core.agent.TaskState
import com.example.core.agent.ToolRiskClassifier
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.data.local.AquaCacheDatabase
import com.example.data.model.UserSettings
import com.example.data.repository.AquaFirestoreRepository
import com.example.device.AndroidDeviceController
import com.example.wallet.FlashWalletConnector
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase3AgentEngineTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase
    private lateinit var policyStore: PermissionPolicyStore
    private lateinit var orchestrator: AgentOrchestrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
        policyStore = PermissionPolicyStore(context, "uid_phase3")
        policyStore.resetDefaults()
        orchestrator = AgentOrchestrator(
            agentTaskDao = db.agentTaskDao(),
            auditLogDao = db.auditLogDao(),
            permissionPolicyStore = policyStore,
            userIdProvider = { "uid_phase3" }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `risk classifier separates read workspace confirm and biometric tiers across domains`() {
        val t0 = ToolRiskClassifier.classify("check_device_status")
        assertEquals(RiskTier.TIER_0_AUTO, t0.riskTier)
        assertEquals(PermissionDomain.READ_ACCESS, t0.permissionDomain)

        val t1 = ToolRiskClassifier.classify("toggle_flashlight")
        assertEquals(RiskTier.TIER_1_WORKSPACE, t1.riskTier)
        assertEquals(PermissionDomain.DEVICE_CONTROL, t1.permissionDomain)

        val t2 = ToolRiskClassifier.classify("backup_history_to_drive")
        assertEquals(RiskTier.TIER_2_CONFIRM, t2.riskTier)
        assertEquals(PermissionDomain.CLOUD_STORAGE, t2.permissionDomain)

        val t3 = ToolRiskClassifier.classify("wallet_sign_message")
        assertEquals(RiskTier.TIER_3_BIOMETRIC_CONFIRM, t3.riskTier)
        assertEquals(PermissionDomain.FINANCIAL_AUTHORIZATION, t3.permissionDomain)
    }

    @Test
    fun `agent orchestrator enforces state machine transitions and redacted audit log`() = runBlocking {
        val taskId = orchestrator.createTask("Send AIzaSyFakeKeyValue0000123456 report", source = "chat")
        val created = db.agentTaskDao().getTask(taskId)
        assertEquals(TaskState.CREATED.name, created?.state)
        assertFalse(created!!.title.contains("AIzaSyFakeKeyValue0000123456"))

        orchestrator.transitionTask(taskId, TaskState.PLANNING)
        orchestrator.transitionTask(taskId, TaskState.EXECUTING, stepIndex = 1, totalSteps = 2)
        orchestrator.transitionTask(taskId, TaskState.VERIFYING, summary = "Verified cleanly")
        orchestrator.transitionTask(taskId, TaskState.COMPLETED, stepIndex = 2, totalSteps = 2, summary = "Done")

        val done = db.agentTaskDao().getTask(taskId)
        assertEquals(TaskState.COMPLETED.name, done?.state)
        assertEquals(2, done?.currentStepIndex)

        val logs = db.auditLogDao().getRecentAuditLogs("uid_phase3", 20)
        assertTrue(logs.any { it.outcome == "CREATED" && !it.redactedDetail.contains("AIzaSyFakeKeyValue0000123456") })
        assertTrue(logs.any { it.outcome == "COMPLETED" })
    }

    @Test
    fun `agent orchestrator rejects denied permission domains and cancels running job cleanly`() = runBlocking {
        policyStore.setAllowed(PermissionDomain.DEVICE_CONTROL, false)
        val taskId = orchestrator.createTask("Toggle Torch", source = "voice")
        val guardDenied = orchestrator.guardToolExecution(taskId, "toggle_flashlight", "bearer token ya29.abcxyz123456")
        assertTrue(guardDenied.isFailure)

        val logs = db.auditLogDao().getRecentAuditLogs("uid_phase3", 20)
        val denialEntry = logs.firstOrNull { it.outcome == "DENIED_POLICY" }
        assertTrue(denialEntry != null)
        assertFalse(denialEntry!!.redactedDetail.contains("ya29.abcxyz123456"))

        // Cancellation cleanup
        val runningJob: Job = launch { delay(10_000L) }
        orchestrator.registerJob(taskId, runningJob)
        orchestrator.cancelTask(taskId, "User tapped Stop")
        assertTrue(runningJob.isCancelled)
        assertEquals(TaskState.CANCELLED.name, db.agentTaskDao().getTask(taskId)?.state)
    }

    @Test
    fun `tool calling engine enforces permission policy store denials and logs completions`() = runBlocking {
        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.aquaai.vqrzmn")
                    .setProjectId("test-phase3")
                    .setApiKey("test-api-key")
                    .build()
            )
        } else {
            FirebaseApp.getInstance()
        }
        val firestoreRepo = AquaFirestoreRepository(FirebaseFirestore.getInstance(app))
        val taskId = orchestrator.createTask("Tool Policy Check")
        val engine = ToolCallingEngine(
            deviceController = AndroidDeviceController(context),
            browserController = AquaBrowserController(context),
            walletConnector = FlashWalletConnector(context),
            firestoreRepo = firestoreRepo,
            agentOrchestrator = orchestrator,
            activeTaskIdProvider = { taskId }
        )

        // Allow READ_ACCESS, run check_device_status
        val allowedRes = engine.executeFunctionCall(
            call = FunctionCallPart("check_device_status", emptyMap()),
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertEquals(JsonPrimitive(true), allowedRes["success"])

        // Deny READ_ACCESS, check_device_status must be blocked by Policy
        policyStore.setAllowed(PermissionDomain.READ_ACCESS, false)
        val blockedRes = engine.executeFunctionCall(
            call = FunctionCallPart("check_device_status", emptyMap()),
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertEquals(JsonPrimitive(false), blockedRes["success"])
        assertTrue(blockedRes["message"].toString().contains("Security Center"))
    }
}
