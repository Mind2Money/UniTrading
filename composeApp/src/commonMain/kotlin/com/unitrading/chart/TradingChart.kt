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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.ui.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

private sealed interface EditableLine {
    val price: Long
    data class Protection(val position: Position, val stop: Boolean, override val price: Long) : EditableLine
    data class Pending(val order: PendingOrder) : EditableLine { override val price get() = order.request.entryPrice!! }
    data class Draft(val request: OrderRequest) : EditableLine { override val price get() = request.entryPrice!! }
}
private enum class ChartDragMode { PRICE, TIME, PLOT, NONE }
private data class LineDrag(val line: EditableLine, val scale: PriceScale, val price: Long)
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
    onProtection: (Position, Long?, Long?) -> Unit,
    orders: List<PendingOrder>,
    allowOrderEdit: Boolean,
    onPendingEntry: (PendingOrder, Long) -> Unit,
    onDraftEntry: (Long) -> Unit,
    estimateDraft: (OrderRequest) -> OrderPreview,
    estimatePending: (String, Long) -> OrderPreview,
    modifier: Modifier = Modifier,
    environment: BrokerEnvironment = BrokerEnvironment.LOCAL_DEMO,
    executions: List<Execution> = emptyList(),
    connected: Boolean = true,
    onSide: (Side) -> Unit = {},
    onSymbolSearch: (() -> Unit)? = null,
    legendCollapsed: Boolean = false,
) {
    val colors = TradingColors
    var viewport by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(ChartViewport()) }
    var manualScale by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<PriceScale?>(null) }
    var previousTotal by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(candles.size) }
    var showHistory by remember { mutableStateOf(true) }
    var showPositions by remember { mutableStateOf(true) }
    var showOrders by remember { mutableStateOf(true) }
    var showData by remember { mutableStateOf(false) }
    var crosshair by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<Int?>(null) }
    var drag by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<LineDrag?>(null) }
    var size by remember { mutableStateOf(IntSize(900, 400)) }
    var selectedTrade by remember(accountKey, instrument.symbol) { mutableStateOf<ClosedTrade?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val left = with(density) { 12.dp.toPx() }
    val right = (size.width - with(density) { 84.dp.toPx() }).coerceAtLeast(left + 1)
    val top = with(density) { 18.dp.toPx() }
    val bottom = (size.height - with(density) { 30.dp.toPx() }).coerceAtLeast(top + 1)
    val plotWidth = right - left
    val range = viewport.range(candles.size)
    val visible = if (range.isEmpty()) emptyList() else candles.slice(range)
    val selectedPosition = positions.firstOrNull { it.id == selectedPositionId } ?: positions.firstOrNull()
    val extras = buildList {
        quote?.let { add(it.bid); add(it.ask) }
        if (showPositions) selectedPosition?.let { p -> add(p.entryPrice); p.stopLoss?.let(::add); p.takeProfit?.let(::add) }
        if (showOrders) orders.forEach { it.request.entryPrice?.let(::add) }
        draft?.entryPrice?.let(::add)
        if (preview?.valid == true) { preview.entryPrice?.let(::add); draft?.stopLoss?.let(::add); draft?.takeProfit?.let(::add) }
    }
    val scale = drag?.scale ?: manualScale?.copy(top = top, bottom = bottom) ?: PriceScale.from(visible, extras, top, bottom)
    val currentScale by rememberUpdatedState(scale)
    val currentPosition by rememberUpdatedState(selectedPosition)
    val currentVisible by rememberUpdatedState(visible)
    val currentProtectionCallback by rememberUpdatedState(onProtection)
    val currentShowPositions by rememberUpdatedState(showPositions)
    val currentTrades by rememberUpdatedState(trades)
    val representedProtectionIds = if (showPositions) setOfNotNull(selectedPosition?.stopLossOrderId, selectedPosition?.takeProfitOrderId) else emptySet()
    val currentOrders by rememberUpdatedState(if (showOrders && allowOrderEdit) orders.filter { it.editable } else emptyList())
    val currentPositions by rememberUpdatedState(positions)
    val currentRepresentedProtectionIds by rememberUpdatedState(representedProtectionIds)
    val currentDraft by rememberUpdatedState(draft)
    val currentPendingCallback by rememberUpdatedState(onPendingEntry)
    val currentDraftCallback by rememberUpdatedState(onDraftEntry)
    val currentTotal by rememberUpdatedState(candles.size)
    val orderVersions = orders.map { listOf(it.id, it.version, it.role, it.positionId, it.editable, it.filledLotsHundredths) }
    val activeDraft = drag?.let { edit -> (edit.line as? EditableLine.Draft)?.request?.copy(entryPrice = edit.price) } ?: draft
    val dragEstimate = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft -> estimateDraft(line.request.copy(entryPrice = edit.price))
            is EditableLine.Pending -> estimatePending(line.order.id, edit.price)
            is EditableLine.Protection -> null
        }
    }
    val activePreview = if (drag?.line is EditableLine.Draft) dragEstimate else preview
    val labelStyle = TextStyle(color = colors.Muted, fontSize = 10.sp)
    val periodMs = timeframe.minutes * 60_000L
    val step = plotWidth / viewport.count.toFloat()
    val currentViewport by rememberUpdatedState(viewport)
    val currentRange by rememberUpdatedState(range)
    fun visibleIndex(x: Float): Int = ((x - left) / (plotWidth / currentViewport.count) + currentViewport.start(currentTotal) - currentRange.first).toInt().coerceIn(0, (currentVisible.size - 1).coerceAtLeast(0))

    LaunchedEffect(focusedTrade?.id, instrument.symbol, timeframe) {
        if (focusedTrade != null && focusedTrade.symbol == instrument.symbol) {
            selectedTrade = focusedTrade
            val index = candles.indexOfLast { it.timeMs <= focusedTrade.closedAtMs }
            if (index >= 0) viewport = viewport.copy(offset = candles.size - index - viewport.count / 2).bounded(candles.size)
        }
    }
    LaunchedEffect(selectedPosition?.id, selectedPosition?.version, positions.map { it.id }, orderVersions) { drag = null }
    LaunchedEffect(candles.size) {
        if (viewport.offset != 0.0) viewport = viewport.copy(offset = viewport.offset + candles.size - previousTotal).bounded(candles.size)
        previousTotal = candles.size
    }

    var collapsed by remember(legendCollapsed) { mutableStateOf(legendCollapsed) }
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
    val toggles = listOf(
        ChartToggle("仓位", showPositions) { showPositions = !showPositions },
        ChartToggle("挂单", showOrders) { showOrders = !showOrders },
        ChartToggle("历史", showHistory) { showHistory = !showHistory },
        ChartToggle("数据表", showData) { showData = !showData },
    )
    val toolbar: @Composable (Modifier) -> Unit = { m -> ChartToolbar(instrument, timeframe, onTimeframe, toggles, onSymbolSearch, m) }

    Column(modifier.background(colors.Surface)) {
        if (showData) {
            toolbar(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
            DividerLine()
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
            toolbar(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
            DividerLine()
            EmptyState("暂无行情", "等待交易商提供历史 K 线。", Modifier.weight(1f))
        } else {
            // The toolbar, legend and navigation float over the plot instead of taking their own banner above it.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize().background(colors.Chart).onSizeChanged { size = it }.testTag("trading-chart")
                        .semantics {
                            contentDescription = "${instrument.name}K线。拖动图表平移；上下滑动右侧价格轴缩放价格，左右滑动底部时间轴缩放时间。订单线可拖动预览。"
                            stateDescription = "bars:${viewport.count};offset:${viewport.offset};low:${scale.low};high:${scale.high};auto:${manualScale == null}"
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Exit) crosshair = null
                                    else if (event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
                                        val pointer = event.changes.firstOrNull()?.position
                                        if (pointer != null && drag == null) crosshair = visibleIndex(pointer.x)
                                    }
                                }
                            }
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, showHistory) {
                            detectTapGestures(onTap = { point ->
                                val bars = currentVisible
                                if (bars.isNotEmpty() && point.x < right && point.y < bottom) {
                                    val index = visibleIndex(point.x)
                                    crosshair = index
                                    if (showHistory) selectedTrade = currentTrades.firstOrNull { trade ->
                                        trade.closedAtMs in bars[index].timeMs until (bars[index].timeMs + periodMs)
                                    }
                                }
                            }, onLongPress = { point -> crosshair = visibleIndex(point.x) })
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, selectedPosition?.id, selectedPosition?.version, orderVersions, draft) {
                            var mode = ChartDragMode.NONE
                            detectDragGestures(
                                orientationLock = null,
                                onDragStart = { down, _, _ ->
                                    val point = down.position
                                    crosshair = null
                                    mode = when {
                                        point.x >= right && point.y < bottom -> ChartDragMode.PRICE
                                        point.y >= bottom && point.x < right -> ChartDragMode.TIME
                                        point.x in left..right && point.y in top..bottom -> ChartDragMode.PLOT
                                        else -> ChartDragMode.NONE
                                    }
                                    val p = currentPosition
                                    val s = currentScale
                                    val candidates = buildList<EditableLine> {
                                        currentDraft?.takeIf { it.kind != OrderKind.MARKET && it.entryPrice != null }?.let { add(EditableLine.Draft(it)) }
                                        currentOrders.filter { it.request.entryPrice != null }.forEach { order ->
                                            if (order.role == OrderRole.ENTRY) add(EditableLine.Pending(order))
                                            else if (order.isProtection && order.id !in currentRepresentedProtectionIds) {
                                                val position = currentPositions.firstOrNull { it.id == order.positionId && it.protectionEditable }
                                                if (position != null) add(EditableLine.Protection(position, order.role == OrderRole.STOP_LOSS, order.request.entryPrice!!))
                                            }
                                        }
                                        if (p != null && p.protectionEditable && currentShowPositions) {
                                            p.stopLoss?.let { add(EditableLine.Protection(p, true, it)) }
                                            p.takeProfit?.let { add(EditableLine.Protection(p, false, it)) }
                                        }
                                    }
                                    val hit = candidates.minByOrNull { abs(s.y(it.price) - point.y) }
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
                                        ChartDragMode.NONE -> Unit
                                    }
                                },
                                onDragEnd = { _ ->
                                    drag?.takeIf { it.price != it.line.price }?.let { edit ->
                                        when (val line = edit.line) {
                                            is EditableLine.Protection -> currentProtectionCallback(line.position,
                                                if (line.stop) edit.price else line.position.stopLoss, if (line.stop) line.position.takeProfit else edit.price)
                                            is EditableLine.Pending -> currentPendingCallback(line.order, edit.price)
                                            is EditableLine.Draft -> currentDraftCallback(edit.price)
                                        }
                                    }
                                    drag = null
                                },
                                onDragCancel = { drag = null },
                            )
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> { crosshair = ((crosshair ?: visible.lastIndex) - 1).coerceAtLeast(0); true }
                                Key.DirectionRight -> { crosshair = ((crosshair ?: 0) + 1).coerceAtMost(visible.lastIndex); true }
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
                    fun priceLine(price: Long, label: String, color: Color, dashed: Boolean = true, alpha: Float = 1f) {
                        val y = scale.y(price)
                        if (y !in top..bottom) return
                        drawLine(color.copy(alpha = alpha), Offset(left, y), Offset(right, y), strokeWidth = 1.3f,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null)
                        val measured = textMeasurer.measure(label, labelStyle)
                        val labelTop = y - measured.size.height - labelPadding
                        drawRect(colors.Surface.copy(alpha = 0.94f), Offset(left + labelPadding, labelTop - labelPadding),
                            Size(measured.size.width + labelPadding * 2, measured.size.height + labelPadding * 2))
                        text(label, left + labelPadding * 2, labelTop, colors.Ink)
                        priceTags.add(PriceTag(price, colors.Ink, colors.Surface))
                    }
                    for (i in 0..5) {
                        val y = top + (bottom - top) * i / 5
                        drawLine(colors.Grid, Offset(left, y), Offset(right, y), 1f)
                        text(instrument.formatPrice(scale.price(y, instrument.tickSize)), right + 8f, y - labelHeight / 2)
                    }
                    for (i in 0..4) {
                        if (visible.isEmpty()) break
                        val index = ((visible.size - 1) * i / 4)
                        val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        drawLine(colors.Grid, Offset(x, top), Offset(x, bottom), 1f)
                        text(clockLabel(visible[index].timeMs), (x - 14f).coerceIn(left, right - 28f), bottom + 9f)
                    }
                    clipRect(left, top, right, bottom) {
                    val draftEntry = activeDraft?.entryPrice ?: activePreview?.entryPrice
                    if (activePreview?.valid == true && activeDraft != null && draftEntry != null) {
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
                        if (candle.close >= candle.open) drawRect(color, Offset(center - width / 2, y), Size(width, height), style = Stroke(1.4f))
                        else drawRect(color, Offset(center - width / 2, y), Size(width, height))
                    }
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
                    if (showPositions) {
                        positions.forEach { p ->
                            val selected = p.id == selectedPosition?.id
                            priceLine(p.entryPrice, "${p.side.label()} ${instrument.formatQuantity(p.lotsHundredths)}  #${p.id.takeLast(5)}", colors.Accent, false, if (selected) .85f else .3f)
                        }
                        selectedPosition?.let { p ->
                            val protectionDrag = drag?.takeIf { (it.line as? EditableLine.Protection)?.position?.id == p.id }
                            val sl = protectionDrag?.takeIf { (it.line as EditableLine.Protection).stop }?.price ?: p.stopLoss
                            val tp = protectionDrag?.takeIf { !(it.line as EditableLine.Protection).stop }?.price ?: p.takeProfit
                            fun label(price: Long, stop: Boolean): String {
                                val pnl = grossPnl(instrument, p.side, p.lotsHundredths, p.entryPrice, price) - p.entryFeeMicros - commission(instrument, p.lotsHundredths)
                                return "${if (stop) "SL 止损" else "TP 止盈"} ${if (!p.feesKnown) "毛" else ""} ${money(pnl, true)} USD${if (protectionDrag != null) " · 预览" else if (p.protectionEditable) " · 拖动" else " · 仅显示"}"
                            }
                            sl?.let { priceLine(it, label(it, true), colors.Stop) }
                            tp?.let { priceLine(it, label(it, false), colors.Target) }
                        }
                    }
                    if (showOrders) orders.filterNot { it.isProtection && it.id in representedProtectionIds }.forEach { order ->
                        val moving = drag?.takeIf { edit ->
                            when (val line = edit.line) {
                                is EditableLine.Pending -> line.order.id == order.id
                                is EditableLine.Protection -> order.id == if (line.stop) line.position.stopLossOrderId else line.position.takeProfitOrderId
                                else -> false
                            }
                        }
                        val price = moving?.price ?: order.request.entryPrice!!
                        val suffix = if (moving != null) " · 预览" else if (!allowOrderEdit || !order.editable) " · 仅显示" else when (order.role) {
                            OrderRole.ENTRY -> " · 拖动入场"
                            OrderRole.STOP_LOSS -> " · 拖动止损"
                            OrderRole.TAKE_PROFIT -> " · 拖动止盈"
                            OrderRole.UNRESOLVED -> " · 仅显示"
                        }
                        val color = when (order.role) {
                            OrderRole.STOP_LOSS -> colors.Stop
                            OrderRole.TAKE_PROFIT -> colors.Target
                            else -> colors.Muted
                        }
                        priceLine(price, "${order.role.label()} ${order.request.side.label()} ${order.request.kind.name}  #${order.id.substringAfterLast(':')}$suffix", color)
                    }
                    if (activeDraft != null && draftEntry != null && draftEntry > 0) {
                        priceLine(draftEntry, "${activeDraft.side.label()} ${activeDraft.kind.name} 草稿 · ${instrument.formatQuantity(activeDraft.lotsHundredths)} ${instrument.quantityUnit}${if (activeDraft.kind != OrderKind.MARKET) " · 拖动入场" else ""}", colors.Muted)
                        if (activePreview?.valid == true) {
                            activeDraft.stopLoss?.let { priceLine(it, "预览 SL · ${activePreview.stopLossPnlMicros?.let { v -> money(v, true) } ?: "—"} USD", colors.Stop, alpha = .65f) }
                            activeDraft.takeProfit?.let { priceLine(it, "预览 TP · ${activePreview.takeProfitPnlMicros?.let { v -> money(v, true) } ?: "—"} USD", colors.Target, alpha = .65f) }
                        }
                    }
                    crosshair?.let { index ->
                        visible.getOrNull(index)?.let { candle ->
                            val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                            drawLine(colors.Muted.copy(alpha = .6f), Offset(x, top), Offset(x, bottom), 1f)
                            drawCircle(colors.Surface, 5f, Offset(x, scale.y(candle.close)))
                            drawCircle(colors.Ink, 3f, Offset(x, scale.y(candle.close)))
                        }
                    }
                    }
                    drawLine(colors.Border, Offset(right, top), Offset(right, bottom))
                    drawLine(colors.Border, Offset(left, bottom), Offset(right, bottom))
                    val tagGap = labelHeight + labelPadding * 2 + 2.dp.toPx()
                    val capacity = ((bottom - top) / tagGap).toInt().coerceAtLeast(1)
                    val tags = priceTags.filter { scale.y(it.price) in top..bottom }.sortedByDescending { it.priority }.distinctBy { it.price }.take(capacity).sortedBy { scale.y(it.price) }
                    val firstCenter = top + labelHeight / 2 + labelPadding
                    val lastCenter = maxOf(firstCenter, bottom - labelHeight / 2 - labelPadding)
                    val centers = spreadLabelCenters(tags.map { scale.y(it.price) }, firstCenter, lastCenter, tagGap)
                    val tagLeft = right + 4.dp.toPx()
                    val tagWidth = (size.width - tagLeft).coerceAtLeast(1f)
                    val lineHeight = labelHeight + labelPadding * 2
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
                }
                val barIndex = crosshair ?: visible.lastIndex
                val bar = visible.getOrNull(barIndex)
                val previous = if (bar == null) null else candles.getOrNull(range.first + barIndex - 1)
                val readout = dragEstimate?.let { estimate ->
                    "入场预览 ${drag?.price?.let(instrument::formatPrice)}   SL ${estimate.stopLossPnlMicros?.let { money(it, true) } ?: "—"}   TP ${estimate.takeProfitPnlMicros?.let { money(it, true) } ?: "—"}   保证金 ${if (estimate.marginKnown) money(estimate.marginMicros) + " USD" else "未知"}   ${estimate.errors.joinToString("；")}"
                }
                Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp, end = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    toolbar(Modifier)
                    ChartLegend(instrument, timeframe, bar, previous, quote, connected, collapsed, { collapsed = !collapsed }, onSide, readout)
                }
                ChartNavigation(
                    onZoomIn = { viewport = viewport.zoom(plotWidth * .1f, plotWidth, candles.size) },
                    onZoomOut = { viewport = viewport.zoom(-plotWidth * .1f, plotWidth, candles.size) },
                    onEarlier = { viewport = viewport.copy(offset = viewport.offset + viewport.count / 2).bounded(candles.size) },
                    onLater = { viewport = viewport.copy(offset = viewport.offset - viewport.count / 2).bounded(candles.size) },
                    onReset = { viewport = ChartViewport(); manualScale = null; selectedTrade = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp),
                )
            }
            selectedPosition?.protectionNote?.let { note ->
                Text(note, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 10.sp, color = colors.Muted)
            }
            selectedTrade?.let { trade ->
                Text("选中历史  #${trade.id} · ${trade.side.label()} ${instrument.formatQuantity(trade.lotsHundredths)} ${instrument.quantityUnit} · ${money(trade.netPnlMicros, true)} USD · ${trade.reason}", Modifier.padding(horizontal = 14.dp, vertical = 3.dp), fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("△ 买入 / 上涨空心", fontSize = 10.sp, color = colors.Muted)
            Text("▽ 卖出 / 下跌实心", fontSize = 10.sp, color = colors.Muted)
            Text("右轴 ↕ 价格缩放 · 底轴 ↔ 时间缩放", fontSize = 10.sp, color = colors.Muted)
            Text(if (environment == BrokerEnvironment.LOCAL_DEMO) "模拟行情 · UTC" else "服务器行情 · UTC", fontSize = 10.sp, color = colors.Muted)
        }
    }
}

fun dateTime(timeMs: Long): String = Instant.fromEpochMilliseconds(timeMs).toString().take(16).replace('T', ' ')
