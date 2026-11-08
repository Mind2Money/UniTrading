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

/** Seals one credential file for the current OS user. There is no plaintext fallback. */
interface CredentialSeal {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun forget() {}
}

private object DpapiSeal : CredentialSeal {
    override fun seal(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
    override fun open(sealed: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(sealed, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
}

/** DPAPI on Windows, [KeychainSeal] on macOS. The `.dpapi` file names predate macOS and are kept on every platform. */
class DesktopCredentialStore(
    private val directory: Path = defaultCredentialDirectory(),
    private val fileName: String = "tradovate.dpapi",
    private val sealing: CredentialSeal = if (isMacOs) KeychainSeal(directory, fileName) else DpapiSeal,
) : CredentialStore {
    private val file get() = directory.resolve(fileName)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext null
        check(Files.size(file) <= 4_000_000)
        val plain = sealing.open(Files.readAllBytes(file))
        try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.IO) {
        val plain = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { sealing.seal(plain) } finally { plain.fill(0) }
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "login-", ".tmp")
        try {
            Files.write(temp, encrypted)
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    override suspend fun clear() { withContext(Dispatchers.IO) { Files.deleteIfExists(file); sealing.forget() } }

    companion object {
        /** License activation record, separate from saved logins so clearing one never touches the other. */
        const val LICENSE_FILE = "license.dpapi"
    }
}

private fun defaultCredentialDirectory(): Path =
    (System.getProperty("unitrading.dataDir") ?: System.getenv("UNITRADING_DATA_DIR"))?.let { Path.of(it, "credentials-v1") }
        ?: userDataRoot().resolve("UniTrading").resolve("credentials-v1")
