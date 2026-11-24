package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unitrading.app.ReplayRecord
import com.unitrading.app.tradeStatistics
import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.math.abs
import kotlin.time.Instant

private val ReplayBlue = Color(0xFF087BEC)
private val ReplayGreen = Color(0xFF059D77)
private val ReplayRed = Color(0xFFE96760)

/** A replay report never uses the connected brokerage account. */
@Composable
internal fun ReplayDashboard(record: ReplayRecord, modifier: Modifier = Modifier, onDescriptionChange: (String) -> Unit = {}) {
    val zone = remember { TimeZone.of("America/Chicago") }
    var editingDescription by remember(record.id) { mutableStateOf(false) }
    var descriptionDraft by remember(record.id) { mutableStateOf(record.description) }
    val ordered = remember(record.trades) { record.trades.sortedBy { it.closedAtMs } }
    val stats = remember(ordered) { tradeStatistics(ordered) }
    fun date(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    fun dateTime(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).toString().replace('T', ' ').take(19)
    val dated = remember(ordered) { ordered.map { date(it.closedAtMs) to (it.netPnlMicros ?: 0L) } }
    val monthGroups = remember(dated) { dated.groupBy { it.first.toString().take(7) }.toList().sortedBy { it.first }.toMap() }
    val dayGroups = remember(dated) { dated.groupBy { it.first } }
    val weekGroups = remember(dated) { dated.groupBy { it.first.minus(it.first.dayOfWeek.ordinal, DateTimeUnit.DAY) } }
    val weekdays = remember(dated) { (0..6).map { i -> dated.filter { it.first.dayOfWeek.ordinal == i }.sumOf { it.second } } }
    val average = { count: Int -> stats.netMicros?.takeIf { count > 0 }?.div(count) }
    val balance = record.finalEquityMicros ?: stats.netMicros?.let { runCatching { checkedAdd(record.initialMicros, it) }.getOrNull() } ?: record.initialMicros
    val winRate = stats.winRate?.let { formatFixed((it * 100).toLong(), 2) + "%" } ?: "—"
    val rr = stats.averageWinLoss?.let { formatFixed((it * 100).toLong(), 2) } ?: "—"
    val equity = remember(record) {
        if (record.equity.isNotEmpty()) record.equity.map { it.equityMicros }
        else {
            var v = record.initialMicros
            listOf(v) + ordered.map { trade -> v = runCatching { checkedAdd(v, trade.netPnlMicros ?: 0L) }.getOrDefault(v); v }
        }
    }
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("replay-dashboard"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(record.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text("${record.status} · ${ordered.size} 笔已平仓交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val first = ordered.firstOrNull()?.openedAtMs
                        val last = record.endedAtMs ?: ordered.lastOrNull()?.closedAtMs
                        if (first != null && last != null) Text("${date(first)} — ${date(last)} · 芝加哥时间", style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("账户净值 · ${record.currency}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(balance), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("回放备注", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            if (editingDescription) onDescriptionChange(descriptionDraft)
                            editingDescription = !editingDescription
                        }, modifier = Modifier.testTag("replay-description-edit")) { Text(if (editingDescription) "保存" else "编辑") }
                    }
                    if (editingDescription) OutlinedTextField(descriptionDraft, { descriptionDraft = it }, Modifier.fillMaxWidth().testTag("replay-description"), label = { Text("策略或复盘备注") })
                    else Text(record.description.ifBlank { "暂无备注" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 900.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReplayPlotCard("净值曲线", Modifier.weight(1f)) { EquityPlot(equity) }
                    ReplayPlotCard("月度表现", Modifier.weight(1f)) { MonthlyPlot(monthGroups.mapValues { (_, rows) -> rows.map { it.second } }) }
                    ReplayPlotCard("星期表现", Modifier.weight(1f)) { WeekdayPlot(weekdays) }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReplayPlotCard("净值曲线", Modifier.fillMaxWidth()) { EquityPlot(equity) }
                    ReplayPlotCard("月度表现", Modifier.fillMaxWidth()) { MonthlyPlot(monthGroups.mapValues { (_, rows) -> rows.map { it.second } }) }
                    ReplayPlotCard("星期表现", Modifier.fillMaxWidth()) { WeekdayPlot(weekdays) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplayDashboardMetric("总盈亏", stats.netMicros?.let { money(it, true) } ?: "—", Modifier.weight(1f))
                    ReplayDashboardMetric("胜率", winRate, Modifier.weight(1f))
                    ReplayDashboardMetric("平均盈亏比", rr, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplayDashboardMetric("月均盈亏", average(monthGroups.size)?.let { money(it, true) } ?: "—", Modifier.weight(1f))
                    ReplayDashboardMetric("周均盈亏", average(weekGroups.size)?.let { money(it, true) } ?: "—", Modifier.weight(1f))
                    ReplayDashboardMetric("日均盈亏", average(dayGroups.size)?.let { money(it, true) } ?: "—", Modifier.weight(1f))
                }
                Text("平均盈亏比 = 平均盈利 / 平均亏损绝对值；日、周、月均值按有平仓交易的自然日、周和月计算。曲线为回放采样净值，可能包含浮动盈亏。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("交易记录 · ${ordered.size} 笔", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("replay-report-trade-count")) }
        if (ordered.isEmpty()) item { Text("暂无已平仓交易", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(ordered.asReversed(), key = { it.id }) { trade ->
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${trade.symbol} · ${if (trade.side == Side.BUY) "多" else "空"} · ${formatFixed(trade.lotsHundredths, 2)}", fontWeight = FontWeight.Medium)
                        val entry = record.priceDecimals?.let { formatFixed(trade.entryPrice, it) } ?: "—"
                        val exit = record.priceDecimals?.let { formatFixed(trade.exitPrice, it) } ?: "—"
                        Text("平仓 ${dateTime(trade.closedAtMs)} · $entry → $exit · ${trade.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(trade.netPnlMicros?.let { money(it, true) } ?: "—", color = if ((trade.netPnlMicros ?: 0) >= 0) ReplayGreen else ReplayRed)
                }
            }
        }
    }
}

@Composable
private fun ReplayDashboardMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ReplayPlotCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.height(250.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}

@Composable
private fun ColumnScope.EquityPlot(values: List<Long>) {
    if (values.isEmpty()) { Text("暂无数据"); return }
    val low = values.minOrNull() ?: 0L; val high = values.maxOrNull() ?: 0L
    Text("${money(low)} — ${money(high)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().weight(1f).testTag("replay-equity-plot")) {
        val spread = (high.toDouble() - low.toDouble()).coerceAtLeast(1.0)
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = if (values.size == 1) 0f else size.width * i / (values.size - 1)
            val y = size.height - ((value.toDouble() - low.toDouble()) / spread).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ReplayBlue, style = Stroke(width = 2.dp.toPx()))
    }
    Text("回放时间 →", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun MonthlyPlot(months: Map<String, List<Long>>) {
    val rows = months.entries.toList().takeLast(8)
    if (rows.isEmpty()) { Text("暂无平仓交易"); return }
    val maxValue = rows.maxOf { (_, values) -> maxOf(values.filter { it > 0 }.sum().toDouble(), -values.filter { it < 0 }.sum().toDouble()) }.coerceAtLeast(1.0)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        rows.forEach { (month, values) ->
            val positive = values.filter { it > 0 }.sum().toDouble(); val negative = -values.filter { it < 0 }.sum().toDouble()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(month, Modifier.width(68.dp), style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.weight(1f).height(18.dp)) {
                    val mid = size.width / 2f
                    drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height), 1f)
                    val red = (mid * negative / maxValue).toFloat(); val green = (mid * positive / maxValue).toFloat()
                    drawRect(ReplayRed, Offset(mid - red, 0f), Size(red, size.height))
                    drawRect(ReplayGreen, Offset(mid, 0f), Size(green, size.height))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WeekdayPlot(values: List<Long>) {
    val scale = values.maxOfOrNull { abs(it.toDouble()) }?.coerceAtLeast(1.0) ?: 1.0
    Canvas(Modifier.fillMaxWidth().weight(1f).testTag("replay-weekday-plot")) {
        val mid = size.height / 2f
        drawLine(Color.Gray, Offset(0f, mid), Offset(size.width, mid), 1f)
        val slot = size.width / values.size
        values.forEachIndexed { i, value ->
            val h = (mid * abs(value.toDouble()) / scale).toFloat()
            drawRect(if (value >= 0) ReplayBlue else ReplayRed, Offset(i * slot + slot * .18f, if (value >= 0) mid - h else mid), Size(slot * .64f, h))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, style = MaterialTheme.typography.labelSmall) } }
    Text("按平仓日汇总 · 芝加哥时间", style = MaterialTheme.typography.labelSmall)
}
