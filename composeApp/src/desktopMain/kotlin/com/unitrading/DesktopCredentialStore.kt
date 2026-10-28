package com.unitrading

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import com.unitrading.app.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

/** DPAPI is scoped to the current Windows user, with no plaintext fallback. */
class DesktopCredentialStore(private val directory: Path = defaultCredentialDirectory()) : CredentialStore {
    private val file get() = directory.resolve("tradovate.dpapi")

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext null
        check(Files.size(file) <= 4_000_000)
        val plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
        try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.IO) {
        val plain = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN) } finally { plain.fill(0) }
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "login-", ".tmp")
        try {
            Files.write(temp, encrypted)
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    override suspend fun clear() { withContext(Dispatchers.IO) { Files.deleteIfExists(file) } }
}

private fun defaultCredentialDirectory(): Path =
    (System.getProperty("unitrading.dataDir") ?: System.getenv("UNITRADING_DATA_DIR"))?.let { Path.of(it, "credentials-v1") }
        ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "UniTrading", "credentials-v1")
