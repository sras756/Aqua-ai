package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserSettings(
    @DocumentId val id: String = "preferences",
    val userId: String = "",
    val preferredModel: String = MODEL_HYDROGEN,
    val thinkingEnabled: Boolean = true,
    val searchGroundingEnabled: Boolean = true,
    val mapsGroundingEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = "Hey Aqua",
    val voiceLanguage: String = "en-US",
    val voiceSpeed: Double = 1.0,
    val voicePitch: Double = 1.0,
    val useGeminiTts: Boolean = true,
    val autonomousReversibleActions: Boolean = true,
    val darkThemeMode: String = "aqua_abyss",
    val useCustomDirectApiKey: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val MODEL_HYDROGEN = "gemini-4-hydrogen"
        const val MODEL_ARGON = "gemini-4-argon"
        const val MODEL_CARBON = "gemini-4-carbon"
        const val MODEL_FLASH_LITE = "gemini-3.1-flash-lite"
        const val MODEL_FLASH_38 = "gemini-3.8-flash"
        const val MODEL_LIVE = "gemini-3.8-live"
        const val MODEL_TTS = "gemini-3.8-flash-tts"
    }
}

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "New Conversation",
    val summary: String = "",
    val modelUsed: String = UserSettings.MODEL_HYDROGEN,
    val messageCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val role: String = "user",
    val content: String = "",
    val modelUsed: String = "",
    val attachmentName: String = "",
    val attachmentMime: String = "",
    val toolActionsJson: String = "",
    val groundingSourcesJson: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MemoryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val category: String = "instruction",
    val title: String = "",
    val content: String = "",
    val approvedByUser: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AquaSkill(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    val format: String = "custom",
    val instructions: String = "",
    val requiredPermissions: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val configJson: String = "{}",
    val securityStatus: String = "verified",
    val securityReport: String = "",
    val enabled: Boolean = true,
    val sourceLabel: String = "user-created",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WalletConfig(
    @DocumentId val id: String = "",
    val userId: String = "",
    val walletProductName: String = "",
    val developerName: String = "",
    val packageName: String = "",
    val integrationMethod: String = "unverified",
    val networkName: String = "Ethereum Mainnet",
    val rpcEndpoint: String = "",
    val publicAddress: String = "",
    val connectionStatus: String = "disconnected",
    val docsUrl: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BrowsingTab(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val url: String = "",
    val lastSummary: String = "",
    val isActive: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

enum class AquaCoreState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    EXECUTING_TOOL,
    ERROR
}

data class PendingAuthorization(
    val id: String,
    val actionTitle: String,
    val actionType: String,
    val description: String,
    val parametersSummary: List<Pair<String, String>>,
    val isFinancialOrSecurity: Boolean = false,
    val onApprove: suspend () -> String,
    val onDeny: suspend () -> String
)

data class ImportedAttachment(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val extractedTextPreview: String,
    val rawBytes: ByteArray? = null,
    val isImage: Boolean = false,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportedAttachment) return false
        return uriString == other.uriString && displayName == other.displayName
    }

    override fun hashCode(): Int {
        return 31 * uriString.hashCode() + displayName.hashCode()
    }
}

data class AgentStepTrace(
    val stepName: String,
    val toolName: String,
    val status: String,
    val detail: String
)
