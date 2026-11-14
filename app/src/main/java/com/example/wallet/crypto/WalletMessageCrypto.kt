package com.example.wallet.crypto

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * Cryptographic helpers for ENS EIP-137 namehash, EVM EIP-191 personal_sign,
 * and Solana Ed25519 off-chain message signing/verification.
 */
object WalletMessageCrypto {

    /**
     * Standard EIP-137 ENS recursive Keccak-256 namehash.
     */
    fun ensNamehash(domain: String): ByteArray {
        val clean = domain.trim().lowercase().trimEnd('.')
        var node = ByteArray(32)
        if (clean.isEmpty()) return node
        val labels = clean.split('.').filter { it.isNotEmpty() }
        for (i in labels.indices.reversed()) {
            val labelHash = CryptoPrimitives.keccak256(labels[i].toByteArray(StandardCharsets.UTF_8))
            node = CryptoPrimitives.keccak256(node + labelHash)
        }
        return node
    }

    /**
     * Produces a 65-byte EIP-191 ("personal_sign") hex signature (`0x` + r[32] + s[32] + v[1]).
     */
    fun signEip191PersonalMessage(message: String, privateKey32: ByteArray): String {
        val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
        val prefix = "\u0019Ethereum Signed Message:\n${msgBytes.size}".toByteArray(StandardCharsets.UTF_8)
        val digest = CryptoPrimitives.keccak256(prefix + msgBytes)
        val sig = CryptoPrimitives.signSecp256k1(digest, privateKey32)
        val rBytes = CryptoPrimitives.bigIntTo32Bytes(sig.r)
        val sBytes = CryptoPrimitives.bigIntTo32Bytes(sig.s)
        val vByte = byteArrayOf((27 + sig.recoveryId).toByte())
        return CryptoPrimitives.toHex(rBytes + sBytes + vByte, prefix = true)
    }

    /**
     * Recovers signer address from a 65-byte EIP-191 signature and verifies it matches [expectedEvmAddress].
     */
    fun verifyEip191PersonalMessage(
        message: String,
        signatureHex: String,
        expectedEvmAddress: String
    ): Boolean {
        val cleanTarget = expectedEvmAddress.trim()
        if (!CryptoPrimitives.isValidEvmAddress(cleanTarget, strictMixedCaseChecksum = false)) return false
        val sigBytes = runCatching { CryptoPrimitives.fromHex(signatureHex) }.getOrNull() ?: return false
        if (sigBytes.size != 65) return false

        val r = BigInteger(1, sigBytes.copyOfRange(0, 32))
        val s = BigInteger(1, sigBytes.copyOfRange(32, 64))
        val rawV = sigBytes[64].toInt() and 0xFF
        val recId = when (rawV) {
            0, 1 -> rawV
            27, 28 -> rawV - 27
            else -> return false
        }
        val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
        val prefix = "\u0019Ethereum Signed Message:\n${msgBytes.size}".toByteArray(StandardCharsets.UTF_8)
        val digest = CryptoPrimitives.keccak256(prefix + msgBytes)
        val recoveredPub = CryptoPrimitives.recoverSecp256k1PublicKey(recId, r, s, digest) ?: return false
        val recoveredAddr = CryptoPrimitives.evmAddressFromPublicKey(recoveredPub)
        return recoveredAddr.equals(cleanTarget, ignoreCase = true)
    }

    /**
     * Verifies a 64-byte Ed25519 signature against [publicKey32] for [message].
     */
    fun verifyEd25519Signature(
        message: ByteArray,
        signatureBytes: ByteArray,
        publicKey32: ByteArray
    ): Boolean {
        if (signatureBytes.size != 64 || publicKey32.size != 32) return false
        return runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey32, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signatureBytes)
        }.getOrDefault(false)
    }
}
