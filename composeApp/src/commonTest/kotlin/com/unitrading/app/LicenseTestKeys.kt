package com.unitrading.app

/** A license key for tests, derived the way scripts/license-key.py does but with few iterations to stay fast. */
internal fun testLicenseKey(id: String, key: String, iterations: Int = 1_000): LicenseKey {
    val salt = ByteArray(16) { (it * 7 + id.length).toByte() }
    return LicenseKey(id, salt.toHex(), iterations, pbkdf2HmacSha256(key.encodeToByteArray(), salt, iterations, 32).toHex())
}

/** In-memory [CredentialStore] with switchable failures. */
internal class TestLicenseStore : CredentialStore {
    var value: String? = null
    var failRead = false
    var failWrite = false
    override suspend fun read(): String? { check(!failRead) { "unreadable" }; return value }
    override suspend fun write(value: String) { check(!failWrite) { "read-only" }; this.value = value }
    override suspend fun clear() { check(!failWrite) { "read-only" }; value = null }
}
