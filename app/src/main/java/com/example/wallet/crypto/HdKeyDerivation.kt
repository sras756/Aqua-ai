package com.example.wallet.crypto

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.Mnemonics.WordCount
import cash.z.ecc.android.bip39.toSeed
import java.math.BigInteger
import java.nio.ByteBuffer

data class DerivedAccountMaterial(
    val accountIndex: Int,
    val evmAddress: String,
    val solanaAddress: String,
    val bitcoinMainnetAddress: String,
    val bitcoinTestnetAddress: String,
    val evmDerivationPath: String,
    val solanaDerivationPath: String,
    val bitcoinMainnetDerivationPath: String,
    val bitcoinTestnetDerivationPath: String
)

data class DerivedPrivateKey(
    val keyBytes: ByteArray,
    val chainCode: ByteArray,
    val derivationPath: String
) {
    fun wipe() {
        CryptoPrimitives.wipe(keyBytes, chainCode)
    }
}

/**
 * Standard BIP-39 / BIP-32 / BIP-44 / BIP-84 / SLIP-0010 hierarchical deterministic
 * key derivation using cash.z.ecc.android:kotlin-bip39 and BouncyCastle primitives.
 */
object HdKeyDerivation {

    private const val HARDENED_BIT = 0x80000000.toInt()
    private val BITCOIN_SEED_KEY = "Bitcoin seed".toByteArray(Charsets.UTF_8)
    private val ED25519_SEED_KEY = "ed25519 seed".toByteArray(Charsets.UTF_8)

    fun generateMnemonic(wordCount: Int = 12): String {
        val count = if (wordCount == 24) WordCount.COUNT_24 else WordCount.COUNT_12
        val mnemonic = Mnemonics.MnemonicCode(count)
        return try {
            mnemonic.words.joinToString(" ") { chars -> String(chars) }
        } finally {
            mnemonic.clear()
        }
    }

    fun validateMnemonic(phrase: String): Result<List<String>> = runCatching {
        val normalized = normalizePhrase(phrase)
        val chars = normalized.toCharArray()
        val code = Mnemonics.MnemonicCode(chars)
        try {
            code.validate()
            val entropy = code.toEntropy()
            CryptoPrimitives.wipe(entropy)
            normalized.split(" ")
        } finally {
            code.clear()
            CryptoPrimitives.wipeChars(chars)
        }
    }

    fun mnemonicToSeed(phrase: String, passphrase: String = ""): ByteArray {
        val normalized = normalizePhrase(phrase)
        val phraseChars = normalized.toCharArray()
        val passChars = passphrase.toCharArray()
        val code = Mnemonics.MnemonicCode(phraseChars)
        return try {
            code.validate()
            code.toSeed(passChars)
        } finally {
            code.clear()
            CryptoPrimitives.wipeChars(phraseChars, passChars)
        }
    }

