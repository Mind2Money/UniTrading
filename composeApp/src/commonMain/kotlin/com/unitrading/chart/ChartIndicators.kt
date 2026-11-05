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

internal class ComputedIndicator(val config: IndicatorConfig, val series: List<IndicatorSeries>) {
    val overlay: Boolean get() = config.kind.pane == IndicatorPane.OVERLAY
}

internal fun computeIndicators(configs: List<IndicatorConfig>, candles: List<Candle>): List<ComputedIndicator> =
    configs.filter { it.visible }.map { ComputedIndicator(it, computeIndicator(it, candles)) }

/** Pixel rows a pane plots values in: below its title row and above its bottom padding. */
internal fun paneValueArea(paneTop: Float, paneHeight: Float, labelHeight: Float, labelPadding: Float): Pair<Float, Float> =
    (paneTop + labelHeight + labelPadding * 3) to (paneTop + paneHeight - labelPadding * 2)

/** Formatted value under pixel row [y], using the same resized bounds as the drawing. */
internal fun paneValueLabel(panes: List<ComputedIndicator>, y: Float, layout: List<IndicatorPaneBounds>, range: IntRange, instrument: Instrument, labelHeight: Float, labelPadding: Float): String? {
    val paneIndex = layout.indexOfFirst { y >= it.top && y < it.bottom }
    val item = panes.getOrNull(paneIndex) ?: return null
    val pane = layout[paneIndex]
    val (valueTop, valueBottom) = paneValueArea(pane.top, pane.height, labelHeight, labelPadding)
    if (valueBottom <= valueTop || y !in valueTop..valueBottom) return null
    val bounds = paneRange(item.config.kind, item.series, range)
    val value = bounds.start + (valueBottom - y) / (valueBottom - valueTop) * (bounds.endInclusive - bounds.start)
    return formatIndicatorValue(item.config.kind, value, instrument)
}

/** Overlay values inside [range], rounded to price units, so the automatic price scale keeps bands in view. */
internal fun overlayScaleValues(overlays: List<ComputedIndicator>, range: IntRange): List<Long> = buildList {
    for (item in overlays) for (s in item.series) for (i in range) {
        val v = s.values.getOrNull(i) ?: continue
        if (!v.isNaN()) add(v.roundToLong())
    }
}

internal fun indicatorReadouts(items: List<ComputedIndicator>, index: Int, instrument: Instrument): List<IndicatorReadout> = items.map { item ->
    IndicatorReadout(item.config.label, item.series.map { s -> formatIndicatorValue(item.config.kind, s.values.getOrNull(index) ?: Double.NaN, instrument) to s.color })
}

fun parseHexColor(value: String?): Color? =
    value?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000L or it) }

private fun PriceScale.yOf(value: Double): Float = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()

/** Polyline through the non-NaN values of [range]; a gap restarts the line. */
private fun DrawScope.drawSeriesLine(values: DoubleArray, range: IntRange, color: Color, x: (Int) -> Float, y: (Double) -> Float) {
    val path = Path()
    var open = false
    for (index in range) {
        val v = values.getOrNull(index) ?: break
        if (v.isNaN()) { open = false; continue }
        if (!open) { path.moveTo(x(index), y(v)); open = true } else path.lineTo(x(index), y(v))
    }
    drawPath(path, color, style = Stroke(1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Overlay lines share the candles' price scale; [x] maps an absolute candle index to its centre. */
internal fun DrawScope.drawIndicatorOverlays(overlays: List<ComputedIndicator>, range: IntRange, scale: PriceScale, palette: TradingPalette, x: (Int) -> Float) {
    overlays.forEach { item ->
        item.series.forEach { s -> drawSeriesLine(s.values, range, parseHexColor(s.color) ?: palette.Ink, x) { scale.yOf(it) } }
    }
}

/**
 * Separate panes use [layout]. Every pane owns its range, dashed guides, right-axis
 * bounds and a title with the values at [readoutIndex].
 */
internal fun DrawScope.drawIndicatorPanes(
    panes: List<ComputedIndicator>,
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
    x: (Int) -> Float,
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
        val bounds = paneRange(kind, item.series, range)
        fun y(v: Double) = valueBottom - ((v - bounds.start) / (bounds.endInclusive - bounds.start) * (valueBottom - valueTop)).toFloat()
        drawLine(palette.Border, Offset(left, paneTop), Offset(right, paneTop), 1f)
        val guides = item.config.guides.filter { it in bounds }
        guides.forEach { guide ->
            val color = if (kind == IndicatorKind.RSI) parseHexColor(item.config.guideColor(guide)) ?: palette.Muted else palette.Grid
            drawLine(color, Offset(left, y(guide)), Offset(right, y(guide)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        clipRect(left, paneTop + 1f, right, paneBottom) {
            item.series.forEach { s ->
                if (s.line.histogram) {
                    val zero = y(0.0)
                    val width = (step * .5f).coerceIn(1f, 8f * density)
                    for (index in range) {
                        val v = s.values.getOrNull(index) ?: break
                        if (v.isNaN()) continue
                        val barY = y(v)
                        drawRect(if (v >= 0) palette.Up else palette.Down, Offset(x(index) - width / 2, minOf(barY, zero)), Size(width, maxOf(abs(barY - zero), 1f)))
                    }
                } else drawSeriesLine(s.values, range, parseHexColor(s.color) ?: palette.Ink, x, ::y)
            }
        }
        fun axisText(text: String, center: Float) {
            val layout = textMeasurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset(right + 8f, (center - layout.size.height / 2).coerceIn(paneTop, maxOf(paneTop, paneBottom - layout.size.height))))
        }
        axisText(formatIndicatorValue(kind, bounds.endInclusive, instrument), valueTop)
        axisText(formatIndicatorValue(kind, bounds.start, instrument), valueBottom)
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
        val title = textMeasurer.measure(item.config.label, labelStyle.copy(color = palette.Muted))
        drawText(title, topLeft = Offset(cursor, titleTop))
        cursor += title.size.width + labelPadding * 3
        item.series.forEach { s ->
            val value = formatIndicatorValue(kind, s.values.getOrNull(readoutIndex) ?: Double.NaN, instrument)
            val color = if (s.line.histogram) palette.Ink else parseHexColor(s.color) ?: palette.Ink
            val layout = textMeasurer.measure(value, labelStyle.copy(color = color, fontWeight = FontWeight.Medium))
            drawText(layout, topLeft = Offset(cursor, titleTop))
            cursor += layout.size.width + labelPadding * 3
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
