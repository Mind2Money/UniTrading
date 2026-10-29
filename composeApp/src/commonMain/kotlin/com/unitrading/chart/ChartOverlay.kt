package com.unitrading.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.ui.TradingColors
import com.unitrading.ui.label
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

internal data class ChartToggle(val label: String, val active: Boolean, val onToggle: () -> Unit)

/** Interval menu state: starred intervals show inline on the toolbar, custom intervals join the dropdown. */
data class IntervalOptions(
    val favorites: List<Timeframe> = Timeframe.defaultFavorites,
    val custom: List<Timeframe> = emptyList(),
    val onToggleFavorite: (Timeframe) -> Unit = {},
    val onAddCustom: (Timeframe) -> Unit = {},
)

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

/** Time-axis label: clock time for intraday bars, calendar day for daily and longer bars. */
fun axisTimeLabel(timeMs: Long, timeframe: Timeframe): String =
    if (timeframe.minutes >= 1440) dateTime(timeMs).substring(5, 10) else clockLabel(timeMs)

/** Symbol picker, interval picker and layer toggles; floats over the plot or sits above the data table. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartToolbar(
    instrument: Instrument,
    instruments: List<Instrument>,
    onSymbol: (String) -> Unit,
    timeframe: Timeframe,
    onTimeframe: (Timeframe) -> Unit,
    intervals: IntervalOptions,
    toggles: List<ChartToggle>,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SymbolPill(instrument, instruments, onSymbol)
        IntervalPill(timeframe, onTimeframe, intervals)
        ToolbarPill {
            Row { toggles.forEach { toggle -> PillSegment(toggle.label, toggle.active, colors.Accent, onClick = toggle.onToggle) } }
        }
    }
}

/**
 * Phone toolbar: one row pinned above the plot instead of the floating toolbar. Symbol, current interval, indicators
 * and the layer menu scroll sideways when the row is too narrow; [trailing] (the account environment) stays at the end.
 */
@Composable
internal fun CompactChartToolbar(
    instrument: Instrument,
    instruments: List<Instrument>,
    onSymbol: (String) -> Unit,
    timeframe: Timeframe,
    onTimeframe: (Timeframe) -> Unit,
    intervals: IntervalOptions,
    layers: List<ChartToggle>,
    indicators: ChartToggle?,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    Row(modifier.height(48.dp).padding(start = 8.dp, end = 10.dp).testTag("chart-toolbar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SymbolPill(instrument, instruments, onSymbol)
            IntervalPill(timeframe, onTimeframe, intervals, inline = false)
            indicators?.let { toggle ->
                ToolbarPill { PillSegment(toggle.label, toggle.active, colors.Accent, Modifier.testTag("chart-indicators"), onClick = toggle.onToggle) }
            }
            LayerMenu(layers)
        }
        trailing()
    }
}

/** Chart layers and the OHLC table behind one menu; it stays open so several layers can be switched in a row. */
@Composable
private fun LayerMenu(layers: List<ChartToggle>) {
    val colors = TradingColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarPill {
            Row(
                Modifier.clickable(role = Role.Button) { expanded = true }.testTag("chart-layers").padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("图层", fontSize = 11.sp, color = colors.Ink)
                Chevron(down = true, colors.Muted)
            }
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 150.dp)) {
            layers.forEachIndexed { index, layer ->
                DropdownMenuItem(
                    text = { Text(layer.label, fontSize = 12.sp, color = if (layer.active) colors.Ink else colors.Muted) },
                    onClick = layer.onToggle,
                    trailingIcon = { Text(if (layer.active) "✓" else "", color = colors.Accent, fontSize = 12.sp) },
                    modifier = Modifier.testTag("layer-$index"),
                )
            }
        }
    }
}

/** Current symbol with a TradingView-style search menu over the broker's instruments. */
@Composable
private fun SymbolPill(instrument: Instrument, instruments: List<Instrument>, onSymbol: (String) -> Unit) {
    val colors = TradingColors
    var expanded by remember { mutableStateOf(false) }
    var query by remember(expanded) { mutableStateOf("") }
    Box {
        ToolbarPill {
            Row(
                Modifier.clickable(role = Role.Button) { expanded = true }.padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "选择品种" }.testTag("chart-symbol"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(instrument.symbol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Ink)
                SearchGlyph(colors.Muted)
            }
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            MenuTextField(query, { query = it }, "搜索品种代码或名称", "symbol-filter", Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
            val matches = instruments.filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
            if (matches.isEmpty()) Text("未找到品种", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 11.sp, color = colors.Muted)
            matches.take(40).forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(candidate.symbol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Ink)
                            Text("${candidate.name} · ${candidate.category}", fontSize = 10.sp, color = colors.Muted)
                        }
                    },
                    onClick = { expanded = false; onSymbol(candidate.symbol) },
                    trailingIcon = { if (candidate.symbol == instrument.symbol) Text("✓", color = colors.Accent, fontSize = 12.sp) },
                    modifier = Modifier.testTag("symbol-option-${candidate.symbol}"),
                )
            }
        }
    }
}

