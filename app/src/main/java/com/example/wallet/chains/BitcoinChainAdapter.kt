package com.example.wallet.chains

import android.net.Uri
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
import com.example.wallet.crypto.Bech32
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

data class BitcoinUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmed: Boolean
)

class BitcoinChainAdapter(
    private val httpClient: OkHttpClient
) : ChainAdapter {

    override fun validateAddress(address: String, network: NetworkConfig): Boolean {
        val trimmed = address.trim()
        val hrp = if (network.isTestnet) "tb" else "bc"
        if (trimmed.lowercase().startsWith("${hrp}1")) {
            return runCatching {
                Bech32.decodeSegwitAddress(hrp, trimmed)
                true
            }.getOrDefault(false)
        }
        // Legacy Base58Check P2PKH / P2SH validation
        return runCatching {
            val decoded = Base58.decodeChecked(trimmed)
            if (decoded.size != 21) return@runCatching false
            val ver = decoded[0].toInt() and 0xFF
            if (network.isTestnet) ver == 0x6F || ver == 0xC4 else ver == 0x00 || ver == 0x05
        }.getOrDefault(false)
    }

    override suspend fun analyzeAddressRisk(
        fromAddress: String,
        toAddress: String,
        network: NetworkConfig
    ): AddressRiskReport {
        val trimmed = toAddress.trim()
        val formatOk = validateAddress(trimmed, network)
        val isSelf = trimmed.equals(fromAddress.trim(), ignoreCase = true)
        val findings = mutableListOf<String>()
        var risk = RiskLevel.LOW

        if (!formatOk) {
            findings.add("Invalid Bitcoin ${if (network.isTestnet) "testnet" else "mainnet"} address or checksum.")
            risk = RiskLevel.CRITICAL
        } else {
            val expectedHrp = if (network.isTestnet) "tb1" else "bc1"
            if (!trimmed.lowercase().startsWith(expectedHrp)) {
                findings.add("Legacy Base58 Bitcoin address detected (higher fee size than Native SegWit $expectedHrp).")
            }
            if (isSelf) {
                findings.add("Recipient equals your own Bitcoin address (consolidation/self-transfer).")
                risk = RiskLevel.MEDIUM
            } else {
                findings.add("Valid Bitcoin UTXO address checksum verified for ${network.name}.")
            }
        }
        return AddressRiskReport(
            address = trimmed,
            networkId = network.id,
            isValidFormat = formatOk,
            checksumValid = formatOk,
            isSelfTransfer = isSelf,
            isContractAddress = false,
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
            require(validateAddress(address, network)) { "Invalid Bitcoin address '$address'." }
            val body = httpGet("${network.defaultRpcUrl}/address/${Uri.encode(address)}")
            val obj = JSONObject(body)
            val chainStats = obj.getJSONObject("chain_stats")
            val mempoolStats = obj.getJSONObject("mempool_stats")
            val confirmedSats = chainStats.getLong("funded_txo_sum") - chainStats.getLong("spent_txo_sum")
            val mempoolDelta = mempoolStats.getLong("funded_txo_sum") - mempoolStats.getLong("spent_txo_sum")
            val totalSats = (confirmedSats + mempoolDelta).coerceAtLeast(0L)
            val btcAmount = BigDecimal.valueOf(totalSats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.DOWN)

            val nativeToken = tokens.firstOrNull { it.isNative }
                ?: TokenDefinition(
                    id = "${network.id}_native",
                    networkId = network.id,
                    symbol = network.nativeSymbol,
                    name = network.name,
                    decimals = 8,
                    contractOrMintAddress = "",
                    coingeckoId = "bitcoin",
                    isNative = true
                )

            listOf(
                AssetBalance(
                    token = nativeToken,
                    rawAmount = BigInteger.valueOf(totalSats),
                    formattedAmount = btcAmount,
                    displayBalance = "${btcAmount.stripTrailingZeros().toPlainString()} ${nativeToken.symbol}",
                    priceUsd = null,
                    valueUsd = null,
                    change24hPct = null
                )
            )
        }
    }

    override suspend fun fetchTransactionHistory(
        address: String,
        network: NetworkConfig
    ): Result<List<WalletTxRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("${network.defaultRpcUrl}/address/${Uri.encode(address)}/txs")
            val arr = JSONArray(body)
            (0 until minOf(arr.length(), 15)).map { idx ->
                val tx = arr.getJSONObject(idx)
                val txid = tx.getString("txid")
                val statusObj = tx.optJSONObject("status")
                val confirmed = statusObj?.optBoolean("confirmed", false) == true
                val blockTime = statusObj?.optLong("block_time", 0L) ?: 0L
                val feeSats = tx.optLong("fee", 0L)

                var receivedSats = 0L
                val vouts = tx.optJSONArray("vout") ?: JSONArray()
                for (j in 0 until vouts.length()) {
                    val v = vouts.getJSONObject(j)
                    if (v.optString("scriptpubkey_address") == address) {
                        receivedSats += v.optLong("value", 0L)
                    }
                }
                val vins = tx.optJSONArray("vin") ?: JSONArray()
                var isOutbound = false
                for (j in 0 until vins.length()) {
                    val prev = vins.getJSONObject(j).optJSONObject("prevout")
                    if (prev?.optString("scriptpubkey_address") == address) {
                        isOutbound = true
                    }
                }
                val btcDisp = BigDecimal.valueOf(receivedSats)
                    .divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString()

                WalletTxRecord(
                    txHash = txid,
                    idempotencyKey = txid,
                    walletId = "",
                    networkId = network.id,
                    networkName = network.name,
                    fromAddress = if (isOutbound) address else "External UTXO",
                    toAddress = if (isOutbound) "Recipient" else address,
                    assetSymbol = network.nativeSymbol,
                    amountDisplay = "$btcDisp ${network.nativeSymbol}",
                    feeDisplay = "$feeSats sats",
                    status = if (confirmed) TxStatus.CONFIRMED else TxStatus.PENDING,
                    confirmations = if (confirmed) 1 else 0,
                    timestampMs = if (blockTime > 0) blockTime * 1000L else System.currentTimeMillis(),
                    explorerUrl = "${network.explorerBaseUrl}$txid",
                    direction = if (isOutbound) TxDirection.OUTBOUND else TxDirection.INBOUND
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
            val utxos = fetchUtxos(network, fromAddress)
            val targetSats = amount.multiply(BigDecimal.valueOf(100_000_000L)).toLong()
            val feeJson = JSONObject(httpGet("${network.defaultRpcUrl}/v1/fees/recommended"))
            val slowRate = feeJson.optLong("hourFee", 2L).coerceAtLeast(1L)
            val stdRate = feeJson.optLong("halfHourFee", 5L).coerceAtLeast(2L)
            val fastRate = feeJson.optLong("fastestFee", 10L).coerceAtLeast(3L)

            val selectedCount = estimateUtxoCountForAmount(utxos, targetSats, stdRate)
            // Native SegWit P2WPKH vbytes: 10.5 overhead + 68 per input + 31 * 2 outputs
            val vbytes = 11 + (selectedCount * 68) + (31 * 2)
            val stdSats = vbytes * stdRate
            val slowSats = vbytes * slowRate
            val fastSats = vbytes * fastRate

            val stdBtc = BigDecimal.valueOf(stdSats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.UP)
            val slowBtc = BigDecimal.valueOf(slowSats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.UP)
            val fastBtc = BigDecimal.valueOf(fastSats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.UP)

            FeeEstimate(
                networkId = network.id,
                nativeSymbol = network.nativeSymbol,
                slowLabel = "${slowBtc.stripTrailingZeros().toPlainString()} ${network.nativeSymbol} ($slowRate sat/vB)",
                standardLabel = "${stdBtc.stripTrailingZeros().toPlainString()} ${network.nativeSymbol} ($stdRate sat/vB)",
                fastLabel = "${fastBtc.stripTrailingZeros().toPlainString()} ${network.nativeSymbol} ($fastRate sat/vB)",
                estimatedFeeDecimal = stdBtc,
                estimatedFeeDisplay = "${stdBtc.stripTrailingZeros().toPlainString()} ${network.nativeSymbol} ($stdRate sat/vB)",
                estimatedFeeUsd = null,
                satsPerVbyte = stdRate,
                estimatedVbytes = vbytes
            )
        }
    }

    override suspend fun simulateTransaction(prepared: PreparedChainTransaction): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val utxos = fetchUtxos(prepared.network, prepared.fromAddress)
                val targetSats = prepared.amountDecimal.multiply(BigDecimal.valueOf(100_000_000L)).toLong()
                require(targetSats >= DUST_LIMIT_SATS) {
                    "Bitcoin output ($targetSats sats) is below dust limit ($DUST_LIMIT_SATS sats)."
                }
                val rate = prepared.feeEstimate.satsPerVbyte.coerceAtLeast(1L)
                val (selected, feeSats) = selectUtxos(utxos, targetSats, rate)
                val totalIn = selected.sumOf { it.valueSats }
                val change = totalIn - targetSats - feeSats
                "Bitcoin UTXO simulation ready: ${selected.size} input(s) totaling $totalIn sats, fee $feeSats sats, change ${if (change >= DUST_LIMIT_SATS) change else 0L} sats."
            }
        }

    override suspend fun signAndBroadcast(
        prepared: PreparedChainTransaction,
        seed: ByteArray
    ): Result<WalletTxRecord> = withContext(Dispatchers.IO) {
        val coinType = if (prepared.network.isTestnet) 1 else 0
        val path = "m/84'/$coinType'/0'/0/${prepared.accountIndex}"
        val derived = HdKeyDerivation.deriveBip32Secp256k1(seed, path)
        try {
            runCatching {
                val utxos = fetchUtxos(prepared.network, prepared.fromAddress)
                val targetSats = prepared.amountDecimal.multiply(BigDecimal.valueOf(100_000_000L)).toLong()
                val rate = prepared.feeEstimate.satsPerVbyte.coerceAtLeast(1L)
                val (selected, feeSats) = selectUtxos(utxos, targetSats, rate)

                val rawHex = buildAndSignSegwitTxHex(
                    utxos = selected,
                    fromAddress = prepared.fromAddress,
                    toAddress = prepared.toAddress,
                    sendSats = targetSats,
                    feeSats = feeSats,
                    privateKey32 = derived.keyBytes,
                    isTestnet = prepared.network.isTestnet
                )

                val req = Request.Builder()
                    .url("${prepared.network.defaultRpcUrl}/tx")
                    .post(rawHex.toRequestBody("text/plain".toMediaType()))
                    .build()
                val txid = httpClient.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty().trim()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Bitcoin broadcast failed (HTTP ${resp.code}): $respText")
                    }
                    respText
                }

                val feeBtc = BigDecimal.valueOf(feeSats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.UP)
                WalletTxRecord(
                    txHash = txid,
                    idempotencyKey = prepared.idempotencyKey,
                    walletId = prepared.walletId,
                    networkId = prepared.network.id,
                    networkName = prepared.network.name,
                    fromAddress = prepared.fromAddress,
                    toAddress = prepared.toAddress,
                    assetSymbol = prepared.network.nativeSymbol,
                    amountDisplay = "${prepared.amountDecimal.stripTrailingZeros().toPlainString()} ${prepared.network.nativeSymbol}",
                    feeDisplay = "${feeBtc.stripTrailingZeros().toPlainString()} ${prepared.network.nativeSymbol} ($feeSats sats)",
                    status = TxStatus.PENDING,
                    confirmations = 0,
                    timestampMs = System.currentTimeMillis(),
                    explorerUrl = "${prepared.network.explorerBaseUrl}$txid",
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
            val statusJson = JSONObject(httpGet("${network.defaultRpcUrl}/tx/${Uri.encode(txHash)}/status"))
            val confirmed = statusJson.optBoolean("confirmed", false)
            val base = existingRecord ?: WalletTxRecord(
                txHash = txHash,
                idempotencyKey = txHash,
                walletId = "",
                networkId = network.id,
                networkName = network.name,
                fromAddress = "",
                toAddress = "",
                assetSymbol = network.nativeSymbol,
                amountDisplay = "Bitcoin Transfer",
                feeDisplay = "",
                status = TxStatus.PENDING,
                confirmations = 0,
                timestampMs = System.currentTimeMillis(),
                explorerUrl = "${network.explorerBaseUrl}$txHash",
                direction = TxDirection.OUTBOUND
            )
            if (confirmed) {
                base.copy(status = TxStatus.CONFIRMED, confirmations = 1, errorMessage = null)
            } else {
                base.copy(status = TxStatus.PENDING, confirmations = 0)
            }
        }
    }

    /**
     * Constructs and signs a BIP-143 SegWit v0 (P2WPKH) raw transaction hex.
     */
    fun buildAndSignSegwitTxHex(
        utxos: List<BitcoinUtxo>,
        fromAddress: String,
        toAddress: String,
        sendSats: Long,
        feeSats: Long,
        privateKey32: ByteArray,
        isTestnet: Boolean
    ): String {
        val totalIn = utxos.sumOf { it.valueSats }
        val changeSats = totalIn - sendSats - feeSats
        require(changeSats >= 0) { "Insufficient UTXO total for amount + fee." }

        val compressedPub = CryptoPrimitives.deriveSecp256k1PublicKey(privateKey32, compressed = true)
        val senderHash160 = CryptoPrimitives.hash160(compressedPub)
        val expectedHrp = if (isTestnet) "tb" else "bc"
        val fromDecoded = Bech32.decodeSegwitAddress(expectedHrp, fromAddress)
        require(fromDecoded.program.contentEquals(senderHash160)) {
            "Derived key does not match sender SegWit address."
        }

        // ScriptCode for BIP-143 P2WPKH: 0x19 76 a9 14 <20-byte hash160> 88 ac
        val scriptCode = byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) +
            senderHash160 +
            byteArrayOf(0x88.toByte(), 0xac.toByte())

        val outputs = mutableListOf<Pair<Long, ByteArray>>()
        outputs.add(sendSats to scriptPubKeyForAddress(toAddress, isTestnet))
        if (changeSats >= DUST_LIMIT_SATS) {
            outputs.add(changeSats to scriptPubKeyForAddress(fromAddress, isTestnet))
        }

        // BIP-143 precomputed hashes
        val prevoutsStream = ByteArrayOutputStream()
        val sequenceStream = ByteArrayOutputStream()
        for (u in utxos) {
            prevoutsStream.write(CryptoPrimitives.fromHex(u.txid).reversedArray())
            prevoutsStream.write(int32LE(u.vout))
            sequenceStream.write(int32LE(0xfffffffd.toInt())) // RBF enabled
        }
        val hashPrevouts = CryptoPrimitives.doubleSha256(prevoutsStream.toByteArray())
        val hashSequence = CryptoPrimitives.doubleSha256(sequenceStream.toByteArray())

        val outputsStream = ByteArrayOutputStream()
        for ((amount, spk) in outputs) {
            outputsStream.write(int64LE(amount))
            outputsStream.write(varInt(spk.size))
            outputsStream.write(spk)
        }
        val hashOutputs = CryptoPrimitives.doubleSha256(outputsStream.toByteArray())

        val witnesses = mutableListOf<List<ByteArray>>()
        for (u in utxos) {
            val preimage = ByteArrayOutputStream().apply {
                write(int32LE(2)) // nVersion = 2
                write(hashPrevouts)
                write(hashSequence)
                write(CryptoPrimitives.fromHex(u.txid).reversedArray())
                write(int32LE(u.vout))
                write(scriptCode)
                write(int64LE(u.valueSats))
                write(int32LE(0xfffffffd.toInt())) // nSequence
                write(hashOutputs)
                write(int32LE(0)) // nLockTime
                write(int32LE(1)) // SIGHASH_ALL = 0x01
            }.toByteArray()

            val sighash = CryptoPrimitives.doubleSha256(preimage)
            val sig = CryptoPrimitives.signSecp256k1(sighash, privateKey32)
            val derWithHashType = CryptoPrimitives.encodeEcdsaDer(sig.r, sig.s) + byteArrayOf(0x01)
            witnesses.add(listOf(derWithHashType, compressedPub))
        }

        val finalTx = ByteArrayOutputStream().apply {
            write(int32LE(2)) // nVersion
            write(0x00) // SegWit marker
            write(0x01) // SegWit flag
            write(varInt(utxos.size))
            for (u in utxos) {
                write(CryptoPrimitives.fromHex(u.txid).reversedArray())
                write(int32LE(u.vout))
                write(0x00) // empty scriptSig for native SegWit P2WPKH
                write(int32LE(0xfffffffd.toInt()))
            }
            write(varInt(outputs.size))
            for ((amount, spk) in outputs) {
                write(int64LE(amount))
                write(varInt(spk.size))
                write(spk)
            }
            for (witStack in witnesses) {
                write(varInt(witStack.size))
                for (item in witStack) {
                    write(varInt(item.size))
                    write(item)
                }
            }
            write(int32LE(0)) // nLockTime
        }.toByteArray()

        return CryptoPrimitives.toHex(finalTx, prefix = false)
    }

    fun scriptPubKeyForAddress(address: String, isTestnet: Boolean): ByteArray {
        val trimmed = address.trim()
        val hrp = if (isTestnet) "tb" else "bc"
        if (trimmed.lowercase().startsWith("${hrp}1")) {
            val decoded = Bech32.decodeSegwitAddress(hrp, trimmed)
            val opVer = if (decoded.witnessVersion == 0) 0x00 else (0x50 + decoded.witnessVersion)
            return byteArrayOf(opVer.toByte(), decoded.program.size.toByte()) + decoded.program
        }
        val base58Decoded = Base58.decodeChecked(trimmed)
        require(base58Decoded.size == 21) { "Unsupported Base58 Bitcoin address." }
        val version = base58Decoded[0].toInt() and 0xFF
        val hash = base58Decoded.copyOfRange(1, 21)
        return when {
            (!isTestnet && version == 0x00) || (isTestnet && version == 0x6F) -> {
                // P2PKH: OP_DUP OP_HASH160 <20> hash OP_EQUALVERIFY OP_CHECKSIG
                byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
            }
            (!isTestnet && version == 0x05) || (isTestnet && version == 0xC4) -> {
                // P2SH: OP_HASH160 <20> hash OP_EQUAL
                byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            }
            else -> throw IllegalArgumentException("Bitcoin address network mismatch.")
        }
    }

    fun selectUtxos(utxos: List<BitcoinUtxo>, targetSats: Long, satsPerVbyte: Long): Pair<List<BitcoinUtxo>, Long> {
        require(utxos.isNotEmpty()) { "No spendable UTXOs found for this Bitcoin address." }
        val sorted = utxos.sortedByDescending { it.valueSats }
        val chosen = mutableListOf<BitcoinUtxo>()
        var sum = 0L
        for (u in sorted) {
            chosen.add(u)
            sum += u.valueSats
            val vbytes = 11 + (chosen.size * 68) + (31 * 2)
            val fee = (vbytes * satsPerVbyte).coerceAtLeast(110L)
            if (sum >= targetSats + fee) {
                return chosen to fee
            }
        }
        val finalVbytes = 11 + (chosen.size * 68) + (31 * 2)
        val needed = targetSats + (finalVbytes * satsPerVbyte)
        throw IllegalStateException("Insufficient BTC balance ($sum sats available, $needed sats required with fee).")
    }

    private fun estimateUtxoCountForAmount(utxos: List<BitcoinUtxo>, targetSats: Long, rate: Long): Int {
        if (utxos.isEmpty()) return 1
        return runCatching { selectUtxos(utxos, targetSats, rate).first.size }.getOrDefault(utxos.size.coerceAtLeast(1))
    }

    private fun fetchUtxos(network: NetworkConfig, address: String): List<BitcoinUtxo> {
        val body = httpGet("${network.defaultRpcUrl}/address/${Uri.encode(address)}/utxo")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val status = o.optJSONObject("status")
            BitcoinUtxo(
                txid = o.getString("txid"),
                vout = o.getInt("vout"),
                valueSats = o.getLong("value"),
                confirmed = status?.optBoolean("confirmed", false) == true
            )
        }
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Bitcoin API HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty Bitcoin API response")
        }
    }

    private fun int32LE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun int64LE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun varInt(n: Int): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), (n and 0xff).toByte(), ((n ushr 8) and 0xff).toByte())
        else -> byteArrayOf(0xfe.toByte()) + int32LE(n)
    }

    private companion object {
        const val DUST_LIMIT_SATS = 546L
    }
}
