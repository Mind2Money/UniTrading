package com.unitrading.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * An accepted license key (授权码), shipped only as a salted PBKDF2-HMAC-SHA256 verifier. [id] names the entitlement
 * in activation records; removing an entry in a release revokes the activations made with it.
 */
class LicenseKey(val id: String, salt: String, private val iterations: Int, verifier: String) {
    private val salt = salt.hexToBytes()
    private val verifier = verifier.hexToBytes()
    init { require(id.isNotBlank() && iterations >= 1 && this.salt.size >= 16 && this.verifier.size == 32) }
    internal fun matches(key: ByteArray): Boolean = constantTimeEquals(pbkdf2HmacSha256(key, salt, iterations, verifier.size), verifier)
}

/** Generated with `scripts/license-key.py`; plaintext keys never enter the repository or the app. */
val ProductionLicenseKeys = listOf(
    LicenseKey("key-2026-11", salt = "7580fe32f051b04b622484f9f8b80f07", iterations = 600000, verifier = "506b0f26b61c5d562c8a572d607d9d65d92f68f7041f42762e032e4988b36d16"),
)

/** Decides whether a key grants use of the app. An online verifier (server-signed, revocable licenses) can replace the offline one. */
interface LicenseVerifier {
    /** The entitlement id [key] grants, or null when it is not accepted. */
    suspend fun verify(key: String): String?
    /** Whether an activation recorded under [id] is still honoured. */
    fun accepts(id: String): Boolean
}

class OfflineLicenseVerifier(private val keys: List<LicenseKey> = ProductionLicenseKeys) : LicenseVerifier {
    override suspend fun verify(key: String): String? {
        val normalized = key.trim()
        if (normalized.isEmpty() || normalized.length > MAX_KEY_LENGTH) return null
        return withContext(Dispatchers.Default) {
            val bytes = normalized.encodeToByteArray()
            try {
                // Every entry is checked so the time taken does not reveal which one matched.
                keys.fold<LicenseKey, String?>(null) { found, entry -> if (entry.matches(bytes)) found ?: entry.id else found }
            } finally { bytes.fill(0) }
        }
    }
    override fun accepts(id: String) = keys.any { it.id == id }
    private companion object { const val MAX_KEY_LENGTH = 256 }
}

/** Consecutive failures allowed before a lock, then locks doubling from [firstLockMs] up to [maxLockMs]. */
class LockoutPolicy(val freeAttempts: Int = 5, val firstLockMs: Long = 30_000, val maxLockMs: Long = 15 * 60_000) {
    fun lockMs(failures: Int): Long =
        if (failures < freeAttempts) 0 else minOf(maxLockMs, firstLockMs shl minOf(failures - freeAttempts, 20))
}

enum class LicenseStatus { CHECKING, REQUIRED, VERIFYING, ACTIVE }

/** [message] explains the last refusal; [notice] is shown once the app is unlocked (activation not saved). */
data class LicenseState(
    val status: LicenseStatus = LicenseStatus.CHECKING,
    val message: String? = null,
    val lockedUntilMs: Long = 0,
    val notice: String? = null,
)

@Serializable
private data class LicenseActivation(val keyId: String, val activatedAtMs: Long)

/** Encrypted by the platform [CredentialStore], so a record cannot be read, edited or copied to another device. */
@Serializable
private data class LicenseDocument(
    val version: Int = 1,
    val activation: LicenseActivation? = null,
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0,
)

/**
 * Activation state of this installation. The key is verified once; the device-bound record of that, never the key,
 * is stored and checked against [verifier] on each start. Failed attempts are counted in the same record, so
 * restarting the app does not reset a lock. Without a [store] an activation lasts for this process only.
 */
class LicenseController(
    private val store: CredentialStore?,
    private val verifier: LicenseVerifier = OfflineLicenseVerifier(),
    private val policy: LockoutPolicy = LockoutPolicy(),
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(LicenseState())
    val state: StateFlow<LicenseState> = mutableState.asStateFlow()
    private val operations = Mutex()
    private var document = LicenseDocument()
    private var loaded = false

    suspend fun load() = operations.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        var message: String? = null
        document = try {
            store?.read()?.let { Json.decodeFromString<LicenseDocument>(it) }?.also { check(it.version == 1) } ?: LicenseDocument()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            message = "无法读取本机授权记录，请重新输入授权码。"
            LicenseDocument()
        }
        loaded = true
        val activation = document.activation
        mutableState.value = when {
            activation != null && verifier.accepts(activation.keyId) -> LicenseState(LicenseStatus.ACTIVE)
            activation != null -> {
                document = document.copy(activation = null)
                LicenseState(LicenseStatus.REQUIRED, "授权码已更换，请输入新的授权码。")
            }
            else -> LicenseState(LicenseStatus.REQUIRED, message, lockedUntil())
        }
    }

    /** Verifies [key] unless attempts are locked; true when the app is now unlocked. */
    suspend fun activate(key: String): Boolean = operations.withLock {
        loadLocked()
        if (mutableState.value.status == LicenseStatus.ACTIVE) return@withLock true
        val lockedUntil = lockedUntil()
        if (lockedUntil > clock()) {
            mutableState.value = LicenseState(LicenseStatus.REQUIRED, "尝试次数过多，请稍后重试。", lockedUntil)
            return@withLock false
        }
        if (key.isBlank()) {
            mutableState.value = LicenseState(LicenseStatus.REQUIRED, "请输入授权码。")
            return@withLock false
        }
        mutableState.value = LicenseState(LicenseStatus.VERIFYING)
        val granted = try { verifier.verify(key) }
        catch (cancel: CancellationException) { mutableState.value = LicenseState(LicenseStatus.REQUIRED); throw cancel }
        catch (_: Exception) {
            // A verifier fault (an online verifier's network error) is not a wrong key and is not counted.
            mutableState.value = LicenseState(LicenseStatus.REQUIRED, "暂时无法验证授权码，请重试。")
            return@withLock false
        }
        val now = clock()
        if (granted == null) {
            val failures = document.failedAttempts + 1
            val lock = policy.lockMs(failures)
            document = document.copy(failedAttempts = failures, lockedUntilMs = if (lock > 0) now + lock else 0)
            persist()
            val left = policy.freeAttempts - failures
            mutableState.value = LicenseState(LicenseStatus.REQUIRED,
                if (lock > 0) "授权码无效，尝试次数过多。" else "授权码无效，还可尝试 $left 次。", document.lockedUntilMs)
            return@withLock false
        }
        document = LicenseDocument(activation = LicenseActivation(granted, now))
        val saved = persist()
        mutableState.value = LicenseState(LicenseStatus.ACTIVE,
            notice = if (saved) null else "授权已通过，但未能保存到本机，下次启动需要重新输入授权码。")
        true
    }

    fun dismissNotice() { mutableState.value = mutableState.value.copy(notice = null) }

    /** A lock never extends past [LockoutPolicy.maxLockMs] from now, so moving the clock back cannot prolong it. */
    private fun lockedUntil(): Long {
        val cap = clock() + policy.maxLockMs
        if (document.lockedUntilMs > cap) document = document.copy(lockedUntilMs = cap)
        return document.lockedUntilMs
    }

    private suspend fun persist(): Boolean = try {
        store?.write(Json.encodeToString(document)) != null
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { false }
}
