package com.unitrading.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.ui.TradingColors
import com.unitrading.ui.label
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class ChartToggle(val label: String, val active: Boolean, val onToggle: () -> Unit)

/** Close against the previous close (or the bar's own open when there is no previous bar); percent kept as integer basis points. */
data class BarChange(val diff: Long, val basisPoints: Long) {
    fun label(instrument: Instrument): String {
        val sign = if (diff < 0) "-" else "+"
        val percentSign = if (basisPoints < 0) "-" else "+"
        return "$sign${instrument.formatPrice(abs(diff))} ($percentSign${formatFixed(abs(basisPoints), 2)}%)"
    }
}

fun barChange(bar: Candle, previous: Candle?): BarChange {
    val base = previous?.close ?: bar.open
    val diff = bar.close - base
    val basisPoints = if (base == 0L) 0L else (diff.toDouble() / base * 10_000).roundToLong()
    return BarChange(diff, basisPoints)
}

fun formatVolume(volume: Long?): String = when {
    volume == null || volume < 0 -> "—"
    volume >= 1_000_000 -> formatFixed((volume * 100 + 500_000) / 1_000_000, 2) + "M"
    volume >= 1_000 -> formatFixed((volume * 100 + 500) / 1_000, 2) + "K"
    else -> volume.toString()
}

/** Time left in the bar that opened at [barOpenMs]; null when [nowMs] falls outside that bar (stale data, closed market). */
fun barCountdown(barOpenMs: Long, periodMs: Long, nowMs: Long): String? {
    val remaining = barOpenMs + periodMs - nowMs
    if (periodMs <= 0 || remaining < 0 || remaining > periodMs) return null
    val total = remaining / 1_000
    val minutes = (total / 60 % 60).toString().padStart(2, '0')
    val seconds = (total % 60).toString().padStart(2, '0')
    val hours = total / 3_600
    return if (hours > 0) "$hours:$minutes:$seconds" else "$minutes:$seconds"
}

/** Symbol pill, timeframe segments and layer toggles; floats over the plot or sits above the data table. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartToolbar(
    instrument: Instrument,
    timeframe: Timeframe,
    onTimeframe: (Timeframe) -> Unit,
    toggles: List<ChartToggle>,
    onSymbolSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolbarPill {
            Row(
                Modifier.then(if (onSymbolSearch != null) Modifier.clickable(role = Role.Button, onClick = onSymbolSearch) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag("chart-symbol"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(instrument.symbol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Ink)
                if (onSymbolSearch != null) SearchGlyph(colors.Muted)
            }
        }
        ToolbarPill {
            Row { Timeframe.entries.forEach { frame -> PillSegment(frame.label, frame == timeframe, colors.Ink) { onTimeframe(frame) } } }
        }
        ToolbarPill {
            Row { toggles.forEach { toggle -> PillSegment(toggle.label, toggle.active, colors.Accent, toggle.onToggle) } }
        }
    }
}

/** Zoom and scroll controls floating above the time axis. */
@Composable
internal fun ChartNavigation(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    ToolbarPill(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSegment("＋", "放大图表", onZoomIn)
            GlyphSegment("－", "缩小图表", onZoomOut)
            PillSegment("较早", false, colors.Ink, onEarlier)
            PillSegment("较新", false, colors.Ink, onLater)
            PillSegment("重置视图", false, colors.Ink, onReset)
        }
    }
}

