package com.example.wallet.core

import com.example.data.local.WalletAddressBookEntity
import com.example.data.local.WalletCacheDao
import com.example.wallet.chains.EvmChainAdapter
import com.example.wallet.chains.EvmToolsClient
import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.HdKeyDerivation
import com.example.wallet.crypto.WalletMessageCrypto
import com.example.wallet.security.WalletSecurityVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Modular self-custodial utility service for:
 * - Multi-chain Address Book persistence & lookup
 * - Live Ethereum Name Service (.eth) resolution
 * - Live EVM block/gas/nonce telemetry & ERC-20 allowance inspection / approve(0) revoke builder
 * - Offline EIP-191 & Solana Ed25519 message signing & signature verification
 */
class WalletUtilityService(
    private val vault: WalletSecurityVault,
    private val dao: WalletCacheDao,
    private val evmAdapter: EvmChainAdapter,
    val evmToolsClient: EvmToolsClient,
    private val marketPriceService: MarketPriceService,
    private val addressValidator: (String, ChainFamily) -> Boolean
) {

    fun observeAddressBook(): Flow<List<AddressBookEntry>> =
        dao.observeAddressBook().map { list ->
            list.map {
                AddressBookEntry(
                    id = it.id,
                    label = it.label,
                    chainFamily = runCatching { ChainFamily.valueOf(it.chainFamily) }.getOrDefault(ChainFamily.EVM),
                    address = it.address,
                    note = it.note,
                    createdAtMs = it.createdAtMs
                )
            }
        }

    suspend fun getAddressBook(): List<AddressBookEntry> =
        dao.getAddressBook().map {
            AddressBookEntry(
                id = it.id,
                label = it.label,
                chainFamily = runCatching { ChainFamily.valueOf(it.chainFamily) }.getOrDefault(ChainFamily.EVM),
                address = it.address,
                note = it.note,
                createdAtMs = it.createdAtMs
            )
        }

    suspend fun saveContact(
        label: String,
        chainFamily: ChainFamily,
        address: String,
        note: String = ""
    ): Result<AddressBookEntry> = runCatching {
        val cleanLabel = label.trim()
        val cleanAddr = address.trim()
        require(cleanLabel.isNotBlank()) { "Contact label is required." }
        require(addressValidator(cleanAddr, chainFamily)) {
            "Invalid ${chainFamily.name} address '$cleanAddr'."
        }
        val normalizedAddr = if (chainFamily == ChainFamily.EVM) {
            CryptoPrimitives.toEip55ChecksumAddress(cleanAddr)
        } else {
            cleanAddr
        }
        val entry = AddressBookEntry(
            id = "ab_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            label = cleanLabel.take(48),
            chainFamily = chainFamily,
            address = normalizedAddr,
            note = note.trim().take(120),
            createdAtMs = System.currentTimeMillis()
        )
        dao.upsertAddressBookEntry(
            WalletAddressBookEntity(
                id = entry.id,
                label = entry.label,
                chainFamily = entry.chainFamily.name,
                address = entry.address,
                note = entry.note,
                createdAtMs = entry.createdAtMs
            )
        )
        entry
    }

    suspend fun deleteContact(entryId: String) {
        dao.deleteAddressBookEntry(entryId)
    }

    suspend fun findContactByAddress(address: String): AddressBookEntry? {
        val clean = address.trim()
        if (clean.isBlank()) return null
        return getAddressBook().firstOrNull { it.address.equals(clean, ignoreCase = true) }
    }

    suspend fun findContactByLabel(labelQuery: String, chainFamily: ChainFamily? = null): AddressBookEntry? {
        val q = labelQuery.trim()
        if (q.isBlank()) return null
        val all = getAddressBook().filter { chainFamily == null || it.chainFamily == chainFamily }
        return all.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: all.firstOrNull { it.label.contains(q, ignoreCase = true) }
    }

    suspend fun resolveEnsName(ensName: String): Result<String> =
        evmToolsClient.resolveEnsName(ensName)

    suspend fun fetchEvmTelemetry(address: String, network: NetworkConfig): Result<EvmChainTelemetry> = runCatching {
        require(network.chainFamily == ChainFamily.EVM) { "${network.name} is not an EVM network." }
        evmToolsClient.fetchLiveChainTelemetry(address, network).getOrThrow()
    }

    suspend fun fetchErc20Allowance(
        ownerAddress: String,
        token: TokenDefinition,
        spenderAddress: String,
        network: NetworkConfig
    ): Result<Erc20AllowanceResult> = runCatching {
        require(network.chainFamily == ChainFamily.EVM) { "ERC-20 allowance inspection requires an EVM network." }
        evmToolsClient.fetchErc20Allowance(ownerAddress, token, spenderAddress, network).getOrThrow()
    }

    suspend fun prepareErc20RevokeApproval(
        walletMeta: WalletMetadata,
        account: WalletAccountInfo,
        network: NetworkConfig,
        token: TokenDefinition,
        spenderAddress: String
    ): Result<PreparedChainTransaction> = runCatching {
        val settings = vault.getSecuritySettings()
        require(!settings.emergencyLocked) {
            "Emergency Wallet Lock is active. Unlock in Security Center first."
        }
        require(!walletMeta.isWatchOnly) {
            "Watch-Only wallets cannot sign approval revocations."
        }
        require(network.chainFamily == ChainFamily.EVM) {
            "ERC-20 approval revocation is supported on EVM networks."
        }
        require(!token.isNative && token.contractOrMintAddress.isNotBlank()) {
            "Select an ERC-20 token contract to revoke."
        }
        val cleanSpender = spenderAddress.trim()
        require(evmAdapter.validateAddress(cleanSpender, network)) {
            "Invalid spender contract address '$cleanSpender'."
        }
        val fromAddress = account.evmAddress
        require(fromAddress.isNotBlank()) { "No EVM address on active account." }

        val nativeToken = TokenDefinition(
            id = "${network.id}_native",
            networkId = network.id,
            symbol = network.nativeSymbol,
            name = network.name,
            decimals = network.nativeDecimals,
            contractOrMintAddress = "",
            coingeckoId = network.coingeckoId,
            isNative = true
        )
        val nativeBal = evmAdapter.fetchBalances(fromAddress, listOf(nativeToken), network)
            .getOrThrow().firstOrNull()?.formattedAmount ?: BigDecimal.ZERO

        val feeBase = evmAdapter.estimateFee(fromAddress, cleanSpender, token, BigDecimal("1"), network).getOrThrow()
        val quotes = if (network.isTestnet) emptyMap() else marketPriceService.fetchPricesUsd(listOf(network.coingeckoId))
        val nativeUsd = quotes[network.coingeckoId]?.priceUsd
        val feeEst = feeBase.copy(
            estimatedFeeUsd = nativeUsd?.let { feeBase.estimatedFeeDecimal.toDouble() * it }
        )
        require(nativeBal >= feeEst.estimatedFeeDecimal) {
            "Insufficient ${network.nativeSymbol} for revoke gas fee (${feeEst.estimatedFeeDisplay})."
        }

        val approveZeroHex = evmToolsClient.encodeErc20Approve(cleanSpender, BigInteger.ZERO)
        val riskReport = evmAdapter.analyzeAddressRisk(fromAddress, cleanSpender, network)
        val keyInput = "revoke|${walletMeta.walletId}|${account.accountIndex}|${network.id}|${token.contractOrMintAddress}|$cleanSpender|${System.currentTimeMillis() / 15_000L}"
        val idempotencyKey = CryptoPrimitives.toHex(CryptoPrimitives.sha256(keyInput.toByteArray())).take(24)

        val draft = PreparedChainTransaction(
            id = "rev_$idempotencyKey",
            idempotencyKey = idempotencyKey,
            walletId = walletMeta.walletId,
            accountIndex = account.accountIndex,
            network = network,
            fromAddress = fromAddress,
            toAddress = cleanSpender,
            token = token,
            amountDecimal = BigDecimal.ZERO,
            feeEstimate = feeEst,
            totalCostDisplay = "0 ${token.symbol} + ${feeEst.estimatedFeeDisplay} (Revoke)",
            nonceOrBlockhash = "",
            utxosPayloadJson = "",
            simulationSummary = "",
            riskReport = riskReport,
            customCallDataHex = approveZeroHex,
            customActionLabel = "Revoke Approval"
        )
        val simSummary = evmAdapter.simulateTransaction(draft).getOrThrow()
        draft.copy(simulationSummary = simSummary)
    }

    fun signMessageLocally(
        walletMeta: WalletMetadata,
        accountIndex: Int,
        chainFamily: ChainFamily,
        message: String
    ): Result<SignedMessageProof> = runCatching {
        require(!walletMeta.isWatchOnly) { "Watch-Only wallets cannot sign messages." }
        require(message.isNotEmpty()) { "Enter a message to sign." }
        require(chainFamily != ChainFamily.BITCOIN) {
            "Off-chain personal_sign supports EVM (EIP-191) and Solana (Ed25519)."
        }

        vault.withSeed(walletMeta.walletId) { seed ->
            when (chainFamily) {
                ChainFamily.EVM -> {
                    val key = HdKeyDerivation.deriveBip32Secp256k1(seed, "m/44'/60'/0'/0/$accountIndex")
                    try {
                        val pub = CryptoPrimitives.deriveSecp256k1PublicKey(key.keyBytes, compressed = false)
                        val addr = CryptoPrimitives.evmAddressFromPublicKey(pub)
                        val sigHex = WalletMessageCrypto.signEip191PersonalMessage(message, key.keyBytes)
                        SignedMessageProof(
                            chainFamily = ChainFamily.EVM,
                            standardName = "EIP-191 personal_sign (secp256k1)",
                            signerAddress = addr,
                            message = message,
                            signature = sigHex
                        )
                    } finally {
                        key.wipe()
                    }
                }
                ChainFamily.SOLANA -> {
                    val key = HdKeyDerivation.deriveSlip10Ed25519(seed, "m/44'/501'/$accountIndex'/0'")
                    try {
                        val pub = CryptoPrimitives.deriveEd25519PublicKey(key.keyBytes)
                        val addr = Base58.encode(pub)
                        val sigBytes = CryptoPrimitives.signEd25519(
                            message.toByteArray(StandardCharsets.UTF_8),
                            key.keyBytes
                        )
                        SignedMessageProof(
                            chainFamily = ChainFamily.SOLANA,
                            standardName = "Solana Off-Chain UTF-8 (Ed25519 Base58)",
                            signerAddress = addr,
                            message = message,
                            signature = Base58.encode(sigBytes)
                        )
                    } finally {
                        key.wipe()
                    }
                }
                ChainFamily.BITCOIN -> error("Unsupported")
            }
        }.getOrThrow()
    }

    fun verifySignedMessage(
        chainFamily: ChainFamily,
        signerAddress: String,
        message: String,
        signature: String
    ): Result<Boolean> = runCatching {
        require(signerAddress.isNotBlank() && signature.isNotBlank()) {
            "Signer address and signature are required."
        }
        when (chainFamily) {
            ChainFamily.EVM -> WalletMessageCrypto.verifyEip191PersonalMessage(
                message = message,
                signatureHex = signature.trim(),
                expectedEvmAddress = signerAddress.trim()
            )
            ChainFamily.SOLANA -> {
                val pub = Base58.decode(signerAddress.trim())
                val sigBytes = Base58.decode(signature.trim())
                WalletMessageCrypto.verifyEd25519Signature(
                    message = message.toByteArray(StandardCharsets.UTF_8),
                    signatureBytes = sigBytes,
                    publicKey32 = pub
                )
            }
            ChainFamily.BITCOIN -> false
        }
    }
}
