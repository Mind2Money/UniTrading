package com.unitrading.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import com.unitrading.ui.ActionIcon
import com.unitrading.ui.AppIcons
import com.unitrading.ui.SegmentedChoice
import com.unitrading.ui.StatusDot
import com.unitrading.ui.TradingColors
import com.unitrading.ui.label
import kotlin.math.abs
import kotlin.math.roundToLong

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

/**
 * Chart controls: symbol, interval, indicators and layers. Wide layouts float them over the plot with the favourite
 * intervals inline; phones pin one row above the plot, scrolling sideways when it is too narrow, with [trailing] (the
 * account environment) fixed at the end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartToolbar(
    instrument: Instrument,
    instruments: List<Instrument>,
    onSymbol: (String) -> Unit,
    timeframe: Timeframe,
    onTimeframe: (Timeframe) -> Unit,
    intervals: IntervalOptions,
    layers: List<ChartToggle>,
    indicators: ChartToggle?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val controls: @Composable () -> Unit = {
        SymbolChip(instrument, instruments, onSymbol)
        IntervalControl(timeframe, onTimeframe, intervals, inline = !compact)
        indicators?.let { IndicatorChip(it, compact) }
        LayerMenu(layers, compact)
    }
    if (compact) Row(modifier.height(48.dp).padding(start = 8.dp, end = 4.dp).testTag("chart-toolbar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
        trailing()
    } else FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { controls() }
}

/** Chart control chip; it stays legible when it floats over the candles. */
@Composable
private fun ToolbarChip(label: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier, active: Boolean = false,
    leading: ImageVector? = null, trailing: ImageVector? = null) {
    val scheme = MaterialTheme.colorScheme
    AssistChip(onClick, { Text(label, maxLines = 1) }, modifier.testTag(tag),
        leadingIcon = leading?.let { icon -> { Icon(icon, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) } },
        trailingIcon = trailing?.let { icon -> { Icon(icon, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) } },
        colors = AssistChipDefaults.assistChipColors(containerColor = if (active) scheme.secondaryContainer else scheme.surfaceContainerHigh.copy(alpha = .94f),
            labelColor = if (active) scheme.onSecondaryContainer else scheme.onSurface, leadingIconContentColor = scheme.onSurfaceVariant, trailingIconContentColor = scheme.onSurfaceVariant),
        border = BorderStroke(1.dp, scheme.outlineVariant))
}

/** Phones keep the toolbar to one row: indicators and layers shrink to their icons, with the same labels for screen readers. */
@Composable
private fun IndicatorChip(toggle: ChartToggle, compact: Boolean) {
    if (compact) ActionIcon(AppIcons.Tune, toggle.label, "chart-indicators", onClick = toggle.onToggle,
        tint = if (toggle.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    else ToolbarChip(toggle.label, toggle.onToggle, "chart-indicators", active = toggle.active, leading = AppIcons.Tune)
}

/** Chart layers and the OHLC table behind one menu; it stays open so several layers can be switched in a row. */
@Composable
private fun LayerMenu(layers: List<ChartToggle>, compact: Boolean) {
    val colors = TradingColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (compact) ActionIcon(AppIcons.Layers, "图层", "chart-layers", tint = MaterialTheme.colorScheme.onSurfaceVariant) { expanded = true }
        else ToolbarChip("图层", { expanded = true }, "chart-layers", leading = AppIcons.Layers, trailing = AppIcons.ExpandMore)
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 160.dp)) {
            layers.forEachIndexed { index, layer ->
                DropdownMenuItem(
                    text = { Text(layer.label, color = if (layer.active) colors.Ink else colors.Muted) },
                    onClick = layer.onToggle,
                    trailingIcon = { if (layer.active) Icon(AppIcons.Check, contentDescription = "已显示", tint = colors.Accent) },
                    modifier = Modifier.testTag("layer-$index"),
                )
            }
        }
    }
}