/**
 * Starred intervals inline plus a dropdown with minute / hour / day presets, favourites and custom intervals.
 * Without [inline] only the current interval shows, and tapping it opens the dropdown.
 */
@Composable
private fun IntervalPill(timeframe: Timeframe, onTimeframe: (Timeframe) -> Unit, intervals: IntervalOptions, inline: Boolean = true) {
    val colors = TradingColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarPill {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inline) {
                    val shown = if (timeframe in intervals.favorites) intervals.favorites else intervals.favorites + timeframe
                    shown.forEach { frame -> PillSegment(frame.label, frame == timeframe, colors.Ink, Modifier.testTag("interval-inline-${frame.minutes}")) { onTimeframe(frame) } }
                } else PillSegment(timeframe.label, true, colors.Ink, Modifier.testTag("interval-current")) { expanded = true }
                Box(
                    Modifier.padding(2.dp).clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button) { expanded = true }
                        .semantics { contentDescription = "选择时间周期" }.testTag("interval-menu").padding(horizontal = 7.dp, vertical = 8.dp),
                ) { Chevron(down = true, colors.Muted) }
            }
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 210.dp)) {
            CustomIntervalRow { frame -> intervals.onAddCustom(frame); onTimeframe(frame); expanded = false }
            val listed = (Timeframe.presets + intervals.custom + intervals.favorites + timeframe).distinct().sortedBy { it.minutes }
            listOf("分钟" to (1 until 60), "小时" to (60 until 1440), "天" to (1440..Timeframe.MAX_MINUTES)).forEach { (title, range) ->
                val members = listed.filter { it.minutes in range }
                if (members.isEmpty()) return@forEach
                Text(title, Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp), fontSize = 10.sp, color = colors.Muted)
                members.forEach { frame ->
                    val starred = frame in intervals.favorites
                    val current = frame == timeframe
                    DropdownMenuItem(
                        text = { Text(frame.label, fontSize = 12.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = if (current) colors.Accent else colors.Ink) },
                        onClick = { expanded = false; onTimeframe(frame) },
                        trailingIcon = {
                            Text(
                                if (starred) "★" else "☆",
                                Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Checkbox) { intervals.onToggleFavorite(frame) }
                                    .semantics { contentDescription = if (starred) "取消收藏 ${frame.label}" else "收藏 ${frame.label}" }
                                    .testTag("interval-favorite-${frame.minutes}").padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (starred) colors.Accent else colors.Muted, fontSize = 14.sp,
                            )
                        },
                        modifier = Modifier.testTag("interval-option-${frame.minutes}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomIntervalRow(onAdd: (Timeframe) -> Unit) {
    val colors = TradingColors
    var count by remember { mutableStateOf("") }
    var unitMinutes by remember { mutableStateOf(1) }
    val candidate = count.toIntOrNull()?.let { Timeframe.of(it, unitMinutes) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("添加自定义周期", fontSize = 10.sp, color = colors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuTextField(count, { if (it.length <= 4 && it.all(Char::isDigit)) count = it }, "数量", "interval-custom-count", Modifier.width(60.dp))
            listOf(1 to "分钟", 60 to "小时", 1440 to "天").forEach { (minutes, label) ->
                PillSegment(label, unitMinutes == minutes, colors.Ink, Modifier.testTag("interval-custom-unit-$minutes")) { unitMinutes = minutes }
            }
            PillSegment("添加", candidate != null, colors.Accent, Modifier.testTag("interval-custom-add")) { candidate?.let(onAdd) }
        }
    }
}

/** Zoom and scroll controls floating above the time axis; [compact] keeps zoom and an icon-only reset, since phones pan by dragging. */
@Composable
internal fun ChartNavigation(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = TradingColors
    ToolbarPill(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSegment("＋", "放大图表", onZoomIn)
            GlyphSegment("－", "缩小图表", onZoomOut)
            if (compact) ResetSegment(onReset) else {
                PillSegment("较早", false, colors.Ink, onClick = onEarlier)
                PillSegment("较新", false, colors.Ink, onClick = onLater)
                PillSegment("重置视图", false, colors.Ink, onClick = onReset)
            }
        }
    }
}

/** Circular arrow for "reset view", drawn rather than taken from a font so it renders the same on every device. */
@Composable
private fun ResetSegment(onClick: () -> Unit) {
    val colors = TradingColors
    Box(Modifier.padding(2.dp).clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "重置视图" }.testTag("chart-reset").padding(horizontal = 8.dp, vertical = 6.dp)) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 1.5.dp.toPx()
            val radius = size.minDimension / 2 - stroke
            // A clockwise arc from the top that stops short of it, with the arrow head at the open end.
            drawArc(colors.Ink, -90f, 300f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round))
            val end = 210.0 * PI / 180
            val tip = Offset(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat())
            val along = Offset(-sin(end).toFloat(), cos(end).toFloat())
            val across = Offset(-along.y, along.x)
            val head = 3.dp.toPx()
            drawPath(Path().apply {
                moveTo(tip.x + along.x * head, tip.y + along.y * head)
                lineTo(tip.x - along.x * head * .5f + across.x * head, tip.y - along.y * head * .5f + across.y * head)
                lineTo(tip.x - along.x * head * .5f - across.x * head, tip.y - along.y * head * .5f - across.y * head)
                close()
            }, colors.Ink)
        }
    }
}

