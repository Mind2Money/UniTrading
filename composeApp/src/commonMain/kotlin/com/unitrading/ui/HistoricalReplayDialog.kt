package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.ChartSession
import com.unitrading.app.IndicatorController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.*
import com.unitrading.chart.*
import com.unitrading.chart.drawing.ChartDrawings
import com.unitrading.chart.drawing.DrawingToolbar
import com.unitrading.chart.drawing.DrawingLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class ReplayInput(val instrument: Instrument, val timeframe: Timeframe, val regular: Boolean,
    val zone: ChartTimeZone, val raw: List<Candle>, val indicators: List<IndicatorConfig>,
    val rawFrame: Timeframe = timeframe, val instruments: List<Instrument> = listOf(instrument),
    val intervals: IntervalOptions = IntervalOptions(), val priceAxis: PriceAxisSettings = PriceAxisSettings(),
    val skipConfirmation: Boolean = false)

private data class ReplayOrderIntent(val side: Side, val kind: OrderKind, val price: Long?, val quantity: Long,
    val stop: Long?, val target: Long?)

/** A separate modal and ledger: no AccountSession is passed into the replay at any point. */
@Composable
internal fun HistoricalReplayDialog(input: ReplayInput, revision: Long = 0,
    history: (String, Timeframe) -> List<Candle> = { _, _ -> emptyList() },
    watch: (String, Timeframe) -> Unit = { _, _ -> }, drawingDefaults: DrawingLibrary? = null,
    historyError: String? = null,
    onDismiss: () -> Unit) {
    var selected by remember(input) { mutableStateOf(input.instrument) }
    var fineSnapshot by remember(input) { mutableStateOf<List<Candle>?>(null) }
    val fineHistory = remember(revision, selected.symbol, input.rawFrame) { history(selected.symbol, input.rawFrame) }
    LaunchedEffect(selected, fineHistory) {
        if ((selected.symbol != input.instrument.symbol || input.raw.size < 2) && fineSnapshot == null && fineHistory.size >= 2)
            fineSnapshot = fineHistory.toList()
    }
    val raw = fineSnapshot ?: if (selected.symbol == input.instrument.symbol) input.raw else emptyList()
    val rawFrame = input.rawFrame
    val prepared = remember(raw, selected, rawFrame) {
        if (raw.size <= 500) raw.sortedBy(Candle::timeMs) to null
        else null
    }
    val loaded by key(raw, selected, rawFrame) { produceState<Pair<List<Candle>, String?>?>(prepared, raw, selected, rawFrame) {
        value = prepared ?: withContext(Dispatchers.Default) {
            raw.sortedBy(Candle::timeMs) to null
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
                    changeSymbol = { symbol -> input.instruments.firstOrNull { it.symbol == symbol }?.let {
                        selected = it; fineSnapshot = null; watch(symbol, input.rawFrame)
                    } }, history = history, watch = watch, revision = revision, drawingDefaults = drawingDefaults, historyError = historyError, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun ReplayWorkspace(input: ReplayInput, initialBars: List<Candle>, initialFrame: Timeframe,
    changeSymbol: (String) -> Unit,
    history: (String, Timeframe) -> List<Candle>, watch: (String, Timeframe) -> Unit,
    revision: Long, drawingDefaults: DrawingLibrary?, historyError: String?, onDismiss: () -> Unit) {
    val spec = input.instrument
    var bars by remember { mutableStateOf(initialBars) }
    var sourceFrame by remember { mutableStateOf(initialFrame) }
    var start by remember { mutableIntStateOf((bars.size - 100).coerceIn(0, bars.lastIndex - 1)) }
    var state by remember { mutableStateOf(ReplayState(start)) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var stepFrame by remember { mutableStateOf(sourceFrame) }
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
    var accountError by remember { mutableStateOf<String?>(null) }
    var orderToConfirm by remember { mutableStateOf<ReplayOrderIntent?>(null) }
    var sideTab by remember { mutableIntStateOf(0) }
    var initialText by remember { mutableStateOf("50000") }
    var drawdownText by remember { mutableStateOf("2000") }
    var targetText by remember { mutableStateOf("3000") }
    var drawdownMode by remember { mutableStateOf(ReplayDrawdown.STATIC) }
    var allowOvernight by remember { mutableStateOf(true) }
    var cutoffText by remember { mutableStateOf("15:59") }
    var riskConfig by remember { mutableStateOf(ReplayRiskConfig(50_000_000_000L, 2_000_000_000L, 3_000_000_000L, ReplayDrawdown.STATIC)) }
    var risk by remember { mutableStateOf(ReplayRiskState.initial(riskConfig)) }
    var layout by remember { mutableStateOf(ChartSplitLayout.SINGLE) }
    var activeChart by remember { mutableStateOf("main") }
    val frames = remember { mutableStateMapOf<String, Timeframe>() }
    val chartFrame = frames[activeChart] ?: input.timeframe
    val chartSessions = remember { mutableStateMapOf<String, Boolean>() }
    val regular = chartSessions[activeChart] ?: input.regular
    var pendingSource by remember { mutableStateOf<Timeframe?>(null) }
    var requestedRegularChart by remember { mutableStateOf<String?>(null) }
    var priceAxis by remember { mutableStateOf(input.priceAxis) }
    val stepChoices = remember(chartFrame, sourceFrame) { replayStepOptions(chartFrame, sourceFrame) }
    val selectedStep = stepFrame.takeIf { it in stepChoices } ?: stepChoices.first()
    var showIndicators by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(true) }
    val indicatorController = remember { IndicatorController(MemorySnapshotStore()) }
    val indicatorSettings by indicatorController.settings.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(indicatorController) { indicatorController.apply(IndicatorSettings(input.indicators)) }
    val drawings = remember(drawingDefaults) {
        val local = DrawingLibrary(MemorySnapshotStore())
        ChartDrawings(local, drawingDefaults ?: local).also { it.bind(spec.symbol) }
    }
    val current = bars[state.index]
    val regularIndices = remember(bars, regular, sourceFrame) {
        if (regular && sourceFrame.seconds < Timeframe.DAY) bars.indices.filter { isRegularChartTime(bars[it].timeMs) } else emptyList()
    }
    fun useFinerHistory(frame: Timeframe, data: List<Candle>): Boolean {
        val sorted = data.sortedBy(Candle::timeMs)
        val end = current.timeMs + sourceFrame.millis
        val index = sorted.binarySearch { (it.timeMs + frame.millis).compareTo(end) }.let { if (it < 0) -it - 2 else it }
        if (index !in sorted.indices || sorted[index].timeMs + frame.millis != end) {
            message = "券商的 ${frame.label} 历史未覆盖当前回放时刻，请选择其他起点"
            return false
        }
        state = state.copy(index = index); start = index.coerceAtMost(sorted.lastIndex - 1)
        bars = sorted.toList(); sourceFrame = frame; message = null
        return true
    }
    val pendingBars = remember(revision, pendingSource, spec.symbol) {
        pendingSource?.let { history(spec.symbol, it) }.orEmpty()
    }
    LaunchedEffect(pendingSource, pendingBars) {
        val frame = pendingSource
        if (frame != null && pendingBars.size >= 2) {
            pendingSource = null
            if (useFinerHistory(frame, pendingBars)) requestedRegularChart?.let { chartSessions[it] = true }
            requestedRegularChart = null
        }
    }
    fun requestFinerHistory(frame: Timeframe, regularChart: String? = null) {
        playing = false
        watch(spec.symbol, frame)
        val cached = history(spec.symbol, frame)
        if (cached.size >= 2) {
            if (useFinerHistory(frame, cached) && regularChart != null) chartSessions[regularChart] = true
        } else {
            pendingSource = frame; requestedRegularChart = regularChart
            message = "正在向券商获取 ${frame.label} 历史…"
        }
    }
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
    fun flattenBeforeOvernight() {
        if (state.positions.isEmpty() && state.pending.isEmpty()) return
        val index = if (!regular || sourceFrame.seconds >= Timeframe.DAY) state.index else {
            val found = regularIndices.binarySearch(state.index).let { if (it < 0) -it - 2 else it }
            regularIndices.getOrNull(found) ?: error("禁止过夜：没有可用的常规时段平仓 K 线")
        }
        val bar = bars[index]
        var closed = state.copy(pending = emptyList())
        for (position in state.positions) closed = closed.close(spec, position, bar.close, bar.timeMs + sourceFrame.millis, "禁止过夜自动平仓")
        accept(closed)
        message = "禁止过夜：已按截止前最后一根可用 K 线收盘价平仓，并撤销挂单。"
    }
    fun step() {
        if (state.index >= bars.lastIndex) { playing = false; return }
        val last = replayStepEnd(bars, state.index, selectedStep)
        while (state.index < last) {
            if (state.index >= bars.lastIndex || risk.failed) { playing = false; return }
            val previous = bars[state.index]
            val next = bars[state.index + 1]
            val action = try {
                if (!riskConfig.allowOvernight && (state.positions.isNotEmpty() || state.pending.isNotEmpty()))
                    replayCutoffAction(previous.timeMs + sourceFrame.millis, next.timeMs, next.timeMs + sourceFrame.millis, riskConfig.overnightCutoffMinutes)
                else ReplayCutoffAction.NONE
            } catch (failure: Exception) { playing = false; message = failure.message ?: "无法计算过夜时间"; return }
            if (action == ReplayCutoffAction.BEFORE_NEXT) {
                val closed = runCatching { flattenBeforeOvernight() }
                if (closed.isFailure) { playing = false; message = closed.exceptionOrNull()?.message; return }
                if (risk.failed) return
            }
            if (regular && sourceFrame.seconds < Timeframe.DAY && !isRegularChartTime(bars[state.index + 1].timeMs)) {
                state = state.copy(index = state.index + 1)
                if (action == ReplayCutoffAction.AFTER_NEXT) {
                    val closed = runCatching { flattenBeforeOvernight() }
                    if (closed.isFailure) { playing = false; message = closed.exceptionOrNull()?.message; return }
                }
                started = true
                continue
            }
            val result = runCatching { accept(state.advance(spec, bars[state.index + 1])) }
            if (result.isFailure) { playing = false; message = result.exceptionOrNull()?.message ?: "回放撮合失败"; return }
            if (action == ReplayCutoffAction.AFTER_NEXT) {
                val closed = runCatching { flattenBeforeOvernight() }
                if (closed.isFailure) { playing = false; message = closed.exceptionOrNull()?.message; return }
            }
            started = true
        }
        if (state.index == bars.lastIndex) playing = false
    }
    LaunchedEffect(playing, speed, state.index) {
        if (playing) { delay((1000L / speed).coerceAtLeast(50)); step() }
    }
    fun executeOrder(order: ReplayOrderIntent) {
        runCatching {
            require(!risk.failed) { "已触及模拟回撤限额，不能新开仓" }
            require(riskConfig.allowOvernight || !replayOvernightBlocked(current.timeMs + sourceFrame.millis, riskConfig.overnightCutoffMinutes)) { "禁止过夜：当前处于平仓后至下一交易日开始的限制时段" }
            val marketableLimit = order.kind == OrderKind.LIMIT && order.price != null &&
                if (order.side == Side.BUY) order.price >= current.close else order.price <= current.close
            val next = if (order.kind == OrderKind.MARKET || marketableLimit)
                state.open(spec, current, order.side, order.quantity, order.stop, order.target)
            else state.enqueue(spec, order.side, order.kind, order.quantity, order.price ?: error("请输入挂单价格"), order.stop, order.target, current.close)
            accept(next)
        }.onSuccess { started = true }
            .onFailure { message = it.message ?: "无法模拟下单" }
    }
    fun requestOrder(side: Side, kind: OrderKind = orderKind, price: Long? = if (kind == OrderKind.MARKET) null else spec.parsePrice(orderPrice)) {
        runCatching {
            require(!risk.failed) { "已触及模拟回撤限额，不能新开仓" }
            require(riskConfig.allowOvernight || !replayOvernightBlocked(current.timeMs + sourceFrame.millis, riskConfig.overnightCutoffMinutes)) { "禁止过夜：当前处于平仓后至下一交易日开始的限制时段" }
            require(!regular || sourceFrame.seconds >= Timeframe.DAY || isRegularChartTime(current.timeMs)) { "当前回放时间不在常规时段，请前进到开市时间" }
            val qty = spec.parseQuantity(quantity) ?: error("请输入有效数量")
            require(kind == OrderKind.MARKET || price != null) { "请输入挂单价格" }
            ReplayOrderIntent(side, kind, price, qty,
                if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价"),
                if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价"))
        }.onSuccess { order ->
            message = null; playing = false
            if (input.skipConfirmation) executeOrder(order) else orderToConfirm = order
        }.onFailure { message = it.message ?: "无法模拟下单" }
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
        entryKinds = OrderKind.entries.toSet(), entryLots = spec.parseQuantity(quantity),
        onEntryOrder = { side, kind, price -> requestOrder(side, kind, price) },
    )
    val chartOrders = remember(state.pending, spec) { state.pending.map { order ->
        PendingOrder("replay-order-${order.id}", OrderRequest(spec.executionSymbol ?: spec.symbol, order.side, order.kind,
            order.quantity, order.price, order.stop, order.target), createdAtMs = current.timeMs, editable = true, protectionEditable = true)
    } }
    val historyTrades = remember(state.trades) { state.trades.mapIndexed { index, trade ->
        val p = trade.position
        ClosedTrade("replay-trade-$index", p.id, p.symbol, p.side, p.lotsHundredths, p.entryPrice, trade.exit,
            p.openedAtMs, trade.exitTimeMs, trade.pnlMicros, trade.reason)
    } }
    val openExecutions = remember(state.positions) { state.positions.map { p ->
        Execution("replay-execution-${p.id}", p.symbol, p.side, p.lotsHundredths, p.entryPrice, p.openedAtMs, p.id)
    } }
    val realized = remember(state.trades) { runCatching { state.trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) } }.getOrNull() }
    val analysis = remember(state.trades) { runCatching { replayAnalysis(state.trades) }.getOrNull() }
    val markPrice = remember(bars, state.index, regular, sourceFrame, regularIndices) {
        if (regular && sourceFrame.seconds < Timeframe.DAY && !isRegularChartTime(current.timeMs)) {
            val found = regularIndices.binarySearch(state.index).let { if (it < 0) -it - 2 else it }
            regularIndices.getOrNull(found)?.let { bars[it].close }
        } else current.close
    }
    val floating = remember(state.positions, markPrice) { if (markPrice == null) null else runCatching {
        state.positions.fold(0L) { sum, p ->
            val gross = grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, markPrice)
            checkedAdd(sum, checkedAdd(gross, -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths))))
        }
    }.getOrNull() }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("历史回放 · 本地模拟", style = MaterialTheme.typography.titleMedium)
            Text("${spec.symbol} · 图表 ${chartFrame.label} · 推进 ${selectedStep.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { playing = false; if (started) confirmExit = true else onDismiss() }, Modifier.testTag("replay-close")) { Text("关闭") }
        }
        historyError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("replay-history-error")) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ChartToolbar(spec, input.instruments, { sym ->
                    if (sym != spec.symbol) {
                        if (started) { playing = false; switchTo = sym } else changeSymbol(sym)
                    }
                }, chartFrame, { frame ->
                    if (regular && frame.isSeconds) message = "秒线暂不支持常规时段，请先切换 ETH"
                    else {
                        pendingSource = null; requestedRegularChart = null
                        frames[activeChart] = frame
                        if (frame.seconds < sourceFrame.seconds && sourceFrame.seconds % frame.seconds == 0) {
                            requestFinerHistory(frame)
                        } else message = null
                    }
                }, input.intervals, emptyList(), ChartToggle("指标 ${indicatorSettings.indicators.size}", true) { showIndicators = true },
                    Modifier.fillMaxWidth(), additionalControls = {
                        AssistChip(onClick = { showHistory = !showHistory }, modifier = Modifier.testTag("replay-history-toggle"),
                            label = { Text("历史订单") }, leadingIcon = { Icon(AppIcons.History, null, Modifier.size(18.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = if (showHistory) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
                        ChartLayoutMenu(layout) { next ->
                            layout = next
                            if (activeChart !in listOf("main", "split1", "split2", "split3").take(next.count)) activeChart = "main"
                        }
                        ChartSessionControls(if (regular) ChartSession.REGULAR else ChartSession.ELECTRONIC) { selected ->
                            pendingSource = null; requestedRegularChart = null
                            val nextRegular = selected == ChartSession.REGULAR
                            val unavailable = if (nextRegular) chartSessionCandles(emptyList(), spec.symbol, Timeframe.M1, true).second else null
                            if (unavailable != null) message = unavailable
                            else if (nextRegular && chartFrame.isSeconds) message = "秒线暂不支持常规时段，请先切换分钟周期"
                            else if (nextRegular && sourceFrame != Timeframe.M1 && sourceFrame.seconds < Timeframe.DAY) {
                                requestFinerHistory(Timeframe.M1, activeChart)
                            } else { chartSessions[activeChart] = nextRegular; message = null }
                        }
                    }, singleRow = true)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                DrawingToolbar(drawings, indicatorSettings.indicators.size, { scope.launch { indicatorController.apply(IndicatorSettings()) } })
                ChartSplitWorkspace(layout, activeChart, { id, cellModifier ->
                    val frame = frames[id] ?: input.timeframe
                    val cellRegular = chartSessions[id] ?: input.regular
                    val useSource = frame.seconds >= sourceFrame.seconds && frame.seconds % sourceFrame.seconds == 0 &&
                        (frame.seconds < Timeframe.DAY || frame == sourceFrame) &&
                        (!cellRegular || frame.seconds >= Timeframe.DAY || sourceFrame == Timeframe.M1)
                    val brokerFrame = if (cellRegular && frame.seconds < Timeframe.DAY) Timeframe.M1 else frame
                    LaunchedEffect(spec.symbol, brokerFrame, useSource) { if (!useSource) watch(spec.symbol, brokerFrame) }
                    val brokerBars = remember(revision, spec.symbol, brokerFrame, useSource) {
                        if (useSource) emptyList() else history(spec.symbol, brokerFrame)
                    }
                    val cutoff = if (current.timeMs > Long.MAX_VALUE - sourceFrame.millis) Long.MAX_VALUE else current.timeMs + sourceFrame.millis
                    val visible = remember(bars, state.index, sourceFrame, frame, cellRegular, brokerBars, cutoff) {
                        if (useSource) runCatching { replayChartBars(bars, state.index, sourceFrame, frame, spec.symbol, cellRegular) }.getOrElse { emptyList() }
                        else {
                            val completed = replayCompletedBrokerBars(brokerBars, brokerFrame, cutoff)
                            if (cellRegular && frame.seconds < Timeframe.DAY)
                                runCatching { chartSessionCandles(completed, spec.symbol, frame, true).first }.getOrElse { emptyList() }
                            else completed
                        }
                    }
                    val price = if (cellRegular && sourceFrame.seconds < Timeframe.DAY && !isRegularChartTime(current.timeMs))
                        visible.lastOrNull()?.close else current.close
                    Box(cellModifier.testTag("replay-chart-$id").semantics { contentDescription = "回放 ${spec.symbol} ${frame.label}" }.pointerInput(id) {
                        awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) activeChart = id } }
                    }) {
                        TradingChart(accountKey = "local-historical-replay-$id", instrument = spec,
                            quote = price?.let { Quote(it, it, current.timeMs) }, candles = visible, timeframe = frame,
                            onTimeframe = { frames[id] = it }, positions = state.positions, trades = historyTrades, selectedPositionId = null,
                            focusedTrade = null, draft = null, preview = null, orders = chartOrders, actions = localChartActions,
                            executions = openExecutions,
                            onSide = { side -> if (id != activeChart) activeChart = id else requestOrder(side, OrderKind.MARKET, null) },
                            modifier = Modifier.fillMaxSize(), indicators = indicatorSettings.indicators,
                            chrome = ChartChrome(drawings = drawings, showDrawingFavorites = false,
                                priceAxis = priceAxis, onPriceAxis = { priceAxis = it }, sharedToolbar = true, showHistory = showHistory,
                                desktopAxisStyle = true, showNavigation = false, showWatermark = true, chartTimeZone = input.zone))
                        if (!useSource && visible.isEmpty()) Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(if (brokerBars.isEmpty()) "正在向券商获取 ${frame.label} 历史…" else "当前回放时刻没有已完成的 ${frame.label} 历史",
                                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!useSource && frame.seconds >= Timeframe.DAY && visible.isNotEmpty())
                            Text("券商结算数据 · 仅显示已完成 K 线", Modifier.align(Alignment.BottomStart).padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }, Modifier.weight(1f).fillMaxHeight(), favoriteDrawings = drawings)
                }
                Surface(Modifier.fillMaxWidth().padding(top = 6.dp), shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { playing = !playing; started = true }, enabled = state.index < bars.lastIndex && !risk.failed,
                                modifier = Modifier.size(36.dp).testTag("replay-play")) {
                                Icon(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { playing = false; step() }, enabled = state.index < bars.lastIndex && !risk.failed,
                                modifier = Modifier.size(36.dp).testTag("replay-next")) {
                                Icon(AppIcons.ChevronRight, "前进一步", Modifier.size(20.dp))
                            }
                            Text("步长", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                            stepChoices.forEach { frame -> TextButton(onClick = { stepFrame = frame }, modifier = Modifier.testTag("replay-step-${frame.id}"), contentPadding = PaddingValues(horizontal = 7.dp)) {
                                Text(replayStepLabel(frame), color = if (selectedStep == frame) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 3.dp))
                            listOf(1, 2, 5, 10).forEach { n -> TextButton(onClick = { speed = n }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("${n}x", color = if (speed == n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            TextButton(onClick = { playing = false; confirmReset = true }) { Text("重选起点") }
                            Text(chartBarDateTime(current.timeMs, sourceFrame, input.zone.zoneId), modifier = Modifier.testTag("replay-time"), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!started) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("回放起点", style = MaterialTheme.typography.labelMedium)
                                Slider(value = start.toFloat(), onValueChange = { start = it.toInt().coerceIn(0, bars.lastIndex - 1); state = ReplayState(start) },
                                    valueRange = 0f..(bars.lastIndex - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.width(200.dp).testTag("replay-start"))
                                OutlinedTextField(startTime, { startTime = it }, label = { Text(if (sourceFrame.seconds >= Timeframe.DAY) "YYYY-MM-DD" else "YYYY-MM-DD HH:mm") },
                                    modifier = Modifier.width(200.dp).testTag("replay-start-time"), singleLine = true)
                                TextButton(onClick = {
                                    val at = if (sourceFrame.seconds >= Timeframe.DAY) runCatching { Instant.parse(startTime.trim() + "T00:00:00Z").toEpochMilliseconds() }.getOrNull()
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
                }
            }
            Surface(Modifier.widthIn(min = 260.dp, max = 310.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本地模拟", style = MaterialTheme.typography.titleMedium)
                            Text(spec.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { if (started) message = "请先重选起点再修改模拟账户设置" else { accountError = null; showAccount = true } },
                            modifier = Modifier.testTag("replay-account-settings")) { Text("账户设置") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("订单", "分析").forEachIndexed { i, label ->
                            Surface(onClick = { sideTab = i }, modifier = Modifier.weight(1f).testTag("replay-tab-$i"),
                                shape = MaterialTheme.shapes.small,
                                color = if (sideTab == i) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (sideTab == i) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (sideTab == 0) {
                        ReplaySection("模拟订单") {
                            Row(Modifier.fillMaxWidth()) { OrderKind.entries.forEach { kind -> TextButton(onClick = { orderKind = kind }, modifier = Modifier.weight(1f)) {
                                Text(when(kind) { OrderKind.MARKET -> "市价"; OrderKind.LIMIT -> "限价"; OrderKind.STOP -> "止损" },
                                    color = if (orderKind == kind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            } } }
                            Text(if (orderKind == OrderKind.MARKET) "按当前回放价格成交" else "按回放行情撮合", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            if (orderKind != OrderKind.MARKET) OutlinedTextField(orderPrice, { orderPrice = it }, label = { Text("挂单价格") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（${spec.quantityUnit}）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-quantity"))
                            OutlinedTextField(stop, { stop = it }, label = { Text("止损价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-stop"))
                            OutlinedTextField(target, { target = it }, label = { Text("止盈价（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-target"))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { requestOrder(Side.SELL) }, modifier = Modifier.weight(1f).testTag("replay-sell"),
                                    colors = ButtonDefaults.buttonColors(containerColor = TradingColors.Down)) { Text("卖出") }
                                Button(onClick = { requestOrder(Side.BUY) }, modifier = Modifier.weight(1f).testTag("replay-buy"),
                                    colors = ButtonDefaults.buttonColors(containerColor = TradingColors.Up)) { Text("买入") }
                            }
                        }
                        ReplaySection("账户概览") {
                            ReplayMetric("模拟资金", "${money(riskConfig.initialMicros)} ${spec.currency}")
                            ReplayMetric("净值", if (realized != null && floating != null) runCatching { money(checkedAdd(riskConfig.initialMicros, checkedAdd(realized, floating))) }.getOrDefault("—") else "—")
                            ReplayMetric("回撤线 · ${riskConfig.mode.name}", "${money(risk.threshold)}${if (risk.failed) " · 已触及" else ""}")
                            ReplayMetric("盈利目标", "${money(riskConfig.targetMicros)}${if (risk.targetReached) " · 已达成" else ""}")
                            ReplayMetric("持仓过夜", if (riskConfig.allowOvernight) "允许" else "禁止 · ${replayCutoffText(riskConfig.overnightCutoffMinutes)} 芝加哥")
                            Text("已实现 ${realized?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-realized"), style = MaterialTheme.typography.bodySmall)
                            Text("浮动 ${floating?.let { money(it, true) } ?: "—"} ${spec.currency}", Modifier.testTag("replay-floating"), style = MaterialTheme.typography.bodySmall)
                            Text("成交 ${state.trades.size} 笔 · 胜 ${state.trades.count { it.pnlMicros > 0 }} · 负 ${state.trades.count { it.pnlMicros < 0 }}", Modifier.testTag("replay-trade-count"), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.pending.isNotEmpty()) ReplaySection("模拟挂单") {
                            state.pending.forEach { order -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (order.side == Side.BUY) "买" else "卖"} ${if (order.kind == OrderKind.LIMIT) "限价" else "止损"} ${spec.formatPrice(order.price)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { state = state.copy(pending = state.pending.filterNot { it.id == order.id }) }) { Text("撤单") }
                            } }
                        }
                        if (state.positions.isNotEmpty()) ReplaySection("模拟持仓") {
                            state.positions.forEach { p ->
                                Text("${if (p.side == Side.BUY) "买" else "卖"} ${spec.formatQuantity(p.lotsHundredths)} · ${spec.formatPrice(p.entryPrice)}", style = MaterialTheme.typography.bodyMedium)
                                Text("SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} · TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { runCatching {
                                    val sl = if (stop.isBlank()) null else spec.parsePrice(stop) ?: error("请输入有效止损价")
                                    val tp = if (target.isBlank()) null else spec.parsePrice(target) ?: error("请输入有效止盈价")
                                    state.protect(spec, p, current.close, sl, tp)
                                }.onSuccess { state = it; message = null }.onFailure { message = it.message } }) { Text("更新止损止盈") }
                                TextButton(onClick = { runCatching { state.close(spec, p, current.close, current.timeMs, "手动平仓") }
                                    .onSuccess { message = null; accept(it) }.onFailure { message = it.message } }) { Text("模拟平仓") }
                            }
                        }
                    } else {
                        val completed = state.trades.size
                        val wins = state.trades.count { it.pnlMicros > 0 }
                        ReplaySection("交易表现") {
                            ReplayMetric("平仓次数", "$completed")
                            ReplayMetric("盈利 / 亏损", "$wins / ${state.trades.count { it.pnlMicros < 0 }}")
                            ReplayMetric("胜率", if (completed == 0) "—" else "${wins * 100 / completed}%")
                            ReplayMetric("已实现盈亏", realized?.let { money(it, true) } ?: "—")
                            ReplayMetric("平均盈亏", if (completed == 0 || realized == null) "—" else money(realized / completed, true))
                            analysis?.let { stats ->
                                ReplayMetric("总盈利", money(stats.grossProfit, true))
                                ReplayMetric("总亏损", money(stats.grossLoss, true))
                                ReplayMetric("平仓权益最大回撤", money(stats.maxClosedDrawdown))
                                ReplayMetric("最佳交易", stats.best?.let { money(it, true) } ?: "—")
                                ReplayMetric("最差交易", stats.worst?.let { money(it, true) } ?: "—")
                            }
                        }
                        ReplaySection("交易记录") {
                            if (state.trades.isEmpty()) Text("暂无模拟成交", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            state.trades.asReversed().take(30).forEach { t ->
                                ReplayMetric("${t.reason} · ${spec.formatPrice(t.exit)}", money(t.pnlMicros, true))
                            }
                        }
                        Text("净持仓按先开先平抵消反向订单。仅使用 K 线 OHLC；同根触及止损按保守成交。无点差和滑点；回撤仅在 K 线收盘时检查。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showIndicators) IndicatorSettingsDialog(indicatorController) { showIndicators = false }
    orderToConfirm?.let { order -> AlertDialog(onDismissRequest = { orderToConfirm = null },
        title = { Text("确认模拟订单") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${spec.symbol} · ${if (order.side == Side.BUY) "买入" else "卖出"} · ${when (order.kind) { OrderKind.MARKET -> "市价"; OrderKind.LIMIT -> "限价"; OrderKind.STOP -> "止损" }}")
            Text("数量 ${spec.formatQuantity(order.quantity)} ${spec.quantityUnit}")
            order.price?.let { Text("价格 ${spec.formatPrice(it)}") }
            Text("止损 ${order.stop?.let(spec::formatPrice) ?: "—"} · 止盈 ${order.target?.let(spec::formatPrice) ?: "—"}")
        } }, confirmButton = { TextButton(onClick = { orderToConfirm = null; executeOrder(order) }, modifier = Modifier.testTag("replay-confirm-order")) { Text("确认模拟下单") } },
        dismissButton = { TextButton(onClick = { orderToConfirm = null }) { Text("取消") } }) }
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
    if (showAccount) AlertDialog(onDismissRequest = { showAccount = false; drawdownMode = riskConfig.mode; allowOvernight = riskConfig.allowOvernight; cutoffText = replayCutoffText(riskConfig.overnightCutoffMinutes) }, title = { Text("模拟账户设置") }, text = {
        Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置初始资金、回撤额度和盈利目标。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            ReplaySection("资金与目标 · ${spec.currency}") {
                OutlinedTextField(initialText, { initialText = it }, label = { Text("初始资金") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(drawdownText, { drawdownText = it }, label = { Text("最大回撤额度") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(targetText, { targetText = it }, label = { Text("总盈利目标") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            ReplaySection("回撤类型") {
                ReplayDrawdown.entries.forEach { mode -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = drawdownMode == mode, onClick = { drawdownMode = mode })
                    Column { Text(when(mode) { ReplayDrawdown.STATIC -> "静态"; ReplayDrawdown.EOD -> "EOD 日终追踪"; ReplayDrawdown.TDD -> "TDD 浮动追踪" })
                        Text(when(mode) { ReplayDrawdown.STATIC -> "回撤线固定"; ReplayDrawdown.EOD -> "按日终余额上移"; ReplayDrawdown.TDD -> "按已观察净值高点上移" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                } }
            }
            ReplaySection("持仓过夜") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = allowOvernight, onClick = { allowOvernight = true }, modifier = Modifier.testTag("replay-overnight-allow"))
                    Text("允许持仓过夜")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !allowOvernight, onClick = { allowOvernight = false }, modifier = Modifier.testTag("replay-overnight-disallow"))
                    Text("不允许持仓过夜")
                }
                if (!allowOvernight) {
                    OutlinedTextField(cutoffText, { cutoffText = it }, label = { Text("每日平仓时间（芝加哥）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("replay-overnight-cutoff"))
                    Text("截止时按最后一根可用 K 线的收盘价自动平仓并撤销挂单，芝加哥 17:00 后可重新下单。未模拟节假日或滑点。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("本地通用规则，日终使用芝加哥 17:00；不代表特定交易公司的考核规则。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            require(allowOvernight || sourceFrame.seconds < Timeframe.DAY) { "禁止过夜需要券商提供日线以下的历史数据" }
            ReplayRiskConfig(parseFixed(initialText, 6) ?: error("初始资金无效"),
                parseFixed(drawdownText, 6) ?: error("回撤额度无效"), parseFixed(targetText, 6) ?: error("盈利目标无效"), drawdownMode,
                allowOvernight, parseReplayCutoff(cutoffText) ?: error("请输入有效的平仓时间，例如 15:59"))
        }
            .onSuccess { riskConfig = it; risk = ReplayRiskState.initial(it); showAccount = false; message = null }
            .onFailure { accountError = it.message }
    }, modifier = Modifier.testTag("replay-account-save")) { Text("保存") } }, dismissButton = { TextButton(onClick = { showAccount = false; drawdownMode = riskConfig.mode; allowOvernight = riskConfig.allowOvernight; cutoffText = replayCutoffText(riskConfig.overnightCutoffMinutes) }) { Text("取消") } })
}

@Composable
private fun ReplaySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ReplayMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
