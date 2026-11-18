package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AquaRoutine
import com.example.data.model.AquaSkill
import com.example.data.model.BrowsingTab
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class AquaFirestoreRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userRoot(uid: String) = db.collection("users").document(uid)

    private fun resolveSafeDocRef(
        colRef: com.google.firebase.firestore.CollectionReference,
        rawId: String?
    ): Pair<com.google.firebase.firestore.DocumentReference, Boolean> {
        val candidate = rawId?.trim().orEmpty()
        if (candidate.isEmpty()) return colRef.document() to false
        if (VALID_ID_REGEX.matches(candidate)) return colRef.document(candidate) to true
        val sanitized = candidate.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        return if (sanitized.isNotEmpty() && VALID_ID_REGEX.matches(sanitized)) {
            colRef.document(sanitized) to true
        } else {
            colRef.document() to false
        }
    }

    private fun <T> Flow<T>.withFirestoreErrorLogging(
        uid: String,
        op: OperationType,
        path: String
    ): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
        throw error
    }

    // --- SETTINGS ---

    fun observeSettings(): Flow<UserSettings> = flow {
        val uid = requireUserId()
        val docRef = userRoot(uid).collection("settings").document("preferences")
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(
                            UserSettings::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: UserSettings(userId = uid)
                    } else {
                        UserSettings(userId = uid)
                    }
                }
                .withFirestoreErrorLogging(uid, OperationType.GET, docRef.path)
        )
    }

    suspend fun saveSettings(settings: UserSettings): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = userRoot(uid).collection("settings").document("preferences")
        val existingSnap = docRef.get().await()
        if (existingSnap.exists()) {
            val updates = mapOf(
                "preferredModel" to settings.preferredModel.take(100),
                "thinkingEnabled" to settings.thinkingEnabled,
                "searchGroundingEnabled" to settings.searchGroundingEnabled,
                "mapsGroundingEnabled" to settings.mapsGroundingEnabled,
                "wakeWordEnabled" to settings.wakeWordEnabled,
                "wakeWordPhrase" to settings.wakeWordPhrase.ifBlank { "Hey Aqua" }.take(64),
                "voiceLanguage" to settings.voiceLanguage.ifBlank { "en-US" }.take(32),
                "voiceSpeed" to settings.voiceSpeed.coerceIn(0.25, 3.0),
                "voicePitch" to settings.voicePitch.coerceIn(0.25, 3.0),
                "useGeminiTts" to settings.useGeminiTts,
                "autonomousReversibleActions" to settings.autonomousReversibleActions,
                "darkThemeMode" to settings.darkThemeMode.ifBlank { "aqua_abyss" }.take(32),
                "useCustomDirectApiKey" to settings.useCustomDirectApiKey,
                "ageYears" to settings.ageYears.coerceIn(0, 120),
                "ageGroup" to settings.ageGroup.takeIf { it in VALID_AGE_GROUPS }.orEmpty().ifBlank { "unset" },
                "gender" to settings.gender.takeIf { it in VALID_GENDERS }.orEmpty().ifBlank { "unspecified" },
                "ageGateConfirmed" to settings.ageGateConfirmed,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "preferredModel" to settings.preferredModel.take(100),
                "thinkingEnabled" to settings.thinkingEnabled,
                "searchGroundingEnabled" to settings.searchGroundingEnabled,
                "mapsGroundingEnabled" to settings.mapsGroundingEnabled,
                "wakeWordEnabled" to settings.wakeWordEnabled,
                "wakeWordPhrase" to settings.wakeWordPhrase.ifBlank { "Hey Aqua" }.take(64),
                "voiceLanguage" to settings.voiceLanguage.ifBlank { "en-US" }.take(32),
                "voiceSpeed" to settings.voiceSpeed.coerceIn(0.25, 3.0),
                "voicePitch" to settings.voicePitch.coerceIn(0.25, 3.0),
                "useGeminiTts" to settings.useGeminiTts,
                "autonomousReversibleActions" to settings.autonomousReversibleActions,
                "darkThemeMode" to settings.darkThemeMode.ifBlank { "aqua_abyss" }.take(32),
                "useCustomDirectApiKey" to settings.useCustomDirectApiKey,
                "ageYears" to settings.ageYears.coerceIn(0, 120),
                "ageGroup" to settings.ageGroup.takeIf { it in VALID_AGE_GROUPS }.orEmpty().ifBlank { "unset" },
                "gender" to settings.gender.takeIf { it in VALID_GENDERS }.orEmpty().ifBlank { "unspecified" },
                "ageGateConfirmed" to settings.ageGateConfirmed,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/settings/preferences")
    }

    // --- CONVERSATIONS & MESSAGES ---

    fun observeConversations(): Flow<List<Conversation>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/conversations"
        emitAll(
            userRoot(uid).collection("conversations")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        Conversation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun createConversation(
        title: String,
        modelUsed: String,
        customId: String? = null
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("conversations")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, customId)
        val safeTitle = title.ifBlank { "New Conversation" }.take(120)
        val safeModel = modelUsed.ifBlank { UserSettings.MODEL_HYDROGEN }.take(100)
        if (hasExplicitId && docRef.get().await().exists()) {
            docRef.update(
                mapOf(
                    "title" to safeTitle,
                    "modelUsed" to safeModel,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "summary" to "",
                "modelUsed" to safeModel,
                "messageCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.CREATE, "users/$uid/conversations")
    }

    suspend fun updateConversationMeta(
        conversationId: String,
        title: String? = null,
        summary: String? = null,
        modelUsed: String? = null,
        incrementMessageCountBy: Long = 0L
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(conversationId.isNotBlank())
        val docRef = userRoot(uid).collection("conversations").document(conversationId)
        val updates = mutableMapOf<String, Any>(
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (title != null) updates["title"] = title.ifBlank { "Conversation" }.take(120)
        if (summary != null) updates["summary"] = summary.take(500)
        if (modelUsed != null) updates["modelUsed"] = modelUsed.ifBlank { UserSettings.MODEL_HYDROGEN }.take(100)
        if (incrementMessageCountBy != 0L) {
            updates["messageCount"] = FieldValue.increment(incrementMessageCountBy)
        }
        docRef.update(updates).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid/conversations/$conversationId")
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val convRef = userRoot(uid).collection("conversations").document(conversationId)
        val messages = convRef.collection("messages").get().await()
        for (doc in messages.documents) {
            doc.reference.delete().await()
        }
        convRef.delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/conversations/$conversationId")
    }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/conversations/$conversationId/messages"
        emitAll(
            userRoot(uid).collection("conversations").document(conversationId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun addMessage(message: ChatMessage): Result<String> = runCatching {
        val uid = requireUserId()
        require(message.conversationId.isNotBlank() && VALID_ID_REGEX.matches(message.conversationId)) {
            "Valid conversationId is required."
        }
        val convRef = userRoot(uid).collection("conversations").document(message.conversationId)
        val (msgRef, hasExplicitId) = resolveSafeDocRef(convRef.collection("messages"), message.id)
        val safeRole = message.role.lowercase().takeIf { it in VALID_MESSAGE_ROLES } ?: "user"
        val safeContent = message.content.take(32000)
        val safeModel = message.modelUsed.take(100)
        val safeToolActions = message.toolActionsJson.take(16000)
        val safeGrounding = message.groundingSourcesJson.take(16000)
        if (hasExplicitId && msgRef.get().await().exists()) {
            msgRef.update(
                mapOf(
                    "content" to safeContent,
                    "modelUsed" to safeModel,
                    "toolActionsJson" to safeToolActions,
                    "groundingSourcesJson" to safeGrounding,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "conversationId" to message.conversationId,
                "role" to safeRole,
                "content" to safeContent,
                "modelUsed" to safeModel,
                "attachmentName" to message.attachmentName.take(255),
                "attachmentMime" to message.attachmentMime.take(128),
                "toolActionsJson" to safeToolActions,
                "groundingSourcesJson" to safeGrounding,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
        }
        msgRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(
            e,
            OperationType.CREATE,
            "users/$uid/conversations/${message.conversationId}/messages"
        )
    }

    // --- MEMORIES ---

    fun observeMemories(): Flow<List<MemoryItem>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/memories"
        emitAll(
            userRoot(uid).collection("memories")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        MemoryItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun saveMemory(memory: MemoryItem): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("memories")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, memory.id)
        val safeCategory = memory.category.lowercase().takeIf { it in VALID_MEMORY_CATEGORIES } ?: "fact"
        val safeTitle = memory.title.ifBlank { "Memory" }.take(120)
        val safeContent = memory.content.ifBlank { "-" }.take(4000)
        if (hasExplicitId && docRef.get().await().exists()) {
            val updates = mapOf(
                "category" to safeCategory,
                "title" to safeTitle,
                "content" to safeContent,
                "approvedByUser" to memory.approvedByUser,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "category" to safeCategory,
                "title" to safeTitle,
                "content" to safeContent,
                "approvedByUser" to memory.approvedByUser,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/memories")
    }

    suspend fun deleteMemory(memoryId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("memories").document(memoryId).delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/memories/$memoryId")
    }

    // --- SKILLS ---

    fun observeSkills(): Flow<List<AquaSkill>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/skills"
        emitAll(
            userRoot(uid).collection("skills")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        AquaSkill::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun saveSkill(skill: AquaSkill): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("skills")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, skill.id)
        val safeName = skill.name.ifBlank { "Unnamed Skill" }.take(100)
        val safeDesc = skill.description.take(1000)
        val safeVersion = skill.version.ifBlank { "1.0.0" }.take(32)
        val safeFormat = skill.format.lowercase().takeIf { it in VALID_SKILL_FORMATS } ?: "custom"
        val safeInstructions = skill.instructions.ifBlank { "No instructions provided." }.take(16000)
        val safePermissions = skill.requiredPermissions.take(20)
        val safeTools = skill.tools.take(20)
        val safeConfig = skill.configJson.take(8000)
        val safeSecurityStatus = skill.securityStatus.lowercase().takeIf { it in VALID_SECURITY_STATUSES } ?: "warning"
        val safeSecurityReport = skill.securityReport.take(2000)
        val safeSource = skill.sourceLabel.take(255)
        if (hasExplicitId && docRef.get().await().exists()) {
            val updates = mapOf(
                "name" to safeName,
                "description" to safeDesc,
                "version" to safeVersion,
                "format" to safeFormat,
                "instructions" to safeInstructions,
                "requiredPermissions" to safePermissions,
                "tools" to safeTools,
                "configJson" to safeConfig,
                "securityStatus" to safeSecurityStatus,
                "securityReport" to safeSecurityReport,
                "enabled" to skill.enabled,
                "sourceLabel" to safeSource,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "name" to safeName,
                "description" to safeDesc,
                "version" to safeVersion,
                "format" to safeFormat,
                "instructions" to safeInstructions,
                "requiredPermissions" to safePermissions,
                "tools" to safeTools,
                "configJson" to safeConfig,
                "securityStatus" to safeSecurityStatus,
                "securityReport" to safeSecurityReport,
                "enabled" to skill.enabled,
                "sourceLabel" to safeSource,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/skills")
    }

    suspend fun toggleSkillEnabled(skillId: String, enabled: Boolean): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("skills").document(skillId)
            .update(mapOf("enabled" to enabled, "updatedAt" to FieldValue.serverTimestamp()))
            .await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid/skills/$skillId")
    }

    suspend fun deleteSkill(skillId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("skills").document(skillId).delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/skills/$skillId")
    }

    // --- WALLET CONFIG ---

    fun observeWalletConfigs(): Flow<List<WalletConfig>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/wallets"
        emitAll(
            userRoot(uid).collection("wallets")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        WalletConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun saveWalletConfig(config: WalletConfig): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("wallets")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, config.id)
        val safeProductName = config.walletProductName.ifBlank { "Flash Wallet" }.take(100)
        val safeDeveloperName = config.developerName.take(100)
        val safePackageName = config.packageName.take(150)
        val safeMethod = config.integrationMethod.lowercase().takeIf { it in VALID_WALLET_METHODS } ?: "unverified"
        val safeNetworkName = config.networkName.ifBlank { "Ethereum Mainnet" }.take(64)
        val safeRpcEndpoint = config.rpcEndpoint.take(300)
        val safePublicAddress = config.publicAddress.take(128)
        val safeStatus = config.connectionStatus.lowercase().takeIf { it in VALID_WALLET_STATUSES } ?: "disconnected"
        val safeDocsUrl = config.docsUrl.take(300)

        if (hasExplicitId && docRef.get().await().exists()) {
            val updates = mapOf(
                "walletProductName" to safeProductName,
                "developerName" to safeDeveloperName,
                "packageName" to safePackageName,
                "integrationMethod" to safeMethod,
                "networkName" to safeNetworkName,
                "rpcEndpoint" to safeRpcEndpoint,
                "publicAddress" to safePublicAddress,
                "connectionStatus" to safeStatus,
                "docsUrl" to safeDocsUrl,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "walletProductName" to safeProductName,
                "developerName" to safeDeveloperName,
                "packageName" to safePackageName,
                "integrationMethod" to safeMethod,
                "networkName" to safeNetworkName,
                "rpcEndpoint" to safeRpcEndpoint,
                "publicAddress" to safePublicAddress,
                "connectionStatus" to safeStatus,
                "docsUrl" to safeDocsUrl,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/wallets")
    }

    suspend fun deleteWalletConfig(walletId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("wallets").document(walletId).delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/wallets/$walletId")
    }

    // --- BROWSER TABS ---

    fun observeBrowserTabs(): Flow<List<BrowsingTab>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/browser_tabs"
        emitAll(
            userRoot(uid).collection("browser_tabs")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        BrowsingTab::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun saveBrowserTab(tab: BrowsingTab): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("browser_tabs")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, tab.id)
        val safeTitle = tab.title.take(200)
        val safeUrl = tab.url.ifBlank { "https://duckduckgo.com" }.take(2048)
        val safeSummary = tab.lastSummary.take(4000)
        if (hasExplicitId && docRef.get().await().exists()) {
            val updates = mapOf(
                "title" to safeTitle,
                "url" to safeUrl,
                "lastSummary" to safeSummary,
                "isActive" to tab.isActive,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "url" to safeUrl,
                "lastSummary" to safeSummary,
                "isActive" to tab.isActive,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/browser_tabs")
    }

    suspend fun deleteBrowserTab(tabId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("browser_tabs").document(tabId).delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/browser_tabs/$tabId")
    }

    // --- AUTONOMOUS ROUTINES ---

    fun observeRoutines(): Flow<List<AquaRoutine>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/routines"
        emitAll(
            userRoot(uid).collection("routines")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        AquaRoutine::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .withFirestoreErrorLogging(uid, OperationType.LIST, path)
        )
    }

    suspend fun saveRoutine(routine: AquaRoutine): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = userRoot(uid).collection("routines")
        val (docRef, hasExplicitId) = resolveSafeDocRef(colRef, routine.id)
        val validCat = routine.category.lowercase().takeIf {
            it in setOf("productivity", "device", "browser", "crypto", "custom")
        } ?: "custom"
        val safeTitle = routine.title.ifBlank { "Aqua Routine" }.take(100)
        val safePrompt = routine.prompt.ifBlank { "Check device status" }.take(4000)
        val safeRunCount = routine.runCount.coerceIn(0L, 1_000_000L)
        val safeLastRunSummary = routine.lastRunSummary.take(1000)
        if (hasExplicitId && docRef.get().await().exists()) {
            val updates = mapOf(
                "title" to safeTitle,
                "prompt" to safePrompt,
                "category" to validCat,
                "speakResult" to routine.speakResult,
                "runCount" to safeRunCount,
                "lastRunSummary" to safeLastRunSummary,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
        } else {
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "prompt" to safePrompt,
                "category" to validCat,
                "speakResult" to routine.speakResult,
                "runCount" to safeRunCount,
                "lastRunSummary" to safeLastRunSummary,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        docRef.id
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.WRITE, "users/$uid/routines")
    }

    suspend fun recordRoutineRun(routineId: String, summary: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("routines").document(routineId)
            .update(
                mapOf(
                    "runCount" to FieldValue.increment(1L),
                    "lastRunSummary" to summary.take(1000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid/routines/$routineId")
    }

    suspend fun deleteRoutine(routineId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        userRoot(uid).collection("routines").document(routineId).delete().await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/routines/$routineId")
    }

    // --- GOOGLE DRIVE HISTORY EXPORT / RESTORE ---

    suspend fun fetchAllConversationsWithMessagesOnce(): Result<List<Pair<Conversation, List<ChatMessage>>>> =
        runCatching {
            val uid = requireUserId()
            val convSnaps = userRoot(uid).collection("conversations")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            val convs = convSnaps.toObjects(
                Conversation::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            convs.map { conv ->
                val msgSnaps = userRoot(uid).collection("conversations")
                    .document(conv.id)
                    .collection("messages")
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .get()
                    .await()
                val msgs = msgSnaps.toObjects(
                    ChatMessage::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                conv to msgs
            }
        }

    suspend fun fetchMemoriesOnce(): Result<List<MemoryItem>> = runCatching {
        val uid = requireUserId()
        val memSnaps = userRoot(uid).collection("memories")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get()
            .await()
        memSnaps.toObjects(
            MemoryItem::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        )
    }

    suspend fun importDriveSnapshot(snapshot: DriveHistorySnapshot): Result<Pair<Int, Int>> =
        runCatching {
            val uid = requireUserId()
            val validIdRegex = Regex("^[a-zA-Z0-9_\\-]{1,128}$")
            var restoredConvs = 0
            var restoredMemories = 0

            val convsCol = userRoot(uid).collection("conversations")
            for (conv in snapshot.conversations) {
                val convRef = if (validIdRegex.matches(conv.id)) {
                    convsCol.document(conv.id)
                } else {
                    convsCol.document()
                }
                val convExists = convRef.get().await().exists()
                if (!convExists) {
                    val createPayload = mapOf(
                        "userId" to uid,
                        "title" to conv.title.ifBlank { "Restored Conversation" }.take(120),
                        "summary" to conv.summary.take(500),
                        "modelUsed" to conv.modelUsed.ifBlank { UserSettings.MODEL_HYDROGEN }.take(100),
                        "messageCount" to conv.messages.size.toLong().coerceIn(0L, 100000L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    convRef.set(createPayload).await()
                    restoredConvs++
                }

                val msgsCol = convRef.collection("messages")
                for (msg in conv.messages) {
                    val msgRef = if (validIdRegex.matches(msg.id)) {
                        msgsCol.document(msg.id)
                    } else {
                        msgsCol.document()
                    }
                    if (!msgRef.get().await().exists()) {
                        val safeRole = msg.role.takeIf { it in setOf("user", "model", "agent", "tool") } ?: "user"
                        val msgPayload = mapOf(
                            "userId" to uid,
                            "conversationId" to convRef.id,
                            "role" to safeRole,
                            "content" to msg.content.take(32000),
                            "modelUsed" to msg.modelUsed.take(100),
                            "attachmentName" to msg.attachmentName.take(255),
                            "attachmentMime" to msg.attachmentMime.take(128),
                            "toolActionsJson" to msg.toolActionsJson.take(16000),
                            "groundingSourcesJson" to msg.groundingSourcesJson.take(16000),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        msgRef.set(msgPayload).await()
                    }
                }
            }

            val memCol = userRoot(uid).collection("memories")
            for (mem in snapshot.memories) {
                val memRef = if (validIdRegex.matches(mem.id)) {
                    memCol.document(mem.id)
                } else {
                    memCol.document()
                }
                if (!memRef.get().await().exists()) {
                    val safeCat = mem.category.takeIf { it in setOf("instruction", "preference", "fact", "reminder") } ?: "fact"
                    val memPayload = mapOf(
                        "userId" to uid,
                        "category" to safeCat,
                        "title" to mem.title.ifBlank { "Restored Memory" }.take(120),
                        "content" to mem.content.ifBlank { "-" }.take(4000),
                        "approvedByUser" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    memRef.set(memPayload).await()
                    restoredMemories++
                }
            }

            restoredConvs to restoredMemories
        }

    private companion object {
        val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]{1,128}$")
        val VALID_WALLET_METHODS = setOf("wallet_connect", "deep_link", "android_intent", "read_only_rpc", "unverified")
        val VALID_WALLET_STATUSES = setOf("connected", "read_only", "disconnected", "unsupported")
        val VALID_MEMORY_CATEGORIES = setOf("instruction", "preference", "fact", "reminder")
        val VALID_SKILL_FORMATS = setOf("json", "yaml", "markdown", "zip", "custom")
        val VALID_SECURITY_STATUSES = setOf("verified", "warning", "blocked")
        val VALID_MESSAGE_ROLES = setOf("user", "model", "agent", "tool")
        val VALID_AGE_GROUPS = setOf("unset", "under_13_blocked", "teen_13_17", "adult_18_plus")
        val VALID_GENDERS = setOf("unspecified", "female", "male", "non_binary", "prefer_not_to_say")
    }
}
