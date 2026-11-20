package com.example.ai

import com.example.data.model.UserSettings
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import org.json.JSONArray
import org.json.JSONObject

internal enum class ToolParamType { STRING, INTEGER, BOOLEAN }

internal data class AquaToolParam(
    val name: String,
    val type: ToolParamType,
    val description: String
)

internal data class AquaToolSpec(
    val name: String,
    val description: String,
    val params: List<AquaToolParam> = emptyList()
)

internal object AquaToolCatalog {

    private fun s(name: String, desc: String) = AquaToolParam(name, ToolParamType.STRING, desc)
    private fun i(name: String, desc: String) = AquaToolParam(name, ToolParamType.INTEGER, desc)
    private fun b(name: String, desc: String) = AquaToolParam(name, ToolParamType.BOOLEAN, desc)

    fun selectActiveSpecs(
        settings: UserSettings?,
        promptHint: String = "",
        unlockAllTools: Boolean = false
    ): List<AquaToolSpec> {
        val includeAll = unlockAllTools || settings == null || promptHint.isBlank()
        val lower = promptHint.lowercase()

        val coreDeviceTools = listOf(
            AquaToolSpec("check_device_status", "Returns live Android battery, charging, Wi-Fi/cellular, media volume, flashlight, and notification access status."),
            AquaToolSpec("toggle_flashlight", "Turns the device camera flashlight torch ON or OFF.", listOf(b("enable", "True for ON, false for OFF."))),
            AquaToolSpec("set_media_volume", "Adjusts Android media volume percentage (0-100).", listOf(i("percent", "Target media volume 0..100."))),
            AquaToolSpec("launch_app", "Launches an installed Android application by display name or package name.", listOf(s("appName", "App name or package ID."))),
            AquaToolSpec("open_android_settings", "Opens an Android Settings section (wifi, bluetooth, display, sound, notifications, battery, location, apps, or general).", listOf(s("section", "Settings section name."))),
            AquaToolSpec(
                "set_alarm",
                "Opens the Android Alarm clock composer to set an alarm.",
                listOf(i("hour", "24h hour (0-23)."), i("minute", "Minute (0-59)."), s("label", "Alarm label."))
            ),
            AquaToolSpec(
                "create_reminder",
                "Creates a persistent reminder in Aqua Memory and optionally opens Calendar.",
                listOf(s("title", "Reminder title."), s("details", "Reminder details or time note."), b("openCalendar", "True to launch Calendar UI."))
            ),
            AquaToolSpec("read_notifications", "Reads recent active Android notifications if permitted."),
            AquaToolSpec("get_device_storage_and_memory", "Returns real live Android storage free/total space, thermal status, and RAM availability."),
            AquaToolSpec(
                "media_playback_control",
                "Controls active Android media playback (play_pause, play, pause, next, previous, stop, or status).",
                listOf(s("action", "One of play_pause, play, pause, next, previous, stop, or status."))
            ),
            AquaToolSpec(
                "accessibility_perform_action",
                "Executes opt-in Accessibility actions (read_screen, back, home, recents, notifications, click_text) only when Accessibility Assist is explicitly enabled.",
                listOf(s("action", "read_screen, back, home, recents, notifications, or click_text."), s("targetText", "Target button label when using click_text."))
            ),
            AquaToolSpec(
                "save_memory_note",
                "Stores a user preference, fact, or instruction in persistent Aqua Memory.",
                listOf(s("category", "instruction, preference, fact, or reminder."), s("title", "Short memory title."), s("content", "Memory content."))
            ),
            AquaToolSpec("get_ai_freedom_config", "Reads active AI engine provider (Google AI Studio key vs Firebase AI), active model, temperature, max steps, autonomy level, and ElevenLabs voice status."),
            AquaToolSpec(
                "speak_with_elevenlabs",
                "Speaks a response or custom narration aloud using the user's configured ElevenLabs Voice ID (falls back cleanly if unconfigured).",
                listOf(s("text", "Text phrase to synthesize and play aloud."))
            )
        )

        val coreWebTools = if (settings?.fullInternetAccessEnabled != false) {
            listOf(
                AquaToolSpec(
                    "web_search",
                    "Searches the live internet via DuckDuckGo & Wikipedia for real-time news, facts, docs, and URLs.",
                    listOf(s("query", "Search query."), i("maxResults", "Number of results (1-12)."))
                ),
                AquaToolSpec(
                    "browser_open_url",
                    "Opens a website URL or search query visibly in Aqua Browser.",
                    listOf(s("urlOrQuery", "Full https URL or search query."))
                )
            )
        } else emptyList()

        val extendedWebNeeded = includeAll || listOf(
            "http", "www.", ".com", ".org", ".io", ".ai", ".dev", "url", "link", "page", "article", "read",
            "api", "fetch", "scrape", "form", "fill", "browser", "tab", "call", "dial", "phone", "sms", "text",
            "clipboard", "copy", "paste", "share", "play store", "youtube", "search"
        ).any { lower.contains(it) }

        val extendedWebAndCommsTools = if (extendedWebNeeded) {
            buildList {
                add(AquaToolSpec("initiate_phone_call", "Opens phone dialer for a number. Requires explicit user confirmation.", listOf(s("phoneNumber", "Phone number to dial."))))
                add(AquaToolSpec("prepare_sms_message", "Prepares an SMS message in the messaging app. Requires explicit user confirmation.", listOf(s("recipient", "Recipient number or contact."), s("body", "SMS message body."))))
                add(AquaToolSpec("read_clipboard", "Reads plain text from the Android clipboard."))
                add(AquaToolSpec("copy_to_clipboard", "Copies text onto the Android clipboard.", listOf(s("label", "Short label."), s("text", "Plain text to copy."))))
                add(AquaToolSpec("share_text_externally", "Opens Android Sharesheet to share text to external apps.", listOf(s("title", "Share subject."), s("text", "Text body."))))
                if (settings?.fullInternetAccessEnabled != false) {
                    add(AquaToolSpec("web_fetch_page", "Fetches and extracts readable text, title, and links from a public URL.", listOf(s("url", "Public http/https URL."))))
                    add(
                        AquaToolSpec(
                            "web_http_request",
                            "Makes an HTTP/REST request to a public URL (GET/HEAD direct; mutations require approval).",
                            listOf(
                                s("method", "GET, HEAD, POST, PUT, PATCH, or DELETE."),
                                s("url", "Public http/https endpoint URL."),
                                s("headersJson", "Optional JSON object of headers."),
                                s("body", "Optional request body.")
                            )
                        )
                    )
                    add(AquaToolSpec("browser_read_current_page", "Reads title, URL, and text from the active Aqua Browser tab."))
                    add(AquaToolSpec("browser_fill_form", "Fills a non-password form input on the active webpage. Requires confirmation.", listOf(s("selector", "CSS selector, name, or ID."), s("value", "Text value to enter."))))
                    add(AquaToolSpec("browser_extract_links", "Extracts clickable http/https links from the active Aqua Browser tab."))
                }
            }
        } else emptyList()

        val walletDomainNeeded = includeAll || listOf(
            "wallet", "crypto", "balance", "portfolio", "tx", "transaction", "transfer", "send",
            "receive", "address", "bitcoin", "btc", "ethereum", "eth", "solana", "sol", "bnb",
            "polygon", "pol", "matic", "arbitrum", "base", "sepolia", "usdt", "usdc", "token", "coin",
            "price", "market", "chart", "contact", "ens", ".eth", "gas", "gwei", "nonce", "block",
            "allowance", "revoke", "approve", "network", "account", "vault", "watch", "sign",
            "verify", "payment", "uri", "bip", "eip", "testnet", "currency", "fee", "risk", "swap"
        ).any { lower.contains(it) }

        val walletTools = if ((settings?.aiWalletAccessEnabled != false) && walletDomainNeeded) {
            val readTools = listOf(
                AquaToolSpec("wallet_check_balance", "Queries live on-chain balances for the active Aqua Wallet (read-only)."),
                AquaToolSpec("wallet_get_portfolio", "Fetches live multi-chain balances, USD valuation, 24h change, and chain allocation.", listOf(b("includeTestnets", "True to include testnets."))),
                AquaToolSpec("wallet_get_transactions", "Inspects Aqua Wallet transaction history across EVM, Solana, and Bitcoin.", listOf(s("statusFilter", "ALL, PENDING, CONFIRMED, or FAILED."))),
                AquaToolSpec("wallet_analyze_portfolio", "Analyzes portfolio concentration, stablecoin buffer, and security posture."),
                AquaToolSpec("wallet_research_network", "Retrieves blockchain network metadata, chainId, RPC host, explorer URL, tokens, and fee tier.", listOf(s("network", "Network name or ID."))),
                AquaToolSpec("wallet_get_token_info", "Fetches token contract/mint metadata and live USD price.", listOf(s("token", "Ticker or contract/mint address."), s("network", "Optional network scope."))),
                AquaToolSpec(
                    "wallet_estimate_fees",
                    "Estimates real-time network fees (slow/standard/fast) for EVM, Solana, or Bitcoin.",
                    listOf(s("network", "Network name or ID."), s("destinationAddress", "Optional recipient address."), s("assetSymbol", "Asset symbol."), s("amount", "Transfer amount string."))
                ),
                AquaToolSpec("wallet_analyze_address_risk", "Audits a blockchain address for validity, checksum, contract code, and burn/self risk.", listOf(s("address", "Blockchain address."), s("network", "Network name or ID."))),
                AquaToolSpec("wallet_lookup_contact", "Reads saved trusted contacts from the Aqua Wallet Address Book.", listOf(s("query", "Optional contact filter."))),
                AquaToolSpec("wallet_get_receive_addresses", "Returns public multi-chain receiving addresses, active wallet/account/network."),
                AquaToolSpec("wallet_get_evm_telemetry", "Fetches live EVM block height, gas price in Gwei, and account nonces.", listOf(s("network", "Optional EVM network name/ID."))),
                AquaToolSpec("wallet_resolve_ens", "Resolves an .eth ENS domain to a checksummed 0x address.", listOf(s("ensName", "ENS domain."))),
                AquaToolSpec("wallet_check_allowance", "Inspects ERC-20 token spender allowance and flags unlimited approvals.", listOf(s("token", "ERC-20 token symbol or address."), s("spenderAddress", "Spender address, ENS, or contact."), s("network", "Optional EVM network."))),
                AquaToolSpec("wallet_check_tx_status", "Checks live on-chain confirmation status for a tx hash or signature.", listOf(s("txHash", "Transaction hash or signature."), s("network", "Optional network."))),
                AquaToolSpec("wallet_get_market_chart", "Fetches live CoinGecko historical price stats (spot, change %, low, high).", listOf(s("coin", "Coin name or symbol."), i("days", "1, 7, 30, 90, or 365."), s("currency", "Optional fiat code (USD, EUR, GBP, JPY, AED, PKR)."))),
                AquaToolSpec("wallet_verify_message", "Cryptographically verifies an EIP-191 or Ed25519 signature.", listOf(s("chainFamily", "EVM or SOLANA."), s("signerAddress", "Signer public address."), s("message", "Original message."), s("signature", "Hex or Base58 signature."))),
                AquaToolSpec("wallet_parse_payment_uri", "Parses EIP-681, BIP-21, or Solana Pay URIs.", listOf(s("uri", "Payment URI or address."))),
                AquaToolSpec("fetch_crypto_price", "Fetches live USD spot price for a cryptocurrency from CoinGecko.", listOf(s("coin", "Coin name or symbol.")))
            )
            val writeTools = if (settings?.aiWalletTransferPrepareEnabled != false) {
                listOf(
                    AquaToolSpec("wallet_prepare_transaction", "Prepares and simulates a transfer, requiring explicit user authorization & vault unlock before broadcast.", listOf(s("network", "Network name or ID."), s("destinationAddress", "Recipient address, ENS, or contact label."), s("assetSymbol", "Asset ticker symbol."), s("amount", "Decimal amount."))),
                    AquaToolSpec("wallet_prepare_revoke_allowance", "Prepares an ERC-20 approve(spender, 0) revocation gated behind user authorization & unlock.", listOf(s("token", "Token symbol or contract."), s("spenderAddress", "Spender address."), s("network", "Optional EVM network."))),
                    AquaToolSpec("wallet_switch_network", "Switches active blockchain network in Aqua Wallet.", listOf(s("network", "Target network."))),
                    AquaToolSpec("wallet_switch_account", "Switches active derived account in Aqua Wallet.", listOf(s("account", "Account label or index."))),
                    AquaToolSpec("wallet_switch_wallet", "Switches active wallet in Aqua Vault.", listOf(s("wallet", "Wallet label or ID."))),
                    AquaToolSpec("wallet_save_contact", "Saves a trusted recipient address to Address Book after confirmation.", listOf(s("label", "Contact label."), s("chainFamily", "EVM, SOLANA, or BITCOIN."), s("address", "Blockchain address."), s("note", "Optional note."))),
                    AquaToolSpec("wallet_delete_contact", "Deletes a saved contact from Address Book after confirmation.", listOf(s("queryOrId", "Contact label, address, or ID."))),
                    AquaToolSpec("wallet_create_wallet", "Creates a new 12 or 24 word self-custodial wallet in Aqua Vault after confirmation.", listOf(s("label", "Wallet label."), i("wordCount", "12 or 24."))),
                    AquaToolSpec("wallet_add_account", "Derives a new multi-chain HD account inside active wallet after confirmation.", listOf(s("label", "Optional account label."))),
                    AquaToolSpec("wallet_add_watch_wallet", "Adds a read-only watch wallet after confirmation.", listOf(s("label", "Wallet label."), s("evmAddress", "Optional 0x address."), s("solanaAddress", "Optional Solana address."), s("bitcoinAddress", "Optional Bitcoin address."))),
                    AquaToolSpec("wallet_add_custom_token", "Adds a custom ERC-20 or SPL token after confirmation.", listOf(s("network", "Target network."), s("symbol", "Ticker symbol."), s("name", "Token name."), s("contractOrMint", "Contract or mint address."), i("decimals", "Decimals (default 18)."), s("coingeckoId", "Optional CoinGecko ID."))),
                    AquaToolSpec("wallet_add_custom_network", "Adds a custom HTTPS EVM RPC network after confirmation.", listOf(s("name", "Network name."), s("nativeSymbol", "Native symbol."), i("chainId", "EVM chain ID."), s("rpcUrl", "HTTPS RPC URL."), s("explorerUrl", "Optional explorer URL."), b("isTestnet", "True if testnet."))),
                    AquaToolSpec("wallet_sign_message", "Signs an off-chain ownership proof message (EIP-191 or Ed25519) after authorization & unlock.", listOf(s("chainFamily", "EVM or SOLANA."), s("message", "Message to sign."))),
                    AquaToolSpec("wallet_update_preferences", "Updates display fiat currency or testnet visibility.", listOf(s("displayCurrencyCode", "USD, EUR, GBP, INR, JPY, AUD, CAD, CHF, SGD, AED."), b("testnetModeEnabled", "Enable/disable testnets."))),
                    AquaToolSpec("wallet_prepare_transfer", "Prepares an unsigned transfer request for explicit user authorization.", listOf(s("destinationAddress", "Recipient address."), s("assetSymbol", "Asset ticker."), s("amount", "Amount string."), s("network", "Optional network.")))
                )
            } else emptyList()
            readTools + writeTools
        } else emptyList()

        val driveNeeded = includeAll || listOf(
            "drive", "backup", "quota", "storage", "restore", "export"
        ).any { lower.contains(it) }

        val driveTools = if (driveNeeded) {
            listOf(
                AquaToolSpec("backup_history_to_drive", "Backs up conversation history and memories to Google Drive AppData."),
                AquaToolSpec("drive_list_backups", "Lists saved Aqua AI history backup files in Google Drive AppData and My Drive."),
                AquaToolSpec("drive_get_storage_quota", "Fetches authenticated Google Drive storage quota, account name, and API key status."),
                AquaToolSpec("drive_sync_now", "Ensures all 10 Aqua AI/ Google Drive folders exist and flushes offline queued mutations.")
            )
        } else emptyList()

        return coreDeviceTools +
            coreWebTools +
            extendedWebAndCommsTools +
            walletTools +
            driveTools
    }

