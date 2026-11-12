package com.unitrading.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The pair of keys that buys and sells at the market on the chart; [buy] and [sell] are how the interface writes them. */
@Serializable
enum class TradingKeys(val buy: String, val sell: String) { LETTERS("W", "S"), ARROWS("↑", "↓") }

/**
 * The order page of phones and compact windows; [summary] is the line the settings show under [label]. [SHEET] slides
 * up over the chart, [DOCKED] takes its own space below the chart, or beside it in landscape, and [POPUP] is a dialog
 * over the chart.
 */
@Serializable
enum class OrderPanelLayout(val label: String, val summary: String) {
    SHEET("上滑面板", "从底部上滑，可拖动收起"),
    DOCKED("停靠下单栏", "停在图表下方或右侧，不遮挡 K 线"),
    POPUP("独立弹窗", "在对话框中下单"),
}

/**
 * [keyboardTrading] lets the chart's [tradingKeys] place market orders. It starts off, and only the chosen pair of
 * keys trades. [tradeSounds] is the joke clip played on an entry, take-profit or stop-loss fill; it starts on, as it
 * was before it had a switch. [orderPanelLayout] starts as the sheet, the order page from before there was a choice.
 * [orderMoreSettings] is whether the docked order bar shows its "更多设置" part, kept from one order to the next.
 */
@Serializable
data class TradingPreferences(
    val skipConfirmation: Boolean = false,
    val keyboardTrading: Boolean = false,
    val tradingKeys: TradingKeys = TradingKeys.LETTERS,
    val tradeSounds: Boolean = true,
    val orderPanelLayout: OrderPanelLayout = OrderPanelLayout.SHEET,
    val orderMoreSettings: Boolean = false,
)

/** Device-wide trading interaction preferences, independent of account snapshots. */
class TradingPreferencesController(private val store: SnapshotStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
    private val current = MutableStateFlow(try {
        store.read(KEY)?.let { json.decodeFromString<TradingPreferences>(it) } ?: TradingPreferences()
    } catch (_: Exception) {
        error.value = "交易设置无法读取，已恢复交易确认、关闭键盘下单并打开成交音效。"
        TradingPreferences()
    })
    val preferences = current.asStateFlow()

    suspend fun setSkipConfirmation(enabled: Boolean): Boolean = update { it.copy(skipConfirmation = enabled) }
    suspend fun setKeyboardTrading(enabled: Boolean): Boolean = update { it.copy(keyboardTrading = enabled) }
    suspend fun setOrderPanelLayout(layout: OrderPanelLayout): Boolean = update { it.copy(orderPanelLayout = layout) }
    suspend fun setOrderMoreSettings(open: Boolean): Boolean = update { it.copy(orderMoreSettings = open) }
    suspend fun setTradingKeys(keys: TradingKeys): Boolean = update { it.copy(tradingKeys = keys) }
    suspend fun setTradeSounds(enabled: Boolean): Boolean = update { it.copy(tradeSounds = enabled) }

    /** Saves the changed preferences first; a failed save leaves them as they were. */
    private suspend fun update(change: (TradingPreferences) -> TradingPreferences): Boolean = mutex.withLock {
        saving.value = true
        try {
            val next = change(current.value)
            withContext(Dispatchers.Default) { store.write(KEY, json.encodeToString(next)) }
            current.value = next
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "交易设置保存失败，设置未变更。"; false }
        finally { saving.value = false }
    }

    companion object { const val KEY = "trading-preferences-v1" }
}
