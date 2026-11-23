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
import com.unitrading.app.IndicatorController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.*
import com.unitrading.chart.*
import com.unitrading.chart.drawing.ChartDrawings
import com.unitrading.chart.drawing.DrawingToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class ReplayInput(val instrument: Instrument, val timeframe: Timeframe, val regular: Boolean,
    val zone: ChartTimeZone, val raw: List<Candle>, val indicators: List<IndicatorConfig>,
    val rawFrame: Timeframe = timeframe, val instruments: List<Instrument> = listOf(instrument),
    val intervals: IntervalOptions = IntervalOptions())

/** A separate modal and ledger: no AccountSession is passed into the replay at any point. */
@Composable
internal fun HistoricalReplayDialog(input: ReplayInput, revision: Long = 0,
    history: (String, Timeframe) -> List<Candle> = { _, _ -> emptyList() },
    watch: (String) -> Unit = {}, onDismiss: () -> Unit) {
    var selected by remember(input) { mutableStateOf(input.instrument) }
    var fineSnapshot by remember(input) { mutableStateOf<List<Candle>?>(null) }
    val fineHistory = remember(revision, selected.symbol) { history(selected.symbol, Timeframe.M1) }
    LaunchedEffect(selected, fineHistory) {
        if (selected.symbol != input.instrument.symbol && fineSnapshot == null && fineHistory.size >= 2)
            fineSnapshot = fineHistory.toList()
    }
    val raw = fineSnapshot ?: if (selected.symbol == input.instrument.symbol) input.raw else emptyList()
    val rawFrame = if (fineSnapshot != null || selected.symbol != input.instrument.symbol) Timeframe.M1 else input.rawFrame
    val prepared = remember(raw, selected, rawFrame) {
        if (raw.size <= 500) chartSessionCandles(raw.sortedBy(Candle::timeMs), selected.symbol, rawFrame, input.regular)
        else null
    }
    val loaded by key(raw, selected, rawFrame) { produceState<Pair<List<Candle>, String?>?>(prepared, raw, selected, rawFrame) {
        value = prepared ?: withContext(Dispatchers.Default) {
            chartSessionCandles(raw.sortedBy(Candle::timeMs), selected.symbol, rawFrame, input.regular)
        }
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
                Text(loaded?.second ?: "正在等待该品种的历史 K 线；数据范围取决于券商。")
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else key(selected.symbol, rawFrame) {
                ReplayWorkspace(input.copy(instrument = selected), bars, rawFrame,
                    fineAvailable = fineSnapshot == null && rawFrame != Timeframe.M1 && fineHistory.size >= 2,
                    useFine = { fineSnapshot = fineHistory.toList() },
                    changeSymbol = { symbol -> input.instruments.firstOrNull { it.symbol == symbol }?.let {
                        selected = it; fineSnapshot = null; watch(symbol)
                    } }, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun ReplayWorkspace(input: ReplayInput, bars: List<Candle>, sourceFrame: Timeframe,
    fineAvailable: Boolean, useFine: () -> Unit, changeSymbol: (String) -> Unit, onDismiss: () -> Unit) {
    val spec = input.instrument
    var start by remember { mutableIntStateOf((bars.size - 100).coerceIn(0, bars.lastIndex - 1)) }
    var state by remember { mutableStateOf(ReplayState(start)) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var stepBars by remember { mutableIntStateOf(1) }
    var quantity by remember { mutableStateOf(spec.formatQuantity(spec.minLotsHundredths.coerceAtLeast(spec.lotStepHundredths))) }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var startTime by remember { mutableStateOf("") }
    var orderKind by remember { mutableStateOf(OrderKind.MARKET) }
    var orderPrice by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var initialText by remember { mutableStateOf("50000") }
    var drawdownText by remember { mutableStateOf("2000") }
    var targetText by remember { mutableStateOf("3000") }
    var drawdownMode by remember { mutableStateOf(ReplayDrawdown.STATIC) }
    var riskConfig by remember { mutableStateOf(ReplayRiskConfig(50_000_000_000L, 2_000_000_000L, 3_000_000_000L, ReplayDrawdown.STATIC)) }
    var risk by remember { mutableStateOf(ReplayRiskState.initial(riskConfig)) }
    var chartFrame by remember { mutableStateOf(if (input.timeframe.seconds >= Timeframe.DAY && sourceFrame != input.timeframe) sourceFrame else input.timeframe) }
    var showIndicators by remember { mutableStateOf(false) }
    val indicatorController = remember { IndicatorController(MemorySnapshotStore()) }
    val indicatorSettings by indicatorController.settings.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(indicatorController) { indicatorController.apply(IndicatorSettings(input.indicators)) }
    val drawings = remember { ChartDrawings(MemorySnapshotStore()).also { it.bind(spec.symbol) } }
    val current = bars[state.index]
    fun accept(next: ReplayState) {
        val bar = bars[next.index]
        val realized = next.trades.fold(0L) { n, trade -> checkedAdd(n, trade.pnlMicros) }
        val floating = next.positions.fold(0L) { n, p -> checkedAdd(n,
            checkedAdd(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, bar.close),
                -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths)))) }
        val balance = checkedAdd(riskConfig.initialMicros, realized)
        val equity = checkedAdd(balance, floating)
        risk = risk.observe(riskConfig, bar.timeMs, balance, equity)
        state = if (risk.failed) next.copy(pending = emptyList()) else next
        if (risk.failed) { playing = false; message = "触及模拟回撤限额，已停止回放并禁止新开仓；现有仓位可手动平仓。" }
        next.lastNotice?.let { message = it }
    }
    fun step() {
        if (state.index >= bars.lastIndex) { playing = false; return }
        repeat(stepBars) {
            if (state.index >= bars.lastIndex || risk.failed) { playing = false; return }
            val result = runCatching { accept(state.advance(spec, bars[state.index + 1])) }
            if (result.isFailure) { playing = false; message = result.exceptionOrNull()?.message ?: "回放撮合失败"; return }
            started = true
        }
        if (state.index == bars.lastIndex) playing = false
    }
    LaunchedEffect(playing, speed, state.index) {
        if (playing) { delay((1000L / speed).coerceAtLeast(50)); step() }
    }
    fun place(side: Side) {
        runCatching {
            require(!risk.failed) { "已触及模拟回撤限额，不能新开仓" }
            val qty = spec.parseQuantity(quantity) ?: error("请输入有效数量")
            val sl = if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价")
            val tp = if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价")
            if (orderKind == OrderKind.MARKET) state.open(spec, current, side, qty, sl, tp)
            else state.enqueue(spec, side, orderKind, qty, spec.parsePrice(orderPrice) ?: error("请输入挂单价格"), sl, tp, current.close)
        }.onSuccess { message = null; accept(it); started = true }
            .onFailure { message = it.message ?: "无法模拟下单" }
    }
    fun pendingId(id: String): Long = id.removePrefix("replay-order-").toLongOrNull() ?: error("模拟挂单不存在")
    fun pendingPreview(id: String, price: Long, sl: Long?, tp: Long?): OrderPreview = runCatching {
        state.modifyPending(spec, pendingId(id), price, sl, tp, current.close)
        OrderPreview(price)
    }.getOrElse { OrderPreview(null, errors = listOf(it.message ?: "模拟挂单修改无效")) }
    val localChartActions = ChartOrderActions(
        estimateDraft = { it to OrderPreview(null, errors = listOf("回放请使用模拟订单面板")) },
        onDraftEntry = {}, onDraftProtection = { _, _ -> }, editPending = true,
        estimatePending = { id, price -> state.pending.firstOrNull { "replay-order-${it.id}" == id }?.let { pendingPreview(id, price, it.stop, it.target) } ?: OrderPreview(null) },
        onPendingEntry = { order, price -> runCatching { state.modifyPending(spec, pendingId(order.id), price, order.request.stopLoss, order.request.takeProfit, current.close) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } }, editPendingProtection = true,
        estimatePendingProtection = { id, sl, tp -> state.pending.firstOrNull { "replay-order-${it.id}" == id }?.let { pendingPreview(id, it.price, sl, tp) } ?: OrderPreview(null) },
        onPendingProtection = { order, sl, tp -> runCatching { state.modifyPending(spec, pendingId(order.id), order.request.entryPrice ?: error("挂单价格无效"), sl, tp, current.close) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } },
        onProtection = { position, sl, tp -> runCatching { state.protect(spec, position, current.close, sl, tp) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } },
        onLineAction = { action -> runCatching {
            when (action) {
                is LineAction.ClosePosition -> accept(state.close(spec, action.position, current.close, current.timeMs, "手动平仓"))
                is LineAction.CancelOrder -> state = state.copy(pending = state.pending.filterNot { "replay-order-${it.id}" == action.order.id })
                is LineAction.RemoveProtection -> state = state.protect(spec, action.position, current.close,
                    if (action.stop) null else action.position.stopLoss, if (action.stop) action.position.takeProfit else null)
                is LineAction.RemovePendingProtection -> state = state.modifyPending(spec, pendingId(action.order.id), action.order.request.entryPrice ?: error("挂单价格无效"),
                    if (action.stop) null else action.order.request.stopLoss, if (action.stop) action.order.request.takeProfit else null, current.close)
                else -> Unit
            }
        }.onFailure { message = it.message } }, addPositionProtection = true, removeProtection = true,
    )
    val chartOrders = remember(state.pending, spec) { state.pending.map { order ->
        PendingOrder("replay-order-${order.id}", OrderRequest(spec.executionSymbol ?: spec.symbol, order.side, order.kind,
            order.quantity, order.price, order.stop, order.target), createdAtMs = current.timeMs, editable = true, protectionEditable = true)
    } }
    val visible = remember(bars, state.index, sourceFrame, chartFrame) {
        runCatching { replayChartBars(bars, state.index, sourceFrame, chartFrame, spec.symbol, input.regular) }.getOrElse { emptyList() }
    }
    val realized = remember(state.trades) { runCatching { state.trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) } }.getOrNull() }
    val analysis = remember(state.trades) { runCatching { replayAnalysis(state.trades) }.getOrNull() }
    val floating = remember(state.positions, current) { runCatching {
        state.positions.fold(0L) { sum, p ->
            val gross = grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, current.close)
            checkedAdd(sum, checkedAdd(gross, -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths))))
        }
    }.getOrNull() }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("历史回放 · 本地模拟", style = MaterialTheme.typography.titleMedium)
            Text("${spec.symbol} · 图表 ${chartFrame.label} · 推进 ${sourceFrame.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { playing = false; if (started) confirmExit = true else onDismiss() }, Modifier.testTag("replay-close")) { Text("关闭") }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ChartToolbar(spec, input.instruments, { sym ->
                    if (sym != spec.symbol) {
                        if (started) { playing = false; switchTo = sym } else changeSymbol(sym)
                    }
                }, chartFrame, { frame ->
                    if (frame.seconds < sourceFrame.seconds || frame.seconds % sourceFrame.seconds != 0 ||
                        (frame.seconds >= Timeframe.DAY && frame != sourceFrame))
                        message = "此周期不能由当前回放数据安全生成，请选择可用周期"
                    else { chartFrame = frame; message = null }
                }, input.intervals, emptyList(), ChartToggle("指标 ${indicatorSettings.indicators.size}", true) { showIndicators = true },
                    Modifier.fillMaxWidth(), singleRow = true)
                if (fineAvailable && !started) TextButton(onClick = useFine) { Text("使用券商 1 分钟历史推进（将重选起点）") }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                DrawingToolbar(drawings, indicatorSettings.indicators.size, { scope.launch { indicatorController.apply(IndicatorSettings()) } })
                TradingChart(accountKey = "local-historical-replay", instrument = spec,
                    quote = Quote(current.close, current.close, current.timeMs), candles = visible, timeframe = chartFrame,
                    onTimeframe = {}, positions = state.positions, trades = emptyList(), selectedPositionId = null,
                    focusedTrade = null, draft = null, preview = null, orders = chartOrders, actions = localChartActions,
                    onSide = { place(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(), indicators = indicatorSettings.indicators,
                    chrome = ChartChrome(drawings = drawings, sharedToolbar = true, desktopAxisStyle = true, showNavigation = false,
                        showWatermark = true, chartTimeZone = input.zone))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { playing = !playing; started = true }, enabled = state.index < bars.lastIndex && !risk.failed, modifier = Modifier.testTag("replay-play")) { Text(if (playing) "暂停" else "播放") }
                    TextButton(onClick = { playing = false; step() }, enabled = state.index < bars.lastIndex && !risk.failed, modifier = Modifier.testTag("replay-next")) { Text("前进") }
                    listOf(1, 5).forEach { n -> TextButton(onClick = { stepBars = n }) {
                        Text("${n}根/步", color = if (stepBars == n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    } }
                    listOf(1, 2, 5, 10).forEach { n -> TextButton(onClick = { speed = n }) { Text("${n}x", color = if (speed == n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
                    TextButton(onClick = { playing = false; confirmReset = true }) { Text("重选起点") }
                    Text(chartBarDateTime(current.timeMs, sourceFrame, input.zone.zoneId), modifier = Modifier.testTag("replay-time"), style = MaterialTheme.typography.bodySmall)
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模拟订单", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { if (started) message = "请先重选起点再修改模拟账户设置" else showAccount = true }) { Text("账户设置") }
                }
                Row { OrderKind.entries.forEach { kind -> TextButton(onClick = { orderKind = kind }, modifier = Modifier.weight(1f)) {
                    Text(when(kind) { OrderKind.MARKET -> "市价"; OrderKind.LIMIT -> "限价"; OrderKind.STOP -> "止损" },
                        color = if (orderKind == kind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                } } }
                Text(if (orderKind == OrderKind.MARKET) "按当前 K 线收盘价成交" else "从下一根回放 K 线开始撮合", style = MaterialTheme.typography.bodySmall)
                if (orderKind != OrderKind.MARKET) OutlinedTextField(orderPrice, { orderPrice = it }, label = { Text("挂单价格") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（${spec.quantityUnit}）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-quantity"))
                OutlinedTextField(stop, { stop = it }, label = { Text("止损价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-stop"))
                OutlinedTextField(target, { target = it }, label = { Text("止盈价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-target"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { place(Side.BUY) }, modifier = Modifier.weight(1f).testTag("replay-buy")) { Text("买入") }
                    Button(onClick = { place(Side.SELL) }, modifier = Modifier.weight(1f).testTag("replay-sell")) { Text("卖出") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Text("模拟资金 ${money(riskConfig.initialMicros)} ${spec.currency}")
                Text("净值 ${if (realized != null && floating != null) runCatching { money(checkedAdd(riskConfig.initialMicros, checkedAdd(realized, floating))) }.getOrDefault("—") else "—"}")
                Text("回撤线 ${money(risk.threshold)} · ${drawdownMode.name}${if (risk.failed) " · 已触及" else ""}", style = MaterialTheme.typography.bodySmall)
                Text("盈利目标 ${money(riskConfig.targetMicros)}${if (risk.targetReached) " · 已达成" else ""}", style = MaterialTheme.typography.bodySmall)
                Text("已实现 ${realized?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-realized"))
                Text("浮动 ${floating?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-floating"))
                Text("成交 ${state.trades.size} 笔 · 胜 ${state.trades.count { it.pnlMicros > 0 }} · 负 ${state.trades.count { it.pnlMicros < 0 }}", Modifier.testTag("replay-trade-count"), style = MaterialTheme.typography.bodySmall)
                val completed = state.trades.size
                val wins = state.trades.count { it.pnlMicros > 0 }
                Text("胜率 ${if (completed == 0) "—" else "${wins * 100 / completed}%"} · 平均盈亏 ${if (completed == 0 || realized == null) "—" else money(realized / completed)}", style = MaterialTheme.typography.bodySmall)
                analysis?.let { stats ->
                    Text("总盈利 ${money(stats.grossProfit)} · 总亏损 ${money(stats.grossLoss)}", style = MaterialTheme.typography.bodySmall)
                    Text("平仓权益最大回撤 ${money(stats.maxClosedDrawdown)}", style = MaterialTheme.typography.bodySmall)
                    Text("最佳 ${stats.best?.let { money(it, true) } ?: "—"} · 最差 ${stats.worst?.let { money(it, true) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                }
                state.pending.forEach { order ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (order.side == Side.BUY) "买" else "卖"} ${if (order.kind == OrderKind.LIMIT) "限价" else "止损"} ${spec.formatPrice(order.price)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { state = state.copy(pending = state.pending.filterNot { it.id == order.id }) }) { Text("撤单") }
                    }
                }
                state.positions.forEach { p ->
                    Text("${if (p.side == Side.BUY) "买" else "卖"} ${spec.formatQuantity(p.lotsHundredths)} · ${spec.formatPrice(p.entryPrice)}", style = MaterialTheme.typography.bodySmall)
                    Text("SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} · TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching {
                        val sl = if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价")
                        val tp = if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价")
                        state.protect(spec, p, current.close, sl, tp)
                    }.onSuccess { state = it; message = null }.onFailure { message = it.message } }) { Text("用上方价格更新止损止盈") }
                    TextButton(onClick = { runCatching { state.close(spec, p, current.close, current.timeMs, "手动平仓") }
                        .onSuccess { message = null; accept(it) }.onFailure { message = it.message } }) { Text("模拟平仓") }
                }
                state.trades.asReversed().take(30).forEach { t -> Text("${t.reason} ${money(t.pnlMicros, true)} · ${spec.formatPrice(t.exit)}", style = MaterialTheme.typography.bodySmall) }
                Text("净持仓按先开先平抵消反向订单。仅使用 K 线 OHLC；同根触及止损按保守成交，挂单非开盘成交时同根止盈不计。无点差和滑点；手续费采用合约默认值。回撤仅在 K 线收盘时检查，EOD 以芝加哥 17:00 已实现余额更新；规则与券商实际规定可能不同。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showIndicators) IndicatorSettingsDialog(indicatorController) { showIndicators = false }
    switchTo?.let { symbol -> AlertDialog(onDismissRequest = { switchTo = null }, title = { Text("切换回放品种？") },
        text = { Text("切换品种会结束当前回放，清除本地模拟仓位、挂单和统计记录。") },
        confirmButton = { TextButton(onClick = { switchTo = null; changeSymbol(symbol) }) { Text("切换品种") } },
        dismissButton = { TextButton(onClick = { switchTo = null }) { Text("取消") } }) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("结束本次回放？") },
        text = { Text("本次模拟仓位和交易记录将清除。") }, confirmButton = {
            TextButton(onClick = { confirmReset = false; playing = false; state = ReplayState(start); risk = ReplayRiskState.initial(riskConfig); started = false }) { Text("重选起点") }
        }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("关闭回放？") },
        text = { Text("本次模拟仓位和交易记录将清除。") }, confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭回放") }
        }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } })
    if (showAccount) AlertDialog(onDismissRequest = { showAccount = false }, title = { Text("模拟账户设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(initialText, { initialText = it }, label = { Text("初始资金") }, singleLine = true)
            OutlinedTextField(drawdownText, { drawdownText = it }, label = { Text("最大回撤额度") }, singleLine = true)
            OutlinedTextField(targetText, { targetText = it }, label = { Text("总盈利目标") }, singleLine = true)
            Text("回撤类型")
            ReplayDrawdown.entries.forEach { mode -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = drawdownMode == mode, onClick = { drawdownMode = mode }); Text(when(mode) {
                    ReplayDrawdown.STATIC -> "静态"; ReplayDrawdown.EOD -> "EOD 日终追踪"; ReplayDrawdown.TDD -> "TDD 浮动追踪"
                })
            } }
            Text("本地通用规则，不代表特定交易公司的考核规则。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { ReplayRiskConfig(parseFixed(initialText, 6) ?: error("初始资金无效"),
            parseFixed(drawdownText, 6) ?: error("回撤额度无效"), parseFixed(targetText, 6) ?: error("盈利目标无效"), drawdownMode) }
            .onSuccess { riskConfig = it; risk = ReplayRiskState.initial(it); showAccount = false; message = null }
            .onFailure { message = it.message }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showAccount = false; drawdownMode = riskConfig.mode }) { Text("取消") } })
}
