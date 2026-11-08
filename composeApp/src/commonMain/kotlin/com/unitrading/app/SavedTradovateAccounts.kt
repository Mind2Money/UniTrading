package com.unitrading.app

import com.unitrading.broker.BrokerPlugin
import com.unitrading.broker.BrokerRegistry
import com.unitrading.broker.CandleCacheStore
import com.unitrading.rithmic.RithmicBroker
import com.unitrading.rithmic.RithmicCredentials
import com.unitrading.rithmic.RithmicRemoteException
import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** Implementations encrypt the entire payload with the platform's credential protection. */
interface CredentialStore {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}

@Serializable
private class SavedConnections(
    val version: Int = 1,
    val sessions: List<TradovateSession> = emptyList(),
    val brokerId: String? = null,
    val accounts: Map<String, String> = emptyMap(),
    /** Rithmic logins the user asked to remember; the password is part of the encrypted payload. */
    val rithmic: List<RithmicCredentials> = emptyList(),
)

internal val TradovateSession.brokerId get() = "tradovate-${environment.wire}-$userId"

/** Saved remote logins (Tradovate sessions and remembered Rithmic credentials) and their restoration. */
class SavedTradovateAccounts(
    private val registry: BrokerRegistry,
    private val store: CredentialStore?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    candleCacheStore: CandleCacheStore? = null,
    private val connectRithmic: suspend (RithmicCredentials, (String) -> Unit) -> BrokerPlugin = { credentials, progress ->
        val broker = RithmicBroker(credentials, candleCacheStore = candleCacheStore)
        try { broker.connect(progress); broker }
        catch (error: Exception) { broker.disconnect(); throw error }
    },
    private val connect: suspend (TradovateSession, suspend (TradovateSession) -> Unit, (String) -> Unit) -> BrokerPlugin = { session, renewed, progress ->
        val broker = TradovateBroker(session, onSessionRenewed = renewed, candleCacheStore = candleCacheStore)
        try { broker.connect(progress); broker }
        catch (error: Exception) { broker.disconnect(); throw error }
    },
) {
    val available get() = store != null
    val message = MutableStateFlow<String?>(null)
    val restoring = MutableStateFlow(false)
    val restoredBrokerId = MutableStateFlow<String?>(null)
    val savedCount = MutableStateFlow(0)
    private val operations = Mutex()
    private val storage = Mutex()
    private var loaded = false
    private var initialized = false
    private var unreadable = false
    private var saveFailed = false
    private var state = SavedConnections()
    // Prevent callbacks from a replaced/disconnected connection from restoring old credentials.
    private val generations = mutableMapOf<String, Any>()

    fun selectedAccount(brokerId: String): String? = state.accounts[brokerId]

    private suspend fun persist() {
        savedCount.value = state.sessions.size + state.rithmic.size
        try {
            store?.write(Json.encodeToString(state))
            if (saveFailed) message.value = null
            saveFailed = false
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { saveFailed = true; message.value = "登录状态保存失败，当前连接仍可使用。请检查设备存储后重试。" }
    }

    private class RestoreTask(val brokerId: String, val label: String, val expiredMessage: String?, val establish: suspend ((String) -> Unit) -> BrokerPlugin)

    suspend fun restore() = operations.withLock {
        if (loaded || store == null) return@withLock
        restoring.value = true
        try {
            if (!initialized) {
                val data = try { store.read() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message.value = "无法读取保存的登录信息，请重新登录或清除已保存登录。"; unreadable = true; loaded = true; return@withLock }
                try {
                    state = data?.let { Json.decodeFromString<SavedConnections>(it) } ?: SavedConnections()
                    check(state.version == 1 && (state.sessions.map { it.brokerId } + state.rithmic.map { it.brokerId }).let { it.distinct().size == it.size })
                    unreadable = false
                    initialized = true
                } catch (_: Exception) {
                    state = SavedConnections()
                    message.value = "保存的登录信息已损坏，原文件未覆盖。请重新登录或清除已保存登录。"
                    loaded = true
                    unreadable = true
                    return@withLock
                }
            }
            storage.withLock { if (saveFailed) persist() }
            savedCount.value = state.sessions.size + state.rithmic.size
            val (tasks, preferredBrokerId) = storage.withLock {
                val tradovate = state.sessions.map { session ->
                    RestoreTask(session.brokerId, "Tradovate ${session.environment.wire}",
                        if (session.expiresAtMs?.let { it > now() } != true) "登录已过期，请重新登录" else null) { progress -> establish(session, progress) }
                }
                val rithmic = state.rithmic.map { credentials ->
                    RestoreTask(credentials.brokerId, "Rithmic ${credentials.systemName}", null) { progress -> establishRithmic(credentials, progress) }
                }
                (tradovate + rithmic) to state.brokerId
            }
            val slots = Semaphore(3)
            val registration = Mutex()
            val failures = coroutineScope { tasks.sortedBy { it.brokerId != preferredBrokerId }.map { task -> async { slots.withPermit {
                val label = task.label
                task.expiredMessage?.let { return@withPermit "$label $it" }
                if (registry.plugins.any { it.descriptor.id == task.brokerId && it.market.value.connected }) return@withPermit null
                if (!saveFailed) message.value = "正在恢复 $label 已保存登录…"
                var candidate: BrokerPlugin? = null
                var registered = false
                try {
                    val broker = task.establish { if (!saveFailed) message.value = it }
                    candidate = broker
                    registration.withLock {
                        registry.register(broker)
                        registered = true
                        if (task.brokerId == preferredBrokerId) restoredBrokerId.value = preferredBrokerId
                    }
                    null
                } catch (_: TimeoutCancellationException) {
                    "$label 连接超时，已保留登录，可重试恢复"
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    if (error is TradovateRemoteException && error.status in listOf(401, 403)) "$label 授权已失效，请重新登录"
                    else if (error is TradovateRemoteException && error.status in 300..399) "$label 服务器地址已变更，请重新登录"
                    else if (error is RithmicRemoteException && error.code != "closed") "$label 登录被拒绝：${error.message}"
                    else "$label 暂时无法连接，已保留登录，可重试恢复"
                } finally { if (!registered) candidate?.disconnect() }
            } } }.awaitAll().filterNotNull() }
            restoredBrokerId.value = state.brokerId?.takeIf { id -> registry.plugins.any { it.descriptor.id == id } }
            if (!saveFailed) message.value = failures.takeIf { it.isNotEmpty() }?.joinToString("；")
            loaded = true
        } finally { restoring.value = false }
    }

    private suspend fun establish(session: TradovateSession, progress: (String) -> Unit): BrokerPlugin {
        val generation = Any()
        storage.withLock { generations[session.brokerId] = generation }
        var candidate: BrokerPlugin? = null
        try { return withTimeout(90_000) {
            connect(session, { renewed ->
                storage.withLock {
                    if (generations[session.brokerId] === generation && state.sessions.any { it.brokerId == renewed.brokerId }) {
                        state = SavedConnections(sessions = state.sessions.filterNot { it.brokerId == renewed.brokerId } + renewed,
                            brokerId = state.brokerId, accounts = state.accounts, rithmic = state.rithmic)
                        persist()
                    }
                }
            }, progress).also { candidate = it }
        } } catch (error: Exception) {
            candidate?.disconnect()
            withContext(NonCancellable) { storage.withLock {
                if (generations[session.brokerId] === generation) generations.remove(session.brokerId)
            } }
            throw error
        }
    }

    private suspend fun establishRithmic(credentials: RithmicCredentials, progress: (String) -> Unit): BrokerPlugin {
        var candidate: BrokerPlugin? = null
        try { return withTimeout(120_000) { connectRithmic(credentials, progress).also { candidate = it } } }
        catch (error: Exception) { candidate?.disconnect(); throw error }
    }

    suspend fun signIn(session: TradovateSession, progress: (String) -> Unit): String = operations.withLock {
        // Preserve a successful browser login even when account discovery is temporarily offline.
        storage.withLock {
            generations.remove(session.brokerId)
            state = SavedConnections(sessions = state.sessions.filterNot { it.brokerId == session.brokerId } + session,
                brokerId = session.brokerId, accounts = state.accounts, rithmic = state.rithmic)
            message.value = null
            unreadable = false
            initialized = true
            persist()
        }
        val broker = establish(session, progress)
        registry.register(broker)
        broker.descriptor.id
    }

    /** Connects first; credentials are written to the encrypted store only after the login worked and only when [remember] is set. */
    suspend fun signInRithmic(credentials: RithmicCredentials, remember: Boolean, progress: (String) -> Unit): String = operations.withLock {
        val broker = establishRithmic(credentials, progress)
        registry.register(broker)
        storage.withLock {
            val kept = state.rithmic.filterNot { it.brokerId == credentials.brokerId }
            state = SavedConnections(sessions = state.sessions, brokerId = credentials.brokerId, accounts = state.accounts,
                rithmic = if (remember) kept + credentials else kept)
            message.value = null
            if (remember || !unreadable) { unreadable = false; initialized = true; persist() }
        }
        broker.descriptor.id
    }

    suspend fun select(brokerId: String, accountId: String? = null) = operations.withLock { storage.withLock {
        state = SavedConnections(sessions = state.sessions, brokerId = brokerId,
            accounts = if (accountId == null) state.accounts else state.accounts + (brokerId to accountId), rithmic = state.rithmic)
        if (!unreadable && (loaded || state.sessions.isNotEmpty() || state.rithmic.isNotEmpty())) persist()
    } }

    suspend fun retry() { operations.withLock { loaded = false }; restore() }

    suspend fun forgetAll(): Boolean = operations.withLock {
        storage.withLock clear@{
            try { store?.clear() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message.value = "清除已保存登录失败，请重试。"; return@clear false }
            generations.clear()
            state.sessions.forEach { session -> registry.unregister(session.brokerId) }
            state.rithmic.forEach { credentials -> registry.unregister(credentials.brokerId) }
            state = SavedConnections()
            savedCount.value = 0
            restoredBrokerId.value = null
            message.value = null
            loaded = true
            initialized = true
            unreadable = false
            saveFailed = false
            true
        }
    }
}
