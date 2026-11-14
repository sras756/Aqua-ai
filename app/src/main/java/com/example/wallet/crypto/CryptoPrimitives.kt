package com.example.wallet.crypto

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequenceGenerator
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class EcdsaSignature(
    val r: BigInteger,
    val s: BigInteger,
    val recoveryId: Int
)

object CryptoPrimitives {

    private val curveParams = CustomNamedCurves.getByName("secp256k1")
    val secp256k1Domain = ECDomainParameters(
        curveParams.curve,
        curveParams.g,
        curveParams.n,
        curveParams.h
    )
    private val halfCurveOrder: BigInteger = curveParams.n.shiftRight(1)

    val secureRandom: SecureRandom by lazy { SecureRandom() }

    fun sha256(input: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(input, 0, input.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    fun doubleSha256(input: ByteArray): ByteArray = sha256(sha256(input))

    fun hash160(input: ByteArray): ByteArray {
        val sha = sha256(input)
        val ripemd = RIPEMD160Digest()
        ripemd.update(sha, 0, sha.size)
        return ByteArray(ripemd.digestSize).also { ripemd.doFinal(it, 0) }
    }

    fun keccak256(input: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(input, 0, input.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    fun toHex(bytes: ByteArray, prefix: Boolean = false): String {
        val sb = StringBuilder(bytes.size * 2 + if (prefix) 2 else 0)
        if (prefix) sb.append("0x")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X").trim()
        require(clean.length % 2 == 0) { "Hex string must have even length." }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bigIntTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).apply {
                System.arraycopy(raw, 0, this, 32 - raw.size, raw.size)
            }
        }
    }

    fun deriveSecp256k1PublicKey(privateKey32: ByteArray, compressed: Boolean): ByteArray {
        val d = BigInteger(1, privateKey32)
        require(d > BigInteger.ZERO && d < secp256k1Domain.n) { "Invalid secp256k1 private key." }
        val point = FixedPointCombMultiplier().multiply(secp256k1Domain.g, d)
        return point.getEncoded(compressed)
    }

    fun evmAddressFromPublicKey(uncompressedOrCompressedPubKey: ByteArray): String {
        val point = secp256k1Domain.curve.decodePoint(uncompressedOrCompressedPubKey)
        val uncompressed = point.getEncoded(false)
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) { "Invalid EC point." }
        val hash = keccak256(uncompressed.copyOfRange(1, 65))
        val rawAddr = toHex(hash.copyOfRange(12, 32), prefix = false)
        return toEip55ChecksumAddress("0x$rawAddr")
    }

    fun toEip55ChecksumAddress(address: String): String {
        val clean = address.removePrefix("0x").removePrefix("0X").lowercase()
        require(clean.length == 40 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid EVM address format."
        }
        val hashHex = toHex(keccak256(clean.toByteArray(Charsets.US_ASCII)))
        val out = StringBuilder("0x")
        for (i in clean.indices) {
            val ch = clean[i]
            val nibble = Character.digit(hashHex[i], 16)
            out.append(if (ch in 'a'..'f' && nibble >= 8) ch.uppercaseChar() else ch)
        }
        return out.toString()
    }

    fun isValidEvmAddress(address: String, strictMixedCaseChecksum: Boolean = false): Boolean {
        if (!Regex("^0x[0-9a-fA-F]{40}$").matches(address)) return false
        val body = address.substring(2)
        val isAllLower = body == body.lowercase()
        val isAllUpper = body == body.uppercase()
        if (isAllLower || isAllUpper) return !strictMixedCaseChecksum
        return toEip55ChecksumAddress(address) == address
    }

    fun signSecp256k1(hash32: ByteArray, privateKey32: ByteArray): EcdsaSignature {
        require(hash32.size == 32) { "Signing digest must be 32 bytes." }
        val d = BigInteger(1, privateKey32)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, secp256k1Domain))
        val components = signer.generateSignature(hash32)
        val r = components[0]
        var s = components[1]
        if (s > halfCurveOrder) {
            s = secp256k1Domain.n.subtract(s)
        }
        val expectedPub = FixedPointCombMultiplier().multiply(secp256k1Domain.g, d).getEncoded(false)
        var recId = -1
        for (i in 0..3) {
            val recovered = recoverSecp256k1PublicKey(i, r, s, hash32)
            if (recovered != null && recovered.contentEquals(expectedPub)) {
                recId = i
                break
            }
        }
        check(recId >= 0) { "Failed to recover secp256k1 signature parity." }
        return EcdsaSignature(r, s, recId)
    }

    private fun recoverSecp256k1PublicKey(
        recId: Int,
        r: BigInteger,
        s: BigInteger,
        messageHash: ByteArray
    ): ByteArray? {
        val n = secp256k1Domain.n
        val i = BigInteger.valueOf((recId / 2).toLong())
        val x = r.add(i.multiply(n))
        val prime = SecP256K1Curve.q
        if (x >= prime) return null
        val rPoint = decompressKey(x, (recId and 1) == 1) ?: return null
        if (!rPoint.multiply(n).isInfinity) return null
        val e = BigInteger(1, messageHash)
        val eInv = BigInteger.ZERO.subtract(e).mod(n)
        val rInv = r.modInverse(n)
        val srInv = rInv.multiply(s).mod(n)
        val eInvrInv = rInv.multiply(eInv).mod(n)
        val q: ECPoint = ECAlgorithms.sumOfTwoMultiplies(secp256k1Domain.g, eInvrInv, rPoint, srInv)
        return if (q.isInfinity) null else q.getEncoded(false)
    }

    private fun decompressKey(xBn: BigInteger, yBit: Boolean): ECPoint? {
        val xBytes = bigIntTo32Bytes(xBn)
        val compEnc = ByteArray(33)
        compEnc[0] = if (yBit) 0x03 else 0x02
        System.arraycopy(xBytes, 0, compEnc, 1, 32)
        return try {
            secp256k1Domain.curve.decodePoint(compEnc)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeEcdsaDer(r: BigInteger, s: BigInteger): ByteArray {
        val bos = ByteArrayOutputStream(72)
        val seq = DERSequenceGenerator(bos)
        seq.addObject(ASN1Integer(r))
        seq.addObject(ASN1Integer(s))
        seq.close()
        return bos.toByteArray()
    }

    fun deriveEd25519PublicKey(privateSeed32: ByteArray): ByteArray {
        require(privateSeed32.size == 32) { "Ed25519 seed must be 32 bytes." }
        val params = Ed25519PrivateKeyParameters(privateSeed32, 0)
        return params.generatePublicKey().encoded
    }

    fun signEd25519(message: ByteArray, privateSeed32: ByteArray): ByteArray {
        require(privateSeed32.size == 32) { "Ed25519 seed must be 32 bytes." }
        val params = Ed25519PrivateKeyParameters(privateSeed32, 0)
        val signer = Ed25519Signer()
        signer.init(true, params)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun wipe(vararg arrays: ByteArray?) {
        for (arr in arrays) {
            arr?.fill(0)
        }
    }

    fun wipeChars(vararg arrays: CharArray?) {
        for (arr in arrays) {
            arr?.fill('\u0000')
        }
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val copy = input.copyOf()
        var zeros = 0
        while (zeros < copy.size && copy[zeros].toInt() == 0) zeros++
        val encoded = CharArray(copy.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < copy.size) {
            encoded[--outputStart] = ALPHABET[divmod(copy, inputStart, 256, 58)]
            if (copy[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < encoded.size && encoded[outputStart] == ALPHABET[0]) outputStart++
        while (--zeros >= 0) encoded[--outputStart] = ALPHABET[0]
        return String(encoded, outputStart, encoded.size - outputStart)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Illegal Base58 character '$c'." }
            input58[i] = digit.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) zeros++
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256).toByte()
            if (input58[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) outputStart++
        return decoded.copyOfRange(outputStart - zeros, decoded.size)
    }

    fun encodeChecked(versionAndPayload: ByteArray): String {
        val checksum = CryptoPrimitives.doubleSha256(versionAndPayload).copyOfRange(0, 4)
        return encode(versionAndPayload + checksum)
    }

    fun decodeChecked(input: String): ByteArray {
        val decoded = decode(input)
        require(decoded.size >= 4) { "Base58Check payload too short." }
        val data = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expected = CryptoPrimitives.doubleSha256(data).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "Base58Check checksum mismatch." }
        return data
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    data class WitnessDecoded(val hrp: String, val witnessVersion: Int, val program: ByteArray)

    fun encodeSegwitAddress(hrp: String, witnessVersion: Int, program: ByteArray): String {
        val converted = convertBits(program, 8, 5, true)
        val data = IntArray(converted.size + 1).apply {
            this[0] = witnessVersion
            for (i in converted.indices) this[i + 1] = converted[i].toInt() and 0xFF
        }
        return encode(hrp, data, isBech32m = witnessVersion > 0)
    }

    fun decodeSegwitAddress(expectedHrp: String, address: String): WitnessDecoded {
        val (hrp, data, isBech32m) = decode(address)
        require(hrp == expectedHrp) { "Unexpected Bech32 HRP '$hrp' (expected '$expectedHrp')." }
        require(data.isNotEmpty()) { "Empty Bech32 witness payload." }
        val version = data[0]
        require(version in 0..16) { "Invalid witness version $version." }
        if (version == 0) require(!isBech32m) { "Witness v0 requires Bech32 checksum." }
        if (version > 0) require(isBech32m) { "Witness v1+ requires Bech32m checksum." }
        val program = convertBits(data.copyOfRange(1, data.size).map { it.toByte() }.toByteArray(), 5, 8, false)
        require(program.size in 2..40) { "Invalid witness program length." }
        if (version == 0) {
            require(program.size == 20 || program.size == 32) { "SegWit v0 must be 20 or 32 bytes." }
        }
        return WitnessDecoded(hrp, version, program)
    }

    private fun encode(hrp: String, data: IntArray, isBech32m: Boolean): String {
        val checksum = createChecksum(hrp, data, if (isBech32m) 0x2bc830a3 else 1)
        val combined = data + checksum
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp).append('1')
        for (v in combined) sb.append(CHARSET[v])
        return sb.toString()
    }

    private data class Decoded(val hrp: String, val data: IntArray, val isBech32m: Boolean)

    private fun decode(bech: String): Decoded {
        require(bech.length in 8..90) { "Invalid Bech32 address length." }
        val hasLower = bech.any { it in 'a'..'z' }
        val hasUpper = bech.any { it in 'A'..'Z' }
        require(!(hasLower && hasUpper)) { "Mixed case Bech32 is invalid." }
        val lower = bech.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "Invalid Bech32 separator position." }
        val hrp = lower.substring(0, pos)
        val dataPart = lower.substring(pos + 1)
        val values = IntArray(dataPart.length) { i ->
            val idx = CHARSET.indexOf(dataPart[i])
            require(idx >= 0) { "Invalid Bech32 character." }
            idx
        }
        val poly = polymod(hrpExpand(hrp) + values)
        val isBech32m = when (poly) {
            1 -> false
            0x2bc830a3 -> true
            else -> throw IllegalArgumentException("Invalid Bech32 checksum.")
        }
        return Decoded(hrp, values.copyOfRange(0, values.size - 6), isBech32m)
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun createChecksum(hrp: String, data: IntArray, specConst: Int): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor specConst
        return IntArray(6) { i -> (mod ushr (5 * (5 - i))) and 31 }
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val value = b.toInt() and 0xFF
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (toBits - bits)) and maxv)
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
                "Invalid Bech32 padding bits."
            }
        }
        return out.toByteArray()
    }

    private fun ByteArray.toByteArray(): ByteArray = this
}

