package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.broker.*
import com.unitrading.app.MarketWatchPreferences
import com.unitrading.app.MemorySnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@Composable
internal fun MarketWatch(
    plugin: BrokerPlugin, market: MarketSnapshot, account: AccountSnapshot,
    selected: String, onSymbol: (String) -> Unit, modifier: Modifier = Modifier,
    preferences: MarketWatchPreferences = remember { MarketWatchPreferences(MemorySnapshotStore()) },
    subtitle: String? = null,
    panel: Boolean = false,
    barActions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TradingColors
    val type = MaterialTheme.typography
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
    val quotes = rememberLazyListState()
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
    // A category steps back to the category list; everything else leaves the add page.
    fun stepBack() { if (category != null && query.isBlank()) category = null else { adding = false; query = ""; category = null } }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = adding || editing,
        onBackCompleted = { if (adding) stepBack() else editing = false })
    Column(modifier.background(colors.Surface).testTag("market-watch")) {
        if (adding) PageTopBar(if (query.isBlank()) category ?: "添加交易品种" else "添加交易品种", onBack = ::stepBack, backTag = "market-back", panel = panel)
        else PageTopBar(if (editing) "编辑自选" else "行情", subtitle.takeUnless { editing }, panel = panel, actions = {
            barActions()
            if (editing) ActionIcon(AppIcons.Check, "完成编辑", "market-edit-done") { editing = false }
            else {
                ActionIcon(AppIcons.Add, "添加交易品种", "market-add") { adding = true }
                ActionIcon(AppIcons.Edit, "编辑自选", "market-edit") { editing = true }
            }
        })
        if (adding) {
            OutlinedTextField(query, { query = it.take(32) }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("symbol-search"),
                singleLine = true, placeholder = { Text("搜索：ES / @ES / ES1!", maxLines = 1) }, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) ActionIcon(AppIcons.Close, "清空搜索", "market-clear-search") { query = "" } })
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("market-search-progress"))
            if (!market.connected && remote) Text("连接已断开 · 仅显示已加载品种", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.Muted, style = type.bodySmall)
            searchError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = colors.Loss, style = type.bodySmall)
                    TextButton({ retry++ }) { Text("重试") }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("market-symbol-browser")) {
                if (query.isBlank() && category == null) {
                    val groups = (listOf("连续合约") + catalog.instruments.map(::group)).distinct()
                    items(groups) { name ->
                        val members = catalog.instruments.filter { group(it) == name }
                        ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${members.count { it.symbol in symbols }}/${members.size}", style = type.labelMedium)
                                    Icon(AppIcons.ChevronRight, contentDescription = null)
                                }
                            },
                            modifier = Modifier.clickable { category = name }.testTag("market-category-$name"),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                } else {
                    if (results.isEmpty()) item {
                        EmptyState(if (searching) "正在查找合约…" else "未找到品种", "输入代码搜索服务器。", icon = AppIcons.Search)
                    }
                    items(results, key = { it.symbol }) { instrument ->
                        val included = instrument.symbol in symbols
                        ListItem(headlineContent = { Text(instrument.symbol, style = type.titleSmall) },
                            supportingContent = {
                                Column {
                                    Text(instrument.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (instrument.isContinuous) Text(watchSubtitle(instrument), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingContent = {
                                ActionIcon(if (included) AppIcons.CheckCircle else AppIcons.AddCircle, if (included) "已添加 ${instrument.symbol}" else "添加 ${instrument.symbol}",
                                    "market-add-${instrument.symbol}", tint = colors.Accent) { if (!included) update(symbols + instrument.symbol) }
                            },
                            modifier = Modifier.testTag("search-${instrument.symbol}"), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        } else if (editing && symbols.isNotEmpty()) {
            WatchListEditor(symbols, { update(it) }, Modifier.weight(1f).fillMaxWidth().testTag("market-quotes"), quotes.firstVisibleItemIndex)
        } else {
            if (!editing && symbols.isNotEmpty()) QuoteListHeader()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("market-quotes"), quotes) {
                if (symbols.isEmpty()) item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState("暂无自选品种", "添加品种后在这里查看实时买卖价。", icon = AppIcons.TrendingUp)
                        TextButton({ adding = true }) { Text("添加交易品种") }
                    }
                }
                items(symbols, key = { it }) { symbol ->
                    val instrument = catalog.instruments.firstOrNull { it.symbol == symbol }
                    if (instrument != null) {
                        // An unconfirmed continuous row keeps its last confirmed contract's prices; orders still wait.
                        key(plugin, instrument.priceSymbol, account.accountId) {
                            MarketQuoteRow(instrument, market.quotes[instrument.priceSymbol],
                                activity[instrument.priceSymbol] ?: MarketActivity(), symbol == selected,
                                connected = market.connected, onClick = { onSymbol(symbol) })
                        }
                    } else Text("$symbol · 等待合约加载", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = colors.Muted, style = type.bodySmall)
                }
            }
        }
        saveError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.Loss, style = type.bodySmall) }
    }
}

