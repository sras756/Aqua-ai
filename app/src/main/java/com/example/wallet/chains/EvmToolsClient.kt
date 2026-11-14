package com.example.wallet.chains

import com.example.wallet.core.Erc20AllowanceResult
import com.example.wallet.core.EvmChainTelemetry
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.TokenDefinition
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.WalletMessageCrypto
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

/**
 * Live Ethereum Name Service (.eth), ERC-20 spender allowance inspection,
 * revoke calldata encoding, and real-time EVM gas/block/nonce telemetry client.
 */
class EvmToolsClient(
    private val httpClient: OkHttpClient
) {
    suspend fun resolveEnsName(
        ensName: String,
        ethereumMainnetRpc: String = ENS_MAINNET_RPC
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = ensName.trim().lowercase()
            require(clean.endsWith(".eth") && clean.length >= 5) {
                "ENS name must end with .eth (e.g. name.eth)."
            }
            val nodeHex = CryptoPrimitives.toHex(WalletMessageCrypto.ensNamehash(clean), prefix = false)

            // 1. Query ENS Registry: resolver(bytes32 node) -> selector 0x0178b8bf
            val resolverObj = JSONObject().apply {
                put("to", ENS_REGISTRY_MAINNET)
                put("data", "0x0178b8bf$nodeHex")
            }
            val resolverRaw = jsonRpc(ethereumMainnetRpc, "eth_call", JSONArray().put(resolverObj).put("latest"))
            val resolverAddr = extractAddressFromWord(resolverRaw)
                ?: throw IllegalStateException("No ENS resolver registered for '$clean'.")
            require(!resolverAddr.equals(ZERO_ADDRESS, ignoreCase = true)) {
                "No ENS resolver registered for '$clean'."
            }

            // 2. Query Public Resolver: addr(bytes32 node) -> selector 0x3b3b57de
            val addrCall = JSONObject().apply {
                put("to", resolverAddr)
                put("data", "0x3b3b57de$nodeHex")
            }
            val resolvedRaw = jsonRpc(ethereumMainnetRpc, "eth_call", JSONArray().put(addrCall).put("latest"))
            val resolvedAddr = extractAddressFromWord(resolvedRaw)
                ?: throw IllegalStateException("ENS name '$clean' does not resolve to an address.")
            require(!resolvedAddr.equals(ZERO_ADDRESS, ignoreCase = true)) {
                "ENS name '$clean' resolves to zero address."
            }
            CryptoPrimitives.toEip55ChecksumAddress(resolvedAddr)
        }
    }

    suspend fun fetchLiveChainTelemetry(
        address: String,
        network: NetworkConfig
    ): Result<EvmChainTelemetry> = withContext(Dispatchers.IO) {
        runCatching {
            val blockHex = jsonRpc(network.defaultRpcUrl, "eth_blockNumber", JSONArray())
            val gasHex = jsonRpc(network.defaultRpcUrl, "eth_gasPrice", JSONArray())
            val confirmedNonceHex = jsonRpc(
                network.defaultRpcUrl,
                "eth_getTransactionCount",
                JSONArray().put(address).put("latest")
            )
            val pendingNonceHex = jsonRpc(
                network.defaultRpcUrl,
                "eth_getTransactionCount",
                JSONArray().put(address).put("pending")
            )

            val gasWei = parseHexBigInt(gasHex)
            val gasGwei = BigDecimal(gasWei)
                .divide(BigDecimal.TEN.pow(9), 3, RoundingMode.HALF_UP)

            EvmChainTelemetry(
                networkId = network.id,
                blockNumber = parseHexBigInt(blockHex).toLong(),
                gasPriceGwei = gasGwei,
                confirmedNonce = parseHexBigInt(confirmedNonceHex),
                pendingNonce = parseHexBigInt(pendingNonceHex)
            )
        }
    }

    suspend fun fetchErc20Allowance(
        ownerAddress: String,
        token: TokenDefinition,
        spenderAddress: String,
        network: NetworkConfig
    ): Result<Erc20AllowanceResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(!token.isNative && token.contractOrMintAddress.isNotBlank()) {
                "Select an ERC-20 token contract (not native gas asset)."
            }
            require(CryptoPrimitives.isValidEvmAddress(ownerAddress, strictMixedCaseChecksum = false)) {
                "Invalid owner EVM address."
            }
            require(CryptoPrimitives.isValidEvmAddress(spenderAddress, strictMixedCaseChecksum = false)) {
                "Invalid spender contract address."
            }
            val ownerPadded = ownerAddress.removePrefix("0x").lowercase().padStart(64, '0')
            val spenderPadded = spenderAddress.removePrefix("0x").lowercase().padStart(64, '0')
            val callObj = JSONObject().apply {
                put("to", token.contractOrMintAddress)
                put("data", "0xdd62ed3e$ownerPadded$spenderPadded")
            }
            val rawHex = jsonRpc(network.defaultRpcUrl, "eth_call", JSONArray().put(callObj).put("latest"))
            val rawAllowance = parseHexBigInt(rawHex)
            val unlimitedThreshold = BigInteger.ONE.shiftLeft(255)
            val isUnlimited = rawAllowance >= unlimitedThreshold
            val formatted = if (isUnlimited) {
                "UNLIMITED (${token.symbol})"
            } else {
                val dec = BigDecimal(rawAllowance).divide(
                    BigDecimal.TEN.pow(token.decimals),
                    token.decimals.coerceAtMost(6),
                    RoundingMode.DOWN
                )
                "${dec.stripTrailingZeros().toPlainString()} ${token.symbol}"
            }
            Erc20AllowanceResult(
                networkId = network.id,
                tokenSymbol = token.symbol,
                tokenContract = token.contractOrMintAddress,
                ownerAddress = ownerAddress,
                spenderAddress = spenderAddress.trim(),
                rawAllowance = rawAllowance,
                formattedAllowance = formatted,
                isUnlimited = isUnlimited
            )
        }
    }

    fun encodeErc20Approve(spenderAddress: String, rawAmount: BigInteger): String {
        val cleanSpender = spenderAddress.trim().removePrefix("0x").lowercase().padStart(64, '0')
        val cleanAmt = rawAmount.toString(16).padStart(64, '0')
        return "0x095ea7b3$cleanSpender$cleanAmt"
    }

    private fun extractAddressFromWord(hexWord: String): String? {
        val clean = hexWord.removePrefix("0x").removePrefix("0X").trim()
        if (clean.length < 40) return null
        val tail40 = clean.takeLast(40)
        return "0x$tail40"
    }

    private fun parseHexBigInt(hex: String): BigInteger {
        val clean = hex.removePrefix("0x").removePrefix("0X").trim()
        return if (clean.isEmpty()) BigInteger.ZERO else BigInteger(clean, 16)
    }

    private fun jsonRpc(rpcUrl: String, method: String, params: JSONArray): String {
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
            if (!resp.isSuccessful) throw IllegalStateException("EVM RPC HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IllegalStateException("Empty RPC response")
            val json = JSONObject(body)
            if (json.has("error") && !json.isNull("error")) {
                throw IllegalStateException(json.getJSONObject("error").optString("message", "EVM RPC error"))
            }
            return json.opt("result")?.toString() ?: throw IllegalStateException("Empty RPC result for $method")
        }
    }

    companion object {
        const val ENS_REGISTRY_MAINNET = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e"
        const val ENS_MAINNET_RPC = "https://ethereum-rpc.publicnode.com"
        private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    }
}
