package com.unitrading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.ReplayHistoryCache
import com.unitrading.broker.*
import com.unitrading.chart.chartBarDateTime
import com.unitrading.chart.parseChartTime
import com.unitrading.chart.IntervalOptions
import com.unitrading.chart.ReplayRiskConfig
import com.unitrading.chart.ReplayDrawdown
import com.unitrading.chart.ReplayDailyLoss
import com.unitrading.chart.parseReplayCutoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal data class ReplayDateRange(val start: Long, val end: Long, val dailyStart: Long, val dailyEnd: Long) {
    fun forFrame(frame: Timeframe): Pair<Long, Long> = if (frame.isDaily) dailyStart to dailyEnd else start to end
}

internal fun replayDateRange(start: String, end: String, zone: ChartTimeZone): ReplayDateRange? = runCatching {
    val first = LocalDate.parse(start.trim())
    val last = LocalDate.parse(end.trim())
    require(first <= last)
    val after = last.plus(1, DateTimeUnit.DAY)
    fun at(date: LocalDate, id: String) = requireNotNull(parseChartTime("$date 00:00", id))
    ReplayDateRange(at(first, zone.zoneId), at(after, zone.zoneId), at(first, "UTC"), at(after, "UTC"))
}.getOrNull()

internal data class ReplayPrepared(val instruments: List<Instrument>, val range: ReplayDateRange,
    val data: Map<Pair<String, Timeframe>, List<Candle>>, val warnings: List<String>, val cacheEnabled: Boolean = true,
    val cacheHits: Int = 0, val downloaded: Int = 0, val initialFrame: Timeframe = Timeframe.M1,
    val riskConfig: ReplayRiskConfig = ReplayRiskConfig(50_000_000_000L, 2_000_000_000L, 3_000_000_000L, ReplayDrawdown.STATIC))

internal fun isReplayFuture(instrument: Instrument): Boolean = instrument.isContinuous ||
    instrument.category.contains("期货", true) || instrument.category.contains("future", true) || instrument.name.contains("future", true)

