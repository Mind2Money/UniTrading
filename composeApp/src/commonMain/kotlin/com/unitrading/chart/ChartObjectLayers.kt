package com.unitrading.chart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.unitrading.app.SnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal fun drawingLayerKey(id: String) = "drawing:$id"
internal fun indicatorLayerKey(id: String) = "indicator:$id"

/** The relative order of objects is independent for every symbol and every chart. */
@Stable
class ChartObjectLayers(private val store: SnapshotStore) {
    private val mutex = Mutex()
    private val readResult = runCatching {
        store.read(KEY)?.let { Json.decodeFromString<Map<String, Map<String, Long>>>(it) }.orEmpty()
    }
    private var layers by mutableStateOf(readResult.getOrDefault(emptyMap()))
    var error by mutableStateOf(if (readResult.isFailure) "对象层级读取失败，原有数据未覆盖。" else null)
        private set

    internal fun forSymbol(symbol: String): Map<String, Long> = layers[symbol].orEmpty()

    /** Zero is the candles' layer. Equal layers retain the original indicator-then-drawing order. */
    internal suspend fun move(symbol: String, key: String, indicators: List<String>, drawings: List<String>, front: Boolean): Boolean = mutex.withLock {
        if (readResult.isFailure) return@withLock false
        val all = listOf(CANDLES) + indicators.map(::indicatorLayerKey) + drawings.map(::drawingLayerKey)
        if (key !in all || key == CANDLES) return@withLock false
        val existing = forSymbol(symbol)
        val sorted = all.distinct().sortedBy { if (it == CANDLES) 0L else existing[it] ?: 0L }.toMutableList()
        sorted.remove(key)
        if (front) sorted.add(key) else sorted.add(0, key)
        val candles = sorted.indexOf(CANDLES)
        val updated = existing + sorted.withIndex().filter { it.value != CANDLES }.associate { it.value to (it.index - candles).toLong() }
        val next = layers + (symbol to updated)
        try {
            withContext(Dispatchers.Default) { store.write(KEY, Json.encodeToString(next)) }
            layers = next
            error = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "对象层级保存失败，请重试。"; false }
    }

    private companion object {
        const val KEY = "chart-object-layers-v1"
        const val CANDLES = "candles"
    }
}