    fun normalizePhrase(phrase: String): String {
        return phrase.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun deriveAccountMaterial(seed: ByteArray, accountIndex: Int): DerivedAccountMaterial {
        require(accountIndex >= 0) { "Account index must be non-negative." }
        val evmPath = "m/44'/60'/0'/0/$accountIndex"
        val solPath = "m/44'/501'/$accountIndex'/0'"
        val btcMainPath = "m/84'/0'/0'/0/$accountIndex"
        val btcTestPath = "m/84'/1'/0'/0/$accountIndex"

        val evmKey = deriveBip32Secp256k1(seed, evmPath)
        val solKey = deriveSlip10Ed25519(seed, solPath)
        val btcMainKey = deriveBip32Secp256k1(seed, btcMainPath)
        val btcTestKey = deriveBip32Secp256k1(seed, btcTestPath)

        return try {
            val evmPub = CryptoPrimitives.deriveSecp256k1PublicKey(evmKey.keyBytes, compressed = false)
            val evmAddress = CryptoPrimitives.evmAddressFromPublicKey(evmPub)

            val solPub = CryptoPrimitives.deriveEd25519PublicKey(solKey.keyBytes)
            val solAddress = Base58.encode(solPub)

            val btcMainPub = CryptoPrimitives.deriveSecp256k1PublicKey(btcMainKey.keyBytes, compressed = true)
            val btcMainHash160 = CryptoPrimitives.hash160(btcMainPub)
            val btcMainAddress = Bech32.encodeSegwitAddress("bc", 0, btcMainHash160)

            val btcTestPub = CryptoPrimitives.deriveSecp256k1PublicKey(btcTestKey.keyBytes, compressed = true)
            val btcTestHash160 = CryptoPrimitives.hash160(btcTestPub)
            val btcTestAddress = Bech32.encodeSegwitAddress("tb", 0, btcTestHash160)

            DerivedAccountMaterial(
                accountIndex = accountIndex,
                evmAddress = evmAddress,
                solanaAddress = solAddress,
                bitcoinMainnetAddress = btcMainAddress,
                bitcoinTestnetAddress = btcTestAddress,
                evmDerivationPath = evmPath,
                solanaDerivationPath = solPath,
                bitcoinMainnetDerivationPath = btcMainPath,
                bitcoinTestnetDerivationPath = btcTestPath
            )
        } finally {
            evmKey.wipe()
            solKey.wipe()
            btcMainKey.wipe()
            btcTestKey.wipe()
        }
    }

    /**
     * Standard BIP-32 HD key derivation over secp256k1.
     */
    fun deriveBip32Secp256k1(seed: ByteArray, path: String): DerivedPrivateKey {
        require(seed.size >= 16) { "BIP-32 seed must be at least 16 bytes." }
        val master = CryptoPrimitives.hmacSha512(BITCOIN_SEED_KEY, seed)
        var k = master.copyOfRange(0, 32)
        var c = master.copyOfRange(32, 64)
        CryptoPrimitives.wipe(master)

        val n = CryptoPrimitives.secp256k1Domain.n
        val segments = parsePath(path)
        for (childIndex in segments) {
            val hardened = (childIndex and HARDENED_BIT) != 0
            val data = ByteArray(37)
            if (hardened) {
                data[0] = 0x00
                System.arraycopy(k, 0, data, 1, 32)
            } else {
                val compressedPub = CryptoPrimitives.deriveSecp256k1PublicKey(k, compressed = true)
                System.arraycopy(compressedPub, 0, data, 0, 33)
            }
            ByteBuffer.wrap(data, 33, 4).putInt(childIndex)
            val i = CryptoPrimitives.hmacSha512(c, data)
            CryptoPrimitives.wipe(data)

            val iL = BigInteger(1, i.copyOfRange(0, 32))
            val kPar = BigInteger(1, k)
            val childKeyInt = iL.add(kPar).mod(n)
            require(iL < n && childKeyInt != BigInteger.ZERO) { "Invalid derived BIP-32 key scalar." }

            CryptoPrimitives.wipe(k, c)
            k = CryptoPrimitives.bigIntTo32Bytes(childKeyInt)
            c = i.copyOfRange(32, 64)
            CryptoPrimitives.wipe(i)
        }
        return DerivedPrivateKey(k, c, path)
    }

    /**
     * Standard SLIP-0010 Ed25519 derivation (all path segments MUST be hardened).
     */
    fun deriveSlip10Ed25519(seed: ByteArray, path: String): DerivedPrivateKey {
        require(seed.size >= 16) { "SLIP-0010 seed must be at least 16 bytes." }
        val master = CryptoPrimitives.hmacSha512(ED25519_SEED_KEY, seed)
        var k = master.copyOfRange(0, 32)
        var c = master.copyOfRange(32, 64)
        CryptoPrimitives.wipe(master)

        val segments = parsePath(path)
        for (childIndex in segments) {
            require((childIndex and HARDENED_BIT) != 0) {
                "SLIP-0010 Ed25519 requires hardened path indices."
            }
            val data = ByteArray(37)
            data[0] = 0x00
            System.arraycopy(k, 0, data, 1, 32)
            ByteBuffer.wrap(data, 33, 4).putInt(childIndex)
            val i = CryptoPrimitives.hmacSha512(c, data)
            CryptoPrimitives.wipe(data)
            CryptoPrimitives.wipe(k, c)
            k = i.copyOfRange(0, 32)
            c = i.copyOfRange(32, 64)
            CryptoPrimitives.wipe(i)
        }
        return DerivedPrivateKey(k, c, path)
    }

    private fun parsePath(path: String): List<Int> {
        val parts = path.trim().split("/")
        require(parts.isNotEmpty() && parts[0] == "m") { "Derivation path must start with 'm'." }
        return parts.drop(1).map { token ->
            val hardened = token.endsWith("'") || token.endsWith("h") || token.endsWith("H")
            val numStr = if (hardened) token.dropLast(1) else token
            val index = numStr.toInt()
            require(index >= 0) { "Negative derivation index in $path." }
            if (hardened) index or HARDENED_BIT else index
        }
    }
}
