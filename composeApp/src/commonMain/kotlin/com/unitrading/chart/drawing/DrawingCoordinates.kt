package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.unitrading.broker.Candle
import com.unitrading.chart.ChartViewport
import com.unitrading.chart.PriceScale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Maps anchors to plot pixels and back. Time runs through the bar index, so sessions without bars take no room; times past
 * either end of the loaded bars continue at one [periodMs] per bar.
 */
internal class DrawingCoordinates(
    val candles: List<Candle>,
    val periodMs: Long,
    val viewport: ChartViewport,
    val plot: Rect,
    /** Bottom of the indicator panes, where full-height lines stop. */
    val chartBottom: Float,
    val scale: PriceScale,
) {
    val total: Int get() = candles.size
    val step: Float = plot.width / viewport.count.toFloat()
    private val start = viewport.start(total)

    /** Fractional bar index of [timeMs]; a time inside a bar lies between its centre and the next one. */
    fun index(timeMs: Long): Double {
        if (candles.isEmpty()) return 0.0
        val first = candles.first().timeMs
        val last = candles.last().timeMs
        if (timeMs < first) return (timeMs - first).toDouble() / periodMs
        if (timeMs >= last) return candles.lastIndex + (timeMs - last).toDouble() / periodMs
        val found = candles.binarySearchBy(timeMs) { it.timeMs }
        if (found >= 0) return found.toDouble()
        val bar = -found - 2
        return bar + ((timeMs - candles[bar].timeMs).toDouble() / periodMs).coerceAtMost(1.0)
    }

    fun timeAt(index: Double): Long {
        if (candles.isEmpty()) return 0
        if (index <= 0.0) return candles.first().timeMs + (index * periodMs).toLong()
        if (index >= candles.lastIndex) return candles.last().timeMs + ((index - candles.lastIndex) * periodMs).toLong()
        val bar = floor(index).toInt()
        val gap = minOf(periodMs, candles[bar + 1].timeMs - candles[bar].timeMs)
        return candles[bar].timeMs + ((index - bar) * gap).toLong()
    }

    fun xOfIndex(index: Double): Float = plot.left + ((index - start + .5) * step).toFloat()
    fun indexAt(x: Float): Double = (x - plot.left).toDouble() / step + start - .5
    fun x(timeMs: Long): Float = xOfIndex(index(timeMs))
    fun y(price: Double): Float = (scale.bottom - (price - scale.low) / (scale.high - scale.low) * (scale.bottom - scale.top)).toFloat()
    fun priceAt(y: Float): Double = scale.low + (scale.bottom - y).toDouble() / (scale.bottom - scale.top) * (scale.high - scale.low)
    fun offset(point: DrawingPoint): Offset = Offset(x(point.timeMs), y(point.price))

    /** The anchor under [position]; [snapToBar] centres it on the nearest bar, as every tool but the freehand ones does. */
    fun pointAt(position: Offset, snapToBar: Boolean = true): DrawingPoint {
        val raw = indexAt(position.x)
        return DrawingPoint(timeAt(if (snapToBar) raw.roundToInt().toDouble() else raw), priceAt(position.y))
    }

    /** Nearest whole bar inside the loaded data; null when no bars are loaded. */
    fun candleIndexAt(x: Float): Int? = if (candles.isEmpty()) null else indexAt(x).roundToInt().coerceIn(0, candles.lastIndex)
}

/**
 * Moves [point] onto the nearest open, high, low, close or [extraValues] of the bar under it when the magnet applies. Weak
 * mode needs the pointer within [weakPixels] of the value; strong mode always takes the nearest.
 */
internal fun DrawingCoordinates.magnetize(point: DrawingPoint, mode: MagnetMode, weakPixels: Float, extraValues: List<Double> = emptyList()): DrawingPoint {
    if (mode == MagnetMode.OFF || candles.isEmpty()) return point
    val bar = candles.getOrNull(index(point.timeMs).roundToInt()) ?: return point
    val y = y(point.price)
    val nearest = (listOf(bar.open, bar.high, bar.low, bar.close).map(Long::toDouble) + extraValues).minByOrNull { kotlin.math.abs(y(it) - y) } ?: return point
    return if (mode == MagnetMode.STRONG || kotlin.math.abs(y(nearest) - y) <= weakPixels) point.copy(price = nearest) else point
}
