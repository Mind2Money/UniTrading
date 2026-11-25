package com.unitrading.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.broker.formatFixed
import com.unitrading.chart.pine.DEFAULT_PINE_SOURCE
import com.unitrading.chart.pine.PineException
import com.unitrading.chart.pine.PineResult
import com.unitrading.chart.pine.compilePine
import com.unitrading.chart.pine.pinePriceFactor
import com.unitrading.chart.pine.pineReplaySample
import com.unitrading.chart.pine.runPine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

internal class ChartPineRun(val result: PineResult? = null, val error: String? = null)
internal class ChartPineFrame(val candles: List<Candle>, val runs: Map<String, ChartPineRun>)

// Large/slow scripts never run in Compose's draw or input dispatch and cannot take over the worker pool.
private val pineDispatcher = Dispatchers.Default.limitedParallelism(2)

@Composable
internal fun rememberChartPine(
    accountKey: String, instrument: Instrument, timeframe: Timeframe, settings: List<IndicatorConfig>, candles: List<Candle>,
): ChartPineFrame? {
    val configs = remember(settings) { settings.filter { it.visible && it.kind == IndicatorKind.PINE } }
    val latest by rememberUpdatedState(candles)
    var frame by remember(accountKey, instrument, timeframe, configs) { mutableStateOf<ChartPineFrame?>(null) }
    LaunchedEffect(accountKey, instrument, timeframe, configs) {
        if (configs.isEmpty()) return@LaunchedEffect
        val scripts = withContext(pineDispatcher) {
            configs.associate { config ->
                ensureActive()
                config.id to try {
                    compilePine(config.pineSource ?: DEFAULT_PINE_SOURCE) to null
                } catch (error: PineException) { null to error.message }
            }
        }
        snapshotFlow { latest }.conflate().collect { bars ->
            frame = withContext(pineDispatcher) {
                val sample = pineReplaySample(bars)
                val results = scripts.mapValues { (_, compiled) ->
                    val (script, error) = compiled
                    if (script == null) ChartPineRun(error = error)
                    else if (sample.isEmpty()) ChartPineRun()
                    else try {
                        ChartPineRun(runPine(script, sample, instrument, timeframe, checkCancelled = { ensureActive() }))
                    } catch (failure: PineException) { ChartPineRun(error = failure.message) }
                }
                ChartPineFrame(sample, results)
            }
            // Process the newest buffered snapshot instead of queuing a replay for every tick.
            delay(200)
        }
    }
    return frame
}

/** Align a completed replay with the current chart by time, not the old size. A new prepend/roll can shift indices. */
internal fun pineSampleOffset(sample: List<Candle>, current: List<Candle>): Int? {
    if (sample.isEmpty() || current.isEmpty()) return null
    if (sample === current) return 0
    val relative = if (sample.first().timeMs >= current.first().timeMs) {
        current.binarySearchBy(sample.first().timeMs) { it.timeMs }.takeIf { it >= 0 } ?: return null
    } else {
        val head = sample.binarySearchBy(current.first().timeMs) { it.timeMs }.takeIf { it >= 0 } ?: return null
        -head
    }
    val start = maxOf(0, -relative)
    val end = minOf(sample.size, current.size - relative)
    if (end <= start) return null
    for (index in start until end) if (sample[index].timeMs != current[index + relative].timeMs) return null
    return relative
}

internal class ChartPinePanel(val result: PineResult, val offset: Int) {
    fun local(range: IntRange): IntRange = if (range.isEmpty()) IntRange.EMPTY else (range.first - offset)..(range.last - offset)
    fun readout(index: Int, instrument: Instrument): IndicatorReadout = IndicatorReadout(result.title, result.plots.asSequence()
        .filter { it.displayed }.take(6).map { plot ->
            val dataIndex = index - offset - plot.offset
            val value = plot.values.getOrNull(dataIndex) ?: Double.NaN
            val color = plot.colors.getOrNull(dataIndex)
            val hex = color?.takeIf { it ushr 24 != 0 }?.let { "#" + (it and 0xFFFFFF).toString(16).padStart(6, '0') }
            (if (result.overlay) formatPinePrice(value, instrument) else formatPineNumber(value)) to hex.orEmpty()
        }.toList())

    fun candleColor(index: Int): Color? = result.barColors.getOrNull(index - offset)?.takeIf { it ushr 24 != 0 }?.let(::Color)

