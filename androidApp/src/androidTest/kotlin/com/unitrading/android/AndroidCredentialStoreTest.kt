package com.unitrading.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AndroidCredentialStoreTest {
    @Test fun encryptedSessionSurvivesRecreationAndRejectsTampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".credentialtest")) { "Use -PcredentialStoreTest=true" }
        val store = AndroidCredentialStore(context)
        val file = File(context.noBackupFilesDir, "tradovate-credentials-v1.enc")
        val fake = "synthetic-credential-test-only-1234567890"
        try {
            store.clear()
            assertNull(store.read())
            store.write(fake)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(fake))
            assertEquals(fake, AndroidCredentialStore(context).read())
            store.write("updated-$fake")
            assertEquals("updated-$fake", AndroidCredentialStore(context).read())
            val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(damaged)
            assertTrue(runCatching { store.read() }.isFailure)
            assertArrayEquals(damaged, file.readBytes())
            store.clear()
            assertNull(AndroidCredentialStore(context).read())
        } finally { store.clear() }
    }

    @Test fun licenseRecordUsesItsOwnFileAndKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".credentialtest"))
        val logins = AndroidCredentialStore(context)
        val license = AndroidCredentialStore(context, AndroidCredentialStore.LICENSE_FILE, AndroidCredentialStore.LICENSE_KEY_ALIAS)
        val loginFile = File(context.noBackupFilesDir, "tradovate-credentials-v1.enc")
        val hadLogins = loginFile.exists()
        try {
            license.write("synthetic-license-record")
            assertEquals("synthetic-license-record", AndroidCredentialStore(context, AndroidCredentialStore.LICENSE_FILE, AndroidCredentialStore.LICENSE_KEY_ALIAS).read())
            assertFalse(File(context.noBackupFilesDir, AndroidCredentialStore.LICENSE_FILE).readBytes().toString(Charsets.UTF_8).contains("synthetic-license-record"))
            assertEquals(hadLogins, loginFile.exists())
            assertTrue(java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(AndroidCredentialStore.LICENSE_KEY_ALIAS))
            if (!hadLogins) assertNull(logins.read())
        } finally { license.clear() }
    }

    /** Run these two methods in separate instrumentation processes to check process-death recovery. */
    @Test fun processRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".credentialtest"))
        val phase = InstrumentationRegistry.getArguments().getString("credentialPhase") ?: "roundtrip"
        check(phase in setOf("write", "read", "roundtrip"))
        val store = AndroidCredentialStore(context)
        if (phase != "read") store.write("synthetic-process-restart-1234567890")
        if (phase != "write") {
            assertEquals("synthetic-process-restart-1234567890", AndroidCredentialStore(context).read())
            store.clear()
        }
    }
}
