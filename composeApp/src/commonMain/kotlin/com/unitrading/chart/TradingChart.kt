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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.ui.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Instant

private sealed interface EditableLine {
    val price: Long
    data class Protection(val position: Position, val stop: Boolean, override val price: Long) : EditableLine
    data class Pending(val order: PendingOrder) : EditableLine { override val price get() = order.request.entryPrice!! }
    data class Draft(val request: OrderRequest) : EditableLine { override val price get() = request.entryPrice!! }
}
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
) {
    var count by remember(accountKey, instrument.symbol) { mutableStateOf(80) }
    var offset by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(0) }
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
    val range = visibleRange(candles.size, count, offset)
    val visible = if (range.isEmpty()) emptyList() else candles.slice(range)
    val selectedPosition = positions.firstOrNull { it.id == selectedPositionId } ?: positions.firstOrNull()
    val extras = buildList {
        quote?.let { add(it.bid); add(it.ask) }
        if (showPositions) selectedPosition?.let { p -> add(p.entryPrice); p.stopLoss?.let(::add); p.takeProfit?.let(::add) }
        if (showOrders) orders.forEach { it.request.entryPrice?.let(::add) }
        draft?.entryPrice?.let(::add)
        if (preview?.valid == true) { preview.entryPrice?.let(::add); draft?.stopLoss?.let(::add); draft?.takeProfit?.let(::add) }
    }
    val scale = drag?.scale ?: PriceScale.from(visible, extras, top, bottom)
    val currentScale by rememberUpdatedState(scale)
    val currentPosition by rememberUpdatedState(selectedPosition)
    val currentVisible by rememberUpdatedState(visible)
    val currentProtectionCallback by rememberUpdatedState(onProtection)
    val currentShowPositions by rememberUpdatedState(showPositions)
    val currentTrades by rememberUpdatedState(trades)
    val currentOrders by rememberUpdatedState(if (showOrders && allowOrderEdit) orders else emptyList())
    val currentDraft by rememberUpdatedState(draft)
    val currentPendingCallback by rememberUpdatedState(onPendingEntry)
    val currentDraftCallback by rememberUpdatedState(onDraftEntry)
    val currentTotal by rememberUpdatedState(candles.size)
    val orderVersions = orders.map { it.id to it.version }
    val activeDraft = drag?.let { edit -> (edit.line as? EditableLine.Draft)?.request?.copy(entryPrice = edit.price) } ?: draft
    val dragEstimate = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft -> estimateDraft(line.request.copy(entryPrice = edit.price))
            is EditableLine.Pending -> estimatePending(line.order.id, edit.price)
            is EditableLine.Protection -> null
        }
    }
    val activePreview = if (drag?.line is EditableLine.Draft) dragEstimate else preview
    val labelStyle = TextStyle(color = TradingColors.Muted, fontSize = 10.sp)
    val periodMs = timeframe.minutes * 60_000L
    val step = plotWidth / visible.size.coerceAtLeast(1)

    LaunchedEffect(focusedTrade?.id, instrument.symbol, timeframe) {
        if (focusedTrade != null && focusedTrade.symbol == instrument.symbol) {
            selectedTrade = focusedTrade
            val index = candles.indexOfLast { it.timeMs <= focusedTrade.closedAtMs }
            if (index >= 0) offset = (candles.size - index - count / 2).coerceIn(0, (candles.size - count).coerceAtLeast(0))
        }
    }
    LaunchedEffect(selectedPosition?.id, selectedPosition?.version, positions.map { it.id }, orderVersions) { drag = null }

    Column(modifier.background(TradingColors.Surface)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Timeframe.entries.forEach { frame ->
                FilterChip(timeframe == frame, { onTimeframe(frame) }, label = { Text(frame.label, fontSize = 11.sp) })
            }
            Spacer(Modifier.width(8.dp))
            FilterChip(showPositions, { showPositions = !showPositions }, label = { Text("仓位", fontSize = 11.sp) })
            FilterChip(showOrders, { showOrders = !showOrders }, label = { Text("挂单", fontSize = 11.sp) })
            FilterChip(showHistory, { showHistory = !showHistory }, label = { Text("历史", fontSize = 11.sp) })
            FilterChip(showData, { showData = !showData }, label = { Text("数据表", fontSize = 11.sp) })
            TextButton({ count = (count - 20).coerceAtLeast(20) }, Modifier.semantics { contentDescription = "放大图表" }) { Text("＋") }
            TextButton({ count = (count + 20).coerceAtMost(240) }, Modifier.semantics { contentDescription = "缩小图表" }) { Text("－") }
            TextButton({ offset = (offset + count / 2).coerceAtMost((candles.size - count).coerceAtLeast(0)) }) { Text("较早", fontSize = 11.sp) }
            TextButton({ offset = (offset - count / 2).coerceAtLeast(0) }) { Text("较新", fontSize = 11.sp) }
            TextButton({ offset = 0; selectedTrade = null }) { Text("最新", fontSize = 11.sp) }
        }
        DividerLine()
        if (showData) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("模拟 UTC / OHLC（${instrument.symbol}）", fontSize = 11.sp, color = TradingColors.Muted)
            }
            LazyColumn(Modifier.weight(1f).testTag("ohlc-table")) {
                items(candles.asReversed(), key = { it.timeMs }) { c ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(dateTime(c.timeMs), fontSize = 11.sp, color = TradingColors.Muted)
                        Text("开 ${instrument.formatPrice(c.open)}    高 ${instrument.formatPrice(c.high)}\n低 ${instrument.formatPrice(c.low)}    收 ${instrument.formatPrice(c.close)}", fontSize = 12.sp)
                    }
                    DividerLine()
                }
            }
        } else if (visible.isEmpty()) {
            EmptyState("暂无行情", "等待交易商提供历史 K 线。", Modifier.weight(1f))
        } else {
            Canvas(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it }.testTag("trading-chart")
                    .semantics { contentDescription = "${instrument.name}K线，蓝色空心为上涨，铜色实心为下跌。可拖动止损止盈、Limit/Stop 草稿和挂单入场线，也可使用价格输入框。" }
                    .pointerInput(accountKey, instrument.symbol, timeframe, size) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Exit) crosshair = null
                                else if (event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
                                    val pointer = event.changes.firstOrNull()?.position
                                    if (pointer != null && drag == null) crosshair = candleIndex(pointer.x, left, plotWidth, currentVisible.size)
                                }
                            }
                        }
                    }
                    .pointerInput(accountKey, instrument.symbol, timeframe, size, showHistory) {
                        detectTapGestures(onTap = { point ->
                            val bars = currentVisible
                            if (bars.isNotEmpty()) {
                                val index = candleIndex(point.x, left, plotWidth, bars.size)
                                crosshair = index
                                if (showHistory) selectedTrade = currentTrades.firstOrNull { trade ->
                                    trade.closedAtMs in bars[index].timeMs until (bars[index].timeMs + periodMs)
                                }
                            }
                        }, onLongPress = { point -> crosshair = candleIndex(point.x, left, plotWidth, currentVisible.size) })
                    }
                    .pointerInput(accountKey, instrument.symbol, timeframe, size, selectedPosition?.id, selectedPosition?.version, orderVersions, draft) {
                        var pan = 0f
                        detectDragGestures(
                            orientationLock = null,
                            onDragStart = { down, _, _ ->
                                val point = down.position
                                pan = 0f
                                val p = currentPosition
                                val s = currentScale
                                val candidates = buildList<EditableLine> {
                                    currentDraft?.takeIf { it.kind != OrderKind.MARKET && it.entryPrice != null }?.let { add(EditableLine.Draft(it)) }
                                    currentOrders.filter { it.request.entryPrice != null }.forEach { add(EditableLine.Pending(it)) }
                                    if (p != null && currentShowPositions) {
                                        p.stopLoss?.let { add(EditableLine.Protection(p, true, it)) }
                                        p.takeProfit?.let { add(EditableLine.Protection(p, false, it)) }
                                    }
                                }
                                val hit = candidates.minByOrNull { abs(s.y(it.price) - point.y) }
                                if (hit != null && abs(s.y(hit.price) - point.y) < 22 * density.density) {
                                    drag = LineDrag(hit, s, hit.price)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val active = drag
                                if (active != null) {
                                    drag = active.copy(price = active.scale.price(change.position.y, instrument.tickSize))
                                } else {
                                    pan += amount.x
                                    val barWidth = plotWidth / currentVisible.size.coerceAtLeast(1)
                                    if (abs(pan) >= barWidth) {
                                        val bars = (pan / barWidth).roundToInt()
                                        offset = (offset + bars).coerceIn(0, (currentTotal - count).coerceAtLeast(0))
                                        pan = 0f
                                    }
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
                fun text(value: String, x: Float, y: Float, color: Color = TradingColors.Muted) {
                    val layout = textMeasurer.measure(value, labelStyle.copy(color = color))
                    drawText(layout, topLeft = Offset(x, y.coerceIn(0f, (size.height - layout.size.height).toFloat().coerceAtLeast(0f))))
                }
                fun x(time: Long): Float? {
                    if (time < visible.first().timeMs || time >= visible.last().timeMs + periodMs) return null
                    val index = visible.indexOfLast { it.timeMs <= time }
                    return left + (index + 0.5f) * step
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
                    drawRect(TradingColors.Surface.copy(alpha = 0.94f), Offset(left + labelPadding, labelTop - labelPadding),
                        Size(measured.size.width + labelPadding * 2, measured.size.height + labelPadding * 2))
                    text(label, left + labelPadding * 2, labelTop, TradingColors.Ink)
                    priceTags.add(PriceTag(price, TradingColors.Ink, TradingColors.Surface))
                }
                for (i in 0..5) {
                    val y = top + (bottom - top) * i / 5
                    drawLine(TradingColors.Grid, Offset(left, y), Offset(right, y), 1f)
                    text(instrument.formatPrice(scale.price(y, instrument.tickSize)), right + 8f, y - labelHeight / 2)
                }
                for (i in 0..4) {
                    val index = ((visible.size - 1) * i / 4)
                    val x = left + (index + .5f) * step
                    drawLine(TradingColors.Grid, Offset(x, top), Offset(x, bottom), 1f)
                    text(clockLabel(visible[index].timeMs), (x - 14f).coerceIn(left, right - 28f), bottom + 9f)
                }
                val draftEntry = activeDraft?.entryPrice ?: activePreview?.entryPrice
                if (activePreview?.valid == true && activeDraft != null && draftEntry != null) {
                    val entryY = scale.y(draftEntry)
                    listOf(activeDraft.stopLoss to TradingColors.Down, activeDraft.takeProfit to TradingColors.Up).forEach { (price, color) ->
                        if (price != null) {
                            val y = scale.y(price)
                            drawRect(color.copy(alpha = 0.07f), Offset(left + plotWidth * .62f, minOf(y, entryY)), Size(plotWidth * .38f, abs(y - entryY)))
                        }
                    }
                }
                visible.forEachIndexed { index, candle ->
                    val center = left + (index + .5f) * step
                    val color = if (candle.close >= candle.open) TradingColors.Up else TradingColors.Down
                    val width = (step * .62f).coerceIn(2f, 12f * density.density)
                    drawLine(color, Offset(center, scale.y(candle.high)), Offset(center, scale.y(candle.low)), 1.3f)
                    val y = minOf(scale.y(candle.open), scale.y(candle.close))
                    val height = maxOf(abs(scale.y(candle.open) - scale.y(candle.close)), 1.6f)
                    if (candle.close >= candle.open) drawRect(color, Offset(center - width / 2, y), Size(width, height), style = Stroke(1.4f))
                    else drawRect(color, Offset(center - width / 2, y), Size(width, height))
                }
                quote?.let {
                    val y = scale.y(it.bid)
                    drawLine(TradingColors.Accent.copy(alpha = .65f), Offset(left, y), Offset(right, y), 1f)
                    drawLine(TradingColors.Muted.copy(alpha = .35f), Offset(left, scale.y(it.ask)), Offset(right, scale.y(it.ask)), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
                    priceTags.add(PriceTag(it.bid, TradingColors.Shell, TradingColors.Accent, priority = 2))
                }
                if (showHistory) trades.forEach { trade ->
                    fun marker(time: Long, price: Long, opening: Boolean) {
                        val x = x(time) ?: return
                        val y = scale.y(price)
                        val upward = if (opening) trade.side == Side.BUY else trade.side == Side.SELL
                        val color = if (upward) TradingColors.Up else TradingColors.Down
                        val path = Path().apply {
                            moveTo(x, y + if (upward) -6 else 6)
                            lineTo(x - 5, y + if (upward) 5 else -5)
                            lineTo(x + 5, y + if (upward) 5 else -5)
                            close()
                        }
                        drawPath(path, TradingColors.Surface, style = Stroke(4f))
                        drawPath(path, color)
                    }
                    marker(trade.openedAtMs, trade.entryPrice, true)
                    marker(trade.closedAtMs, trade.exitPrice, false)
                    if (trade.id == selectedTrade?.id) {
                        val x1 = x(trade.openedAtMs)
                        val x2 = x(trade.closedAtMs)
                        if (x1 != null && x2 != null) drawLine(TradingColors.Muted, Offset(x1, scale.y(trade.entryPrice)), Offset(x2, scale.y(trade.exitPrice)), 1.5f)
                    }
                }
                if (showPositions) {
                    positions.forEach { p ->
                        val selected = p.id == selectedPosition?.id
                        priceLine(p.entryPrice, "${p.side.label()} ${formatFixed(p.lotsHundredths, 2)}  #${p.id.takeLast(5)}", TradingColors.Accent, false, if (selected) .85f else .3f)
                    }
                    selectedPosition?.let { p ->
                        val protectionDrag = drag?.takeIf { (it.line as? EditableLine.Protection)?.position?.id == p.id }
                        val sl = protectionDrag?.takeIf { (it.line as EditableLine.Protection).stop }?.price ?: p.stopLoss
                        val tp = protectionDrag?.takeIf { !(it.line as EditableLine.Protection).stop }?.price ?: p.takeProfit
                        fun label(price: Long, stop: Boolean): String {
                            val pnl = grossPnl(instrument, p.side, p.lotsHundredths, p.entryPrice, price) - p.entryFeeMicros - commission(instrument, p.lotsHundredths)
                            return "${if (stop) "SL 止损" else "TP 止盈"}  ${money(pnl, true)} USD${if (protectionDrag != null) " · 预览" else " · 拖动"}"
                        }
                        sl?.let { priceLine(it, label(it, true), TradingColors.Stop) }
                        tp?.let { priceLine(it, label(it, false), TradingColors.Target) }
                    }
                }
                if (showOrders) orders.forEach { order ->
                    val moving = drag?.takeIf { (it.line as? EditableLine.Pending)?.order?.id == order.id }
                    val price = moving?.price ?: order.request.entryPrice!!
                    priceLine(price, "挂单 ${order.request.side.label()} ${order.request.kind.name}  #${order.id.substringAfterLast(':')}${if (moving != null) " · 预览" else if (allowOrderEdit) " · 拖动入场" else ""}",
                        if (moving != null) TradingColors.Accent else TradingColors.Muted)
                }
                if (activeDraft != null && draftEntry != null && draftEntry > 0) {
                    priceLine(draftEntry, "${activeDraft.side.label()} ${activeDraft.kind.name} 草稿 · ${formatFixed(activeDraft.lotsHundredths, 2)} 手${if (activeDraft.kind != OrderKind.MARKET) " · 拖动入场" else ""}", TradingColors.Muted)
                    if (activePreview?.valid == true) {
                        activeDraft.stopLoss?.let { priceLine(it, "预览 SL · ${activePreview.stopLossPnlMicros?.let { v -> money(v, true) } ?: "—"} USD", TradingColors.Stop, alpha = .65f) }
                        activeDraft.takeProfit?.let { priceLine(it, "预览 TP · ${activePreview.takeProfitPnlMicros?.let { v -> money(v, true) } ?: "—"} USD", TradingColors.Target, alpha = .65f) }
                    }
                }
                crosshair?.let { index ->
                    visible.getOrNull(index)?.let { candle ->
                        val x = left + (index + .5f) * step
                        drawLine(TradingColors.Muted.copy(alpha = .6f), Offset(x, top), Offset(x, bottom), 1f)
                        drawCircle(TradingColors.Surface, 5f, Offset(x, scale.y(candle.close)))
                        drawCircle(TradingColors.Ink, 3f, Offset(x, scale.y(candle.close)))
                    }
                }
                val tagGap = labelHeight + labelPadding * 2 + 2.dp.toPx()
                val capacity = ((bottom - top) / tagGap).toInt().coerceAtLeast(1)
                val tags = priceTags.sortedByDescending { it.priority }.distinctBy { it.price }.take(capacity).sortedBy { scale.y(it.price) }
                val firstCenter = top + labelHeight / 2 + labelPadding
                val lastCenter = maxOf(firstCenter, bottom - labelHeight / 2 - labelPadding)
                val centers = spreadLabelCenters(tags.map { scale.y(it.price) }, firstCenter, lastCenter, tagGap)
                tags.zip(centers).forEach { (tag, center) ->
                    val leader = 4.dp.toPx()
                    drawLine(TradingColors.Muted, Offset(right, scale.y(tag.price)), Offset(right + leader, center), 1f)
                    drawRect(tag.background, Offset(right + leader, center - labelHeight / 2 - labelPadding),
                        Size((size.width - right - leader).coerceAtLeast(1f), labelHeight + labelPadding * 2))
                    text(instrument.formatPrice(tag.price), right + leader + labelPadding, center - labelHeight / 2, tag.ink)
                }
            }
            val bar = visible.getOrNull(crosshair ?: visible.lastIndex)
            if (bar != null) Text(
                if (dragEstimate != null) "入场预览 ${drag?.price?.let(instrument::formatPrice)}   SL ${dragEstimate.stopLossPnlMicros?.let { money(it, true) } ?: "—"}   TP ${dragEstimate.takeProfitPnlMicros?.let { money(it, true) } ?: "—"}   保证金 ${money(dragEstimate.marginMicros)} USD   ${dragEstimate.errors.joinToString("；")}"
                else "${dateTime(bar.timeMs)} UTC   开 ${instrument.formatPrice(bar.open)}   高 ${instrument.formatPrice(bar.high)}   低 ${instrument.formatPrice(bar.low)}   收 ${instrument.formatPrice(bar.close)}",
                Modifier.fillMaxWidth().testTag("chart-readout").horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 5.dp), color = TradingColors.Muted, fontSize = 10.sp,
            )
            selectedTrade?.let { trade ->
                Text("选中历史  #${trade.id} · ${trade.side.label()} ${formatFixed(trade.lotsHundredths, 2)} 手 · ${money(trade.netPnlMicros, true)} USD · ${trade.reason}", Modifier.padding(horizontal = 14.dp, vertical = 3.dp), fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("△ 买入 / 上涨空心", fontSize = 10.sp, color = TradingColors.Muted)
            Text("▽ 卖出 / 下跌实心", fontSize = 10.sp, color = TradingColors.Muted)
            Text("SL 止损   TP 止盈", fontSize = 10.sp, color = TradingColors.Muted)
            Text("模拟行情 · UTC", fontSize = 10.sp, color = TradingColors.Muted)
        }
    }
}

fun dateTime(timeMs: Long): String = Instant.fromEpochMilliseconds(timeMs).toString().take(16).replace('T', ' ')
