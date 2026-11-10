package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.math.roundToInt
import kotlin.time.Clock

private fun ratio(value: Double?, percent: Boolean = false): String = when {
    value == null -> "—"
    value.isInfinite() -> "∞"
    else -> formatFixed((value * 100).toLong(), 2) + if (percent) "%" else ""
}

@Composable
internal fun AccountAnalyticsPage(accounts: List<OverviewAccount>, modifier: Modifier = Modifier) {
    var scopeId by remember(accounts.map { it.info.id }) { mutableStateOf(accounts.firstOrNull()?.info?.id) }
    var symbol by remember { mutableStateOf<String?>(null) }
    var currencySelection by remember { mutableStateOf<String?>(null) }
    var zoneId by remember { mutableStateOf("America/Chicago") }
    val zone = remember(zoneId) { TimeZone.of(zoneId) }
    val today = Clock.System.now().toLocalDateTime(zone).date
    var month by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }
    var monthOnly by remember { mutableStateOf(true) }
    var cumulative by remember { mutableStateOf(true) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var showTrades by remember { mutableStateOf(false) }
    val effectiveScope = scopeId?.takeIf { id -> accounts.any { it.info.id == id } }
    val scoped = accounts.filter { effectiveScope == null || it.info.id == effectiveScope }
    val currencies = scoped.map { it.info.currency }.distinct().sorted()
    val currency = currencySelection?.takeIf { it in currencies } ?: currencies.firstOrNull()
    val currencyRecords = remember(scoped, currency) { analyticsTrades(scoped.filter { it.info.currency == currency }) }
    val symbols = currencyRecords.map { it.trade.symbol }.distinct().sorted()
    val effectiveSymbol = symbol?.takeIf { it in symbols }
    val filtered = remember(currencyRecords, effectiveSymbol) { currencyRecords.filter { effectiveSymbol == null || it.trade.symbol == effectiveSymbol } }
    val daily = remember(filtered, zone) { filtered.groupBy { it.date(zone) } }
    val monthRecords = remember(filtered, zone, month) { filtered.filter { val d = it.date(zone); d.year == month.year && d.month == month.month } }
    val records = if (monthOnly) monthRecords else filtered
    val stats = remember(records) { tradeStatistics(records) }
    val chartDays = remember(records, zone) { records.groupBy { it.date(zone) }.toList().sortedBy { it.first } }
    val colors = TradingColors
    val calendarCellHeight = (104 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    CompositionLocalProvider(LocalContentColor provides colors.Ink) {
    LazyColumn(modifier.background(colors.Shell).testTag("account-analytics"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("交易分析", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("${scoped.singleOrNull()?.info?.name ?: "所选子账户"} · 已加载平仓记录 · ${currency ?: "无账户"}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (accounts.size > 1) AnalyticsChoices(listOf<String?>(null) + accounts.map { it.info.id }, effectiveScope,
                    { id -> accounts.firstOrNull { it.info.id == id }?.info?.name ?: "全部子账户" }) { scopeId = it; selectedDay = null }
                if (currencies.size > 1) AnalyticsChoices(currencies, currency, { it }) { currencySelection = it; selectedDay = null }
                AnalyticsChoices(listOf<String?>(null) + symbols, effectiveSymbol, { it ?: "全部合约" }) { symbol = it; selectedDay = null }
                AnalyticsChoices(listOf("America/Chicago", "Asia/Shanghai", "UTC"), zoneId,
                    { when (it) { "America/Chicago" -> "芝加哥"; "Asia/Shanghai" -> "北京时间"; else -> "UTC" } }) { zoneId = it; selectedDay = null }
                Text("按所选时区的平仓自然日归类，非交易所结算日。分批平仓按券商返回的平仓记录计数。", color = colors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(monthOnly, { monthOnly = true }, label = { Text("日历月份") }, modifier = Modifier.testTag("analytics-range-month"))
                FilterChip(!monthOnly, { monthOnly = false }, label = { Text("全部已加载") }, modifier = Modifier.testTag("analytics-range-all"))
            }
            if (stats.unknown > 0) Text("${stats.unknown} 笔净盈亏待确认；以下盈亏、胜率和曲线仅包含 ${stats.known} 笔已知记录。", color = colors.Caution, style = MaterialTheme.typography.bodySmall)
            if (stats.known > 0 && stats.netMicros == null) Text("金额超出计算范围，未显示汇总。", color = colors.Caution)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("${if (stats.unknown > 0) "已知部分净盈亏" else "净盈亏"} · ${currency ?: "—"}", stats.netMicros?.let { money(it, true) } ?: "—", Modifier.weight(1f), stats.netMicros)
                    AnalyticsMetric("胜率", if (stats.unknown > 0) "待确认" else ratio(stats.winRate, true), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("盈利因子", ratio(stats.profitFactor), Modifier.weight(1f))
                    AnalyticsMetric("平均盈亏比", ratio(stats.averageWinLoss), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("已实现最大回撤", stats.drawdownMicros?.let { money(it) } ?: "—", Modifier.weight(1f))
                    AnalyticsMetric("平仓记录", stats.total.toString(), Modifier.weight(1f))
                }
                Text("盈利 ${stats.wins} · 亏损 ${stats.losses} · 持平 ${stats.breakeven} · 待确认 ${stats.unknown}", color = colors.Muted)
                Text("胜率 = 盈利笔数 / 已知盈亏笔数（含持平）；盈利因子 = 总盈利 / 总亏损绝对值。回撤仅按平仓净盈亏曲线计算，不含浮动盈亏。", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (stats.unknown > 0) "已知部分盈亏表现" else "盈亏表现", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(cumulative, { cumulative = true }, label = { Text("累计") })
                        FilterChip(!cumulative, { cumulative = false }, label = { Text("每日") })
                    }
                    val values = chartDays.map { (_, trades) -> tradeStatistics(trades).netMicros?.div(1_000_000.0) }
                    PnlPlot(values, cumulative)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(chartDays.firstOrNull()?.first?.toString() ?: "暂无数据", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                        Text(chartDays.lastOrNull()?.first?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("每日盈亏日历", style = MaterialTheme.typography.titleLarge)
                TextButton({ showTrades = !showTrades }, Modifier.testTag("analytics-toggle-trades")) { Text(if (showTrades) "日历" else "交易明细") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ month = month.minus(1, DateTimeUnit.MONTH); selectedDay = null }, Modifier.testTag("calendar-previous")) { Icon(AppIcons.ChevronLeft, "上个月") }
                Text("${month.year}年${month.month.number}月", Modifier.testTag("calendar-month"), style = MaterialTheme.typography.titleMedium)
                IconButton({ month = month.plus(1, DateTimeUnit.MONTH); selectedDay = null }, Modifier.testTag("calendar-next")) { Icon(AppIcons.ChevronRight, "下个月") }
                TextButton({ month = LocalDate(today.year, today.month, 1); selectedDay = null }) { Text("本月") }
            }
            Text("点击日期后，下方显示当日明细。周汇总仅包含当前月份。", color = colors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (!showTrades) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, color = colors.Muted, style = MaterialTheme.typography.labelSmall) }
            } } }
            items(calendarDates(month).chunked(7), key = { it.first().toString() }) { week ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { date ->
                            val inMonth = date.month == month.month && date.year == month.year
                            val entries = if (inMonth) daily[date].orEmpty() else emptyList()
                            val dayStats = tradeStatistics(entries)
                            val tint = if (dayStats.unknown > 0) colors.Caution else colors.pnl(dayStats.netMicros)
                            Column(Modifier.weight(1f).height(calendarCellHeight)
                                .background(if (entries.isEmpty()) colors.Surface else tint.copy(alpha = .10f), MaterialTheme.shapes.small)
                                .border(1.dp, if (selectedDay == date) colors.Accent else if (entries.isEmpty()) colors.Border else tint.copy(alpha = .5f), MaterialTheme.shapes.small)
                                .clickable(enabled = inMonth) { selectedDay = date }
                                .testTag("calendar-day-$date")
                                .semantics { contentDescription = "$date，${dayStats.netMicros?.let { money(it, true) } ?: "暂无已知盈亏"}，${entries.size}笔" }
                                .padding(horizontal = 3.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(date.day.toString(), color = if (inMonth) colors.Ink else colors.Muted.copy(alpha = .4f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                                if (entries.isNotEmpty()) {
                                    Text(if (dayStats.unknown > 0) "待确认" else dayStats.netMicros?.let(::compactPnl) ?: "—", color = tint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${entries.size}笔", color = colors.Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
                                    Text(if (dayStats.unknown > 0) "已知${dayStats.known}笔" else dayStats.winRate?.let { "${it.roundToInt()}%" } ?: "—", color = colors.Muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    val weekly = tradeStatistics(week.filter { it.month == month.month && it.year == month.year }.flatMap { daily[it].orEmpty() })
                    Text("${if (weekly.unknown > 0) "周已知部分" else "周汇总"} ${weekly.netMicros?.let { money(it, true) } ?: "—"} · ${weekly.total}笔${if (weekly.unknown > 0) " · ${weekly.unknown}笔待确认" else ""}",
                        color = colors.pnl(weekly.netMicros), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                }
            }
        }
        val detailRecords = if (showTrades) monthRecords else selectedDay?.let { daily[it].orEmpty() }.orEmpty()
        if (selectedDay != null || showTrades) {
            item {
                val detailStats = tradeStatistics(detailRecords)
                Text(if (showTrades) "本月平仓记录" else selectedDay.toString(), style = MaterialTheme.typography.titleLarge)
                Text("${if (detailStats.unknown > 0) "已知部分 " else ""}${detailStats.netMicros?.let { money(it, true) } ?: "—"} ${currency.orEmpty()} · ${detailStats.total}笔 · 胜率 ${if (detailStats.unknown > 0) "待确认（${detailStats.known}/${detailStats.total}笔已知）" else ratio(detailStats.winRate, true)}", color = colors.Muted)
                if (detailRecords.isEmpty()) Text("暂无已加载平仓记录", color = colors.Muted)
            }
            items(detailRecords, key = { "${it.account.id.length}:${it.account.id}:${it.trade.id}" }) { record ->
                Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(record.trade.symbol, fontWeight = FontWeight.SemiBold)
                            Text(record.account.name, color = colors.Muted, style = MaterialTheme.typography.labelSmall)
                            Text(kotlin.time.Instant.fromEpochMilliseconds(record.trade.closedAtMs).toLocalDateTime(zone).toString().replace('T', ' ').take(19), color = colors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(record.trade.netPnlMicros?.let { money(it, true) } ?: "待确认", color = colors.pnl(record.trade.netPnlMicros))
                    }
                }
            }
        }
        item { Text("数据范围受券商返回的历史记录限制；此处不推算缺失历史、不生成评分或交易建议。", color = colors.Muted, style = MaterialTheme.typography.labelSmall) }
    }
    }
}

@Composable
private fun <T> AnalyticsChoices(values: List<T>, current: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value -> FilterChip(current == value, { onSelect(value) }, label = { Text(label(value), maxLines = 1) }) }
    }
}

@Composable
private fun AnalyticsMetric(title: String, value: String, modifier: Modifier, pnl: Long? = null) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = TradingColors.Surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = TradingColors.Muted, style = MaterialTheme.typography.labelMedium)
            Text(value, color = if (pnl == null) TradingColors.Ink else TradingColors.pnl(pnl), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun compactPnl(micros: Long): String {
    val units = micros / 1_000_000.0
    val sign = if (units > 0) "+" else ""
    return when {
        kotlin.math.abs(units) >= 1_000_000 -> sign + ratio(units / 1_000_000) + "M"
        kotlin.math.abs(units) >= 1_000 -> sign + ratio(units / 1_000) + "k"
        else -> sign + ratio(units)
    }
}

@Composable
private fun PnlPlot(daily: List<Double?>, cumulative: Boolean) {
    val colors = TradingColors
    if (daily.none { it != null }) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { Text("暂无已知盈亏数据", color = colors.Muted) }
        return
    }
    // Unknown days break the line. The next known point continues the known-record total, not an invented zero.
    var running = 0.0
    val values = if (cumulative) listOf<Double?>(0.0) + daily.map { value -> value?.let { running += it; running } } else daily
    val low = minOf(0.0, values.filterNotNull().min()); val high = maxOf(0.0, values.filterNotNull().max())
    val span = (high - low).coerceAtLeast(1.0)
    Text("${ratio(high)} / ${ratio(low)}", color = colors.Muted, style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = if (cumulative) "已知平仓累计盈亏曲线" else "已知每日净盈亏柱状图" }) {
        fun y(value: Double) = (12 + (high - value) / span * (size.height - 24)).toFloat()
        repeat(5) { index -> val yy = 12f + index * (size.height - 24) / 4; drawLine(colors.Border, Offset(0f, yy), Offset(size.width, yy)) }
        drawLine(colors.Muted.copy(alpha = .5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        if (cumulative) {
            val path = Path(); var connected = false
            values.forEachIndexed { index, value ->
                if (value == null) connected = false else {
                    val x = index * size.width / (values.size - 1).coerceAtLeast(1)
                    if (connected) path.lineTo(x, y(value)) else path.moveTo(x, y(value))
                    connected = true; drawCircle(colors.Accent, 2.dp.toPx(), Offset(x, y(value)))
                }
            }
            drawPath(path, colors.Accent, style = Stroke(2.dp.toPx()))
        } else {
            val width = size.width / values.size
            values.forEachIndexed { index, value -> if (value != null) {
                drawLine(if (value >= 0) colors.pnl(1L) else colors.pnl(-1L), Offset((index + .5f) * width, y(0.0)),
                    Offset((index + .5f) * width, y(value)), strokeWidth = (width * .55f).coerceAtLeast(1f))
            } }
        }
    }
}
