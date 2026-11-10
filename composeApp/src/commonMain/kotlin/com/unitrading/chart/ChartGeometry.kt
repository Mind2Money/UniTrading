package com.unitrading.chart

import com.unitrading.broker.Candle
import kotlin.math.roundToLong
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ceil

/** How much one wheel notch scales the visible range, as the exponent of e: about 13%. */
private const val WHEEL_ZOOM = .12

/** Rendering uses floating coordinates; account arithmetic remains integer-only. */
data class PriceScale(val low: Double, val high: Double, val top: Float, val bottom: Float) {
    init { require(high > low && bottom > top) }
    fun y(price: Long): Float = bottom - ((price - low) / (high - low) * (bottom - top)).toFloat()
    fun price(y: Float, tickSize: Long): Long {
        require(tickSize > 0)
        val raw = low + (bottom - y.coerceIn(top, bottom)) / (bottom - top) * (high - low)
        return ((raw / tickSize).roundToLong() * tickSize).coerceAtLeast(tickSize)
    }
    fun pan(pixels: Float): PriceScale {
        val shift = pixels / (bottom - top) * (high - low)
        return copy(low = low + shift, high = high + shift)
    }
    fun zoom(pixels: Float, minimumSpan: Double): PriceScale {
        val span = ((high - low) * exp(pixels / (bottom - top) * 3.0)).coerceIn(minimumSpan, 1e16)
        val center = (low + high) / 2
        return copy(low = center - span / 2, high = center + span / 2)
    }
    /** Keep the price under the fingers' previous centroid beneath their new centroid. */
    fun pinch(factor: Double, previousAnchor: Float, anchor: Float, minimumSpan: Double): PriceScale {
        if (!factor.isFinite() || factor <= 0.0) return this
        val span = ((high - low) / factor).coerceIn(minimumSpan, 1e16)
        val price = high - previousAnchor.coerceIn(0f, 1f) * (high - low)
        val nextHigh = price + anchor.coerceIn(0f, 1f) * span
        return copy(low = nextHigh - span, high = nextHigh)
    }
    /** The wheel over the price axis: [ticks] are notches, positive when rolling towards the user, which zooms out. */
    fun zoomWheel(ticks: Float, minimumSpan: Double): PriceScale = zoom((ticks * WHEEL_ZOOM / 3.0).toFloat() * (bottom - top), minimumSpan)
    companion object {
        fun from(candles: List<Candle>, extras: List<Long>, top: Float, bottom: Float): PriceScale {
            val points = candles.flatMap { listOf(it.low, it.high) } + extras.filter { it > 0 }
            val min = (points.minOrNull() ?: 1).toDouble()
            val max = (points.maxOrNull() ?: 2).toDouble()
            val padding = maxOf((max - min) * 0.16, 8.0)
            return PriceScale(maxOf(0.0, min - padding), max + padding, top, bottom)
        }
    }
}

/** Continuous bar coordinates preserve fractional movement and allow space after the latest bar. */
data class ChartViewport(val count: Double = 80.0, val offset: Double = 0.0) {
    fun start(total: Int) = total - offset - count
    fun range(total: Int): IntRange {
        if (total == 0) return IntRange.EMPTY
        return floor(start(total)).toInt().coerceIn(0, total) until ceil(total - offset).toInt().coerceIn(0, total)
    }
    /**
     * Bar slots at least partly inside the view, as bar indices. Unlike [range] they do not stop at the loaded bars: the
     * view may hold empty slots before the oldest bar (negative indices) and after the newest one ([total] and up).
     */
    fun slots(total: Int): IntRange = floor(start(total)).toInt()..(ceil(total - offset).toInt() - 1)
    /** The slot at [across], the share of the plot width from its left edge; a point outside the plot takes the nearest of [slots]. */
    fun slotAt(across: Double, total: Int): Int {
        val inside = slots(total)
        return floor(start(total) + across * count).toInt().coerceAtMost(inside.last).coerceAtLeast(inside.first)
    }
    fun pan(pixels: Float, width: Float, total: Int): ChartViewport =
        copy(offset = offset + pixels / width.coerceAtLeast(1f) * count).bounded(total)
    fun zoom(pixels: Float, width: Float, total: Int): ChartViewport {
        val next = (count * exp(-pixels / width.coerceAtLeast(1f) * 3.0)).coerceIn(12.0, 600.0)
        return copy(count = next, offset = offset + (count - next) / 2).bounded(total)
    }
    /**
     * The wheel over the plot; [ticks] are notches, positive when rolling towards the user, which shows more bars. The bar
     * at [anchor] (0 the left edge, 1 the right) stays under the pointer, except on a chart following the newest bar, which
     * keeps its right edge. Zooming out of the past stops at the newest bar instead of opening empty space after it.
     */
    fun zoomAt(ticks: Float, anchor: Float, total: Int): ChartViewport {
        val next = (count * exp(ticks * WHEEL_ZOOM)).coerceIn(12.0, 600.0)
        val moved = if (offset <= 0.0) offset else (offset + (1 - anchor) * (count - next)).coerceAtLeast(0.0)
        return copy(count = next, offset = moved).bounded(total)
    }
    /** Continuous pinch scaling and panning, anchored to the two-finger centroid. */
    fun pinch(factor: Double, previousAnchor: Float, anchor: Float, total: Int): ChartViewport {
        if (!factor.isFinite() || factor <= 0.0) return this
        val next = (count / factor).coerceIn(12.0, 600.0)
        return copy(count = next, offset = offset + (1 - previousAnchor.coerceIn(0f, 1f)) * count -
            (1 - anchor.coerceIn(0f, 1f)) * next).bounded(total)
    }
    fun bounded(total: Int) = copy(offset = offset.coerceIn(-count * .8, (total - count * .2).coerceAtLeast(0.0)))
    /**
     * The view once the series changes from [previous] to [next]: offset 0 stays on the newest bar, any other keeps its
     * bars on screen. Only bars added right of the view move it, because [offset] counts from the newest bar; older
     * history lands on the left and changes nothing.
     */
    fun following(previous: List<Candle>, next: List<Candle>): ChartViewport {
        if (offset == 0.0 || previous.isEmpty()) return bounded(next.size)
        val edge = previous[(ceil(previous.size - offset).toInt() - 1).coerceIn(0, previous.lastIndex)].timeMs
        return copy(offset = offset + next.count { it.timeMs > edge } - previous.count { it.timeMs > edge }).bounded(next.size)
    }
}