/** Current symbol with a menu over the market page's watch list, in its order. */
@Composable
private fun SymbolChip(instrument: Instrument, instruments: List<Instrument>, onSymbol: (String) -> Unit) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    var expanded by remember { mutableStateOf(false) }
    var query by remember(expanded) { mutableStateOf("") }
    Box {
        ToolbarChip(instrument.symbol, { expanded = true }, "chart-symbol", Modifier.semantics { contentDescription = "选择品种" }, trailing = AppIcons.Search)
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            MenuTextField(query, { query = it }, "筛选自选品种", "symbol-filter", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            val matches = instruments.filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
            if (matches.isEmpty()) Text("自选中没有该品种，请在行情页添加", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = type.bodySmall, color = colors.Muted)
            matches.take(40).forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(candidate.symbol, style = type.titleSmall)
                            Text("${candidate.name} · ${candidate.category}", style = type.bodySmall, color = colors.Muted)
                        }
                    },
                    onClick = { expanded = false; onSymbol(candidate.symbol) },
                    trailingIcon = { if (candidate.symbol == instrument.symbol) Icon(AppIcons.Check, contentDescription = "当前品种", tint = colors.Accent) },
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
private fun IntervalControl(timeframe: Timeframe, onTimeframe: (Timeframe) -> Unit, intervals: IntervalOptions, inline: Boolean) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (inline) ToolbarPill(Modifier.height(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = if (timeframe in intervals.favorites) intervals.favorites else intervals.favorites + timeframe
                shown.forEach { frame -> PillSegment(frame.label, frame == timeframe, Modifier.testTag("interval-inline-${frame.minutes}")) { onTimeframe(frame) } }
                Box(Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { expanded = true }
                    .semantics { contentDescription = "选择时间周期" }.testTag("interval-menu").padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Icon(AppIcons.ExpandMore, contentDescription = null, Modifier.size(18.dp), tint = colors.Muted)
                }
            }
        } else ToolbarChip(timeframe.label, { expanded = true }, "interval-current", trailing = AppIcons.ExpandMore)
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 220.dp)) {
            CustomIntervalRow { frame -> intervals.onAddCustom(frame); onTimeframe(frame); expanded = false }
            val listed = (Timeframe.presets + intervals.custom + intervals.favorites + timeframe).distinct().sortedBy { it.minutes }
            listOf("分钟" to (1 until 60), "小时" to (60 until 1440), "天" to (1440..Timeframe.MAX_MINUTES)).forEach { (title, range) ->
                val members = listed.filter { it.minutes in range }
                if (members.isEmpty()) return@forEach
                Text(title, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp), style = type.labelMedium, color = colors.Muted)
                members.forEach { frame ->
                    val starred = frame in intervals.favorites
                    val current = frame == timeframe
                    DropdownMenuItem(
                        text = { Text(frame.label, style = if (current) type.titleSmall else type.bodyMedium, color = if (current) colors.Accent else colors.Ink) },
                        onClick = { expanded = false; onTimeframe(frame) },
                        trailingIcon = {
                            ActionIcon(if (starred) AppIcons.Star else AppIcons.StarBorder, if (starred) "取消收藏 ${frame.label}" else "收藏 ${frame.label}",
                                "interval-favorite-${frame.minutes}", tint = if (starred) colors.Accent else colors.Muted) { intervals.onToggleFavorite(frame) }
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
    val type = MaterialTheme.typography
    var count by remember { mutableStateOf("") }
    var unitMinutes by remember { mutableStateOf(1) }
    val candidate = count.toIntOrNull()?.let { Timeframe.of(it, unitMinutes) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("添加自定义周期", style = type.labelMedium, color = TradingColors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuTextField(count, { if (it.length <= 4 && it.all(Char::isDigit)) count = it }, "数量", "interval-custom-count", Modifier.weight(1f))
            TextButton({ candidate?.let(onAdd) }, Modifier.testTag("interval-custom-add"), enabled = candidate != null) { Text("添加") }
        }
        SegmentedChoice(listOf(1, 60, 1440), unitMinutes, { when (it) { 1 -> "分钟"; 60 -> "小时"; else -> "天" } }, { "interval-custom-unit-$it" },
            { unitMinutes = it }, Modifier.fillMaxWidth(), height = 32.dp)
    }
}

/** Zoom and scroll controls floating above the time axis; [compact] keeps zoom and reset, since phones pan by dragging. */
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
    val button = Modifier.size(36.dp)
    ToolbarPill(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(AppIcons.ZoomIn, "放大图表", "chart-zoom-in", button, iconSize = 20.dp, onClick = onZoomIn)
            ActionIcon(AppIcons.ZoomOut, "缩小图表", "chart-zoom-out", button, iconSize = 20.dp, onClick = onZoomOut)
            if (!compact) {
                ActionIcon(AppIcons.ChevronLeft, "较早", "chart-earlier", button, iconSize = 20.dp, onClick = onEarlier)
                ActionIcon(AppIcons.ChevronRight, "较新", "chart-later", button, iconSize = 20.dp, onClick = onLater)
            }
            ActionIcon(AppIcons.RestartAlt, "重置视图", "chart-reset", button, iconSize = 20.dp, onClick = onReset)
        }
    }
}

/**
 * Legend in the top-left corner of the plot: instrument title, hovered-bar OHLC and change, Bid/Ask trade buttons with
 * the spread, volume, and a collapse toggle. The card itself does not take pointer input, so panning that starts over
 * its text still reaches the chart; only the buttons consume clicks. [compact] (phones) leaves the trade buttons to the
 * order bar below the chart and folds the volume into the OHLC line.
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
    val type = MaterialTheme.typography
    @Composable
    fun Figure(label: String, value: String, color: Color) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = type.labelSmall, color = colors.Muted)
        Text(value, style = type.labelSmall, color = color)
    }
    Column(modifier.background(colors.Surface.copy(alpha = .9f), MaterialTheme.shapes.small)
        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("chart-legend"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${instrument.name} · ${timeframe.label} · ${instrument.category}", Modifier.weight(1f, fill = false), style = type.labelMedium)
            StatusDot(if (connected) colors.Profit else colors.Loss, Modifier.semantics { contentDescription = if (connected) "行情已连接" else "行情未连接" })
            ActionIcon(if (collapsed) AppIcons.ExpandMore else AppIcons.ExpandLess, if (collapsed) "展开图例" else "收起图例", "legend-toggle", Modifier.size(28.dp), tint = colors.Muted, iconSize = 20.dp, onClick = onToggleCollapsed)
        }
        // A line drag's estimate shows even when the legend is folded: it is the only live feedback until the drag ends.
        if (readout != null) Text(readout, Modifier.testTag("chart-readout"), style = type.labelSmall)
        if (!collapsed) {
            if (readout == null && bar != null) {
                val tone = if (bar.close >= bar.open) colors.UpText else colors.DownText
                val change = barChange(bar, previous)
                FlowRow(Modifier.testTag("chart-readout").padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("开" to bar.open, "高" to bar.high, "低" to bar.low, "收" to bar.close).forEach { (label, value) -> Figure(label, instrument.formatPrice(value), tone) }
                    Text(change.label(instrument), style = type.labelSmall, color = if (change.diff >= 0) colors.UpText else colors.DownText)
                    Figure("量", formatVolume(bar.volume), colors.Ink)
                    Text("${dateTime(bar.timeMs)} UTC", style = type.labelSmall, color = colors.Muted)
                }
            }
            if (!compact) Row(Modifier.padding(top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeButton(Side.SELL, quote?.let { instrument.formatPrice(it.bid) } ?: "—", colors.DownText, Modifier.testTag("legend-sell")) { onSide(Side.SELL) }
                Text(quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—", style = type.labelSmall, color = colors.Muted)
                TradeButton(Side.BUY, quote?.let { instrument.formatPrice(it.ask) } ?: "—", colors.UpText, Modifier.testTag("legend-buy")) { onSide(Side.BUY) }
            }
        }
    }
}

/** Grouped controls on a chip-like surface. */
@Composable
private fun ToolbarPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerHigh.copy(alpha = .94f), border = BorderStroke(1.dp, scheme.outlineVariant), content = content)
}

@Composable
private fun PillSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxHeight().background(if (selected) scheme.secondaryContainer else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun MenuTextField(value: String, onValue: (String) -> Unit, placeholder: String, tag: String, modifier: Modifier = Modifier) {
    val colors = TradingColors
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    BasicTextField(
        value, onValue, modifier.testTag(tag), singleLine = true,
        textStyle = TextStyle(color = colors.Ink).merge(style), cursorBrush = SolidColor(colors.Accent),
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 40.dp).background(scheme.surfaceContainerHighest, MaterialTheme.shapes.small).border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.Muted)
                inner()
            }
        },
    )
}

/** Bid or Ask price over its side; picks the order side and never submits an order by itself. */
@Composable
internal fun TradeButton(side: Side, price: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Column(
        modifier.clip(shape).background(color.copy(alpha = .14f)).border(1.dp, color.copy(alpha = .6f), shape)
            .clickable(role = Role.Button, onClick = onClick).widthIn(min = 84.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(price, style = MaterialTheme.typography.titleSmall, color = color)
        Text(side.label(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
