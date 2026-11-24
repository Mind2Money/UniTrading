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
    private data class Change(val symbol: String, val before: Map<String, Long>, val after: Map<String, Long>)
    private val history = ArrayDeque<Change>()
    private val redoHistory = ArrayDeque<Change>()
    internal var onRecorded: (() -> Unit)? = null
    private val readResult = runCatching {
        store.read(KEY)?.let { Json.decodeFromString<Map<String, Map<String, Long>>>(it) }.orEmpty()
    }
    private var layers by mutableStateOf(readResult.getOrDefault(emptyMap()))
    var error by mutableStateOf(if (readResult.isFailure) "对象层级读取失败，原有数据未覆盖。" else null)
        private set

    internal fun forSymbol(symbol: String): Map<String, Long> = layers[symbol].orEmpty()

    /** Zero is the candles' layer. Equal layers retain the original indicator-then-drawing order. */
    internal suspend fun move(symbol: String, key: String, indicators: List<String>, drawings: List<String>, front: Boolean): Boolean =
        moveBy(symbol, key, indicators, drawings, if (front) Int.MAX_VALUE else Int.MIN_VALUE)

    internal suspend fun moveBy(symbol: String, key: String, indicators: List<String>, drawings: List<String>, delta: Int): Boolean = mutex.withLock {
        if (readResult.isFailure) return@withLock false
        val all = listOf(CANDLES) + indicators.map(::indicatorLayerKey) + drawings.map(::drawingLayerKey)
        if (key !in all || key == CANDLES) return@withLock false
        val existing = forSymbol(symbol)
        val sorted = all.distinct().sortedBy { if (it == CANDLES) 0L else existing[it] ?: 0L }.toMutableList()
        val from = sorted.indexOf(key)
        val to = (from.toLong() + delta.toLong()).coerceIn(0L, sorted.lastIndex.toLong()).toInt()
        sorted.removeAt(from)
        sorted.add(to, key)
        val candles = sorted.indexOf(CANDLES)
        val updated = existing + sorted.withIndex().filter { it.value != CANDLES }.associate { it.value to (it.index - candles).toLong() }
        if (updated == existing) return@withLock true
        val next = layers + (symbol to updated)
        try {
            withContext(Dispatchers.Default) { store.write(KEY, Json.encodeToString(next)) }
            layers = next
            history.addLast(Change(symbol, existing, updated))
            if (history.size > 100) history.removeFirst()
            redoHistory.clear()
            onRecorded?.invoke()
            error = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "对象层级保存失败，请重试。"; false }
    }

    internal suspend fun undo(): Boolean = restore(undo = true)
    internal suspend fun redo(): Boolean = restore(undo = false)

    private suspend fun restore(undo: Boolean): Boolean = mutex.withLock {
        val source = if (undo) history else redoHistory
        val target = if (undo) redoHistory else history
        val change = source.lastOrNull() ?: return@withLock false
        val next = layers + (change.symbol to if (undo) change.before else change.after)
        try {
            withContext(Dispatchers.Default) { store.write(KEY, Json.encodeToString(next)) }
            layers = next
            source.removeLast()
            target.addLast(change)
            error = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "对象层级恢复失败，请重试。"; false }
    }

    private companion object {
        const val KEY = "chart-object-layers-v1"
        const val CANDLES = "candles"
    }
}
