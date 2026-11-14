package com.example.wallet.ai

import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AquaWalletEngine
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.PortfolioSummary
import com.example.wallet.core.PreparedChainTransaction
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
 * - READ ONLY: balances, portfolio analytics, transaction history, network/token metadata,
 *   fee estimation, and recipient risk checks.
 * - GATED PREPARE: unsigned transaction preparation (`prepareUnsignedTransaction`) only;
 *   cannot bypass explicit user authorization, cannot sign autonomously, cannot access
 *   mnemonics/seeds/private keys, cannot alter spending limits, and cannot unlock emergency lock.
 */
class AquaAiWalletBridge(
    private val engine: AquaWalletEngine
) {

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
            appendLine("Security Posture: PIN=${settings.hasPin}, Biometric=${settings.biometricEnabled}, EmergencyLock=${settings.emergencyLocked}, BackupVerified=${meta.backupVerified}")
            appendLine("Limits: Per-Tx=$${"%.2f".format(Locale.US, settings.perTxLimitUsd)}, Daily=$${"%.2f".format(Locale.US, settings.dailyLimitUsd)} (Spent Today=$${"%.2f".format(Locale.US, settings.spentTodayUsd)})")
        }
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
                    appendLine("- [${tx.status}] ${tx.networkName}: ${tx.amountDisplay} | ${tx.direction} | fee=${tx.feeDisplay} | conf=${tx.confirmations} | hash=${tx.txHash.take(18)}...")
                }
            }
        }
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

    private suspend fun resolveDestinationAddress(rawDest: String, net: NetworkConfig): String {
        val clean = rawDest.trim()
        if (clean.isBlank()) return clean
        if (net.chainFamily == com.example.wallet.core.ChainFamily.EVM && clean.endsWith(".eth", ignoreCase = true)) {
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
