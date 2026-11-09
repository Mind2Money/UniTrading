package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import com.unitrading.chart.dateTime
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Read-only live market analysis. The caller owns subscriptions; closing this panel releases them. */
@Composable
internal fun OrderFlowPanel(
    plugin: BrokerPlugin,
    instrument: Instrument,
    timeframe: Timeframe,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val feed = plugin.orderFlow
    val incoming = key(plugin) { feed?.collectAsState()?.value ?: OrderFlowSnapshot() }
    val contract = instrument.executionSymbol
    val snapshot = when {
        contract != null && incoming.symbol == contract -> incoming
        incoming.symbol == null -> OrderFlowSnapshot(statusMessage = incoming.statusMessage)
        else -> OrderFlowSnapshot(statusMessage = "正在切换订单流订阅…")
    }
    var tab by rememberSaveable(plugin.descriptor.id, contract) { mutableStateOf(0) }
    var ticks by rememberSaveable { mutableStateOf(1L) }
    val analysis = remember(snapshot.trades, timeframe, ticks, instrument.tickSize) {
        analyzeOrderFlow(snapshot.trades, timeframe, ticks, instrument.tickSize.coerceAtLeast(1))
    }
    val colors = TradingColors
    BoxWithConstraints(modifier.fillMaxSize()) {
    val shortPanel = maxHeight < 420.dp
    CompositionLocalProvider(LocalContentColor provides colors.Ink) {
    Column(Modifier.fillMaxSize().background(colors.Surface).testTag("order-flow-panel")) {
        PanelHeader("订单流", subtitle = contract ?: instrument.tradeSymbolStatus ?: "主力待确认") {
            StatusBadge(if (snapshot.connected) "实时" else "等待数据", if (snapshot.connected) colors.Profit else colors.Muted)
            ActionIcon(AppIcons.Close, "关闭订单流", "close-order-flow", onClick = onClose)
        }
        if (!shortPanel) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (snapshot.truncated) "滚动窗口 · 最近 ${snapshot.trades.size} 笔（上限 20,000）"
                else "本次订阅 · ${snapshot.trades.size} 笔（上限 20,000）",
                style = MaterialTheme.typography.labelSmall, color = colors.Muted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("orderflow-tabs")) {
            listOf("足迹", "分布", "Delta", "逐笔", "DOM").forEachIndexed { index, label ->
                TextButton({ tab = index }, Modifier.heightIn(min = 48.dp).testTag("flow-tab-${listOf("FOOTPRINT", "PROFILE", "DELTA", "TAPE", "DOM")[index]}")
                    .semantics { selected = tab == index },
                    colors = ButtonDefaults.textButtonColors(contentColor = if (index == tab) colors.Accent else colors.Muted)) {
                    Text(label, fontWeight = if (index == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        DividerLine()
        if (contract == null) {
            FlowEmpty("主力合约待确认", instrument.tradeSymbolStatus ?: "等待服务器解析可执行合约。", Modifier.weight(1f))
        } else if (feed == null) {
            FlowEmpty("此连接未提供订单流", "请切换至已连接的 Rithmic 合约。", Modifier.weight(1f))
        } else {
            if (!snapshot.connected) {
                Text(if (snapshot.statusMessage.isNotBlank()) snapshot.statusMessage else "订单流已断开，等待重新订阅",
                    Modifier.fillMaxWidth().background(colors.Loss.copy(alpha = .1f)).padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("orderflow-status"), style = MaterialTheme.typography.bodySmall, color = colors.Loss)
            }
            if (tab == 0 || tab == 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("每档 tick", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                    listOf(1L, 2L, 4L, 8L).forEach { value ->
                        FilterChip(ticks == value, { ticks = value }, { Text(value.toString()) },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("orderflow-ticks-$value"))
                    }
                    if (tab == 0) Text(timeframe.label, style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                }
            }
            if (tab == 4) {
                FlowDom(snapshot, instrument, Modifier.weight(1f).fillMaxWidth())
            } else if (snapshot.trades.isEmpty()) {
                FlowEmpty("等待实时逐笔成交", "从打开订单流后开始采集；不使用 K 线还原逐笔。", Modifier.weight(1f))
            } else when (tab) {
                0 -> FlowFootprint(analysis, instrument, Modifier.weight(1f).fillMaxWidth())
                1 -> FlowProfile(analysis, instrument, Modifier.weight(1f).fillMaxWidth())
                2 -> FlowDelta(analysis, Modifier.weight(1f).fillMaxWidth())
                else -> key(plugin, contract) { FlowTape(snapshot, instrument, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        DividerLine()
        val first = snapshot.trades.minOfOrNull { it.timeMs } ?: snapshot.startedAtMs
        val window = "${if (shortPanel) "${if (snapshot.truncated) "滚动窗口" else "本次订阅"} ${snapshot.trades.size}/20,000 笔 · " else ""}" +
            "${first?.let { "起点 ${dateTime(it)} UTC · " } ?: ""}无历史回补 · 数量为张 · 未知方向不计入 Delta"
        Text(window,
            Modifier.fillMaxWidth().then(if (shortPanel) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 12.dp, vertical = 5.dp).testTag("orderflow-window"),
            style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = if (shortPanel) 1 else Int.MAX_VALUE)
    }
    }
    }
}

@Composable
private fun FlowEmpty(title: String, detail: String, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { EmptyState(title, detail) }
    }
}

@Composable
private fun FlowSummary(analysis: OrderFlowAnalysis, modifier: Modifier = Modifier) {
    val colors = TradingColors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        FlowFigure("总量", analysis.totalVolume.toString(), colors.Ink)
        FlowFigure("主动买", analysis.totalBuy.toString(), colors.Profit)
        FlowFigure("主动卖", analysis.totalSell.toString(), colors.Loss)
        FlowFigure("未知", analysis.totalUnknown.toString(), colors.Muted)
        FlowFigure("Delta", signedFlow(analysis.delta), colors.pnl(analysis.delta))
    }
}

@Composable
private fun FlowFigure(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
        Text(value, style = MaterialTheme.typography.labelLarge, color = color, fontFamily = FontFamily.Monospace)
    }
}

/** A fixed cell width preserves Bid × Ask numbers. Both axes scroll independently on phones. */
@Composable
private fun FlowFootprint(analysis: OrderFlowAnalysis, instrument: Instrument, modifier: Modifier = Modifier) {
    val colors = TradingColors
    val bars = analysis.bars.takeLast(12)
    val prices = remember(bars) { bars.flatMap { it.levels.map(ProfileLevel::price) }.distinct().sortedDescending() }
    val byPrice = remember(bars) { bars.map { it.levels.associateBy(ProfileLevel::price) } }
    val pocs = remember(bars) { bars.map { it.levels.maxWithOrNull(compareBy<ProfileLevel> { level -> level.totalVolume }.thenByDescending { level -> level.price })?.price } }
    val scroll = rememberScrollState()
    LaunchedEffect(bars.lastOrNull()?.timeMs) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier.testTag("orderflow-footprint")) {
        Text("Bid 卖 × Ask 买 · 蓝底为 POC · 最近 12 根",
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            // Every strip shares one horizontal offset; only the bar cells move. Prices remain
            // in the same LazyColumn row as their cells, so vertical scrolling cannot drift.
            Row(Modifier.fillMaxWidth().height(34.dp).background(colors.Raised), verticalAlignment = Alignment.CenterVertically) {
                FlowCell("价格", Modifier.width(88.dp).testTag("orderflow-price-header"), colors.Muted)
                Row(Modifier.weight(1f).horizontalScroll(scroll).testTag("orderflow-footprint-bars")) {
                    bars.forEach { FlowCell(quoteTime(it.timeMs).take(5), Modifier.width(112.dp), colors.Muted) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("orderflow-footprint-prices")) {
                items(prices, key = { it }) { price ->
                    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        FlowCell(instrument.formatPrice(price), Modifier.width(88.dp).testTag("orderflow-footprint-price-$price"), colors.Muted)
                        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                            bars.forEachIndexed { index, _ ->
                                val level = byPrice[index][price]
                                val color = when { level == null || level.delta == 0L -> colors.Muted; level.delta > 0 -> colors.Profit; else -> colors.Loss }
                                val isPoc = price == pocs[index]
                                Box(Modifier.width(112.dp).fillMaxHeight().padding(1.dp)
                                    .background(if (isPoc) colors.Accent.copy(alpha = .22f) else color.copy(alpha = if (level == null) 0f else .1f)),
                                    contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(level?.let { "${it.sellVolume} × ${it.buyVolume}" } ?: "·",
                                            style = MaterialTheme.typography.labelSmall, color = if (isPoc) colors.Ink else color,
                                            fontFamily = FontFamily.Monospace, maxLines = 1)
                                        if (level != null && (level.unknownVolume > 0 || isPoc)) Text(
                                            listOfNotNull("POC".takeIf { isPoc }, "? ${level.unknownVolume}".takeIf { level.unknownVolume > 0 }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(34.dp).background(colors.Raised), verticalAlignment = Alignment.CenterVertically) {
                FlowCell("Delta", Modifier.width(88.dp), colors.Muted)
                Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                    bars.forEach { FlowCell(signedFlow(it.delta), Modifier.width(112.dp), colors.pnl(it.delta)) }
                }
            }
        }
    }
}

@Composable
private fun FlowProfile(analysis: OrderFlowAnalysis, instrument: Instrument, modifier: Modifier = Modifier) {
    val colors = TradingColors
    val highest = analysis.levels.maxOfOrNull { it.totalVolume }?.coerceAtLeast(1) ?: 1
    Column(modifier.testTag("orderflow-profile")) {
        FlowSummary(analysis)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("POC ${analysis.poc?.let(instrument::formatPrice) ?: "—"}", color = colors.Accent, style = MaterialTheme.typography.labelMedium)
            Text("VAH ${analysis.valueAreaHigh?.let(instrument::formatPrice) ?: "—"}", style = MaterialTheme.typography.labelMedium)
            Text("VAL ${analysis.valueAreaLow?.let(instrument::formatPrice) ?: "—"}", style = MaterialTheme.typography.labelMedium)
            Text("价值区域 70%", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
        }
        Text("价档成交量 · 主动卖 / 主动买 / 未知", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(analysis.levels.asReversed(), key = { it.price }) { level ->
                val poc = level.price == analysis.poc
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).background(if (poc) colors.Accent.copy(alpha = .12f) else Color.Transparent)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(instrument.formatPrice(level.price), Modifier.width(86.dp), style = MaterialTheme.typography.labelSmall,
                        color = if (poc) colors.Accent else colors.Ink, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Canvas(Modifier.weight(1f).height(18.dp).semantics { contentDescription = "卖 ${level.sellVolume}，买 ${level.buyVolume}，未知 ${level.unknownVolume}" }) {
                        val unit = size.width / highest.toFloat()
                        val sell = level.sellVolume * unit
                        val buy = level.buyVolume * unit
                        drawRect(colors.Loss.copy(alpha = .75f), size = Size(sell, size.height))
                        drawRect(colors.Profit.copy(alpha = .75f), Offset(sell, 0f), Size(buy, size.height))
                        drawRect(colors.Muted.copy(alpha = .65f), Offset(sell + buy, 0f), Size(level.unknownVolume * unit, size.height))
                    }
                    Text(level.totalVolume.toString(), Modifier.widthIn(min = 38.dp, max = 82.dp), style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FlowDelta(analysis: OrderFlowAnalysis, modifier: Modifier = Modifier) {
    val colors = TradingColors
    val bars = analysis.bars
    val cvd = bars.lastOrNull()?.cumulativeDelta ?: 0
    Column(modifier.testTag("orderflow-delta")) {
        FlowSummary(analysis)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Text("CVD ${signedFlow(cvd)} · 从保留窗口起点累计", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = colors.Accent, style = MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "累计 Delta $cvd；各柱为对应时间段 Delta，曲线为 CVD。" }) {
                    val low = minOf(0L, bars.minOfOrNull { it.cumulativeDelta } ?: 0).toDouble()
                    val high = maxOf(0L, bars.maxOfOrNull { it.cumulativeDelta } ?: 0).toDouble()
                    val padding = ((high - low) * .05).coerceAtLeast(1.0)
                    val lower = low - padding
                    val upper = high + padding
                    val span = (upper - lower).coerceAtLeast(1.0)
                    val zero = (size.height * .65f * (upper / span)).toFloat()
                    drawLine(colors.Border, Offset(0f, zero), Offset(size.width, zero), 1f)
                    val path = Path()
                    val width = size.width / bars.size.coerceAtLeast(1)
                    val maxDelta = bars.maxOfOrNull { abs(it.delta) }?.coerceAtLeast(1) ?: 1
                    val bottomZero = size.height * .83f
                    drawLine(colors.Border, Offset(0f, bottomZero), Offset(size.width, bottomZero), 1f)
                    bars.forEachIndexed { i, bar ->
                        val x = width * (i + .5f)
                        val y = ((upper - bar.cumulativeDelta) / span * size.height * .65f).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        val h = (bar.delta.toDouble() / maxDelta * size.height * .15).toFloat()
                        drawRect(if (bar.delta >= 0) colors.Profit else colors.Loss,
                            Offset(x - width * .32f, if (h > 0) bottomZero - h else bottomZero), Size((width * .64f).coerceAtLeast(1f), abs(h)))
                    }
                    drawPath(path, colors.Accent, style = Stroke(2.dp.toPx()))
                    if (bars.size == 1) {
                        val y = ((upper - bars[0].cumulativeDelta) / span * size.height * .65f).toFloat()
                        drawCircle(colors.Accent, 3.dp.toPx(), Offset(size.width / 2, y))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(bars.firstOrNull()?.let { quoteTime(it.timeMs) } ?: "", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                    Text("蓝线 CVD · 柱 Delta · UTC", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                    Text(bars.lastOrNull()?.let { quoteTime(it.timeMs) } ?: "", style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                }
                FlowDeltaRow("时间 UTC", "Delta", "CVD", colors.Muted)
            }
            items(bars.asReversed(), key = { it.timeMs }) { bar ->
                FlowDeltaRow(quoteTime(bar.timeMs), signedFlow(bar.delta), signedFlow(bar.cumulativeDelta), colors.pnl(bar.delta))
            }
        }
    }
}

@Composable
private fun FlowDeltaRow(time: String, delta: String, cvd: String, color: Color) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowCell(time, Modifier.weight(1f), TradingColors.Muted)
        FlowCell(delta, Modifier.weight(1f), color)
        FlowCell(cvd, Modifier.weight(1f), TradingColors.Accent)
    }
}

@Composable
private fun FlowTape(snapshot: OrderFlowSnapshot, instrument: Instrument, modifier: Modifier = Modifier) {
    val colors = TradingColors
    var minimum by rememberSaveable { mutableStateOf("1") }
    var frozen by remember { mutableStateOf<List<MarketTrade>?>(null) }
    LaunchedEffect(snapshot.connected) { if (!snapshot.connected) frozen = null }
    val threshold = minimum.toLongOrNull()
    val visible = remember(snapshot.trades, frozen, threshold) {
        (frozen ?: snapshot.trades).asReversed().filter { it.size >= (threshold ?: 1L) }.take(1000)
    }
    Column(modifier.testTag("orderflow-tape")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(minimum, { value -> if (value.length <= 12 && value.all(Char::isDigit)) minimum = value },
                Modifier.weight(1f).testTag("flow-tape-min-size"), label = { Text("最小成交量（张）") }, singleLine = true,
                isError = threshold == null || threshold < 1, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            ActionIcon(if (frozen == null) AppIcons.Pause else AppIcons.PlayArrow,
                if (frozen == null) "暂停逐笔显示" else "恢复逐笔显示", "flow-tape-pause") {
                frozen = if (frozen == null) snapshot.trades else null
            }
        }
        Text(if (frozen != null) "显示已暂停，后台继续采集 · 筛选后最近 1,000 笔" else "筛选后最近 1,000 笔 · 新成交在上方",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("orderflow-tape-state"),
            style = MaterialTheme.typography.labelSmall, color = colors.Muted)
        Row(Modifier.fillMaxWidth().background(colors.Raised).padding(horizontal = 12.dp, vertical = 8.dp)) {
            FlowCell("时间 UTC", Modifier.weight(1.2f), colors.Muted)
            FlowCell("价格", Modifier.weight(1.4f), colors.Muted)
            FlowCell("方向", Modifier.weight(.8f), colors.Muted)
            FlowCell("张", Modifier.weight(.7f), colors.Muted)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) item { EmptyState("筛选后没有成交", "调整最小成交量，或等待新的逐笔。") }
            items(visible) { trade ->
                val color = when (trade.aggressor) { Side.BUY -> colors.Profit; Side.SELL -> colors.Loss; null -> colors.Muted }
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlowCell(quoteTime(trade.timeMs), Modifier.weight(1.2f), colors.Muted)
                    FlowCell(instrument.formatPrice(trade.price), Modifier.weight(1.4f), color)
                    FlowCell(when (trade.aggressor) { Side.BUY -> "买"; Side.SELL -> "卖"; null -> "未知" }, Modifier.weight(.8f), color)
                    FlowCell(trade.size.toString(), Modifier.weight(.7f), colors.Ink)
                }
            }
        }
    }
}

@Composable
private fun FlowDom(snapshot: OrderFlowSnapshot, instrument: Instrument, modifier: Modifier = Modifier) {
    val colors = TradingColors
    val full = snapshot.depthStatus == DepthStatus.AVAILABLE
    val bids = (if (full) snapshot.bids else listOfNotNull(snapshot.bestBid)).associate { it.price to it.size }
    val asks = (if (full) snapshot.asks else listOfNotNull(snapshot.bestAsk)).associate { it.price to it.size }
    val prices = (bids.keys + asks.keys).sortedDescending()
    val maxSize = (bids.values + asks.values).maxOrNull()?.coerceAtLeast(1) ?: 1
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var centered by remember(instrument.executionSymbol, full) { mutableStateOf(false) }
    val bestPrice = snapshot.bestBid?.price?.takeIf { it in bids }
        ?: bids.keys.maxOrNull() ?: snapshot.bestAsk?.price?.takeIf { it in asks } ?: asks.keys.minOrNull()
    LaunchedEffect(instrument.executionSymbol, full, prices.isEmpty(), bestPrice != null) {
        if (!centered && bestPrice != null) {
            val index = prices.indexOf(bestPrice)
            if (index >= 0) {
                val viewport = snapshotFlow {
                    listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                }.first { it > 0 }
                val halfRows = (viewport / with(density) { 34.dp.toPx() } / 2).toInt()
                listState.scrollToItem((index - halfRows).coerceAtLeast(0))
                centered = true
            }
        }
    }
    Column(modifier.testTag("orderflow-dom")) {
        Text(if (full) "DOM · 按价格汇总的挂单量 · 只读" else "仅买一 / 卖一 · 尚无多档深度",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("orderflow-dom-mode"),
            style = MaterialTheme.typography.labelMedium, color = if (full) colors.Ink else colors.Muted)
        if (!full) Text(snapshot.statusMessage.ifBlank { "等待服务器深度；多档数据需要相应 Level 2 权限。" },
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.Muted)
        Row(Modifier.fillMaxWidth().background(colors.Raised).padding(horizontal = 12.dp, vertical = 8.dp)) {
            FlowCell("买方挂单", Modifier.weight(1f), colors.Profit)
            FlowCell("价格", Modifier.weight(1.3f), colors.Muted)
            FlowCell("卖方挂单", Modifier.weight(1f), colors.Loss)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (prices.isEmpty()) item { EmptyState("等待盘口数据", "深度为空时不补造价格或挂单量。") }
            items(prices, key = { it }) { price ->
                val bid = bids[price]
                val ask = asks[price]
                val best = price == snapshot.bestBid?.price || price == snapshot.bestAsk?.price
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).background(if (best) colors.Accent.copy(alpha = .1f) else Color.Transparent)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlowDepthSize(bid, maxSize, colors.Profit, Modifier.weight(1f), true)
                    FlowCell(instrument.formatPrice(price), Modifier.weight(1.3f), if (best) colors.Accent else colors.Ink)
                    FlowDepthSize(ask, maxSize, colors.Loss, Modifier.weight(1f), false)
                }
            }
        }
    }
}

@Composable
private fun FlowDepthSize(value: Long?, max: Long, color: Color, modifier: Modifier, alignEnd: Boolean) {
    Box(modifier.height(30.dp), contentAlignment = Alignment.Center) {
        if (value != null) Box(Modifier.fillMaxWidth((value.toDouble() / max).toFloat().coerceIn(0f, 1f)).height(22.dp)
            .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart).background(color.copy(alpha = .2f)))
        Text(value?.toString() ?: "·", style = MaterialTheme.typography.labelSmall, color = color, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun FlowCell(value: String, modifier: Modifier, color: Color) {
    Text(value, modifier.padding(horizontal = 3.dp), color = color, style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun signedFlow(value: Long) = if (value > 0) "+$value" else value.toString()
