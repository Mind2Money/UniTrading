package com.unitrading.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.ChartChrome
import com.unitrading.chart.ChartOrderActions
import com.unitrading.chart.IntervalOptions
import com.unitrading.chart.TradingChart
import com.unitrading.tradovate.TradovateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun UniTradingApp(store: SnapshotStore, login: TradovateLogin? = null) {
    val runtime = remember(store) { TradingRuntime(store) }
    TradingScreen(runtime, login)
}

@Composable
fun TradingScreen(runtime: TradingRuntime, login: TradovateLogin? = null, soundPlayer: TradeSoundPlayer? = null) {
    val player = remember(soundPlayer) { soundPlayer ?: platformTradeSoundPlayer() }
    LaunchedEffect(runtime, player) { TradeSoundController(player).run(runtime.registry) }
    val theme by runtime.theme.settings.collectAsState()
    TradingTheme(theme) {
        val colors = TradingColors
        val registry = runtime.registry
        val plugins by registry.state.collectAsState()
        var brokerId by rememberSaveable { mutableStateOf(plugins.firstOrNull()?.descriptor?.id ?: "") }
        val loginScope = rememberCoroutineScope()
        val restoredBrokerId by runtime.savedLogins.restoredBrokerId.collectAsState()
        val loginMessage by runtime.savedLogins.message.collectAsState()
        var manuallySelected by remember { mutableStateOf(false) }
        LaunchedEffect(runtime) { runtime.savedLogins.restore() }
        LaunchedEffect(restoredBrokerId) { if (!manuallySelected) restoredBrokerId?.let { brokerId = it } }
        fun selectBroker(id: String) {
            manuallySelected = true
            brokerId = id
            loginScope.launch { runtime.savedLogins.select(id) }
        }
        var showTheme by remember { mutableStateOf(false) }
        if (showTheme) ThemeSettingsDialog(runtime.theme) { showTheme = false }
        var showIndicators by remember { mutableStateOf(false) }
        if (showIndicators) IndicatorSettingsDialog(runtime.indicators) { showIndicators = false }
        var showConnection by remember { mutableStateOf(false) }
        if (showConnection) TradovateConnectionDialog(runtime, login, ::selectBroker, { showConnection = false })
        val plugin = plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.firstOrNull()
        if (plugin == null) {
            LoginWelcome(runtime, { showConnection = true }, { showTheme = true })
            return@TradingTheme
        }
        fun disconnectCurrent() {
            registry.unregister(plugin.descriptor.id)
            selectBroker(registry.plugins.firstOrNull()?.descriptor?.id ?: "")
        }
        val catalog by key(plugin) { plugin.catalog.collectAsState() }
        if (catalog.accounts.isEmpty() || catalog.instruments.isEmpty()) {
            val feed by key(plugin) { plugin.market.collectAsState() }
            val emptyScope = rememberCoroutineScope()
            var query by remember(plugin) { mutableStateOf("MES") }
            var searchError by remember(plugin) { mutableStateOf<String?>(null) }
            Surface(Modifier.fillMaxSize(), color = colors.Shell) {
                Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("UniTrading · ${plugin.descriptor.name}", fontSize = 20.sp)
                    Text(feed.statusMessage, color = colors.Muted)
                    Text("账户和合约由交易商提供。列表为空时，应用不会用本地数据填充。", fontSize = 12.sp)
                    BrokerAccountChooser(registry, plugin, "", ::selectBroker, {})
                    OutlinedTextField(query, { query = it }, label = { Text("搜索服务器合约，如 MES") }, singleLine = true)
                    Button({ emptyScope.launch { try { plugin.searchInstruments(query) } catch (_: Exception) { searchError = "合约搜索失败，请检查连接和权限" } } }, enabled = feed.connected) { Text("查询合约") }
                    searchError?.let { Text(it, color = colors.Loss) }
                    TextButton({ showConnection = true }) { Text("连接 / 重新登录 Tradovate") }
                    TextButton({ disconnectCurrent() }) { Text("断开连接") }
                    TextButton({ showTheme = true }, Modifier.testTag("open-theme")) { Text("外观与主题") }
                    SavedLoginControls(runtime.savedLogins) { selectBroker(registry.plugins.firstOrNull()?.descriptor?.id ?: "") }
                }
            }
            return@TradingTheme
        }
        var chosenAccountId by remember(plugin) { mutableStateOf(runtime.savedLogins.selectedAccount(plugin.descriptor.id) ?: catalog.accounts.first().id) }
        val accountId = chosenAccountId.takeIf { id -> catalog.accounts.any { it.id == id } } ?: catalog.accounts.first().id
        val localDemo = plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO
        val session = remember(plugin, accountId) { plugin.openSession(accountId) }
        DisposableEffect(plugin, session) {
            runtime.livePnl.track(plugin, session)
            onDispose { runtime.livePnl.untrack(session) }
        }
        val account by key(session) { session.state.collectAsState() }
        val market by key(plugin) { plugin.market.collectAsState() }
        val paused by runtime.paused.collectAsState()
        val startupError by runtime.startupError.collectAsState()
        val storageError by runtime.storageError.collectAsState()
        val accountKey = "$brokerId/$accountId/${plugin.hashCode()}"
        var chosenSymbol by remember(plugin) { mutableStateOf(catalog.instruments.first().symbol) }
        val instrument = catalog.instruments.firstOrNull { it.symbol == chosenSymbol } ?: catalog.instruments.first()
        val symbol = instrument.symbol
        val quote = market.quotes[symbol]
        var timeframe by remember { mutableStateOf(Timeframe.M5) }
        val orderSessions = remember(accountKey) { mutableMapOf<String, MobileOrderSession>() }
        val orderSession = remember(accountKey, symbol) { orderSessions.getOrPut(symbol) { MobileOrderSession(instrument) } }
        var draft by orderSession.draft
        var showDraft by remember(accountKey, symbol) { mutableStateOf(false) }
        var selectedPositionId by remember(accountKey) { mutableStateOf<String?>(null) }
        var focusedTrade by remember(accountKey) { mutableStateOf<ClosedTrade?>(null) }
        var focusedExecution by remember(accountKey) { mutableStateOf<Execution?>(null) }
        var protection by remember(accountKey) { mutableStateOf<ProtectionEdit?>(null) }
        var pendingEdit by remember(accountKey) { mutableStateOf<PendingEntryEdit?>(null) }
        var pendingProtection by remember(accountKey) { mutableStateOf<PendingProtectionEdit?>(null) }
        var confirmation by remember(accountKey, symbol) { mutableStateOf<Confirmation?>(null) }
        var mobileTab by remember { mutableStateOf(1) }
        var showAccounts by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showActivity by remember(accountKey) { mutableStateOf(false) }
        var showOrder by remember { mutableStateOf(false) }
        var orderPanelState by orderSession.panelState
        val orderOpen = orderPanelState == MobileOrderPanelState.EDITING
        val orderActive = orderPanelState != MobileOrderPanelState.CLOSED
        var orderExpanded by orderSession.expanded
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<TradingNotice?>(null) }
        var nextNoticeId by remember { mutableLongStateOf(0) }
        val scope = rememberCoroutineScope()
        fun showNotice(message: String, attention: Boolean = false) {
            notice = TradingNotice(++nextNoticeId, message, if (attention) NoticeTone.ATTENTION else NoticeTone.INFO, persistent = attention)
        }
        fun syncAccount() {
            if (busy || !market.connected) return
            busy = true
            scope.launch {
                try { plugin.resynchronize(); showNotice("账户状态已同步") }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { showNotice("同步失败，请检查连接后重试；未重发交易指令", attention = true) }
                finally { busy = false }
            }
        }
        val chartPreferences by runtime.chartPreferences.preferences.collectAsState()
        val intervals = IntervalOptions(chartPreferences.favorites, chartPreferences.custom,
            onToggleFavorite = { frame -> scope.launch { runtime.chartPreferences.toggleFavorite(frame) } },
            onAddCustom = { frame -> scope.launch { runtime.chartPreferences.addCustom(frame) } })
        val request = draft.request(instrument)
        val (capacity, refreshCapacity) = rememberQuantityCapacity(session, instrument, draft, account,
            market.tradingReady && quote?.let { market.simulatedTimeMs - it.timeMs in -5_000..15_000 } == true, localDemo)
        val preview = remember(session, instrument, request, market.revision, account, capacity) {
            request?.let(session::preview)?.withCapacity(request, capacity)
        }
        val candles = remember(plugin, symbol, timeframe, market.revision) { plugin.history(symbol, timeframe) }
        val indicatorSettings by runtime.indicators.settings.collectAsState()
        val visiblePositions = account.positions.filter { it.symbol == symbol }
        val visibleTrades = account.trades.filter { it.symbol == symbol }
        val visibleOrders = account.orders.filter { it.request.symbol == symbol }

        LaunchedEffect(runtime) { runtime.run() }
        LaunchedEffect(plugin, symbol, timeframe) { plugin.watch(symbol, timeframe) }
        LaunchedEffect(accountKey) { showOrder = false; notice = null }
        LaunchedEffect(plugin, catalog.instruments.map { it.symbol }) {
            if (chosenSymbol != symbol) {
                chosenSymbol = symbol
                selectedPositionId = null; focusedTrade = null; focusedExecution = null; protection = null; pendingEdit = null; pendingProtection = null; confirmation = null
            }
        }

        fun chooseSymbol(value: String) {
            chosenSymbol = value
            focusedTrade = null
            focusedExecution = null
            selectedPositionId = null
            protection = null
            pendingEdit = null
            pendingProtection = null
            confirmation = null
            mobileTab = 1
        }
        fun chooseBroker(value: String) { if (!busy) selectBroker(value) }
        fun chooseAccount(value: String) {
            if (!busy) {
                manuallySelected = true
                chosenAccountId = value
                loginScope.launch { runtime.savedLogins.select(plugin.descriptor.id, value) }
            }
        }
        fun changeDraft(value: OrderDraft) { draft = value; showDraft = true }
        fun perform(onAccepted: () -> Unit = {}, action: suspend () -> TradeResult) {
            if (busy) return
            busy = true
            val origin = session.account.name
            scope.launch {
                try {
                    val result = action()
                    runtime.save()
                    showNotice("$origin：${result.message}", attention = !result.accepted || result.outcome == CommandOutcome.UNKNOWN)
                    if (result.accepted) { showDraft = false; onAccepted() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    showNotice("操作失败：${error.message}", attention = true)
                } finally { busy = false }
            }
        }
        fun editProtection(position: Position, sl: Long? = position.stopLoss, tp: Long? = position.takeProfit) {
            if (!plugin.capabilities.modifyProtection || !position.protectionEditable || busy) return
            val spec = plugin.instruments.firstOrNull { it.symbol == position.symbol }
                ?: run { showNotice("品种或账户已切换，请重新选择仓位", attention = true); return }
            chosenSymbol = position.symbol
            selectedPositionId = position.id
            pendingProtection = null
            protection = ProtectionEdit(position, sl?.let(spec::formatPrice) ?: "", tp?.let(spec::formatPrice) ?: "")
        }

        /** Opens the confirmation for a pending entry's stop loss / take profit, prefilled with [sl] and [tp]. */
        fun editPendingProtection(order: PendingOrder, sl: Long? = order.request.stopLoss, tp: Long? = order.request.takeProfit) {
            if (busy) return
            val current = account.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || current.request != order.request || current.role != OrderRole.ENTRY ||
                !current.editable || !current.protectionEditable || !plugin.capabilities.modifyPendingProtection) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            val spec = catalog.instruments.firstOrNull { it.symbol == current.request.symbol } ?: return
            chosenSymbol = current.request.symbol
            protection = null
            pendingEdit = null
            showDraft = false
            pendingProtection = PendingProtectionEdit(current, sl?.let(spec::formatPrice) ?: "", tp?.let(spec::formatPrice) ?: "")
        }

        fun editPending(order: PendingOrder, price: Long = order.request.entryPrice!!) {
            if (busy) return
            val current = account.orders.firstOrNull { it.id == order.id }
            // A bracket of an unfilled entry is repriced through that entry's stop loss / take profit.
            val bracket = current?.takeIf { it.isProtection && it.positionId == null }
            val entry = bracket?.let { b -> account.orders.firstOrNull { it.role == OrderRole.ENTRY && b.id in listOf(it.stopLossOrderId, it.takeProfitOrderId) } }
            if (bracket != null && entry != null) {
                editPendingProtection(entry, if (bracket.role == OrderRole.STOP_LOSS) price else entry.request.stopLoss,
                    if (bracket.role == OrderRole.TAKE_PROFIT) price else entry.request.takeProfit)
                return
            }
            if (current == null || current.version != order.version || current.role != order.role || !current.editable) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            if (current.isProtection) {
                val position = account.positions.firstOrNull { it.id == current.positionId && it.protectionEditable }
                if (position == null) { showNotice("保护单尚未唯一关联到当前仓位", attention = true); return }
                pendingEdit = null
                editProtection(position, if (current.role == OrderRole.STOP_LOSS) price else position.stopLoss,
                    if (current.role == OrderRole.TAKE_PROFIT) price else position.takeProfit)
                return
            }
            if (current.role != OrderRole.ENTRY || !plugin.capabilities.modifyPendingOrders) return
            val spec = catalog.instruments.firstOrNull { it.symbol == current.request.symbol } ?: return
            chosenSymbol = current.request.symbol
            protection = null
            pendingProtection = null
            showDraft = false
            pendingEdit = PendingEntryEdit(current, spec.formatPrice(price))
        }

        Surface(Modifier.fillMaxSize(), color = colors.Shell) {
            val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val focusManager = LocalFocusManager.current
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val desktop = maxWidth >= 900.dp && maxHeight >= 600.dp
                val wide = desktop && maxWidth >= 1240.dp
                val compact = !desktop
                val compactShort = compact && maxHeight < 550.dp
                val environmentLabel = if (localDemo) "本地模拟" else if (plugin.descriptor.environment == BrokerEnvironment.LIVE) "Live 实盘" else "远程 Demo"
                // Phones are chart-first: on the chart tab the chart's own toolbar row replaces the app header, and the open
                // order panel takes the tab bar's place while editing; minimizing restores navigation.
                val chartFirst = compact && mobileTab == 1
                val panelOpen = chartFirst && orderOpen
                fun minimizeOrder() { focusManager.clearFocus(); orderPanelState = MobileOrderPanelState.MINIMIZED }
                fun closeOrder() {
                    focusManager.clearFocus()
                    orderPanelState = MobileOrderPanelState.CLOSED
                    orderExpanded = false
                    showDraft = false
                    draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                    if (confirmation is Confirmation.Place) confirmation = null
                }
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = panelOpen && !keyboardOpen, onBackCompleted = { minimizeOrder() })
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && mobileTab == 3 && showActivity, onBackCompleted = { showActivity = false })
                val chart: @Composable (Modifier) -> Unit = { m ->
                    // Minimizing hides only the form; the active draft remains editable on the chart.
                    val draftShown = if (compact) orderActive else showDraft
                    // Draft lines take a drop at once; pending orders and positions wait for the confirmation dialogs.
                    val actions = ChartOrderActions(
                        estimateDraft = session::preview,
                        onDraftEntry = { changeDraft(draft.copy(entry = instrument.formatPrice(it))) },
                        onDraftProtection = { stop, price ->
                            changeDraft(draft.withProtectionPrice(stop, instrument.formatPrice(price)))
                        },
                        editPending = plugin.capabilities.modifyPendingOrders,
                        estimatePending = session::previewOrderModification,
                        onPendingEntry = { order, price -> editPending(order, price) },
                        editPendingProtection = plugin.capabilities.modifyPendingProtection,
                        estimatePendingProtection = session::previewOrderProtection,
                        onPendingProtection = { order, sl, tp -> editPendingProtection(order, sl, tp) },
                        onProtection = { p, sl, tp -> editProtection(p, sl, tp) },
                    )
                    TradingChart(accountKey, instrument, quote, candles, timeframe, { timeframe = it }, visiblePositions, visibleTrades,
                        selectedPositionId, focusedTrade, request.takeIf { draftShown }, preview.takeIf { draftShown }, visibleOrders, actions,
                        m, environment = plugin.descriptor.environment, executions = account.executions.filter { it.symbol == symbol }, indicators = indicatorSettings.indicators, onIndicators = { showIndicators = true }, focusedExecution = focusedExecution,
                        instruments = catalog.instruments, onSymbol = ::chooseSymbol, intervals = intervals, connected = market.connected,
                        onSide = { side -> changeDraft(draft.withSide(side, instrument, quote)); if (compact) orderPanelState = MobileOrderPanelState.EDITING else if (!wide) showOrder = true },
                        // The legend's fold is remembered once the user toggles it; until then short phone screens start folded.
                        chrome = ChartChrome(compact, chartPreferences.legendCollapsed ?: compactShort,
                            { folded -> scope.launch { runtime.chartPreferences.setLegendCollapsed(folded) } },
                            ordering = compact && orderOpen, toolbarEnd = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!localDemo) ConnectionStatusControl(market, compact = true, busy,
                                        onSync = if (plugin.capabilities.resynchronize) ({ syncAccount() }) else null)
                                    Badge(environmentLabel)
                                }
                            }))
                }
                Column(Modifier.fillMaxSize()) {
                    if (!chartFirst) {
                        Row(Modifier.fillMaxWidth().height(if (compact) 48.dp else 44.dp).testTag("app-toolbar")
                            .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Canvas(Modifier.size(22.dp)) {
                                drawLine(colors.Accent, Offset(size.width * .1f, size.height * .72f), Offset(size.width * .45f, size.height * .3f), 4f)
                                drawLine(colors.Accent, Offset(size.width * .45f, size.height * .3f), Offset(size.width * .65f, size.height * .55f), 4f)
                                drawLine(colors.Ink, Offset(size.width * .65f, size.height * .55f), Offset(size.width * .95f, size.height * .12f), 4f)
                            }
                            Column(if (compact) Modifier.weight(1f) else Modifier) {
                                Text("UniTrading", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                if (compact) Text(session.account.name, fontSize = 10.sp, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Badge(environmentLabel)
                            if (!localDemo && compact) ConnectionStatusControl(market, compact = true, busy,
                                onSync = if (plugin.capabilities.resynchronize) ({ syncAccount() }) else null)
                            // Phones reach accounts, theme and the order ticket through the tab bar and the chart's order panel.
                            if (desktop) {
                                Text(session.account.name, Modifier.weight(1f),
                                    fontSize = 12.sp, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!localDemo) ConnectionStatusControl(market, compact = false, busy,
                                    onSync = if (plugin.capabilities.resynchronize) ({ syncAccount() }) else null)
                                if (localDemo) TextButton({ runtime.paused.value = !paused }) { Text(if (paused) "继续行情" else "暂停行情", fontSize = 11.sp) }
                                TextButton({ runtime.desktopLayout.reset(); scope.launch { runtime.desktopLayout.save() } }, Modifier.testTag("reset-desktop-layout")) { Text("重置布局") }
                                TextButton({ showTheme = true }, Modifier.testTag("open-theme")) { Text("主题") }
                                TextButton({ showActivity = false; showSettings = true }, Modifier.testTag("open-settings")) { Text("设置") }
                                TextButton({ showAccounts = true }, Modifier.testTag("open-accounts")) { Text("切换账户", fontSize = 12.sp) }
                                if (!wide) Button({ showOrder = true; showDraft = true }, Modifier.testTag("open-order")) { Text("下单") }
                            }
                        }
                        DividerLine()
                    }
                    if (desktop) {
                        DesktopWorkspace(runtime.desktopLayout, wide, Modifier.weight(1f).fillMaxWidth(),
                            accounts = {
                                BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount)
                            },
                            market = { m -> MarketWatch(plugin, market, symbol, ::chooseSymbol, m) },
                            chart = chart,
                            order = { m -> OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                { request?.let { confirmation = Confirmation.Place(it) } }, m.background(colors.Shell), environment = plugin.descriptor.environment,
                                capacity = capacity, onRefreshCapacity = refreshCapacity) },
                            terminal = { m ->
                                TradeTerminal(plugin, account, market, selectedPositionId, false,
                                    onSelectPosition = { p -> focusedExecution = null; chosenSymbol = p.symbol; selectedPositionId = p.id; focusedTrade = null },
                                    onSelectTrade = { t -> focusedExecution = null; chosenSymbol = t.symbol; focusedTrade = t; selectedPositionId = null },
                                    onClose = { confirmation = Confirmation.Close(it) }, onProtect = { editProtection(it) }, onCancel = { confirmation = Confirmation.Cancel(it) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = m, onProtectOrder = { editPendingProtection(it) })
                            })
                    } else {
                        when (mobileTab) {
                            0 -> MarketWatch(plugin, market, symbol, ::chooseSymbol, Modifier.weight(1f))
                            1 -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                // Landscape phones dock the open ticket beside the chart; portrait phones stack it under the chart.
                                val dock = maxWidth >= 600.dp && maxWidth > maxHeight && !keyboardOpen
                                // Give fields space on phones, keep the chart usable, and reserve the whole remaining area for IME editing.
                                val panelHeight = if (keyboardOpen) maxHeight else
                                    (maxHeight * if (orderExpanded) .86f else .70f).coerceAtMost(if (orderExpanded) maxHeight else 600.dp)
                                val bar: @Composable () -> Unit = {
                                    if (orderActive) MinimizedOrderBar(instrument, draft, busy,
                                        onRestore = { orderPanelState = MobileOrderPanelState.EDITING }, onClose = { closeOrder() })
                                    else MobileTradeBar(account, onOpen = { orderPanelState = MobileOrderPanelState.EDITING },
                                        onPositions = { mobileTab = 2 })
                                }
                                val panel: @Composable (Modifier) -> Unit = { m ->
                                    MobileOrderPanel(instrument, session.account.name, environmentLabel, handle = !dock && !keyboardOpen,
                                        onMinimize = { minimizeOrder() }, onClose = { closeOrder() }, modifier = m, busy = busy, expanded = orderExpanded,
                                        onExpand = if (dock || keyboardOpen) null else ({ orderExpanded = !orderExpanded })) {
                                        MobileOrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, { request?.let { confirmation = Confirmation.Place(it) } },
                                            Modifier.weight(1f), environment = plugin.descriptor.environment, capacity = capacity, onRefreshCapacity = refreshCapacity)
                                    }
                                }
                                if (dock) Row(Modifier.fillMaxSize()) {
                                    Column(Modifier.weight(1f).fillMaxHeight()) {
                                        chart(Modifier.weight(1f))
                                        if (!orderOpen) bar()
                                    }
                                    AnimatedVisibility(orderOpen,
                                        enter = expandHorizontally(tween(280), expandFrom = Alignment.End),
                                        exit = shrinkHorizontally(tween(220), shrinkTowards = Alignment.End)) {
                                        Row {
                                            VerticalDivider(color = colors.Border)
                                            panel(Modifier.width(340.dp).fillMaxHeight())
                                        }
                                    }
                                } else Column(Modifier.fillMaxSize()) {
                                    // Keep the chart composed so opening the IME does not reset its viewport or layers.
                                    chart(if (orderOpen && keyboardOpen) Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { } else Modifier.weight(1f))
                                    val animatedHeight by animateDpAsState(
                                        if (orderOpen) panelHeight else 76.dp,
                                        animationSpec = tween(280), label = "order-panel-height")
                                    Crossfade(orderOpen, Modifier.fillMaxWidth().height(animatedHeight).clipToBounds(),
                                        animationSpec = tween(180), label = "order-panel-content") { editing ->
                                        if (editing) panel(Modifier.fillMaxSize()) else bar()
                                    }
                                }
                            }
                            2 -> Column(Modifier.weight(1f)) {
                                AccountStrip(account)
                                DividerLine()
                                TradeTerminal(plugin, account, market, selectedPositionId, true,
                                    onSelectPosition = { p -> focusedExecution = null; chosenSymbol = p.symbol; selectedPositionId = p.id; focusedTrade = null; mobileTab = 1 },
                                    onSelectTrade = { t -> focusedExecution = null; chosenSymbol = t.symbol; focusedTrade = t; mobileTab = 1 },
                                    onClose = { confirmation = Confirmation.Close(it) }, onProtect = { editProtection(it) }, onCancel = { confirmation = Confirmation.Cancel(it) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = Modifier.weight(1f).fillMaxWidth(), onProtectOrder = { editPendingProtection(it) })
                            }
                            else -> if (showActivity) {
                                ActivityLogPage("${plugin.descriptor.name} · ${session.account.name}", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.weight(1f).fillMaxWidth())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    if (compact) {
                        if (!panelOpen) {
                            DividerLine()
                            MobileNavigation(mobileTab) { if (mobileTab != it) showActivity = false; mobileTab = it }
                        }
                    } else {
                        DividerLine()
                        AccountStrip(account)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
                            Text(if (!localDemo) plugin.descriptor.environment.label else if (paused) "模拟已暂停" else "模拟行情运行中 · ×15", color = colors.Muted, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (localDemo) "报价 ${clockLabel(market.simulatedTimeMs, true)} UTC   |   本地保存   |   无真实资金" else "${session.account.name}   |   状态以服务器为准   |   ${plugin.descriptor.environment.label}", color = colors.Muted, fontSize = 10.sp)
                        }
                    }
                }
                // Notifications occupy overlay space, never alter chart/panel constraints, and do not dim the workspace.
                Column(Modifier.align(if (desktop) Alignment.TopEnd else Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = if (desktop) 54.dp else if (keyboardOpen) 8.dp else 58.dp)
                    .widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight * .65f)
                    .verticalScroll(rememberScrollState()).testTag("notification-overlay"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    storageError?.let { message ->
                        FloatingNoticeCard(TradingNotice(0, message, NoticeTone.ATTENTION, persistent = true), "storage-notice", null,
                            title = "保存失败", action = { TextButton({ scope.launch { runtime.save() } }, Modifier.testTag("retry-storage-save")) { Text("重试保存") } })
                    }
                    notice?.let { current ->
                        FloatingNoticeCard(current, "trade-notice", { if (notice?.id == current.id) notice = null }, title = "操作提示")
                    }
                    loginMessage?.let { message -> key(message) {
                        var dismissed by remember { mutableStateOf(false) }
                        if (!dismissed) FloatingNoticeCard(TradingNotice(0, message, persistent = true), "saved-login-status", { dismissed = true }, title = "登录状态")
                    } }
                }
                if (desktop && showSettings) {
                    Dialog(onDismissRequest = { if (showActivity) showActivity = false else showSettings = false }) {
                        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.large, color = colors.Shell) {
                            if (showActivity) {
                                ActivityLogPage("${plugin.descriptor.name} · ${session.account.name}", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.fillMaxSize())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.fillMaxSize(), onClose = { showSettings = false })
                        }
                    }
                }
                if (showOrder) {
                    Dialog(onDismissRequest = { if (!busy) showOrder = false }) {
                        Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                            Column(Modifier.widthIn(max = 420.dp).fillMaxHeight(.94f).imePadding()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) { TextButton({ showOrder = false }) { Text("返回图表") } }
                                OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, {
                                    request?.let { confirmation = Confirmation.Place(it); showOrder = false }
                                }, Modifier.weight(1f), environment = plugin.descriptor.environment, capacity = capacity, onRefreshCapacity = refreshCapacity)
                            }
                        }
                    }
                }
                if (showAccounts) AlertDialog(
                    onDismissRequest = { showAccounts = false },
                    title = { Text("交易商与账户") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                        BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount)
                        Text(if (localDemo) "当前账户为本地模拟。" else "${plugin.descriptor.environment.label} · 服务器账户 · 净额持仓", fontSize = 12.sp, color = colors.Muted)
                        TextButton({ showAccounts = false; showConnection = true }) { Text("连接 Tradovate") }
                        if (!localDemo) TextButton({ disconnectCurrent(); showAccounts = false }) { Text("断开 Tradovate") }
                        TextButton({ showTheme = true; showAccounts = false }) { Text("外观与主题") }
                        SavedLoginControls(runtime.savedLogins) { selectBroker(registry.plugins.firstOrNull()?.descriptor?.id ?: ""); showAccounts = false }
                    } },
                    confirmButton = { TextButton({ showAccounts = false }) { Text("完成") } },
                    dismissButton = { if (localDemo) TextButton({ showAccounts = false; confirmation = Confirmation.Reset }) { Text("重置演示") } },
                )

                confirmation?.takeIf { action ->
                    val target = when (action) {
                        is Confirmation.Place -> action.request.symbol
                        is Confirmation.Close -> action.position.symbol
                        is Confirmation.Cancel -> action.order.request.symbol
                        Confirmation.Reset -> null
                    }
                    target == null || catalog.instruments.any { it.symbol == target }
                }?.let { action ->
                    val title = when (action) { is Confirmation.Place -> if (localDemo) "确认模拟下单" else "确认 ${plugin.descriptor.environment.label} 下单"; is Confirmation.Close -> "确认平仓"; is Confirmation.Cancel -> "确认撤单"; Confirmation.Reset -> "重置全部模拟数据？" }
                    val livePreview = (action as? Confirmation.Place)?.let { session.preview(it.request).withCapacity(it.request, capacity) }
                    AlertDialog(
                        onDismissRequest = { if (!busy) confirmation = null },
                        title = { Text(title) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${plugin.descriptor.name} / ${session.account.name} / ${plugin.descriptor.environment.label}", color = colors.Muted, fontSize = 12.sp)
                            when (action) {
                                is Confirmation.Place -> {
                                    val r = action.request
                                    Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}")
                                    EstimateRow("当前预估入场", livePreview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                                    EstimateRow("预计保证金 USD", livePreview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                                    Text("提交时会按最新报价和账户状态重新校验，实际成交价可能与预估不同。", fontSize = 12.sp, color = colors.Muted)
                                    livePreview?.errors?.forEach { Text(it, color = colors.Loss, fontSize = 12.sp) }
                                }
                                is Confirmation.Close -> Text("平掉 ${action.position.symbol} 的 #${action.position.id} 全部 ${catalog.instruments.first { it.symbol == action.position.symbol }.formatQuantity(action.position.lotsHundredths)} ${catalog.instruments.first { it.symbol == action.position.symbol }.quantityUnit}，按当前可成交报价执行。")
                                is Confirmation.Cancel -> Text("撤销${action.order.role.label()} #${action.order.id}。" +
                                    if (action.order.isProtection) "撤单后将移除这项保护，服务器也可能调整关联订单。" else "若已被触发，交易商将拒绝撤单。")
                                Confirmation.Reset -> Text("将覆盖两个交易商的全部本地模拟余额、仓位、挂单和历史，并重新生成演示交易。此操作不能撤销。")
                            }
                        } },
                        confirmButton = { Button({
                            confirmation = null
                            val id = requestId()
                            when (action) {
                                is Confirmation.Place -> perform({ if (compact) closeOrder() }) { session.placeOrder(id, action.request) }
                                is Confirmation.Close -> perform { session.closePosition(id, action.position.id) }
                                is Confirmation.Cancel -> perform { session.cancelOrder(id, action.order.id) }
                                Confirmation.Reset -> perform { runtime.resetAll(); TradeResult(true, "已重置全部本地模拟数据") }
                            }
                        }, enabled = !busy && (livePreview == null || livePreview.valid), modifier = Modifier.testTag("confirm-action")) { Text("确认") } },
                        dismissButton = { TextButton({ confirmation = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                pendingEdit?.takeIf { edit -> catalog.instruments.any { it.symbol == edit.order.request.symbol } }?.let { edit ->
                    val order = edit.order
                    val spec = catalog.instruments.first { it.symbol == order.request.symbol }
                    val current = account.orders.firstOrNull { it.id == order.id }
                    val price = spec.parsePrice(edit.entry)
                    val estimate = price?.let { session.previewOrderModification(order.id, it) }
                    val validVersion = current != null && current.version == order.version && current.role == OrderRole.ENTRY && current.editable
                    AlertDialog(
                        modifier = Modifier.testTag("pending-entry-dialog"),
                        onDismissRequest = { if (!busy) pendingEdit = null },
                        title = { Text("修改挂单入场价") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${order.request.symbol} · ${order.request.side.label()} ${order.request.kind.name} · ${spec.formatQuantity(order.request.lotsHundredths)} ${spec.quantityUnit}", fontSize = 12.sp)
                            Text("原入场价 ${spec.formatPrice(order.request.entryPrice!!)}", color = colors.Muted, fontSize = 11.sp)
                            PriceInput("新入场价", edit.entry, { pendingEdit = edit.copy(entry = it) }, "pending-entry")
                            EstimateRow("预计保证金", estimate?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} USD" } ?: "—")
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", estimate?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", estimate?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            Text("本次只改入场价，订单类型、数量和 SL/TP 不变。确认后会按最新报价重新校验。", color = colors.Muted, fontSize = 11.sp)
                            if (!validVersion) Text("挂单已成交、已撤销或版本已更新，请取消后重新选择。", color = colors.Loss, fontSize = 11.sp)
                            if (price == null) Text("请输入符合品种精度的有效价格。", color = colors.Loss, fontSize = 11.sp)
                            estimate?.errors?.forEach { Text(it, color = colors.Loss, fontSize = 11.sp) }
                        } },
                        confirmButton = { Button({
                            val newPrice = price ?: return@Button
                            pendingEdit = null
                            perform { session.modifyOrder(requestId(), order.id, order.version, newPrice) }
                        }, Modifier.testTag("confirm-pending-entry"), enabled = !busy && validVersion && estimate?.valid == true) { Text("确认改价") } },
                        dismissButton = { TextButton({ pendingEdit = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                pendingProtection?.let { edit ->
                    val spec = catalog.instruments.firstOrNull { it.symbol == edit.order.request.symbol } ?: return@let
                    PendingProtectionDialog(edit, spec, account.orders.firstOrNull { it.id == edit.order.id }, plugin.capabilities,
                        { sl, tp -> session.previewOrderProtection(edit.order.id, sl, tp) }, busy, { pendingProtection = it },
                        onConfirm = { sl, tp ->
                            pendingProtection = null
                            perform { session.modifyOrderProtection(requestId(), edit.order.id, edit.order.version, sl, tp) }
                        },
                        onDismiss = { if (!busy) pendingProtection = null })
                }
                protection?.takeIf { edit -> catalog.instruments.any { it.symbol == edit.position.symbol } }?.let { edit ->
                    val p = edit.position
                    val spec = catalog.instruments.first { it.symbol == p.symbol }
                    val current = account.positions.firstOrNull { it.id == p.id }
                    val sl = if (edit.stopLoss.isBlank()) null else spec.parsePrice(edit.stopLoss)
                    val tp = if (edit.takeProfit.isBlank()) null else spec.parsePrice(edit.takeProfit)
                    val validInput = (edit.stopLoss.isBlank() || sl != null && sl > 0 && sl % spec.tickSize == 0L) && (edit.takeProfit.isBlank() || tp != null && tp > 0 && tp % spec.tickSize == 0L)
                    val validVersion = current?.version == p.version
                    val capabilityError = protectionEditError(plugin.capabilities, p, sl, tp)
                    fun estimate(price: Long?): String = price?.takeIf { it > 0 }?.let {
                        runCatching { "${money(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, it) - p.entryFeeMicros - commission(spec, p.lotsHundredths), true)} USD" }.getOrDefault("超出范围")
                    } ?: "未设置"
                    AlertDialog(
                        modifier = Modifier.testTag("protection-dialog"),
                        onDismissRequest = { if (!busy) protection = null },
                        title = { Text("修改止损 / 止盈") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${p.symbol} · ${p.side.label()} ${spec.formatQuantity(p.lotsHundredths)} ${spec.quantityUnit} · #${p.id}", fontSize = 12.sp)
                            Text("原 SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", color = colors.Muted, fontSize = 11.sp)
                            PriceInput(if (plugin.capabilities.removeProtection) "新止损 SL（留空移除）" else "新止损 SL（保留原价或修改）", edit.stopLoss, { protection = edit.copy(stopLoss = it) }, "protect-sl")
                            PriceInput(if (plugin.capabilities.removeProtection) "新止盈 TP（留空移除）" else "新止盈 TP（保留原价或修改）", edit.takeProfit, { protection = edit.copy(takeProfit = it) }, "protect-tp")
                            if (!plugin.capabilities.atomicProtectionEdit) Text("每次只能修改一条保护线。请等服务器确认后再修改另一条。", fontSize = 11.sp)
                            capabilityError?.let { Text(it, color = colors.Loss, fontSize = 11.sp) }
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", estimate(sl))
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", estimate(tp))
                            Text("当前显示预估结果。确认后由交易商校验并更新，跳空可能使实际结果与预估不同。", color = colors.Muted, fontSize = 11.sp)
                            if (!validVersion) Text("仓位已关闭或保护价已更新，请取消后重新选择。", color = colors.Loss, fontSize = 11.sp)
                            if (!validInput) Text("价格须大于零，并符合该品种的最小变动单位。", color = colors.Loss, fontSize = 11.sp)
                        } },
                        confirmButton = { Button({
                            protection = null
                            perform { session.modifyProtection(requestId(), p.id, p.version, sl, tp) }
                        }, Modifier.testTag("confirm-protection"), enabled = validInput && validVersion && capabilityError == null && !busy) { Text("确认修改") } },
                        dismissButton = { TextButton({ protection = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                if (startupError != null && confirmation !is Confirmation.Reset) AlertDialog(
                    onDismissRequest = {}, title = { Text("模拟存档无法读取") },
                    text = { Text(startupError!! + "\n请先备份原文件。继续重置会覆盖所有模拟账户。") },
                    confirmButton = { TextButton({ confirmation = Confirmation.Reset }) { Text("检查重置操作") } },
                )
            }
        }
    }
}
