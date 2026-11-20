package com.example.core

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.core.agent.AgentOrchestrator
import com.example.core.device.AquaSafFileController
import com.example.core.device.MediaPlaybackController
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.data.local.AquaCacheDatabase
import com.example.data.model.UserSettings
import com.example.data.repository.AquaFirestoreRepository
import com.example.device.AndroidDeviceController
import com.example.device.AquaAccessibilityService
import com.example.device.AquaNotificationListenerService
import com.example.security.ComplianceState
import com.example.security.PlayComplianceInspector
import com.example.wallet.FlashWalletConnector
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.firestore.FirebaseFirestore
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
class Phase4DeviceControlTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
        AquaAccessibilityService.setDisclosureAccepted(context, false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `media playback controller dispatches valid commands and rejects unknown actions`() {
        val media = MediaPlaybackController(context)
        assertTrue(media.dispatchCommand("play_pause").isSuccess)
        assertTrue(media.dispatchCommand("next").isSuccess)
        assertTrue(media.dispatchCommand("invalid_cmd").isFailure)
        assertTrue(media.queryActiveSessions().isEmpty())
    }

    @Test
    fun `saf file controller and accessibility disclosure obey Play-compliant opt-in rules`() {
        val saf = AquaSafFileController(context)
        assertTrue(saf.getAuthorizedTreeUris().isEmpty())
        assertEquals("sample.txt", saf.resolveDisplayName(Uri.parse("content://docs/sample.txt")))

        assertFalse(AquaAccessibilityService.hasAcceptedDisclosure(context))
        AquaAccessibilityService.setDisclosureAccepted(context, true)
        assertTrue(AquaAccessibilityService.hasAcceptedDisclosure(context))

        val deviceController = AndroidDeviceController(context)
        val telemetry = deviceController.getTelemetry()
        assertTrue(telemetry.thermalStatusLabel.isNotBlank())
        assertFalse(telemetry.accessibilityEnabled)

        val complianceReport = PlayComplianceInspector.evaluate(
            context,
            UserSettings(ageYears = 25, ageGateConfirmed = true)
        )
        assertEquals(
            ComplianceState.COMPLIANT,
            complianceReport.checks.first { it.id == PlayComplianceInspector.CHECK_PERMISSIONS_MINIMIZATION }.status
        )
    }

    @Test
    fun `notification listener dismiss fails safely when disconnected and device control honors policy`() = runBlocking {
        assertFalse(AquaNotificationListenerService.dismissNotification("non_existent_key"))

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.aquaai.vqrzmn")
                    .setProjectId("test-phase4")
                    .setApiKey("test-api-key")
                    .build()
            )
        } else {
            FirebaseApp.getInstance()
        }
        val firestoreRepo = AquaFirestoreRepository(FirebaseFirestore.getInstance(app))
        val policyStore = PermissionPolicyStore(context, "uid_phase4")
        policyStore.resetDefaults()
        val orchestrator = AgentOrchestrator(db.agentTaskDao(), db.auditLogDao(), policyStore) { "uid_phase4" }
        val engine = ToolCallingEngine(
            deviceController = AndroidDeviceController(context),
            browserController = AquaBrowserController(context),
            walletConnector = FlashWalletConnector(context),
            firestoreRepo = firestoreRepo,
            agentOrchestrator = orchestrator
        )

        val playRes = engine.executeFunctionCall(
            call = FunctionCallPart("media_playback_control", mapOf("action" to JsonPrimitive("pause"))),
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertEquals(JsonPrimitive(true), playRes["success"])

        val accNotEnabled = engine.executeFunctionCall(
            call = FunctionCallPart("accessibility_perform_action", mapOf("action" to JsonPrimitive("read_screen"))),
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertEquals(JsonPrimitive(false), accNotEnabled["success"])

        policyStore.setAllowed(PermissionDomain.DEVICE_CONTROL, false)
        val blockedMedia = engine.executeFunctionCall(
            call = FunctionCallPart("media_playback_control", mapOf("action" to JsonPrimitive("next"))),
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertEquals(JsonPrimitive(false), blockedMedia["success"])
    }
}