object RlpEncoder {
    fun encodeByteArray(bytes: ByteArray): ByteArray {
        return if (bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80) {
            bytes
        } else if (bytes.size <= 55) {
            byteArrayOf((0x80 + bytes.size).toByte()) + bytes
        } else {
            val lenBytes = minimalBigEndianBytes(bytes.size.toLong())
            byteArrayOf((0xb7 + lenBytes.size).toByte()) + lenBytes + bytes
        }
    }

    fun encodeBigInteger(value: BigInteger): ByteArray {
        if (value == BigInteger.ZERO) return byteArrayOf(0x80.toByte())
        val raw = value.toByteArray()
        val trimmed = if (raw[0].toInt() == 0 && raw.size > 1) raw.copyOfRange(1, raw.size) else raw
        return encodeByteArray(trimmed)
    }

    fun encodeList(encodedItems: List<ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        for (item in encodedItems) bos.write(item)
        val payload = bos.toByteArray()
        return if (payload.size <= 55) {
            byteArrayOf((0xc0 + payload.size).toByte()) + payload
        } else {
            val lenBytes = minimalBigEndianBytes(payload.size.toLong())
            byteArrayOf((0xf7 + lenBytes.size).toByte()) + lenBytes + payload
        }
    }

    private fun minimalBigEndianBytes(value: Long): ByteArray {
        if (value == 0L) return ByteArray(0)
        val tmp = ByteArrayOutputStream()
        var v = value
        while (v > 0) {
            tmp.write((v and 0xFF).toInt())
            v = v ushr 8
        }
        return tmp.toByteArray().reversedArray()
    }
}
