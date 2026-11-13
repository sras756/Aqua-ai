package com.example.wallet

/**
 * Lightweight pure-Kotlin QR Code generator (Byte mode, ECC Level L, Versions 1..6)
 * for rendering real scannable receiving address & WalletConnect URI QR codes
 * without external binary dependencies.
 */
object QrCodeGenerator {

    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = selectVersion(data.size) ?: return null
        val size = 17 + version * 4
        val ecCodewordsPerBlock = EC_CODEWORDS_L[version]
        val dataCodewordsTotal = TOTAL_CODEWORDS[version] - ecCodewordsPerBlock

        val bitStream = BitBuffer()
        // Mode indicator: Byte (0100)
        bitStream.put(0b0100, 4)
        // Character count indicator (8 bits for versions 1..9)
        bitStream.put(data.size, 8)
        for (b in data) {
            bitStream.put(b.toInt() and 0xFF, 8)
        }
        // Terminator
        val maxBits = dataCodewordsTotal * 8
        val termBits = minOf(4, maxBits - bitStream.size)
        if (termBits > 0) bitStream.put(0, termBits)
        while (bitStream.size % 8 != 0) bitStream.put(0, 1)
        var padByte = 0xEC
        while (bitStream.size < maxBits) {
            bitStream.put(padByte, 8)
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        val dataBytes = bitStream.toByteArray()
        val ecBytes = reedSolomonRemainder(dataBytes, ecCodewordsPerBlock)
        val allCodewords = dataBytes + ecBytes

        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(version, size, modules, isFunction)
        placeDataBits(size, allCodewords, modules, isFunction)
        val mask = 0 // Mask pattern 0: (r + c) % 2 == 0
        applyMask0(size, modules, isFunction)
        drawFormatBits(size, mask, modules, isFunction)
        return modules
    }

    private fun selectVersion(byteLen: Int): Int? {
        for (v in 1..6) {
            val capacityBytes = TOTAL_CODEWORDS[v] - EC_CODEWORDS_L[v] - 2
            if (byteLen <= capacityBytes) return v
        }
        return null
    }

    private fun drawFunctionPatterns(
        version: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        // Finder patterns
        drawFinder(0, 0, size, modules, isFunction)
        drawFinder(size - 7, 0, size, modules, isFunction)
        drawFinder(0, size - 7, size, modules, isFunction)

        // Timing patterns
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            setFunc(6, i, dark, modules, isFunction)
            setFunc(i, 6, dark, modules, isFunction)
        }

        // Alignment pattern for versions >= 2
        if (version >= 2) {
            val pos = size - 7
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val dark = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1
                    setFunc(pos + dy, pos + dx, dark, modules, isFunction)
                }
            }
        }

        // Dark module
        setFunc(size - 8, 8, true, modules, isFunction)

        // Reserve format info area
        for (i in 0..8) {
            if (!isFunction[8][i]) isFunction[8][i] = true
            if (!isFunction[i][8]) isFunction[i][8] = true
        }
        for (i in size - 8 until size) {
            isFunction[8][i] = true
            isFunction[i][8] = true
        }
    }

    private fun drawFinder(
        top: Int,
        left: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val r = top + dy
                val c = left + dx
                if (r in 0 until size && c in 0 until size) {
                    val inBox = dy in 0..6 && dx in 0..6
                    val dist = maxOf(kotlin.math.abs(dy - 3), kotlin.math.abs(dx - 3))
                    val dark = inBox && (dist == 3 || dist <= 1)
                    setFunc(r, c, dark, modules, isFunction)
                }
            }
        }
    }

    private fun setFunc(
        r: Int,
        c: Int,
        dark: Boolean,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        modules[r][c] = dark
        isFunction[r][c] = true
    }

    private fun placeDataBits(
        size: Int,
        codewords: ByteArray,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var up = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until size) {
                val r = if (up) size - 1 - vert else vert
                for (j in 0..1) {
                    val c = right - j
                    if (!isFunction[r][c]) {
                        val bit = if (bitIndex < totalBits) {
                            ((codewords[bitIndex / 8].toInt() ushr (7 - (bitIndex % 8))) and 1) != 0
                        } else {
                            false
                        }
                        modules[r][c] = bit
                        bitIndex++
                    }
                }
            }
            up = !up
            right -= 2
        }
    }

    private fun applyMask0(
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!isFunction[r][c] && ((r + c) % 2 == 0)) {
                    modules[r][c] = !modules[r][c]
                }
            }
        }
    }

    private fun drawFormatBits(
        size: Int,
        mask: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        // ECC L = 01, Mask = 000 -> data = 01000 -> format bits with BCH + XOR 0x5412 = 0x77C4
        val bits = 0x77C4
        for (i in 0..5) setFunc(8, i, ((bits ushr i) and 1) != 0, modules, isFunction)
        setFunc(8, 7, ((bits ushr 6) and 1) != 0, modules, isFunction)
        setFunc(8, 8, ((bits ushr 7) and 1) != 0, modules, isFunction)
        setFunc(7, 8, ((bits ushr 8) and 1) != 0, modules, isFunction)
        for (i in 9..14) setFunc(14 - i, 8, ((bits ushr i) and 1) != 0, modules, isFunction)

        for (i in 0..7) setFunc(size - 1 - i, 8, ((bits ushr i) and 1) != 0, modules, isFunction)
        for (i in 8..14) setFunc(8, size - 15 + i, ((bits ushr i) and 1) != 0, modules, isFunction)
    }

    private fun reedSolomonRemainder(data: ByteArray, degree: Int): ByteArray {
        val gen = rsGeneratorPoly(degree)
        val result = IntArray(degree)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, degree - 1)
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = result[i] xor gfMul(gen[i], factor)
            }
        }
        return ByteArray(degree) { result[it].toByte() }
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        var root = 1
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
            root = gfMul(root, 2)
        }
        return poly.copyOfRange(1, poly.size)
    }

    private fun gfMul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var res = 0
        for (i in 0 until 8) {
            if ((y and 1) != 0) res = res xor x
            val hi = (x and 0x80) != 0
            x = (x shl 1) and 0xFF
            if (hi) x = x xor 0x1D
            y = y ushr 1
        }
        return res
    }

    private class BitBuffer {
        private val bytes = mutableListOf<Int>()
        var size = 0
            private set

        fun put(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value ushr i) and 1
                if (size % 8 == 0) bytes.add(0)
                if (bit == 1) {
                    val idx = bytes.size - 1
                    bytes[idx] = bytes[idx] or (1 shl (7 - (size % 8)))
                }
                size++
            }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
    }

    // Versions 1..6 single-block ECC L parameters
    private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172)
    private val EC_CODEWORDS_L = intArrayOf(0, 7, 10, 15, 20, 26, 36)
}
