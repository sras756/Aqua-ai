package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.ai.ToolCallingEngine
import com.example.base.FirestoreEmulatorTestBase
import com.example.browser.AquaBrowserController
import com.example.data.model.AquaRoutine
import com.example.data.model.AquaSkill
import com.example.data.model.ChatMessage
import com.example.data.model.MemoryItem
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.device.AndroidDeviceController
import com.example.wallet.FlashWalletConnector
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AquaFirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun authenticatedUser_canSaveAndObserveSettingsAndConversationsAndMessages() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AquaFirestoreRepository(firestore)

        val settingsResult = withTimeout(TIMEOUT_MS) {
            repo.saveSettings(UserSettings(preferredModel = UserSettings.MODEL_HYDROGEN))
        }
        assertTrue(settingsResult.isSuccess)

        val convId = "conv_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val createConvResult = withTimeout(TIMEOUT_MS) {
            repo.createConversation("Voice Briefing", UserSettings.MODEL_HYDROGEN, convId)
        }
        assertTrue(createConvResult.isSuccess)

        val msgResult = withTimeout(TIMEOUT_MS) {
            repo.addMessage(
                ChatMessage(
                    conversationId = convId,
                    role = "user",
                    content = "Hey Aqua, summarize today's schedule",
                    modelUsed = UserSettings.MODEL_HYDROGEN
                )
            )
        }
        assertTrue(msgResult.isSuccess)

        val observedMessages = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(convId).first { it.isNotEmpty() }
        }
        assertEquals(1, observedMessages.size)
        assertEquals("Hey Aqua, summarize today's schedule", observedMessages.first().content)
    }

    @Test
    fun authenticatedUser_canCreateObserveUpdateSkillsMemoriesAndWallets() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AquaFirestoreRepository(firestore)

        val skillResult = withTimeout(TIMEOUT_MS) {
            repo.saveSkill(
                AquaSkill(
                    name = "Code Explainer",
                    description = "Explains Kotlin and Python files",
                    version = "1.0.0",
                    format = "markdown",
                    instructions = "Break down functions step by step.",
                    requiredPermissions = listOf("SAF_READ"),
                    tools = listOf("analyze_file"),
                    securityStatus = "verified",
                    securityReport = "Clean declarative skill.",
                    enabled = true,
                    sourceLabel = "test"
                )
            )
        }
        assertTrue(skillResult.isSuccess)
        val skillId = skillResult.getOrThrow()

        val skills = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSkills().first { list -> list.any { it.id == skillId } }
        }
        assertTrue(skills.any { it.id == skillId })

        val toggleResult = withTimeout(TIMEOUT_MS) { repo.toggleSkillEnabled(skillId, false) }
        assertTrue(toggleResult.isSuccess)

        val memResult = withTimeout(TIMEOUT_MS) {
            repo.saveMemory(
                MemoryItem(
                    category = "preference",
                    title = "Concise Answers",
                    content = "Keep responses under 3 sentences when speaking.",
                    approvedByUser = true
                )
            )
        }
        assertTrue(memResult.isSuccess)

        val walletId = "w_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val walletResult = withTimeout(TIMEOUT_MS) {
            repo.saveWalletConfig(
                WalletConfig(
                    id = walletId,
                    walletProductName = "Flash Wallet",
                    developerName = "Flash Labs",
                    packageName = "com.flash.wallet",
                    integrationMethod = "read_only_rpc",
                    networkName = "Ethereum Mainnet",
                    rpcEndpoint = "https://ethereum-rpc.publicnode.com",
                    publicAddress = "0x0000000000000000000000000000000000000000",
                    connectionStatus = "read_only"
                )
            )
        }
        assertTrue(walletResult.isSuccess)

        // Regression test: re-syncing existing walletId with createdAt == null (e.g. deriveNewAccount/selectAccount)
        val walletUpdateResult = withTimeout(TIMEOUT_MS) {
            repo.saveWalletConfig(
                WalletConfig(
                    id = walletId,
                    walletProductName = "Aqua Primary Vault",
                    developerName = "Aqua Native Self-Custody",
                    packageName = "",
                    integrationMethod = "read_only_rpc",
                    networkName = "Polygon PoS",
                    rpcEndpoint = "https://polygon-bor-rpc.publicnode.com",
                    publicAddress = "0x1111111111111111111111111111111111111111",
                    connectionStatus = "connected",
                    createdAt = null
                )
            )
        }
        assertTrue("Re-saving existing walletId with null createdAt must succeed", walletUpdateResult.isSuccess)

        val observedWallets = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeWalletConfigs().first { list ->
                list.any { it.id == walletId && it.networkName == "Polygon PoS" }
            }
        }
        assertTrue(observedWallets.any { it.walletProductName == "Aqua Primary Vault" })
    }

    @Test
    fun unauthenticatedCaller_observeMemories_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = AquaFirestoreRepository(firestore)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeMemories().first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val isPermissionOrUnauth = (caught is IllegalStateException) ||
            (caught?.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertTrue("Expected auth guard or PERMISSION_DENIED, got $caught", isPermissionOrUnauth)
    }

    @Test
    fun crossUserAccess_directDocRead_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = AquaFirestoreRepository(firestore)
        val memId = withTimeout(TIMEOUT_MS) {
            aliceRepo.saveMemory(
                MemoryItem(
                    category = "fact",
                    title = "Private Fact",
                    content = "Alice only",
                    approvedByUser = true
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        var denied = false
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("memories").document(memId)
                    .get()
                    .let { com.google.android.gms.tasks.Tasks.await(it) }
            }
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            denied = fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        }
        assertTrue("Expected cross-user read to fail with PERMISSION_DENIED", denied)
    }

    @Test
    fun authenticatedUser_canCreateObserveUpdateAndDeleteRoutines() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AquaFirestoreRepository(firestore)

        val saveRes = withTimeout(TIMEOUT_MS) {
            repo.saveRoutine(
                AquaRoutine(
                    title = "Battery & Mail Check",
                    prompt = "Inspect device status and summarize unread Gmail messages.",
                    category = "productivity",
                    speakResult = false
                )
            )
        }
        assertTrue(saveRes.isSuccess)
        val routineId = saveRes.getOrThrow()

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeRoutines().first { list -> list.any { it.id == routineId } }
        }
        assertTrue(observed.any { it.id == routineId && it.title == "Battery & Mail Check" })

        val recordRes = withTimeout(TIMEOUT_MS) {
            repo.recordRoutineRun(routineId, "Completed cleanly")
        }
        assertTrue(recordRes.isSuccess)

        val deleteRes = withTimeout(TIMEOUT_MS) { repo.deleteRoutine(routineId) }
        assertTrue(deleteRes.isSuccess)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ToolCallingEngine(
            deviceController = AndroidDeviceController(ctx),
            browserController = AquaBrowserController(ctx),
            walletConnector = FlashWalletConnector(ctx),
            firestoreRepo = repo
        )
        org.junit.Assert.assertNotNull(engine.buildToolDeclarations())
    }

    @Test
    fun authenticatedUser_canSaveAndObserveAgeAndGenderSettings() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AquaFirestoreRepository(firestore)

        val saveRes = withTimeout(TIMEOUT_MS) {
            repo.saveSettings(
                UserSettings(
                    preferredModel = UserSettings.MODEL_HYDROGEN,
                    ageYears = 16,
                    ageGroup = "teen_13_17",
                    gender = "female",
                    ageGateConfirmed = true
                )
            )
        }
        assertTrue(saveRes.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSettings().first { it.ageGateConfirmed && it.ageYears == 16 }
        }
        assertEquals("teen_13_17", observed.ageGroup)
        assertEquals("female", observed.gender)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_aqua@test.com"
        const val BOB_EMAIL = "bob_aqua@test.com"
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
