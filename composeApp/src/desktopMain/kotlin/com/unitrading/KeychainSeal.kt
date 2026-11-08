package com.unitrading

import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface Keychain {
    fun read(service: String, account: String): String?
    fun write(service: String, account: String, secret: String)
    fun delete(service: String, account: String)
}

/**
 * Each file is AES-256-GCM under a random key kept in the user's login keychain, per data folder and never synced.
 * [keychain] defaults to Apple's `security` tool because a keychain item trusts the program that created it: this
 * app's ad-hoc signature changes with every build, so items it created itself would ask for a password after each update.
 */
internal class KeychainSeal(directory: Path, fileName: String, private val keychain: Keychain = SecurityToolKeychain()) : CredentialSeal {
    private val account = fileName + "@" + MessageDigest.getInstance("SHA-256")
        .digest(directory.toAbsolutePath().normalize().toString().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    private val random = SecureRandom()

    override fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return byteArrayOf(FORMAT) + nonce + cipher(Cipher.ENCRYPT_MODE, existingOrNewKey(), nonce).doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        check(sealed.size > 1 + NONCE_BYTES && sealed[0] == FORMAT) { "凭据文件格式无效" }
        val key = checkNotNull(keychain.read(SERVICE, account)?.let(::decodeKey)) { "钥匙串中没有此文件的密钥" }
        val nonce = sealed.copyOfRange(1, 1 + NONCE_BYTES)
        return cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(sealed, 1 + NONCE_BYTES, sealed.size - 1 - NONCE_BYTES)
    }

    /** Best effort: once the file is gone the key protects nothing. */
    override fun forget() { runCatching { keychain.delete(SERVICE, account) } }

    private fun existingOrNewKey(): SecretKeySpec {
        keychain.read(SERVICE, account)?.let { return decodeKey(it) }
        val encoded = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))
        keychain.write(SERVICE, account, encoded)
        // `security -i` does not report a failed command in its exit status.
        check(keychain.read(SERVICE, account) == encoded) { "无法写入 macOS 钥匙串" }
        return decodeKey(encoded)
    }

    private fun decodeKey(encoded: String) = SecretKeySpec(Base64.getDecoder().decode(encoded).also { check(it.size == KEY_BYTES) { "钥匙串中的密钥无效" } }, "AES")
    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)) }

    private companion object {
        const val SERVICE = "com.unitrading.credentials.v1"
        const val FORMAT: Byte = 1
        const val NONCE_BYTES = 12
        const val KEY_BYTES = 32
    }
}

internal class SecurityToolResult(val exit: Int, val output: String)

/** Item-not-found is exit status 44. A secret travels on stdin only: the arguments of a running process are visible to others. */
internal class SecurityToolKeychain(private val run: (List<String>, String?) -> SecurityToolResult = ::runSecurityTool) : Keychain {
    override fun read(service: String, account: String): String? {
        val result = run(listOf("find-generic-password", "-s", service, "-a", account, "-w"), null)
        return when (result.exit) {
            0 -> result.output.trim()
            ITEM_NOT_FOUND -> null
            else -> error("无法读取 macOS 钥匙串（${result.exit}）")
        }
    }

    override fun write(service: String, account: String, secret: String) {
        run(listOf("-q", "-i"), "add-generic-password -U -s \"$service\" -a \"$account\" -w \"$secret\"\n")
    }

    override fun delete(service: String, account: String) {
        val result = run(listOf("delete-generic-password", "-s", service, "-a", account), null)
        check(result.exit == 0 || result.exit == ITEM_NOT_FOUND) { "无法删除 macOS 钥匙串项（${result.exit}）" }
    }

    private companion object { const val ITEM_NOT_FOUND = 44 }
}

// Output is one short line, so reading it after exit cannot fill the pipe. The timeout covers a locked keychain's dialog.
private fun runSecurityTool(arguments: List<String>, input: String?): SecurityToolResult {
    val process = ProcessBuilder(listOf("/usr/bin/security") + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("macOS 钥匙串操作超时")
    }
    return SecurityToolResult(process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
}