/**
 * Opening time of bar slot [index]; null without bars. Slots past either end of [candles] continue at one [periodMs] per
 * slot, as drawing anchors do, so the market's closed hours are not skipped there.
 */
fun slotTime(candles: List<Candle>, index: Int, periodMs: Long): Long? = when {
    candles.isEmpty() -> null
    index < 0 -> candles.first().timeMs + index * periodMs
    index > candles.lastIndex -> candles.last().timeMs + (index - candles.lastIndex) * periodMs
    else -> candles[index].timeMs
}

fun visibleRange(total: Int, count: Int, offset: Int): IntRange {
    if (total <= 0) return IntRange.EMPTY
    val size = count.coerceIn(1, total)
    val end = (total - offset.coerceAtLeast(0)).coerceIn(size, total)
    return (end - size) until end
}

fun candleIndex(x: Float, left: Float, plotWidth: Float, count: Int): Int =
    if (count <= 0 || plotWidth <= 0) 0 else (((x - left) / plotWidth) * count).toInt().coerceIn(0, count - 1)

/** Separate nearby labels without moving their price lines; the renderer adds leaders. */
fun spreadLabelCenters(desired: List<Float>, top: Float, bottom: Float, minimumGap: Float): List<Float> {
    require(bottom >= top && minimumGap > 0)
    if (desired.isEmpty()) return emptyList()
    require(desired.zipWithNext().all { (a, b) -> a <= b })
    val gap = if (desired.size == 1) 0f else minOf(minimumGap, (bottom - top) / (desired.size - 1))
    val result = desired.map { it.coerceIn(top, bottom) }.toMutableList()
    for (i in 1..result.lastIndex) result[i] = maxOf(result[i], result[i - 1] + gap)
    result[result.lastIndex] = minOf(result.last(), bottom)
    for (i in result.lastIndex - 1 downTo 0) result[i] = minOf(result[i], result[i + 1] - gap)
    return result
}

/**
 * Like [spreadLabelCenters], but label `i` may reserve [extraAbove]/[extraBelow] pixels around its centre for
 * stacked lines (the live price tag carries the contract name above and the bar countdown below). When the
 * reservations cannot fit between [top] and [bottom], they are dropped and the uniform spacing is used instead.
 */
fun spreadLabelCenters(desired: List<Float>, top: Float, bottom: Float, minimumGap: Float, extraAbove: List<Float>, extraBelow: List<Float>): List<Float> {
    require(extraAbove.size == desired.size && extraBelow.size == desired.size)
    require(extraAbove.all { it >= 0 } && extraBelow.all { it >= 0 })
    if (desired.isEmpty()) return emptyList()
    require(desired.zipWithNext().all { (a, b) -> a <= b })
    fun gap(i: Int) = minimumGap + extraBelow[i - 1] + extraAbove[i]
    val needed = extraAbove.first() + extraBelow.last() + (1..desired.lastIndex).fold(0f) { sum, i -> sum + gap(i) }
    if (needed > bottom - top) return spreadLabelCenters(desired, top, bottom, minimumGap)
    val result = desired.mapIndexed { i, y -> y.coerceIn(top + extraAbove[i], bottom - extraBelow[i]) }.toMutableList()
    for (i in 1..result.lastIndex) result[i] = maxOf(result[i], result[i - 1] + gap(i))
    result[result.lastIndex] = minOf(result.last(), bottom - extraBelow.last())
    for (i in result.lastIndex - 1 downTo 0) result[i] = minOf(result[i], result[i + 1] - gap(i + 1))
    return result
}
