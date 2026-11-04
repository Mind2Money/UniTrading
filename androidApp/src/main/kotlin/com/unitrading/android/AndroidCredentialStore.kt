package com.unitrading.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.unitrading.app.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Each store has its own file and non-exportable Keystore key; the defaults hold the saved broker logins. */
class AndroidCredentialStore(
    context: Context,
    fileName: String = "tradovate-credentials-v1.enc",
    private val alias: String = "unitrading.tradovate.sessions.v1",
) : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return@withContext null
        }
        check(bytes.size in 30..4_000_000 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        val plain = value.toByteArray(Charsets.UTF_8)
        val bytes = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
        // Retaining the non-exportable key avoids affecting an in-flight atomic write.
    }

    companion object {
        /** License activation record; kept out of backups like the logins, and bound to this device's Keystore. */
        const val LICENSE_FILE = "license-v1.enc"
        const val LICENSE_KEY_ALIAS = "unitrading.license.v1"
    }
}
