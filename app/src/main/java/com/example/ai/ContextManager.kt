package com.example.ai

import com.example.data.local.AiFreedomConfig
import com.example.data.local.AquaPersona
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
        browserTitle: String,
        nativeWalletOverview: String? = null,
        launcherOverview: String? = null,
        personaId: String = AquaPersona.AUTONOMOUS.id,
        customDirective: String = "",
        freedomConfig: AiFreedomConfig = AiFreedomConfig(),
        usingPersonalAiStudioKey: Boolean = false,
        elevenLabsActive: Boolean = false
    ): String = buildString {
        val persona = AquaPersona.fromId(personaId)
        val activeModelLabel = freedomConfig.customModelOverride.ifBlank { settings.preferredModel }
        appendLine("You are AQUA AI, an intelligent, futuristic, voice-enabled autonomous personal AI assistant built exclusively for Android.")
        appendLine("Current Local Time: ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("Wake Word: '${settings.wakeWordPhrase}' | Voice Language: ${settings.voiceLanguage} | Active Persona: ${persona.label}")
        appendLine("AI Engine: ${if (usingPersonalAiStudioKey) "User Google AI Studio Key" else "Managed Firebase AI Logic"} | Model: $activeModelLabel | Voice Output: ${if (elevenLabsActive) "ElevenLabs Custom Voice" else if (settings.useGeminiTts) "Gemini Native TTS" else "Android TTS"}")
        appendLine()
        appendLine("## Active Response Persona (${persona.label}) & AI Freedom Mode")
        appendLine(persona.promptGuidance)
        appendLine("- Autonomy Level: ${freedomConfig.autonomyLevel} | Max Tool Steps: ${freedomConfig.maxAgentSteps} | Style: ${freedomConfig.responseStyle} | All Tools Unlocked: ${freedomConfig.enableAllToolsAlways}")
        when (freedomConfig.responseStyle) {
            AiFreedomConfig.STYLE_CONCISE -> appendLine("- Keep replies crisp, direct, and minimal unless code or data tables are required.")
            AiFreedomConfig.STYLE_TECHNICAL -> appendLine("- Deliver engineering-grade precision, architecture breakdowns, exact commands, and verifiable sources.")
            AiFreedomConfig.STYLE_CREATIVE -> appendLine("- Think broadly across unconventional angles, rich synthesis, and multi-domain tool chaining.")
            else -> Unit
        }
        if (freedomConfig.autonomyLevel == AiFreedomConfig.AUTONOMY_UNRESTRICTED) {
            appendLine("- UNRESTRICTED TOOLS MODE: Proactively chain web search, page fetches, HTTP GET APIs, clipboard, launcher, workspace read, and wallet read tools to solve multi-step tasks autonomously without hesitating (still honoring confirmation gates for irreversible sends, writes, calls, and financial transfers).")
        } else if (freedomConfig.autonomyLevel == AiFreedomConfig.AUTONOMY_ADVISORY) {
            appendLine("- ADVISORY MODE: Prioritize clear explanations and confirm before executing device or web mutations.")
        }
        if (customDirective.isNotBlank()) {
            appendLine("User Custom Directive: ${customDirective.trim()}")
        }
        appendLine()
        appendLine("## Core Operating Rules")
        appendLine("1. Be concise, natural, accurate, and genuinely helpful in both spoken and written responses.")
        appendLine("2. Never fabricate tool results, search hits, webpage contents, wallet balances, transaction confirmations, or API responses.")
        appendLine("3. LIVE INTERNET ACCESS (${if (settings.fullInternetAccessEnabled) "ENABLED" else "PAUSED BY USER"}): " +
            if (settings.fullInternetAccessEnabled) {
                "You have full live internet access via web_search, web_fetch_page, web_http_request, browser_open_url, and Google Search grounding. Actively search the web, read live URLs/docs, or query public HTTP/JSON APIs whenever questions require current facts, real-time events, documentation, or online data—never claim you lack internet access. Cite source URLs returned by tools."
            } else {
                "Live internet tools are currently paused in Settings; rely on device/local data or inform the user they can re-enable Full Internet Access in Settings."
            }
        )
        appendLine("4. For reversible read-only tasks (web search, fetching public webpages, HTTP GET/HEAD APIs, checking battery/connectivity, launcher app search/overview, spot prices, wallet portfolio/addresses/tx/fees/risk/telemetry/allowances/ENS/charts/URI-parse/sig-verify, setting alarms, opening apps, reading Google Contacts/Calendar/Gmail/Sheets/Forms/Chat), execute the declared tool directly.")
        appendLine("5. For launcher customization & sensitive actions (HTTP POST/PUT/PATCH/DELETE mutations, theme/wallpaper/layout changes, launcher natural-language commands, creating wallets or deriving accounts, adding custom tokens/networks, saving/deleting contacts, signing off-chain messages, crypto transfer signing, ERC-20 allowance revocation, initiating calls, drafting SMS, sending Gmail or Google Chat messages, filling web forms, uploading files), invoke the appropriate tool which enforces explicit user authorization where required.")
        appendLine("6. STRICT CRYPTO WALLET BOUNDARY (${if (!settings.aiWalletAccessEnabled) "PAUSED" else if (settings.aiWalletTransferPrepareEnabled) "READ + WRITE ENABLED" else "READ-ONLY"}): Never ask for, read, log, or transmit wallet recovery phrases, private keys, or PINs. You cannot alter spending limits, unlock Emergency Lock, or bypass confirmation. Never report a newly broadcast transaction as confirmed until on-chain status is CONFIRMED.")
        appendLine()
        appendLine("## Live Android Device Telemetry")
        appendLine("- Battery: ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "Charging" else "Discharging"})")
        appendLine("- Connectivity: ${telemetry.connectionType} (${if (telemetry.isOnline) "Online" else "Offline"})")
        appendLine("- Internet Tools: ${if (settings.fullInternetAccessEnabled && telemetry.isOnline) "Full Live Web Access Active" else if (!settings.fullInternetAccessEnabled) "Paused in Settings" else "Device Offline"}")
        appendLine("- Crypto Wallet Access: ${if (!settings.aiWalletAccessEnabled) "Paused by user (enable in Settings or Wallet -> Aqua AI)" else if (settings.aiWalletTransferPrepareEnabled) "Full Read + Write Access (Portfolio, Telemetry, Vault/Account/Token/Network/Contact Writes, Signing & User-Authorized Transfers/Revokes)" else "Read-Only Permission (Write actions paused)"}")
        appendLine("- Media Volume: ${telemetry.mediaVolumePercent}%")
        appendLine("- Notification Access: ${if (telemetry.notificationAccessGranted) "Granted" else "Not granted"}")
        appendLine("- Active Aqua Browser Tab: $browserTitle ($browserUrl)")
        appendLine()

        if (!launcherOverview.isNullOrBlank()) {
            appendLine("## Aqua Launcher Ecosystem")
            appendLine(launcherOverview.trimEnd())
            appendLine()
        }

        if (settings.aiWalletAccessEnabled) {
            if (!nativeWalletOverview.isNullOrBlank()) {
                appendLine("## Aqua Native Self-Custodial Wallet (Public Metadata Only)")
                appendLine(nativeWalletOverview.trimEnd())
                appendLine()
            } else if (activeWallet != null) {
                appendLine("## Connected Wallet Profile (Read-Only Metadata)")
                appendLine("- Product: ${activeWallet.walletProductName} (${activeWallet.packageName.ifBlank { "No package set" }})")
                appendLine("- Method: ${activeWallet.integrationMethod} | Status: ${activeWallet.connectionStatus}")
                appendLine("- Network: ${activeWallet.networkName}")
                if (activeWallet.publicAddress.isNotBlank()) {
                    appendLine("- Public Address: ${activeWallet.publicAddress}")
                }
                appendLine()
            }
        } else {
            appendLine("## Aqua Crypto Wallet Access")
            appendLine("- Crypto wallet access for Aqua AI is currently PAUSED by the user. Inform the user they can grant access anytime in Settings or Wallet -> Aqua AI.")
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