/**
 * TradingView-style legend in the top-left corner of the plot: instrument title, hovered-bar OHLC and change,
 * Bid/Ask trade buttons with the spread, volume, and a collapse toggle. The card itself does not take pointer
 * input, so panning that starts over its text still reaches the chart; only the buttons consume clicks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(
    instrument: Instrument,
    timeframe: Timeframe,
    bar: Candle?,
    previous: Candle?,
    quote: Quote?,
    connected: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onSide: (Side) -> Unit,
    readout: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    Column(
        modifier.background(colors.Surface.copy(alpha = .8f), RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 6.dp).testTag("chart-legend"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${instrument.name} · ${timeframe.label} · ${instrument.category}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.Ink)
            Box(Modifier.size(7.dp).background(if (connected) colors.Profit else colors.Loss, CircleShape)
                .semantics { contentDescription = if (connected) "行情已连接" else "行情未连接" })
        }
        if (!collapsed) {
            if (readout != null) {
                Text(readout, Modifier.testTag("chart-readout"), fontSize = 11.sp, color = colors.Ink)
            } else if (bar != null) {
                val tone = if (bar.close >= bar.open) colors.Up else colors.Down
                val change = barChange(bar, previous)
                FlowRow(Modifier.testTag("chart-readout"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("开" to bar.open, "高" to bar.high, "低" to bar.low, "收" to bar.close).forEach { (label, value) ->
                        Row {
                            Text(label, fontSize = 11.sp, color = colors.Muted)
                            Spacer(Modifier.width(3.dp))
                            Text(instrument.formatPrice(value), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tone)
                        }
                    }
                    Text(change.label(instrument), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (change.diff >= 0) colors.Up else colors.Down)
                    Text("${dateTime(bar.timeMs)} UTC", fontSize = 10.sp, color = colors.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeButton(Side.SELL, quote?.let { instrument.formatPrice(it.bid) } ?: "—", colors.Down) { onSide(Side.SELL) }
                Text(quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—", fontSize = 11.sp, color = colors.Muted)
                TradeButton(Side.BUY, quote?.let { instrument.formatPrice(it.ask) } ?: "—", colors.Up) { onSide(Side.BUY) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("成交量", fontSize = 11.sp, color = colors.Muted)
                Text(formatVolume(bar?.volume), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.Ink)
            }
        }
        CollapseToggle(collapsed, onToggleCollapsed)
    }
}

@Composable
private fun ToolbarPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = TradingColors
    Surface(modifier, shape = RoundedCornerShape(7.dp), color = colors.Surface.copy(alpha = .9f),
        border = BorderStroke(1.dp, colors.Border.copy(alpha = .75f)), content = content)
}

@Composable
private fun PillSegment(label: String, selected: Boolean, activeColor: Color, onClick: () -> Unit) {
    val colors = TradingColors
    Box(Modifier.padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (selected) colors.Raised else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, fontSize = 11.sp, color = if (selected) activeColor else colors.Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun GlyphSegment(glyph: String, description: String, onClick: () -> Unit) {
    val colors = TradingColors
    Box(Modifier.padding(2.dp).clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(glyph, fontSize = 13.sp, color = colors.Ink)
    }
}

@Composable
private fun TradeButton(side: Side, price: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .12f)).border(1.dp, color.copy(alpha = .8f), RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick).testTag(if (side == Side.BUY) "legend-buy" else "legend-sell")
            .widthIn(min = 78.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(price, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(side.label(), fontSize = 10.sp, color = color)
    }
}

@Composable
private fun CollapseToggle(collapsed: Boolean, onClick: () -> Unit) {
    val colors = TradingColors
    Box(
        Modifier.size(22.dp, 16.dp).clip(RoundedCornerShape(3.dp)).border(1.dp, colors.Border, RoundedCornerShape(3.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (collapsed) "展开图例" else "收起图例" }.testTag("legend-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(8.dp, 5.dp)) {
            val path = Path().apply {
                if (collapsed) { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
                else { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            }
            drawPath(path, colors.Muted, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val radius = size.minDimension * .34f
        drawCircle(color, radius, Offset(radius + 1f, radius + 1f), style = Stroke(1.6f))
        drawLine(color, Offset(radius * 1.7f + 1f, radius * 1.7f + 1f), Offset(size.width - 1f, size.height - 1f), 1.8f, cap = StrokeCap.Round)
    }
}
