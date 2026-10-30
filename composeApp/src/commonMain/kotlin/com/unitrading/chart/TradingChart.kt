package com.unitrading.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.ui.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

private enum class ChartDragMode { PRICE, TIME, PLOT, PANE, NONE }
private data class PriceTag(val price: Long, val ink: Color, val background: Color, val priority: Int = 1)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TradingChart(
    accountKey: String,
    instrument: Instrument,
    quote: Quote?,
    candles: List<Candle>,
    timeframe: Timeframe,
    onTimeframe: (Timeframe) -> Unit,
    positions: List<Position>,
    trades: List<ClosedTrade>,
    selectedPositionId: String?,
    focusedTrade: ClosedTrade?,
    draft: OrderRequest?,
    preview: OrderPreview?,
    orders: List<PendingOrder>,
    actions: ChartOrderActions,
    modifier: Modifier = Modifier,
    environment: BrokerEnvironment = BrokerEnvironment.LOCAL_DEMO,
    executions: List<Execution> = emptyList(),
    focusedExecution: Execution? = null,
    connected: Boolean = true,
    onSide: (Side) -> Unit = {},
    instruments: List<Instrument> = emptyList(),
    onSymbol: (String) -> Unit = {},
    intervals: IntervalOptions = IntervalOptions(),
    chrome: ChartChrome = ChartChrome(),
    indicators: List<IndicatorConfig> = emptyList(),
    onIndicators: (() -> Unit)? = null,
) {
    val colors = TradingColors
    val compact = chrome.compact
    var viewport by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(ChartViewport()) }
    var manualScale by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<PriceScale?>(null) }
    var previousTotal by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(candles.size) }
    var showHistory by remember { mutableStateOf(true) }
    var showPositions by remember { mutableStateOf(true) }
    var showOrders by remember { mutableStateOf(true) }
    var showData by remember { mutableStateOf(false) }
    var crosshair by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<Int?>(null) }
    /** Pointer row of the crosshair; null after keyboard moves, which keep only the vertical line. */
    var crosshairY by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<Float?>(null) }
    var drag by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<LineDrag?>(null) }
    var size by remember { mutableStateOf(IntSize(900, 400)) }
    /** Where the floating toolbar and legend sit over the plot; line labels keep clear of it. */
    var legendArea by remember { mutableStateOf<Rect?>(null) }
    var selectedTrade by remember(accountKey, instrument.symbol) { mutableStateOf<ClosedTrade?>(null) }
    var selectedExecution by remember(accountKey, instrument.symbol) { mutableStateOf<Execution?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val left = with(density) { 12.dp.toPx() }
    val right = (size.width - with(density) { 84.dp.toPx() }).coerceAtLeast(left + 1)
    val top = with(density) { 18.dp.toPx() }
    val bottom = (size.height - with(density) { 30.dp.toPx() }).coerceAtLeast(top + 1)
    val plotWidth = right - left
    val range = viewport.range(candles.size)
    val visible = if (range.isEmpty()) emptyList() else candles.slice(range)
    // Indicators use every loaded bar; separate panes take the bottom of the drawable area, above the time axis.
    val computedIndicators = remember(candles, indicators) { computeIndicators(indicators, candles) }
    val overlays = computedIndicators.filter { it.overlay }
    val panes = computedIndicators.filterNot { it.overlay }
    val paneHeight = if (panes.isEmpty()) 0f else paneAreaHeight(bottom - top, panes.size) / panes.size
    val plotBottom = (bottom - paneHeight * panes.size).coerceAtLeast(top + 1)
    val selectedPosition = positions.firstOrNull { it.id == selectedPositionId } ?: positions.firstOrNull()
    val extras = buildList {
        quote?.let { add(it.bid); add(it.ask) }
        if (showPositions) selectedPosition?.let { p -> add(p.entryPrice); p.stopLoss?.let(::add); p.takeProfit?.let(::add) }
        if (showOrders) orders.forEach { order ->
            order.request.entryPrice?.let(::add)
            if (order.role == OrderRole.ENTRY) { order.request.stopLoss?.let(::add); order.request.takeProfit?.let(::add) }
        }
        draft?.entryPrice?.let(::add)
        if (preview?.valid == true) { preview.entryPrice?.let(::add); draft?.stopLoss?.let(::add); draft?.takeProfit?.let(::add) }
        addAll(overlayScaleValues(overlays, range))
    }
    val scale = drag?.scale ?: manualScale?.copy(top = top, bottom = plotBottom) ?: PriceScale.from(visible, extras, top, plotBottom)
    val currentScale by rememberUpdatedState(scale)
    val currentVisible by rememberUpdatedState(visible)
    val currentTrades by rememberUpdatedState(trades)
    val currentExecutions by rememberUpdatedState(executions)
    val currentActions by rememberUpdatedState(actions)
    val currentTotal by rememberUpdatedState(candles.size)
    val orderVersions = orders.map { listOf(it.id, it.version, it.role, it.positionId, it.editable, it.filledLotsHundredths, it.request, it.protectionEditable) }
    val activeDraft = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft -> line.request.copy(entryPrice = edit.price)
            is EditableLine.DraftProtection -> line.request.withProtection(line.stop, edit.price)
            else -> null
        }
    } ?: draft
    val dragEstimate = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft, is EditableLine.DraftProtection -> actions.estimateDraft(activeDraft!!)
            is EditableLine.Pending -> actions.estimatePending(line.order.id, edit.price)
            is EditableLine.PendingProtection -> line.order.request.withProtection(line.stop, edit.price)
                .let { actions.estimatePendingProtection(line.order.id, it.stopLoss, it.takeProfit) }
            is EditableLine.Protection -> null
        }
    }
    val activePreview = if (drag?.line is EditableLine.Draft || drag?.line is EditableLine.DraftProtection) dragEstimate else preview
    // A market draft enters at the side of the quote it trades against, so its lines show even while the estimate is invalid.
    val draftEntry = activeDraft?.let { it.entryPrice ?: activePreview?.entryPrice ?: quote?.let { q -> if (it.side == Side.BUY) q.ask else q.bid } }
    val lines = orderLines(instrument, positions, selectedPosition, orders, activeDraft, activePreview, draftEntry, drag,
        OrderLineOptions(showPositions, showOrders, actions.editPending, actions.editPendingProtection))
    val currentLines by rememberUpdatedState(lines)
    val readout = drag?.let { dragReadout(instrument, it, dragEstimate, lines.firstOrNull { line -> line.moving }) }
    // While a phone order panel is open the legend leaves the order lines' labels free, returning only for a drag's estimate.
    val legendShown = !chrome.ordering || readout != null
    val labelStyle = TextStyle(color = colors.Muted, fontSize = 10.sp)
    val periodMs = timeframe.minutes * 60_000L
    val step = plotWidth / viewport.count.toFloat()
    val currentViewport by rememberUpdatedState(viewport)
    val currentRange by rememberUpdatedState(range)
    fun visibleIndex(x: Float): Int = ((x - left) / (plotWidth / currentViewport.count) + currentViewport.start(currentTotal) - currentRange.first).toInt().coerceIn(0, (currentVisible.size - 1).coerceAtLeast(0))

    LaunchedEffect(focusedTrade?.id, instrument.symbol, timeframe) {
        if (focusedTrade != null && focusedTrade.symbol == instrument.symbol) {
            selectedTrade = focusedTrade
            selectedExecution = null
            val index = candles.indexOfLast { it.timeMs <= focusedTrade.closedAtMs }
            if (index >= 0) viewport = viewport.copy(offset = candles.size - index - viewport.count / 2).bounded(candles.size)
        }
    }
    LaunchedEffect(focusedExecution?.id, instrument.symbol, timeframe, candles.firstOrNull()?.timeMs) {
        if (focusedExecution != null && focusedExecution.symbol == instrument.symbol) {
            selectedExecution = focusedExecution
            selectedTrade = null
            val index = candles.indexOfLast { it.timeMs <= focusedExecution.timeMs }
            if (index >= 0) viewport = viewport.copy(offset = candles.size - index - viewport.count / 2).bounded(candles.size)
        }
    }
    LaunchedEffect(selectedPosition?.id, selectedPosition?.version, positions.map { it.id }, orderVersions) { drag = null }
    LaunchedEffect(candles.size) {
        if (viewport.offset != 0.0) viewport = viewport.copy(offset = viewport.offset + candles.size - previousTotal).bounded(candles.size)
        previousTotal = candles.size
    }

    // Folds at once on a toggle; the screen saves the choice and hands it back through [chrome] when the chart is rebuilt.
    var collapsed by remember(chrome.legendCollapsed) { mutableStateOf(chrome.legendCollapsed) }
    val remote = environment != BrokerEnvironment.LOCAL_DEMO
    var wallClockMs by remember { mutableStateOf(0L) }
    LaunchedEffect(remote, candles.isEmpty()) {
        if (remote && candles.isNotEmpty()) while (true) {
            wallClockMs = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    // Local demo bars advance on the simulated clock carried by their quotes; server bars count down on the wall clock.
    val nowMs = if (remote) wallClockMs.takeIf { it > 0 } else quote?.timeMs
    val countdown = candles.lastOrNull()?.let { last -> nowMs?.let { barCountdown(last.timeMs, periodMs, it) } }
    val layers = listOf(
        ChartToggle("仓位", showPositions) { showPositions = !showPositions },
        ChartToggle("挂单", showOrders) { showOrders = !showOrders },
        ChartToggle("历史", showHistory) { showHistory = !showHistory },
        ChartToggle("数据表", showData) { showData = !showData },
    )
    val indicatorToggle = onIndicators?.let { open -> ChartToggle(if (indicators.isEmpty()) "指标" else "指标 ${indicators.size}", indicators.any { it.visible }, open) }
    val pickable = instruments.ifEmpty { listOf(instrument) }
    // Phones pin one toolbar row above the chart in every mode; wider layouts float the full toolbar over the plot.
    val toolbar: @Composable (Modifier) -> Unit = { m ->
        if (compact) CompactChartToolbar(instrument, pickable, onSymbol, timeframe, onTimeframe, intervals, layers, indicatorToggle, chrome.toolbarEnd, m)
        else ChartToolbar(instrument, pickable, onSymbol, timeframe, onTimeframe, intervals, layers + listOfNotNull(indicatorToggle), m)
    }

    Column(modifier.background(colors.Surface)) {
        if (compact) {
            toolbar(Modifier.fillMaxWidth())
            DividerLine()
        }
        if (showData) {
            if (!compact) {
                toolbar(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
                DividerLine()
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${environment.label} · UTC / OHLC（${instrument.symbol}）", fontSize = 11.sp, color = colors.Muted)
            }
            LazyColumn(Modifier.weight(1f).testTag("ohlc-table")) {
                items(candles.asReversed(), key = { it.timeMs }) { c ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(dateTime(c.timeMs), fontSize = 11.sp, color = colors.Muted)
                        Text("开 ${instrument.formatPrice(c.open)}    高 ${instrument.formatPrice(c.high)}\n低 ${instrument.formatPrice(c.low)}    收 ${instrument.formatPrice(c.close)}" +
                            (c.volume?.let { "    量 ${formatVolume(it)}" } ?: ""), fontSize = 12.sp)
                    }
                    DividerLine()
                }
            }
        } else if (candles.isEmpty()) {
            if (!compact) {
                toolbar(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
                DividerLine()
            }
            EmptyState("暂无行情", "等待交易商提供历史 K 线。", Modifier.weight(1f))
        } else {
            // The toolbar, legend and navigation float over the plot instead of taking their own banner above it.
            val crosshairState = crosshair?.let { index -> "$index@" + (crosshairY?.takeIf { it in top..plotBottom }?.let { scale.price(it, instrument.tickSize).toString() } ?: "none") } ?: "none"
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize().background(colors.Chart).onSizeChanged { size = it }.testTag("trading-chart")
                        .semantics {
                            contentDescription = "${instrument.name}K线。拖动图表平移；上下滑动右侧价格轴缩放价格，左右滑动底部时间轴缩放时间。订单线可拖动预览。"
                            stateDescription = "bars:${viewport.count};offset:${viewport.offset};low:${scale.low};high:${scale.high};auto:${manualScale == null};panes:${panes.size};overlays:${overlays.size};cross:$crosshairState"
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Exit) crosshair = null
                                    else if ((event.type == PointerEventType.Move || event.type == PointerEventType.Enter) && event.changes.none { it.pressed }) {
                                        val pointer = event.changes.firstOrNull()?.position
                                        if (pointer != null && drag == null) { crosshair = visibleIndex(pointer.x); crosshairY = pointer.y }
                                    }
                                }
                            }
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, showHistory, panes.size) {
                            detectTapGestures(onTap = { point ->
                                val bars = currentVisible
                                if (bars.isNotEmpty() && point.x < right && point.y < bottom) {
                                    val index = visibleIndex(point.x)
                                    crosshair = index
                                    crosshairY = point.y
                                    if (showHistory) selectedTrade = currentTrades.firstOrNull { trade ->
                                        trade.closedAtMs in bars[index].timeMs until (bars[index].timeMs + periodMs)
                                    }
                                    if (showHistory) {
                                        selectedExecution = currentExecutions.filter { it.timeMs in bars[index].timeMs until (bars[index].timeMs + periodMs) }
                                            .minByOrNull { abs(currentScale.y(it.price) - point.y) }?.takeIf { abs(currentScale.y(it.price) - point.y) <= 24 * density.density }
                                        if (selectedExecution != null) selectedTrade = null
                                    }
                                }
                            }, onLongPress = { point -> crosshair = visibleIndex(point.x); crosshairY = point.y })
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, selectedPosition?.id, selectedPosition?.version, orderVersions, draft, panes.size) {
                            var mode = ChartDragMode.NONE
                            detectDragGestures(
                                orientationLock = null,
                                onDragStart = { down, _, _ ->
                                    val point = down.position
                                    crosshair = null
                                    mode = when {
                                        point.x >= right && point.y < bottom -> ChartDragMode.PRICE
                                        point.y >= bottom && point.x < right -> ChartDragMode.TIME
                                        point.x in left..right && point.y in top..plotBottom -> ChartDragMode.PLOT
                                        point.x in left..right && point.y in plotBottom..bottom -> ChartDragMode.PANE
                                        else -> ChartDragMode.NONE
                                    }
                                    val s = currentScale
                                    // The line drawn last (the draft, then pending brackets) wins a tie, as it is drawn on top.
                                    val hit = currentLines.asReversed().mapNotNull { it.edit }.minByOrNull { abs(s.y(it.price) - point.y) }
                                    if (mode == ChartDragMode.PLOT && hit != null && abs(s.y(hit.price) - point.y) < 22 * density.density) {
                                        drag = LineDrag(hit, s, hit.price)
                                    }
                                    if (mode == ChartDragMode.PRICE || mode == ChartDragMode.PLOT && drag == null) manualScale = s
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val active = drag
                                    if (active != null) {
                                        drag = active.copy(price = active.scale.price(change.position.y, instrument.tickSize))
                                    } else when (mode) {
                                        ChartDragMode.PRICE -> manualScale = (manualScale ?: currentScale).zoom(amount.y, instrument.tickSize * 4.0)
                                        ChartDragMode.TIME -> viewport = viewport.zoom(amount.x, plotWidth, currentTotal)
                                        ChartDragMode.PLOT -> {
                                            viewport = viewport.pan(amount.x, plotWidth, currentTotal)
                                            manualScale = (manualScale ?: currentScale).pan(amount.y)
                                        }
                                        ChartDragMode.PANE -> viewport = viewport.pan(amount.x, plotWidth, currentTotal)
                                        ChartDragMode.NONE -> Unit
                                    }
                                },
                                onDragEnd = { _ ->
                                    drag?.takeIf { it.price != it.line.price }?.let { edit ->
                                        val act = currentActions
                                        when (val line = edit.line) {
                                            is EditableLine.Protection -> act.onProtection(line.position,
                                                if (line.stop) edit.price else line.position.stopLoss, if (line.stop) line.position.takeProfit else edit.price)
                                            is EditableLine.Pending -> act.onPendingEntry(line.order, edit.price)
                                            is EditableLine.PendingProtection -> line.order.request.withProtection(line.stop, edit.price)
                                                .let { act.onPendingProtection(line.order, it.stopLoss, it.takeProfit) }
                                            is EditableLine.Draft -> act.onDraftEntry(edit.price)
                                            is EditableLine.DraftProtection -> act.onDraftProtection(line.stop, edit.price)
                                        }
                                    }
                                    drag = null
                                },
                                onDragCancel = { drag = null },
                            )
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> { crosshair = ((crosshair ?: visible.lastIndex) - 1).coerceAtLeast(0); crosshairY = null; true }
                                Key.DirectionRight -> { crosshair = ((crosshair ?: 0) + 1).coerceAtMost(visible.lastIndex); crosshairY = null; true }
                                Key.Escape -> { drag = null; crosshair = null; true }
                                else -> false
                            }
                        }.focusable()
                ) {
                    fun text(value: String, x: Float, y: Float, color: Color = colors.Muted) {
                        val layout = textMeasurer.measure(value, labelStyle.copy(color = color))
                        drawText(layout, topLeft = Offset(x, y.coerceIn(0f, (size.height - layout.size.height).toFloat().coerceAtLeast(0f))))
                    }
                    fun x(time: Long): Float? {
                        if (visible.isEmpty() || time < visible.first().timeMs || time >= visible.last().timeMs + periodMs) return null
                        val index = visible.indexOfLast { it.timeMs <= time }
                        return left + (range.first + index - viewport.start(candles.size) + 0.5).toFloat() * step
                    }
                    val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
                    val labelPadding = 3.dp.toPx()
                    val priceTags = mutableListOf<PriceTag>()
                    val placedLabels = listOfNotNull(legendArea?.takeIf { legendShown }).toMutableList()
                    fun priceLine(price: Long, label: String, color: Color, dashed: Boolean = true, alpha: Float = 1f) {
                        val y = scale.y(price)
                        if (y !in top..plotBottom) return
                        drawLine(color.copy(alpha = alpha), Offset(left, y), Offset(right, y), strokeWidth = 1.3f,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null)
                        val measured = textMeasurer.measure(label, labelStyle)
                        val labelTop = y - measured.size.height - labelPadding
                        val box = Size(measured.size.width + labelPadding * 2, measured.size.height + labelPadding * 2)
                        // Labels of nearby lines would print over each other, so a label moves right of any it would cover.
                        var x = left + labelPadding
                        while (true) x = placedLabels.firstOrNull { it.overlaps(Rect(Offset(x, labelTop - labelPadding), box)) }?.right?.plus(labelPadding) ?: break
                        placedLabels.add(Rect(Offset(x, labelTop - labelPadding), box))
                        drawRect(colors.Surface.copy(alpha = 0.94f), Offset(x, labelTop - labelPadding), box)
                        text(label, x + labelPadding, labelTop, colors.Ink)
                        priceTags.add(PriceTag(price, colors.Ink, colors.Surface))
                    }
                    for (i in 0..5) {
                        val y = top + (plotBottom - top) * i / 5
                        drawLine(colors.Grid, Offset(left, y), Offset(right, y), 1f)
                        text(instrument.formatPrice(scale.price(y, instrument.tickSize)), right + 8f, y - labelHeight / 2)
                    }
                    for (i in 0..4) {
                        if (visible.isEmpty()) break
                        val index = ((visible.size - 1) * i / 4)
                        val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        drawLine(colors.Grid, Offset(x, top), Offset(x, bottom), 1f)
                        text(axisTimeLabel(visible[index].timeMs, timeframe), (x - 14f).coerceIn(left, right - 28f), bottom + 9f)
                    }
                    clipRect(left, top, right, plotBottom) {
                    if (activeDraft != null && draftEntry != null) {
                        val entryY = scale.y(draftEntry)
                        listOf(activeDraft.stopLoss to colors.Down, activeDraft.takeProfit to colors.Up).forEach { (price, color) ->
                            if (price != null) {
                                val y = scale.y(price)
                                drawRect(color.copy(alpha = 0.07f), Offset(left + plotWidth * .62f, minOf(y, entryY)), Size(plotWidth * .38f, abs(y - entryY)))
                            }
                        }
                    }
                    visible.forEachIndexed { index, candle ->
                        val center = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        val color = if (candle.close >= candle.open) colors.Up else colors.Down
                        val width = (step * .62f).coerceIn(2f, 12f * density.density)
                        drawLine(color, Offset(center, scale.y(candle.high)), Offset(center, scale.y(candle.low)), 1.3f)
                        val y = minOf(scale.y(candle.open), scale.y(candle.close))
                        val height = maxOf(abs(scale.y(candle.open) - scale.y(candle.close)), 1.6f)
                        drawRect(color, Offset(center - width / 2, y), Size(width, height))
                    }
                    drawIndicatorOverlays(overlays, range, scale, colors) { index -> left + (index - viewport.start(candles.size) + .5).toFloat() * step }
                    quote?.let {
                        val y = scale.y(it.bid)
                        drawLine(colors.Accent.copy(alpha = .65f), Offset(left, y), Offset(right, y), 1f)
                        drawLine(colors.Muted.copy(alpha = .35f), Offset(left, scale.y(it.ask)), Offset(right, scale.y(it.ask)), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
                        priceTags.add(PriceTag(it.bid, colors.Shell, colors.Accent, priority = 2))
                    }
                    if (showHistory) executions.forEach { execution ->
                        val x = x(execution.timeMs)
                        if (x != null) {
                            val y = scale.y(execution.price)
                            val up = execution.side == Side.BUY
                            val marker = Path().apply {
                                moveTo(x, y + if (up) -6f else 6f)
                                lineTo(x - 5f, y + if (up) 5f else -5f)
                                lineTo(x + 5f, y + if (up) 5f else -5f)
                                close()
                            }
                            drawPath(marker, colors.Surface, style = Stroke(4f))
                            drawPath(marker, if (up) colors.Up else colors.Down)
                            if (execution.id == selectedExecution?.id) drawCircle(colors.Accent, 9.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                        }
                    }
                    if (showHistory) trades.forEach { trade ->
                        fun marker(time: Long, price: Long, opening: Boolean) {
                            val x = x(time) ?: return
                            val y = scale.y(price)
                            val upward = if (opening) trade.side == Side.BUY else trade.side == Side.SELL
                            val color = if (upward) colors.Up else colors.Down
                            val path = Path().apply {
                                moveTo(x, y + if (upward) -6 else 6)
                                lineTo(x - 5, y + if (upward) 5 else -5)
                                lineTo(x + 5, y + if (upward) 5 else -5)
                                close()
                            }
                            drawPath(path, colors.Surface, style = Stroke(4f))
                            drawPath(path, color)
                        }
                        marker(trade.openedAtMs, trade.entryPrice, true)
                        marker(trade.closedAtMs, trade.exitPrice, false)
                        if (trade.id == selectedTrade?.id) {
                            val x1 = x(trade.openedAtMs)
                            val x2 = x(trade.closedAtMs)
                            if (x1 != null && x2 != null) drawLine(colors.Muted, Offset(x1, scale.y(trade.entryPrice)), Offset(x2, scale.y(trade.exitPrice)), 1.5f)
                        }
                    }
                    lines.forEach { line ->
                        val color = when (line.tone) {
                            LineTone.POSITION -> colors.Accent
                            LineTone.STOP -> colors.Stop
                            LineTone.TARGET -> colors.Target
                            LineTone.ORDER -> colors.Muted
                        }
                        priceLine(line.price, line.label, color, line.dashed, line.alpha)
                    }
                    }
                    if (panes.isNotEmpty()) drawIndicatorPanes(panes, range, range.first + (crosshair ?: visible.lastIndex), instrument, left, right, plotBottom, paneHeight, step,
                        textMeasurer, labelStyle, colors) { index -> left + (index - viewport.start(candles.size) + .5).toFloat() * step }
                    drawLine(colors.Border, Offset(right, top), Offset(right, bottom))
                    drawLine(colors.Border, Offset(left, bottom), Offset(right, bottom))
                    val tagGap = labelHeight + labelPadding * 2 + 2.dp.toPx()
                    val capacity = ((plotBottom - top) / tagGap).toInt().coerceAtLeast(1)
                    val tags = priceTags.filter { scale.y(it.price) in top..plotBottom }.sortedByDescending { it.priority }.distinctBy { it.price }.take(capacity).sortedBy { scale.y(it.price) }
                    val firstCenter = top + labelHeight / 2 + labelPadding
                    val lastCenter = maxOf(firstCenter, plotBottom - labelHeight / 2 - labelPadding)
                    val lineHeight = labelHeight + labelPadding * 2
                    // Reserve a line above the live price for the contract name and one below for the countdown, so a
                    // position or order tag right next to the price moves aside instead of hiding them.
                    val centers = spreadLabelCenters(tags.map { scale.y(it.price) }, firstCenter, lastCenter, tagGap,
                        tags.map { if (it.priority == 2) lineHeight else 0f }, tags.map { if (it.priority == 2 && countdown != null) lineHeight else 0f })
                    val tagLeft = right + 4.dp.toPx()
                    val tagWidth = (size.width - tagLeft).coerceAtLeast(1f)
                    tags.zip(centers).forEachIndexed { index, (tag, center) ->
                        drawLine(colors.Muted, Offset(right, scale.y(tag.price)), Offset(tagLeft, center), 1f)
                        drawRect(tag.background, Offset(tagLeft, center - lineHeight / 2), Size(tagWidth, lineHeight))
                        text(instrument.formatPrice(tag.price), tagLeft + labelPadding, center - labelHeight / 2, tag.ink)
                        if (tag.priority == 2) {
                            // The live price tag also names the contract above and counts the bar down below, unless a neighbour needs the room.
                            val roomAbove = centers.getOrNull(index - 1)?.let { center - it } ?: Float.MAX_VALUE
                            val roomBelow = centers.getOrNull(index + 1)?.let { it - center } ?: Float.MAX_VALUE
                            if (roomAbove >= lineHeight * 2) {
                                drawRect(tag.background, Offset(tagLeft, center - lineHeight * 1.5f), Size(tagWidth, lineHeight))
                                text(instrument.symbol, tagLeft + labelPadding, center - lineHeight * 1.5f + labelPadding, tag.ink)
                            }
                            if (countdown != null && roomBelow >= lineHeight * 2) {
                                drawRect(tag.background, Offset(tagLeft, center + lineHeight / 2), Size(tagWidth, lineHeight))
                                text(countdown, tagLeft + labelPadding, center + lineHeight / 2 + labelPadding, tag.ink)
                            }
                        }
                    }
                    crosshair?.let { index ->
                        val candle = visible.getOrNull(index) ?: return@let
                        val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        val line = colors.Ink.copy(alpha = .55f)
                        drawLine(line, Offset(x, top), Offset(x, bottom), 1f, pathEffect = dash)
                        val timeLabel = crosshairTimeLabel(candle.timeMs, timeframe)
                        val timeWidth = textMeasurer.measure(timeLabel, labelStyle).size.width + labelPadding * 2
                        val timeLeft = (x - timeWidth / 2).coerceIn(left, maxOf(left, right - timeWidth))
                        drawRect(colors.Ink, Offset(timeLeft, bottom + 1f), Size(timeWidth, lineHeight))
                        text(timeLabel, timeLeft + labelPadding, bottom + 1f + labelPadding, colors.Surface)
                        val y = crosshairY ?: return@let
                        val value = when {
                            y in top..plotBottom -> instrument.formatPrice(scale.price(y, instrument.tickSize))
                            y in plotBottom..bottom -> paneValueLabel(panes, y, plotBottom, paneHeight, range, instrument, labelHeight, labelPadding)
                            else -> null
                        } ?: return@let
                        drawLine(line, Offset(left, y), Offset(right, y), 1f, pathEffect = dash)
                        drawRect(colors.Ink, Offset(tagLeft, y - lineHeight / 2), Size(tagWidth, lineHeight))
                        text(value, tagLeft + labelPadding, y - labelHeight / 2, colors.Surface)
                    }
                }
                val barIndex = crosshair ?: visible.lastIndex
                val bar = visible.getOrNull(barIndex)
                val previous = if (bar == null) null else candles.getOrNull(range.first + barIndex - 1)
                if (legendShown) Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp, end = 96.dp)
                    .onPlaced { legendArea = Rect(it.positionInParent(), it.size.toSize()) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!compact) toolbar(Modifier)
                    ChartLegend(instrument, timeframe, bar, previous, quote, connected, collapsed, { collapsed = !collapsed; chrome.onLegendCollapsed(collapsed) }, onSide, readout, compact = compact)
                    if (!collapsed && overlays.isNotEmpty()) IndicatorLegend(indicatorReadouts(overlays, range.first + barIndex, instrument))
                }
                val navigationBottom = 38.dp + with(density) { (paneHeight * panes.size).toDp() }
                ChartNavigation(
                    onZoomIn = { viewport = viewport.zoom(plotWidth * .1f, plotWidth, candles.size) },
                    onZoomOut = { viewport = viewport.zoom(-plotWidth * .1f, plotWidth, candles.size) },
                    onEarlier = { viewport = viewport.copy(offset = viewport.offset + viewport.count / 2).bounded(candles.size) },
                    onLater = { viewport = viewport.copy(offset = viewport.offset - viewport.count / 2).bounded(candles.size) },
                    onReset = { viewport = ChartViewport(); manualScale = null; selectedTrade = null; selectedExecution = null },
                    // On phones the small control sits bottom-left, away from the newest bars at the right edge.
                    modifier = if (compact) Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = navigationBottom)
                        else Modifier.align(Alignment.BottomCenter).padding(bottom = navigationBottom),
                    compact = compact,
                )
            }
            selectedPosition?.protectionNote?.let { note ->
                Text(note, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 10.sp, color = colors.Muted)
            }
            selectedTrade?.let { trade ->
                Text("选中历史  #${trade.id} · ${if (trade.directionKnown) trade.side.label() else "买卖配对"} ${instrument.formatQuantity(trade.lotsHundredths)} ${instrument.quantityUnit} · ${trade.netPnlMicros?.let { money(it, true) } ?: "毛 " + (trade.grossPnlMicros?.let { money(it, true) } ?: "—") + "（费用未知）"} USD · ${trade.reason}", Modifier.padding(horizontal = 14.dp, vertical = 3.dp), fontSize = 10.sp)
            }
            selectedExecution?.let { fill ->
                Text("选中成交 #${fill.id} · ${dateTime(fill.timeMs)} UTC · ${fill.side.label()} ${instrument.formatQuantity(fill.lotsHundredths)} ${instrument.quantityUnit} · ${instrument.formatPrice(fill.price)}",
                    Modifier.padding(horizontal = 14.dp, vertical = 3.dp).testTag("selected-execution"), fontSize = 10.sp)
            }
        }
        // Gesture and marker hints; phones drop the row to give the height to the chart (the plot's description keeps the gestures).
        if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("△ 买入 / 上涨", fontSize = 10.sp, color = colors.Muted)
            Text("▽ 卖出 / 下跌", fontSize = 10.sp, color = colors.Muted)
            Text("右轴 ↕ 价格缩放 · 底轴 ↔ 时间缩放", fontSize = 10.sp, color = colors.Muted)
            Text(if (environment == BrokerEnvironment.LOCAL_DEMO) "模拟行情 · UTC" else "服务器行情 · UTC", fontSize = 10.sp, color = colors.Muted)
        }
    }
}

/**
 * How [TradingChart] arranges its controls. [legendCollapsed] folds the legend with its indicator list, and
 * [onLegendCollapsed] receives each toggle so the screen can remember it. [compact] (phones) pins one toolbar row above
 * the plot, closed by [toolbarEnd], instead of the floating toolbar, and the legend stays hidden while [ordering] (the
 * order panel is open). Grouped in one parameter because at 32 parameters D8 emitted a TradingChart that ART's verifier
 * rejected (VerifyError on Android); [ChartOrderActions] groups the order-line callbacks for the same reason.
 */
class ChartChrome(
    val compact: Boolean = false,
    val legendCollapsed: Boolean = false,
    val onLegendCollapsed: (Boolean) -> Unit = {},
    val ordering: Boolean = false,
    val toolbarEnd: @Composable () -> Unit = {},
)

fun dateTime(timeMs: Long): String = Instant.fromEpochMilliseconds(timeMs).toString().take(16).replace('T', ' ')

/** Crosshair time tag: date and minute for intraday bars, the date alone for daily and longer bars; UTC like the axis. */
fun crosshairTimeLabel(timeMs: Long, timeframe: Timeframe): String =
    if (timeframe.minutes >= 1440) dateTime(timeMs).take(10) else dateTime(timeMs)
