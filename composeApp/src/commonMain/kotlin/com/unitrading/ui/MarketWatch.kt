package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MarketWatch(
    plugin: BrokerPlugin, market: MarketSnapshot, account: AccountSnapshot,
    selected: String, onSymbol: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    val catalog by plugin.catalog.collectAsState()
    var query by remember(plugin) { mutableStateOf("") }
    var showLegend by remember { mutableStateOf(false) }
    var searching by remember(plugin) { mutableStateOf(false) }
    var searchError by remember(plugin) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val activity = remember(account) { marketActivities(account) }
    val filtered = catalog.instruments.filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
    Column(modifier.background(colors.Surface).testTag("market-watch")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("市场报价", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TextButton({ showLegend = true }, Modifier.testTag("market-marker-help")) { Text("标记说明", fontSize = 11.sp) }
        }
        OutlinedTextField(query, { query = it; searchError = null },
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("symbol-search"),
            placeholder = { Text("搜索品种 / 合约", fontSize = 12.sp) }, singleLine = true)
        if (plugin.descriptor.environment != BrokerEnvironment.LOCAL_DEMO) {
            TextButton({
                searching = true
                scope.launch {
                    try { plugin.searchInstruments(query); searchError = null }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { searchError = "合约搜索失败，请检查连接与授权" }
                    finally { searching = false }
                }
            }, Modifier.padding(horizontal = 8.dp), enabled = query.isNotBlank() && market.connected && !searching) {
                Text(if (searching) "查询中…" else "＋ 查询服务器合约", fontSize = 11.sp)
            }
            searchError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = colors.Loss, fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} 个品种 · UTC", Modifier.weight(1f), color = colors.Muted, fontSize = 10.sp)
            Text(if (market.connected) "实时买卖报价" else "连接已断开", color = if (market.connected) colors.Muted else colors.Loss, fontSize = 10.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("market-quotes")) {
            if (filtered.isEmpty()) item { EmptyState("未找到品种", "更换关键词或查询服务器合约。") }
            items(filtered, key = { it.symbol }) { instrument ->
                key(plugin, instrument.executionSymbol, account.accountId) {
                    MarketQuoteRow(instrument, market.quotes[instrument.executionSymbol],
                        activity[instrument.executionSymbol] ?: MarketActivity(), instrument.symbol == selected,
                        connected = market.connected, onClick = { onSymbol(instrument.symbol) })
                }
            }
        }
        Text(if (plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO) "全部为本地生成的模拟行情" else "行情与标的由 Tradovate 提供",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.Muted, fontSize = 10.sp)
    }
    if (showLegend) AlertDialog(onDismissRequest = { showLegend = false }, title = { Text("行情标记") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("每行左上角显示当前账户在该合约上的状态，数字为笔数。")
            Text("多 / 空：多头、空头持仓\n挂：入场挂单\nSL / TP：独立止损、止盈单\n待：订单关联待确认")
            Text("多种状态可同时出现。附在入场请求或持仓上的保护价格不重复计为独立挂单。")
            Text("报价颜色表示该侧报价相对上一笔的升降；点差以最小跳动（tick）计。时间为最近报价的 UTC 时间。")
        } }, confirmButton = { TextButton({ showLegend = false }) { Text("知道了") } })
}

private data class ActivityMarker(val label: String, val count: Int, val color: Color)

