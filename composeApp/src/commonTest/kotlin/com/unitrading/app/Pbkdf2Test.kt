package com.unitrading.app

import kotlin.test.*

/** Published vectors: NIST FIPS 180-4 examples, RFC 4231 (HMAC-SHA256) and RFC 7914 §11 (PBKDF2-HMAC-SHA256). */
class Pbkdf2Test {
    private fun ascii(text: String) = text.encodeToByteArray()

    @Test fun sha256MatchesNistVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(ByteArray(0)).toHex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest(ascii("abc")).toHex())
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).toHex())
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex())
    }

    @Test fun hmacSha256MatchesRfc4231() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hmacSha256(ByteArray(20) { 0x0b }, ascii("Hi There")).toHex())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hmacSha256(ascii("Jefe"), ascii("what do ya want for nothing?")).toHex())
        // Keys longer than the block are hashed first.
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmacSha256(ByteArray(131) { 0xaa.toByte() }, ascii("Test Using Larger Than Block-Size Key - Hash Key First")).toHex())
    }

    @Test fun pbkdf2MatchesRfc7914Vectors() {
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            pbkdf2HmacSha256(ascii("passwd"), ascii("salt"), 1, 64).toHex())
        assertEquals("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            pbkdf2HmacSha256(ascii("Password"), ascii("NaCl"), 80_000, 64).toHex())
    }

    @Test fun constantTimeEqualsComparesLengthAndEveryByte() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(0, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test fun hexRoundTrips() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, bytes.toHex().hexToBytes())
        assertFails { "abc".hexToBytes() }
    }
}
