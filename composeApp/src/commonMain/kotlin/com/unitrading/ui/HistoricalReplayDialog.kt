package com.unitrading.ui

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
import androidx.compose.ui.window.DialogProperties
import com.unitrading.app.ChartTimeZone
import com.unitrading.broker.*
import com.unitrading.chart.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class ReplayInput(val instrument: Instrument, val timeframe: Timeframe, val regular: Boolean,
    val zone: ChartTimeZone, val raw: List<Candle>, val indicators: List<IndicatorConfig>)

/** A separate modal and ledger: no AccountSession is passed into the replay at any point. */
@Composable
internal fun HistoricalReplayDialog(input: ReplayInput, onDismiss: () -> Unit) {
    val prepared = remember(input) {
        if (input.raw.size <= 500) chartSessionCandles(input.raw.sortedBy(Candle::timeMs), input.instrument.symbol, input.timeframe, input.regular)
        else null
    }
    val loaded by produceState<Pair<List<Candle>, String?>?>(prepared, input) {
        if (prepared == null) value = withContext(Dispatchers.Default) {
            chartSessionCandles(input.raw.sortedBy(Candle::timeMs), input.instrument.symbol, input.timeframe, input.regular)
        }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("historical-replay"),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            val bars = loaded?.first.orEmpty()
            if (loaded == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在准备历史数据…")
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
            else if (bars.size < 2) Column(Modifier.padding(24.dp)) {
                Text(loaded?.second ?: "当前图表没有足够的历史 K 线，请等待数据加载后重试。")
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else ReplayWorkspace(input, bars, onDismiss)
        }
    }
}

@Composable
private fun ReplayWorkspace(input: ReplayInput, bars: List<Candle>, onDismiss: () -> Unit) {
    val spec = input.instrument
    var start by remember { mutableIntStateOf((bars.size - 100).coerceIn(0, bars.lastIndex - 1)) }
    var state by remember { mutableStateOf(ReplayState(start)) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var quantity by remember { mutableStateOf(spec.formatQuantity(spec.minLotsHundredths.coerceAtLeast(spec.lotStepHundredths))) }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf("") }
    val current = bars[state.index]
    fun step() {
        if (state.index >= bars.lastIndex) { playing = false; return }
        val result = runCatching { state.advance(spec, bars[state.index + 1]) }
        result.onSuccess { state = it; started = true; if (it.index == bars.lastIndex) playing = false }
            .onFailure { playing = false; message = it.message ?: "回放撮合失败" }
    }
    LaunchedEffect(playing, speed, state.index) {
        if (playing) { delay((1000L / speed).coerceAtLeast(50)); step() }
    }
    fun place(side: Side) {
        runCatching {
            val qty = spec.parseQuantity(quantity) ?: error("请输入有效数量")
            val sl = if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价")
            val tp = if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价")
            state.open(spec, current, side, qty, sl, tp)
        }.onSuccess { state = it; started = true; message = null }
            .onFailure { message = it.message ?: "无法模拟下单" }
    }
    val noTrading = remember { ChartOrderActions(
        estimateDraft = { it to OrderPreview(null, errors = listOf("回放请使用模拟订单面板")) },
        onDraftEntry = {}, onDraftProtection = { _, _ -> }, editPending = false,
        estimatePending = { _, _ -> OrderPreview(null) }, onPendingEntry = { _, _ -> }, editPendingProtection = false,
        estimatePendingProtection = { _, _, _ -> OrderPreview(null) }, onPendingProtection = { _, _, _ -> }, onProtection = { _, _, _ -> },
    ) }
    val visible = remember(bars, state.index) { bars.subList(0, state.index + 1) }
    val realized = remember(state.trades) { runCatching { state.trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) } }.getOrNull() }
    val floating = remember(state.positions, current) { runCatching {
        state.positions.fold(0L) { sum, p ->
            val gross = grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, current.close)
            checkedAdd(sum, checkedAdd(gross, -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths))))
        }
    }.getOrNull() }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("历史回放 · 本地模拟", style = MaterialTheme.typography.titleMedium)
            Text("${spec.symbol} · ${input.timeframe.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { playing = false; if (started) confirmExit = true else onDismiss() }, Modifier.testTag("replay-close")) { Text("关闭") }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TradingChart(accountKey = "local-historical-replay", instrument = spec,
                    quote = Quote(current.close, current.close, current.timeMs), candles = visible, timeframe = input.timeframe,
                    onTimeframe = {}, positions = state.positions, trades = emptyList(), selectedPositionId = null,
                    focusedTrade = null, draft = null, preview = null, orders = emptyList(), actions = noTrading,
                    onSide = { place(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(), indicators = input.indicators,
                    chrome = ChartChrome(sharedToolbar = true, desktopAxisStyle = true, showNavigation = false,
                        showWatermark = true, chartTimeZone = input.zone))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { playing = !playing; started = true }, enabled = state.index < bars.lastIndex, modifier = Modifier.testTag("replay-play")) { Text(if (playing) "暂停" else "播放") }
                    TextButton(onClick = { playing = false; step() }, enabled = state.index < bars.lastIndex, modifier = Modifier.testTag("replay-next")) { Text("下一根") }
                    listOf(1, 2, 5, 10).forEach { n -> TextButton(onClick = { speed = n }) { Text("${n}x", color = if (speed == n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
                    TextButton(onClick = { playing = false; confirmReset = true }) { Text("重选起点") }
                    Text(chartBarDateTime(current.timeMs, input.timeframe, input.zone.zoneId), modifier = Modifier.testTag("replay-time"), style = MaterialTheme.typography.bodySmall)
                }
                if (!started) {
                    Text("选择开始位置（拖动后从该 K 线开始）", style = MaterialTheme.typography.bodySmall)
                    Slider(value = start.toFloat(), onValueChange = { start = it.toInt().coerceIn(0, bars.lastIndex - 1); state = ReplayState(start) },
                        valueRange = 0f..(bars.lastIndex - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.testTag("replay-start"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(startTime, { startTime = it }, label = { Text(if (input.timeframe.seconds >= Timeframe.DAY) "起点日期 YYYY-MM-DD" else "起点时间 YYYY-MM-DD HH:mm") },
                            modifier = Modifier.weight(1f).testTag("replay-start-time"), singleLine = true)
                        TextButton(onClick = {
                            val at = if (input.timeframe.seconds >= Timeframe.DAY) runCatching { Instant.parse(startTime.trim() + "T00:00:00Z").toEpochMilliseconds() }.getOrNull()
                                else parseChartTime(startTime, input.zone.zoneId)
                            if (at == null) message = "请输入有效的图表时区日期和时间"
                            else if (at < bars.first().timeMs || at > bars.last().timeMs) message = "该时间不在已加载的历史范围内"
                            else {
                                val found = bars.binarySearch { it.timeMs.compareTo(at) }.let { if (it < 0) -it - 1 else it }
                                start = found.coerceIn(0, bars.lastIndex - 1); state = ReplayState(start); message = null
                            }
                        }) { Text("定位") }
                    }
                }
            }
            Column(Modifier.widthIn(min = 230.dp, max = 290.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模拟订单", style = MaterialTheme.typography.titleSmall)
                Text("按当前 K 线收盘价成交", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（${spec.quantityUnit}）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-quantity"))
                OutlinedTextField(stop, { stop = it }, label = { Text("止损价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-stop"))
                OutlinedTextField(target, { target = it }, label = { Text("止盈价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-target"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { place(Side.BUY) }, modifier = Modifier.weight(1f).testTag("replay-buy")) { Text("买入") }
                    Button(onClick = { place(Side.SELL) }, modifier = Modifier.weight(1f).testTag("replay-sell")) { Text("卖出") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Text("已实现 ${realized?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-realized"))
                Text("浮动 ${floating?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-floating"))
                Text("成交 ${state.trades.size} 笔 · 胜 ${state.trades.count { it.pnlMicros > 0 }} · 负 ${state.trades.count { it.pnlMicros < 0 }}", Modifier.testTag("replay-trade-count"), style = MaterialTheme.typography.bodySmall)
                state.positions.forEach { p ->
                    Text("${if (p.side == Side.BUY) "买" else "卖"} ${spec.formatQuantity(p.lotsHundredths)} · ${spec.formatPrice(p.entryPrice)}", style = MaterialTheme.typography.bodySmall)
                    Text("SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} · TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching {
                        val sl = if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价")
                        val tp = if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价")
                        state.protect(spec, p, current.close, sl, tp)
                    }.onSuccess { state = it; message = null }.onFailure { message = it.message } }) { Text("用上方价格更新止损止盈") }
                    TextButton(onClick = { runCatching { state.close(spec, p, current.close, current.timeMs, "手动平仓") }
                        .onSuccess { state = it; message = null }.onFailure { message = it.message } }) { Text("模拟平仓") }
                }
                state.trades.asReversed().take(30).forEach { t -> Text("${t.reason} ${money(t.pnlMicros, true)} · ${spec.formatPrice(t.exit)}", style = MaterialTheme.typography.bodySmall) }
                Text("仅使用 K 线 OHLC；开盘未触及保护价、同一根又同时触及止损止盈时先按止损。止损跳空按更差开盘价成交。无点差和滑点；手续费按合约默认值，默认零不代表券商免费。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("结束本次回放？") },
        text = { Text("本次模拟仓位和交易记录将清除。") }, confirmButton = {
            TextButton(onClick = { confirmReset = false; playing = false; state = ReplayState(start); started = false }) { Text("重选起点") }
        }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("关闭回放？") },
        text = { Text("本次模拟仓位和交易记录将清除。") }, confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭回放") }
        }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } })
}
