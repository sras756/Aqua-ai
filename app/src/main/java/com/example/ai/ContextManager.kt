package com.example.ai

import com.example.data.model.AquaSkill
import com.example.data.model.MemoryItem
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.device.DeviceTelemetry
import java.text.DateFormat
import java.util.Date

object ContextManager {

    fun buildSystemInstruction(
        settings: UserSettings,
        memories: List<MemoryItem>,
        enabledSkills: List<AquaSkill>,
        activeWallet: WalletConfig?,
        telemetry: DeviceTelemetry,
        browserUrl: String,
        browserTitle: String
    ): String = buildString {
        appendLine("You are AQUA AI, an intelligent, futuristic, voice-enabled autonomous personal AI assistant built exclusively for Android.")
        appendLine("Current Local Time: ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("Wake Word: '${settings.wakeWordPhrase}' | Voice Language: ${settings.voiceLanguage}")
        appendLine()
        appendLine("## Core Operating Rules")
        appendLine("1. Be concise, natural, accurate, and genuinely helpful in both spoken and written responses.")
        appendLine("2. Never fabricate tool results, wallet balances, transaction confirmations, or webpage actions.")
        appendLine("3. For reversible read-only tasks (checking battery, connectivity, spot prices, reading open webpage, setting alarms, opening apps), execute the declared tool directly.")
        appendLine("4. For irreversible or sensitive actions (crypto transfer preparation, initiating calls, drafting SMS, filling web forms, uploading files), invoke the appropriate tool which enforces explicit user authorization.")
        appendLine("5. Never ask for or transmit wallet seed phrases, private keys, or passwords.")
        appendLine()
        appendLine("## Live Android Device Telemetry")
        appendLine("- Battery: ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "Charging" else "Discharging"})")
        appendLine("- Connectivity: ${telemetry.connectionType} (${if (telemetry.isOnline) "Online" else "Offline"})")
        appendLine("- Media Volume: ${telemetry.mediaVolumePercent}%")
        appendLine("- Notification Access: ${if (telemetry.notificationAccessGranted) "Granted" else "Not granted"}")
        appendLine("- Active Aqua Browser Tab: $browserTitle ($browserUrl)")
        appendLine()

        if (activeWallet != null) {
            appendLine("## Connected Wallet Profile (Read-Only Metadata)")
            appendLine("- Product: ${activeWallet.walletProductName} (${activeWallet.packageName.ifBlank { "No package set" }})")
            appendLine("- Method: ${activeWallet.integrationMethod} | Status: ${activeWallet.connectionStatus}")
            appendLine("- Network: ${activeWallet.networkName}")
            if (activeWallet.publicAddress.isNotBlank()) {
                appendLine("- Public Address: ${activeWallet.publicAddress}")
            }
            appendLine()
        }

        if (memories.isNotEmpty()) {
            appendLine("## User-Approved Persistent Memories & Instructions")
            memories.take(20).forEach { mem ->
                appendLine("- [${mem.category}] ${mem.title}: ${mem.content}")
            }
            appendLine()
        }

        if (enabledSkills.isNotEmpty()) {
            appendLine("## Active User-Validated Skills (Declarative Instructions)")
            enabledSkills.filter { it.enabled && it.securityStatus != "blocked" }.take(10).forEach { skill ->
                appendLine("### Skill: ${skill.name} (v${skill.version}, status=${skill.securityStatus})")
                if (skill.description.isNotBlank()) appendLine("Description: ${skill.description}")
                appendLine("Instructions: ${skill.instructions.take(1500)}")
            }
        }
    }
}
