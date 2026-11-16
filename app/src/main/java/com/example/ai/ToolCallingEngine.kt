package com.example.ai

import com.example.browser.AquaBrowserController
import com.example.data.local.AiFreedomConfig
import com.example.data.model.AgentStepTrace
import com.example.data.model.MemoryItem
import com.example.data.model.PendingAuthorization
import com.example.data.model.UserSettings
import com.example.data.model.WalletConfig
import com.example.data.repository.AquaFirestoreRepository
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.data.repository.GoogleWorkspaceRepository
import com.example.data.repository.WorkspaceService
import com.example.device.AndroidDeviceController
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.LauncherAiController
import com.example.launcher.model.LauncherThemePreset
import com.example.launcher.model.WallpaperType
import com.example.network.AquaWebClient
import com.example.wallet.FlashWalletConnector
import com.example.wallet.ai.AquaAiWalletBridge
import com.example.wallet.core.TxStatus
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ToolCallingEngine(
    private val deviceController: AndroidDeviceController,
    private val browserController: AquaBrowserController,
    private val walletConnector: FlashWalletConnector,
    private val firestoreRepo: AquaFirestoreRepository,
    private val driveHistoryRepo: GoogleDriveHistoryRepository? = null,
    private val workspaceRepo: GoogleWorkspaceRepository? = null,
    private val aiWalletBridge: AquaAiWalletBridge? = null,
    private val webClient: AquaWebClient = AquaWebClient(),
    private val onWalletMutated: () -> Unit = {},
    private val launcherEngine: AquaLauncherEngine? = null,
    private val freedomConfigProvider: (() -> AiFreedomConfig)? = null,
    private val aiStatusProvider: (() -> String)? = null,
    private val onSpeakWithElevenLabs: ((String) -> String)? = null
) {

    fun buildToolDeclarations(
        settings: UserSettings? = null,
        promptHint: String = "",
        unlockAllTools: Boolean = false
    ): Tool = AquaToolCatalog.buildFirebaseTool(settings, promptHint, unlockAllTools)

    fun buildRestToolDeclarations(
        settings: UserSettings? = null,
        promptHint: String = "",
        unlockAllTools: Boolean = false
    ): JSONArray = AquaToolCatalog.buildRestFunctionDeclarationsJson(settings, promptHint, unlockAllTools)
    // Unified declarations provided via AquaToolCatalog above.

    suspend fun executeFunctionCall(
        call: FunctionCallPart,
        settings: UserSettings,
        activeWallet: WalletConfig?,
        onNavigateToBrowser: (String) -> Unit,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        onTrace: (AgentStepTrace) -> Unit
    ): JsonObject {
        val name = call.name
        val args = call.args

        fun argStr(key: String): String =
            args[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        fun argInt(key: String, default: Int = 0): Int =
            args[key]?.jsonPrimitive?.intOrNull ?: default

        fun argBool(key: String, default: Boolean = false): Boolean =
            args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

        return try {
            when (name) {
                "check_device_status" -> {
                    val t = deviceController.getTelemetry()
                    onTrace(AgentStepTrace("Telemetry", name, "completed", "Battery ${t.batteryPercent}%, ${t.connectionType}"))
                    jsonResult(
                        true,
                        "Battery: ${t.batteryPercent}% (${if (t.isCharging) "Charging" else "Not charging"}), " +
                            "Network: ${t.connectionType} (${if (t.isOnline) "Online" else "Offline"}), " +
                            "Media Volume: ${t.mediaVolumePercent}%, Flashlight: ${if (t.flashlightEnabled) "ON" else "OFF"}, " +
                            "Notification Access: ${if (t.notificationAccessGranted) "Granted" else "Not Granted"}"
                    )
                }

                "toggle_flashlight" -> {
                    val enable = argBool("enable", true)
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Toggle Flashlight",
                        type = "Device Control",
                        desc = "Switch device flashlight ${if (enable) "ON" else "OFF"}.",
                        params = listOf("State" to if (enable) "ON" else "OFF"),
                        requestAuthorization = requestAuthorization
                    ) {
                        deviceController.toggleFlashlight(enable).getOrThrow()
                    }
                    onTrace(AgentStepTrace("Flashlight", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "set_media_volume" -> {
                    val pct = argInt("percent", 50)
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Adjust Media Volume",
                        type = "Device Control",
                        desc = "Set Android media volume to $pct%.",
                        params = listOf("Volume" to "$pct%"),
                        requestAuthorization = requestAuthorization
                    ) {
                        deviceController.setMediaVolumePercent(pct).getOrThrow()
                    }
                    onTrace(AgentStepTrace("Media Volume", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "launch_app" -> {
                    val appName = argStr("appName")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Launch Application",
                        type = "App Launch",
                        desc = "Open '$appName' on this device.",
                        params = listOf("Target App" to appName),
                        requestAuthorization = requestAuthorization
                    ) {
                        deviceController.launchApp(appName).getOrThrow()
                    }
                    onTrace(AgentStepTrace("Launch App", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "open_android_settings" -> {
                    val section = argStr("section").ifBlank { "general" }
                    val msg = deviceController.openAndroidSettings(section).getOrThrow()
                    onTrace(AgentStepTrace("Settings", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "set_alarm" -> {
                    val hour = argInt("hour", 8)
                    val minute = argInt("minute", 0)
                    val label = argStr("label").ifBlank { "Aqua AI Alarm" }
                    val msg = deviceController.setAlarm(hour, minute, label).getOrThrow()
                    onTrace(AgentStepTrace("Alarm", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "create_reminder" -> {
                    val title = argStr("title").ifBlank { "Reminder" }
                    val details = argStr("details")
                    val openCal = argBool("openCalendar", false)
                    firestoreRepo.saveMemory(
                        MemoryItem(
                            category = "reminder",
                            title = title,
                            content = details.ifBlank { title },
                            approvedByUser = true
                        )
                    ).getOrThrow()
                    val calMsg = if (openCal) {
                        deviceController.createCalendarReminder(title, details).getOrThrow()
                    } else {
                        "Saved reminder '$title' to Aqua Memory."
                    }
                    onTrace(AgentStepTrace("Reminder", name, "completed", calMsg))
                    jsonResult(true, calMsg)
                }

                "read_notifications" -> {
                    if (!deviceController.isNotificationAccessEnabled()) {
                        onTrace(AgentStepTrace("Notifications", name, "permission_required", "Notification access not granted"))
                        jsonResult(
                            false,
                            "Notification listener permission is not granted. Ask the user to enable Notification Access for Aqua AI in Android Settings."
                        )
                    } else {
                        val list = deviceController.readRecentNotifications()
                        val text = if (list.isEmpty()) {
                            "No active notifications currently in the shade."
                        } else {
                            list.take(10).joinToString("\n") { n ->
                                "- [${n.packageName}] ${n.title}: ${n.text}"
                            }
                        }
                        onTrace(AgentStepTrace("Notifications", name, "completed", "Read ${list.size} notifications"))
                        jsonResult(true, text)
                    }
                }

                "initiate_phone_call" -> {
                    val phone = argStr("phoneNumber")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Initiate Phone Call",
                            actionType = "Communication (Irreversible Action Guard)",
                            description = "Aqua wants to open the phone dialer for $phone.",
                            parametersSummary = listOf("Phone Number" to phone),
                            isFinancialOrSecurity = false,
                            onApprove = { deviceController.openDialer(phone).getOrThrow() },
                            onDeny = { "User cancelled phone dialer action." }
                        )
                    )
                    val msg = if (approved) {
                        deviceController.openDialer(phone).getOrThrow()
                    } else {
                        "User declined phone dialer initiation."
                    }
                    onTrace(AgentStepTrace("Phone Call", name, if (approved) "completed" else "denied", msg))
                    jsonResult(approved, msg)
                }

                "prepare_sms_message" -> {
                    val recipient = argStr("recipient")
                    val body = argStr("body")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Prepare SMS Message",
                            actionType = "Communication Confirmation",
                            description = "Review recipient and drafted SMS before opening Messaging.",
                            parametersSummary = listOf("Recipient" to recipient, "Message" to body),
                            isFinancialOrSecurity = false,
                            onApprove = { deviceController.prepareSmsMessage(recipient, body).getOrThrow() },
                            onDeny = { "User cancelled SMS preparation." }
                        )
                    )
                    val msg = if (approved) {
                        deviceController.prepareSmsMessage(recipient, body).getOrThrow()
                    } else {
                        "User declined SMS preparation."
                    }
                    onTrace(AgentStepTrace("SMS Draft", name, if (approved) "completed" else "denied", msg))
                    jsonResult(approved, msg)
                }

                "web_search" -> {
                    if (!settings.fullInternetAccessEnabled) {
                        onTrace(AgentStepTrace("Web Search", name, "disabled", "Full Internet Access disabled in Settings"))
                        return jsonResult(false, "Full Internet Access is currently disabled in Aqua Settings.")
                    }
                    val query = argStr("query")
                    val maxResults = argInt("maxResults", 8)
                    val res = webClient.searchWeb(query, maxResults).getOrThrow()
                    val formatted = buildString {
                        appendLine("Live Web Search Results for '${res.query}':")
                        if (res.instantAnswer.isNotBlank()) {
                            appendLine("Instant Summary: ${res.instantAnswer}")
                            appendLine()
                        }
                        res.hits.forEachIndexed { idx, hit ->
                            appendLine("[${idx + 1}] ${hit.title} (${hit.sourceEngine})")
                            appendLine("URL: ${hit.url}")
                            if (hit.snippet.isNotBlank()) appendLine("Snippet: ${hit.snippet}")
                            appendLine()
                        }
                    }.trim()
                    onTrace(AgentStepTrace("Web Search", name, "completed", "Found ${res.hits.size} live results"))
                    jsonResult(true, formatted.take(8000))
                }

                "web_fetch_page" -> {
                    if (!settings.fullInternetAccessEnabled) {
                        onTrace(AgentStepTrace("Web Fetch", name, "disabled", "Full Internet Access disabled in Settings"))
                        return jsonResult(false, "Full Internet Access is currently disabled in Aqua Settings.")
                    }
                    val url = argStr("url")
                    val page = webClient.fetchWebpage(url).getOrThrow()
                    val formatted = buildString {
                        appendLine("Title: ${page.title}")
                        appendLine("URL: ${page.finalUrl} (HTTP ${page.statusCode}, ${page.contentType})")
                        appendLine()
                        appendLine(page.textContent.take(6500))
                        if (page.links.isNotEmpty()) {
                            appendLine()
                            appendLine("Top Links:")
                            page.links.take(10).forEach { link ->
                                appendLine("- ${link.text}: ${link.href}")
                            }
                        }
                    }.trim()
                    onTrace(AgentStepTrace("Web Fetch", name, "completed", "Fetched ${page.finalUrl}"))
                    jsonResult(true, formatted.take(8500))
                }

                "web_http_request" -> {
                    if (!settings.fullInternetAccessEnabled) {
                        onTrace(AgentStepTrace("HTTP Request", name, "disabled", "Full Internet Access disabled in Settings"))
                        return jsonResult(false, "Full Internet Access is currently disabled in Aqua Settings.")
                    }
                    val method = argStr("method").uppercase().ifBlank { "GET" }
                    val url = argStr("url")
                    val headersJson = argStr("headersJson").ifBlank { null }
                    val body = argStr("body").ifBlank { null }
                    val isReadOnly = method == "GET" || method == "HEAD"

                    if (!isReadOnly) {
                        val approved = requestAuthorization(
                            PendingAuthorization(
                                id = UUID.randomUUID().toString(),
                                actionTitle = "Authorize Outbound HTTP $method Request",
                                actionType = "Internet API Mutation",
                                description = "Review endpoint URL and payload before Aqua transmits this $method request.",
                                parametersSummary = listOf(
                                    "Method" to method,
                                    "URL" to url,
                                    "Body Preview" to (body ?: "(empty)").take(160)
                                ),
                                isFinancialOrSecurity = false,
                                onApprove = { "User approved HTTP $method" },
                                onDeny = { "User declined HTTP $method" }
                            )
                        )
                        if (!approved) {
                            onTrace(AgentStepTrace("HTTP $method", name, "denied", "User declined request"))
                            return jsonResult(false, "HTTP $method request declined by user.")
                        }
                    }

                    val resp = webClient.httpRequest(method, url, headersJson, body).getOrThrow()
                    val formatted = "HTTP ${resp.statusCode} ${resp.method} ${resp.finalUrl} (${resp.contentType}):\n${resp.body}"
                    onTrace(AgentStepTrace("HTTP $method", name, "completed", "HTTP ${resp.statusCode} from ${resp.finalUrl}"))
                    jsonResult(resp.statusCode in 200..399, formatted.take(8500))
                }

                "browser_open_url" -> {
                    val urlOrQuery = argStr("urlOrQuery")
                    val resolved = browserController.navigateTo(urlOrQuery)
                    onNavigateToBrowser(resolved)
                    val msg = "Opened '$resolved' in Aqua Browser."
                    onTrace(AgentStepTrace("Browser Navigate", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "browser_read_current_page" -> {
                    val text = browserController.extractCurrentPageText().getOrThrow()
                    onTrace(AgentStepTrace("Read Page", name, "completed", "Extracted ${text.length} chars"))
                    jsonResult(true, text.take(8000))
                }

                "browser_fill_form" -> {
                    val selector = argStr("selector")
                    val value = argStr("value")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Fill Webpage Form Field",
                            actionType = "Browser Automation",
                            description = "Fill a standard input field on the open webpage (passwords and CAPTCHAs are blocked).",
                            parametersSummary = listOf("Selector" to selector, "Value" to value),
                            isFinancialOrSecurity = false,
                            onApprove = { browserController.fillStandardFormField(selector, value).getOrThrow() },
                            onDeny = { "User declined webpage form fill." }
                        )
                    )
                    val msg = if (approved) {
                        browserController.fillStandardFormField(selector, value).getOrThrow()
                    } else {
                        "User declined webpage form fill."
                    }
                    onTrace(AgentStepTrace("Fill Form", name, if (approved) "completed" else "denied", msg))
                    jsonResult(approved, msg)
                }

                "wallet_check_balance" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused. Enable 'Crypto Wallet Access for Aqua AI' in Settings or Wallet -> Aqua AI.")
                    }
                    val nativePair = aiWalletBridge?.activeWalletAndAccount()
                    if (nativePair != null) {
                        val summary = aiWalletBridge.getPortfolioSummaryText().getOrThrow()
                        onTrace(AgentStepTrace("Wallet Portfolio", name, "completed", "Fetched live multi-chain balances"))
                        jsonResult(true, summary)
                    } else if (activeWallet == null || activeWallet.publicAddress.isBlank()) {
                        onTrace(AgentStepTrace("Wallet Balance", name, "unconfigured", "No connected wallet"))
                        jsonResult(
                            false,
                            "No Aqua Wallet is created or imported yet. Direct the user to the Wallet tab to create a new wallet, import a recovery phrase, or add a watch-only address."
                        )
                    } else {
                        val snap = walletConnector.fetchLiveReadOnlySnapshot(activeWallet).getOrThrow()
                        val summary = buildString {
                            append("Network: ${snap.networkName} | Balance: ${snap.nativeBalance}")
                            if (!snap.usdtBalance.isNullOrBlank()) append(" | USDT: ${snap.usdtBalance}")
                            append(" | Recent Txs: ${snap.recentTransactions.size} | Source: ${snap.sourceInfo}")
                        }
                        onTrace(AgentStepTrace("Wallet Balance", name, "completed", summary))
                        jsonResult(true, summary)
                    }
                }

                "wallet_get_portfolio" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val includeTest = args["includeTestnets"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    val summary = bridge.getPortfolioSummaryText(includeTest).getOrThrow()
                    onTrace(AgentStepTrace("Portfolio", name, "completed", "Queried multi-chain portfolio"))
                    jsonResult(true, summary)
                }

                "wallet_get_receive_addresses" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val summary = bridge.getReceiveAddressesText().getOrThrow()
                    onTrace(AgentStepTrace("Receive Addresses", name, "completed", "Fetched derived public receiving addresses"))
                    jsonResult(true, summary)
                }

                "wallet_get_transactions" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val filter = argStr("statusFilter").ifBlank { "ALL" }
                    val summary = bridge.getTransactionsText(filter).getOrThrow()
                    onTrace(AgentStepTrace("Tx History", name, "completed", "Synced transactions (filter=$filter)"))
                    jsonResult(true, summary)
                }

                "wallet_check_tx_status" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val txHash = argStr("txHash")
                    val network = argStr("network")
                    val summary = bridge.checkTxStatusText(txHash, network).getOrThrow()
                    onTrace(AgentStepTrace("Tx Status", name, "completed", "Checked status for ${txHash.take(12)}..."))
                    jsonResult(true, summary)
                }

                "wallet_analyze_portfolio" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val analysis = bridge.analyzePortfolioText().getOrThrow()
                    onTrace(AgentStepTrace("Portfolio Audit", name, "completed", "Analyzed allocation & security"))
                    jsonResult(true, analysis)
                }

                "wallet_research_network" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val network = argStr("network")
                    val report = bridge.researchNetworkText(network).getOrThrow()
                    onTrace(AgentStepTrace("Network Research", name, "completed", "Researched ${network.ifBlank { "active network" }}"))
                    jsonResult(true, report)
                }

                "wallet_get_evm_telemetry" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val network = argStr("network")
                    val report = bridge.getEvmTelemetryText(network).getOrThrow()
                    onTrace(AgentStepTrace("EVM Telemetry", name, "completed", "Fetched live block, gas & nonce"))
                    jsonResult(true, report)
                }

                "wallet_resolve_ens" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val ensName = argStr("ensName")
                    val res = bridge.resolveEnsText(ensName).getOrThrow()
                    onTrace(AgentStepTrace("ENS Resolve", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_check_allowance" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val token = argStr("token")
                    val spender = argStr("spenderAddress")
                    val network = argStr("network")
                    val res = bridge.checkTokenAllowanceText(token, spender, network).getOrThrow()
                    onTrace(AgentStepTrace("ERC-20 Allowance", name, "completed", "Checked $token allowance"))
                    jsonResult(true, res)
                }

                "wallet_prepare_revoke_allowance" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI transfer/revoke proposals disabled by user"))
                        return jsonResult(false, "AI transaction & allowance-revoke proposals are currently paused. Enable them in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    if (bridge.isEmergencyLocked()) {
                        onTrace(AgentStepTrace("Emergency Lock", name, "blocked", "Emergency Lock is active"))
                        return jsonResult(false, "Emergency Wallet Lock is active. Unlock it manually in Wallet -> Security Center first.")
                    }
                    val token = argStr("token")
                    val spender = argStr("spenderAddress")
                    val network = argStr("network")
                    val prepared = bridge.prepareRevokeAllowance(token, spender, network).getOrThrow()
                    val needsUnlock = bridge.isVaultLockedForSigning()
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Authorize ERC-20 Approval Revoke",
                            actionType = "Native Self-Custody Signing (approve 0)",
                            description = if (needsUnlock) {
                                "Unlock your wallet vault and confirm revoking spender approval on-chain."
                            } else {
                                "Review spender, network fee, and simulation before signing approve(0) revoke."
                            },
                            parametersSummary = listOf(
                                "Network" to prepared.network.name,
                                "Token" to "${prepared.token.symbol} (${prepared.token.contractOrMintAddress})",
                                "Spender Revoked" to prepared.toAddress,
                                "Network Fee" to prepared.feeEstimate.estimatedFeeDisplay,
                                "Simulation" to prepared.simulationSummary
                            ),
                            isFinancialOrSecurity = true,
                            requiresWalletUnlock = needsUnlock,
                            onApprove = { "User approved ERC-20 revoke signing" },
                            onDeny = { "ERC-20 revoke cancelled by user." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Revoke Tx", name, "denied", "User rejected revoke authorization"))
                        return jsonResult(false, "ERC-20 approval revocation rejected by user authorization gate.")
                    }
                    val txRecord = bridge.signAndBroadcastAuthorized(prepared).getOrThrow()
                    onWalletMutated()
                    val statusNote = "Revoke broadcast submitted (status: ${txRecord.status}, 0 confirmations). Hash: ${txRecord.txHash}. Explorer: ${txRecord.explorerUrl}"
                    onTrace(AgentStepTrace("Revoke Broadcast", name, "submitted_pending", txRecord.txHash))
                    jsonResult(true, statusNote)
                }

                "wallet_get_market_chart" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val coin = argStr("coin").ifBlank { "bitcoin" }
                    val days = argInt("days", 7)
                    val currency = argStr("currency").ifBlank { "USD" }
                    val res = bridge.getMarketChartText(coin, days, currency).getOrThrow()
                    onTrace(AgentStepTrace("Market Chart", name, "completed", "Fetched ${days}d CoinGecko market chart for $coin"))
                    jsonResult(true, res)
                }

                "wallet_switch_network" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val network = argStr("network")
                    val res = bridge.switchActiveNetworkText(network).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Switch Network", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_switch_account" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val account = argStr("account")
                    val res = bridge.switchActiveAccountText(account).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Switch Account", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_switch_wallet" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val wallet = argStr("wallet")
                    val res = bridge.switchActiveWalletText(wallet).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Switch Wallet", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_get_token_info" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val token = argStr("token")
                    val network = argStr("network")
                    val info = bridge.getTokenInfoText(token, network).getOrThrow()
                    onTrace(AgentStepTrace("Token Info", name, "completed", "Inspected $token"))
                    jsonResult(true, info)
                }

                "wallet_estimate_fees" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val network = argStr("network")
                    val dest = argStr("destinationAddress")
                    val asset = argStr("assetSymbol")
                    val amount = argStr("amount").ifBlank { "0.001" }
                    val est = bridge.estimateFeesText(network, dest, asset, amount).getOrThrow()
                    onTrace(AgentStepTrace("Fee Estimate", name, "completed", est))
                    jsonResult(true, est)
                }

                "wallet_analyze_address_risk" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val address = argStr("address")
                    val network = argStr("network")
                    val report = bridge.analyzeAddressRisk(address, network).getOrThrow()
                    val msg = "Address ${report.address} on ${report.networkId}: Risk=${report.riskLevel}, ValidFormat=${report.isValidFormat}, ChecksumValid=${report.checksumValid}, IsContract=${report.isContractAddress}, SelfTransfer=${report.isSelfTransfer}. Findings: ${report.findings.joinToString("; ")}"
                    onTrace(AgentStepTrace("Risk Audit", name, "completed", "Risk=${report.riskLevel}"))
                    jsonResult(true, msg)
                }

                "wallet_lookup_contact" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val q = argStr("query")
                    val resText = bridge.lookupAddressBookText(q).getOrThrow()
                    onTrace(AgentStepTrace("Address Book", name, "completed", "Queried saved contacts"))
                    jsonResult(true, resText)
                }

                "wallet_save_contact" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val label = argStr("label")
                    val chainFamily = argStr("chainFamily")
                    val address = argStr("address")
                    val note = argStr("note")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Save Trusted Wallet Contact",
                            actionType = "Address Book Modification (Explicit Approval)",
                            description = "Confirm adding this recipient to your trusted Aqua Wallet Address Book.",
                            parametersSummary = listOf(
                                "Label" to label,
                                "Chain Family" to chainFamily.uppercase(),
                                "Address" to address,
                                "Note" to note.ifBlank { "None" }
                            ),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved saving Address Book contact" },
                            onDeny = { "Cancelled saving Address Book contact." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Save Contact", name, "denied", "User rejected saving contact"))
                        return jsonResult(false, "Saving wallet contact rejected by user.")
                    }
                    val savedMsg = bridge.saveAddressBookContact(label, chainFamily, address, note).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Save Contact", name, "completed", savedMsg))
                    jsonResult(true, savedMsg)
                }

                "wallet_delete_contact" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val queryOrId = argStr("queryOrId")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Delete Wallet Contact",
                            actionType = "Address Book Deletion (Explicit Approval)",
                            description = "Confirm removing '$queryOrId' from your trusted Aqua Wallet Address Book.",
                            parametersSummary = listOf("Contact Query / ID" to queryOrId),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved deleting contact" },
                            onDeny = { "Cancelled deleting contact." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Delete Contact", name, "denied", "User rejected deleting contact"))
                        return jsonResult(false, "Deleting wallet contact rejected by user.")
                    }
                    val deletedMsg = bridge.deleteAddressBookContactText(queryOrId).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Delete Contact", name, "completed", deletedMsg))
                    jsonResult(true, deletedMsg)
                }

                "wallet_create_wallet" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val label = argStr("label").ifBlank { "Aqua Wallet" }
                    val wordCount = if (argInt("wordCount", 12) == 24) 24 else 12
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Create New Self-Custodial Wallet",
                            actionType = "Vault Creation (Zero-Key Boundary)",
                            description = "Generate a new $wordCount-word multi-chain wallet locally using OS randomness and Android Keystore encryption.",
                            parametersSummary = listOf("Wallet Label" to label, "Word Count" to "$wordCount words"),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved wallet creation" },
                            onDeny = { "Wallet creation cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Create Wallet", name, "denied", "User declined wallet creation"))
                        return jsonResult(false, "Wallet creation cancelled by user.")
                    }
                    val resMsg = bridge.createWalletText(label, wordCount).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Create Wallet", name, "completed", "Created wallet '$label'"))
                    jsonResult(true, resMsg)
                }

                "wallet_add_account" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    if (bridge.isEmergencyLocked()) {
                        return jsonResult(false, "Emergency Wallet Lock is active. Unlock it in Wallet -> Security Center first.")
                    }
                    val label = argStr("label")
                    val needsUnlock = bridge.isVaultLockedForSigning()
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Derive New HD Account",
                            actionType = "Vault Derivation (Explicit Approval)",
                            description = "Derive a new multi-chain HD account inside your active Aqua Wallet.",
                            parametersSummary = listOf("Account Label" to label.ifBlank { "Next Sequential Account" }),
                            isFinancialOrSecurity = true,
                            requiresWalletUnlock = needsUnlock,
                            onApprove = { "User approved deriving new account" },
                            onDeny = { "Account derivation cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Add Account", name, "denied", "User rejected account derivation"))
                        return jsonResult(false, "Account derivation cancelled by user.")
                    }
                    val resMsg = bridge.addAccountText(label).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Add Account", name, "completed", "Derived new account"))
                    jsonResult(true, resMsg)
                }

                "wallet_add_watch_wallet" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val label = argStr("label").ifBlank { "Watch Portfolio" }
                    val evmAddress = argStr("evmAddress")
                    val solanaAddress = argStr("solanaAddress")
                    val bitcoinAddress = argStr("bitcoinAddress")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Add Watch-Only Wallet",
                            actionType = "Vault Import (Public Addresses)",
                            description = "Add a read-only watch portfolio tracking these public addresses.",
                            parametersSummary = listOf(
                                "Label" to label,
                                "EVM" to evmAddress.ifBlank { "None" },
                                "Solana" to solanaAddress.ifBlank { "None" },
                                "Bitcoin" to bitcoinAddress.ifBlank { "None" }
                            ),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved adding watch wallet" },
                            onDeny = { "Watch wallet cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Watch Wallet", name, "denied", "User declined watch wallet"))
                        return jsonResult(false, "Watch-only wallet creation cancelled by user.")
                    }
                    val resMsg = bridge.addWatchOnlyWalletText(label, evmAddress, solanaAddress, bitcoinAddress).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Watch Wallet", name, "completed", resMsg))
                    jsonResult(true, resMsg)
                }

                "wallet_add_custom_token" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val network = argStr("network")
                    val symbol = argStr("symbol")
                    val tokenName = argStr("name")
                    val contract = argStr("contractOrMint")
                    val decimals = argInt("decimals", 18)
                    val coingeckoId = argStr("coingeckoId")
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Add Custom Token Contract",
                            actionType = "Token Registry Write (Explicit Approval)",
                            description = "Verify token contract address and decimals before adding to your wallet.",
                            parametersSummary = listOf(
                                "Network" to network.ifBlank { "Active Network" },
                                "Symbol / Name" to "$symbol (${tokenName.ifBlank { symbol }})",
                                "Contract / Mint" to contract,
                                "Decimals" to decimals.toString()
                            ),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved custom token" },
                            onDeny = { "Custom token cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Custom Token", name, "denied", "User rejected adding token"))
                        return jsonResult(false, "Adding custom token cancelled by user.")
                    }
                    val resMsg = bridge.addCustomTokenText(network, symbol, tokenName, contract, decimals, coingeckoId).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Custom Token", name, "completed", resMsg))
                    jsonResult(true, resMsg)
                }

                "wallet_add_custom_network" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val netName = argStr("name")
                    val nativeSym = argStr("nativeSymbol")
                    val chainId = argInt("chainId", 0).toLong()
                    val rpcUrl = argStr("rpcUrl")
                    val explorerUrl = argStr("explorerUrl")
                    val isTestnet = argBool("isTestnet", false)
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Add Custom EVM RPC Network",
                            actionType = "Network Registry Write (Explicit Approval)",
                            description = "Verify HTTPS RPC endpoint and chain ID before adding a custom blockchain network.",
                            parametersSummary = listOf(
                                "Network Name" to netName,
                                "Chain ID" to chainId.toString(),
                                "Native Asset" to nativeSym,
                                "RPC URL" to rpcUrl
                            ),
                            isFinancialOrSecurity = true,
                            onApprove = { "User approved custom network" },
                            onDeny = { "Custom network cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Custom Network", name, "denied", "User rejected custom network"))
                        return jsonResult(false, "Adding custom network cancelled by user.")
                    }
                    val resMsg = bridge.addCustomNetworkText(netName, nativeSym, chainId, rpcUrl, explorerUrl, isTestnet).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Custom Network", name, "completed", resMsg))
                    jsonResult(true, resMsg)
                }

                "wallet_sign_message" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    if (bridge.isEmergencyLocked()) {
                        onTrace(AgentStepTrace("Emergency Lock", name, "blocked", "Emergency Lock is active"))
                        return jsonResult(false, "Emergency Wallet Lock is active. Unlock it manually in Wallet -> Security Center first.")
                    }
                    val chainFamily = argStr("chainFamily").ifBlank { "EVM" }
                    val message = argStr("message")
                    val needsUnlock = bridge.isVaultLockedForSigning()
                    val approved = requestAuthorization(
                        PendingAuthorization(
                            id = UUID.randomUUID().toString(),
                            actionTitle = "Sign Off-Chain Ownership Message",
                            actionType = "Cryptographic Signature (Explicit Approval)",
                            description = if (needsUnlock) {
                                "Unlock your wallet vault and confirm signing this off-chain message."
                            } else {
                                "Review the exact plain-text message before signing with your local wallet key."
                            },
                            parametersSummary = listOf(
                                "Chain Family" to chainFamily.uppercase(),
                                "Message" to message.take(240)
                            ),
                            isFinancialOrSecurity = true,
                            requiresWalletUnlock = needsUnlock,
                            onApprove = { "User approved message signature" },
                            onDeny = { "Message signing cancelled." }
                        )
                    )
                    if (!approved) {
                        onTrace(AgentStepTrace("Sign Message", name, "denied", "User declined message signing"))
                        return jsonResult(false, "Off-chain message signing rejected by user.")
                    }
                    val proof = bridge.signMessageText(chainFamily, message).getOrThrow()
                    val res = "Signed (${proof.standardName}) | Signer: ${proof.signerAddress} | Signature: ${proof.signature}"
                    onTrace(AgentStepTrace("Sign Message", name, "completed", "Signed via ${proof.standardName}"))
                    jsonResult(true, res)
                }

                "wallet_verify_message" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet read access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val chainFamily = argStr("chainFamily").ifBlank { "EVM" }
                    val signerAddress = argStr("signerAddress")
                    val message = argStr("message")
                    val signature = argStr("signature")
                    val res = bridge.verifyMessageText(chainFamily, signerAddress, message, signature).getOrThrow()
                    onTrace(AgentStepTrace("Verify Signature", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_parse_payment_uri" -> {
                    if (!settings.aiWalletAccessEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI wallet read access disabled by user"))
                        return jsonResult(false, "Crypto wallet access for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val uri = argStr("uri")
                    val res = bridge.parsePaymentUriText(uri).getOrThrow()
                    onTrace(AgentStepTrace("Parse URI", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_update_preferences" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Write", name, "paused", "AI wallet write permission disabled by user"))
                        return jsonResult(false, "Crypto Wallet Write permission for Aqua AI is currently paused in Settings or Wallet -> Aqua AI.")
                    }
                    val bridge = aiWalletBridge
                        ?: return jsonResult(false, "Aqua Wallet engine is unavailable.")
                    val currency = argStr("displayCurrencyCode").takeIf { it.isNotBlank() }
                    val testnet = args["testnetModeEnabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    val res = bridge.updateWalletPreferencesText(currency, testnet).getOrThrow()
                    onWalletMutated()
                    onTrace(AgentStepTrace("Wallet Prefs", name, "completed", res))
                    jsonResult(true, res)
                }

                "wallet_prepare_transaction", "wallet_prepare_transfer" -> {
                    if (!settings.aiWalletAccessEnabled || !settings.aiWalletTransferPrepareEnabled) {
                        onTrace(AgentStepTrace("Wallet Access", name, "paused", "AI transfer proposals disabled by user"))
                        return jsonResult(false, "AI transfer proposals are currently paused. Enable 'Crypto Wallet Access' and 'Allow AI to Prepare Transfers' in Settings or Wallet -> Aqua AI.")
                    }
                    val dest = argStr("destinationAddress")
                    val asset = argStr("assetSymbol").ifBlank { "ETH" }
                    val amount = argStr("amount")
                    val networkQuery = argStr("network")

                    val nativePair = aiWalletBridge?.activeWalletAndAccount()
                    if (aiWalletBridge != null && nativePair != null) {
                        if (aiWalletBridge.isEmergencyLocked()) {
                            onTrace(AgentStepTrace("Emergency Lock", name, "blocked", "Emergency Lock is active"))
                            return jsonResult(false, "Emergency Wallet Lock is active. Unlock it manually in Wallet -> Security Center before initiating transfers.")
                        }
                        val prepared = aiWalletBridge.prepareUnsignedTransaction(
                            networkQuery = networkQuery,
                            toAddress = dest,
                            assetSymbol = asset,
                            amountText = amount
                        ).getOrThrow()

                        val needsUnlock = aiWalletBridge.isVaultLockedForSigning()
                        val approved = requestAuthorization(
                            PendingAuthorization(
                                id = UUID.randomUUID().toString(),
                                actionTitle = "Authorize Aqua Wallet Transaction",
                                actionType = "Native Self-Custody Signing (Explicit Approval)",
                                description = if (needsUnlock) {
                                    "Unlock your wallet vault with PIN/Biometrics and review recipient, fees, simulation, and risk audit before local signing."
                                } else {
                                    "Review recipient, fees, simulation, and risk audit before local signing."
                                },
                                parametersSummary = listOf(
                                    "Network" to prepared.network.name,
                                    "From" to prepared.fromAddress,
                                    "To" to prepared.toAddress,
                                    "Amount" to "${prepared.amountDecimal.stripTrailingZeros().toPlainString()} ${prepared.token.symbol}",
                                    "Network Fee" to prepared.feeEstimate.estimatedFeeDisplay,
                                    "Total Cost" to prepared.totalCostDisplay,
                                    "Simulation" to prepared.simulationSummary,
                                    "Risk Audit" to "${prepared.riskReport.riskLevel}: ${prepared.riskReport.findings.firstOrNull().orEmpty()}"
                                ),
                                isFinancialOrSecurity = true,
                                requiresWalletUnlock = needsUnlock,
                                onApprove = { "User approved local signing" },
                                onDeny = { "Transfer cancelled by user." }
                            )
                        )
                        if (!approved) {
                            onTrace(AgentStepTrace("Tx Prep", name, "denied", "User rejected transaction authorization"))
                            return jsonResult(false, "Transaction rejected by user authorization gate.")
                        }
                        val txRecord = aiWalletBridge.signAndBroadcastAuthorized(prepared).getOrThrow()
                        onWalletMutated()
                        val statusNote = if (txRecord.status == TxStatus.PENDING) {
                            "Broadcast submitted (status: PENDING confirmation, 0 confirmations). Hash: ${txRecord.txHash}. Explorer: ${txRecord.explorerUrl}"
                        } else {
                            "Broadcast status: ${txRecord.status}. Hash: ${txRecord.txHash}"
                        }
                        onTrace(AgentStepTrace("Tx Broadcast", name, "submitted_pending", txRecord.txHash))
                        jsonResult(true, statusNote)
                    } else {
                        val wallet = activeWallet ?: WalletConfig(
                            walletProductName = "External Wallet",
                            networkName = networkQuery.ifBlank { "Ethereum Mainnet" }
                        )
                        val prepared = walletConnector.prepareTransferRequest(
                            config = wallet,
                            destinationAddress = dest,
                            assetSymbol = asset,
                            amount = amount
                        ).getOrThrow()

                        val approved = requestAuthorization(
                            PendingAuthorization(
                                id = UUID.randomUUID().toString(),
                                actionTitle = "Authorize Cryptocurrency Transfer Request",
                                actionType = "Financial Transaction (Explicit Approval Required)",
                                description = "Review all transfer parameters before handing off to your wallet's official signing mechanism. Aqua cannot confirm on-chain finality until the wallet & blockchain confirm.",
                                parametersSummary = listOf(
                                    "Destination" to prepared.destinationAddress,
                                    "Network" to prepared.networkName,
                                    "Asset & Amount" to "${prepared.amount} ${prepared.assetSymbol}",
                                    "Network Fee" to prepared.estimatedFeeDisplay,
                                    "Signing URI" to prepared.eip681Uri
                                ),
                                isFinancialOrSecurity = true,
                                onApprove = {
                                    walletConnector.launchSigningIntent(prepared.eip681Uri, wallet.packageName).getOrThrow()
                                },
                                onDeny = { "Transfer request cancelled by user." }
                            )
                        )
                        val msg = if (approved) {
                            walletConnector.launchSigningIntent(prepared.eip681Uri, wallet.packageName)
                                .getOrElse { err -> "Signing intent could not launch: ${err.message}" }
                        } else {
                            "Cryptocurrency transfer rejected by user authorization gate."
                        }
                        onTrace(AgentStepTrace("Transfer Prep", name, if (approved) "handed_to_wallet" else "denied", msg))
                        jsonResult(approved, msg)
                    }
                }

                "fetch_crypto_price" -> {
                    val coin = argStr("coin").ifBlank { "bitcoin" }
                    val (resolvedCoin, price) = walletConnector.fetchMarketPriceUsd(coin).getOrThrow()
                    val msg = "Live CoinGecko spot price for $resolvedCoin: $${String.format("%,.2f", price)} USD."
                    onTrace(AgentStepTrace("Market Price", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "save_memory_note" -> {
                    val cat = argStr("category").lowercase().let {
                        if (it in setOf("instruction", "preference", "fact", "reminder")) it else "fact"
                    }
                    val title = argStr("title").ifBlank { "Saved Note" }
                    val content = argStr("content").ifBlank { title }
                    firestoreRepo.saveMemory(
                        MemoryItem(
                            category = cat,
                            title = title,
                            content = content,
                            approvedByUser = true
                        )
                    ).getOrThrow()
                    val msg = "Saved memory [$cat] '$title'."
                    onTrace(AgentStepTrace("Save Memory", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "backup_history_to_drive" -> {
                    val repo = driveHistoryRepo
                        ?: return jsonResult(false, "Google Drive repository is not initialized.")
                    val token = repo.tryGetSilentAccessToken()
                    if (token.isNullOrBlank()) {
                        val msg = "Google Drive consent is required first. Open the Memory & History tab and tap 'Back Up to Drive' to authorize your Google Drive account."
                        onTrace(AgentStepTrace("Google Drive Sync", name, "needs_auth", msg))
                        jsonResult(false, msg)
                    } else {
                        val convsWithMsgs = firestoreRepo.fetchAllConversationsWithMessagesOnce().getOrThrow()
                        val memories = firestoreRepo.fetchMemoriesOnce().getOrThrow()
                        val snap = GoogleDriveHistoryRepository.buildSnapshot(
                            userId = convsWithMsgs.firstOrNull()?.first?.userId ?: "aqua_user",
                            conversationsWithMessages = convsWithMsgs,
                            memories = memories
                        )
                        val meta = repo.backupHistoryToAppData(token, snap).getOrThrow()
                        val msg = "Backed up ${snap.conversations.size} conversation(s) and ${snap.memories.size} memory item(s) to Google Drive (${meta.name})."
                        onTrace(AgentStepTrace("Google Drive Sync", name, "completed", msg))
                        jsonResult(true, msg)
                    }
                }

                "workspace_contacts_list" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CONTACTS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CONTACTS, name, onTrace)
                    val query = argStr("query")
                    val list = repo.listContacts(token, query).getOrThrow()
                    val summary = if (list.isEmpty()) {
                        "No matching Google Contacts found."
                    } else {
                        list.take(20).joinToString("\n") { c ->
                            "- ${c.displayName} | Email: ${c.email.ifBlank { "-" }} | Phone: ${c.phone.ifBlank { "-" }}"
                        }
                    }
                    onTrace(AgentStepTrace("Contacts", name, "completed", "Fetched ${list.size} contact(s)"))
                    jsonResult(true, summary)
                }

                "workspace_contacts_create" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CONTACTS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CONTACTS, name, onTrace)
                    val fullName = argStr("fullName")
                    val email = argStr("email")
                    val phone = argStr("phone")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Create Google Contact",
                        type = "Google Contacts",
                        desc = "Add a new contact to your Google Contacts.",
                        params = listOf("Name" to fullName, "Email" to email, "Phone" to phone),
                        requestAuthorization = requestAuthorization
                    ) {
                        val created = repo.createContact(token, fullName, email, phone).getOrThrow()
                        "Created Google Contact '${created.displayName}' (${created.resourceName})."
                    }
                    onTrace(AgentStepTrace("Create Contact", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "workspace_calendar_list" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CALENDAR.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CALENDAR, name, onTrace)
                    val maxResults = argInt("maxResults", 15)
                    val events = repo.listUpcomingEvents(token, maxResults).getOrThrow()
                    val summary = if (events.isEmpty()) {
                        "No upcoming Google Calendar events found."
                    } else {
                        events.joinToString("\n") { ev ->
                            "- ${ev.summary} (${ev.startTime} -> ${ev.endTime})${if (ev.location.isNotBlank()) " @ ${ev.location}" else ""}"
                        }
                    }
                    onTrace(AgentStepTrace("Calendar List", name, "completed", "Fetched ${events.size} event(s)"))
                    jsonResult(true, summary)
                }

                "workspace_calendar_create" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CALENDAR.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CALENDAR, name, onTrace)
                    val summary = argStr("summary")
                    val startIso = argStr("startIso")
                    val endIso = argStr("endIso")
                    val desc = argStr("description")
                    val location = argStr("location")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Create Calendar Event",
                        type = "Google Calendar",
                        desc = "Schedule an event on your primary Google Calendar.",
                        params = listOf("Title" to summary, "Start" to startIso, "End" to endIso),
                        requestAuthorization = requestAuthorization
                    ) {
                        val ev = repo.createCalendarEvent(token, summary, startIso, endIso, desc, location).getOrThrow()
                        "Created Calendar event '${ev.summary}' (${ev.startTime} to ${ev.endTime})."
                    }
                    onTrace(AgentStepTrace("Create Event", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "workspace_gmail_list" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.GMAIL.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.GMAIL, name, onTrace)
                    val query = argStr("query")
                    val max = argInt("maxResults", 10)
                    val msgs = repo.listGmailMessages(token, query, max).getOrThrow()
                    val summary = if (msgs.isEmpty()) {
                        "No Gmail messages matched your query."
                    } else {
                        msgs.joinToString("\n") { m ->
                            "- From: ${m.from} | Subject: ${m.subject} | Snippet: ${m.snippet.take(120)}"
                        }
                    }
                    onTrace(AgentStepTrace("Gmail Inbox", name, "completed", "Loaded ${msgs.size} email(s)"))
                    jsonResult(true, summary)
                }

                "workspace_gmail_send" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.GMAIL.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.GMAIL, name, onTrace)
                    val to = argStr("to")
                    val subject = argStr("subject")
                    val body = argStr("body")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = true,
                        title = "Send Email via Gmail",
                        type = "Outbound Communication (Explicit Approval Required)",
                        desc = "Review recipient, subject, and body before Aqua sends this email via your Gmail account.",
                        params = listOf("To" to to, "Subject" to subject, "Body Preview" to body.take(160)),
                        requestAuthorization = requestAuthorization
                    ) {
                        val id = repo.sendGmailMessage(token, to, subject, body).getOrThrow()
                        "Sent Gmail message to $to (message id: $id)."
                    }
                    onTrace(AgentStepTrace("Send Gmail", name, "completed", msg))
                    jsonResult(!msg.contains("declined", ignoreCase = true), msg)
                }

                "workspace_sheets_create" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.SHEETS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.SHEETS, name, onTrace)
                    val title = argStr("title")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Create Google Sheet",
                        type = "Google Sheets",
                        desc = "Create a new Google Sheets spreadsheet.",
                        params = listOf("Title" to title),
                        requestAuthorization = requestAuthorization
                    ) {
                        val sheet = repo.createSpreadsheet(token, title).getOrThrow()
                        "Created Google Sheet '${sheet.title}' (ID: ${sheet.spreadsheetId})."
                    }
                    onTrace(AgentStepTrace("Create Sheet", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "workspace_sheets_read" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.SHEETS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.SHEETS, name, onTrace)
                    val sid = argStr("spreadsheetId")
                    val range = argStr("range").ifBlank { "Sheet1!A1:J25" }
                    val res = repo.readSheetRange(token, sid, range).getOrThrow()
                    val summary = if (res.rows.isEmpty()) {
                        "Range ${res.range} in sheet ${res.spreadsheetId} is empty."
                    } else {
                        res.rows.joinToString("\n") { row -> row.joinToString(" | ") }
                    }
                    onTrace(AgentStepTrace("Read Sheet", name, "completed", "Read ${res.rows.size} row(s)"))
                    jsonResult(true, summary)
                }

                "workspace_sheets_append" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.SHEETS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.SHEETS, name, onTrace)
                    val sid = argStr("spreadsheetId")
                    val range = argStr("range").ifBlank { "Sheet1!A1" }
                    val values = argStr("valuesCsv").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Append Row to Sheet",
                        type = "Google Sheets",
                        desc = "Append a new row to spreadsheet $sid ($range).",
                        params = listOf("Spreadsheet ID" to sid, "Range" to range, "Values" to values.joinToString(", ")),
                        requestAuthorization = requestAuthorization
                    ) {
                        repo.appendSheetRow(token, sid, range, values).getOrThrow()
                    }
                    onTrace(AgentStepTrace("Append Sheet Row", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "workspace_forms_create" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.FORMS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.FORMS, name, onTrace)
                    val title = argStr("title")
                    val firstQuestion = argStr("firstQuestion")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Create Google Form",
                        type = "Google Forms",
                        desc = "Create a new Google Form.",
                        params = listOf("Title" to title, "First Question" to firstQuestion.ifBlank { "(none)" }),
                        requestAuthorization = requestAuthorization
                    ) {
                        val form = repo.createForm(token, title, firstQuestion).getOrThrow()
                        "Created Google Form '${form.title}' (ID: ${form.formId}, Responder URI: ${form.responderUri})."
                    }
                    onTrace(AgentStepTrace("Create Form", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "workspace_forms_get_responses" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.FORMS.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.FORMS, name, onTrace)
                    val formId = argStr("formId")
                    val (info, responses) = repo.getFormAndResponses(token, formId).getOrThrow()
                    val summary = buildString {
                        appendLine("Form: ${info.title} (${info.formId})")
                        if (info.questionTitles.isNotEmpty()) {
                            appendLine("Questions: ${info.questionTitles.joinToString(", ")}")
                        }
                        appendLine("Responses (${responses.size}):")
                        if (responses.isEmpty()) {
                            appendLine("- No responses submitted yet.")
                        } else {
                            responses.take(20).forEach { r ->
                                appendLine("- [${r.lastSubmittedTime}] ${r.respondentEmail.ifBlank { "Anonymous" }}: ${r.answersSummary}")
                            }
                        }
                    }
                    onTrace(AgentStepTrace("Form Responses", name, "completed", "Loaded ${responses.size} response(s)"))
                    jsonResult(true, summary)
                }

                "workspace_chat_list_spaces" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CHAT.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CHAT, name, onTrace)
                    val spaces = repo.listChatSpaces(token).getOrThrow()
                    val summary = if (spaces.isEmpty()) {
                        "No Google Chat spaces found for this account."
                    } else {
                        spaces.joinToString("\n") { s -> "- ${s.displayName} (${s.name}, type=${s.spaceType})" }
                    }
                    onTrace(AgentStepTrace("Chat Spaces", name, "completed", "Loaded ${spaces.size} space(s)"))
                    jsonResult(true, summary)
                }

                "workspace_chat_list_messages" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CHAT.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CHAT, name, onTrace)
                    val spaceName = argStr("spaceName")
                    val msgs = repo.listChatMessages(token, spaceName).getOrThrow()
                    val summary = if (msgs.isEmpty()) {
                        "No messages found in $spaceName."
                    } else {
                        msgs.joinToString("\n") { m -> "- ${m.senderName}: ${m.text}" }
                    }
                    onTrace(AgentStepTrace("Chat Messages", name, "completed", "Loaded ${msgs.size} message(s)"))
                    jsonResult(true, summary)
                }

                "workspace_chat_send_message" -> {
                    val repo = workspaceRepo ?: return jsonResult(false, "Google Workspace repository is unavailable.")
                    val token = repo.tryGetSilentToken(WorkspaceService.CHAT.scopes)
                        ?: return workspaceNeedsAuthResult(WorkspaceService.CHAT, name, onTrace)
                    val spaceName = argStr("spaceName")
                    val text = argStr("text")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = true,
                        title = "Send Google Chat Message",
                        type = "Outbound Communication (Explicit Approval Required)",
                        desc = "Confirm sending this message into Google Chat space $spaceName.",
                        params = listOf("Space" to spaceName, "Message" to text.take(160)),
                        requestAuthorization = requestAuthorization
                    ) {
                        val sent = repo.sendChatMessage(token, spaceName, text).getOrThrow()
                        "Sent Google Chat message to $spaceName (${sent.name})."
                    }
                    onTrace(AgentStepTrace("Send Chat", name, "completed", msg))
                    jsonResult(!msg.contains("declined", ignoreCase = true), msg)
                }

                "get_device_storage_and_memory" -> {
                    val s = deviceController.getStorageAndMemoryStats()
                    val msg = "Storage: ${s.storageFreeMb} MB free of ${s.storageTotalMb} MB | " +
                        "RAM: ${s.ramAvailableMb} MB available of ${s.ramTotalMb} MB (lowMemory=${s.lowMemory})"
                    onTrace(AgentStepTrace("System Resources", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "read_clipboard" -> {
                    val clipText = deviceController.readClipboardText().getOrThrow()
                    val msg = if (clipText.isBlank()) {
                        "Android clipboard is currently empty or contains non-text content."
                    } else {
                        "Clipboard text (${clipText.length} chars):\n${clipText.take(2000)}"
                    }
                    onTrace(AgentStepTrace("Read Clipboard", name, "completed", "${clipText.length} chars"))
                    jsonResult(true, msg)
                }

                "copy_to_clipboard" -> {
                    val label = argStr("label").ifBlank { "Aqua AI" }
                    val text = argStr("text")
                    val msg = deviceController.copyTextToClipboard(label, text).getOrThrow()
                    onTrace(AgentStepTrace("Copy Clipboard", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "share_text_externally" -> {
                    val title = argStr("title").ifBlank { "Shared from Aqua AI" }
                    val text = argStr("text")
                    val msg = runWithOptionalGate(
                        settings = settings,
                        isSensitive = false,
                        title = "Share via Android Sharesheet",
                        type = "External Share",
                        desc = "Open Android Sharesheet so you can choose an app to share this text.",
                        params = listOf("Title" to title, "Preview" to text.take(140)),
                        requestAuthorization = requestAuthorization
                    ) {
                        deviceController.shareTextExternally(title, text).getOrThrow()
                    }
                    onTrace(AgentStepTrace("Share Text", name, "completed", msg))
                    jsonResult(!msg.contains("declined", ignoreCase = true), msg)
                }

                "browser_extract_links" -> {
                    val links = browserController.extractCurrentPageLinks().getOrThrow()
                    val summary = if (links.isEmpty()) {
                        "No clickable http/https links found on the active page."
                    } else {
                        links.joinToString("\n") { l -> "- ${l.text}: ${l.href}" }
                    }
                    onTrace(AgentStepTrace("Extract Links", name, "completed", "Found ${links.size} links"))
                    jsonResult(true, summary)
                }

                "launcher_get_overview" -> {
                    val le = launcherEngine
                        ?: return jsonResult(false, "Aqua Launcher engine is not initialized.")
                    le.refreshDefaultLauncherStatus()
                    val st = le.state.value
                    val apps = le.installedApps.value
                    val games = le.getGameApps(st, apps)
                    val recent = le.getRecentApps(5)
                    val msg = buildString {
                        appendLine("Default Home Active: ${le.isDefaultLauncher.value} (System default: ${le.currentDefaultHomePackage.value ?: "Unknown"})")
                        appendLine("Discovered Launchable Apps: ${apps.size} | Games: ${games.size} | Hidden: ${st.hiddenPackages.size}")
                        appendLine("Active Theme: ${st.theme.presetId} (Grid ${st.theme.gridColumns}x${st.theme.gridRows}, Pages ${st.pageCount})")
                        appendLine("Wallpaper: ${st.wallpaper.typeId} (${st.wallpaper.label})")
                        if (recent.isNotEmpty()) {
                            appendLine("Recent Apps: ${recent.joinToString { it.label }}")
                        }
                    }.trim()
                    onTrace(AgentStepTrace("Launcher Overview", name, "completed", "${apps.size} apps"))
                    jsonResult(true, msg)
                }

                "launcher_search_apps" -> {
                    val le = launcherEngine
                        ?: return jsonResult(false, "Aqua Launcher engine is not initialized.")
                    val query = argStr("query").trim().lowercase()
                    val gamesOnly = argBool("gamesOnly", false)
                    val pool = if (gamesOnly) le.getGameApps() else le.installedApps.value
                    val matched = pool.filter {
                        query.isBlank() ||
                            it.label.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query) ||
                            it.category.label.lowercase().contains(query)
                    }
                    val msg = if (matched.isEmpty()) {
                        "No installed applications matched '$query'."
                    } else {
                        "Found ${matched.size} app(s):\n" + matched.take(15).joinToString("\n") {
                            "- ${it.label} (${it.packageName}, ${it.category.label})"
                        }
                    }
                    onTrace(AgentStepTrace("Search Apps", name, "completed", "${matched.size} found"))
                    jsonResult(true, msg)
                }

                "launcher_set_theme_or_wallpaper" -> {
                    val le = launcherEngine
                        ?: return jsonResult(false, "Aqua Launcher engine is not initialized.")
                    val themeId = argStr("themePresetId").trim()
                    val wallId = argStr("wallpaperTypeId").trim()
                    val notes = mutableListOf<String>()
                    if (args.containsKey("gamingMode")) {
                        val gm = argBool("gamingMode", false)
                        le.toggleGamingMode(gm)
                        notes.add("Gaming Mode -> ${if (gm) "ON" else "OFF"}")
                    }
                    if (themeId.isNotBlank()) {
                        val preset = LauncherThemePreset.fromId(themeId)
                        le.applyThemePreset(preset)
                        notes.add("Theme -> ${preset.displayName}")
                    }
                    if (wallId.isNotBlank()) {
                        val wt = WallpaperType.fromId(wallId)
                        le.updateWallpaperConfig { it.copy(typeId = wt.id, label = wt.label) }
                        notes.add("Wallpaper -> ${wt.label}")
                    }
                    val summary = if (notes.isEmpty()) "No launcher theme/wallpaper changes specified." else notes.joinToString(" • ")
                    onTrace(AgentStepTrace("Customize Launcher", name, "completed", summary))
                    jsonResult(true, summary)
                }

                "launcher_manage_layout" -> {
                    val le = launcherEngine
                        ?: return jsonResult(false, "Aqua Launcher engine is not initialized.")
                    val action = argStr("action").trim().lowercase()
                    val target = argStr("packageNameOrLabel").trim()
                    val page = argInt("pageIndex", 0)
                    val matchedApp = le.installedApps.value.firstOrNull {
                        it.packageName.equals(target, ignoreCase = true) ||
                            it.label.equals(target, ignoreCase = true) ||
                            it.label.lowercase().contains(target.lowercase())
                    }
                    val msg = when (action) {
                        "pin_home" -> {
                            val app = matchedApp ?: return jsonResult(false, "No installed app matching '$target'.")
                            le.addAppToHome(app, page)
                            "Pinned ${app.label} to Aqua Launcher Home page ${page + 1}."
                        }
                        "toggle_dock" -> {
                            val app = matchedApp ?: return jsonResult(false, "No installed app matching '$target'.")
                            le.toggleDockApp(app.packageName)
                            "Updated Aqua Launcher Dock for ${app.label}."
                        }
                        "create_folder" -> {
                            require(target.isNotBlank()) { "Folder name is required." }
                            le.createHomeFolder(target, emptyList(), page)
                            "Created folder '$target' on Home page ${page + 1}."
                        }
                        "hide_app" -> {
                            val app = matchedApp ?: return jsonResult(false, "No installed app matching '$target'.")
                            le.toggleHiddenApp(app.packageName)
                            "Toggled drawer visibility for ${app.label}."
                        }
                        else -> return jsonResult(false, "Unknown layout action '$action'.")
                    }
                    onTrace(AgentStepTrace("Launcher Layout", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "launcher_execute_command" -> {
                    val le = launcherEngine
                        ?: return jsonResult(false, "Aqua Launcher engine is not initialized.")
                    val cmd = argStr("command")
                    val aiCtrl = LauncherAiController(le)
                    val outcome = aiCtrl.executeCommand(cmd)
                    val proposal = outcome.pendingPermissionProposal
                    val finalMsg = if (proposal != null) {
                        val approved = requestAuthorization(
                            PendingAuthorization(
                                id = UUID.randomUUID().toString(),
                                actionTitle = proposal.title,
                                actionType = "Launcher System Action",
                                description = proposal.description,
                                parametersSummary = listOfNotNull(
                                    "Action" to proposal.actionKind,
                                    proposal.packageName?.let { "Package" to it }
                                ),
                                isFinancialOrSecurity = true,
                                onApprove = {
                                    aiCtrl.executeApprovedProposal(proposal)
                                        .getOrElse { "Action failed: ${it.localizedMessage}" }
                                },
                                onDeny = { "Launcher system action cancelled by user." }
                            )
                        )
                        if (approved) {
                            aiCtrl.executeApprovedProposal(proposal)
                                .getOrElse { "Action failed: ${it.localizedMessage}" }
                        } else {
                            "User declined '${proposal.title}'."
                        }
                    } else {
                        outcome.message
                    }
                    onTrace(AgentStepTrace("Launcher AI Control", name, if (outcome.executed || proposal != null) "completed" else "info", finalMsg.take(120)))
                    jsonResult(true, finalMsg)
                }

                "get_ai_freedom_config" -> {
                    val cfg = freedomConfigProvider?.invoke() ?: AiFreedomConfig()
                    val summary = aiStatusProvider?.invoke()
                        ?: "Autonomy=${cfg.autonomyLevel}, Temp=${cfg.temperature}, TopP=${cfg.topP}, MaxTokens=${cfg.maxOutputTokens}, MaxSteps=${cfg.maxAgentSteps}, UnlockAllTools=${cfg.enableAllToolsAlways}, Style=${cfg.responseStyle}"
                    onTrace(AgentStepTrace("AI Engine Status", name, "completed", "Inspected AI Freedom config"))
                    jsonResult(true, summary)
                }

                "speak_with_elevenlabs" -> {
                    val textToSpeak = argStr("text")
                    if (textToSpeak.isBlank()) {
                        return jsonResult(false, "Provide non-empty text to synthesize speech.")
                    }
                    val outcome = onSpeakWithElevenLabs?.invoke(textToSpeak)
                        ?: "Voice synthesis callback is unavailable in headless mode."
                    onTrace(AgentStepTrace("Voice Synthesis", name, "completed", outcome.take(100)))
                    jsonResult(true, outcome)
                }

                else -> {
                    onTrace(AgentStepTrace("Unknown Tool", name, "error", "Unsupported tool"))
                    jsonResult(false, "Tool '$name' is not implemented.")
                }
            }
        } catch (e: Exception) {
            onTrace(AgentStepTrace("Error Recovery", name, "failed", e.message ?: "Execution failed"))
            jsonResult(false, "Tool '$name' failed: ${e.message ?: "Unknown error"}")
        }
    }

    private suspend fun runWithOptionalGate(
        settings: UserSettings,
        isSensitive: Boolean,
        title: String,
        type: String,
        desc: String,
        params: List<Pair<String, String>>,
        requestAuthorization: suspend (PendingAuthorization) -> Boolean,
        action: suspend () -> String
    ): String {
        val autonomy = freedomConfigProvider?.invoke()?.autonomyLevel ?: AiFreedomConfig.AUTONOMY_BALANCED
        val autoRunReversible = when (autonomy) {
            AiFreedomConfig.AUTONOMY_UNRESTRICTED -> !isSensitive
            AiFreedomConfig.AUTONOMY_ADVISORY -> false
            else -> !isSensitive && settings.autonomousReversibleActions
        }
        if (autoRunReversible) {
            return action()
        }
        val approved = requestAuthorization(
            PendingAuthorization(
                id = UUID.randomUUID().toString(),
                actionTitle = title,
                actionType = type,
                description = desc,
                parametersSummary = params,
                isFinancialOrSecurity = isSensitive,
                onApprove = action,
                onDeny = { "Action '$title' cancelled by user." }
            )
        )
        return if (approved) action() else "Action '$title' was declined by user."
    }

    private fun workspaceNeedsAuthResult(
        service: WorkspaceService,
        toolName: String,
        onTrace: (AgentStepTrace) -> Unit
    ): JsonObject {
        val msg = "Google ${service.label} consent is required first. Direct the user to open the Workspace Hub from Home and connect ${service.label} (or Connect All)."
        onTrace(AgentStepTrace("Workspace Auth", toolName, "needs_auth", msg))
        return jsonResult(false, msg)
    }

    private fun jsonResult(success: Boolean, message: String): JsonObject {
        return JsonObject(
            mapOf(
                "success" to JsonPrimitive(success),
                "message" to JsonPrimitive(message)
            )
        )
    }
}
