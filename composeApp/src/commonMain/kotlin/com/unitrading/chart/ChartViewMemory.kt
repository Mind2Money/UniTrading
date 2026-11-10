package com.unitrading.chart

import com.unitrading.app.SnapshotStore
import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * How a chart showed one series when it was left: [count] bars across the plot, and where its right edge stood. A view at
 * or past the newest bar keeps [offset], the bars between its right edge and the newest bar, and goes on following new
 * bars. A view moved into the past keeps the opening time of the bar at its right edge ([edgeTimeMs]) and how much of that
 * bar the edge cut off ([edgeCut]), so it shows the same bars again however many arrived since. [low] and [high] are a price
 * axis zoomed or moved by hand; without them the axis fits the bars.
 */
@Serializable
data class SavedChartView(
    val count: Double,
    val offset: Double = 0.0,
    val edgeTimeMs: Long? = null,
    val edgeCut: Double = 0.0,
    val low: Double? = null,
    val high: Double? = null,
) {
    internal fun valid(): Boolean = count.isFinite() && count in MIN_BARS..MAX_BARS && offset.isFinite() && edgeCut.isFinite() && edgeCut in 0.0..1.0 &&
        (edgeTimeMs == null || offset == 0.0) && ((low == null && high == null) ||
            (low != null && high != null && low.isFinite() && high.isFinite() && low >= 0.0 && low < high))

    /** The view over [candles]; a view of the past finds its right edge among them by time. */
    fun viewport(candles: List<Candle>): ChartViewport {
        val edge = edgeTimeMs ?: return ChartViewport(count, offset).bounded(candles.size)
        val index = candles.indexOfLast { it.timeMs <= edge }.takeIf { it >= 0 } ?: 0
        return ChartViewport(count, candles.size - 1 - index + edgeCut).bounded(candles.size)
    }

    /** The price axis set by hand, if any; the chart replaces its top and bottom with the plot's. */
    fun priceScale(): PriceScale? = if (low != null && high != null) PriceScale(low, high, 0f, 1f) else null

    companion object {
        const val MIN_BARS = 12.0
        const val MAX_BARS = 600.0

        /** What to keep of [viewport] over [candles] (not empty) and of a price axis set by hand. */
        fun of(viewport: ChartViewport, candles: List<Candle>, manual: PriceScale?): SavedChartView {
            val (low, high) = manual?.let { it.low to it.high } ?: (null to null)
            if (viewport.offset <= 0.0 || candles.isEmpty()) return SavedChartView(viewport.count, viewport.offset, low = low, high = high)
            val edge = ceil(candles.size - viewport.offset)
            val index = (edge.toInt() - 1).coerceIn(0, candles.lastIndex)
            return SavedChartView(viewport.count, edgeTimeMs = candles[index].timeMs, edgeCut = (edge - (candles.size - viewport.offset)).coerceIn(0.0, 1.0),
                low = low, high = high)
        }
    }
}

/** The key a series' view is kept under: the same symbol and interval share one view in every chart and at every broker. */
fun chartViewKey(symbol: String, timeframe: Timeframe) = "$symbol|${timeframe.id}"

@Serializable
private data class SavedChartViews(val views: List<Entry> = emptyList(), val count: Double? = null) {
    @Serializable data class Entry(val key: String, val view: SavedChartView)
}

/**
 * The views of the series the user looked at, newest last, so a chart opens as it was left wherever it opens again: after
 * a change of symbol or interval, of page or window, of broker or account, and after a restart. [count] is the bar count
 * of the view saved last, for a chart that opens on a series it has never shown. Saved to the store [saveDelayMs] after
 * the last change, so a drag or a spin of the wheel is written once.
 */
class ChartViewMemory(private val store: SnapshotStore, saveDispatcher: CoroutineDispatcher = Dispatchers.Default, private val saveDelayMs: Long = 400) {
    private var state: SavedChartViews = try {
        store.read(KEY)?.let { json.decodeFromString<SavedChartViews>(it) }?.let { file ->
            SavedChartViews(file.views.filter { it.key.isNotBlank() && it.view.valid() }.distinctBy { it.key }.takeLast(MAX_VIEWS),
                file.count?.takeIf { it.isFinite() && it in SavedChartView.MIN_BARS..SavedChartView.MAX_BARS })
        } ?: SavedChartViews()
    } catch (_: Exception) { SavedChartViews() }
    private val saves = Channel<SavedChartViews>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + saveDispatcher).launch {
            for (first in saves) {
                if (saveDelayMs > 0) delay(saveDelayMs)
                val latest = saves.tryReceive().getOrNull() ?: first
                runCatching { store.write(KEY, json.encodeToString(SavedChartViews.serializer(), latest)) }
            }
        }
    }

    val count: Double get() = state.count ?: ChartViewport().count

    fun view(key: String): SavedChartView? = state.views.lastOrNull { it.key == key }?.view

    /** Keeps [view] as the latest of [key]; the oldest series are forgotten past [MAX_VIEWS]. */
    fun keep(key: String, view: SavedChartView) {
        if (!view.valid() || key.isBlank()) return
        if (state.views.lastOrNull()?.let { it.key == key && it.view == view } == true && state.count == view.count) return
        state = SavedChartViews((state.views.filterNot { it.key == key } + SavedChartViews.Entry(key, view)).takeLast(MAX_VIEWS), view.count)
        saves.trySend(state)
    }

    companion object {
        const val KEY = "chart-views-v1"
        const val MAX_VIEWS = 300
        private val json = Json { ignoreUnknownKeys = true }
    }
}