@Composable
internal fun MarketQuoteRow(
    instrument: Instrument, quote: Quote?, activity: MarketActivity, selected: Boolean,
    connected: Boolean = true, onClick: () -> Unit,
) {
    val colors = TradingColors
    val markers = listOf(
        ActivityMarker("多", activity.longPositions, colors.Accent),
        ActivityMarker("空", activity.shortPositions, colors.Loss),
        ActivityMarker("挂", activity.entries, colors.Target),
        ActivityMarker("SL", activity.stops, colors.Stop),
        ActivityMarker("TP", activity.targets, colors.Target),
        ActivityMarker("待", activity.unresolved, colors.Muted),
    ).filter { it.count > 0 }
    var previous by remember(instrument.executionSymbol, connected) { mutableStateOf<Quote?>(null) }
    var lastBidDirection by remember(instrument.executionSymbol, connected) { mutableIntStateOf(0) }
    var lastAskDirection by remember(instrument.executionSymbol, connected) { mutableIntStateOf(0) }
    val comparable = previous?.takeIf { quote != null && quote.timeMs >= it.timeMs }
    val bidDirection = if (quote == null) 0 else comparable?.let { quote.bid.compareTo(it.bid) }?.takeIf { it != 0 } ?: lastBidDirection
    val askDirection = if (quote == null) 0 else comparable?.let { quote.ask.compareTo(it.ask) }?.takeIf { it != 0 } ?: lastAskDirection
    SideEffect { previous = quote; lastBidDirection = bidDirection; lastAskDirection = askDirection }
    fun quoteColor(direction: Int) = when {
        !connected || quote == null -> colors.Muted
        direction > 0 -> colors.Accent
        direction < 0 -> colors.Loss
        else -> colors.Ink
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("symbol-${instrument.symbol}")
        .background(if (selected) colors.Accent.copy(alpha = .06f) else colors.Surface)
        .clickable(onClickLabel = "打开 ${instrument.symbol} 图表", onClick = onClick)
        .semantics { stateDescription = activity.description; this.selected = selected }) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.15f
        if (markers.isNotEmpty()) Canvas(Modifier.align(Alignment.TopStart).size(22.dp).testTag("market-corner-${instrument.symbol}")) {
            // Each active status gets a slice of the corner flag, in the same order as the labels.
            markers.forEachIndexed { index, marker ->
                val start = index.toFloat() / markers.size
                val end = (index + 1f) / markers.size
                drawPath(Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width * (1f - start), size.height * start)
                    lineTo(size.width * (1f - end), size.height * end)
                    close()
                }, marker.color)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (markers.isNotEmpty()) FlowRow(Modifier.padding(start = 10.dp).testTag("market-markers-${instrument.symbol}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                markers.forEach { Text("${it.label} ${it.count}", color = it.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            }
            val symbol: @Composable (Modifier) -> Unit = { m -> Column(m, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(instrument.symbol, color = colors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (instrument.isContinuous) "连续 · ${instrument.executionSymbol ?: "主力待确认"}" else instrument.name,
                    color = colors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val quotes: @Composable (Modifier) -> Unit = { m -> Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuotePrice("Bid 卖出", quote?.let { instrument.formatPrice(it.bid) }, quoteColor(bidDirection), Modifier.weight(1f), stacked)
                QuotePrice("Ask 买入", quote?.let { instrument.formatPrice(it.ask) }, quoteColor(askDirection), Modifier.weight(1f), stacked)
            } }
            if (stacked) {
                symbol(Modifier.fillMaxWidth())
                quotes(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                symbol(Modifier.weight(.31f))
                quotes(Modifier.weight(.69f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(quote?.let { quoteTime(it.timeMs) } ?: "等待报价", fontSize = 10.sp, color = colors.Muted)
                Text("点差 ${quoteSpread(quote, instrument)} tick", fontSize = 10.sp, color = colors.Muted)
            }
        }
    }
    HorizontalDivider(color = colors.Border.copy(alpha = .24f), thickness = .5.dp)
}

@Composable
private fun QuotePrice(label: String, price: String?, color: Color, modifier: Modifier, stacked: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TradingColors.Muted, fontSize = 9.sp, maxLines = 1)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val text = price ?: "—"
            // Reserve the full number even for long index prices and increased system font size.
            val baseSize = if (stacked) 24f else 25f
            val size = minOf(baseSize, maxWidth.value / (text.length * .64f * LocalDensity.current.fontScale)).coerceAtLeast(10f)
            Text(buildAnnotatedString {
                val split = if (price != null && '.' in text) (text.length - 2).coerceAtLeast(text.indexOf('.') + 1) else text.length
                withStyle(SpanStyle(fontSize = (size * .8f).sp)) { append(text.take(split)) }
                withStyle(SpanStyle(fontSize = size.sp)) { append(text.drop(split)) }
            }, Modifier.fillMaxWidth().semantics { contentDescription = "$label ${price ?: "暂无报价"}" },
                color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                maxLines = 1, softWrap = false)
        }
    }
}
