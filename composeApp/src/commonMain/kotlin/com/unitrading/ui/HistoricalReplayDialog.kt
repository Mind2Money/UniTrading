package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.unitrading.app.ChartUndoCoordinator
import com.unitrading.app.IndicatorController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ReplayRecord
import com.unitrading.app.ReplayRecords
import com.unitrading.app.ReplayEquityPoint
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
import kotlin.time.Clock
import kotlin.random.Random

internal data class ReplayInput(val instrument: Instrument, val timeframe: Timeframe, val regular: Boolean,
    val zone: ChartTimeZone, val raw: List<Candle>, val indicators: List<IndicatorConfig>,
    val rawFrame: Timeframe = timeframe, val instruments: List<Instrument> = listOf(instrument),
    val intervals: IntervalOptions = IntervalOptions(), val priceAxis: PriceAxisSettings = PriceAxisSettings(),
    val skipConfirmation: Boolean = false,
    val riskConfig: ReplayRiskConfig = defaultReplayRiskConfig())

private data class ReplayOrderIntent(val side: Side, val kind: OrderKind, val price: Long?, val quantity: Long,
    val stop: Long?, val target: Long?)

/** A separate modal and ledger: no AccountSession is passed into the replay at any point. */
@Composable
internal fun HistoricalReplayDialog(input: ReplayInput, revision: Long = 0,
    records: ReplayRecords? = null,
    history: (String, Timeframe) -> List<Candle> = { _, _ -> emptyList() },
    watch: (String, Timeframe) -> Unit = { _, _ -> }, drawingDefaults: DrawingLibrary? = null,
    historyError: String? = null,
    onDismiss: () -> Unit) {
    var selected by remember(input) { mutableStateOf(input.instrument) }
    var fineSnapshot by remember(input) { mutableStateOf<List<Candle>?>(null) }
    // Unsaved completed runs belong to this replay window, even when its symbol or start changes.
    val temporaryRecords = remember(input) { mutableStateListOf<ReplayRecord>() }
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
                    } }, history = history, watch = watch, revision = revision, drawingDefaults = drawingDefaults, historyError = historyError,
                    records = records, temporaryRecords = temporaryRecords, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun ReplayWorkspace(input: ReplayInput, initialBars: List<Candle>, initialFrame: Timeframe,
    changeSymbol: (String) -> Unit,
    history: (String, Timeframe) -> List<Candle>, watch: (String, Timeframe) -> Unit,
    revision: Long, drawingDefaults: DrawingLibrary?, historyError: String?, records: ReplayRecords?,
    temporaryRecords: MutableList<ReplayRecord>, onDismiss: () -> Unit) {
    val spec = input.instrument
    var bars by remember { mutableStateOf(initialBars) }
    var sourceFrame by remember { mutableStateOf(initialFrame) }
    var start by remember { mutableIntStateOf((bars.size - 100).coerceIn(0, bars.lastIndex - 1)) }
    var state by remember { mutableStateOf(ReplayState(start)) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var stepFrame by remember { mutableStateOf(sourceFrame) }
    var draft by remember { mutableStateOf(OrderDraft(quantity = spec.formatQuantity(spec.minLotsHundredths.coerceAtLeast(spec.lotStepHundredths)))) }
    var showDraft by remember { mutableStateOf(false) }
    var lineToConfirm by remember { mutableStateOf<LineAction?>(null) }
    val quantity = draft.quantity
    val stop = draft.stopLoss.takeIf { draft.stopLossEnabled }.orEmpty()
    val target = draft.takeProfit.takeIf { draft.takeProfitEnabled }.orEmpty()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var startTime by remember { mutableStateOf("") }
    var startCalendar by remember { mutableStateOf(false) }
    var recordId by remember { mutableStateOf("${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}") }
    // Deleting the active session must not immediately recreate it through autosave or normal exit.
    var currentRecordDeleted by remember { mutableStateOf(false) }
    // A completed run is already handled by the user's save/discard choice.
    var completionFinalized by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ReplayRecord?>(null) }
    var description by remember { mutableStateOf("") }
    val equityPoints = remember { mutableStateListOf<ReplayEquityPoint>() }
    var savedRecords by remember { mutableStateOf<List<ReplayRecord>>(emptyList()) }
    var recordsExpanded by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<ReplayRecord?>(null) }
    var recordDeleteError by remember { mutableStateOf<String?>(null) }
    val orderKind = draft.kind
    val orderPrice = draft.entry
    var orderToConfirm by remember { mutableStateOf<ReplayOrderIntent?>(null) }
    var sideTab by remember { mutableIntStateOf(0) }
    var panelOpen by remember { mutableStateOf(true) }
    val riskConfig = input.riskConfig
    var showFailure by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
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
    val objectLayers = remember { ChartObjectLayers(MemorySnapshotStore()) }
    val indicatorSettings by indicatorController.settings.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(indicatorController) { indicatorController.apply(IndicatorSettings(input.indicators)) }
    val drawings = remember(drawingDefaults) {
        val local = DrawingLibrary(MemorySnapshotStore())
        ChartDrawings(local, drawingDefaults ?: local).also { it.bind(spec.symbol) }
    }
    remember(indicatorController, drawings, objectLayers) { ChartUndoCoordinator(indicatorController, drawings, objectLayers, scope) }.scope = scope
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
    fun accept(next: ReplayState, evaluationBar: Candle? = null) {
        val sourceBar = evaluationBar ?: bars[next.index]
        val softPrice = replaySoftStopPrice(spec, riskConfig, risk, state, next, sourceBar)
        var checked = next
        if (softPrice != null) {
            checked = checked.copy(pending = emptyList())
            checked.positions.toList().forEach { position ->
                checked = checked.close(spec, position, softPrice, sourceBar.timeMs, "每日软止损自动平仓")
            }
        }
        // Once flattened, later prices in this OHLC bar cannot affect the closed position.
        val bar = if (softPrice == null) sourceBar else sourceBar.copy(open = softPrice, high = softPrice, low = softPrice, close = softPrice)
        val realized = checked.trades.fold(0L) { n, trade -> checkedAdd(n, trade.pnlMicros) }
        val floating = checked.positions.fold(0L) { n, p -> checkedAdd(n,
            checkedAdd(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, bar.close),
                -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths)))) }
        val balance = checkedAdd(riskConfig.initialMicros, realized)
        val equity = checkedAdd(balance, floating)
        var observedEquity = equity
        val wasDailyHit = risk.dailyLossHit
        val wasConsistencyHit = risk.consistencyHit
        risk = risk.observe(riskConfig, bar.timeMs, balance, equity)
        // Intrabar prices can cross a risk line and recover before the candle closes.
        if (checked.index > state.index && checked.positions.isNotEmpty()) {
            val worstFloating = checked.positions.fold(0L) { n, p ->
                val price = if (p.side == Side.BUY) bar.low else bar.high
                checkedAdd(n, checkedAdd(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, price),
                    -checkedAdd(p.entryFeeMicros, commission(spec, p.lotsHundredths))))
            }
            observedEquity = minOf(equity, checkedAdd(balance, worstFloating))
            risk = risk.observe(riskConfig, bar.timeMs, balance, observedEquity).copy(lastEquity = equity)
        }
        val softLocked = risk.dailyLossHit && riskConfig.dailyLossMode == ReplayDailyLoss.SOFT
        var accepted = if (risk.failed || softLocked) checked.copy(pending = emptyList()) else checked
        // A changed ledger may not have a safely reconstructible intrabar trigger; flatten at the known close.
        if (softLocked && !risk.failed && accepted.positions.isNotEmpty()) {
            accepted.positions.toList().forEach { position ->
                accepted = accepted.close(spec, position, sourceBar.close, sourceBar.timeMs, "每日软止损自动平仓")
            }
            val settled = accepted.closingEquity(spec, riskConfig, sourceBar.close)
            risk = risk.observe(riskConfig, sourceBar.timeMs, settled, settled).copy(dailyLossHit = true)
        }
        val profitTrigger = replayProfitTrigger(spec, riskConfig, risk, state, accepted, bar)
        if (profitTrigger != null) {
            accepted = accepted.copy(pending = emptyList())
            val reason = if (profitTrigger.kind == ReplayProfitKind.TARGET) "盈利目标自动平仓" else "一致性自动平仓"
            accepted.positions.toList().forEach { position ->
                accepted = accepted.close(spec, position, profitTrigger.price, bar.timeMs, reason)
            }
            val settled = accepted.closingEquity(spec, riskConfig, profitTrigger.price)
            risk = risk.observe(riskConfig, bar.timeMs, settled, settled).copy(
                targetReached = profitTrigger.kind == ReplayProfitKind.TARGET,
                consistencyHit = profitTrigger.kind == ReplayProfitKind.CONSISTENCY)
        }
        val tradeChanged = accepted.trades.size != state.trades.size
        if (equityPoints.isEmpty() || tradeChanged || bar.timeMs - equityPoints.last().timeMs >= 900_000L || risk.failed) {
            val sampled = if (risk.failed && next.index > state.index) observedEquity
                else if (profitTrigger != null) risk.lastEquity else equity
            val point = ReplayEquityPoint(bar.timeMs, sampled)
            if (equityPoints.lastOrNull()?.timeMs == bar.timeMs) equityPoints[equityPoints.lastIndex] = point else equityPoints.add(point)
            if (equityPoints.size > 5_000) equityPoints.removeAt(0)
        }
        state = accepted
        if (softLocked && !wasDailyHit) message = "触及每日软止损：已自动平仓并撤销挂单，下一交易日前禁止开仓。"
        if (!risk.dailyLossHit && wasDailyHit) message = "新交易日开始，已解除每日软止损限制。"
        if (risk.consistencyHit && !wasConsistencyHit) message = "达到今日一致性盈利上限：已自动平仓并撤销挂单，下一交易日恢复开仓。"
        if (!risk.consistencyHit && wasConsistencyHit) message = "新交易日开始，已解除一致性开仓限制。"
        if (risk.failed) { playing = false; showFailure = true; message = if (risk.dailyFailure) "触及最大日亏损，考核失败。" else "触及最大总亏损，考核失败。" }
        else if (risk.targetReached) { playing = false; showSuccess = true; message = null; orderToConfirm = null; lineToConfirm = null }
        if (!risk.failed && !risk.targetReached && !softLocked && !risk.consistencyHit && profitTrigger == null) next.lastNotice?.let { message = it }
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
            if (state.index >= bars.lastIndex || risk.failed || risk.targetReached) { playing = false; return }
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
                if (risk.failed || risk.targetReached) { playing = false; return }
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
            val result = runCatching {
                val nextBar = bars[state.index + 1]
                val pendingAtOpen = replaySinglePendingAtOpen(spec, state, nextBar)
                val pendingProfitAtOpen = pendingAtOpen?.let { replayProfitAtOpen(spec, riskConfig, risk, it, nextBar) } == true
                if (replayProfitAtOpen(spec, riskConfig, risk, state, nextBar) || pendingProfitAtOpen) {
                    val openingBar = nextBar.copy(high = nextBar.open, low = nextBar.open, close = nextBar.open)
                    accept((if (pendingProfitAtOpen) pendingAtOpen!! else state).copy(index = state.index + 1, lastNotice = null), openingBar)
                } else {
                    val softPrice = replaySoftStopBeforeAdvance(spec, riskConfig, risk, state, nextBar)
                    val pendingSoftPrice = pendingAtOpen?.let { replaySoftStopBeforeAdvance(spec, riskConfig, risk, it, nextBar) }
                    if (softPrice != null || pendingSoftPrice != null) {
                        val triggerPrice = softPrice ?: pendingSoftPrice!!
                        val triggerBar = nextBar.copy(open = triggerPrice, high = triggerPrice, low = triggerPrice, close = triggerPrice)
                        accept((if (softPrice == null) pendingAtOpen!! else state).copy(index = state.index + 1, lastNotice = null), triggerBar)
                    } else {
                        val pendingProfitBeforeTp = pendingAtOpen?.let { replayProfitPrecedesTakeProfit(spec, riskConfig, risk, it, nextBar) } == true
                        val nextState = if (pendingProfitBeforeTp) pendingAtOpen!!.copy(index = state.index + 1, lastNotice = null)
                            else if (replayProfitPrecedesTakeProfit(spec, riskConfig, risk, state, nextBar)) state.copy(index = state.index + 1, lastNotice = null)
                            else state.advance(spec, nextBar)
                        accept(nextState)
                    }
                }
            }
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
            require(!risk.targetReached) { "已达到盈利目标，不能新开仓" }
            require(!(risk.dailyLossHit && riskConfig.dailyLossMode == ReplayDailyLoss.SOFT)) { "已触及每日软止损，下一交易日前禁止开仓" }
            require(!risk.consistencyHit) { "已达到今日一致性盈利上限，下一交易日前禁止开仓" }
            require(riskConfig.allowOvernight || !replayOvernightBlocked(current.timeMs + sourceFrame.millis, riskConfig.overnightCutoffMinutes)) { "禁止过夜：当前处于平仓后至下一交易日开始的限制时段" }
            val marketableLimit = order.kind == OrderKind.LIMIT && order.price != null &&
                if (order.side == Side.BUY) order.price >= current.close else order.price <= current.close
            val next = if (order.kind == OrderKind.MARKET || marketableLimit)
                state.open(spec, current, order.side, order.quantity, order.stop, order.target)
            else state.enqueue(spec, order.side, order.kind, order.quantity, order.price ?: error("请输入挂单价格"), order.stop, order.target, current.close)
            accept(next)
        }.onSuccess { started = true; showDraft = false }
            .onFailure { message = it.message ?: "无法模拟下单" }
    }
    fun requestOrder(side: Side, kind: OrderKind = orderKind, price: Long? = if (kind == OrderKind.MARKET) null else spec.parsePrice(orderPrice)) {
        runCatching {
            require(!risk.failed) { "已触及模拟回撤限额，不能新开仓" }
            require(!risk.targetReached) { "已达到盈利目标，不能新开仓" }
            require(!(risk.dailyLossHit && riskConfig.dailyLossMode == ReplayDailyLoss.SOFT)) { "已触及每日软止损，下一交易日前禁止开仓" }
            require(!risk.consistencyHit) { "已达到今日一致性盈利上限，下一交易日前禁止开仓" }
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
    val ticketSpec = if (spec.isContinuous && spec.executionSymbol == null) spec.copy(isContinuous = false) else spec
    val replayQuote = Quote(current.close, current.close, current.timeMs)
    fun previewOrder(order: OrderRequest): OrderPreview = runCatching {
        require(!risk.failed) { "已触及回撤限制" }
        require(!risk.targetReached) { "已达到盈利目标，不能新开仓" }
        require(!(risk.dailyLossHit && riskConfig.dailyLossMode == ReplayDailyLoss.SOFT)) { "已触及每日软止损，下一交易日前禁止开仓" }
        require(!risk.consistencyHit) { "已达到今日一致性盈利上限，下一交易日前禁止开仓" }
        require(riskConfig.allowOvernight || !replayOvernightBlocked(current.timeMs + sourceFrame.millis, riskConfig.overnightCutoffMinutes)) { "当前处于禁止过夜限制时段" }
        require(!regular || sourceFrame.seconds >= Timeframe.DAY || isRegularChartTime(current.timeMs)) { "当前回放时间不在常规时段" }
        val price = order.entryPrice ?: current.close
        val marketable = order.kind == OrderKind.MARKET || order.kind == OrderKind.LIMIT &&
            if (order.side == Side.BUY) price >= current.close else price <= current.close
        if (marketable) state.open(spec, current, order.side, order.lotsHundredths, order.stopLoss, order.takeProfit)
        else state.enqueue(spec, order.side, order.kind, order.lotsHundredths, price, order.stopLoss, order.takeProfit, current.close)
        OrderPreview(if (marketable) current.close else price, feesKnown = false, marginKnown = false)
    }.getOrElse { OrderPreview(null, errors = listOf(it.message ?: "模拟订单无效")) }
    val draftRequest = draft.request(ticketSpec)
    val draftPreview = draftRequest?.let(::previewOrder)
    fun prepareDraft(side: Side, kind: OrderKind, price: Long?) {
        playing = false; sideTab = 0; panelOpen = true; showDraft = true
        draft = OrderDraft(side, kind, draft.quantity, price?.takeUnless { kind == OrderKind.MARKET }?.let(spec::formatPrice).orEmpty())
        message = null
    }
    fun executeLine(action: LineAction) {
        runCatching {
            when (action) {
                is LineAction.ClosePosition -> accept(state.close(spec, action.position, current.close, current.timeMs, "手动平仓"))
                is LineAction.CancelOrder -> state = state.copy(pending = state.pending.filterNot { "replay-order-${it.id}" == action.order.id })
                is LineAction.RemoveProtection -> state = state.protect(spec, action.position, current.close,
                    if (action.stop) null else action.position.stopLoss, if (action.stop) action.position.takeProfit else null)
                is LineAction.RemovePendingProtection -> state = state.modifyPending(spec, pendingId(action.order.id), action.order.request.entryPrice ?: error("挂单价格无效"),
                    if (action.stop) null else action.order.request.stopLoss, if (action.stop) action.order.request.takeProfit else null, current.close)
                is LineAction.RemoveDraftProtection -> draft = draft.withProtectionEnabled(ticketSpec, replayQuote, action.stop, false)
                LineAction.SubmitDraft -> requestOrder(draft.side, draft.kind, if (draft.kind == OrderKind.MARKET) null else ticketSpec.parsePrice(draft.entry))
                LineAction.CloseDraft -> showDraft = false
                LineAction.EditDraftQuantity -> Unit
            }
        }.onFailure { message = it.message }
    }
    fun requestLine(action: LineAction) {
        if (!input.skipConfirmation && (action is LineAction.ClosePosition || action is LineAction.CancelOrder)) {
            playing = false; lineToConfirm = action
        } else executeLine(action)
    }
    val localChartActions = ChartOrderActions(
        estimateDraft = { it to previewOrder(it) },
        onDraftEntry = { price -> draft = draft.copy(entry = ticketSpec.formatPrice(price)); showDraft = true },
        onDraftProtection = { isStop, price -> draft = draft.withProtectionPrice(isStop, ticketSpec.formatPrice(price)); showDraft = true }, editPending = true,
        estimatePending = { id, price -> state.pending.firstOrNull { "replay-order-${it.id}" == id }?.let { pendingPreview(id, price, it.stop, it.target) } ?: OrderPreview(null) },
        onPendingEntry = { order, price -> runCatching { state.modifyPending(spec, pendingId(order.id), price, order.request.stopLoss, order.request.takeProfit, current.close) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } }, editPendingProtection = true,
        estimatePendingProtection = { id, sl, tp -> state.pending.firstOrNull { "replay-order-${it.id}" == id }?.let { pendingPreview(id, it.price, sl, tp) } ?: OrderPreview(null) },
        onPendingProtection = { order, sl, tp -> runCatching { state.modifyPending(spec, pendingId(order.id), order.request.entryPrice ?: error("挂单价格无效"), sl, tp, current.close) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } },
        onProtection = { position, sl, tp -> runCatching { state.protect(spec, position, current.close, sl, tp) }
            .onSuccess { state = it; message = null }.onFailure { message = it.message } },
        onLineAction = { action -> requestLine(action) }, addPositionProtection = true, removeProtection = true,
        addProtection = true, draftQuantity = draft.quantity, onDraftQuantity = { draft = draft.copy(quantity = it) },
        inlineDraftQuantity = true, directDraftSubmit = true,
        entryKinds = OrderKind.entries.toSet(), entryLots = spec.parseQuantity(quantity),
        onEntryOrder = { side, kind, price -> prepareDraft(side, kind, price) },
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
    fun currentRecord(status: String = if (risk.failed) "失败" else if (risk.targetReached) "已达成" else "进行中"): ReplayRecord {
        val equity = runCatching {
            val realized = state.trades.fold(0L) { n, trade -> checkedAdd(n, trade.pnlMicros) }
            val floating = state.positions.fold(0L) { n, position -> checkedAdd(n,
                checkedAdd(grossPnl(spec, position.side, position.lotsHundredths, position.entryPrice, current.close),
                    -checkedAdd(position.entryFeeMicros, commission(spec, position.lotsHundredths)))) }
            checkedAdd(riskConfig.initialMicros, checkedAdd(realized, floating))
        }.getOrNull()
        val finalEquity = if (risk.failed) equityPoints.lastOrNull()?.equityMicros ?: equity else equity
        val samples = (listOf(ReplayEquityPoint(bars[start].timeMs, riskConfig.initialMicros)) + equityPoints.toList()).let { points ->
            if (finalEquity == null || points.lastOrNull()?.timeMs == current.timeMs) points else points + ReplayEquityPoint(current.timeMs, finalEquity)
        }
        return ReplayRecord(recordId, spec.symbol, spec.currency, riskConfig.initialMicros,
            Clock.System.now().toEpochMilliseconds(), historyTrades, status, current.timeMs, finalEquity, samples, spec.priceDecimals, description,
            startedAtMs = bars[start].timeMs)
    }
    LaunchedEffect(started, historyTrades, riskConfig.initialMicros, recordId, showSuccess, showFailure, currentRecordDeleted, completionFinalized) {
        if (started && !showSuccess && !showFailure && !currentRecordDeleted && !completionFinalized && historyTrades.isNotEmpty() && records != null) {
            runCatching { records.save(currentRecord()) }.onFailure { message = "回放记录保存失败：${it.message ?: "请检查存储"}" }
        }
    }
    fun restartReplay() {
        playing = false; started = false; state = ReplayState(start); risk = ReplayRiskState.initial(riskConfig)
        equityPoints.clear(); description = ""; showFailure = false; showSuccess = false; message = null; currentRecordDeleted = false; completionFinalized = false
        draft = OrderDraft(quantity = spec.formatQuantity(spec.minLotsHundredths.coerceAtLeast(spec.lotStepHundredths)))
        showDraft = false; orderToConfirm = null; lineToConfirm = null
        recordId = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"
    }
    fun finishReplay(after: () -> Unit) {
        if (records == null || historyTrades.isEmpty() || currentRecordDeleted || completionFinalized) { after(); return }
        runCatching { records.save(currentRecord("已结束")) }.onSuccess { after() }
            .onFailure { message = "回放记录保存失败：${it.message ?: "请检查存储"}" }
    }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Surface(Modifier.fillMaxWidth().testTag("replay-header"), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppLogo(24.dp, Modifier.testTag("replay-header-logo"))
                Text("历史回放 · 本地模拟", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${spec.symbol} · 图表 ${chartFrame.label} · 推进 ${selectedStep.label}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                ActionIcon(AppIcons.Close, "关闭回放", "replay-close", modifier = Modifier.size(36.dp), iconSize = 18.dp) {
                    playing = false; if (started) confirmExit = true else onDismiss()
                }
            }
        }
        Spacer(Modifier.height(4.dp))
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
                            quote = price?.let { Quote(it, it, cutoff) }, candles = visible, timeframe = frame,
                            onTimeframe = { frames[id] = it }, positions = state.positions, trades = historyTrades, selectedPositionId = null,
                            focusedTrade = null, draft = draftRequest.takeIf { showDraft }, preview = draftPreview.takeIf { showDraft }, orders = chartOrders, actions = localChartActions,
                            executions = openExecutions,
                            onSide = { side -> activeChart = id; playing = false; sideTab = 0; panelOpen = true; showDraft = true; draft = draft.withSide(side, ticketSpec, replayQuote) },
                            modifier = Modifier.fillMaxSize(), indicators = if (drawings.hideIndicators) emptyList() else indicatorSettings.indicators,
                            chrome = ChartChrome(drawings = drawings, showDrawingFavorites = false,
                                priceAxis = priceAxis, onPriceAxis = { priceAxis = it }, sharedToolbar = true, showHistory = showHistory,
                                allIndicators = indicatorSettings.indicators,
                                onIndicatorChanges = { configs -> scope.launch { indicatorController.apply(IndicatorSettings(configs)) } },
                                objectLayers = objectLayers,
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
                Surface(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("replay-controls"), shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    @Composable fun PlaybackControls() {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { playing = !playing; started = true }, enabled = state.index < bars.lastIndex && !risk.failed && !risk.targetReached,
                                modifier = Modifier.size(36.dp).testTag("replay-play")) {
                                Icon(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { playing = false; step() }, enabled = state.index < bars.lastIndex && !risk.failed && !risk.targetReached,
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
                            TextButton(onClick = {
                                playing = false
                                if (completionFinalized && risk.targetReached) restartReplay() else confirmReset = true
                            }, modifier = Modifier.testTag("replay-reset")) { Text("重选起点") }
                            Text(chartBarDateTime(current.timeMs, sourceFrame, input.zone.zoneId), modifier = Modifier.testTag("replay-time"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    @Composable fun StartControls() {
                        Row(Modifier.testTag("replay-start-controls"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("回放起点", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Slider(value = start.toFloat(), onValueChange = { start = it.toInt().coerceIn(0, bars.lastIndex - 1); state = ReplayState(start) },
                                valueRange = 0f..(bars.lastIndex - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.width(145.dp).testTag("replay-start"))
                            OutlinedTextField(startTime, { startTime = it }, placeholder = { Text(if (sourceFrame.seconds >= Timeframe.DAY) "YYYY-MM-DD" else "YYYY-MM-DD HH:mm", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.width(180.dp).testTag("replay-start-time"), singleLine = true)
                            IconButton(onClick = { startCalendar = true }, modifier = Modifier.testTag("replay-start-calendar")) { Icon(AppIcons.Calendar, "选择起点日期", Modifier.size(20.dp)) }
                            TextButton(onClick = {
                                val at = if (sourceFrame.seconds >= Timeframe.DAY) runCatching { Instant.parse(startTime.trim() + "T00:00:00Z").toEpochMilliseconds() }.getOrNull()
                                    else parseChartTime(startTime, input.zone.zoneId)
                                if (at == null) message = "请输入有效的图表时区日期和时间"
                                else if (at < bars.first().timeMs || at > bars.last().timeMs) message = "该时间不在已加载的历史范围内"
                                else {
                                    val found = bars.binarySearch { it.timeMs.compareTo(at) }.let { if (it < 0) -it - 1 else it }
                                    start = found.coerceIn(0, bars.lastIndex - 1); state = ReplayState(start); message = null
                                }
                            }, modifier = Modifier.testTag("replay-start-locate")) { Text("定位") }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (maxWidth >= 900.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { PlaybackControls() }
                            if (!started) {
                                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
                                StartControls()
                            }
                        } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            PlaybackControls()
                            if (!started) { VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp)); StartControls() }
                        }
                    }
                }
            }

            if (panelOpen) Surface(Modifier.widthIn(min = 260.dp, max = 310.dp).fillMaxHeight().testTag("replay-side-panel"),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本地模拟", style = MaterialTheme.typography.titleMedium)
                            Text(spec.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    message?.let {
                        Text(it, color = if (completionFinalized && it.startsWith("本次回放")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (sideTab == 0) {
                        OrderTicket(ticketSpec, replayQuote, BrokerCapabilities(hedging = false), draft,
                            onDraft = { draft = it; showDraft = true }, preview = draftPreview, busy = false,
                            onSubmit = { requestOrder(draft.side, draft.kind, if (draft.kind == OrderKind.MARKET) null else ticketSpec.parsePrice(draft.entry)) },
                            modifier = Modifier.fillMaxWidth().height(500.dp).testTag("replay-order-ticket"), skipConfirmation = input.skipConfirmation,
                            accountName = "回放模拟账户", showPriceExplanation = false, showEstimates = false,
                            onClose = { panelOpen = false }, closeLabel = "收起下单面板", closeTag = "replay-order-collapse")
                        if (state.pending.isNotEmpty()) ReplaySection("模拟挂单") {
                            state.pending.forEach { order -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (order.side == Side.BUY) "买" else "卖"} ${if (order.kind == OrderKind.LIMIT) "限价" else "止损"} ${spec.formatPrice(order.price)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { chartOrders.firstOrNull { it.id == "replay-order-${order.id}" }?.let { requestLine(LineAction.CancelOrder(it)) } }) { Text("撤单") }
                            } }
                        }
                    } else {
                        Text("回放分析", style = MaterialTheme.typography.titleLarge)
                        Text("查看本次回放或选择已保存的记录。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { playing = false; report = currentRecord() }, modifier = Modifier.fillMaxWidth().testTag("replay-current-report")) { Text("查看本次回放分析") }
                        OutlinedButton(onClick = {
                            playing = false
                            runCatching {
                                val temporaryIds = temporaryRecords.map { it.id }.toSet()
                                (records?.list().orEmpty().filterNot { it.id in temporaryIds } + temporaryRecords).sortedByDescending { it.savedAtMs }
                            }
                                .onSuccess { savedRecords = it; recordsExpanded = true }
                                .onFailure {
                                    message = "读取本地记录失败：${it.message ?: "请检查存储"}"
                                    if (temporaryRecords.isNotEmpty()) {
                                        savedRecords = temporaryRecords.sortedByDescending { it.savedAtMs }; recordsExpanded = true
                                    }
                                }
                        }, enabled = records != null || temporaryRecords.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("replay-records-picker")) { Text("选择回放记录") }


                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationRail(Modifier.width(72.dp).fillMaxHeight().testTag("replay-panel-rail"), windowInsets = WindowInsets(0, 0, 0, 0)) {
                NavigationRailItem(selected = panelOpen && sideTab == 0, onClick = { panelOpen = !panelOpen || sideTab != 0; sideTab = 0 }, icon = { Icon(AppIcons.OrderEntry, null) },
                    label = { Text("下单") }, modifier = Modifier.testTag("replay-tab-0"))
                NavigationRailItem(selected = panelOpen && sideTab == 1, onClick = { panelOpen = !panelOpen || sideTab != 1; sideTab = 1 }, icon = { Icon(AppIcons.Analytics, null) },
                    label = { Text("分析") }, modifier = Modifier.testTag("replay-tab-1"))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("replay-account-strip"), shape = MaterialTheme.shapes.small,
            color = TradingColors.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val balance = realized?.let { runCatching { checkedAdd(riskConfig.initialMicros, it) }.getOrNull() }
                val equity = if (balance != null && floating != null) runCatching { checkedAdd(balance, floating) }.getOrNull() else null
                val daily = runCatching { checkedAdd(risk.lastEquity, -risk.dayOpeningBalance) }.getOrNull()
                Metric("本交易日盈亏", daily?.let { money(it, true) } ?: "—", color = TradingColors.pnl(daily), valueTag = "replay-daily-pnl")
                Metric("模拟余额 · ${spec.currency}", balance?.let { money(it) } ?: "—")
                Metric("净值 · ${spec.currency}", equity?.let { money(it) } ?: "—")
                Metric("已实现盈亏", realized?.let { money(it, true) } ?: "—", color = TradingColors.pnl(realized), valueTag = "replay-realized")
                Metric("浮动盈亏", floating?.let { money(it, true) } ?: "—", color = TradingColors.pnl(floating), valueTag = "replay-floating")
                Metric("回撤线 · ${when (riskConfig.mode) { ReplayDrawdown.STATIC -> "静态"; ReplayDrawdown.EOD -> "日终"; ReplayDrawdown.TDD -> "浮动" }}",
                    "${money(risk.threshold)}${if (risk.failed) " · 已触及" else ""}")
                Metric("最大日亏损 · ${when (riskConfig.dailyLossMode) {
                    ReplayDailyLoss.SOFT -> "软止损"; ReplayDailyLoss.HARD -> "硬止损"; ReplayDailyLoss.NONE -> "无止损"
                }}", riskConfig.maxDailyLossMicros?.let { money(it) } ?: "未设置")
                Metric("盈利目标", "${money(riskConfig.targetMicros)}${if (risk.targetReached) " · 已达成" else ""}")
                Metric("一致性 · ${riskConfig.consistencyPercent}%", riskConfig.consistencyLimitMicros?.let { "每日 ${money(it)}${if (risk.consistencyHit) " · 已触及" else ""}" } ?: "关闭", valueTag = "replay-consistency-status")
                Metric("持仓过夜", if (riskConfig.allowOvernight) "允许" else "禁止 · ${replayCutoffText(riskConfig.overnightCutoffMinutes)} 芝加哥")
                Metric("交易统计", "成交 ${state.trades.size} 笔 · 胜 ${state.trades.count { it.pnlMicros > 0 }} · 负 ${state.trades.count { it.pnlMicros < 0 }}", valueTag = "replay-trade-count")
            }
        }
    }
    if (startCalendar) ReplayCalendarDialog(
        runCatching { chartBarDateTime(current.timeMs, sourceFrame, input.zone.zoneId).take(10) }.getOrDefault("1970-01-01"),
        onDismiss = { startCalendar = false }) { date ->
        startTime = if (sourceFrame.seconds >= Timeframe.DAY) date else "$date 00:00"
        val at = if (sourceFrame.seconds >= Timeframe.DAY) runCatching { Instant.parse(date + "T00:00:00Z").toEpochMilliseconds() }.getOrNull()
            else parseChartTime(startTime, input.zone.zoneId)
        if (at == null || at > bars.last().timeMs || at + 86_400_000L <= bars.first().timeMs) message = "该日期不在已加载的历史范围内"
        else {
            val found = bars.binarySearch { it.timeMs.compareTo(at) }.let { if (it < 0) -it - 1 else it }
            if (found !in 0 until bars.lastIndex || chartBarDateTime(bars[found].timeMs, sourceFrame, input.zone.zoneId).take(10) != date)
                message = "所选日期没有可用的回放起点"
            else { start = found; state = ReplayState(start); message = null }
        }
        startCalendar = false
    }
    if (recordsExpanded) AlertDialog(onDismissRequest = { recordsExpanded = false },
        title = { Text("选择回放记录") },
        text = { Column(Modifier.widthIn(min = 320.dp, max = 500.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (savedRecords.isEmpty()) Text("暂无已保存的回放记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (savedRecords.any { saved -> temporaryRecords.any { it.id == saved.id } })
                Text("临时记录将在关闭回放后清除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            savedRecords.forEach { saved ->
                Surface(onClick = { playing = false; report = saved; recordsExpanded = false },
                    modifier = Modifier.fillMaxWidth().testTag("replay-record-${saved.id}"), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${saved.symbol} · ${saved.trades.size} 笔", style = MaterialTheme.typography.titleSmall)
                            Text("${if (temporaryRecords.any { it.id == saved.id }) "临时 · " else ""}${saved.status} · ${Instant.fromEpochMilliseconds(saved.savedAtMs).toString().replace('T', ' ').take(16)} UTC",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { recordDeleteError = null; recordToDelete = saved },
                            modifier = Modifier.testTag("replay-record-delete-${saved.id}")) {
                            Icon(AppIcons.Delete, contentDescription = "删除回放记录", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(AppIcons.ChevronRight, null)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { recordsExpanded = false }, modifier = Modifier.testTag("replay-records-close")) { Text("关闭") } })
    recordToDelete?.let { saved -> AlertDialog(
        onDismissRequest = { recordToDelete = null; recordDeleteError = null },
        title = { Text("删除回放记录？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("将删除 ${saved.symbol} 的这条回放记录及其已平仓交易，删除后无法恢复。")
            if (saved.id == recordId && !completionFinalized) Text("这是当前回放的记录；删除后本次回放将不再自动保存，可在考核结束时选择保存。")
            recordDeleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("replay-record-delete-error")) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                if (temporaryRecords.any { it.id == saved.id }) temporaryRecords.removeAll { it.id == saved.id }
                else requireNotNull(records).delete(saved.id)
            }.onSuccess {
                if (saved.id == recordId) currentRecordDeleted = true
                savedRecords = savedRecords.filterNot { it.id == saved.id }
                recordToDelete = null; recordDeleteError = null
            }.onFailure { recordDeleteError = "删除失败：${it.message ?: "请检查存储"}" }
        }, modifier = Modifier.testTag("replay-record-delete-confirm")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { recordToDelete = null; recordDeleteError = null },
            modifier = Modifier.testTag("replay-record-delete-cancel")) { Text("取消") } },
    ) }
    report?.let { saved -> Dialog(onDismissRequest = { report = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { report = null }, modifier = Modifier.testTag("replay-report-close")) { Text("关闭") }
                }
                ReplayDashboard(saved, Modifier.weight(1f).fillMaxWidth()) { text ->
                    val updated = saved.copy(description = text)
                    runCatching {
                        val temporaryIndex = temporaryRecords.indexOfFirst { it.id == saved.id }
                        if (temporaryIndex >= 0) temporaryRecords[temporaryIndex] = updated
                        else if (saved.id != recordId || !currentRecordDeleted) records?.save(updated)
                    }.onSuccess {
                        if (saved.id == recordId) description = text
                        report = updated
                    }.onFailure { message = "保存备注失败：${it.message ?: "请检查存储"}" }
                }
            }
        }
    } }
    if (showIndicators) IndicatorSettingsDialog(indicatorController) { showIndicators = false }
    lineToConfirm?.let { action -> AlertDialog(onDismissRequest = { lineToConfirm = null },
        title = { Text(if (action is LineAction.ClosePosition) "确认平仓" else "确认撤单") },
        text = { Text(if (action is LineAction.ClosePosition) "按当前回放价格平仓？" else "撤销此模拟挂单？") },
        confirmButton = { TextButton(onClick = { lineToConfirm = null; executeLine(action) }, Modifier.testTag("replay-confirm-line")) { Text("确认") } },
        dismissButton = { TextButton(onClick = { lineToConfirm = null }) { Text("取消") } }) }
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
        text = { Text("切换品种会结束当前回放，模拟仓位和挂单将清除；已保存的平仓记录可用于分析。") },
        confirmButton = { TextButton(onClick = { finishReplay { switchTo = null; changeSymbol(symbol) } }) { Text("切换品种") } },
        dismissButton = { TextButton(onClick = { switchTo = null }) { Text("取消") } }) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("结束本次回放？") },
        text = { Text(if (temporaryRecords.isNotEmpty()) "本次模拟仓位和挂单将清除；已保存和临时回放记录仍可用于分析，临时记录将在关闭回放后清除。"
            else "本次模拟仓位和挂单将清除；已保存的平仓记录可用于分析。") }, confirmButton = {
            TextButton(onClick = { finishReplay { confirmReset = false; restartReplay() } }, modifier = Modifier.testTag("replay-confirm-reset")) { Text("重选起点") }
        }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("关闭回放？") },
        text = { Text(if (temporaryRecords.isNotEmpty()) "本次模拟仓位和挂单将清除；临时回放记录将随回放界面关闭而删除，已保存的记录仍可用于分析。"
            else "本次模拟仓位和挂单将清除；已保存的平仓记录可用于分析。") }, confirmButton = {
            TextButton(onClick = { finishReplay(onDismiss) }) { Text("关闭回放") }
        }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } })
    if (showSuccess) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth(.94f).testTag("replay-success"),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(AppIcons.Star, null, Modifier.size(18.dp), tint = Color(0xFFE6A23C))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(AppIcons.Star, null, Modifier.padding(16.dp).size(34.dp), tint = Color(0xFFE6A23C))
                    }
                    Icon(AppIcons.Star, null, Modifier.size(18.dp), tint = Color(0xFFE6A23C))
                }
                Text("恭喜达成盈利目标！", style = MaterialTheme.typography.headlineSmall)
                Text("本次回放已自动平仓并撤销挂单。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("盈利目标 ${money(riskConfig.targetMicros)} ${spec.currency}", style = MaterialTheme.typography.titleMedium,
                    color = TradingColors.Profit)
                message?.takeIf { it.contains("失败：") }?.let {
                    Text(it, Modifier.testTag("replay-success-error"), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    runCatching { requireNotNull(records).save(currentRecord("已达成")) }
                        .onSuccess {
                            currentRecordDeleted = false; completionFinalized = true; showSuccess = false
                            message = "本次回放已保存，可查看分析或重选起点。"
                        }.onFailure { message = "回放记录保存失败：${it.message ?: "请检查存储"}" }
                }, modifier = Modifier.fillMaxWidth().testTag("replay-success-save"), enabled = records != null,
                    colors = brandButtonColors()) { Text("保存本次回放") }
                OutlinedButton(onClick = {
                    val completed = currentRecord("已达成")
                    runCatching { records?.let { if (it.list().any { saved -> saved.id == recordId }) it.delete(recordId) } }.onSuccess {
                        temporaryRecords.removeAll { it.id == recordId }
                        temporaryRecords.add(completed)
                        currentRecordDeleted = true; completionFinalized = true; showSuccess = false
                        message = "本次回放仅临时保留，可查看分析或重选起点；关闭回放后清除。"
                    }
                        .onFailure { message = "回放记录删除失败：${it.message ?: "请检查存储"}" }
                }, modifier = Modifier.fillMaxWidth().testTag("replay-success-discard")) { Text("不保存本次回放") }
            }
        }
    }
    if (showFailure) AlertDialog(onDismissRequest = {}, title = { Text("回放考核失败") },
        text = { Column { Text(if (risk.dailyFailure) "账户触及最大日亏损。" else "账户触及最大总亏损。")
            message?.takeIf { it.contains("失败：") }?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                records?.save(currentRecord("失败"))
            }.onSuccess {
                restartReplay()
            }.onFailure { message = "回放记录保存失败：${it.message ?: "请检查存储"}" }
        }, modifier = Modifier.testTag("replay-failure-save"), enabled = records != null) { Text("保存记录并重新开始") } },
        dismissButton = { TextButton(onClick = {
            runCatching { records?.delete(recordId) }.onSuccess {
                restartReplay()
            }.onFailure { message = "回放记录删除失败：${it.message ?: "请检查存储"}" }
        }, modifier = Modifier.testTag("replay-failure-discard")) { Text("不保存并重新开始") } })

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
