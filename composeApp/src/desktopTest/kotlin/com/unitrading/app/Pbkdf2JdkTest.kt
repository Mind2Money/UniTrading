package com.unitrading.app

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.*

/** The common-code primitives against the JDK providers, across every SHA-256 padding boundary. */
class Pbkdf2JdkTest {
    private val random = Random(20261105)

    @Test fun sha256AndHmacMatchTheJdk() {
        for (length in 0..300) {
            val message = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(message), Sha256.digest(message), "SHA-256 of $length bytes")
            val key = random.nextBytes(random.nextInt(1, 160))
            val expected = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
            assertContentEquals(expected, hmacSha256(key, message), "HMAC of $length bytes with a ${key.size}-byte key")
        }
    }

    @Test fun pbkdf2MatchesTheJdk() {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val cases = listOf(Triple("k", 1, 1), Triple("unit-test-key-A1", 2, 32), Triple("x".repeat(100), 1_000, 64), Triple("ascii~key!01", 4_096, 33))
        for ((password, iterations, length) in cases) {
            val salt = random.nextBytes(16)
            val expected = factory.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)).encoded
            assertContentEquals(expected, pbkdf2HmacSha256(password.encodeToByteArray(), salt, iterations, length), "$iterations iterations, $length bytes")
        }
    }

    @Test fun productionVerifierRefusesAWrongKeyInBoundedTime() = runBlocking<Unit> {
        val elapsed = measureTimeMillis { assertNull(OfflineLicenseVerifier().verify("not-the-license-key")) }
        println("Production PBKDF2 check took $elapsed ms")
        assertTrue(elapsed < 10_000)
    }

    /** Confirms a key against the shipped verifier without committing it: set UNITRADING_LICENSE_KEY to run. */
    @Test fun productionVerifierAcceptsTheKeyFromTheEnvironment() = runBlocking<Unit> {
        val key = System.getenv("UNITRADING_LICENSE_KEY")
        assumeTrue("UNITRADING_LICENSE_KEY not set", key != null)
        assertNotNull(OfflineLicenseVerifier().verify(key!!))
    }
}
