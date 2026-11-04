package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.unitrading.app.MarketWatchPreferences
import com.unitrading.app.MemorySnapshotStore
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@Composable
internal fun MarketWatch(
    plugin: BrokerPlugin, market: MarketSnapshot, account: AccountSnapshot,
    selected: String, onSymbol: (String) -> Unit, modifier: Modifier = Modifier,
    preferences: MarketWatchPreferences = remember { MarketWatchPreferences(MemorySnapshotStore()) },
) {
    val colors = TradingColors
    val catalog by plugin.catalog.collectAsState()
    val lists by preferences.lists.collectAsState()
    val saveError by preferences.error.collectAsState()
    val broker = plugin.descriptor.id
    var adding by remember(plugin) { mutableStateOf(false) }
    var editing by remember(plugin) { mutableStateOf(false) }
    var query by remember(plugin) { mutableStateOf("") }
    var category by remember(plugin) { mutableStateOf<String?>(null) }
    var searching by remember(plugin) { mutableStateOf(false) }
    var searchError by remember(plugin) { mutableStateOf<String?>(null) }
    var retry by remember(plugin) { mutableIntStateOf(0) }
    val remote = plugin.descriptor.environment != BrokerEnvironment.LOCAL_DEMO
    val symbols = lists[broker].orEmpty()
    val activity = remember(account) { marketActivities(account) }
    LaunchedEffect(preferences, broker, catalog.instruments.isEmpty()) {
        preferences.initialize(broker, catalog.instruments, selected)
    }
    LaunchedEffect(plugin, adding, query, market.connected, retry) {
        searching = false
        searchError = null
        if (adding && remote && query.isNotBlank() && market.connected) {
            searching = true
            try {
                delay(350)
                withTimeout(20_000) { plugin.searchInstruments(query) }
            } catch (_: TimeoutCancellationException) { searchError = "搜索超时，请重试" }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { searchError = "搜索失败，请检查连接与授权" }
            finally { searching = false }
        }
    }
    fun update(value: List<String>) = preferences.update(broker, value)
    fun group(instrument: Instrument) = if (instrument.isContinuous) "连续合约" else instrument.category
    val results = marketSearchResults(catalog.instruments, query)
        .filter { query.isNotBlank() || category == null || group(it) == category }
    Column(modifier.background(colors.Surface).testTag("market-watch")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (adding) WatchAction("‹", "返回行情", "market-back") { adding = false; query = ""; category = null }
            Text(if (adding) "添加交易品种" else if (editing) "编辑自选" else "行情", Modifier.weight(1f).padding(start = 8.dp),
                color = colors.Ink, fontSize = if (adding) 14.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (!adding) {
                if (editing) WatchAction("✓", "完成编辑", "market-edit-done") { editing = false }
                else {
                    WatchAction("+", "添加交易品种", "market-add") { adding = true }
                    WatchAction("✎", "编辑自选", "market-edit") { editing = true }
                }
            }
        }
        if (adding) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", color = colors.Muted, fontSize = 20.sp)
                BasicTextField(query, { query = it.take(32) }, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("symbol-search"),
                    singleLine = true, textStyle = TextStyle(color = colors.Ink, fontSize = 13.sp), cursorBrush = SolidColor(colors.Accent),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索：ES / @ES / ES1!", color = colors.Muted, fontSize = 12.sp); inner() } })
                if (query.isNotEmpty()) WatchAction("×", "清空搜索", "market-clear-search") { query = "" }
            }
            HorizontalDivider(color = colors.Border, thickness = .5.dp)
            if (query.isBlank()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⌂ 全部", Modifier.clickable { category = null }.padding(vertical = 8.dp), color = colors.Accent, fontSize = 12.sp)
                    category?.let { Text("  ›  $it", color = colors.Muted, fontSize = 12.sp) }
                }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("market-search-progress"))
            if (!market.connected && remote) Text("连接已断开 · 仅显示已加载品种", Modifier.padding(12.dp), color = colors.Muted, fontSize = 11.sp)
            searchError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = colors.Loss, fontSize = 11.sp)
                    TextButton({ retry++ }) { Text("重试") }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("market-symbol-browser")) {
                if (query.isBlank() && category == null) {
                    val groups = (listOf("连续合约") + catalog.instruments.map(::group)).distinct()
                    items(groups) { name ->
                        val members = catalog.instruments.filter { group(it) == name }
                        Row(Modifier.fillMaxWidth().clickable { category = name }.padding(horizontal = 14.dp, vertical = 14.dp)
                            .testTag("market-category-$name"), verticalAlignment = Alignment.CenterVertically) {
                            Text("▰", color = colors.Target, fontSize = 19.sp)
                            Text(name, Modifier.weight(1f).padding(horizontal = 10.dp), color = colors.Ink, fontSize = 13.sp)
                            Text("${members.count { it.symbol in symbols }}/${members.size}  ›", color = colors.Muted, fontSize = 11.sp)
                        }
                    }
                } else {
                    if (results.isEmpty()) item {
                        Text(if (searching) "正在查找合约…" else "未找到品种，输入代码搜索服务器。",
                            Modifier.padding(16.dp), color = colors.Muted, fontSize = 12.sp)
                    }
                    items(results, key = { it.symbol }) { instrument ->
                        val included = instrument.symbol in symbols
                        Row(Modifier.fillMaxWidth().testTag("search-${instrument.symbol}").padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(instrument.symbol, color = colors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(instrument.name, color = colors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (instrument.isContinuous) Text(watchSubtitle(instrument), color = colors.Muted, fontSize = 10.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            WatchAction(if (included) "✓" else "⊕", if (included) "已添加 ${instrument.symbol}" else "添加 ${instrument.symbol}",
                                "market-add-${instrument.symbol}") { if (!included) update(symbols + instrument.symbol) }
                        }
                    }
                }
            }
        } else {
            if (editing) Text("箭头调整顺序，减号移出自选", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = colors.Muted, fontSize = 10.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("market-quotes")) {
                if (symbols.isEmpty()) item {
                    Column(Modifier.padding(14.dp)) {
                        Text("暂无自选品种", color = colors.Ink, fontSize = 13.sp)
                        TextButton({ adding = true }) { Text("＋ 添加交易品种") }
                    }
                }
                items(symbols, key = { it }) { symbol ->
                    val instrument = catalog.instruments.firstOrNull { it.symbol == symbol }
                    if (editing) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(symbol, Modifier.weight(1f), color = colors.Ink, fontSize = 13.sp, maxLines = 1)
                            val index = symbols.indexOf(symbol)
                            WatchAction("↑", "上移 $symbol", "market-up-$symbol", index > 0) {
                                update(symbols.toMutableList().apply { removeAt(index); add(index - 1, symbol) })
                            }
                            WatchAction("↓", "下移 $symbol", "market-down-$symbol", index < symbols.lastIndex) {
                                update(symbols.toMutableList().apply { removeAt(index); add(index + 1, symbol) })
                            }
                            WatchAction("−", "移除 $symbol", "market-remove-$symbol") { update(symbols - symbol) }
                        }
                    } else if (instrument != null) {
                        // An unconfirmed continuous row keeps its last confirmed contract's prices; orders still wait.
                        key(plugin, instrument.priceSymbol, account.accountId) {
                            MarketQuoteRow(instrument, market.quotes[instrument.priceSymbol],
                                activity[instrument.priceSymbol] ?: MarketActivity(), symbol == selected,
                                connected = market.connected, onClick = { onSymbol(symbol) })
                        }
                    } else Text("$symbol · 等待合约加载", Modifier.padding(12.dp), color = colors.Muted, fontSize = 12.sp)
                }
            }
        }
        saveError?.let { Text(it, Modifier.padding(8.dp), color = colors.Loss, fontSize = 11.sp) }
    }
}