    fun bounds(range: IntRange, slots: IntRange, candles: List<Candle>, periodMs: Long): ClosedFloatingPointRange<Double>? =
        pineValueBounds(result, local(range), local(slots), candles, periodMs, barOrigin = offset)
}

/** Pine may draw arbitrary numbers, but those numbers must not flatten the candles on the order-entry price axis. */
internal fun pineOverlayScaleValues(
    panels: List<ChartPinePanel>, range: IntRange, slots: IntRange, candles: List<Candle>, periodMs: Long, factor: Double,
): List<Long> {
    if (panels.isEmpty() || range.isEmpty()) return emptyList()
    val low = range.minOf { candles[it].low }.toDouble()
    val high = range.maxOf { candles[it].high }.toDouble()
    val extension = maxOf(high - low, abs(high) * .001, factor) * 4
    val allowed = maxOf(1.0, low - extension)..minOf(high + extension, (Long.MAX_VALUE / 4).toDouble())
    return buildList {
        for (panel in panels) {
            val bounds = panel.bounds(range, slots, candles, periodMs) ?: continue
            for (value in listOf(bounds.start, bounds.endInclusive)) {
                pinePriceUnits(value, factor)?.takeIf { it.toDouble() in allowed }?.let(::add)
            }
        }
    }
}

/** Preserve the configured indicator order while routing force_overlay drawings out of their owner's pane. */
internal fun mergeChartIndicators(
    configs: List<IndicatorConfig>, builtins: List<ComputedIndicator>, frame: ChartPineFrame?, offset: Int?,
): List<ComputedIndicator> {
    if (frame == null || offset == null) return builtins
    val native = builtins.associateBy { it.config.id }
    fun PineResult.forPane(onPrice: Boolean) = copy(
        overlay = onPrice,
        plots = plots.filter { it.onPrice == onPrice }, levels = levels.filter { it.onPrice == onPrice },
        fills = fills.filter { it.onPrice == onPrice }, marks = marks.filter { it.onPrice == onPrice },
        drawings = drawings.filter { it.onPrice == onPrice },
        backgrounds = if (onPrice) priceBackgrounds else backgrounds,
        barColors = if (onPrice) barColors else emptyArray(),
    )
    return buildList {
        for (config in configs) {
            val builtin = native[config.id]
            if (builtin != null) {
                add(builtin)
                continue
            }
            val result = frame.runs[config.id]?.result ?: continue
            if (!result.overlay) add(ComputedIndicator(config, emptyList(), ChartPinePanel(result.forPane(false), offset)))
            val price = result.forPane(true)
            if (result.overlay || price.plots.isNotEmpty() || price.levels.isNotEmpty() || price.fills.isNotEmpty() || price.marks.isNotEmpty() ||
                price.drawings.isNotEmpty() || price.backgrounds.any { it != null } || price.barColors.any { it != null }) {
                add(ComputedIndicator(config, emptyList(), ChartPinePanel(price, offset)))
            }
        }
    }
}

internal fun formatPineNumber(value: Double): String = when {
    !value.isFinite() -> "—"
    abs(value) >= 1e12 || value != 0.0 && abs(value) < 0.0001 -> value.toString()
    else -> formatFixed((value * 10_000).roundToLong(), 4).trimEnd('0').trimEnd('.')
}

private fun formatPinePrice(value: Double, instrument: Instrument): String =
    pinePriceUnits(value, pinePriceFactor(instrument))?.let(instrument::formatPrice) ?: "—"

internal fun pinePriceUnits(value: Double, factor: Double): Long? = (value * factor)
    .takeIf { it.isFinite() && abs(it) < Long.MAX_VALUE.toDouble() }?.roundToLong()

internal fun paddedPineRange(raw: ClosedFloatingPointRange<Double>?): ClosedFloatingPointRange<Double> {
    if (raw == null) return -1.0..1.0
    val (low, high) = raw.start to raw.endInclusive
    val halfSpan = high / 2 - low / 2
    val padding = maxOf(halfSpan * .12, maxOf(abs(low), abs(high)) * 1e-9, if (halfSpan == 0.0) .01 else 1e-12)
    return (low - padding).coerceAtLeast(-Double.MAX_VALUE)..(high + padding).coerceAtMost(Double.MAX_VALUE)
}
