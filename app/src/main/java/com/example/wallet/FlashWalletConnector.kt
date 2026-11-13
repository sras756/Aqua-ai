package com.example.wallet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.data.model.WalletConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

data class WalletIntegrationDiscovery(
    val packageInstalled: Boolean,
    val resolvedAppLabel: String,
    val supportsWalletConnectScheme: Boolean,
    val supportsCustomDeepLink: Boolean,
    val limitationNotice: String,
    val recommendedAlternatives: List<String>
)

data class WalletBalanceSnapshot(
    val networkName: String,
    val nativeSymbol: String,
    val nativeBalance: String,
    val usdtBalance: String?,
    val recentTransactions: List<ChainTxRecord>,
    val sourceInfo: String
)

data class ChainTxRecord(
    val hash: String,
    val status: String,
    val summary: String,
    val timestampLabel: String
)

data class PreparedTransferRequest(
    val destinationAddress: String,
    val networkName: String,
    val assetSymbol: String,
    val amount: String,
    val estimatedFeeDisplay: String,
    val eip681Uri: String,
    val canLaunchWalletApp: Boolean
)

class FlashWalletConnector(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Step 1: Integration Discovery.
     * Never assumes a Flash Wallet package name or undocumented API.
     * Inspects whether the user-specified official Flash Wallet Android package
     * is installed and which documented Android integration channels exist.
     */
    fun discoverIntegration(config: WalletConfig, customScheme: String = ""): WalletIntegrationDiscovery {
        val pm = context.packageManager
        val pkg = config.packageName.trim()

        var installed = false
        var appLabel = ""
        if (pkg.isNotEmpty()) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                installed = true
                appLabel = pm.getApplicationLabel(appInfo).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                installed = false
            }
        }

        val wcIntent = Intent(Intent.ACTION_VIEW, Uri.parse("wc:00000000-0000-0000-0000-000000000000@2"))
        val wcHandlers = pm.queryIntentActivities(wcIntent, 0)
        val supportsWc = if (pkg.isNotEmpty()) {
            wcHandlers.any { it.activityInfo.packageName == pkg }
        } else {
            wcHandlers.isNotEmpty()
        }

        val supportsScheme = if (customScheme.isNotBlank()) {
            val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("${customScheme.trim()}://"))
            pm.queryIntentActivities(schemeIntent, 0).let { list ->
                if (pkg.isNotEmpty()) list.any { it.activityInfo.packageName == pkg } else list.isNotEmpty()
            }
        } else {
            false
        }

        val limitation = buildString {
            if (pkg.isEmpty()) {
                append("Official Flash Crypto Wallet package name, developer identity, and integration docs have not been specified yet. ")
            } else if (!installed) {
                append("Package '$pkg' is not currently installed on this Android device. ")
            }
            if (config.docsUrl.isBlank()) {
                append("No official provider SDK/API documentation URL was supplied, so undocumented wallet APIs will not be guessed or simulated.")
            }
        }.trim()

        val alternatives = listOf(
            "Provide the exact Flash Wallet Android package name (e.g. from Google Play) and official integration docs.",
            "Connect in Read-Only Public Address mode using an official public JSON-RPC endpoint to inspect live balances and transactions.",
            "Use WalletConnect v2 / EIP-681 intent handoff if your installed Flash Wallet registers the wc: or ethereum: URI handler."
        )

        return WalletIntegrationDiscovery(
            packageInstalled = installed,
            resolvedAppLabel = appLabel,
            supportsWalletConnectScheme = supportsWc,
            supportsCustomDeepLink = supportsScheme,
            limitationNotice = limitation,
            recommendedAlternatives = alternatives
        )
    }

    /**
     * Real public blockchain balance & transaction inspection for read-only public addresses.
     * Supports EVM JSON-RPC endpoints (Ethereum, Polygon, BNB Smart Chain, Arbitrum, Base)
     * and Bitcoin public Mempool API. Zero fabricated balances.
     */
    suspend fun fetchLiveReadOnlySnapshot(config: WalletConfig): Result<WalletBalanceSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = config.publicAddress.trim()
                require(address.isNotEmpty()) {
                    "Enter a valid public wallet address to query live on-chain balances."
                }
                val networkLower = config.networkName.lowercase()
                if (networkLower.contains("bitcoin") || networkLower.contains("btc")) {
                    fetchBitcoinSnapshot(address, config.networkName)
                } else {
                    val rpc = config.rpcEndpoint.trim().ifBlank { defaultRpcForNetwork(config.networkName) }
                    require(rpc.startsWith("https://")) {
                        "A valid HTTPS JSON-RPC endpoint is required for ${config.networkName}."
                    }
                    fetchEvmSnapshot(address, config.networkName, rpc)
                }
            }
        }

    suspend fun fetchMarketPriceUsd(coinId: String): Result<Pair<String, Double>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = when (coinId.trim().lowercase()) {
                    "btc", "bitcoin" -> "bitcoin"
                    "eth", "ethereum" -> "ethereum"
                    "usdt", "tether" -> "tether"
                    "bnb" -> "binancecoin"
                    "sol", "solana" -> "solana"
                    "matic", "pol", "polygon" -> "matic-network"
                    else -> coinId.trim().lowercase()
                }
                val req = Request.Builder()
                    .url("https://api.coingecko.com/api/v3/simple/price?ids=$normalized&vs_currencies=usd")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Market price API returned HTTP ${resp.code}.")
                    }
                    val body = resp.body?.string() ?: throw IllegalStateException("Empty market response.")
                    val json = JSONObject(body)
                    val coinObj = json.optJSONObject(normalized)
                        ?: throw IllegalArgumentException("Asset '$coinId' not found on CoinGecko.")
                    val price = coinObj.getDouble("usd")
                    normalized to price
                }
            }
        }

    fun prepareTransferRequest(
        config: WalletConfig,
        destinationAddress: String,
        assetSymbol: String,
        amount: String,
        estimatedFeeDisplay: String = "Determined by signing wallet on ${config.networkName}"
    ): Result<PreparedTransferRequest> = runCatching {
        val dest = destinationAddress.trim()
        require(dest.isNotEmpty()) { "Destination address is required." }
        val amtDecimal = amount.trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Amount must be a valid positive number.")
        require(amtDecimal > BigDecimal.ZERO) { "Amount must be greater than zero." }

        val uri = if (config.networkName.lowercase().contains("bitcoin")) {
            "bitcoin:$dest?amount=${amtDecimal.stripTrailingZeros().toPlainString()}"
        } else {
            val wei = amtDecimal.multiply(BigDecimal.TEN.pow(18)).toBigInteger()
            "ethereum:$dest?value=$wei"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            if (config.packageName.isNotBlank()) setPackage(config.packageName.trim())
        }
        val canLaunch = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

        PreparedTransferRequest(
            destinationAddress = dest,
            networkName = config.networkName,
            assetSymbol = assetSymbol.uppercase(),
            amount = amtDecimal.stripTrailingZeros().toPlainString(),
            estimatedFeeDisplay = estimatedFeeDisplay,
            eip681Uri = uri,
            canLaunchWalletApp = canLaunch
        )
    }

    fun launchSigningIntent(uriString: String, packageName: String): Result<String> = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            if (packageName.isNotBlank()) setPackage(packageName.trim())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty()) {
            throw IllegalStateException(
                "No installed wallet app handles '$uriString'. Install or specify your Flash Wallet's official deep-link handler."
            )
        }
        context.startActivity(intent)
        "Handed signing request to external wallet app. Transaction confirmation depends on wallet & network finality."
    }

    private fun fetchEvmSnapshot(
        address: String,
        networkName: String,
        rpcUrl: String
    ): WalletBalanceSnapshot {
        require(Regex("^0x[a-fA-F0-9]{40}$").matches(address)) {
            "EVM address must be a 42-character 0x hex address."
        }
        val balanceHex = jsonRpcCall(rpcUrl, "eth_getBalance", JSONArray().put(address).put("latest"))
        val wei = BigInteger(balanceHex.removePrefix("0x").ifEmpty { "0" }, 16)
        val nativeDecimal = BigDecimal(wei).divide(BigDecimal.TEN.pow(18), 6, RoundingMode.DOWN)
            .stripTrailingZeros().toPlainString()

        val nativeSymbol = when {
            networkName.lowercase().contains("bnb") || networkName.lowercase().contains("bsc") -> "BNB"
            networkName.lowercase().contains("polygon") -> "POL"
            else -> "ETH"
        }

        // Optional USDT ERC-20 balance check on Ethereum Mainnet (official Tether contract 0xdAC17F958D2ee523a2206206994597C13D831ec7)
        val usdtBalance: String? = if (networkName.lowercase().contains("ethereum")) {
            runCatching {
                val paddedAddr = address.removePrefix("0x").lowercase().padStart(64, '0')
                val callObj = JSONObject().apply {
                    put("to", "0xdAC17F958D2ee523a2206206994597C13D831ec7")
                    put("data", "0x70a08231$paddedAddr")
                }
                val resHex = jsonRpcCall(rpcUrl, "eth_call", JSONArray().put(callObj).put("latest"))
                val rawUsdt = BigInteger(resHex.removePrefix("0x").ifEmpty { "0" }, 16)
                BigDecimal(rawUsdt).divide(BigDecimal.TEN.pow(6), 2, RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString()
            }.getOrNull()
        } else {
            null
        }

        return WalletBalanceSnapshot(
            networkName = networkName,
            nativeSymbol = nativeSymbol,
            nativeBalance = "$nativeDecimal $nativeSymbol",
            usdtBalance = usdtBalance?.let { "$it USDT" },
            recentTransactions = emptyList(),
            sourceInfo = "Live JSON-RPC: $rpcUrl (Standard JSON-RPC does not index historical txs without an explorer API key)"
        )
    }

    private fun fetchBitcoinSnapshot(address: String, networkName: String): WalletBalanceSnapshot {
        val url = "https://mempool.space/api/address/${Uri.encode(address)}"
        val req = Request.Builder().url(url).build()
        val body = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Mempool API HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("Empty Mempool response")
        }
        val obj = JSONObject(body)
        val chainStats = obj.getJSONObject("chain_stats")
        val funded = chainStats.getLong("funded_txo_sum")
        val spent = chainStats.getLong("spent_txo_sum")
        val sats = (funded - spent).coerceAtLeast(0L)
        val btc = BigDecimal(sats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.DOWN)
            .stripTrailingZeros().toPlainString()

        val txReq = Request.Builder().url("https://mempool.space/api/address/${Uri.encode(address)}/txs").build()
        val txs = runCatching {
            httpClient.newCall(txReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<ChainTxRecord>()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                (0 until minOf(arr.length(), 10)).map { idx ->
                    val tx = arr.getJSONObject(idx)
                    val txid = tx.getString("txid")
                    val statusObj = tx.optJSONObject("status")
                    val confirmed = statusObj?.optBoolean("confirmed", false) == true
                    val blockHeight = statusObj?.optLong("block_height", 0L) ?: 0L
                    ChainTxRecord(
                        hash = txid,
                        status = if (confirmed) "Confirmed (#$blockHeight)" else "Mempool Pending",
                        summary = "Fee: ${tx.optLong("fee", 0L)} sats",
                        timestampLabel = statusObj?.optLong("block_time", 0L)?.let {
                            if (it > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it * 1000L)) else "Pending"
                        } ?: "Pending"
                    )
                }
            }
        }.getOrDefault(emptyList())

        return WalletBalanceSnapshot(
            networkName = networkName,
            nativeSymbol = "BTC",
            nativeBalance = "$btc BTC",
            usdtBalance = null,
            recentTransactions = txs,
            sourceInfo = "Live Mempool.space API"
        )
    }

    private fun jsonRpcCall(rpcUrl: String, method: String, params: JSONArray): String {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        val req = Request.Builder()
            .url(rpcUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("RPC HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            if (json.has("error")) {
                throw IllegalStateException(json.getJSONObject("error").optString("message", "RPC error"))
            }
            return json.getString("result")
        }
    }

    private fun defaultRpcForNetwork(networkName: String): String {
        return when {
            networkName.lowercase().contains("polygon") -> "https://polygon-bor-rpc.publicnode.com"
            networkName.lowercase().contains("bnb") || networkName.lowercase().contains("bsc") ->
                "https://bsc-rpc.publicnode.com"
            networkName.lowercase().contains("arbitrum") -> "https://arbitrum-one-rpc.publicnode.com"
            networkName.lowercase().contains("base") -> "https://base-rpc.publicnode.com"
            networkName.lowercase().contains("sepolia") -> "https://ethereum-sepolia-rpc.publicnode.com"
            else -> "https://ethereum-rpc.publicnode.com"
        }
    }
}
