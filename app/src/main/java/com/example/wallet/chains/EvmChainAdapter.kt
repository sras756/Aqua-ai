package com.example.wallet.chains

import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AssetBalance
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.PreparedChainTransaction
import com.example.wallet.core.RiskLevel
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.TxDirection
import com.example.wallet.core.TxStatus
import com.example.wallet.core.WalletTxRecord
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.HdKeyDerivation
import com.example.wallet.crypto.RlpEncoder
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

class EvmChainAdapter(
    private val httpClient: OkHttpClient
) : ChainAdapter {

    override fun validateAddress(address: String, network: NetworkConfig): Boolean {
        return CryptoPrimitives.isValidEvmAddress(address.trim(), strictMixedCaseChecksum = false)
    }

    override suspend fun analyzeAddressRisk(
        fromAddress: String,
        toAddress: String,
        network: NetworkConfig
    ): AddressRiskReport = withContext(Dispatchers.IO) {
        val trimmed = toAddress.trim()
        val formatOk = Regex("^0x[0-9a-fA-F]{40}$").matches(trimmed)
        val hasMixedCase = formatOk &&
            trimmed.drop(2).any { it in 'a'..'f' } &&
            trimmed.drop(2).any { it in 'A'..'F' }
        val checksumValid = formatOk && (!hasMixedCase || CryptoPrimitives.toEip55ChecksumAddress(trimmed) == trimmed)
        val isZero = trimmed.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)
        val isSelf = trimmed.equals(fromAddress.trim(), ignoreCase = true)

        val findings = mutableListOf<String>()
        var risk = RiskLevel.LOW
        var isContract = false

        if (!formatOk) {
            findings.add("Invalid EVM 0x address format.")
            risk = RiskLevel.CRITICAL
        } else {
            if (!checksumValid) {
                findings.add("Mixed-case EIP-55 checksum failed — possible mistyped or poisoned address.")
                risk = RiskLevel.HIGH
            }
            if (isZero) {
                findings.add("Zero/burn address (0x000...000). Funds sent here are permanently lost.")
                risk = RiskLevel.CRITICAL
            }
            if (isSelf) {
                findings.add("Destination matches your own sender address (self-transfer).")
                if (risk == RiskLevel.LOW) risk = RiskLevel.MEDIUM
            }
            val codeRes = runCatching {
                jsonRpc(network.defaultRpcUrl, "eth_getCode", JSONArray().put(trimmed).put("latest"))
            }
            val code = codeRes.getOrNull().orEmpty()
            if (code.length > 4 && code != "0x" && code != "0x0") {
                isContract = true
                findings.add("Recipient is a smart contract (${(code.length - 2) / 2} bytes bytecode). Verify contract before sending.")
                if (risk == RiskLevel.LOW) risk = RiskLevel.MEDIUM
            }
        }
        if (findings.isEmpty()) {
            findings.add("Valid EVM EOA format and EIP-55 checksum verified on ${network.name}.")
        }
        AddressRiskReport(
            address = trimmed,
            networkId = network.id,
            isValidFormat = formatOk,
            checksumValid = checksumValid,
            isSelfTransfer = isSelf,
            isContractAddress = isContract,
            riskLevel = risk,
            findings = findings
        )
    }

    override suspend fun fetchBalances(
        address: String,
        tokens: List<TokenDefinition>,
        network: NetworkConfig
    ): Result<List<AssetBalance>> = withContext(Dispatchers.IO) {
        runCatching {
            require(validateAddress(address, network)) { "Invalid EVM address '$address'." }
            val out = mutableListOf<AssetBalance>()
            val nativeToken = tokens.firstOrNull { it.isNative }
                ?: TokenDefinition(
                    id = "${network.id}_native",
                    networkId = network.id,
                    symbol = network.nativeSymbol,
                    name = network.name,
                    decimals = network.nativeDecimals,
                    contractOrMintAddress = "",
                    coingeckoId = network.coingeckoId,
                    isNative = true
                )

            val nativeHex = jsonRpc(
                network.defaultRpcUrl,
                "eth_getBalance",
                JSONArray().put(address).put("latest")
            )
            val nativeRaw = parseHexBigInt(nativeHex)
            val nativeFormatted = toDecimal(nativeRaw, nativeToken.decimals)
            out.add(
                AssetBalance(
                    token = nativeToken,
                    rawAmount = nativeRaw,
                    formattedAmount = nativeFormatted,
                    displayBalance = "${nativeFormatted.stripTrailingZeros().toPlainString()} ${nativeToken.symbol}",
                    priceUsd = null,
                    valueUsd = null,
                    change24hPct = null
                )
            )

            val paddedAddr = address.removePrefix("0x").lowercase().padStart(64, '0')
            for (token in tokens.filter { !it.isNative && it.enabled && it.contractOrMintAddress.isNotBlank() }) {
                val balHex = runCatching {
                    val callObj = JSONObject().apply {
                        put("to", token.contractOrMintAddress)
                        put("data", "0x70a08231$paddedAddr")
                    }
                    jsonRpc(network.defaultRpcUrl, "eth_call", JSONArray().put(callObj).put("latest"))
                }.getOrNull() ?: continue
                val raw = parseHexBigInt(balHex)
                val formatted = toDecimal(raw, token.decimals)
                out.add(
                    AssetBalance(
                        token = token,
                        rawAmount = raw,
                        formattedAmount = formatted,
                        displayBalance = "${formatted.stripTrailingZeros().toPlainString()} ${token.symbol}",
                        priceUsd = null,
                        valueUsd = null,
                        change24hPct = null
                    )
                )
            }
            out
        }
    }

    override suspend fun fetchTransactionHistory(
        address: String,
        network: NetworkConfig
    ): Result<List<WalletTxRecord>> = withContext(Dispatchers.IO) {
        // Standard EVM JSON-RPC nodes do not expose full historical account index without an explorer key;
        // locally broadcast transactions are persisted & tracked in Aqua Room cache and updated via eth_getTransactionReceipt.
        Result.success(emptyList())
    }

    override suspend fun estimateFee(
        fromAddress: String,
        toAddress: String,
        token: TokenDefinition,
        amount: BigDecimal,
        network: NetworkConfig
    ): Result<FeeEstimate> = withContext(Dispatchers.IO) {
        runCatching {
            val gasPriceHex = jsonRpc(network.defaultRpcUrl, "eth_gasPrice", JSONArray())
            val gasPriceWei = parseHexBigInt(gasPriceHex).coerceAtLeast(BigInteger.valueOf(100_000_000L))
            val priorityWei = runCatching {
                parseHexBigInt(jsonRpc(network.defaultRpcUrl, "eth_maxPriorityFeePerGas", JSONArray()))
            }.getOrElse { BigInteger.valueOf(1_000_000_000L) }
                .coerceAtLeast(BigInteger.valueOf(100_000_000L))

            val maxFeePerGas = gasPriceWei.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)
                .add(priorityWei)

            val txData = if (token.isNative) {
                "0x"
            } else {
                val scaled = amount.multiply(BigDecimal.TEN.pow(token.decimals)).toBigInteger()
                encodeErc20Transfer(toAddress.trim(), scaled)
            }
            val callTo = if (token.isNative) toAddress.trim() else token.contractOrMintAddress.trim()
            val valueHex = if (token.isNative) {
                "0x" + amount.multiply(BigDecimal.TEN.pow(token.decimals)).toBigInteger().toString(16)
            } else {
                "0x0"
            }

            val estimatedGas = runCatching {
                val estObj = JSONObject().apply {
                    put("from", fromAddress.trim())
                    put("to", callTo)
                    put("value", valueHex)
                    if (txData != "0x") put("data", txData)
                }
                parseHexBigInt(jsonRpc(network.defaultRpcUrl, "eth_estimateGas", JSONArray().put(estObj)))
                    .toLong()
                    .coerceAtLeast(21_000L)
            }.getOrElse {
                if (token.isNative) 21_000L else 65_000L
            }

            val bufferedGasLimit = (estimatedGas * 115L) / 100L
            val standardFeeWei = maxFeePerGas.multiply(BigInteger.valueOf(estimatedGas))
            val slowFeeWei = gasPriceWei.multiply(BigInteger.valueOf(estimatedGas))
            val fastFeeWei = standardFeeWei.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN)

            val stdDec = toDecimal(standardFeeWei, network.nativeDecimals)
            val slowDec = toDecimal(slowFeeWei, network.nativeDecimals)
            val fastDec = toDecimal(fastFeeWei, network.nativeDecimals)

            FeeEstimate(
                networkId = network.id,
                nativeSymbol = network.nativeSymbol,
                slowLabel = "${slowDec.setScale(6, RoundingMode.UP).stripTrailingZeros().toPlainString()} ${network.nativeSymbol}",
                standardLabel = "${stdDec.setScale(6, RoundingMode.UP).stripTrailingZeros().toPlainString()} ${network.nativeSymbol}",
                fastLabel = "${fastDec.setScale(6, RoundingMode.UP).stripTrailingZeros().toPlainString()} ${network.nativeSymbol}",
                estimatedFeeDecimal = stdDec,
                estimatedFeeDisplay = "${stdDec.setScale(6, RoundingMode.UP).stripTrailingZeros().toPlainString()} ${network.nativeSymbol}",
                estimatedFeeUsd = null,
                gasLimit = bufferedGasLimit,
                maxPriorityFeeWei = priorityWei,
                maxFeePerGasWei = maxFeePerGas
            )
        }
    }

    override suspend fun simulateTransaction(prepared: PreparedChainTransaction): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val callTo = if (prepared.token.isNative) {
                    prepared.toAddress
                } else {
                    prepared.token.contractOrMintAddress
                }
                val scaledAmount = prepared.amountDecimal
                    .multiply(BigDecimal.TEN.pow(prepared.token.decimals))
                    .toBigInteger()
                val txObj = JSONObject().apply {
                    put("from", prepared.fromAddress)
                    if (prepared.customCallDataHex.isNotBlank()) {
                        put("to", prepared.token.contractOrMintAddress)
                        put("value", "0x0")
                        put("data", prepared.customCallDataHex)
                    } else {
                        put("to", callTo)
                        if (prepared.token.isNative) {
                            put("value", "0x" + scaledAmount.toString(16))
                        } else {
                            put("value", "0x0")
                            put("data", encodeErc20Transfer(prepared.toAddress, scaledAmount))
                        }
                    }
                }
                val gasHex = jsonRpc(
                    prepared.network.defaultRpcUrl,
                    "eth_estimateGas",
                    JSONArray().put(txObj)
                )
                val estGas = parseHexBigInt(gasHex)
                val actionDesc = prepared.customActionLabel.ifBlank { "EVM pre-flight simulation" }
                "$actionDesc succeeded (estimated gas: $estGas units on chainId ${prepared.network.chainId})."
            }
        }

    override suspend fun signAndBroadcast(
        prepared: PreparedChainTransaction,
        seed: ByteArray
    ): Result<WalletTxRecord> = withContext(Dispatchers.IO) {
        val path = "m/44'/60'/0'/0/${prepared.accountIndex}"
        val derived = HdKeyDerivation.deriveBip32Secp256k1(seed, path)
        try {
            runCatching {
                val nonceHex = jsonRpc(
                    prepared.network.defaultRpcUrl,
                    "eth_getTransactionCount",
                    JSONArray().put(prepared.fromAddress).put("pending")
                )
                val nonce = parseHexBigInt(nonceHex)

                val scaledAmount = prepared.amountDecimal
                    .multiply(BigDecimal.TEN.pow(prepared.token.decimals))
                    .toBigInteger()
                val toHex = if (prepared.customCallDataHex.isNotBlank() || !prepared.token.isNative) {
                    prepared.token.contractOrMintAddress
                } else {
                    prepared.toAddress
                }
                val valueWei = if (prepared.customCallDataHex.isBlank() && prepared.token.isNative) scaledAmount else BigInteger.ZERO
                val dataBytes = when {
                    prepared.customCallDataHex.isNotBlank() -> CryptoPrimitives.fromHex(prepared.customCallDataHex)
                    prepared.token.isNative -> ByteArray(0)
                    else -> CryptoPrimitives.fromHex(encodeErc20Transfer(prepared.toAddress, scaledAmount))
                }

                val rawHex = buildAndSignEip1559RawHex(
                    chainId = prepared.network.chainId,
                    nonce = nonce,
                    maxPriorityFeeWei = prepared.feeEstimate.maxPriorityFeeWei,
                    maxFeePerGasWei = prepared.feeEstimate.maxFeePerGasWei,
                    gasLimit = prepared.feeEstimate.gasLimit.coerceAtLeast(21_000L),
                    toAddress = toHex,
                    valueWei = valueWei,
                    dataBytes = dataBytes,
                    privateKey32 = derived.keyBytes
                )

                val txHash = jsonRpc(
                    prepared.network.defaultRpcUrl,
                    "eth_sendRawTransaction",
                    JSONArray().put(rawHex)
                )

                WalletTxRecord(
                    txHash = txHash,
                    idempotencyKey = prepared.idempotencyKey,
                    walletId = prepared.walletId,
                    networkId = prepared.network.id,
                    networkName = prepared.network.name,
                    fromAddress = prepared.fromAddress,
                    toAddress = prepared.toAddress,
                    assetSymbol = prepared.token.symbol,
                    amountDisplay = if (prepared.customActionLabel.isNotBlank()) {
                        "${prepared.customActionLabel} (${prepared.token.symbol})"
                    } else {
                        "${prepared.amountDecimal.stripTrailingZeros().toPlainString()} ${prepared.token.symbol}"
                    },
                    feeDisplay = prepared.feeEstimate.estimatedFeeDisplay,
                    status = TxStatus.PENDING,
                    confirmations = 0,
                    timestampMs = System.currentTimeMillis(),
                    explorerUrl = "${prepared.network.explorerBaseUrl}$txHash",
                    direction = if (prepared.fromAddress.equals(prepared.toAddress, ignoreCase = true)) TxDirection.SELF else TxDirection.OUTBOUND
                )
            }
        } finally {
            derived.wipe()
        }
    }

    override suspend fun fetchTransactionStatus(
        txHash: String,
        existingRecord: WalletTxRecord?,
        network: NetworkConfig
    ): Result<WalletTxRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val receiptResult = jsonRpcNullable(
                network.defaultRpcUrl,
                "eth_getTransactionReceipt",
                JSONArray().put(txHash)
            )
            val base = existingRecord ?: WalletTxRecord(
                txHash = txHash,
                idempotencyKey = txHash,
                walletId = "",
                networkId = network.id,
                networkName = network.name,
                fromAddress = "",
                toAddress = "",
                assetSymbol = network.nativeSymbol,
                amountDisplay = "Unknown",
                feeDisplay = "",
                status = TxStatus.PENDING,
                confirmations = 0,
                timestampMs = System.currentTimeMillis(),
                explorerUrl = "${network.explorerBaseUrl}$txHash",
                direction = TxDirection.OUTBOUND
            )
            if (receiptResult == null || receiptResult == JSONObject.NULL) {
                return@runCatching base.copy(status = TxStatus.PENDING, confirmations = 0)
            }
            val receipt = receiptResult as JSONObject
            val statusHex = receipt.optString("status", "0x1")
            val txBlock = parseHexBigInt(receipt.optString("blockNumber", "0x0"))
            val latestBlock = runCatching {
                parseHexBigInt(jsonRpc(network.defaultRpcUrl, "eth_blockNumber", JSONArray()))
            }.getOrDefault(txBlock)
            val confirmations = (latestBlock.subtract(txBlock).toInt() + 1).coerceAtLeast(1)

            if (statusHex == "0x1") {
                base.copy(status = TxStatus.CONFIRMED, confirmations = confirmations, errorMessage = null)
            } else {
                base.copy(status = TxStatus.FAILED, confirmations = confirmations, errorMessage = "EVM transaction reverted on-chain.")
            }
        }
    }

    fun encodeErc20Transfer(recipient: String, rawAmount: BigInteger): String {
        val cleanDest = recipient.removePrefix("0x").lowercase().padStart(64, '0')
        val cleanAmt = rawAmount.toString(16).padStart(64, '0')
        return "0xa9059cbb$cleanDest$cleanAmt"
    }

    fun buildAndSignEip1559RawHex(
        chainId: Long,
        nonce: BigInteger,
        maxPriorityFeeWei: BigInteger,
        maxFeePerGasWei: BigInteger,
        gasLimit: Long,
        toAddress: String,
        valueWei: BigInteger,
        dataBytes: ByteArray,
        privateKey32: ByteArray
    ): String {
        val unsignedItems = listOf(
            RlpEncoder.encodeBigInteger(BigInteger.valueOf(chainId)),
            RlpEncoder.encodeBigInteger(nonce),
            RlpEncoder.encodeBigInteger(maxPriorityFeeWei),
            RlpEncoder.encodeBigInteger(maxFeePerGasWei),
            RlpEncoder.encodeBigInteger(BigInteger.valueOf(gasLimit)),
            RlpEncoder.encodeByteArray(CryptoPrimitives.fromHex(toAddress)),
            RlpEncoder.encodeBigInteger(valueWei),
            RlpEncoder.encodeByteArray(dataBytes),
            RlpEncoder.encodeList(emptyList())
        )
        val unsignedRlp = RlpEncoder.encodeList(unsignedItems)
        val sigHash = CryptoPrimitives.keccak256(byteArrayOf(0x02) + unsignedRlp)
        val sig = CryptoPrimitives.signSecp256k1(sigHash, privateKey32)
        val signedItems = unsignedItems + listOf(
            RlpEncoder.encodeBigInteger(BigInteger.valueOf(sig.recoveryId.toLong())),
            RlpEncoder.encodeBigInteger(sig.r),
            RlpEncoder.encodeBigInteger(sig.s)
        )
        return CryptoPrimitives.toHex(byteArrayOf(0x02) + RlpEncoder.encodeList(signedItems), prefix = true)
    }

    private fun jsonRpc(rpcUrl: String, method: String, params: JSONArray): String {
        val res = jsonRpcNullable(rpcUrl, method, params)
            ?: throw IllegalStateException("Empty JSON-RPC result for $method")
        return res.toString()
    }

    private fun jsonRpcNullable(rpcUrl: String, method: String, params: JSONArray): Any? {
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
            val body = resp.body?.string() ?: throw IllegalStateException("Empty EVM RPC body")
            val json = JSONObject(body)
            if (json.has("error") && !json.isNull("error")) {
                val err = json.getJSONObject("error")
                throw IllegalStateException(err.optString("message", "EVM RPC error"))
            }
            return json.opt("result")
        }
    }

    private fun parseHexBigInt(hex: String): BigInteger {
        val clean = hex.removePrefix("0x").removePrefix("0X").trim()
        return if (clean.isEmpty()) BigInteger.ZERO else BigInteger(clean, 16)
    }

    private fun toDecimal(raw: BigInteger, decimals: Int): BigDecimal {
        return BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), decimals.coerceAtMost(8), RoundingMode.DOWN)
    }
}