/** Column captions, once for the whole list. */
@Composable
private fun QuoteListHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.labelSmall
        val color = TradingColors.Muted
        Text("品种", Modifier.weight(QuoteSymbolWeight), color = color, style = style, maxLines = 1)
        Text("Bid 卖出", Modifier.weight(QuotePriceWeight), color = color, style = style, textAlign = TextAlign.End, maxLines = 1)
        Text("Ask 买入", Modifier.weight(QuotePriceWeight), color = color, style = style, textAlign = TextAlign.End, maxLines = 1)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private const val QuoteSymbolWeight = .36f
private const val QuotePriceWeight = .32f

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

private data class ActivityMarker(val label: String, val count: Int, val color: Color)

@Composable
internal fun MarketQuoteRow(
    instrument: Instrument, quote: Quote?, activity: MarketActivity, selected: Boolean,
    connected: Boolean = true, onClick: () -> Unit,
) {
    val colors = TradingColors
    val markers = listOf(
        ActivityMarker("多", activity.longPositions, colors.UpText),
        ActivityMarker("空", activity.shortPositions, colors.DownText),
        ActivityMarker("挂", activity.entries, colors.Accent),
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
        direction > 0 -> colors.UpText
        direction < 0 -> colors.DownText
        else -> colors.Ink
    }
    val type = MaterialTheme.typography
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("symbol-${instrument.symbol}")
        .background(if (selected) colors.Selection else Color.Transparent)
        .clickable(onClickLabel = "打开 ${instrument.symbol} 图表", onClick = onClick)
        .semantics { stateDescription = activity.description; this.selected = selected }) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.15f
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val symbol: @Composable (Modifier) -> Unit = { m -> Column(m) {
                Text(instrument.symbol, style = type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(watchSubtitle(instrument), color = colors.Muted, style = type.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val time = quote?.let { quoteTime(it.timeMs) } ?: "等待报价"
            val spread = "点差 ${quoteSpread(quote, instrument)} tick"
            if (stacked) {
                symbol(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuotePrice("Bid 卖出", quote?.let { instrument.formatPrice(it.bid) }, quoteColor(bidDirection), time, Modifier.weight(1f), stacked = true)
                    QuotePrice("Ask 买入", quote?.let { instrument.formatPrice(it.ask) }, quoteColor(askDirection), spread, Modifier.weight(1f), stacked = true)
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                symbol(Modifier.weight(QuoteSymbolWeight))
                QuotePrice("Bid 卖出", quote?.let { instrument.formatPrice(it.bid) }, quoteColor(bidDirection), time, Modifier.weight(QuotePriceWeight), stacked = false)
                QuotePrice("Ask 买入", quote?.let { instrument.formatPrice(it.ask) }, quoteColor(askDirection), spread, Modifier.weight(QuotePriceWeight), stacked = false)
            }
            if (markers.isNotEmpty()) FlowRow(Modifier.padding(top = 2.dp).testTag("market-markers-${instrument.symbol}"),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                markers.forEach { StatusBadge("${it.label} ${it.count}", it.color) }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun QuotePrice(label: String, price: String?, color: Color, detail: String, modifier: Modifier, stacked: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (stacked) Text(label, color = TradingColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val text = price ?: "—"
            // Reserve the full number even for long index prices and increased system font size.
            val size = minOf(if (stacked) 22f else 20f, maxWidth.value / (text.length * .62f * LocalDensity.current.fontScale)).coerceAtLeast(10f)
            Text(buildAnnotatedString {
                val split = if (price != null && '.' in text) (text.length - 2).coerceAtLeast(text.indexOf('.') + 1) else text.length
                withStyle(SpanStyle(fontSize = (size * .78f).sp)) { append(text.take(split)) }
                withStyle(SpanStyle(fontSize = size.sp)) { append(text.drop(split)) }
            }, Modifier.fillMaxWidth().semantics { contentDescription = "$label ${price ?: "暂无报价"}" },
                color = color, style = MaterialTheme.typography.titleMedium.copy(fontSize = size.sp, lineHeight = (size + 4).sp), textAlign = TextAlign.End,
                maxLines = 1, softWrap = false)
        }
        Text(detail, color = TradingColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
