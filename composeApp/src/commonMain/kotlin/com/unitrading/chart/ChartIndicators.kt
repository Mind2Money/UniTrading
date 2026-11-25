package com.unitrading.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.ui.TradingColors
import com.unitrading.ui.TradingPalette
import kotlin.math.abs
import kotlin.math.roundToLong

internal class ComputedIndicator(val config: IndicatorConfig, val series: List<IndicatorSeries>, val pine: ChartPinePanel? = null) {
    val overlay: Boolean get() = pine?.result?.overlay ?: (config.kind.pane == IndicatorPane.OVERLAY)
    val title: String get() = pine?.result?.title ?: config.label

    fun readout(index: Int, instrument: Instrument): IndicatorReadout = pine?.readout(index, instrument) ?: IndicatorReadout(title,
        series.map { formatIndicatorValue(config.kind, it.values.getOrNull(index) ?: Double.NaN, instrument) to if (it.line.histogram) "" else it.color })

    fun bounds(range: IntRange, slots: IntRange, candles: List<Candle>, periodMs: Long): ClosedFloatingPointRange<Double> =
        pine?.let { paddedPineRange(it.bounds(range, slots, candles, periodMs)) } ?: paneRange(config.kind, series, range)

    fun formatPaneValue(value: Double, instrument: Instrument): String = if (pine == null) formatIndicatorValue(config.kind, value, instrument) else formatPineNumber(value)
}

internal fun computeIndicators(configs: List<IndicatorConfig>, candles: List<Candle>, timeframe: com.unitrading.broker.Timeframe): List<ComputedIndicator> =
    configs.filter { it.visible && it.kind != IndicatorKind.PINE }.map { config ->
        val values = computeIndicator(config, candles)
        if (config.kind == IndicatorKind.VWAP && timeframe.seconds >= 86_400) {
            values.forEach { it.values.fill(Double.NaN) }
        }
        ComputedIndicator(config, values)
    }

/** Pixel rows a pane plots values in: below its title row and above its bottom padding. */
internal fun paneValueArea(paneTop: Float, paneHeight: Float, labelHeight: Float, labelPadding: Float): Pair<Float, Float> =
    (paneTop + labelHeight + labelPadding * 3) to (paneTop + paneHeight - labelPadding * 2)

/** Formatted value under pixel row [y], using the same resized bounds as the drawing. */
internal fun paneValueLabel(panes: List<ComputedIndicator>, y: Float, layout: List<IndicatorPaneBounds>, range: IntRange,
    instrument: Instrument, labelHeight: Float, labelPadding: Float, slots: IntRange = range, periodMs: Long = 1,
    candles: List<Candle> = emptyList(),
): String? {
    val paneIndex = layout.indexOfFirst { y >= it.top && y < it.bottom }
    val item = panes.getOrNull(paneIndex) ?: return null
    val pane = layout[paneIndex]
    val (valueTop, valueBottom) = paneValueArea(pane.top, pane.height, labelHeight, labelPadding)
    if (valueBottom <= valueTop || y !in valueTop..valueBottom) return null
    val bounds = item.bounds(range, slots, candles, periodMs)
    val fraction = ((valueBottom - y) / (valueBottom - valueTop)).toDouble()
    val value = bounds.start * (1.0 - fraction) + bounds.endInclusive * fraction
    return item.formatPaneValue(value, instrument)
}

/** Overlay values inside [range], rounded to price units for the automatic scale. */
internal fun overlayScaleValues(overlays: List<ComputedIndicator>, range: IntRange): List<Long> = buildList {
    for (item in overlays) for (s in item.series) for (i in range) {
        val v = s.values.getOrNull(i) ?: continue
        if (v.isFinite()) add(v.roundToLong())
    }
}

internal fun indicatorReadouts(items: List<ComputedIndicator>, index: Int, instrument: Instrument): List<IndicatorReadout> =
    items.map { it.readout(index, instrument) }

fun parseHexColor(value: String?): Color? =
    value?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000L or it) }

