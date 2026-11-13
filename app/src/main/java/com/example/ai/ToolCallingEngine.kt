package com.example.ai

import com.example.browser.AquaBrowserController
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
import com.example.wallet.FlashWalletConnector
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.UUID

class ToolCallingEngine(
    private val deviceController: AndroidDeviceController,
    private val browserController: AquaBrowserController,
    private val walletConnector: FlashWalletConnector,
    private val firestoreRepo: AquaFirestoreRepository,
    private val driveHistoryRepo: GoogleDriveHistoryRepository? = null,
    private val workspaceRepo: GoogleWorkspaceRepository? = null
) {

    fun buildToolDeclarations(): Tool {
        return Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = "check_device_status",
                    description = "Returns live Android battery percentage, charging state, Wi-Fi/cellular connectivity, media volume, flashlight state, and notification access status.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "toggle_flashlight",
                    description = "Turns the device camera flashlight torch ON or OFF.",
                    parameters = mapOf(
                        "enable" to Schema.boolean(description = "True to turn ON, false to turn OFF.")
                    )
                ),
                FunctionDeclaration(
                    name = "set_media_volume",
                    description = "Adjusts the Android media volume percentage between 0 and 100.",
                    parameters = mapOf(
                        "percent" to Schema.integer(description = "Target media volume percentage from 0 to 100.")
                    )
                ),
                FunctionDeclaration(
                    name = "launch_app",
                    description = "Launches an installed Android application by display name or package name.",
                    parameters = mapOf(
                        "appName" to Schema.string(description = "Application name or Android package identifier.")
                    )
                ),
                FunctionDeclaration(
                    name = "open_android_settings",
                    description = "Opens a specific Android Settings screen such as wifi, bluetooth, display, sound, notifications, battery, location, apps, or general.",
                    parameters = mapOf(
                        "section" to Schema.string(description = "Settings section name.")
                    )
                ),
                FunctionDeclaration(
                    name = "set_alarm",
                    description = "Opens the Android Alarm clock composer to set an alarm.",
                    parameters = mapOf(
                        "hour" to Schema.integer(description = "24-hour clock hour (0-23)."),
                        "minute" to Schema.integer(description = "Minute (0-59)."),
                        "label" to Schema.string(description = "Alarm label.")
                    )
                ),
                FunctionDeclaration(
                    name = "create_reminder",
                    description = "Creates a persistent reminder in Aqua Memory and opens Calendar composer if requested.",
                    parameters = mapOf(
                        "title" to Schema.string(description = "Reminder title."),
                        "details" to Schema.string(description = "Reminder details or time note."),
                        "openCalendar" to Schema.boolean(description = "True to also launch Calendar event UI.")
                    )
                ),
                FunctionDeclaration(
                    name = "read_notifications",
                    description = "Reads recent active Android notifications if Notification Listener permission is granted.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "initiate_phone_call",
                    description = "Opens the phone dialer for a number. Always requires explicit user confirmation.",
                    parameters = mapOf(
                        "phoneNumber" to Schema.string(description = "Phone number to dial.")
                    )
                ),
                FunctionDeclaration(
                    name = "prepare_sms_message",
                    description = "Prepares an SMS message in the Android messaging app. Always requires explicit user confirmation.",
                    parameters = mapOf(
                        "recipient" to Schema.string(description = "Recipient phone number or contact address."),
                        "body" to Schema.string(description = "SMS message body text.")
                    )
                ),
                FunctionDeclaration(
                    name = "browser_open_url",
                    description = "Opens a website URL or web search query in the integrated Aqua Browser.",
                    parameters = mapOf(
                        "urlOrQuery" to Schema.string(description = "Full https URL or search query.")
                    )
                ),
                FunctionDeclaration(
                    name = "browser_read_current_page",
                    description = "Extracts the current webpage title, URL, and visible text from the active Aqua Browser tab.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "browser_fill_form",
                    description = "Fills a standard non-password form input on the active webpage. Requires explicit user confirmation.",
                    parameters = mapOf(
                        "selector" to Schema.string(description = "CSS selector, input name, or element ID."),
                        "value" to Schema.string(description = "Text value to enter.")
                    )
                ),
                FunctionDeclaration(
                    name = "wallet_check_balance",
                    description = "Queries live on-chain native balance, USDT balance (if Ethereum), and recent transactions for the configured read-only public wallet address.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "fetch_crypto_price",
                    description = "Fetches real-time spot price in USD for a cryptocurrency (e.g., bitcoin, ethereum, tether, solana, bnb) from CoinGecko.",
                    parameters = mapOf(
                        "coin" to Schema.string(description = "Coin name or ticker symbol.")
                    )
                ),
                FunctionDeclaration(
                    name = "wallet_prepare_transfer",
                    description = "Prepares a cryptocurrency transfer request for explicit user authorization and external wallet signing. Never claims transaction success before wallet/chain confirmation.",
                    parameters = mapOf(
                        "destinationAddress" to Schema.string(description = "Recipient blockchain address."),
                        "assetSymbol" to Schema.string(description = "Asset ticker such as ETH, BTC, or USDT."),
                        "amount" to Schema.string(description = "Transfer amount as decimal string.")
                    )
                ),
                FunctionDeclaration(
                    name = "save_memory_note",
                    description = "Stores a user preference, fact, or instruction in persistent Aqua Memory.",
                    parameters = mapOf(
                        "category" to Schema.string(description = "One of: instruction, preference, fact, reminder."),
                        "title" to Schema.string(description = "Short memory title."),
                        "content" to Schema.string(description = "Memory content.")
                    )
                ),
                FunctionDeclaration(
                    name = "backup_history_to_drive",
                    description = "Backs up the user's conversation history and memories to their authorized Google Drive account.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "workspace_contacts_list",
                    description = "Lists or searches the user's Google Contacts.",
                    parameters = mapOf(
                        "query" to Schema.string(description = "Optional contact name, email, or phone query. Empty to list top connections.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_contacts_create",
                    description = "Creates a new contact in Google Contacts.",
                    parameters = mapOf(
                        "fullName" to Schema.string(description = "Full name of the contact."),
                        "email" to Schema.string(description = "Email address (optional if phone provided)."),
                        "phone" to Schema.string(description = "Phone number (optional if email provided).")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_calendar_list",
                    description = "Lists upcoming events from the user's primary Google Calendar.",
                    parameters = mapOf(
                        "maxResults" to Schema.integer(description = "Maximum number of events (1-50, default 15).")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_calendar_create",
                    description = "Creates a new event on the user's primary Google Calendar.",
                    parameters = mapOf(
                        "summary" to Schema.string(description = "Event title."),
                        "startIso" to Schema.string(description = "Start time in ISO-8601 format, e.g. 2026-11-15T10:00:00Z."),
                        "endIso" to Schema.string(description = "End time in ISO-8601 format, e.g. 2026-11-15T11:00:00Z."),
                        "description" to Schema.string(description = "Optional event description."),
                        "location" to Schema.string(description = "Optional event location.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_gmail_list",
                    description = "Searches or lists recent messages from the user's Gmail inbox.",
                    parameters = mapOf(
                        "query" to Schema.string(description = "Optional Gmail search filter such as 'is:unread' or sender name."),
                        "maxResults" to Schema.integer(description = "Maximum emails to fetch (1-20, default 10).")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_gmail_send",
                    description = "Sends an email via Gmail. Always requires explicit user confirmation before sending.",
                    parameters = mapOf(
                        "to" to Schema.string(description = "Recipient email address."),
                        "subject" to Schema.string(description = "Email subject line."),
                        "body" to Schema.string(description = "Plain-text email body.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_sheets_create",
                    description = "Creates a new Google Sheets spreadsheet.",
                    parameters = mapOf(
                        "title" to Schema.string(description = "Spreadsheet title.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_sheets_read",
                    description = "Reads cell values from a Google Sheets spreadsheet.",
                    parameters = mapOf(
                        "spreadsheetId" to Schema.string(description = "Google Spreadsheet ID."),
                        "range" to Schema.string(description = "A1 notation range, e.g. Sheet1!A1:J25.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_sheets_append",
                    description = "Appends a row of comma-separated values to a Google Sheets spreadsheet.",
                    parameters = mapOf(
                        "spreadsheetId" to Schema.string(description = "Google Spreadsheet ID."),
                        "range" to Schema.string(description = "Target sheet/range, e.g. Sheet1!A1."),
                        "valuesCsv" to Schema.string(description = "Comma-separated values for the row columns.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_forms_create",
                    description = "Creates a new Google Form and optionally adds an initial question.",
                    parameters = mapOf(
                        "title" to Schema.string(description = "Title of the Google Form."),
                        "firstQuestion" to Schema.string(description = "Optional first question prompt to add.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_forms_get_responses",
                    description = "Reads a Google Form's metadata, questions, and submitted responses.",
                    parameters = mapOf(
                        "formId" to Schema.string(description = "Google Form ID.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_chat_list_spaces",
                    description = "Lists Google Chat spaces and rooms accessible to the user.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "workspace_chat_list_messages",
                    description = "Lists recent messages inside a Google Chat space.",
                    parameters = mapOf(
                        "spaceName" to Schema.string(description = "Space resource name, e.g. spaces/AAAA1234.")
                    )
                ),
                FunctionDeclaration(
                    name = "workspace_chat_send_message",
                    description = "Sends a message into a Google Chat space. Always requires explicit user confirmation.",
                    parameters = mapOf(
                        "spaceName" to Schema.string(description = "Space resource name, e.g. spaces/AAAA1234."),
                        "text" to Schema.string(description = "Message text to post.")
                    )
                ),
                FunctionDeclaration(
                    name = "get_device_storage_and_memory",
                    description = "Returns real live Android internal storage free/total space and device RAM availability.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "read_clipboard",
                    description = "Reads the current plain-text content from the Android system clipboard.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "copy_to_clipboard",
                    description = "Copies useful generated text or notes directly onto the Android clipboard.",
                    parameters = mapOf(
                        "label" to Schema.string(description = "Short label for the clipboard item."),
                        "text" to Schema.string(description = "Plain text to copy.")
                    )
                ),
                FunctionDeclaration(
                    name = "share_text_externally",
                    description = "Opens the Android Sharesheet chooser to share text or summaries to external apps.",
                    parameters = mapOf(
                        "title" to Schema.string(description = "Share subject or title."),
                        "text" to Schema.string(description = "Text body to share.")
                    )
                ),
                FunctionDeclaration(
                    name = "browser_extract_links",
                    description = "Extracts clickable http/https links from the active Aqua Browser webpage.",
                    parameters = emptyMap()
                )
            )
        )
    }

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
                    if (activeWallet == null || activeWallet.publicAddress.isBlank()) {
                        onTrace(AgentStepTrace("Wallet Balance", name, "unconfigured", "No connected wallet address"))
                        jsonResult(
                            false,
                            "No Flash Wallet profile or public address is configured yet. Direct the user to the Aqua Wallet Connector tab to set up and verify their Flash Wallet."
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

                "fetch_crypto_price" -> {
                    val coin = argStr("coin").ifBlank { "bitcoin" }
                    val (resolvedCoin, price) = walletConnector.fetchMarketPriceUsd(coin).getOrThrow()
                    val msg = "Live CoinGecko spot price for $resolvedCoin: $${String.format("%,.2f", price)} USD."
                    onTrace(AgentStepTrace("Market Price", name, "completed", msg))
                    jsonResult(true, msg)
                }

                "wallet_prepare_transfer" -> {
                    val dest = argStr("destinationAddress")
                    val asset = argStr("assetSymbol").ifBlank { "ETH" }
                    val amount = argStr("amount")
                    val wallet = activeWallet ?: WalletConfig(
                        walletProductName = "External Wallet",
                        networkName = "Ethereum Mainnet"
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
        if (!isSensitive && settings.autonomousReversibleActions) {
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
