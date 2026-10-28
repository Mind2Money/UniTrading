package com.unitrading.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
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

/** Height under the price plot for [panes] separate indicators: a fifth of [height] each, at most half of it. */
internal fun paneAreaHeight(height: Float, panes: Int): Float = if (panes <= 0) 0f else height * minOf(.2f * panes, .5f)

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
 * Separate panes stacked from [top] down, [paneHeight] each. Every pane owns its range, dashed guides, right-axis
 * bounds and a title with the values at [readoutIndex].
 */
internal fun DrawScope.drawIndicatorPanes(
    panes: List<ComputedIndicator>,
    range: IntRange,
    readoutIndex: Int,
    instrument: Instrument,
    left: Float,
    right: Float,
    top: Float,
    paneHeight: Float,
    step: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    palette: TradingPalette,
    x: (Int) -> Float,
) {
    val labelPadding = 3.dp.toPx()
    val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
    panes.forEachIndexed { paneIndex, item ->
        val paneTop = top + paneIndex * paneHeight
        val paneBottom = paneTop + paneHeight
        val valueTop = paneTop + labelHeight + labelPadding * 3
        val valueBottom = paneBottom - labelPadding * 2
        if (valueBottom <= valueTop) return@forEachIndexed
        val kind = item.config.kind
        val bounds = paneRange(kind, item.series, range)
        fun y(v: Double) = valueBottom - ((v - bounds.start) / (bounds.endInclusive - bounds.start) * (valueBottom - valueTop)).toFloat()
        drawLine(palette.Border, Offset(left, paneTop), Offset(right, paneTop), 1f)
        kind.guides.filter { it in bounds }.forEach { guide ->
            drawLine(palette.Grid, Offset(left, y(guide)), Offset(right, y(guide)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
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
        modifier.background(colors.Surface.copy(alpha = .8f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        readouts.forEach { readout ->
            Row(Modifier.testTag("indicator-readout"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(readout.title, fontSize = 11.sp, color = colors.Muted)
                readout.values.forEach { (value, hex) -> Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = parseHexColor(hex) ?: colors.Ink) }
            }
        }
    }
}