/** Select the complete replay universe and fetch its range before opening the local simulator. */
@Composable
internal fun ReplaySetupDialog(broker: BrokerPlugin, cache: ReplayHistoryCache, persistentCache: Boolean, instruments: List<Instrument>, initial: Instrument,
    zone: ChartTimeZone, referenceTime: Long, initialFrame: Timeframe, intervals: IntervalOptions, initialRegular: Boolean,
    onDismiss: () -> Unit, onReady: (ReplayPrepared) -> Unit) {
    val today = remember(referenceTime, zone) { Instant.fromEpochMilliseconds(referenceTime.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds())
        .toLocalDateTime(TimeZone.of(zone.zoneId)).date }
    var start by remember { mutableStateOf(today.minus(30, DateTimeUnit.DAY).toString()) }
    var end by remember { mutableStateOf(today.toString()) }
    val frame = if (initialRegular && initialFrame.isSeconds) Timeframe.M1 else initialFrame
    var initialText by remember { mutableStateOf("50000") }
    var customBalance by remember { mutableStateOf(false) }
    var drawdownText by remember { mutableStateOf("2000") }
    var dailyText by remember { mutableStateOf("1000") }
    var targetText by remember { mutableStateOf("3000") }
    var drawdownMode by remember { mutableStateOf(ReplayDrawdown.STATIC) }
    var dailyMode by remember { mutableStateOf(ReplayDailyLoss.HARD) }
    var allowOvernight by remember { mutableStateOf(true) }
    var cutoffText by remember { mutableStateOf("15:59") }
    var assetsExpanded by remember { mutableStateOf(false) }
    fun accountConfig() = ReplayRiskConfig(
        parseFixed(initialText, 6) ?: error("请输入有效的初始资金"),
        parseFixed(drawdownText, 6) ?: error("请输入有效的最大总亏损"),
        parseFixed(targetText, 6) ?: error("请输入有效的盈利目标"), drawdownMode,
        allowOvernight, parseReplayCutoff(cutoffText) ?: error("请输入有效的平仓时间"),
        parseFixed(dailyText, 6) ?: error("请输入有效的最大日亏损"), dailyMode)

    var calendarForStart by remember { mutableStateOf<Boolean?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Instrument>().also { if (isReplayFuture(initial)) it[initial.symbol] = initial } }
    var prepared by remember { mutableStateOf<ReplayPrepared?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var useCache by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val loading = progress != null
    val choices = (listOf(initial) + instruments + selected.values).distinctBy { it.symbol }
        .filter { isReplayFuture(it) && (it.symbol.contains(query, true) || it.name.contains(query, true)) }
    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().testTag("replay-setup"), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = 860.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(36.dp)
                    Column(Modifier.weight(1f)) {
                        Text("创建回放", style = MaterialTheme.typography.headlineSmall)
                        Text("设置模拟账户、期货品种和历史日期", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("分钟行情按图表时区读取，日线使用券商交易日。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                Text("账户资金", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("5000" to "5K", "10000" to "10K", "25000" to "25K", "50000" to "50K", "100000" to "100K", "250000" to "250K").forEach { (amount, label) ->
                        FilterChip(selected = !customBalance && initialText == amount, onClick = { initialText = amount; customBalance = false }, label = { Text(label) }, enabled = !loading)
                    }
                    FilterChip(selected = customBalance, onClick = { customBalance = true }, label = { Text("自定义") }, enabled = !loading)
                }
                if (customBalance) OutlinedTextField(initialText, { initialText = it }, label = { Text("自定义资金（美元）") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-initial-balance"))
                Text("资产 · 期货", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { assetsExpanded = !assetsExpanded }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-assets")) {
                    Text(if (selected.isEmpty()) "选择期货品种" else "已选 ${selected.size} 个：${selected.keys.joinToString("、")}")
                }
                if (assetsExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f).testTag("replay-symbol-filter"),
                            label = { Text("搜索期货") }, singleLine = true, enabled = !loading)
                        TextButton(onClick = { scope.launch { runCatching { broker.searchInstruments(query) }.onFailure { error = "合约搜索失败" } } }, enabled = !loading && query.isNotBlank()) { Text("查询") }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        choices.take(100).forEach { item ->
                            Row(Modifier.fillMaxWidth().testTag("replay-select-${item.symbol}").clickable(enabled = !loading) {
                                if (selected.containsKey(item.symbol)) selected.remove(item.symbol) else selected[item.symbol] = item
                                prepared = null
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected.containsKey(item.symbol), onCheckedChange = null, enabled = !loading)
                                Column(Modifier.weight(1f)) { Text(item.symbol); Text(item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("回放日期", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplayDateField(start, "开始日期", "replay-range-start", !loading, Modifier.weight(1f)) { calendarForStart = true }
                    ReplayDateField(end, "结束日期（含当天）", "replay-range-end", !loading, Modifier.weight(1f)) { calendarForStart = false }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("考核规则（美元）", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(targetText, { targetText = it }, label = { Text("盈利目标") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-target-amount"))
                OutlinedTextField(dailyText, { dailyText = it }, label = { Text("最大日亏损") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-daily-loss"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = dailyMode == ReplayDailyLoss.SOFT, onClick = { dailyMode = ReplayDailyLoss.SOFT }, label = { Text("软止损") }, enabled = !loading, modifier = Modifier.testTag("replay-daily-soft"))
                    FilterChip(selected = dailyMode == ReplayDailyLoss.HARD, onClick = { dailyMode = ReplayDailyLoss.HARD }, label = { Text("硬止损：触及即失败") }, enabled = !loading, modifier = Modifier.testTag("replay-daily-hard"))
                }
                Text("软止损触及后撤销挂单并暂停开仓，下一交易日恢复；现有持仓可平仓。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(drawdownText, { drawdownText = it }, label = { Text("最大总亏损") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-total-loss"))
                Text("回撤类型", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplayDrawdown.entries.forEach { mode -> FilterChip(selected = drawdownMode == mode, onClick = { drawdownMode = mode }, enabled = !loading,
                        label = { Text(when(mode) { ReplayDrawdown.STATIC -> "静态"; ReplayDrawdown.EOD -> "日终追踪"; ReplayDrawdown.TDD -> "浮动追踪" }) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowOvernight, onCheckedChange = { allowOvernight = it }, enabled = !loading, modifier = Modifier.testTag("replay-overnight-allow"))
                    Text("允许持仓过夜")
                }
                if (!allowOvernight) OutlinedTextField(cutoffText, { cutoffText = it }, label = { Text("每日平仓时间（芝加哥）") }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("replay-overnight-cutoff"))
                Text("交易日按芝加哥时间 17:00 切换；回放后的图表周期可以调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().clickable(enabled = !loading) { useCache = !useCache; prepared = null }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCache, onCheckedChange = null, enabled = !loading, modifier = Modifier.testTag("replay-use-cache"))
                    Column {
                        Text(if (persistentCache) "使用本地历史缓存" else "使用本次运行的历史缓存", style = MaterialTheme.typography.bodyMedium)
                        Text("最近两天重新读取；取消勾选可强制重读。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                progress?.let { Text(it, Modifier.testTag("replay-load-progress"), color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, Modifier.testTag("replay-load-error"), color = MaterialTheme.colorScheme.error) }
                prepared?.let { ready ->
                    Column(Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
                        Text("缓存 ${ready.cacheHits} 段 · 券商读取 ${ready.downloaded} 段", style = MaterialTheme.typography.bodySmall)
                        ready.instruments.forEach { instrument ->
                            val bars = ready.data[instrument.symbol to Timeframe.M1].orEmpty()
                            if (bars.isNotEmpty()) Text("${instrument.symbol}：${bars.size} 根 1 分钟 K 线 · ${chartBarDateTime(bars.first().timeMs, Timeframe.M1, zone.zoneId)} 至 ${chartBarDateTime(bars.last().timeMs, Timeframe.M1, zone.zoneId)}", style = MaterialTheme.typography.bodySmall)
                        }
                        ready.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { job?.cancel(); progress = null; onDismiss() }) { Text(if (loading) "取消加载" else "取消") }
                    if (prepared != null) Button(onClick = { runCatching { accountConfig() }.onSuccess { onReady(requireNotNull(prepared).copy(riskConfig = it)) }.onFailure { error = it.message } }, Modifier.testTag("replay-begin"), enabled = !loading, colors = brandButtonColors()) { Text("开始回放") }
                    else Button(onClick = {
                        if (runCatching { accountConfig() }.onFailure { error = it.message }.isFailure) return@Button
                        val range = replayDateRange(start, end, zone)
                        if (range == null) { error = "请输入有效日期，结束日期不能早于开始日期"; return@Button }
                        if (selected.isEmpty()) { error = "请至少选择一个品种"; return@Button }
                        if (initialRegular && frame.isSeconds) { error = "秒线暂不支持常规时段，请切换分钟周期，或先在主图切换 ETH"; return@Button }
                        val chosen = selected.values.toList()
                        error = null; prepared = null
                        progress = "正在准备历史数据…"
                        job = scope.launch {
                            try {
                                val sourceFrame = if (frame.isSeconds) frame else Timeframe.M1
                                val values = coroutineScope { chosen.flatMap { instrument -> listOf(Timeframe.M1, Timeframe.D1, sourceFrame).distinct().map { requested -> async {
                                    val (from, until) = range.forFrame(requested)
                                    val loaded = cache.load(broker, instrument.symbol, requested, from, until, enabled = useCache, persistent = persistentCache) { done, total, cached ->
                                        progress = "正在读取 ${instrument.symbol} · ${requested.label} · $done/$total 段（缓存 $cached）"
                                    }
                                    Triple(instrument, requested, loaded)
                                } } }.awaitAll() }
                                val data = mutableMapOf<Pair<String, Timeframe>, List<Candle>>()
                                val warnings = mutableListOf<String>()
                                values.forEach { (instrument, frame, loaded) ->
                                    val result = loaded.result
                                    if ((frame == Timeframe.M1 || frame == sourceFrame) && result.candles.size < 2) throw IllegalStateException("${instrument.symbol} 在所选范围内没有足够的 ${frame.label} 数据")
                                    if (frame == Timeframe.D1 && result.candles.isEmpty()) warnings += "${instrument.symbol}：券商没有返回该日期范围的日线"
                                    data[instrument.symbol to frame] = result.candles
                                    result.warning?.let { warnings += "${instrument.symbol}：$it" }
                                }
                                prepared = ReplayPrepared(chosen, range, data, warnings.distinct(), useCache,
                                    values.sumOf { it.third.cacheHits }, values.sumOf { it.third.downloaded }, frame)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = failure.message ?: "历史数据读取失败" }
                            finally { progress = null }
                        }
                    }, Modifier.testTag("replay-load"), enabled = !loading, colors = brandButtonColors()) { Text("读取历史数据") }
                }
            }
        }
    }
    calendarForStart?.let { isStart ->
        ReplayCalendarDialog(if (isStart) start else end, onDismiss = { calendarForStart = null }) { date ->
            if (isStart) start = date else end = date
            prepared = null; calendarForStart = null
        }
    }
}

@Composable
private fun ReplayDateField(value: String, label: String, tag: String, enabled: Boolean, modifier: Modifier = Modifier, open: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(value, {}, readOnly = true, enabled = enabled, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(AppIcons.Calendar, null, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().testTag(tag).clickable(enabled = enabled, onClick = open))
    }
}

@Composable
internal fun ReplayCalendarDialog(value: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val selected = remember(value) { LocalDate.parse(value) }
    var month by remember(value) { mutableStateOf(LocalDate(selected.year, selected.month, 1)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择日期") }, text = {
        Column(Modifier.widthIn(min = 280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minus(1, DateTimeUnit.YEAR) }, modifier = Modifier.testTag("replay-calendar-prev-year")) { Text("«") }
                IconButton(onClick = { month = month.minus(1, DateTimeUnit.MONTH) }, modifier = Modifier.testTag("replay-calendar-prev-month")) { Icon(AppIcons.ChevronLeft, "上个月") }
                Text("${month.year}年${month.month.ordinal + 1}月", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { month = month.plus(1, DateTimeUnit.MONTH) }, modifier = Modifier.testTag("replay-calendar-next-month")) { Icon(AppIcons.ChevronRight, "下个月") }
                IconButton(onClick = { month = month.plus(1, DateTimeUnit.YEAR) }, modifier = Modifier.testTag("replay-calendar-next-year")) { Text("»") }
            }
            Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelSmall) }
            } }
            val first = month.minus(month.dayOfWeek.ordinal, DateTimeUnit.DAY)
            repeat(6) { week -> Row(Modifier.fillMaxWidth()) { repeat(7) { day ->
                val date = first.plus(week * 7 + day, DateTimeUnit.DAY)
                Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                    if (date.month == month.month) TextButton(onClick = { onSelect(date.toString()) },
                        modifier = Modifier.size(40.dp).testTag("replay-calendar-$date"), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (date == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        Text(date.day.toString())
                    }
                }
            } } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
