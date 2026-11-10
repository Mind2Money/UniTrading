package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** Saved under live-pnl-v1. On by default so a signed-in account reaches the system surface without setup. */
@Serializable
data class LivePnlSettings(val enabled: Boolean = true)

/** One rendering of the displayed account for the platform notice (Android Live Update, iOS Live Activity). */
data class LivePnlUpdate(
    val brokerId: String,
    val accountId: String,
    /** "Login · Account", each by the alias the user gave it where there is one. */
    val title: String,
    /** Signed floating P&L with currency, or "—" when balance or equity is unknown. */
    val pnlText: String,
    val pnlMicros: Long?,
    /** Equity, open position count and connection state. */
    val detail: String,
    val connected: Boolean,
    /** When the snapshot was rendered; platforms show it as the notice time. */
    val timeMs: Long,
) {
    /** 1 profit, -1 loss, 0 flat or unknown. */
    val trend: Int get() = when { pnlMicros == null || pnlMicros == 0L -> 0; pnlMicros > 0 -> 1; else -> -1 }
    /** Everything a platform renders; equal keys mean the notice would look the same. */
    val key: String get() = "$brokerId/$accountId|$title|$pnlText|$detail"

    companion object {
        /** [names] puts the user's aliases of the login and the account in the title. */
        fun from(broker: BrokerDescriptor, account: AccountInfo, snapshot: AccountSnapshot, connected: Boolean, nowMs: Long,
            names: AccountAliases = AccountAliases()): LivePnlUpdate {
            val pnl = snapshot.floatingPnlMicros.takeIf { snapshot.balanceKnown && snapshot.equityKnown }
            val detail = buildString {
                append(if (snapshot.equityKnown) "净值 ${money(snapshot.equityMicros)}" else "净值未知")
                if (snapshot.equityKnown && snapshot.equityEstimated) append("（估算）")
                append(" · 持仓 ${snapshot.positions.size}")
                if (!connected) append(" · 已断开")
            }
            val text = if (pnl == null) "—" else "${money(pnl, signed = true)} ${account.currency}"
            return LivePnlUpdate(broker.id, account.id, "${names.login(broker)} · ${names.account(broker.id, account)}", text, pnl, detail, connected, nowMs)
        }
    }
}

/** Platform surface: [show] replaces the single notice, [clear] removes it. Called on the thread running [LivePnlController.run]. */
interface LivePnlPresenter {
    fun show(update: LivePnlUpdate)
    fun clear()
}

/**
 * Mirrors the account the UI currently displays to the platform notice. The UI calls [track] / [untrack]; the
 * platform runs [run] once with its presenter. A refresh is posted when the rendered text changes, at most once
 * per [MIN_INTERVAL_MS], and repeated every [HEARTBEAT_MS] so platform expiry timers keep a quiet account alive.
 */
class LivePnlController(
    private val store: SnapshotStore,
    private val writer: CoroutineDispatcher = Dispatchers.Default,
    /** The user's aliases; renaming the shown login or account refreshes the notice. */
    private val names: StateFlow<AccountAliases> = MutableStateFlow(AccountAliases()),
) {
    private class Tracked(val plugin: BrokerPlugin, val session: AccountSession)

    val error = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(try {
        store.read(KEY)?.let { Json.decodeFromString<LivePnlSettings>(it) } ?: LivePnlSettings()
    } catch (_: Exception) {
        error.value = "通知设置无法读取，已使用默认值。"
        LivePnlSettings()
    })
    /** True while a platform presenter runs; the settings switch is only shown then. */
    val available = MutableStateFlow(false)
    private val source = MutableStateFlow<Tracked?>(null)

    fun track(plugin: BrokerPlugin, session: AccountSession) { source.value = Tracked(plugin, session) }
    /** Stops mirroring [session] unless another account already replaced it. */
    fun untrack(session: AccountSession) { source.update { if (it?.session === session) null else it } }

    suspend fun setEnabled(enabled: Boolean): Boolean {
        val value = LivePnlSettings(enabled)
        return try {
            withContext(writer) { store.write(KEY, Json.encodeToString(value)) }
            settings.value = value
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "通知设置保存失败，请检查存储后重试。"; false }
    }

    /** Runs until cancelled; [nowMs] stamps each update (tests pass virtual time). The notice is cleared on exit. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(presenter: LivePnlPresenter, nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Unit = coroutineScope {
        val latest = MutableStateFlow<LivePnlUpdate?>(null)
        val feed = launch {
            combine(settings, source) { current, tracked -> tracked.takeIf { current.enabled } }
                .flatMapLatest { tracked ->
                    if (tracked == null) flowOf(null)
                    else combine(tracked.session.state, tracked.plugin.market, names) { account, market, aliases ->
                        LivePnlUpdate.from(tracked.plugin.descriptor, tracked.session.account, account, market.connected, nowMs(), aliases)
                    }
                }
                .collect { latest.value = it }
        }
        available.value = true
        try {
            var shown: LivePnlUpdate? = null
            while (true) {
                withTimeoutOrNull(HEARTBEAT_MS) { latest.first { it?.key != shown?.key } }
                val current = latest.value
                if (current == null) {
                    if (shown != null) { presenter.safely { clear() }; shown = null }
                    continue
                }
                presenter.safely { show(current) }
                shown = current
                delay(MIN_INTERVAL_MS)
            }
        } finally {
            feed.cancel()
            available.value = false
            presenter.safely { clear() }
        }
    }

    private inline fun LivePnlPresenter.safely(block: LivePnlPresenter.() -> Unit) {
        try { block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* A platform failure must not stop trading. */ }
    }

    companion object {
        const val KEY = "live-pnl-v1"
        const val MIN_INTERVAL_MS = 1_000L
        const val HEARTBEAT_MS = 20_000L
    }
}
