package com.unitrading.app

/**
 * SHA-256 (FIPS 180-4), HMAC (RFC 2104) and PBKDF2 (RFC 8018) for checking license keys against their verifiers.
 * Common code because the iOS target has no shared crypto provider; the desktop tests compare every function with
 * the JDK implementation and the common tests with the published RFC and NIST vectors.
 */
internal object Sha256 {
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).let { values -> IntArray(values.size) { values[it].toInt() } }
    private val INITIAL = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
        .let { values -> IntArray(values.size) { values[it].toInt() } }

    fun digest(message: ByteArray): ByteArray = digestFrom(INITIAL, 0, message).toBytes()

    /** Hashes [message] as the continuation of [prefixBytes] bytes already absorbed into [state]; [state] is not modified. */
    fun digestFrom(state: IntArray, prefixBytes: Int, message: ByteArray): IntArray {
        val result = state.copyOf()
        val total = (message.size + 9 + 63) / 64 * 64
        val buffer = message.copyOf(total)
        buffer[message.size] = 0x80.toByte()
        val bits = (prefixBytes.toLong() + message.size) * 8
        for (i in 0 until 8) buffer[total - 1 - i] = (bits ushr (8 * i)).toByte()
        val block = IntArray(16)
        val schedule = IntArray(64)
        for (offset in 0 until total step 64) {
            for (j in 0 until 16) block[j] = buffer.intAt(offset + 4 * j)
            compress(result, block, schedule)
        }
        buffer.fill(0)
        block.fill(0)
        schedule.fill(0)
        return result
    }

    /** State after absorbing one 64-byte block; the HMAC pad states start from here. */
    fun absorbBlock(block: IntArray): IntArray = INITIAL.copyOf().also { compress(it, block, IntArray(64)) }

    fun compress(state: IntArray, block: IntArray, w: IntArray) {
        for (t in 0 until 16) w[t] = block[t]
        for (t in 16 until 64) {
            val a = w[t - 15]
            val b = w[t - 2]
            val s0 = a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)
            val s1 = b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (t in 0 until 64) {
            val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private fun ByteArray.intAt(offset: Int): Int =
        (this[offset].toInt() and 0xff shl 24) or (this[offset + 1].toInt() and 0xff shl 16) or
            (this[offset + 2].toInt() and 0xff shl 8) or (this[offset + 3].toInt() and 0xff)
}

internal fun IntArray.toBytes(): ByteArray = ByteArray(size * 4) { i -> (this[i / 4] ushr (24 - 8 * (i % 4))).toByte() }

/** Inner and outer HMAC-SHA256 states after the key pads, so each further HMAC costs two compressions. */
private class HmacSha256Key(key: ByteArray) {
    val inner: IntArray
    val outer: IntArray
    init {
        val k = if (key.size > 64) Sha256.digest(key) else key
        fun pad(value: Int): IntArray {
            val block = IntArray(16) { j ->
                var word = 0
                for (b in 0 until 4) word = (word shl 8) or ((k.getOrElse(4 * j + b) { 0 }.toInt() xor value) and 0xff)
                word
            }
            return Sha256.absorbBlock(block).also { block.fill(0) }
        }
        inner = pad(0x36)
        outer = pad(0x5c)
        if (k !== key) k.fill(0)
    }
    fun mac(message: ByteArray): IntArray = Sha256.digestFrom(outer, 64, Sha256.digestFrom(inner, 64, message).toBytes())
    fun clear() { inner.fill(0); outer.fill(0) }
}

internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray = HmacSha256Key(key).let { k -> k.mac(message).toBytes().also { k.clear() } }

internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    require(iterations >= 1 && length >= 1)
    val key = HmacSha256Key(password)
    val out = ByteArray(length)
    // U_j for j >= 2 is the HMAC of a 32-byte value: one padded block per compression, reused without allocation.
    val block = IntArray(16).apply { this[8] = 0x80000000.toInt(); this[15] = (64 + 32) * 8 }
    val state = IntArray(8)
    val w = IntArray(64)
    val t = IntArray(8)
    var index = 1
    var written = 0
    while (written < length) {
        val first = key.mac(salt + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte()))
        first.copyInto(t)
        var u = first
        repeat(iterations - 1) {
            u.copyInto(block, 0, 0, 8)
            key.inner.copyInto(state); Sha256.compress(state, block, w)
            state.copyInto(block, 0, 0, 8)
            key.outer.copyInto(state); Sha256.compress(state, block, w)
            u = state
            for (k in 0 until 8) t[k] = t[k] xor state[k]
        }
        val bytes = t.toBytes()
        val count = minOf(32, length - written)
        bytes.copyInto(out, written, 0, count)
        bytes.fill(0)
        first.fill(0)
        written += count
        index++
    }
    key.clear(); block.fill(0); state.fill(0); w.fill(0); t.fill(0)
    return out
}

/** Compares in time independent of where the arrays differ; lengths are public. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
    return difference == 0
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
