package com.unitrading.chart

import com.unitrading.broker.Candle
import kotlin.math.roundToLong

/** Rendering uses floating coordinates; account arithmetic remains integer-only. */
data class PriceScale(val low: Double, val high: Double, val top: Float, val bottom: Float) {
    init { require(high > low && bottom > top) }
    fun y(price: Long): Float = bottom - ((price - low) / (high - low) * (bottom - top)).toFloat()
    fun price(y: Float, tickSize: Long): Long {
        require(tickSize > 0)
        val raw = low + (bottom - y.coerceIn(top, bottom)) / (bottom - top) * (high - low)
        return ((raw / tickSize).roundToLong() * tickSize).coerceAtLeast(tickSize)
    }
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
