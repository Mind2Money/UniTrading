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
 * [keyboardTrading] lets the chart's [tradingKeys] place market orders. It starts off, and only the chosen pair of
 * keys trades.
 */
@Serializable
data class TradingPreferences(
    val skipConfirmation: Boolean = false,
    val keyboardTrading: Boolean = false,
    val tradingKeys: TradingKeys = TradingKeys.LETTERS,
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
        error.value = "交易设置无法读取，已恢复交易确认并关闭键盘下单。"
        TradingPreferences()
    })
    val preferences = current.asStateFlow()

    suspend fun setSkipConfirmation(enabled: Boolean): Boolean = update { it.copy(skipConfirmation = enabled) }
    suspend fun setKeyboardTrading(enabled: Boolean): Boolean = update { it.copy(keyboardTrading = enabled) }
    suspend fun setTradingKeys(keys: TradingKeys): Boolean = update { it.copy(tradingKeys = keys) }

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
