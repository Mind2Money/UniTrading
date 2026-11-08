package com.unitrading

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopCredentialStoreTest {
    @Test fun sealedFileSurvivesNewInstanceWithoutPlaintextAndRejectsTampering() = runBlocking {
        assumeTrue(isWindows || isMacOs)
        val directory = Files.createTempDirectory("unitrading-credentials-test-")
        try {
            val store = DesktopCredentialStore(directory)
            assertNull(store.read())
            val fixture = "fake-credential-for-encryption-test-only"
            store.write(fixture)
            val file = directory.resolve("tradovate.dpapi")
            assertFalse(Files.readAllBytes(file).toString(Charsets.UTF_8).contains(fixture))
            assertEquals(fixture, DesktopCredentialStore(directory).read())
            store.write("updated-$fixture")
            assertEquals("updated-$fixture", DesktopCredentialStore(directory).read())
            val corrupted = Files.readAllBytes(file).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            Files.write(file, corrupted)
            assertFails { store.read() }
            assertContentEquals(corrupted, Files.readAllBytes(file))
            store.clear()
            assertNull(DesktopCredentialStore(directory).read())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun licenseRecordIsIndependentOfSavedLogins() = runBlocking {
        assumeTrue(isWindows || isMacOs)
        val directory = Files.createTempDirectory("unitrading-license-test-")
        try {
            val logins = DesktopCredentialStore(directory)
            val license = DesktopCredentialStore(directory, DesktopCredentialStore.LICENSE_FILE)
            logins.write("synthetic-login")
            license.write("synthetic-license-record")
            assertEquals("synthetic-login", DesktopCredentialStore(directory).read())
            assertEquals("synthetic-license-record", DesktopCredentialStore(directory, DesktopCredentialStore.LICENSE_FILE).read())
            assertFalse(Files.readAllBytes(directory.resolve("license.dpapi")).toString(Charsets.UTF_8).contains("synthetic-license-record"))
            logins.clear()
            assertEquals("synthetic-license-record", license.read())
            license.clear()
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

class KeychainSealTest {
    private class FakeKeychain : Keychain {
        val items = mutableMapOf<Pair<String, String>, String>()
        var writes = 0
        var dropWrites = false
        override fun read(service: String, account: String) = items[service to account]
        override fun write(service: String, account: String, secret: String) { writes++; if (!dropWrites) items[service to account] = secret }
        override fun delete(service: String, account: String) { items.remove(service to account) }
    }
    private val directory = Path.of("synthetic", "credentials-v1")
    private val plain = "fake-credential-for-encryption-test-only".toByteArray()

    @Test fun roundTripsUnderOneKeyWithoutPlaintext() {
        val keychain = FakeKeychain()
        val first = KeychainSeal(directory, "tradovate.dpapi", keychain).seal(plain)
        val second = KeychainSeal(directory, "tradovate.dpapi", keychain).seal(plain)
        assertEquals(1, keychain.writes, "The key is created once")
        assertFalse(first.contentEquals(second), "Each write has its own nonce")
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(plain.toString(Charsets.ISO_8859_1)))
        assertContentEquals(plain, KeychainSeal(directory, "tradovate.dpapi", keychain).open(first))
    }

    @Test fun filesAndDataFoldersHaveTheirOwnKeys() {
        val keychain = FakeKeychain()
        KeychainSeal(directory, "tradovate.dpapi", keychain).seal(plain)
        KeychainSeal(directory, "license.dpapi", keychain).seal(plain)
        KeychainSeal(Path.of("other", "credentials-v1"), "tradovate.dpapi", keychain).seal(plain)
        assertEquals(3, keychain.items.size)
    }

    @Test fun tamperingOrAMissingKeyNeverYieldsData() {
        val keychain = FakeKeychain()
        val seal = KeychainSeal(directory, "tradovate.dpapi", keychain)
        val sealed = seal.seal(plain)
        assertFails { seal.open(sealed.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
        assertFails { seal.open(sealed.clone().apply { this[0] = 9 }) }
        assertFails { seal.open(ByteArray(5)) }
        seal.forget()
        assertTrue(keychain.items.isEmpty())
        assertFails { seal.open(sealed) }
    }

    @Test fun aKeyTheKeychainDidNotKeepFailsTheWrite() {
        val keychain = FakeKeychain().apply { dropWrites = true }
        assertFails { KeychainSeal(directory, "tradovate.dpapi", keychain).seal(plain) }
    }

    @Test fun securityToolGetsTheSecretOnStdinAndMapsItsExitStatus() {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        var result = SecurityToolResult(0, "stored-secret\n")
        val keychain = SecurityToolKeychain { arguments, input -> calls.add(arguments to input); result }
        assertEquals("stored-secret", keychain.read("service", "account"))
        result = SecurityToolResult(44, "")
        assertNull(keychain.read("service", "account"))
        keychain.delete("service", "account")
        result = SecurityToolResult(36, "")
        assertFails { keychain.read("service", "account") }
        assertFails { keychain.delete("service", "account") }
        calls.clear()
        keychain.write("service", "account", "new-secret-value")
        val (arguments, input) = calls.single()
        assertFalse(arguments.any { "new-secret-value" in it }, "Arguments are visible to other processes")
        assertEquals("add-generic-password -U -s \"service\" -a \"account\" -w \"new-secret-value\"\n", input)
    }
}
