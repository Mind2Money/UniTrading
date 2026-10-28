package com.unitrading

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

class DesktopCredentialStoreTest {
    @Test fun dpapiSurvivesNewInstanceWithoutPlaintextAndRejectsTampering() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
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
}
