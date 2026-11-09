package com.unitrading.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.ChartChrome
import com.unitrading.chart.ChartOrderActions
import com.unitrading.chart.IntervalOptions
import com.unitrading.chart.LineAction
import com.unitrading.chart.TradingChart
import com.unitrading.tradovate.TradovateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Orders, edits, cancels and history work on Paper Trading; closing a position is unverified live, and conformance is pending. */
private const val RITHMIC_LOGIN_ENABLED = true

@Composable
fun UniTradingApp(store: SnapshotStore, login: TradovateLogin? = null) {
    val runtime = remember(store) { TradingRuntime(store) }
    TradingScreen(runtime, login)
}

@Composable
fun TradingScreen(runtime: TradingRuntime, login: TradovateLogin? = null, soundPlayer: TradeSoundPlayer? = null, updates: AppUpdates? = null) {
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
        var showRithmicConnection by remember { mutableStateOf(false) }
        if (showRithmicConnection) RithmicConnectionDialog(runtime, ::selectBroker, { showRithmicConnection = false })
        val connectRithmic: (() -> Unit)? = if (RITHMIC_LOGIN_ENABLED) ({ showRithmicConnection = true }) else null
        val plugin = plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.firstOrNull()
        if (plugin == null) {
            LoginWelcome(runtime, { showConnection = true }, { showTheme = true }, onLoginRithmic = connectRithmic)
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
                Column(Modifier.safeDrawingPadding()) {
                    PageTopBar(plugin.descriptor.name, feed.statusMessage, actions = {
                        EnvironmentBadge(plugin.descriptor.environment)
                        ActionIcon(AppIcons.Palette, "外观与主题", "open-theme") { showTheme = true }
                    })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 560.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("账户和合约由交易商提供。列表为空时，应用不会用本地数据填充。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.Muted)
                        BrokerAccountChooser(registry, plugin, "", ::selectBroker, {})
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("搜索服务器合约，如 MES") }, singleLine = true)
                            Button({ emptyScope.launch { try { plugin.searchInstruments(query) } catch (_: Exception) { searchError = "合约搜索失败，请检查连接和权限" } } }, enabled = feed.connected) { Text("查询合约") }
                        }
                        searchError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                        ListSubheader("连接")
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ showConnection = true }, Modifier.fillMaxWidth()) { Text("连接 / 重新登录 Tradovate") }
                            connectRithmic?.let { OutlinedButton(it, Modifier.fillMaxWidth()) { Text("连接 / 重新登录 Rithmic") } }
                            TextButton({ disconnectCurrent() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("断开连接") }
                            SavedLoginControls(runtime.savedLogins) { selectBroker(registry.plugins.firstOrNull()?.descriptor?.id ?: "") }
                        }
                    }
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
        val savedChart = remember(plugin) { runtime.chartPreferences.preferences.value }
        // The last chart symbol can load after the first catalog snapshot; keep it pending until it loads or the user picks one.
        var restoring by remember(plugin) { mutableStateOf(savedChart.lastSymbols[plugin.descriptor.id]) }
        var chosenSymbol by remember(plugin) { mutableStateOf(restoring?.takeIf { saved -> catalog.instruments.any { it.symbol == saved } } ?: catalog.instruments.first().symbol) }
        val instrument = catalog.instruments.firstOrNull { it.symbol == chosenSymbol } ?: catalog.instruments.first()
        val symbol = instrument.symbol
        val executionSymbol = instrument.executionSymbol
        var previousExecutionSymbol by remember(accountKey, symbol) { mutableStateOf(executionSymbol) }
        val quote = market.quotes[executionSymbol]
        var timeframe by remember { mutableStateOf(savedChart.lastTimeframe ?: Timeframe.M5) }
        // The chart's symbol menu lists the market page's watch list, in its order.
        val watchLists by runtime.marketWatch.lists.collectAsState()
        val watchSymbols = watchLists[plugin.descriptor.id].orEmpty().toSet()
        // Keep loading and verifying the saved list while mobile navigation shows another page.
        LaunchedEffect(plugin, watchSymbols) { plugin.watchQuotes(watchSymbols) }
        DisposableEffect(plugin) { onDispose { plugin.watchQuotes(emptySet()) } }
        val watchInstruments = watchLists[plugin.descriptor.id].orEmpty().mapNotNull { saved -> catalog.instruments.firstOrNull { it.symbol == saved } }
        val orderSessions = remember(accountKey) { mutableMapOf<String, MobileOrderSession>() }
        val orderSession = remember(accountKey, symbol, executionSymbol) { orderSessions.getOrPut("$symbol/$executionSymbol") { MobileOrderSession(instrument) } }
        var draft by orderSession.draft
        var showDraft by remember(accountKey, symbol) { mutableStateOf(false) }
        var selectedPositionId by remember(accountKey) { mutableStateOf<String?>(null) }
        var focusedTrade by remember(accountKey) { mutableStateOf<ClosedTrade?>(null) }
        var focusedExecution by remember(accountKey) { mutableStateOf<Execution?>(null) }
        var protection by remember(accountKey) { mutableStateOf<ProtectionEdit?>(null) }
        var pendingEdit by remember(accountKey) { mutableStateOf<PendingEntryEdit?>(null) }
        var pendingProtection by remember(accountKey) { mutableStateOf<PendingProtectionEdit?>(null) }
        var confirmation by remember(accountKey, symbol, executionSymbol) { mutableStateOf<Confirmation?>(null) }
        var mobileTab by remember { mutableStateOf(1) }
        var showSettings by remember { mutableStateOf(false) }
        var showActivity by remember(accountKey) { mutableStateOf(false) }
        var showOrder by remember { mutableStateOf(false) }
        val orderFlowSupported = plugin.orderFlow != null
        var showOrderFlow by remember(plugin) { mutableStateOf(false) }
        var orderPanelState by orderSession.panelState
        val orderOpen = orderPanelState == MobileOrderPanelState.EDITING
        val orderActive = orderPanelState != MobileOrderPanelState.CLOSED
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
        val tradingPreferences by runtime.tradingPreferences.preferences.collectAsState()
        val skipConfirmation = tradingPreferences.skipConfirmation
        val chartPreferences by runtime.chartPreferences.preferences.collectAsState()
        val intervals = IntervalOptions(chartPreferences.favorites, chartPreferences.custom,
            onToggleFavorite = { frame -> scope.launch { runtime.chartPreferences.toggleFavorite(frame) } },
            onAddCustom = { frame -> scope.launch { runtime.chartPreferences.addCustom(frame) } })
        val request = draft.request(instrument)
        val (capacity, refreshCapacity) = rememberQuantityCapacity(runtime.orderCapacity, plugin.descriptor.id, session, instrument, draft, account,
            market.tradingReady, localDemo)
        val preview = remember(session, instrument, request, market.revision, account, capacity) {
            request?.let(session::preview)?.withCapacity(request, capacity)
        }
        val candles = remember(plugin, symbol, timeframe, market.revision) { plugin.history(symbol, timeframe) }
        val indicatorSettings by runtime.indicators.settings.collectAsState()
        val visiblePositions = account.positions.filter { it.symbol == executionSymbol }
        val visibleTrades = account.trades.filter { it.symbol == executionSymbol }
        val visibleOrders = account.orders.filter { it.request.symbol == executionSymbol }

        LaunchedEffect(runtime) { runtime.run() }
        LaunchedEffect(plugin, symbol, executionSymbol, timeframe) { plugin.watch(symbol, timeframe) }
        LaunchedEffect(plugin, symbol, executionSymbol, showOrderFlow) { plugin.watchOrderFlow(symbol.takeIf { showOrderFlow }) }
        DisposableEffect(plugin) { onDispose { plugin.watchOrderFlow(null) } }
        LaunchedEffect(symbol, executionSymbol) {
            if (previousExecutionSymbol != executionSymbol) {
                selectedPositionId = null; focusedTrade = null; focusedExecution = null
                protection = null; pendingEdit = null; pendingProtection = null
                previousExecutionSymbol = executionSymbol
            }
        }
        LaunchedEffect(accountKey) { showOrder = false; notice = null }
        LaunchedEffect(plugin, catalog.instruments.map { it.symbol }) {
            val saved = restoring
            val target = if (saved != null && catalog.instruments.any { it.symbol == saved }) saved.also { restoring = null } else symbol
            if (chosenSymbol != target) {
                chosenSymbol = target
                selectedPositionId = null; focusedTrade = null; focusedExecution = null; protection = null; pendingEdit = null; pendingProtection = null; confirmation = null
            }
        }
        LaunchedEffect(plugin, catalog.instruments.isEmpty()) { runtime.marketWatch.initialize(plugin.descriptor.id, catalog.instruments, symbol) }
        // A saved remote symbol may need a server search; give up once the search cannot find it.
        LaunchedEffect(plugin, restoring, market.connected) {
            val saved = restoring ?: return@LaunchedEffect
            if (!market.connected || catalog.instruments.any { it.symbol == saved }) return@LaunchedEffect
            try { withTimeoutOrNull(20_000) { plugin.searchInstruments(saved) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Still missing: fall through. */ }
            if (catalog.instruments.none { it.symbol == saved }) restoring = null
        }
        // Saved on every change, so the chart reopens where the user left it however the app exits.
        LaunchedEffect(plugin, symbol, timeframe, restoring) {
            runtime.chartPreferences.setLastChart(plugin.descriptor.id, symbol.takeIf { restoring == null }, timeframe)
        }

        fun showSymbol(value: String) {
            chosenSymbol = value
            restoring = null
        }
        /** The continuous chart of a contract, while one trades it or last did. A dated chart has only that contract's own, thinner history. */
        fun continuousChart(contract: String) = catalog.instruments.firstOrNull { it.isContinuous && it.priceSymbol == contract }?.symbol
        fun showContract(contract: String) {
            if (contract != executionSymbol) showSymbol(continuousChart(contract) ?: contract)
        }
        // A chart opens by hand only from the watch list, so a dated chart outside it came from a saved list click or the start-up fallback.
        val replacement = continuousChart(symbol).takeIf { restoring == null && !instrument.isContinuous && symbol !in watchSymbols }
        LaunchedEffect(replacement) { replacement?.let(::showSymbol) }
        fun chooseSymbol(value: String) {
            showSymbol(value)
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
        fun editProtection(position: Position, sl: Long? = position.stopLoss, tp: Long? = position.takeProfit, fromChart: Boolean = false) {
            if (!plugin.capabilities.modifyProtection || !position.protectionEditable || busy) return
            val spec = plugin.instruments.firstOrNull { it.symbol == position.symbol }
                ?: run { showNotice("品种或账户已切换，请重新选择仓位", attention = true); return }
            if (fromChart && skipConfirmation) {
                val current = session.state.value.positions.firstOrNull { it.id == position.id }
                val error = when {
                    current == null || current.version != position.version || current.stopLoss != position.stopLoss || current.takeProfit != position.takeProfit -> "仓位或止损/止盈已更新，请重新选择"
                    listOfNotNull(sl, tp).any { it <= 0 || it % spec.tickSize != 0L } -> "价格须大于零并符合最小变动单位"
                    else -> protectionEditError(plugin.capabilities, current, sl, tp)
                }
                if (error != null) { showNotice(error, attention = true); return }
                perform { session.modifyProtection(requestId(), position.id, position.version, sl, tp) }
                return
            }
            showContract(position.symbol)
            selectedPositionId = position.id
            pendingProtection = null
            protection = ProtectionEdit(position, sl?.let(spec::formatPrice) ?: "", tp?.let(spec::formatPrice) ?: "")
        }

        /** Opens the confirmation for a pending entry's stop loss / take profit, prefilled with [sl] and [tp]. */
        fun editPendingProtection(order: PendingOrder, sl: Long? = order.request.stopLoss, tp: Long? = order.request.takeProfit, fromChart: Boolean = false) {
            if (busy) return
            val current = session.state.value.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || current.request != order.request || current.role != OrderRole.ENTRY ||
                !current.editable || !current.protectionEditable || !plugin.capabilities.modifyPendingProtection) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            val spec = catalog.instruments.firstOrNull { it.symbol == current.request.symbol } ?: return
            if (fromChart && skipConfirmation) {
                val error = pendingProtectionEditError(plugin.capabilities, current, sl, tp)
                    ?: if (listOfNotNull(sl, tp).any { it <= 0 || it % spec.tickSize != 0L }) "价格须大于零并符合最小变动单位"
                    else session.previewOrderProtection(current.id, sl, tp).errors.firstOrNull()
                if (error != null) { showNotice(error, attention = true); return }
                perform { session.modifyOrderProtection(requestId(), current.id, current.version, sl, tp) }
                return
            }
            showContract(current.request.symbol)
            protection = null
            pendingEdit = null
            showDraft = false
            pendingProtection = PendingProtectionEdit(current, sl?.let(spec::formatPrice) ?: "", tp?.let(spec::formatPrice) ?: "")
        }

        fun editPending(order: PendingOrder, price: Long = order.request.entryPrice!!, fromChart: Boolean = false) {
            if (busy) return
            val current = session.state.value.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || current.role != order.role || current.request != order.request) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            if (!current.editable) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            if (current.isProtection) {
                val position = session.state.value.positions.firstOrNull { it.id == current.positionId && it.protectionEditable }
                if (position == null) { showNotice("保护单尚未唯一关联到当前仓位", attention = true); return }
                pendingEdit = null
                editProtection(position, if (current.role == OrderRole.STOP_LOSS) price else position.stopLoss,
                    if (current.role == OrderRole.TAKE_PROFIT) price else position.takeProfit, fromChart)
                return
            }
            if (current.role != OrderRole.ENTRY || !plugin.capabilities.modifyPendingOrders) return
            if (fromChart && skipConfirmation) {
                val estimate = session.previewOrderModification(current.id, price)
                if (!estimate.valid) { showNotice(estimate.errors.firstOrNull() ?: "改单价格无效", attention = true); return }
                perform { session.modifyOrder(requestId(), current.id, current.version, price) }
                return
            }
            val spec = catalog.instruments.firstOrNull { it.symbol == current.request.symbol } ?: return
            showContract(current.request.symbol)
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
                // Compact windows wide enough for side navigation: landscape phones and portrait tablets.
                val rail = compact && maxWidth >= 600.dp
                val environment = plugin.descriptor.environment
                val syncAction: (() -> Unit)? = if (plugin.capabilities.resynchronize) ({ syncAccount() }) else null
                val barStatus: @Composable () -> Unit = {
                    EnvironmentBadge(environment)
                    if (localDemo) Spacer(Modifier.width(12.dp)) else ConnectionStatusControl(market, compact = true, busy, syncAction)
                }
                val selectTab: (Int) -> Unit = { tab ->
                    if (mobileTab != tab) showActivity = false
                    showOrderFlow = false
                    mobileTab = tab
                }
                // The persistent order sheet shares the chart surface and never dims it.
                val chartFirst = compact && mobileTab == 1 && !showOrderFlow
                val panelOpen = chartFirst && orderOpen
                fun closeOrder() {
                    focusManager.clearFocus()
                    orderPanelState = MobileOrderPanelState.CLOSED
                    showDraft = false
                    if (confirmation is Confirmation.Place) confirmation = null
                }
                fun executeTrade(action: Confirmation) {
                    if (busy) return
                    when (action) {
                        is Confirmation.Place -> {
                            val checked = session.preview(action.request).withCapacity(action.request, capacity)
                            if (!checked.valid) { showNotice(checked.errors.firstOrNull() ?: "订单参数无效", attention = true); return }
                            perform({ if (compact) closeOrder() }) { session.placeOrder(requestId(), action.request) }
                        }
                        is Confirmation.Close -> {
                            val current = session.state.value.positions.firstOrNull { it.id == action.position.id }
                            if (current == null || current.version != action.position.version) {
                                showNotice("仓位已关闭或已更新，请重新选择", attention = true); return
                            }
                            perform { session.closePosition(requestId(), current.id) }
                        }
                        is Confirmation.Cancel -> {
                            val current = session.state.value.orders.firstOrNull { it.id == action.order.id }
                            if (current == null || current.version != action.order.version || current.request != action.order.request) {
                                showNotice("挂单已成交、已撤销或已更新，请重新选择", attention = true); return
                            }
                            perform { session.cancelOrder(requestId(), current.id) }
                        }
                        Confirmation.Reset -> perform { runtime.resetAll(); TradeResult(true, "已重置全部本地模拟数据") }
                    }
                }
                fun reviewTrade(action: Confirmation) {
                    if (busy) return
                    if (skipConfirmation && action !is Confirmation.Reset) executeTrade(action)
                    else confirmation = action
                }
                fun reviewMobileOrder() {
                    if (!busy && orderPanelState != MobileOrderPanelState.CLOSED && preview?.valid == true) request?.let { reviewTrade(Confirmation.Place(it)) }
                }
                /** A button on an order line: the same paths as the trade list, so confirmations and version checks apply. */
                fun runLineAction(action: LineAction) {
                    when (action) {
                        is LineAction.ClosePosition -> reviewTrade(Confirmation.Close(action.position))
                        is LineAction.CancelOrder -> reviewTrade(Confirmation.Cancel(action.order))
                        is LineAction.RemoveProtection -> action.position.let { p ->
                            editProtection(p, p.stopLoss.takeUnless { action.stop }, p.takeProfit.takeIf { action.stop }, fromChart = true)
                        }
                        is LineAction.RemovePendingProtection -> action.order.request.let { r ->
                            editPendingProtection(action.order, r.stopLoss.takeUnless { action.stop }, r.takeProfit.takeIf { action.stop }, fromChart = true)
                        }
                        is LineAction.RemoveDraftProtection -> changeDraft(draft.withProtectionEnabled(instrument, quote, action.stop, false))
                        LineAction.CloseDraft -> if (compact) closeOrder() else showDraft = false
                    }
                }
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && mobileTab == 3 && showActivity, onBackCompleted = { showActivity = false })
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && showOrderFlow, onBackCompleted = { showOrderFlow = false })
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
                        onPendingEntry = { order, price -> editPending(order, price, fromChart = true) },
                        editPendingProtection = plugin.capabilities.modifyPendingProtection,
                        estimatePendingProtection = session::previewOrderProtection,
                        onPendingProtection = { order, sl, tp -> editPendingProtection(order, sl, tp, fromChart = true) },
                        onProtection = { p, sl, tp -> editProtection(p, sl, tp, fromChart = true) },
                        onLineAction = { runLineAction(it) },
                        addProtection = plugin.capabilities.addProtection,
                        removeProtection = plugin.capabilities.removeProtection,
                    )
                    val drawings = runtime.drawings
                    Row(m) {
                    if (!compact || drawings.toolbarOpen) com.unitrading.chart.drawing.DrawingToolbar(drawings, indicatorSettings.indicators.size, { scope.launch { runtime.indicators.apply(com.unitrading.app.IndicatorSettings()) } })
                    TradingChart(accountKey, instrument, quote, candles, timeframe, { timeframe = it }, visiblePositions, visibleTrades,
                        selectedPositionId, focusedTrade, request.takeIf { draftShown }, preview.takeIf { draftShown }, visibleOrders, actions,
                        Modifier.weight(1f).fillMaxHeight(), environment = plugin.descriptor.environment, executions = account.executions.filter { it.symbol == executionSymbol }, indicators = if (drawings.hideIndicators) emptyList() else indicatorSettings.indicators, onIndicators = { showIndicators = true }, focusedExecution = focusedExecution,
                        instruments = watchInstruments, onSymbol = ::chooseSymbol, intervals = intervals, connected = market.connected,
                        indicatorPaneFractions = chartPreferences.indicatorPaneFractions,
                        onIndicatorPaneFractions = { fractions -> scope.launch { runtime.chartPreferences.setIndicatorPaneFractions(fractions) } },
                        onSide = { side ->
                            val base = if (compact && orderPanelState == MobileOrderPanelState.CLOSED)
                                OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10") else draft
                            changeDraft(base.withSide(side, instrument, quote))
                            when {
                                compact -> orderPanelState = MobileOrderPanelState.EDITING
                                !wide -> showOrder = true
                                // The form is what this button is for, so a folded order panel opens.
                                !runtime.desktopLayout.preferences.value.orderOpen -> {
                                    runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true)
                                    scope.launch { runtime.desktopLayout.save() }
                                }
                            }
                        },
                        // The legend's fold is remembered once the user toggles it; until then short phone screens start folded.
                        chrome = ChartChrome(compact, chartPreferences.legendCollapsed ?: compactShort,
                            { folded -> scope.launch { runtime.chartPreferences.setLegendCollapsed(folded) } },
                            ordering = compact && orderOpen, drawings = drawings, toolbarEnd = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (compact && orderFlowSupported) ActionIcon(AppIcons.TrendingUp, "订单流分析", "open-order-flow") {
                                        closeOrder(); showOrderFlow = true
                                    }
                                    barStatus()
                                }
                            }))
                    }
                }
                Row(Modifier.fillMaxSize()) {
                if (rail) MobileNavigationRail(mobileTab, selectTab)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (desktop) {
                        Row(Modifier.fillMaxWidth().height(48.dp).testTag("app-toolbar").padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLogo(24.dp)
                            Text("UniTrading", style = MaterialTheme.typography.titleMedium)
                            EnvironmentBadge(environment)
                            Text(session.account.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!localDemo) ConnectionStatusControl(market, compact = false, busy, syncAction)
                            if (localDemo) ActionIcon(if (paused) AppIcons.PlayArrow else AppIcons.Pause, if (paused) "继续行情" else "暂停行情", "toggle-demo-feed") { runtime.paused.value = !paused }
                            if (orderFlowSupported) TextButton({ showOrderFlow = !showOrderFlow }, Modifier.testTag("open-order-flow")) {
                                Text(if (showOrderFlow) "返回工作区" else "订单流")
                            }
                            ActionIcon(AppIcons.ViewQuilt, "重置布局", "reset-desktop-layout") { runtime.desktopLayout.reset(); scope.launch { runtime.desktopLayout.save() } }
                            ActionIcon(AppIcons.Settings, "设置", "open-settings") { showActivity = false; showSettings = true }
                            if (!wide) Button({ showOrder = true; showDraft = true }, Modifier.testTag("open-order")) { Text("下单") }
                        }
                        DividerLine()
                    }
                    if (showOrderFlow) {
                        if (desktop && wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                            chart(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider()
                            OrderFlowPanel(plugin, instrument, timeframe, Modifier.weight(1f).fillMaxHeight()) { showOrderFlow = false }
                        } else OrderFlowPanel(plugin, instrument, timeframe, Modifier.weight(1f).fillMaxWidth()) { showOrderFlow = false }
                    } else if (desktop) {
                        DesktopWorkspace(runtime.desktopLayout, wide, Modifier.weight(1f).fillMaxWidth(),
                            accounts = {
                                BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount)
                            },
                            market = { m -> MarketWatch(plugin, market, account, symbol, ::chooseSymbol, m, runtime.marketWatch) },
                            chart = chart,
                            order = { m, minimize -> OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                { request?.let { reviewTrade(Confirmation.Place(it)) } }, m.background(colors.Shell), environment = environment,
                                capacity = capacity, onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation,
                                onClose = minimize, closeLabel = "收起下单面板", closeTag = "desktop-order-minimize") },
                            terminal = { m ->
                                TradeTerminal(plugin, account, market, selectedPositionId, false,
                                    onSelectPosition = { p -> focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null },
                                    onSelectTrade = { t -> focusedExecution = null; showContract(t.symbol); focusedTrade = t; selectedPositionId = null },
                                    onClose = { reviewTrade(Confirmation.Close(it)) }, onProtect = { editProtection(it) }, onCancel = { reviewTrade(Confirmation.Cancel(it)) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = m, onProtectOrder = { editPendingProtection(it) })
                            }, terminalSummary = "持仓 ${account.positions.size} · 挂单 ${terminalOrders(account).rows.size}")
                    } else {
                        when (mobileTab) {
                            0 -> MarketWatch(plugin, market, account, symbol, ::chooseSymbol, Modifier.weight(1f), runtime.marketWatch, session.account.name) { barStatus() }
                            1 -> key(orderSession) {
                                // The sheet keeps a full-height host while the chart dock changes continuously.
                                val idleDock = if (rail) MobileTradeBarHeight else MobileTradeBarHeight + MobileNavigationHeight
                                val dockHeight by animateDpAsState(if (orderActive) MobileOrderPeekHeight else idleDock,
                                    animationSpec = tween(250), label = "chart-order-dock")
                                MobileOrderSheet(instrument, quote, plugin.capabilities, draft, ::changeDraft,
                                    orderPanelState, { orderPanelState = it }, { closeOrder() }, ::reviewMobileOrder, preview?.valid == true && request != null,
                                    Modifier.weight(1f).fillMaxWidth(), busy = busy, skipConfirmation = skipConfirmation,
                                    onHidden = {
                                        if (orderPanelState == MobileOrderPanelState.CLOSED)
                                            draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                                    },
                                    content = {
                                        notice?.let { current ->
                                            Box(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                                                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                FloatingNoticeCard(current, "trade-notice", { if (notice?.id == current.id) notice = null }, title = "操作提示")
                                            }
                                        }
                                        OrderForm(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                            ::reviewMobileOrder, Modifier.weight(1f),
                                            environment = environment, capacity = capacity, onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation)
                                    },
                                    body = {
                                        Column(Modifier.fillMaxSize()) {
                                            chart(Modifier.weight(1f))
                                            Box(Modifier.fillMaxWidth().height(dockHeight).clipToBounds()) {
                                                androidx.compose.animation.AnimatedVisibility(!orderActive, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                                                    Column(Modifier.fillMaxWidth().requiredHeight(idleDock)) {
                                                        Box(Modifier.height(MobileTradeBarHeight)) {
                                                            MobileTradeBar(account, onOpen = {
                                                                draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                                                                orderPanelState = MobileOrderPanelState.EDITING
                                                            }, onPositions = { mobileTab = 2 })
                                                        }
                                                        if (!rail) MobileNavigation(mobileTab, selectTab)
                                                    }
                                                }
                                            }
                                        }
                                    })
                            }
                            2 -> Column(Modifier.weight(1f).background(colors.Surface)) {
                                PageTopBar("交易", session.account.name, actions = { barStatus() })
                                TradeTerminal(plugin, account, market, selectedPositionId, true,
                                    onSelectPosition = { p -> focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null; mobileTab = 1 },
                                    onSelectTrade = { t -> focusedExecution = null; showContract(t.symbol); focusedTrade = t; mobileTab = 1 },
                                    onClose = { reviewTrade(Confirmation.Close(it)) }, onProtect = { editProtection(it) }, onCancel = { reviewTrade(Confirmation.Cancel(it)) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = Modifier.weight(1f).fillMaxWidth(), onProtectOrder = { editPendingProtection(it) }, header = { AccountSummary(account, connected = market.connected) })
                            }
                            else -> if (showActivity) {
                                ActivityLogPage("${plugin.descriptor.name} · ${session.account.name}", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.weight(1f).fillMaxWidth())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.weight(1f).fillMaxWidth(), onConnectRithmic = connectRithmic, barActions = { barStatus() }, updates = updates)
                        }
                    }
                    if (compact && !chartFirst && !rail) MobileNavigation(mobileTab, selectTab)
                    else if (desktop) {
                        DividerLine()
                        AccountStrip(account, note = if (localDemo) "${if (paused) "模拟已暂停" else "模拟行情 ×15"} · 报价 ${clockLabel(market.simulatedTimeMs, true)} UTC · 无真实资金"
                            else "状态以服务器为准 · ${environment.label}", connected = market.connected)
                    }
                }
                }
                // Notifications occupy overlay space, never alter chart/panel constraints, and do not dim the workspace.
                Column(Modifier.align(if (desktop) Alignment.TopEnd else Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = if (desktop) 56.dp else if (keyboardOpen) 8.dp else 56.dp)
                    .widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight * .65f)
                    .verticalScroll(rememberScrollState()).testTag("notification-overlay"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    storageError?.let { message ->
                        FloatingNoticeCard(TradingNotice(0, message, NoticeTone.ATTENTION, persistent = true), "storage-notice", null,
                            title = "保存失败", action = { TextButton({ scope.launch { runtime.save() } }, Modifier.testTag("retry-storage-save")) { Text("重试保存") } })
                    }
                    notice?.takeUnless { panelOpen }?.let { current ->
                        FloatingNoticeCard(current, "trade-notice", { if (notice?.id == current.id) notice = null }, title = "操作提示")
                    }
                    loginMessage?.let { message -> key(message) {
                        var dismissed by remember { mutableStateOf(false) }
                        if (!dismissed) FloatingNoticeCard(TradingNotice(0, message, persistent = true), "saved-login-status", { dismissed = true }, title = "登录状态")
                    } }
                }
                if (desktop && showSettings) {
                    Dialog(onDismissRequest = { if (showActivity) showActivity = false else showSettings = false }) {
                        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.extraLarge, color = colors.Shell) {
                            if (showActivity) {
                                ActivityLogPage("${plugin.descriptor.name} · ${session.account.name}", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.fillMaxSize())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.fillMaxSize(), onClose = { showSettings = false }, onConnectRithmic = connectRithmic, updates = updates)
                        }
                    }
                }
                if (showOrder) {
                    Dialog(onDismissRequest = { if (!busy) showOrder = false }) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, {
                                request?.let { reviewTrade(Confirmation.Place(it)); showOrder = false }
                            }, Modifier.widthIn(max = 420.dp).fillMaxHeight(.94f).imePadding(), environment = environment, capacity = capacity,
                                onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation, onClose = { showOrder = false })
                        }
                    }
                }

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
                    val confirmLabel = when (action) { is Confirmation.Place -> action.request.side.label(); is Confirmation.Close -> "平仓"; is Confirmation.Cancel -> "撤单"; Confirmation.Reset -> "重置" }
                    val confirmColor = when (action) {
                        is Confirmation.Place -> if (action.request.side == Side.BUY) colors.Up else colors.Down
                        else -> MaterialTheme.colorScheme.error
                    }
                    AlertDialog(
                        onDismissRequest = { if (!busy) confirmation = null },
                        title = { Text(title) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${plugin.descriptor.name} / ${session.account.name} / ${environment.label}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            when (action) {
                                is Confirmation.Place -> {
                                    val r = action.request
                                    r.sourceSymbol?.let { Text("图表 / 指标 $it → 下单 ${r.symbol}", color = colors.Accent, style = MaterialTheme.typography.bodySmall) }
                                    Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}", style = MaterialTheme.typography.titleSmall)
                                    EstimateRow("当前预估入场", livePreview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                                    EstimateRow("预计保证金 USD", livePreview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                                    Text("提交时会按最新报价和账户状态重新校验，实际成交价可能与预估不同。", style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                                    livePreview?.errors?.forEach { Text(it, color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                                }
                                is Confirmation.Close -> Text("平掉 ${action.position.symbol} 的 #${shortId(action.position.id)} 全部 ${catalog.instruments.first { it.symbol == action.position.symbol }.formatQuantity(action.position.lotsHundredths)} ${catalog.instruments.first { it.symbol == action.position.symbol }.quantityUnit}，按当前可成交报价执行。")
                                is Confirmation.Cancel -> Text("撤销${action.order.role.label()} #${shortId(action.order.id)}。" +
                                    if (action.order.isProtection) "撤单后将移除这项保护，服务器也可能调整关联订单。" else "若已被触发，交易商将拒绝撤单。")
                                Confirmation.Reset -> Text("将覆盖两个交易商的全部本地模拟余额、仓位、挂单和历史，并重新生成演示交易。此操作不能撤销。")
                            }
                        } },
                        confirmButton = { Button({
                            confirmation = null
                            executeTrade(action)
                        }, enabled = !busy && (livePreview == null || livePreview.valid), modifier = Modifier.testTag("confirm-action"),
                            colors = ButtonDefaults.buttonColors(containerColor = confirmColor, contentColor = contentOn(confirmColor))) { Text(confirmLabel) } },
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
                            Text("${order.request.symbol} · ${order.request.side.label()}${order.request.kind.label()} · ${spec.formatQuantity(order.request.lotsHundredths)} ${spec.quantityUnit}", style = MaterialTheme.typography.titleSmall)
                            Text("原入场价 ${spec.formatPrice(order.request.entryPrice!!)}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            PriceInput("新入场价", edit.entry, { pendingEdit = edit.copy(entry = it) }, "pending-entry")
                            EstimateRow("预计保证金", estimate?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} USD" } ?: "—")
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", estimate?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", estimate?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            Text("本次只改入场价，订单类型、数量和 SL/TP 不变。确认后会按最新报价重新校验。", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            if (!validVersion) Text("挂单已成交、已撤销或版本已更新，请取消后重新选择。", color = colors.Loss, style = MaterialTheme.typography.bodySmall)
                            if (price == null) Text("请输入符合品种精度的有效价格。", color = colors.Loss, style = MaterialTheme.typography.bodySmall)
                            estimate?.errors?.forEach { Text(it, color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                        } },
                        confirmButton = { Button({
                            val newPrice = price ?: return@Button
                            pendingEdit = null
                            perform { session.modifyOrder(requestId(), order.id, order.version, newPrice) }
                        }, Modifier.testTag("confirm-pending-entry"), enabled = !busy && validVersion && estimate?.valid == true) { Text(if (skipConfirmation) "应用改价" else "确认改价") } },
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
                        onDismiss = { if (!busy) pendingProtection = null }, skipConfirmation = skipConfirmation)
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
                            Text("${p.symbol} · ${p.side.label()} ${spec.formatQuantity(p.lotsHundredths)} ${spec.quantityUnit} · #${shortId(p.id)}", style = MaterialTheme.typography.titleSmall)
                            Text("原 SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            PriceInput(if (plugin.capabilities.removeProtection) "新止损 SL（留空移除）" else "新止损 SL（保留原价或修改）", edit.stopLoss, { protection = edit.copy(stopLoss = it) }, "protect-sl")
                            PriceInput(if (plugin.capabilities.removeProtection) "新止盈 TP（留空移除）" else "新止盈 TP（保留原价或修改）", edit.takeProfit, { protection = edit.copy(takeProfit = it) }, "protect-tp")
                            if (!plugin.capabilities.atomicProtectionEdit) Text("每次只能修改一条保护线。请等服务器确认后再修改另一条。", style = MaterialTheme.typography.bodySmall)
                            capabilityError?.let { Text(it, color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", estimate(sl))
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", estimate(tp))
                            Text("当前显示预估结果。确认后由交易商校验并更新，跳空可能使实际结果与预估不同。", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            if (!validVersion) Text("仓位已关闭或止损/止盈已更新，请取消后重新选择。", color = colors.Loss, style = MaterialTheme.typography.bodySmall)
                            if (!validInput) Text("价格须大于零，并符合该品种的最小变动单位。", color = colors.Loss, style = MaterialTheme.typography.bodySmall)
                        } },
                        confirmButton = { Button({
                            protection = null
                            perform { session.modifyProtection(requestId(), p.id, p.version, sl, tp) }
                        }, Modifier.testTag("confirm-protection"), enabled = validInput && validVersion && capabilityError == null && !busy) { Text(if (skipConfirmation) "应用修改" else "确认修改") } },
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
