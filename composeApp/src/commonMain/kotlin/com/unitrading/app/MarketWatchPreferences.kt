package com.unitrading.app

import com.unitrading.broker.Instrument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Ordered watch lists are scoped to the broker, not to the currently selected account. */
class MarketWatchPreferences(private val store: SnapshotStore) {
    private val state = MutableStateFlow<Map<String, List<String>>>(try {
        store.read("market-watch-v1")?.let { Json.decodeFromString(it) } ?: emptyMap()
    } catch (_: Exception) { emptyMap() })
    val lists = state.asStateFlow()
    val error = MutableStateFlow<String?>(null)

    fun initialize(broker: String, instruments: List<Instrument>, selected: String) {
        if (broker in state.value || instruments.isEmpty()) return
        val defaults = instruments.filter { it.isContinuous }.ifEmpty { instruments }.take(6).map { it.symbol }
        update(broker, (listOf(selected).filter { s -> instruments.any { it.symbol == s } } + defaults).distinct())
    }

    fun update(broker: String, symbols: List<String>) {
        state.value = state.value + (broker to symbols.distinct())
        try {
            store.write("market-watch-v1", Json.encodeToString(state.value))
            error.value = null
        } catch (_: Exception) { error.value = "自选保存失败，当前列表仍有效" }
    }
}