/**
 * TradingView-style legend in the top-left corner of the plot: instrument title, hovered-bar OHLC and change,
 * Bid/Ask trade buttons with the spread, volume, and a collapse toggle. The card itself does not take pointer
 * input, so panning that starts over its text still reaches the chart; only the buttons consume clicks.
 * [compact] (phones) leaves the trade buttons to the order bar below the chart, folds the volume into the OHLC
 * line and puts the collapse toggle next to the title.
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
    compact: Boolean = false,
) {
    val colors = TradingColors
    Column(
        modifier.background(colors.Surface.copy(alpha = .92f), RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 10.dp, top = if (compact) 5.dp else 7.dp, bottom = if (compact) 5.dp else 6.dp).testTag("chart-legend"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${instrument.name} · ${timeframe.label} · ${instrument.category}", Modifier.weight(1f, fill = false),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.Ink)
            Box(Modifier.size(7.dp).background(if (connected) colors.Profit else colors.Loss, CircleShape)
                .semantics { contentDescription = if (connected) "行情已连接" else "行情未连接" })
            if (compact) CollapseToggle(collapsed, onToggleCollapsed)
        }
        // A line drag's estimate shows even when the legend is folded: it is the only live feedback until the drag ends.
        if (readout != null) {
            Text(readout, Modifier.testTag("chart-readout"), fontSize = 11.sp, color = colors.Ink)
        }
        if (!collapsed) {
            if (readout == null && bar != null) {
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
                    if (compact) Row {
                        Text("量", fontSize = 11.sp, color = colors.Muted)
                        Spacer(Modifier.width(3.dp))
                        Text(formatVolume(bar.volume), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.Ink)
                    }
                    Text("${dateTime(bar.timeMs)} UTC", fontSize = 10.sp, color = colors.Muted)
                }
            }
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TradeButton(Side.SELL, quote?.let { instrument.formatPrice(it.bid) } ?: "—", colors.Down, Modifier.testTag("legend-sell")) { onSide(Side.SELL) }
                    Text(quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—", fontSize = 11.sp, color = colors.Muted)
                    TradeButton(Side.BUY, quote?.let { instrument.formatPrice(it.ask) } ?: "—", colors.Up, Modifier.testTag("legend-buy")) { onSide(Side.BUY) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("成交量", fontSize = 11.sp, color = colors.Muted)
                    Text(formatVolume(bar?.volume), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.Ink)
                }
            }
        }
        if (!compact) CollapseToggle(collapsed, onToggleCollapsed)
    }
}

@Composable
private fun ToolbarPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = TradingColors
    Surface(modifier, shape = RoundedCornerShape(7.dp), color = colors.Surface.copy(alpha = .9f),
        border = BorderStroke(1.dp, colors.Border.copy(alpha = .75f)), content = content)
}

@Composable
private fun PillSegment(label: String, selected: Boolean, activeColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = TradingColors
    Box(modifier.padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (selected) colors.Raised else Color.Transparent)
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
private fun MenuTextField(value: String, onValue: (String) -> Unit, placeholder: String, tag: String, modifier: Modifier = Modifier) {
    val colors = TradingColors
    BasicTextField(
        value, onValue, modifier.testTag(tag), singleLine = true,
        textStyle = TextStyle(color = colors.Ink, fontSize = 12.sp), cursorBrush = SolidColor(colors.Accent),
        decorationBox = { inner ->
            Box(Modifier.background(colors.Raised.copy(alpha = .6f), RoundedCornerShape(6.dp)).border(1.dp, colors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = colors.Muted)
                inner()
            }
        },
    )
}

/** Bid or Ask price over its side; picks the order side and never submits an order by itself. */
@Composable
internal fun TradeButton(side: Side, price: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .12f)).border(1.dp, color.copy(alpha = .8f), RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
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
    ) { Chevron(down = collapsed, colors.Muted) }
}

@Composable
internal fun Chevron(down: Boolean, color: Color) {
    Canvas(Modifier.size(8.dp, 5.dp)) {
        val path = Path().apply {
            if (down) { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
            else { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
        }
        drawPath(path, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
