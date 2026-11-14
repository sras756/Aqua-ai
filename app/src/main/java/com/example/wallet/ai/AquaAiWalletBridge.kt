package com.example.wallet.ai

import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AquaWalletEngine
import com.example.wallet.core.ChainFamily
import com.example.wallet.core.DisplayFiatCurrency
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.PortfolioSummary
import com.example.wallet.core.PreparedChainTransaction
import com.example.wallet.core.SignedMessageProof
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.TxStatus
import com.example.wallet.core.WalletAccountInfo
import com.example.wallet.core.WalletMetadata
import com.example.wallet.core.WalletTxRecord
import java.math.BigDecimal
import java.util.Locale

/**
 * Security-isolated AI access bridge for Aqua Wallet.
 *
 * Strictly enforced capabilities:
 * - READ PERMISSION: balances, portfolio analytics, receive addresses, transaction history & status,
 *   network/token metadata, EVM chain telemetry, ENS resolution, ERC-20 allowance audits,
 *   CoinGecko market charts, payment URI parsing, signature verification, fee estimation, and recipient risk checks.
 * - WRITE PERMISSION (User-Controlled & Gated): creating non-custodial/watch-only wallets, deriving accounts,
 *   switching active wallet/account/network, adding custom tokens/networks, saving/deleting contacts,
 *   updating display currency/testnet preferences, offline EIP-191/Solana message signing,
 *   unsigned transaction preparation & ERC-20 approve(0) revoke preparation.
 * - ZERO-KEY INVARIANT: Cannot bypass explicit user authorization or PIN/Biometrics, cannot access mnemonics/seeds/private
 *   keys/PINs, cannot alter spending limits, and cannot unlock emergency lock.
 */