/** Polyline through the non-NaN values of [range]; a gap restarts the line. */
internal fun DrawScope.drawSeriesLine(values: DoubleArray, range: IntRange, color: Color, lineStyle: IndicatorLineStyle, x: (Int) -> Float, y: (Double) -> Float) {
    val path = Path()
    var open = false
    for (index in range) {
        val v = values.getOrNull(index) ?: break
        val row = y(v)
        if (v.isNaN() || !row.isFinite()) { open = false; continue }
        if (!open) { path.moveTo(x(index), row); open = true } else path.lineTo(x(index), row)
    }
    val width = lineStyle.width.dp.toPx()
    val effect = when (lineStyle.pattern) {
        IndicatorLinePattern.SOLID -> null
        IndicatorLinePattern.DASHED -> PathEffect.dashPathEffect(floatArrayOf(maxOf(width * 4, 6.dp.toPx()), maxOf(width * 3, 4.dp.toPx())))
        IndicatorLinePattern.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(width, width * 3))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

/** Overlay lines share the candles' price scale; [x] maps an absolute candle index to its centre. */
internal fun DrawScope.drawIndicatorOverlays(overlays: List<ComputedIndicator>, range: IntRange, scale: PriceScale, palette: TradingPalette, x: (Int) -> Float) {
    overlays.forEach { item ->
        item.series.forEach { s -> drawSeriesLine(s.values, range, parseHexColor(s.color) ?: palette.Ink, item.config.lineStyle(s.line.name), x) { scale.yOf(it) } }
    }
}

/**
 * Separate panes use [layout]. Every pane owns its range, dashed guides, right-axis
 * bounds and a title with the values at [readoutIndex].
 */
internal fun DrawScope.drawIndicatorPanes(
    panes: List<ComputedIndicator>,
    candles: List<Candle>,
    range: IntRange,
    readoutIndex: Int,
    instrument: Instrument,
    left: Float,
    right: Float,
    layout: List<IndicatorPaneBounds>,
    step: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    palette: TradingPalette,
    axisLeft: Boolean = false,
    slots: IntRange = range,
    periodMs: Long = 1,
    pineTextBudget: PineTextBudget,
    pineGeometryLimit: Int,
    x: (Double) -> Float,
) {
    val labelPadding = 3.dp.toPx()
    val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
    panes.forEachIndexed { paneIndex, item ->
        val pane = layout.getOrNull(paneIndex) ?: return@forEachIndexed
        val paneTop = pane.top
        val paneBottom = pane.bottom
        val (valueTop, valueBottom) = paneValueArea(paneTop, pane.height, labelHeight, labelPadding)
        if (valueBottom <= valueTop) return@forEachIndexed
        val kind = item.config.kind
        val bounds = item.bounds(range, slots, candles, periodMs)
        val halfSpan = bounds.endInclusive / 2 - bounds.start / 2
        fun y(v: Double) = valueBottom - ((v / 2 - bounds.start / 2) / halfSpan * (valueBottom - valueTop)).toFloat()
        drawLine(palette.Border, Offset(left, paneTop), Offset(right, paneTop), 1f)
        val guides = item.config.guides.filter { it in bounds }
        guides.forEach { guide ->
            val color = if (kind == IndicatorKind.RSI) parseHexColor(item.config.guideColor(guide)) ?: palette.Muted else palette.Grid
            drawLine(color, Offset(left, y(guide)), Offset(right, y(guide)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        clipRect(left, paneTop + 1f, right, paneBottom) {
            item.pine?.let { pine ->
                val localX: (Double) -> Float = { x(it + pine.offset) }
                val localRange = pine.local(range)
                drawPineBackground(pine.result, localRange, left, right, valueTop, valueBottom, step, localX)
                clipRect(left, valueTop, right, paneBottom) {
                    drawPineVisuals(pine.result, localRange, pine.local(slots), candles, periodMs,
                        left, right, valueTop, valueBottom, step, textMeasurer,
                        barOrigin = pine.offset, textBudget = pineTextBudget, geometryBudget = PineGeometryBudget(pineGeometryLimit),
                        x = localX, y = ::y)
                }
            }
            item.series.forEach { s ->
                if (s.line.histogram) {
                    val zero = y(0.0)
                    val width = (step * .5f).coerceIn(1f, 8f * density)
                    for (index in range) {
                        val v = s.values.getOrNull(index) ?: break
                        if (v.isNaN()) continue
                        val barY = y(v)
                        drawRect(if (kind == IndicatorKind.VOLUME) { val bar = candles.getOrNull(index); if (bar != null && bar.close < bar.open) palette.Down else palette.Up } else if (v >= 0) palette.Up else palette.Down, Offset(x(index.toDouble()) - width / 2, minOf(barY, zero)), Size(width, maxOf(abs(barY - zero), 1f)))
                    }
                } else drawSeriesLine(s.values, range, parseHexColor(s.color) ?: palette.Ink, item.config.lineStyle(s.line.name), { x(it.toDouble()) }, ::y)
            }
        }
        fun axisText(text: String, center: Float) {
            val layout = textMeasurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset(if (axisLeft) (left - layout.size.width - 8f).coerceAtLeast(0f) else right + 8f, (center - layout.size.height / 2).coerceIn(paneTop, maxOf(paneTop, paneBottom - layout.size.height))))
        }
        axisText(item.formatPaneValue(bounds.endInclusive, instrument), valueTop)
        axisText(item.formatPaneValue(bounds.start, instrument), valueBottom)
        if (kind == IndicatorKind.RSI) {
            // Keep nearby labels from overlapping each other or the axis bounds in short panes.
            val labelRows = mutableListOf(valueTop, valueBottom)
            guides.sortedDescending().forEach { guide ->
                val row = y(guide)
                if (labelRows.all { abs(it - row) >= labelHeight + labelPadding }) {
                    axisText(formatParam(guide), row)
                    labelRows.add(row)
                }
            }
        }
        var cursor = left + labelPadding * 2
        val titleTop = paneTop + labelPadding
        val readout = item.readout(readoutIndex, instrument)
        val title = textMeasurer.measure(readout.title, labelStyle.copy(color = palette.Muted))
        clipRect(left, paneTop, right, paneBottom) {
            drawText(title, topLeft = Offset(cursor, titleTop))
            cursor += title.size.width + labelPadding * 3
            readout.values.forEach { (value, hex) ->
                val color = parseHexColor(hex) ?: palette.Ink
                val valueLayout = textMeasurer.measure(value, labelStyle.copy(color = color, fontWeight = FontWeight.Medium))
                drawText(valueLayout, topLeft = Offset(cursor, titleTop))
                cursor += valueLayout.size.width + labelPadding * 3
            }
        }
    }
}

/** Overlay readouts under the OHLC legend: one row per indicator with its label and coloured values. */
@Composable
internal fun IndicatorLegend(readouts: List<IndicatorReadout>, modifier: Modifier = Modifier) {
    val colors = TradingColors
    Column(
        modifier.background(colors.Surface.copy(alpha = .8f), androidx.compose.material3.MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        readouts.forEach { readout ->
            Row(Modifier.testTag("indicator-readout"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(readout.title, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.Muted)
                readout.values.forEach { (value, hex) -> Text(value, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = parseHexColor(hex) ?: colors.Ink) }
            }
        }
    }
}