/** Continuous rows name the dated contract behind their prices and mark it until the volume check confirms it. */
internal fun watchSubtitle(instrument: Instrument): String {
    if (!instrument.isContinuous) return instrument.name
    val contract = instrument.executionSymbol ?: instrument.quoteSymbol ?: return "连续 · ${instrument.executionLabel}"
    return "连续 · $contract" + when {
        instrument.executionSymbol != null -> ""
        instrument.tradeSymbolStatus?.startsWith("正在核实主力") == true -> " · 核实中"
        else -> " · 待确认"
    }
}

@Composable
private fun WatchAction(glyph: String, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(36.dp).testTag(tag).semantics { contentDescription = label }, enabled = enabled) {
        Text(glyph, color = if (enabled) TradingColors.Accent else TradingColors.Muted.copy(alpha = .4f), fontSize = 23.sp)
    }
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
    var previous by remember(instrument.priceSymbol, connected) { mutableStateOf<Quote?>(null) }
    var lastBidDirection by remember(instrument.priceSymbol, connected) { mutableIntStateOf(0) }
    var lastAskDirection by remember(instrument.priceSymbol, connected) { mutableIntStateOf(0) }
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
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.15f
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
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (markers.isNotEmpty()) FlowRow(Modifier.padding(start = 10.dp).testTag("market-markers-${instrument.symbol}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                markers.forEach { Text("${it.label} ${it.count}", color = it.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            }
            val symbol: @Composable (Modifier) -> Unit = { m -> Column(m, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(instrument.symbol, color = colors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(watchSubtitle(instrument), color = colors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val quotes: @Composable (Modifier) -> Unit = { m -> Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuotePrice("Bid 卖出", quote?.let { instrument.formatPrice(it.bid) }, quoteColor(bidDirection), Modifier.weight(1f), stacked)
                QuotePrice("Ask 买入", quote?.let { instrument.formatPrice(it.ask) }, quoteColor(askDirection), Modifier.weight(1f), stacked)
            } }
            if (stacked) {
                symbol(Modifier.fillMaxWidth())
                quotes(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                symbol(Modifier.weight(.34f))
                quotes(Modifier.weight(.66f))
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
            val baseSize = if (stacked) 23f else 22f
            val size = minOf(baseSize, maxWidth.value / (text.length * .64f * LocalDensity.current.fontScale)).coerceAtLeast(10f)
            Text(buildAnnotatedString {
                val split = if (price != null && '.' in text) (text.length - 2).coerceAtLeast(text.indexOf('.') + 1) else text.length
                withStyle(SpanStyle(fontSize = (size * .8f).sp)) { append(text.take(split)) }
                withStyle(SpanStyle(fontSize = size.sp)) { append(text.drop(split)) }
            }, Modifier.fillMaxWidth().semantics { contentDescription = "$label ${price ?: "暂无报价"}" },
                color = color, fontSize = size.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, softWrap = false)
        }
    }
}