class AquaAiWalletBridge(
    private val engine: AquaWalletEngine
) {

    fun isVaultLockedForSigning(): Boolean = engine.vault.isWalletLocked()

    fun isEmergencyLocked(): Boolean = engine.vault.getSecuritySettings().emergencyLocked

    fun activeWalletAndAccount(): Pair<WalletMetadata, WalletAccountInfo>? {
        val settings = engine.vault.getSecuritySettings()
        val wallets = engine.vault.getAllWallets()
        if (wallets.isEmpty()) return null
        val meta = wallets.firstOrNull { it.walletId == settings.activeWalletId } ?: wallets.first()
        val accounts = engine.vault.getAccountsForWallet(meta.walletId)
        val account = accounts.firstOrNull { it.accountIndex == settings.activeAccountIndex }
            ?: accounts.firstOrNull()
            ?: return null
        return meta to account
    }

    suspend fun activeNetwork(): NetworkConfig {
        val settings = engine.vault.getSecuritySettings()
        val nets = engine.getAvailableNetworks(includeTestnets = true)
        return nets.firstOrNull { it.id == settings.activeNetworkId } ?: nets.first()
    }

    suspend fun getPublicOverviewText(): String {
        val pair = activeWalletAndAccount()
            ?: return "No Aqua Wallet has been created or imported yet."
        val (meta, account) = pair
        val settings = engine.vault.getSecuritySettings()
        val net = activeNetwork()
        return buildString {
            appendLine("Active Wallet: ${meta.label} (${if (meta.isWatchOnly) "Watch-Only" else "${meta.wordCount}-word Native Self-Custody"})")
            appendLine("Active Account: ${account.label} (index #${account.accountIndex})")
            appendLine("Active Network: ${net.name} (${net.chainFamily.name}, chainId=${net.chainId})")
            appendLine("Public Addresses (Safe Metadata Only):")
            if (account.evmAddress.isNotBlank()) appendLine("- EVM (ETH/BSC/Polygon/Base/Arbitrum): ${account.evmAddress}")
            if (account.solanaAddress.isNotBlank()) appendLine("- Solana: ${account.solanaAddress}")
            if (account.bitcoinMainnetAddress.isNotBlank()) appendLine("- Bitcoin Mainnet (SegWit): ${account.bitcoinMainnetAddress}")
            if (account.bitcoinTestnetAddress.isNotBlank()) appendLine("- Bitcoin Testnet4 (SegWit): ${account.bitcoinTestnetAddress}")
            appendLine("Security Posture: PIN=${settings.hasPin}, Biometric=${settings.biometricEnabled}, VaultLocked=${engine.vault.isWalletLocked()}, EmergencyLock=${settings.emergencyLocked}, BackupVerified=${meta.backupVerified}")
            appendLine("Limits: Per-Tx=$${"%.2f".format(Locale.US, settings.perTxLimitUsd)}, Daily=$${"%.2f".format(Locale.US, settings.dailyLimitUsd)} (Spent Today=$${"%.2f".format(Locale.US, settings.spentTodayUsd)})")
        }
    }

    suspend fun getReceiveAddressesText(): Result<String> = runCatching {
        val allWallets = engine.vault.getAllWallets()
        if (allWallets.isEmpty()) {
            throw IllegalStateException("No Aqua Wallet created or imported yet. Open the Wallet tab to create or import one.")
        }
        val (meta, account) = activeWalletAndAccount()!!
        val settings = engine.vault.getSecuritySettings()
        val activeNet = activeNetwork()
        val accounts = engine.vault.getAccountsForWallet(meta.walletId)
        val networks = engine.getAvailableNetworks(settings.testnetModeEnabled)

        buildString {
            appendLine("Active Wallet: ${meta.label} (${if (meta.isWatchOnly) "Watch-Only" else "Self-Custody"})")
            appendLine("Active Account: ${account.label} (index #${account.accountIndex})")
            appendLine("Active Network: ${activeNet.name} (${activeNet.id})")
            appendLine("Public Receive Addresses:")
            if (account.evmAddress.isNotBlank()) {
                appendLine("- EVM (Ethereum, BSC, Polygon, Base, Arbitrum): ${account.evmAddress}")
            }
            if (account.solanaAddress.isNotBlank()) {
                appendLine("- Solana (SOL & SPL): ${account.solanaAddress}")
            }
            if (account.bitcoinMainnetAddress.isNotBlank()) {
                appendLine("- Bitcoin Mainnet (SegWit): ${account.bitcoinMainnetAddress}")
            }
            if (account.bitcoinTestnetAddress.isNotBlank()) {
                appendLine("- Bitcoin Testnet4 (SegWit): ${account.bitcoinTestnetAddress}")
            }
            if (accounts.size > 1) {
                appendLine("Accounts in ${meta.label}: ${accounts.joinToString { "${it.label} (#${it.accountIndex})" }}")
            }
            if (allWallets.size > 1) {
                appendLine("All Wallets in Vault: ${allWallets.joinToString { it.label }}")
            }
            appendLine("Enabled Networks (${networks.size}, Testnets=${settings.testnetModeEnabled}): ${networks.joinToString { "${it.name} [${it.id}]" }}")
        }
    }

    suspend fun switchActiveNetworkText(networkQuery: String): Result<String> = runCatching {
        val target = resolveNetwork(networkQuery)
        val s = engine.vault.getSecuritySettings()
        val enableTestnet = s.testnetModeEnabled || target.isTestnet
        engine.vault.updateSecuritySettings(
            s.copy(activeNetworkId = target.id, testnetModeEnabled = enableTestnet)
        )
        "Active Aqua Wallet network switched to ${target.name} (${target.id}, ${target.chainFamily.name})."
    }

    suspend fun switchActiveAccountText(accountQuery: String): Result<String> = runCatching {
        val (meta, _) = activeWalletAndAccount()
            ?: throw IllegalStateException("No Aqua Wallet initialized.")
        val accounts = engine.vault.getAccountsForWallet(meta.walletId)
        val q = accountQuery.trim()
        val indexGuess = q.removePrefix("#").toIntOrNull()
        val target = accounts.firstOrNull { acc ->
            (indexGuess != null && acc.accountIndex == indexGuess) ||
                acc.label.equals(q, ignoreCase = true) ||
                acc.label.contains(q, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Account '$accountQuery' not found in '${meta.label}'. Available: ${accounts.joinToString { "${it.label} (#${it.accountIndex})" }}"
        )
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeAccountIndex = target.accountIndex))
        "Switched active account in '${meta.label}' to ${target.label} (index #${target.accountIndex}). EVM: ${target.evmAddress}"
    }

    suspend fun getPortfolioSummaryText(includeTestnets: Boolean? = null): Result<String> = runCatching {
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized in Aqua Wallet Vault.")
        val settings = engine.vault.getSecuritySettings()
        val useTestnets = includeTestnets ?: settings.testnetModeEnabled
        val portfolio: PortfolioSummary = engine.fetchPortfolio(account, useTestnets)

        buildString {
            appendLine("Portfolio for ${meta.label} — ${account.label}:")
            val totalStr = portfolio.totalValueUsd?.let { "$${"%,.2f".format(Locale.US, it)} USD" } ?: "Unavailable (offline or testnet)"
            val chgStr = portfolio.weighted24hChangePct?.let { "${if (it >= 0) "+" else ""}${"%.2f".format(Locale.US, it)}% (24h)" } ?: ""
            appendLine("Total Portfolio Value: $totalStr $chgStr".trim())
            if (portfolio.chainTotalsUsd.isNotEmpty()) {
                appendLine("Chain Allocation:")
                portfolio.chainTotalsUsd.entries.sortedByDescending { it.value }.forEach { (chain, usd) ->
                    appendLine("- $chain: $${"%,.2f".format(Locale.US, usd)}")
                }
            }
            val nonZeroOrMain = portfolio.assets.filter {
                it.formattedAmount > BigDecimal.ZERO || it.token.isNative
            }
            appendLine("Assets (${nonZeroOrMain.size} active/native):")
            nonZeroOrMain.forEach { a ->
                val usdPart = a.valueUsd?.let { " ($${"%,.2f".format(Locale.US, it)})" } ?: ""
                appendLine("- [${a.token.networkId}] ${a.displayBalance}$usdPart")
            }
            if (portfolio.warnings.isNotEmpty()) {
                appendLine("RPC / Data Warnings:")
                portfolio.warnings.forEach { appendLine("- $it") }
            }
        }
    }

    suspend fun getTransactionsText(statusFilter: String = "ALL"): Result<String> = runCatching {
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized.")
        val settings = engine.vault.getSecuritySettings()
        val synced = engine.syncTransactionHistory(meta.walletId, account, settings.testnetModeEnabled)
        val normalized = statusFilter.trim().uppercase()
        val filtered = when (normalized) {
            "PENDING", "CONFIRMED", "FAILED" -> synced.filter { it.status.name == normalized }
            else -> synced
        }
        if (filtered.isEmpty()) {
            "No transactions found for filter '$normalized' on ${meta.label}."
        } else {
            buildString {
                appendLine("Transactions (${filtered.size}, filter=$normalized):")
                filtered.take(20).forEach { tx ->
                    appendLine("- [${tx.status}] ${tx.networkName}: ${tx.amountDisplay} | ${tx.direction} | fee=${tx.feeDisplay} | conf=${tx.confirmations} | hash=${tx.txHash}")
                }
            }
        }
    }

    suspend fun checkTxStatusText(txHash: String, networkQuery: String): Result<String> = runCatching {
        val cleanHash = txHash.trim()
        require(cleanHash.isNotBlank()) { "Transaction hash is required." }
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized.")
        val settings = engine.vault.getSecuritySettings()
        val synced = engine.syncTransactionHistory(meta.walletId, account, settings.testnetModeEnabled)
        val cached = synced.firstOrNull { it.txHash.equals(cleanHash, ignoreCase = true) }
        if (cached != null) {
            val refreshed = engine.refreshSingleTxStatus(cached).getOrElse { cached }
            return@runCatching "Transaction ${refreshed.txHash} on ${refreshed.networkName}: Status=${refreshed.status}, Confirmations=${refreshed.confirmations}, Amount=${refreshed.amountDisplay}, Fee=${refreshed.feeDisplay}, Explorer=${refreshed.explorerUrl}"
        }
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val placeholder = WalletTxRecord(
            txHash = cleanHash,
            idempotencyKey = "lookup_${cleanHash.take(12)}",
            walletId = meta.walletId,
            networkId = net.id,
            networkName = net.name,
            fromAddress = account.addressFor(net),
            toAddress = "",
            assetSymbol = net.nativeSymbol,
            amountDisplay = "",
            feeDisplay = "",
            status = TxStatus.PENDING,
            confirmations = 0,
            timestampMs = System.currentTimeMillis(),
            explorerUrl = "${net.explorerBaseUrl.trimEnd('/')}/tx/$cleanHash",
            direction = com.example.wallet.core.TxDirection.OUTBOUND
        )
        val live = engine.adapterFor(net.chainFamily).fetchTransactionStatus(cleanHash, placeholder, net).getOrThrow()
        "Transaction ${live.txHash} on ${live.networkName}: Status=${live.status}, Confirmations=${live.confirmations}, Explorer=${live.explorerUrl}"
    }

    suspend fun analyzePortfolioText(): Result<String> = runCatching {
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized.")
        val settings = engine.vault.getSecuritySettings()
        val portfolio = engine.fetchPortfolio(account, settings.testnetModeEnabled)
        val txs = engine.syncTransactionHistory(meta.walletId, account, settings.testnetModeEnabled)
        val total = portfolio.totalValueUsd ?: 0.0
        val pendingCount = txs.count { it.status == TxStatus.PENDING }
        val failedCount = txs.count { it.status == TxStatus.FAILED }

        val stableSymbols = setOf("USDT", "USDC", "DAI", "BUSD")
        val stableUsd = portfolio.assets
            .filter { it.token.symbol.uppercase() in stableSymbols }
            .sumOf { it.valueUsd ?: 0.0 }
        val stableSharePct = if (total > 0.0) (stableUsd / total) * 100.0 else 0.0

        val topChain = portfolio.chainTotalsUsd.maxByOrNull { it.value }
        val topChainPct = if (total > 0.0 && topChain != null) (topChain.value / total) * 100.0 else 0.0

        buildString {
            appendLine("Portfolio & Security Analysis for ${meta.label}:")
            appendLine("- Total USD Valuation: $${"%,.2f".format(Locale.US, total)}")
            if (portfolio.weighted24hChangePct != null) {
                appendLine("- Weighted 24h Portfolio Performance: ${"%+.2f".format(Locale.US, portfolio.weighted24hChangePct)}%")
            }
            appendLine("- Stablecoin Buffer: $${"%,.2f".format(Locale.US, stableUsd)} (${"%.1f".format(Locale.US, stableSharePct)}% of portfolio)")
            if (topChain != null) {
                appendLine("- Highest Chain Concentration: ${topChain.key} (${"%.1f".format(Locale.US, topChainPct)}%)")
            }
            appendLine("- Activity Health: ${txs.size} total txs ($pendingCount pending, $failedCount failed)")
            appendLine("- Custody & Safety Audit:")
            if (!meta.backupVerified && !meta.isWatchOnly) {
                appendLine("  * WARNING: Recovery phrase backup is not verified yet.")
            } else {
                appendLine("  * Recovery backup status: ${if (meta.isWatchOnly) "Watch-only" else "Verified"}")
            }
            if (!settings.hasPin && !settings.biometricEnabled) {
                appendLine("  * RECOMMENDATION: Enable a Wallet PIN or Biometrics in Security Center.")
            }
            if (settings.emergencyLocked) {
                appendLine("  * NOTICE: Emergency Wallet Lock is currently ACTIVE (outbound signing blocked).")
            }
            if (portfolio.warnings.isNotEmpty()) {
                appendLine("- Unreachable Networks (${portfolio.warnings.size}): ${portfolio.warnings.joinToString("; ")}")
            }
        }
    }

    suspend fun researchNetworkText(networkQuery: String): Result<String> = runCatching {
        val net = resolveNetwork(networkQuery)
        val tokens = engine.getTokensForNetwork(net.id)
        val pair = activeWalletAndAccount()
        val addr = pair?.second?.addressFor(net).orEmpty()
        val feeSample = if (addr.isNotBlank()) {
            val nativeToken = tokens.firstOrNull { it.isNative }
            if (nativeToken != null) {
                engine.adapterFor(net.chainFamily)
                    .estimateFee(addr, addr, nativeToken, BigDecimal("0.0001"), net)
                    .getOrNull()
            } else null
        } else null

        buildString {
            appendLine("Network Profile: ${net.name} (id=${net.id})")
            appendLine("- Architecture: ${net.chainFamily.name} | Chain ID: ${net.chainId} | SLIP-44 Coin Type: ${net.slip44CoinType}")
            appendLine("- Native Asset: ${net.nativeSymbol} (${net.nativeDecimals} decimals) | Environment: ${if (net.isTestnet) "Testnet" else "Mainnet"}")
            appendLine("- Default RPC Endpoint: ${net.defaultRpcUrl}")
            appendLine("- Explorer: ${net.explorerBaseUrl}")
            appendLine("- Configured Tokens (${tokens.size}): ${tokens.joinToString { it.symbol }}")
            if (feeSample != null) {
                appendLine("- Live Standard Fee Estimate: ${feeSample.estimatedFeeDisplay} (Slow: ${feeSample.slowLabel}, Fast: ${feeSample.fastLabel})")
            }
        }
    }

    suspend fun getEvmTelemetryText(networkQuery: String): Result<String> = runCatching {
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        require(net.chainFamily == ChainFamily.EVM) {
            "${net.name} is a ${net.chainFamily.name} network; EVM gas/nonce telemetry applies to EVM chains."
        }
        val (_, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet account initialized.")
        val telemetry = engine.utilities.fetchEvmTelemetry(account.evmAddress, net).getOrThrow()
        "Live EVM Telemetry (${net.name}): Block #${telemetry.blockNumber} | Gas=${telemetry.gasPriceGwei.stripTrailingZeros().toPlainString()} Gwei | Confirmed Nonce=${telemetry.confirmedNonce} | Pending Nonce=${telemetry.pendingNonce} | Address=${account.evmAddress}"
    }

    suspend fun resolveEnsText(ensName: String): Result<String> = runCatching {
        val resolved = engine.utilities.resolveEnsName(ensName).getOrThrow()
        "ENS '${ensName.trim()}' resolves on Ethereum Mainnet to $resolved"
    }

    suspend fun checkTokenAllowanceText(
        tokenQuery: String,
        spenderAddress: String,
        networkQuery: String
    ): Result<String> = runCatching {
        val (_, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet account initialized.")
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        require(net.chainFamily == ChainFamily.EVM) { "ERC-20 allowance checks require an EVM network." }
        val tokens = engine.getTokensForNetwork(net.id)
        val token = resolveToken(tokens, tokenQuery, net)
        require(!token.isNative && token.contractOrMintAddress.isNotBlank()) {
            "Select an ERC-20 token contract (e.g. USDT, USDC) on ${net.name}."
        }
        val resolvedSpender = resolveDestinationAddress(spenderAddress, net)
        val res = engine.utilities.fetchErc20Allowance(account.evmAddress, token, resolvedSpender, net).getOrThrow()
        "ERC-20 Allowance on ${net.name}: Owner=${res.ownerAddress} | Token=${res.tokenSymbol} (${res.tokenContract}) | Spender=${res.spenderAddress} | Allowance=${res.formattedAllowance} | UnlimitedRisk=${res.isUnlimited}"
    }

    suspend fun prepareRevokeAllowance(
        tokenQuery: String,
        spenderAddress: String,
        networkQuery: String
    ): Result<PreparedChainTransaction> = runCatching {
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized in Aqua Vault.")
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val tokens = engine.getTokensForNetwork(net.id)
        val token = resolveToken(tokens, tokenQuery, net)
        val resolvedSpender = resolveDestinationAddress(spenderAddress, net)
        engine.utilities.prepareErc20RevokeApproval(meta, account, net, token, resolvedSpender).getOrThrow()
    }

    suspend fun getMarketChartText(
        coinOrSymbol: String,
        days: Int = 7,
        fiatCode: String = "USD"
    ): Result<String> = runCatching {
        val clean = coinOrSymbol.trim().ifBlank { "bitcoin" }
        val coinId = engine.marketPriceService.normalizeCoinId(clean)
        val boundedDays = days.coerceIn(1, 365)
        val chart = engine.marketPriceService.fetchMarketChart(coinId, clean.uppercase(), boundedDays).getOrThrow()
        val currency = DisplayFiatCurrency.fromCode(fiatCode)
        val fxRate = if (currency == DisplayFiatCurrency.USD) 1.0 else {
            engine.marketPriceService.fetchFiatRateFromUsd(currency).getOrDefault(1.0)
        }
        val usedCurrency = if (currency != DisplayFiatCurrency.USD && fxRate > 0.0) currency else DisplayFiatCurrency.USD
        val mult = if (usedCurrency == DisplayFiatCurrency.USD) 1.0 else fxRate
        val sym = usedCurrency.symbol
        "CoinGecko Market Chart (${clean.uppercase()} / ${usedCurrency.code}, ${chart.days}d): Spot=$sym${"%,.4f".format(Locale.US, chart.currentPriceUsd * mult)} | PeriodChange=${"%+.2f".format(Locale.US, chart.periodChangePct)}% | Low=$sym${"%,.4f".format(Locale.US, chart.lowUsd * mult)} | High=$sym${"%,.4f".format(Locale.US, chart.highUsd * mult)} | Samples=${chart.points.size}"
    }

    suspend fun getTokenInfoText(symbolOrAddress: String, networkQuery: String): Result<String> = runCatching {
        val q = symbolOrAddress.trim()
        require(q.isNotBlank()) { "Token symbol or contract/mint address is required." }
        val nets = engine.getAvailableNetworks(includeTestnets = true)
        val scopedNet = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val netTokens = engine.getTokensForNetwork(scopedNet.id)
        val matchedToken: TokenDefinition? = netTokens.firstOrNull {
            it.symbol.equals(q, ignoreCase = true) ||
                it.name.equals(q, ignoreCase = true) ||
                (it.contractOrMintAddress.isNotBlank() && it.contractOrMintAddress.equals(q, ignoreCase = true))
        } ?: nets.flatMap { engine.getTokensForNetwork(it.id) }.firstOrNull {
            it.symbol.equals(q, ignoreCase = true) ||
                (it.contractOrMintAddress.isNotBlank() && it.contractOrMintAddress.equals(q, ignoreCase = true))
        }

        val coinId = matchedToken?.coingeckoId ?: engine.marketPriceService.normalizeCoinId(q)
        val quotes = engine.marketPriceService.fetchPricesUsd(listOf(coinId))
        val quote = quotes[coinId]

        buildString {
            if (matchedToken != null) {
                appendLine("Token: ${matchedToken.name} (${matchedToken.symbol})")
                appendLine("- Network ID: ${matchedToken.networkId} | Decimals: ${matchedToken.decimals} | Type: ${if (matchedToken.isNative) "Native Gas Asset" else "Contract / Mint Token"}")
                if (matchedToken.contractOrMintAddress.isNotBlank()) {
                    appendLine("- Contract / Mint Address: ${matchedToken.contractOrMintAddress}")
                }
            } else {
                appendLine("Token Query: $q (CoinGecko ID: $coinId)")
            }
            if (quote != null) {
                val chg = quote.change24hPct?.let { " (${"%+.2f".format(Locale.US, it)}% 24h)" } ?: ""
                appendLine("- Live CoinGecko Price: $${"%,.4f".format(Locale.US, quote.priceUsd)} USD$chg")
            } else {
                appendLine("- Live Spot Price: Currently unavailable from CoinGecko.")
            }
        }
    }

    suspend fun lookupAddressBookText(query: String = ""): Result<String> = runCatching {
        val contacts = engine.utilities.getAddressBook()
        val q = query.trim()
        val matched = if (q.isBlank()) contacts else contacts.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.address.contains(q, ignoreCase = true) ||
                it.note.contains(q, ignoreCase = true)
        }
        if (matched.isEmpty()) {
            "No saved Aqua Wallet Address Book contacts matching '${q.ifBlank { "all" }}'."
        } else {
            buildString {
                appendLine("Saved Trusted Address Book Contacts (${matched.size}):")
                matched.forEach { c ->
                    val notePart = if (c.note.isNotBlank()) " — ${c.note}" else ""
                    appendLine("- ${c.label} [${c.chainFamily.name}]: ${c.address}$notePart")
                }
            }
        }
    }

    suspend fun saveAddressBookContact(
        label: String,
        chainFamilyName: String,
        address: String,
        note: String = ""
    ): Result<String> = runCatching {
        val family = when (chainFamilyName.trim().uppercase()) {
            "EVM", "ETH", "ETHEREUM", "BSC", "POLYGON", "BASE", "ARBITRUM" -> ChainFamily.EVM
            "SOL", "SOLANA" -> ChainFamily.SOLANA
            "BTC", "BITCOIN" -> ChainFamily.BITCOIN
            else -> throw IllegalArgumentException("Specify chainFamily as EVM, SOLANA, or BITCOIN.")
        }
        val entry = engine.utilities.saveContact(label, family, address, note).getOrThrow()
        "Saved '${entry.label}' (${entry.chainFamily.name}: ${entry.address}) to Aqua Wallet Address Book."
    }

    suspend fun deleteAddressBookContactText(queryOrId: String): Result<String> = runCatching {
        val q = queryOrId.trim()
        require(q.isNotBlank()) { "Specify a contact label, address, or ID to delete." }
        val contacts = engine.utilities.getAddressBook()
        val match = contacts.firstOrNull { it.id == q }
            ?: contacts.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: contacts.firstOrNull { it.address.equals(q, ignoreCase = true) }
            ?: throw IllegalArgumentException("No saved contact matching '$q'.")
        engine.utilities.deleteContact(match.id)
        "Deleted '${match.label}' (${match.chainFamily.name}: ${match.address}) from Aqua Wallet Address Book."
    }

    fun parsePaymentUriText(uri: String): Result<String> = runCatching {
        val clean = uri.trim()
        require(clean.isNotBlank()) { "Payment URI or address string is required." }
        val parsed = engine.parseScannedPaymentUri(clean)
        buildString {
            append("Parsed Payment Request -> Address: ${parsed.address}")
            if (!parsed.suggestedNetworkId.isNullOrBlank()) append(" | Network: ${parsed.suggestedNetworkId}")
            if (!parsed.suggestedAsset.isNullOrBlank()) append(" | Asset: ${parsed.suggestedAsset}")
            if (!parsed.suggestedAmount.isNullOrBlank()) append(" | Amount: ${parsed.suggestedAmount}")
        }
    }

    suspend fun createWalletText(label: String, wordCount: Int = 12): Result<String> = runCatching {
        val normalizedWords = if (wordCount == 24) 24 else 12
        val (meta, acc) = engine.vault.createWallet(
            label = label.ifBlank { "Aqua Wallet" },
            wordCount = normalizedWords
        ).getOrThrow()
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeWalletId = meta.walletId, activeAccountIndex = acc.accountIndex))
        buildString {
            appendLine("Created native self-custodial ${meta.wordCount}-word wallet '${meta.label}' and set it as active.")
            appendLine("Public Receiving Addresses:")
            appendLine("- EVM: ${acc.evmAddress}")
            appendLine("- Solana: ${acc.solanaAddress}")
            appendLine("- Bitcoin: ${acc.bitcoinMainnetAddress}")
            appendLine("Security Notice: Back up and verify the recovery phrase in Wallet -> Security Center. Aqua AI never sees your seed phrase.")
        }.trimEnd()
    }

    suspend fun addAccountText(label: String = ""): Result<String> = runCatching {
        val (meta, _) = activeWalletAndAccount()
            ?: throw IllegalStateException("No Aqua Wallet initialized.")
        val acc = engine.vault.addDerivedAccount(meta.walletId, label).getOrThrow()
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeAccountIndex = acc.accountIndex))
        buildString {
            appendLine("Derived new account '${acc.label}' (index #${acc.accountIndex}) in '${meta.label}' and switched to it.")
            appendLine("- EVM: ${acc.evmAddress}")
            appendLine("- Solana: ${acc.solanaAddress}")
            appendLine("- Bitcoin: ${acc.bitcoinMainnetAddress}")
        }.trimEnd()
    }

    suspend fun addWatchOnlyWalletText(
        label: String,
        evmAddress: String = "",
        solanaAddress: String = "",
        bitcoinAddress: String = ""
    ): Result<String> = runCatching {
        val (meta, acc) = engine.vault.importWatchOnlyWallet(
            label = label.ifBlank { "Watch Portfolio" },
            evmAddress = evmAddress,
            solanaAddress = solanaAddress,
            bitcoinAddress = bitcoinAddress
        ).getOrThrow()
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeWalletId = meta.walletId, activeAccountIndex = 0))
        "Added Watch-Only wallet '${meta.label}' (EVM=${acc.evmAddress.ifBlank { "none" }}, SOL=${acc.solanaAddress.ifBlank { "none" }}, BTC=${acc.bitcoinMainnetAddress.ifBlank { acc.bitcoinTestnetAddress.ifBlank { "none" } }})."
    }

    suspend fun switchActiveWalletText(walletQuery: String): Result<String> = runCatching {
        val wallets = engine.vault.getAllWallets()
        require(wallets.isNotEmpty()) { "No wallets exist in Aqua Vault." }
        val q = walletQuery.trim()
        val target = wallets.firstOrNull {
            it.walletId.equals(q, ignoreCase = true) ||
                it.label.equals(q, ignoreCase = true) ||
                it.label.contains(q, ignoreCase = true)
        } ?: throw IllegalArgumentException("Wallet '$walletQuery' not found. Available: ${wallets.joinToString { it.label }}")
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeWalletId = target.walletId, activeAccountIndex = 0))
        "Switched active Aqua Wallet to '${target.label}' (${if (target.isWatchOnly) "Watch-Only" else "Self-Custody"})."
    }

    suspend fun addCustomTokenText(
        networkQuery: String,
        symbol: String,
        name: String,
        contractOrMint: String,
        decimals: Int = 18,
        coingeckoId: String = ""
    ): Result<String> = runCatching {
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val tok = engine.addCustomToken(
            networkId = net.id,
            symbol = symbol,
            name = name,
            contractOrMint = contractOrMint,
            decimals = decimals,
            coingeckoId = coingeckoId
        ).getOrThrow()
        "Added custom token ${tok.symbol} (${tok.name}, decimals=${tok.decimals}) on ${net.name}: ${tok.contractOrMintAddress}"
    }

    suspend fun addCustomNetworkText(
        name: String,
        nativeSymbol: String,
        chainId: Long,
        rpcUrl: String,
        explorerUrl: String = "",
        isTestnet: Boolean = false
    ): Result<String> = runCatching {
        val net = engine.addCustomEvmNetwork(
            name = name,
            nativeSymbol = nativeSymbol,
            chainId = chainId,
            rpcUrl = rpcUrl,
            explorerBaseUrl = explorerUrl,
            isTestnet = isTestnet
        ).getOrThrow()
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(
            s.copy(activeNetworkId = net.id, testnetModeEnabled = s.testnetModeEnabled || isTestnet)
        )
        "Added and selected custom EVM network '${net.name}' (chainId=${net.chainId}, symbol=${net.nativeSymbol}, RPC=${net.defaultRpcUrl})."
    }

    fun signMessageText(chainFamilyName: String, message: String): Result<SignedMessageProof> = runCatching {
        val (meta, acc) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized in Aqua Vault.")
        val family = when (chainFamilyName.trim().uppercase()) {
            "EVM", "ETH", "ETHEREUM", "" -> ChainFamily.EVM
            "SOL", "SOLANA" -> ChainFamily.SOLANA
            else -> throw IllegalArgumentException("Message signing supports EVM (EIP-191) or SOLANA (Ed25519).")
        }
        engine.utilities.signMessageLocally(meta, acc.accountIndex, family, message).getOrThrow()
    }

    fun verifyMessageText(
        chainFamilyName: String,
        signerAddress: String,
        message: String,
        signature: String
    ): Result<String> = runCatching {
        val family = when (chainFamilyName.trim().uppercase()) {
            "EVM", "ETH", "ETHEREUM", "" -> ChainFamily.EVM
            "SOL", "SOLANA" -> ChainFamily.SOLANA
            else -> throw IllegalArgumentException("Signature verification supports EVM or SOLANA.")
        }
        val valid = engine.utilities.verifySignedMessage(family, signerAddress, message, signature).getOrThrow()
        "Signature verification on ${family.name} for $signerAddress: ${if (valid) "VALID" else "INVALID"}"
    }

    fun updateWalletPreferencesText(
        displayCurrencyCode: String? = null,
        testnetModeEnabled: Boolean? = null
    ): Result<String> = runCatching {
        val s = engine.vault.getSecuritySettings()
        val newCurrency = displayCurrencyCode?.trim()?.takeIf { it.isNotBlank() }?.let {
            DisplayFiatCurrency.fromCode(it).code
        } ?: s.displayCurrencyCode
        val newTestnet = testnetModeEnabled ?: s.testnetModeEnabled
        engine.vault.updateSecuritySettings(
            s.copy(displayCurrencyCode = newCurrency, testnetModeEnabled = newTestnet)
        )
        "Updated Aqua Wallet preferences: Display Currency=$newCurrency, Testnet Mode=$newTestnet."
    }

    private suspend fun resolveDestinationAddress(rawDest: String, net: NetworkConfig): String {
        val clean = rawDest.trim()
        if (clean.isBlank()) return clean
        if (net.chainFamily == ChainFamily.EVM && clean.endsWith(".eth", ignoreCase = true)) {
            return engine.utilities.resolveEnsName(clean).getOrThrow()
        }
        if (!engine.adapterFor(net.chainFamily).validateAddress(clean, net)) {
            val contact = engine.utilities.findContactByLabel(clean, net.chainFamily)
            if (contact != null) return contact.address
        }
        return clean
    }

    suspend fun estimateFeesText(
        networkQuery: String,
        toAddress: String,
        assetSymbol: String,
        amountText: String
    ): Result<String> = runCatching {
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val pair = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet account available to derive sender address.")
        val fromAddr = pair.second.addressFor(net)
        val dest = resolveDestinationAddress(toAddress, net).ifBlank { fromAddr }
        val tokens = engine.getTokensForNetwork(net.id)
        val token = resolveToken(tokens, assetSymbol, net)
        val amt = amountText.trim().toBigDecimalOrNull() ?: BigDecimal("0.001")
        val est: FeeEstimate = engine.adapterFor(net.chainFamily)
            .estimateFee(fromAddr, dest, token, amt, net)
            .getOrThrow()
        val quotes = if (net.isTestnet) emptyMap() else engine.marketPriceService.fetchPricesUsd(listOf(net.coingeckoId))
        val nativeUsd = quotes[net.coingeckoId]?.priceUsd
        val feeUsdStr = nativeUsd?.let { " (~$${"%.4f".format(Locale.US, est.estimatedFeeDecimal.toDouble() * it)} USD)" } ?: ""
        "Live Fee Estimate on ${net.name} for ${token.symbol}: ${est.estimatedFeeDisplay}$feeUsdStr | Tiers: Slow=${est.slowLabel}, Standard=${est.standardLabel}, Fast=${est.fastLabel}"
    }

    suspend fun analyzeAddressRisk(address: String, networkQuery: String): Result<AddressRiskReport> = runCatching {
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val fromAddr = activeWalletAndAccount()?.second?.addressFor(net).orEmpty()
        val resolved = resolveDestinationAddress(address, net)
        val report = engine.adapterFor(net.chainFamily).analyzeAddressRisk(fromAddr, resolved, net)
        val contact = engine.utilities.findContactByAddress(resolved)
        if (contact != null) {
            report.copy(findings = listOf("Trusted Address Book contact: '${contact.label}'") + report.findings)
        } else {
            report
        }
    }

    suspend fun prepareUnsignedTransaction(
        networkQuery: String,
        toAddress: String,
        assetSymbol: String,
        amountText: String
    ): Result<PreparedChainTransaction> = runCatching {
        val (meta, account) = activeWalletAndAccount()
            ?: throw IllegalStateException("No wallet initialized in Aqua Vault.")
        val net = if (networkQuery.isNotBlank()) resolveNetwork(networkQuery) else activeNetwork()
        val resolvedDest = resolveDestinationAddress(toAddress, net)
        val tokens = engine.getTokensForNetwork(net.id)
        val token = resolveToken(tokens, assetSymbol, net)
        engine.prepareTransaction(
            walletMeta = meta,
            account = account,
            network = net,
            token = token,
            toAddress = resolvedDest,
            amountText = amountText
        ).getOrThrow()
    }

    suspend fun signAndBroadcastAuthorized(prepared: PreparedChainTransaction): Result<WalletTxRecord> {
        return engine.signAndBroadcastPrepared(prepared)
    }

    suspend fun resolveNetwork(query: String): NetworkConfig {
        val q = query.trim().lowercase()
        val nets = engine.getAvailableNetworks(includeTestnets = true)
        if (q.isBlank()) return activeNetwork()
        return nets.firstOrNull {
            it.id.equals(q, ignoreCase = true) ||
                it.name.equals(q, ignoreCase = true) ||
                it.nativeSymbol.equals(q, ignoreCase = true) ||
                it.name.lowercase().contains(q)
        } ?: throw IllegalArgumentException("Unknown blockchain network '$query'.")
    }

    private fun resolveToken(tokens: List<TokenDefinition>, assetSymbol: String, net: NetworkConfig): TokenDefinition {
        val sym = assetSymbol.trim()
        if (sym.isBlank()) return tokens.first { it.isNative }
        return tokens.firstOrNull { it.symbol.equals(sym, ignoreCase = true) }
            ?: tokens.firstOrNull { it.contractOrMintAddress.equals(sym, ignoreCase = true) }
            ?: throw IllegalArgumentException("Asset '$assetSymbol' is not configured on ${net.name}.")
    }
}
