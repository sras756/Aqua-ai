package com.example.wallet.chains

import android.util.Base64
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
import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.HdKeyDerivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SolanaChainAdapter(
    private val httpClient: OkHttpClient
) : ChainAdapter {

    override fun validateAddress(address: String, network: NetworkConfig): Boolean {
        val trimmed = address.trim()
        if (trimmed.length !in 32..44) return false
        return runCatching {
            val decoded = Base58.decode(trimmed)
            decoded.size == 32
        }.getOrDefault(false)
    }

    override suspend fun analyzeAddressRisk(
        fromAddress: String,
        toAddress: String,
        network: NetworkConfig
    ): AddressRiskReport = withContext(Dispatchers.IO) {
        val trimmed = toAddress.trim()
        val formatOk = validateAddress(trimmed, network)
        val isSelf = trimmed == fromAddress.trim()
        val findings = mutableListOf<String>()
        var risk = RiskLevel.LOW
        var isExecutableAccount = false

        if (!formatOk) {
            findings.add("Invalid Solana Base58 32-byte public key format.")
            risk = RiskLevel.CRITICAL
        } else {
            if (isSelf) {
                findings.add("Destination equals your own Solana address (self-transfer).")
                risk = RiskLevel.MEDIUM
            }
            val acctInfo = runCatching {
                rpcCall(
                    network.defaultRpcUrl,
                    "getAccountInfo",
                    JSONArray().put(trimmed).put(JSONObject().put("encoding", "base64"))
                )
            }.getOrNull()
            val valueObj = acctInfo?.optJSONObject("value")
            if (valueObj != null && valueObj.optBoolean("executable", false)) {
                isExecutableAccount = true
                findings.add("Recipient is an executable Solana on-chain program, not a normal user wallet.")
                risk = RiskLevel.HIGH
            } else if (valueObj == null) {
                findings.add("Recipient Solana account is currently unfunded (0 lamports). Verify it is not a typo.")
                if (risk == RiskLevel.LOW) risk = RiskLevel.MEDIUM
            } else {
                findings.add("Valid funded Solana Base58 account verified on ${network.name}.")
            }
        }
        AddressRiskReport(
            address = trimmed,
            networkId = network.id,
            isValidFormat = formatOk,
            checksumValid = formatOk,
            isSelfTransfer = isSelf,
            isContractAddress = isExecutableAccount,
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
            require(validateAddress(address, network)) { "Invalid Solana address '$address'." }
            val out = mutableListOf<AssetBalance>()

            val balRes = rpcCall(network.defaultRpcUrl, "getBalance", JSONArray().put(address))
            val lamports = balRes.getLong("value")
            val nativeToken = tokens.firstOrNull { it.isNative }
                ?: TokenDefinition("${network.id}_native", network.id, "SOL", "Solana", 9, "", "solana", isNative = true)
            val solAmount = BigDecimal.valueOf(lamports).divide(BigDecimal.TEN.pow(9), 9, RoundingMode.DOWN)
            out.add(
                AssetBalance(
                    token = nativeToken,
                    rawAmount = BigInteger.valueOf(lamports),
                    formattedAmount = solAmount,
                    displayBalance = "${solAmount.stripTrailingZeros().toPlainString()} SOL",
                    priceUsd = null,
                    valueUsd = null,
                    change24hPct = null
                )
            )

            // Live SPL Token accounts by owner
            val splByMint = mutableMapOf<String, Pair<BigInteger, Int>>()
            runCatching {
                val filter = JSONObject().put("programId", SPL_TOKEN_PROGRAM_ID)
                val cfg = JSONObject().put("encoding", "jsonParsed")
                val res = rpcCall(network.defaultRpcUrl, "getTokenAccountsByOwner", JSONArray().put(address).put(filter).put(cfg))
                val arr = res.optJSONArray("value") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val info = arr.getJSONObject(i)
                        .getJSONObject("account")
                        .getJSONObject("data")
                        .getJSONObject("parsed")
                        .getJSONObject("info")
                    val mint = info.getString("mint")
                    val tokenAmt = info.getJSONObject("tokenAmount")
                    val raw = tokenAmt.getString("amount").toBigIntegerOrNull() ?: BigInteger.ZERO
                    val dec = tokenAmt.optInt("decimals", 6)
                    val prev = splByMint[mint]?.first ?: BigInteger.ZERO
                    splByMint[mint] = (prev + raw) to dec
                }
            }

            for (token in tokens.filter { !it.isNative && it.enabled && it.contractOrMintAddress.isNotBlank() }) {
                val found = splByMint[token.contractOrMintAddress]
                val raw = found?.first ?: BigInteger.ZERO
                val dec = found?.second ?: token.decimals
                val decAmt = BigDecimal(raw).divide(BigDecimal.TEN.pow(dec), dec.coerceAtMost(6), RoundingMode.DOWN)
                out.add(
                    AssetBalance(
                        token = token,
                        rawAmount = raw,
                        formattedAmount = decAmt,
                        displayBalance = "${decAmt.stripTrailingZeros().toPlainString()} ${token.symbol}",
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
        runCatching {
            val sigRes = rpcArrayCall(
                network.defaultRpcUrl,
                "getSignaturesForAddress",
                JSONArray().put(address).put(JSONObject().put("limit", 15))
            )
            (0 until sigRes.length()).map { i ->
                val item = sigRes.getJSONObject(i)
                val sig = item.getString("signature")
                val hasErr = !item.isNull("err")
                val confStatus = item.optString("confirmationStatus", "")
                val status = when {
                    hasErr -> TxStatus.FAILED
                    confStatus == "finalized" || confStatus == "confirmed" -> TxStatus.CONFIRMED
                    else -> TxStatus.PENDING
                }
                val blockTimeMs = item.optLong("blockTime", 0L).let {
                    if (it > 0L) it * 1000L else System.currentTimeMillis()
                }
                WalletTxRecord(
                    txHash = sig,
                    idempotencyKey = sig,
                    walletId = "",
                    networkId = network.id,
                    networkName = network.name,
                    fromAddress = address,
                    toAddress = "",
                    assetSymbol = "SOL",
                    amountDisplay = item.optString("memo", "On-chain tx").ifBlank { "Solana Transaction" },
                    feeDisplay = "~0.000005 SOL",
                    status = status,
                    confirmations = if (status == TxStatus.CONFIRMED) 32 else 0,
                    timestampMs = blockTimeMs,
                    explorerUrl = if (network.isTestnet) "${network.explorerBaseUrl}$sig?cluster=devnet" else "${network.explorerBaseUrl}$sig",
                    direction = TxDirection.OUTBOUND,
                    errorMessage = if (hasErr) "On-chain instruction failed" else null
                )
            }
        }
    }

    override suspend fun estimateFee(
        fromAddress: String,
        toAddress: String,
        token: TokenDefinition,
        amount: BigDecimal,
        network: NetworkConfig
    ): Result<FeeEstimate> = withContext(Dispatchers.IO) {
        runCatching {
            val latestBlockhash = fetchLatestBlockhash(network.defaultRpcUrl)
            val msgBytes = if (token.isNative) {
                buildNativeTransferMessage(
                    fromAddress = fromAddress.trim(),
                    toAddress = toAddress.trim(),
                    lamports = amount.multiply(BigDecimal.TEN.pow(9)).toLong().coerceAtLeast(1L),
                    recentBlockhash = latestBlockhash
                )
            } else {
                // Estimate using sample token transfer message when SPL source account exists, fallback to base 5000 lamports
                ByteArray(0)
            }

            val lamportsFee = if (msgBytes.isNotEmpty()) {
                runCatching {
                    val msgB64 = Base64.encodeToString(msgBytes, Base64.NO_WRAP)
                    val res = rpcCall(
                        network.defaultRpcUrl,
                        "getFeeForMessage",
                        JSONArray().put(msgB64).put(JSONObject().put("commitment", "confirmed"))
                    )
                    res.optLong("value", 5000L).coerceAtLeast(5000L)
                }.getOrDefault(5000L)
            } else {
                5000L
            }

            val feeSol = BigDecimal.valueOf(lamportsFee).divide(BigDecimal.TEN.pow(9), 9, RoundingMode.UP)
            val fastSol = BigDecimal.valueOf(lamportsFee * 2L).divide(BigDecimal.TEN.pow(9), 9, RoundingMode.UP)
            val display = "${feeSol.stripTrailingZeros().toPlainString()} SOL"

            FeeEstimate(
                networkId = network.id,
                nativeSymbol = "SOL",
                slowLabel = display,
                standardLabel = display,
                fastLabel = "${fastSol.stripTrailingZeros().toPlainString()} SOL",
                estimatedFeeDecimal = feeSol,
                estimatedFeeDisplay = display,
                estimatedFeeUsd = null,
                lamportsFee = lamportsFee
            )
        }
    }

    override suspend fun simulateTransaction(prepared: PreparedChainTransaction): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val blockhash = fetchLatestBlockhash(prepared.network.defaultRpcUrl)
                val msg = if (prepared.token.isNative) {
                    val lamports = prepared.amountDecimal.multiply(BigDecimal.TEN.pow(9)).toLong()
                    buildNativeTransferMessage(
                        fromAddress = prepared.fromAddress,
                        toAddress = prepared.toAddress,
                        lamports = lamports,
                        recentBlockhash = blockhash
                    )
                } else {
                    val rawUnits = prepared.amountDecimal
                        .multiply(BigDecimal.TEN.pow(prepared.token.decimals))
                        .toLong()
                    val sourceTokenAcct = findFirstTokenAccount(
                        prepared.network.defaultRpcUrl,
                        prepared.fromAddress,
                        prepared.token.contractOrMintAddress
                    ) ?: throw IllegalStateException("Sender holds no token account for ${prepared.token.symbol}.")
                    val destTokenAcct = findFirstTokenAccount(
                        prepared.network.defaultRpcUrl,
                        prepared.toAddress,
                        prepared.token.contractOrMintAddress
                    ) ?: throw IllegalStateException(
                        "Recipient does not yet have an initialized ${prepared.token.symbol} SPL token account."
                    )
                    buildSplTransferCheckedMessage(
                        ownerAddress = prepared.fromAddress,
                        sourceTokenAccount = sourceTokenAcct,
                        destTokenAccount = destTokenAcct,
                        mintAddress = prepared.token.contractOrMintAddress,
                        amountRaw = rawUnits,
                        decimals = prepared.token.decimals,
                        recentBlockhash = blockhash
                    )
                }
                // Unsigned wire tx: [compact-u16 sigCount=1, 64 zero signature bytes, message]
                val wireTx = byteArrayOf(0x01) + ByteArray(64) + msg
                val wireB64 = Base64.encodeToString(wireTx, Base64.NO_WRAP)
                val cfg = JSONObject().apply {
                    put("encoding", "base64")
                    put("sigVerify", false)
                }
                val simRes = rpcCall(
                    prepared.network.defaultRpcUrl,
                    "simulateTransaction",
                    JSONArray().put(wireB64).put(cfg)
                )
                val value = simRes.getJSONObject("value")
                if (!value.isNull("err")) {
                    throw IllegalStateException("Solana simulation failed: ${value.opt("err")}")
                }
                val units = value.optLong("unitsConsumed", 150L)
                "Solana pre-flight simulation succeeded (compute units: $units, fee: ${prepared.feeEstimate.lamportsFee} lamports)."
            }
        }

    override suspend fun signAndBroadcast(
        prepared: PreparedChainTransaction,
        seed: ByteArray
    ): Result<WalletTxRecord> = withContext(Dispatchers.IO) {
        val path = "m/44'/501'/${prepared.accountIndex}'/0'"
        val derived = HdKeyDerivation.deriveSlip10Ed25519(seed, path)
        try {
            runCatching {
                val blockhash = fetchLatestBlockhash(prepared.network.defaultRpcUrl)
                val message = if (prepared.token.isNative) {
                    val lamports = prepared.amountDecimal.multiply(BigDecimal.TEN.pow(9)).toLong()
                    buildNativeTransferMessage(
                        fromAddress = prepared.fromAddress,
                        toAddress = prepared.toAddress,
                        lamports = lamports,
                        recentBlockhash = blockhash
                    )
                } else {
                    val rawUnits = prepared.amountDecimal
                        .multiply(BigDecimal.TEN.pow(prepared.token.decimals))
                        .toLong()
                    val sourceTokenAcct = findFirstTokenAccount(
                        prepared.network.defaultRpcUrl,
                        prepared.fromAddress,
                        prepared.token.contractOrMintAddress
                    ) ?: throw IllegalStateException("Sender holds no token account for ${prepared.token.symbol}.")
                    val destTokenAcct = findFirstTokenAccount(
                        prepared.network.defaultRpcUrl,
                        prepared.toAddress,
                        prepared.token.contractOrMintAddress
                    ) ?: throw IllegalStateException(
                        "Recipient does not yet have an initialized ${prepared.token.symbol} SPL token account."
                    )
                    buildSplTransferCheckedMessage(
                        ownerAddress = prepared.fromAddress,
                        sourceTokenAccount = sourceTokenAcct,
                        destTokenAccount = destTokenAcct,
                        mintAddress = prepared.token.contractOrMintAddress,
                        amountRaw = rawUnits,
                        decimals = prepared.token.decimals,
                        recentBlockhash = blockhash
                    )
                }

                val signature = CryptoPrimitives.signEd25519(message, derived.keyBytes)
                require(signature.size == 64) { "Invalid Ed25519 signature size." }
                val signedWireTx = byteArrayOf(0x01) + signature + message
                val wireB64 = Base64.encodeToString(signedWireTx, Base64.NO_WRAP)

                val txSig = rpcStringCall(
                    prepared.network.defaultRpcUrl,
                    "sendTransaction",
                    JSONArray().put(wireB64).put(JSONObject().put("encoding", "base64"))
                )

                WalletTxRecord(
                    txHash = txSig,
                    idempotencyKey = prepared.idempotencyKey,
                    walletId = prepared.walletId,
                    networkId = prepared.network.id,
                    networkName = prepared.network.name,
                    fromAddress = prepared.fromAddress,
                    toAddress = prepared.toAddress,
                    assetSymbol = prepared.token.symbol,
                    amountDisplay = "${prepared.amountDecimal.stripTrailingZeros().toPlainString()} ${prepared.token.symbol}",
                    feeDisplay = prepared.feeEstimate.estimatedFeeDisplay,
                    status = TxStatus.PENDING,
                    confirmations = 0,
                    timestampMs = System.currentTimeMillis(),
                    explorerUrl = if (prepared.network.isTestnet) {
                        "${prepared.network.explorerBaseUrl}$txSig?cluster=devnet"
                    } else {
                        "${prepared.network.explorerBaseUrl}$txSig"
                    },
                    direction = if (prepared.fromAddress == prepared.toAddress) TxDirection.SELF else TxDirection.OUTBOUND
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
            val res = rpcCall(
                network.defaultRpcUrl,
                "getSignatureStatuses",
                JSONArray().put(JSONArray().put(txHash)).put(JSONObject().put("searchTransactionHistory", true))
            )
            val statusObj = res.optJSONArray("value")?.optJSONObject(0)
            val base = existingRecord ?: WalletTxRecord(
                txHash = txHash,
                idempotencyKey = txHash,
                walletId = "",
                networkId = network.id,
                networkName = network.name,
                fromAddress = "",
                toAddress = "",
                assetSymbol = "SOL",
                amountDisplay = "Solana Transfer",
                feeDisplay = "0.000005 SOL",
                status = TxStatus.PENDING,
                confirmations = 0,
                timestampMs = System.currentTimeMillis(),
                explorerUrl = "${network.explorerBaseUrl}$txHash",
                direction = TxDirection.OUTBOUND
            )
            if (statusObj == null) {
                return@runCatching base.copy(status = TxStatus.PENDING, confirmations = 0)
            }
            if (!statusObj.isNull("err")) {
                return@runCatching base.copy(
                    status = TxStatus.FAILED,
                    errorMessage = "Solana transaction failed on-chain: ${statusObj.opt("err")}"
                )
            }
            val confStatus = statusObj.optString("confirmationStatus", "")
            val confs = if (confStatus == "finalized") 32 else statusObj.optInt("confirmations", 1)
            if (confStatus == "confirmed" || confStatus == "finalized") {
                base.copy(status = TxStatus.CONFIRMED, confirmations = confs, errorMessage = null)
            } else {
                base.copy(status = TxStatus.PENDING, confirmations = confs)
            }
        }
    }

    /**
     * Solana Legacy Message for SystemProgram::Transfer
     * Account order: [from (signer, writable), to (writable), SystemProgram 1111...1111 (readonly)]
     */
    fun buildNativeTransferMessage(
        fromAddress: String,
        toAddress: String,
        lamports: Long,
        recentBlockhash: String
    ): ByteArray {
        val fromBytes = Base58.decode(fromAddress)
        val toBytes = Base58.decode(toAddress)
        val sysProgram = ByteArray(32) // Base58 "11111111111111111111111111111111" = 32 zero bytes
        val blockhashBytes = Base58.decode(recentBlockhash)
        require(fromBytes.size == 32 && toBytes.size == 32 && blockhashBytes.size == 32) {
            "Solana keys and blockhash must be 32 bytes."
        }

        val bos = ByteArrayOutputStream()
        // Header: 1 required signature, 0 readonly signed, 1 readonly unsigned (System Program)
        bos.write(1)
        bos.write(0)
        bos.write(1)

        // Account keys count = 3
        bos.write(3)
        bos.write(fromBytes)
        bos.write(toBytes)
        bos.write(sysProgram)

        // Recent blockhash
        bos.write(blockhashBytes)

        // 1 compiled instruction
        bos.write(1)
        bos.write(2) // programIdIndex = 2 (System Program)
        bos.write(2) // account index count = 2
        bos.write(0) // from
        bos.write(1) // to

        // Instruction data: uint32 LE instruction=2 (Transfer) + uint64 LE lamports = 12 bytes
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2)
            .putLong(lamports)
            .array()
        bos.write(data.size)
        bos.write(data)
        return bos.toByteArray()
    }

    private fun buildSplTransferCheckedMessage(
        ownerAddress: String,
        sourceTokenAccount: String,
        destTokenAccount: String,
        mintAddress: String,
        amountRaw: Long,
        decimals: Int,
        recentBlockhash: String
    ): ByteArray {
        val ownerBytes = Base58.decode(ownerAddress)
        val srcBytes = Base58.decode(sourceTokenAccount)
        val dstBytes = Base58.decode(destTokenAccount)
        val mintBytes = Base58.decode(mintAddress)
        val splProgram = Base58.decode(SPL_TOKEN_PROGRAM_ID)
        val blockhashBytes = Base58.decode(recentBlockhash)

        val bos = ByteArrayOutputStream()
        // Header: 1 signer (owner), 0 readonly signed, 2 readonly unsigned (mint, splProgram)
        bos.write(1)
        bos.write(0)
        bos.write(2)

        bos.write(5)
        bos.write(ownerBytes)    // 0: signer readonly/fee payer -> writable for fee
        bos.write(srcBytes)      // 1: writable
        bos.write(dstBytes)      // 2: writable
        bos.write(mintBytes)     // 3: readonly unsigned
        bos.write(splProgram)    // 4: readonly unsigned

        bos.write(blockhashBytes)

        // 1 compiled instruction: TokenProgram::TransferChecked (index 12)
        bos.write(1)
        bos.write(4) // programIdIndex = 4 (SPL Token)
        bos.write(4) // accounts: [source, mint, dest, owner]
        bos.write(1)
        bos.write(3)
        bos.write(2)
        bos.write(0)

        val data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(12.toByte())
            .putLong(amountRaw)
            .put(decimals.toByte())
            .array()
        bos.write(data.size)
        bos.write(data)
        return bos.toByteArray()
    }

    private fun fetchLatestBlockhash(rpcUrl: String): String {
        val res = rpcCall(
            rpcUrl,
            "getLatestBlockhash",
            JSONArray().put(JSONObject().put("commitment", "finalized"))
        )
        return res.getJSONObject("value").getString("blockhash")
    }

    private fun findFirstTokenAccount(rpcUrl: String, owner: String, mint: String): String? {
        val filter = JSONObject().put("mint", mint)
        val cfg = JSONObject().put("encoding", "jsonParsed")
        val res = rpcCall(rpcUrl, "getTokenAccountsByOwner", JSONArray().put(owner).put(filter).put(cfg))
        val arr = res.optJSONArray("value") ?: return null
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).getString("pubkey")
    }

    private fun rpcCall(rpcUrl: String, method: String, params: JSONArray): JSONObject {
        val raw = executeRpc(rpcUrl, method, params)
        return raw.getJSONObject("result")
    }

    private fun rpcArrayCall(rpcUrl: String, method: String, params: JSONArray): JSONArray {
        val raw = executeRpc(rpcUrl, method, params)
        return raw.getJSONArray("result")
    }

    private fun rpcStringCall(rpcUrl: String, method: String, params: JSONArray): String {
        val raw = executeRpc(rpcUrl, method, params)
        return raw.getString("result")
    }

    private fun executeRpc(rpcUrl: String, method: String, params: JSONArray): JSONObject {
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
            if (!resp.isSuccessful) throw IllegalStateException("Solana RPC HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            if (json.has("error") && !json.isNull("error")) {
                val err = json.getJSONObject("error")
                throw IllegalStateException(err.optString("message", "Solana RPC error"))
            }
            return json
        }
    }

    private companion object {
        const val SPL_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    }
}