    fun buildFirebaseTool(
        settings: UserSettings?,
        promptHint: String = "",
        unlockAllTools: Boolean = false
    ): Tool {
        val specs = selectActiveSpecs(settings, promptHint, unlockAllTools)
        return Tool.functionDeclarations(
            specs.map { spec ->
                FunctionDeclaration(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.params.associate { param ->
                        val schema = when (param.type) {
                            ToolParamType.STRING -> Schema.string(description = param.description)
                            ToolParamType.INTEGER -> Schema.integer(description = param.description)
                            ToolParamType.BOOLEAN -> Schema.boolean(description = param.description)
                        }
                        param.name to schema
                    }
                )
            }
        )
    }

    fun buildRestFunctionDeclarationsJson(
        settings: UserSettings?,
        promptHint: String = "",
        unlockAllTools: Boolean = false
    ): JSONArray {
        val specs = selectActiveSpecs(settings, promptHint, unlockAllTools)
        val arr = JSONArray()
        specs.forEach { spec ->
            val obj = JSONObject().apply {
                put("name", spec.name)
                put("description", spec.description)
                if (spec.params.isNotEmpty()) {
                    val props = JSONObject()
                    spec.params.forEach { p ->
                        props.put(
                            p.name,
                            JSONObject().apply {
                                put(
                                    "type",
                                    when (p.type) {
                                        ToolParamType.STRING -> "STRING"
                                        ToolParamType.INTEGER -> "INTEGER"
                                        ToolParamType.BOOLEAN -> "BOOLEAN"
                                    }
                                )
                                put("description", p.description)
                            }
                        )
                    }
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", props)
                        }
                    )
                }
            }
            arr.put(obj)
        }
        return arr
    }
}
