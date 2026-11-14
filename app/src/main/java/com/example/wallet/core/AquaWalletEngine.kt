package com.example.wallet.core

import android.net.Uri
import com.example.data.local.CachedWalletTxEntity
import com.example.data.local.WalletCacheDao
import com.example.data.local.WalletCustomNetworkEntity
import com.example.data.local.WalletCustomTokenEntity
import com.example.wallet.chains.BitcoinChainAdapter
import com.example.wallet.chains.ChainAdapter
import com.example.wallet.chains.EvmChainAdapter
import com.example.wallet.chains.EvmToolsClient
import com.example.wallet.chains.SolanaChainAdapter
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.security.WalletSecurityVault
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Collections
import java.util.concurrent.TimeUnit

data class ParsedPaymentUri(
    val address: String,
    val suggestedNetworkId: String?,
    val suggestedAmount: String?,
    val suggestedAsset: String?
)

class AquaWalletEngine(
    val vault: WalletSecurityVault,
    private val walletCacheDao: WalletCacheDao,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val evmAdapter = EvmChainAdapter(httpClient)
    private val solanaAdapter = SolanaChainAdapter(httpClient)
    private val bitcoinAdapter = BitcoinChainAdapter(httpClient)
    val marketPriceService = MarketPriceService(httpClient)
    val utilities = WalletUtilityService(
        vault = vault,
        dao = walletCacheDao,
        evmAdapter = evmAdapter,
        evmToolsClient = EvmToolsClient(httpClient),
        marketPriceService = marketPriceService,
        addressValidator = { address, family ->
            val probeNet = DefaultWalletRegistry.networks.firstOrNull { it.chainFamily == family }
                ?: DefaultWalletRegistry.networks.first()
            adapterFor(family).validateAddress(address, probeNet)
        }
    )

    private val inFlightIdempotencyKeys: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    fun adapterFor(family: ChainFamily): ChainAdapter = when (family) {
        ChainFamily.EVM -> evmAdapter
        ChainFamily.SOLANA -> solanaAdapter
        ChainFamily.BITCOIN -> bitcoinAdapter
    }

    suspend fun getAvailableNetworks(includeTestnets: Boolean): List<NetworkConfig> {
        val customs = walletCacheDao.getCustomNetworks().map { e ->
            NetworkConfig(
                id = e.id,
                name = e.name,
                chainFamily = ChainFamily.EVM,
                nativeSymbol = e.nativeSymbol,
                nativeDecimals = 18,
                chainId = e.chainId,
                slip44CoinType = 60,
                defaultRpcUrl = e.rpcUrl,
                explorerBaseUrl = e.explorerBaseUrl,
                coingeckoId = marketPriceService.normalizeCoinId(e.nativeSymbol),
                isTestnet = e.isTestnet,
                isCustom = true
            )
        }
        return (DefaultWalletRegistry.networks + customs).filter { includeTestnets || !it.isTestnet }
    }

    suspend fun getTokensForNetwork(networkId: String): List<TokenDefinition> {
        val defaults = DefaultWalletRegistry.defaultTokens.filter { it.networkId == networkId }
        val custom = walletCacheDao.getCustomTokens()
            .filter { it.networkId == networkId }
            .map {
                TokenDefinition(
                    id = it.id,
                    networkId = it.networkId,
                    symbol = it.symbol,
                    name = it.name,
                    decimals = it.decimals,
                    contractOrMintAddress = it.contractOrMintAddress,
                    coingeckoId = it.coingeckoId,
                    isNative = false,
                    enabled = it.enabled
                )
            }
        val merged = (defaults + custom).distinctBy { it.id }
        if (merged.none { it.isNative }) {
            val net = getAvailableNetworks(includeTestnets = true).firstOrNull { it.id == networkId }
            if (net != null) {
                return listOf(
                    TokenDefinition(
                        id = "${net.id}_native",
                        networkId = net.id,
                        symbol = net.nativeSymbol,
                        name = net.name,
                        decimals = net.nativeDecimals,
                        contractOrMintAddress = "",
                        coingeckoId = net.coingeckoId,
                        isNative = true
                    )
                ) + merged
            }
        }
        return merged
    }

    suspend fun addCustomToken(
        networkId: String,
        symbol: String,
        name: String,
        contractOrMint: String,
        decimals: Int,
        coingeckoId: String = ""
    ): Result<TokenDefinition> = runCatching {
        val cleanSym = symbol.trim().uppercase()
        val cleanAddr = contractOrMint.trim()
        require(cleanSym.isNotBlank() && cleanAddr.isNotBlank()) {
            "Token symbol and contract/mint address are required."
        }
        require(decimals in 0..18) { "Token decimals must be between 0 and 18." }
        val id = "tok_${networkId}_${cleanSym.lowercase()}_${cleanAddr.take(8).lowercase()}"
        val entity = WalletCustomTokenEntity(
            id = id,
            networkId = networkId,
            symbol = cleanSym,
            name = name.trim().ifBlank { cleanSym },
            decimals = decimals,
            contractOrMintAddress = cleanAddr,
            coingeckoId = coingeckoId.trim().ifBlank { marketPriceService.normalizeCoinId(cleanSym) },
            enabled = true
        )
        walletCacheDao.upsertCustomToken(entity)
        TokenDefinition(id, networkId, entity.symbol, entity.name, decimals, cleanAddr, entity.coingeckoId, false, true)
    }

    suspend fun addCustomEvmNetwork(
        name: String,
        nativeSymbol: String,
        chainId: Long,
        rpcUrl: String,
        explorerBaseUrl: String,
        isTestnet: Boolean
    ): Result<NetworkConfig> = runCatching {
        val cleanRpc = rpcUrl.trim()
        require(name.isNotBlank() && nativeSymbol.isNotBlank()) { "Network name and symbol are required." }
        require(chainId > 0L) { "EVM chainId must be positive." }
        require(cleanRpc.startsWith("https://")) { "Custom RPC URL must use HTTPS." }
        val id = "custom_evm_$chainId"
        val entity = WalletCustomNetworkEntity(
            id = id,
            name = name.trim(),
            nativeSymbol = nativeSymbol.trim().uppercase(),
            chainId = chainId,
            rpcUrl = cleanRpc,
            explorerBaseUrl = explorerBaseUrl.trim(),
            isTestnet = isTestnet
        )
        walletCacheDao.upsertCustomNetwork(entity)
        NetworkConfig(
            id = id,
            name = entity.name,
            chainFamily = ChainFamily.EVM,
            nativeSymbol = entity.nativeSymbol,
            nativeDecimals = 18,
            chainId = chainId,
            slip44CoinType = 60,
            defaultRpcUrl = cleanRpc,
            explorerBaseUrl = entity.explorerBaseUrl,
            coingeckoId = marketPriceService.normalizeCoinId(entity.nativeSymbol),
            isTestnet = isTestnet,
            isCustom = true
        )
    }

    suspend fun fetchPortfolio(
        account: WalletAccountInfo,
        includeTestnets: Boolean
    ): PortfolioSummary = coroutineScope {
        val networks = getAvailableNetworks(includeTestnets)
        val warnings = mutableListOf<String>()

        val perNetBalances = networks.map { net ->
            async {
                val addr = account.addressFor(net)
                if (addr.isBlank()) return@async net to emptyList()
                val tokens = getTokensForNetwork(net.id)
                val res = adapterFor(net.chainFamily).fetchBalances(addr, tokens, net)
                res.onFailure { err ->
                    warnings.add("${net.name}: ${err.localizedMessage ?: "RPC unreachable"}")
                }
                net to res.getOrDefault(emptyList())
            }
        }.awaitAll()

        val allAssets = perNetBalances.flatMap { it.second }
        val coinIds = allAssets.map { it.token.coingeckoId }.distinct()
        val quotes = marketPriceService.fetchPricesUsd(coinIds)

        var totalUsd = 0.0
        var hasAnyPrice = false
        var weightedChangeNumerator = 0.0
        val chainTotals = mutableMapOf<String, Double>()

        val pricedAssets = perNetBalances.flatMap { (net, balances) ->
            balances.map { bal ->
                val quote = if (net.isTestnet) null else quotes[bal.token.coingeckoId.lowercase()]
                val price = quote?.priceUsd
                val valUsd = if (price != null) bal.formattedAmount.toDouble() * price else null
                if (valUsd != null) {
                    hasAnyPrice = true
                    totalUsd += valUsd
                    chainTotals[net.name] = (chainTotals[net.name] ?: 0.0) + valUsd
                    val chg = quote?.change24hPct
                    if (chg != null) {
                        weightedChangeNumerator += valUsd * chg
                    }
                }
                bal.copy(
                    priceUsd = price,
                    valueUsd = valUsd,
                    change24hPct = quote?.change24hPct
                )
            }
        }

        val weightedChange = if (hasAnyPrice && totalUsd > 0.0) {
            weightedChangeNumerator / totalUsd
        } else {
            null
        }

        PortfolioSummary(
            totalValueUsd = if (hasAnyPrice) totalUsd else null,
            weighted24hChangePct = weightedChange,
            assets = pricedAssets,
            chainTotalsUsd = chainTotals,
            lastUpdatedMs = System.currentTimeMillis(),
            warnings = warnings
        )
    }

    fun observeCachedTransactions(walletId: String): Flow<List<WalletTxRecord>> =
        walletCacheDao.observeTransactionsForWallet(walletId).map { list -> list.map { it.toModel() } }

    suspend fun syncTransactionHistory(
        walletId: String,
        account: WalletAccountInfo,
        includeTestnets: Boolean
    ): List<WalletTxRecord> = coroutineScope {
        val networks = getAvailableNetworks(includeTestnets)
        // 1. Refresh any local PENDING transactions
        val existing = walletCacheDao.getTransactionsForWallet(walletId).map { it.toModel() }
        for (tx in existing.filter { it.status == TxStatus.PENDING }) {
            val net = networks.firstOrNull { it.id == tx.networkId } ?: continue
            adapterFor(net.chainFamily).fetchTransactionStatus(tx.txHash, tx, net)
                .onSuccess { updated -> walletCacheDao.upsertTransaction(updated.toEntity()) }
        }

        // 2. Fetch live remote tx history (Solana, Bitcoin, etc.)
        val remoteFetched = networks.map { net ->
            async {
                val addr = account.addressFor(net)
                if (addr.isBlank()) return@async emptyList()
                adapterFor(net.chainFamily).fetchTransactionHistory(addr, net)
                    .getOrDefault(emptyList())
                    .map { it.copy(walletId = walletId) }
            }
        }.awaitAll().flatten()

        if (remoteFetched.isNotEmpty()) {
            walletCacheDao.upsertTransactions(remoteFetched.map { it.toEntity() })
        }

        walletCacheDao.getTransactionsForWallet(walletId).map { it.toModel() }
    }

    suspend fun refreshSingleTxStatus(tx: WalletTxRecord): Result<WalletTxRecord> = runCatching {
        val nets = getAvailableNetworks(includeTestnets = true)
        val net = nets.firstOrNull { it.id == tx.networkId }
            ?: throw IllegalStateException("Network '${tx.networkId}' not found.")
        val updated = adapterFor(net.chainFamily)
            .fetchTransactionStatus(tx.txHash, tx, net)
            .getOrThrow()
            .copy(walletId = tx.walletId)
        walletCacheDao.upsertTransaction(updated.toEntity())
        updated
    }

    suspend fun prepareTransaction(
        walletMeta: WalletMetadata,
        account: WalletAccountInfo,
        network: NetworkConfig,
        token: TokenDefinition,
        toAddress: String,
        amountText: String
    ): Result<PreparedChainTransaction> = runCatching {
        val settings = vault.getSecuritySettings()
        if (settings.emergencyLocked) {
            throw SecurityException("Emergency Wallet Lock is active. Unlock in Security Center first.")
        }
        require(!walletMeta.isWatchOnly) {
            "This is a Watch-Only wallet and cannot construct signed transfers."
        }
        val adapter = adapterFor(network.chainFamily)
        val fromAddress = account.addressFor(network)
        require(fromAddress.isNotBlank()) { "No derived address for ${network.name}." }
        val cleanDest = toAddress.trim()
        require(adapter.validateAddress(cleanDest, network)) {
            "Recipient address '$cleanDest' is not valid on ${network.name}."
        }
        val amtDecimal = amountText.trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Amount must be a valid positive decimal.")
        require(amtDecimal > BigDecimal.ZERO) { "Transfer amount must be greater than zero." }

        // 1. Live balance verification
        val balances = adapter.fetchBalances(fromAddress, listOf(token), network).getOrThrow()
        val assetBal = balances.firstOrNull { it.token.id == token.id }
            ?: balances.firstOrNull { it.token.symbol.equals(token.symbol, ignoreCase = true) }
            ?: throw IllegalStateException("Could not verify balance for ${token.symbol}.")
        require(assetBal.formattedAmount >= amtDecimal) {
            "Insufficient ${token.symbol} balance (${assetBal.displayBalance} available)."
        }

        // 2. Fee estimation & price check for spending limits
        val feeEst = adapter.estimateFee(fromAddress, cleanDest, token, amtDecimal, network).getOrThrow()
        val quotes = if (network.isTestnet) emptyMap() else {
            marketPriceService.fetchPricesUsd(listOf(token.coingeckoId, network.coingeckoId))
        }
        val tokenPriceUsd = quotes[token.coingeckoId]?.priceUsd
        val nativePriceUsd = quotes[network.coingeckoId]?.priceUsd
        val feeWithUsd = feeEst.copy(
            estimatedFeeUsd = nativePriceUsd?.let { feeEst.estimatedFeeDecimal.toDouble() * it }
        )

        if (token.isNative) {
            val totalNeeded = amtDecimal + feeWithUsd.estimatedFeeDecimal
            require(assetBal.formattedAmount >= totalNeeded) {
                "Insufficient ${token.symbol} for amount + network fee (${totalNeeded.stripTrailingZeros().toPlainString()} ${token.symbol} needed)."
            }
        } else {
            val nativeToken = DefaultWalletRegistry.defaultTokens.firstOrNull { it.networkId == network.id && it.isNative }
                ?: TokenDefinition("${network.id}_native", network.id, network.nativeSymbol, network.name, network.nativeDecimals, "", network.coingeckoId, isNative = true)
            val nativeBal = adapter.fetchBalances(fromAddress, listOf(nativeToken), network).getOrThrow().firstOrNull()
            require((nativeBal?.formattedAmount ?: BigDecimal.ZERO) >= feeWithUsd.estimatedFeeDecimal) {
                "Insufficient ${network.nativeSymbol} to cover gas/network fee (${feeWithUsd.estimatedFeeDisplay})."
            }
        }

        if (!network.isTestnet && tokenPriceUsd != null) {
            val estimatedUsd = amtDecimal.toDouble() * tokenPriceUsd
            vault.checkSpendingLimit(estimatedUsd).getOrThrow()
        }

        // 3. Risk inspection + Trusted Address Book match
        val rawRiskReport = adapter.analyzeAddressRisk(fromAddress, cleanDest, network)
        if (rawRiskReport.riskLevel == RiskLevel.CRITICAL) {
            throw SecurityException(rawRiskReport.findings.joinToString(" "))
        }
        val contactMatch = utilities.findContactByAddress(cleanDest)
        val riskReport = if (contactMatch != null) {
            rawRiskReport.copy(
                findings = listOf("Trusted Address Book contact: '${contactMatch.label}'") + rawRiskReport.findings
            )
        } else {
            rawRiskReport
        }

        val totalCostDisplay = if (token.isNative) {
            val total = (amtDecimal + feeWithUsd.estimatedFeeDecimal).stripTrailingZeros().toPlainString()
            "$total ${token.symbol}"
        } else {
            "${amtDecimal.stripTrailingZeros().toPlainString()} ${token.symbol} + ${feeWithUsd.estimatedFeeDisplay}"
        }

        val keyInput = "${walletMeta.walletId}|${account.accountIndex}|${network.id}|$cleanDest|${token.symbol}|${amtDecimal.toPlainString()}|${System.currentTimeMillis() / 15_000L}"
        val idempotencyKey = CryptoPrimitives.toHex(CryptoPrimitives.sha256(keyInput.toByteArray())).take(24)

        val draft = PreparedChainTransaction(
            id = "prep_$idempotencyKey",
            idempotencyKey = idempotencyKey,
            walletId = walletMeta.walletId,
            accountIndex = account.accountIndex,
            network = network,
            fromAddress = fromAddress,
            toAddress = cleanDest,
            token = token,
            amountDecimal = amtDecimal,
            feeEstimate = feeWithUsd,
            totalCostDisplay = totalCostDisplay,
            nonceOrBlockhash = "",
            utxosPayloadJson = "",
            simulationSummary = "",
            riskReport = riskReport
        )

        // 4. Pre-flight chain simulation
        val simSummary = adapter.simulateTransaction(draft).getOrElse { err ->
            throw IllegalStateException("Transaction simulation failed: ${err.localizedMessage}")
        }
        draft.copy(simulationSummary = simSummary)
    }

    suspend fun signAndBroadcastPrepared(
        prepared: PreparedChainTransaction
    ): Result<WalletTxRecord> {
        // Duplicate-submission protection
        val existingDup = walletCacheDao.findByIdempotencyKey(prepared.idempotencyKey)
        if (existingDup != null && existingDup.status != TxStatus.FAILED.name) {
            return Result.failure(
                IllegalStateException("Duplicate submission prevented: transaction ${existingDup.txHash.take(12)}... is already ${existingDup.status}.")
            )
        }
        if (!inFlightIdempotencyKeys.add(prepared.idempotencyKey)) {
            return Result.failure(IllegalStateException("Transaction broadcast already in progress."))
        }

        return try {
            val seedRes = vault.withSeed(prepared.walletId) { seed -> seed.copyOf() }
            if (seedRes.isFailure) {
                return Result.failure(seedRes.exceptionOrNull() ?: SecurityException("Vault locked"))
            }
            val seedCopy = seedRes.getOrThrow()
            try {
                val res = adapterFor(prepared.network.chainFamily).signAndBroadcast(prepared, seedCopy)
                res.onSuccess { record ->
                    walletCacheDao.upsertTransaction(record.toEntity())
                    if (!prepared.network.isTestnet) {
                        val quotes = marketPriceService.fetchPricesUsd(listOf(prepared.token.coingeckoId))
                        val price = quotes[prepared.token.coingeckoId]?.priceUsd ?: 0.0
                        vault.recordBroadcastSpend(prepared.amountDecimal.toDouble() * price)
                    }
                }.onFailure { err ->
                    val failedRec = WalletTxRecord(
                        txHash = "failed_${prepared.idempotencyKey}",
                        idempotencyKey = prepared.idempotencyKey,
                        walletId = prepared.walletId,
                        networkId = prepared.network.id,
                        networkName = prepared.network.name,
                        fromAddress = prepared.fromAddress,
                        toAddress = prepared.toAddress,
                        assetSymbol = prepared.token.symbol,
                        amountDisplay = "${prepared.amountDecimal.stripTrailingZeros().toPlainString()} ${prepared.token.symbol}",
                        feeDisplay = prepared.feeEstimate.estimatedFeeDisplay,
                        status = TxStatus.FAILED,
                        confirmations = 0,
                        timestampMs = System.currentTimeMillis(),
                        explorerUrl = "",
                        direction = TxDirection.OUTBOUND,
                        errorMessage = err.localizedMessage ?: "Broadcast failed"
                    )
                    walletCacheDao.upsertTransaction(failedRec.toEntity())
                }
                res
            } finally {
                CryptoPrimitives.wipe(seedCopy)
            }
        } finally {
            inFlightIdempotencyKeys.remove(prepared.idempotencyKey)
        }
    }

    fun parseScannedPaymentUri(raw: String): ParsedPaymentUri {
        val text = raw.trim()
        if (text.startsWith("ethereum:", ignoreCase = true)) {
            val body = text.substringAfter(":").substringBefore("?")
            val cleanAddr = body.substringBefore("@")
            val query = text.substringAfter("?", "")
            val parsedUri = runCatching { Uri.parse("http://x?$query") }.getOrNull()
            val valueWei = parsedUri?.getQueryParameter("value")?.toBigDecimalOrNull()
            val amountEth = valueWei?.divide(BigDecimal.TEN.pow(18), 8, RoundingMode.DOWN)?.stripTrailingZeros()?.toPlainString()
            return ParsedPaymentUri(cleanAddr, "eth_mainnet", amountEth, "ETH")
        }
        if (text.startsWith("bitcoin:", ignoreCase = true)) {
            val addr = text.substringAfter(":").substringBefore("?")
            val query = text.substringAfter("?", "")
            val parsedUri = runCatching { Uri.parse("http://x?$query") }.getOrNull()
            val amt = parsedUri?.getQueryParameter("amount")
            val isTest = addr.startsWith("tb1", ignoreCase = true)
            return ParsedPaymentUri(addr, if (isTest) "btc_testnet" else "btc_mainnet", amt, if (isTest) "tBTC" else "BTC")
        }
        if (text.startsWith("solana:", ignoreCase = true)) {
            val addr = text.substringAfter(":").substringBefore("?")
            val query = text.substringAfter("?", "")
            val parsedUri = runCatching { Uri.parse("http://x?$query") }.getOrNull()
            val amt = parsedUri?.getQueryParameter("amount")
            return ParsedPaymentUri(addr, "solana_mainnet", amt, "SOL")
        }
        val netGuess = when {
            text.startsWith("0x") -> "eth_mainnet"
            text.startsWith("bc1") -> "btc_mainnet"
            text.startsWith("tb1") -> "btc_testnet"
            text.length in 32..44 -> "solana_mainnet"
            else -> null
        }
        return ParsedPaymentUri(text, netGuess, null, null)
    }

    private fun CachedWalletTxEntity.toModel(): WalletTxRecord = WalletTxRecord(
        txHash = txHash,
        idempotencyKey = idempotencyKey,
        walletId = walletId,
        networkId = networkId,
        networkName = networkName,
        fromAddress = fromAddress,
        toAddress = toAddress,
        assetSymbol = assetSymbol,
        amountDisplay = amountDisplay,
        feeDisplay = feeDisplay,
        status = runCatching { TxStatus.valueOf(status) }.getOrDefault(TxStatus.PENDING),
        confirmations = confirmations,
        timestampMs = timestampMs,
        explorerUrl = explorerUrl,
        direction = runCatching { TxDirection.valueOf(direction) }.getOrDefault(TxDirection.OUTBOUND),
        errorMessage = errorMessage
    )

    private fun WalletTxRecord.toEntity(): CachedWalletTxEntity = CachedWalletTxEntity(
        txHash = txHash,
        idempotencyKey = idempotencyKey,
        walletId = walletId,
        networkId = networkId,
        networkName = networkName,
        fromAddress = fromAddress,
        toAddress = toAddress,
        assetSymbol = assetSymbol,
        amountDisplay = amountDisplay,
        feeDisplay = feeDisplay,
        status = status.name,
        confirmations = confirmations,
        timestampMs = timestampMs,
        explorerUrl = explorerUrl,
        direction = direction.name,
        errorMessage = errorMessage
    )
}
