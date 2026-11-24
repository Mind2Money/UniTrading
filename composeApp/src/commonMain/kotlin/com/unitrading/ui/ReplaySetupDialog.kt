package com.unitrading.ui

import androidx.compose.foundation.clickable
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
    val cacheHits: Int = 0, val downloaded: Int = 0)

/** Select the complete replay universe and fetch its range before opening the local simulator. */
@Composable
internal fun ReplaySetupDialog(broker: BrokerPlugin, cache: ReplayHistoryCache, persistentCache: Boolean, instruments: List<Instrument>, initial: Instrument,
    zone: ChartTimeZone, referenceTime: Long, onDismiss: () -> Unit, onReady: (ReplayPrepared) -> Unit) {
    val today = remember(referenceTime, zone) { Instant.fromEpochMilliseconds(referenceTime.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds())
        .toLocalDateTime(TimeZone.of(zone.zoneId)).date }
    var start by remember { mutableStateOf(today.minus(30, DateTimeUnit.DAY).toString()) }
    var end by remember { mutableStateOf(today.toString()) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf(initial.symbol to initial) }
    var prepared by remember { mutableStateOf<ReplayPrepared?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var useCache by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val loading = progress != null
    val choices = (listOf(initial) + instruments + selected.values).distinctBy { it.symbol }
        .filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Surface(Modifier.widthIn(max = 580.dp).fillMaxWidth().testTag("replay-setup"), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("准备历史回放", style = MaterialTheme.typography.titleLarge)
                Text("选择本次回放使用的品种和日期。分钟行情按所选图表时区读取；日线使用券商交易日。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it; prepared = null }, Modifier.weight(1f).testTag("replay-range-start"),
                        label = { Text("开始日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, enabled = !loading)
                    OutlinedTextField(end, { end = it; prepared = null }, Modifier.weight(1f).testTag("replay-range-end"),
                        label = { Text("结束日期（含当天）") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, enabled = !loading)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f).testTag("replay-symbol-filter"),
                        label = { Text("筛选品种") }, singleLine = true, enabled = !loading)
                    TextButton(onClick = { scope.launch { runCatching { broker.searchInstruments(query) }.onFailure { error = "合约搜索失败" } } }, enabled = !loading && query.isNotBlank()) { Text("查询") }
                }
                Text("已选 ${selected.size} 个品种", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    choices.take(100).forEach { item ->
                        Row(Modifier.fillMaxWidth().testTag("replay-select-${item.symbol}").clickable(enabled = !loading) {
                            if (selected.containsKey(item.symbol)) selected.remove(item.symbol) else selected[item.symbol] = item
                            prepared = null
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected.containsKey(item.symbol), onCheckedChange = null, enabled = !loading)
                            Column { Text(item.symbol); Text(item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
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
                    if (prepared != null) Button(onClick = { onReady(requireNotNull(prepared)) }, Modifier.testTag("replay-begin"), enabled = !loading) { Text("开始回放") }
                    else Button(onClick = {
                        val range = replayDateRange(start, end, zone)
                        if (range == null) { error = "请输入有效日期，结束日期不能早于开始日期"; return@Button }
                        if (selected.isEmpty()) { error = "请至少选择一个品种"; return@Button }
                        val chosen = selected.values.toList()
                        error = null; prepared = null
                        progress = "正在准备历史数据…"
                        job = scope.launch {
                            try {
                                val values = coroutineScope { chosen.flatMap { instrument -> listOf(Timeframe.M1, Timeframe.D1).map { frame -> async {
                                    val (from, until) = range.forFrame(frame)
                                    val loaded = cache.load(broker, instrument.symbol, frame, from, until, enabled = useCache, persistent = persistentCache) { done, total, cached ->
                                        progress = "正在读取 ${instrument.symbol} · ${frame.label} · $done/$total 段（缓存 $cached）"
                                    }
                                    Triple(instrument, frame, loaded)
                                } } }.awaitAll() }
                                val data = mutableMapOf<Pair<String, Timeframe>, List<Candle>>()
                                val warnings = mutableListOf<String>()
                                values.forEach { (instrument, frame, loaded) ->
                                    val result = loaded.result
                                    if (frame == Timeframe.M1 && result.candles.size < 2) throw IllegalStateException("${instrument.symbol} 在所选范围内没有足够的 1 分钟数据")
                                    if (frame == Timeframe.D1 && result.candles.isEmpty()) warnings += "${instrument.symbol}：券商没有返回该日期范围的日线"
                                    data[instrument.symbol to frame] = result.candles
                                    result.warning?.let { warnings += "${instrument.symbol}：$it" }
                                }
                                prepared = ReplayPrepared(chosen, range, data, warnings.distinct(), useCache,
                                    values.sumOf { it.third.cacheHits }, values.sumOf { it.third.downloaded })
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = failure.message ?: "历史数据读取失败" }
                            finally { progress = null }
                        }
                    }, Modifier.testTag("replay-load"), enabled = !loading) { Text("读取历史数据") }
                }
            }
        }
    }
}
