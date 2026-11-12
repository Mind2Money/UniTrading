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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    LaunchedEffect(runtime, player) {
        val enabled = runtime.tradingPreferences.preferences.map { it.tradeSounds }.stateIn(this)
        TradeSoundController(player, enabled = enabled).run(runtime.registry)
    }
    LaunchedEffect(runtime) { runtime.accountRisk.monitor(runtime.registry) }
    val theme by runtime.theme.settings.collectAsState()
    val aliases by runtime.accountAliases.aliases.collectAsState()
    CompositionLocalProvider(LocalAccountAliases provides aliases) {
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
                    PageTopBar(aliases.login(plugin.descriptor), feed.statusMessage, actions = {
                        EnvironmentBadge(plugin.descriptor.environment)
                        ActionIcon(AppIcons.Palette, "外观与主题", "open-theme") { showTheme = true }
                    })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 560.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("账户和合约由交易商提供。列表为空时，应用不会用本地数据填充。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.Muted)
                        BrokerAccountChooser(registry, plugin, "", ::selectBroker, {})
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("搜索服务器合约，如 MES") }, singleLine = true)
                            Button({ emptyScope.launch { try { plugin.searchInstruments(query) } catch (_: Exception) { searchError = "合约搜索失败，请检查连接和权限" } } }, enabled = feed.connected, colors = brandButtonColors()) { Text("查询合约") }
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
        val session = remember(plugin, accountId) { runtime.accountRisk.guard(plugin, plugin.openSession(accountId)) }
        DisposableEffect(plugin, session) {
            runtime.livePnl.track(plugin, session)
            onDispose { runtime.livePnl.untrack(session) }
        }
        val account by key(session) { session.state.collectAsState() }
        val analyticsTrades = if (localDemo) account.trades else {
            val cached by runtime.analyticsCache.rows.collectAsState()
            val cachedTrades = cached[plugin.descriptor.id]?.get(accountId).orEmpty()
            LaunchedEffect(session) {
                session.state.map { it.trades }.distinctUntilChanged().collect {
                    runtime.analyticsCache.update(plugin.descriptor.id, accountId, it)
                }
            }
            remember(cachedTrades, account.trades) { mergeAnalyticsTrades(cachedTrades, account.trades) }
        }
        val loginName = aliases.login(plugin.descriptor)
        val accountName = aliases.account(plugin.descriptor.id, session.account)
        val market by key(plugin) { plugin.market.collectAsState() }
        val riskRules by runtime.accountRisk.rules.collectAsState()
        val riskRule = riskRules[plugin.descriptor.id]?.get(accountId)
        val brackets = riskRule?.brackets ?: AccountBrackets()
        val sizeByRisk = brackets.sizingRisk != null
        // Resetting an unreadable rule file leaves the rules as empty as they were, so the notice follows the file's own state too.
        val riskUnreadable by runtime.accountRisk.storageUnreadable.collectAsState()
        val riskMessage = key(riskUnreadable) { runtime.accountRisk.restriction(plugin, session) }
        val riskTrigger = runtime.accountRisk.lockingTrigger(plugin, session)
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
        // The main chart's interval outlives a change of broker, whichever chart was being worked on then.
        val mainTimeframe = remember { arrayOf(savedChart.lastTimeframe ?: Timeframe.M5) }
        var timeframe by remember(plugin) { mutableStateOf(mainTimeframe[0]) }
        /**
         * The chart the user last worked in is the active chart: [chosenSymbol] and [timeframe] are its symbol and interval,
         * and the order panel, the market list's selection and the order flow follow it. Every other chart window waits in
         * [parkedCharts] with its own symbol and interval until a press makes it the active one, when the two trade places.
         */
        var activeChart by remember(plugin) { mutableStateOf(MAIN_CHART) }
        var parkedCharts by remember(plugin) {
            mutableStateOf(savedChart.extraCharts.associate { it.id to ChartSlot(it.id, it.symbols[plugin.descriptor.id] ?: chosenSymbol, Timeframe.ofSeconds(it.timeframeSeconds)) })
        }
        /** The chart windows beside the main chart, in the order they were opened. */
        var chartOrder by remember(plugin) { mutableStateOf(savedChart.extraCharts.map { it.id }) }
        // The chart's symbol menu lists the market page's watch list, in its order.
        val watchLists by runtime.marketWatch.lists.collectAsState()
        val watchSymbols = watchLists[plugin.descriptor.id].orEmpty().toSet()
        // Keep loading and verifying the saved list while mobile navigation shows another page. A chart window's symbol is
        // loaded, verified and quoted the same way, so its chart is complete before it is the one being worked on.
        val chartSymbols = parkedCharts.values.mapTo(mutableSetOf()) { it.symbol }
        LaunchedEffect(plugin, watchSymbols, chartSymbols) { plugin.watchQuotes(watchSymbols + chartSymbols) }
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
        var standaloneEdit by remember(accountKey) { mutableStateOf<StandaloneOrderEdit?>(null) }
        var pendingProtection by remember(accountKey) { mutableStateOf<PendingProtectionEdit?>(null) }
        var confirmation by remember(accountKey, symbol, executionSymbol) { mutableStateOf<Confirmation?>(null) }
        var mobileTab by remember { mutableStateOf(1) }
        var showOverview by remember { mutableStateOf(false) }
        var accountTab by remember { mutableStateOf(0) }
        var showSettings by remember { mutableStateOf(false) }
        var showActivity by remember(accountKey) { mutableStateOf(false) }
        var showOrder by remember { mutableStateOf(false) }
        val orderFlowSupported = plugin.orderFlow != null
        var showOrderFlow by remember(plugin) { mutableStateOf(false) }
        var orderPanelState by orderSession.panelState
        // The docked order bar's "更多设置" fold. It opens at once and is saved, so the next order and the next start find it as it was left.
        var advancedOrder by remember(runtime) { mutableStateOf(runtime.tradingPreferences.preferences.value.orderMoreSettings) }
        val orderOpen = orderPanelState == MobileOrderPanelState.EDITING
        val orderActive = orderPanelState != MobileOrderPanelState.CLOSED
        var busy by remember { mutableStateOf(false) }
        var flattenReview by remember { mutableStateOf<FlattenReview?>(null) }
        var flattenResults by remember { mutableStateOf<List<FlattenOutcome>?>(null) }
        var flattening by remember { mutableStateOf(false) }
        /** The window the user last worked in: null for the main window, else the key of a window that holds part of the workspace. */
        var activeWindow by remember { mutableStateOf<String?>(null) }
        val liveWindows = remember { mutableStateListOf<String>() }
        val panelWindows = LocalPanelWindowHost.current != null
        /** Whether the order ticket has a window of its own, where it fits whatever the main window's width. */
        fun orderDetached() = panelWindows && WorkspacePanel.ORDER in runtime.desktopLayout.preferences.value.detached
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
        // The watch list's history is read ahead and cached, so a chart that opens only fetches the bars since.
        val preload = remember(watchLists[plugin.descriptor.id], symbol, timeframe, chartPreferences.favorites) {
            historyPreloadSeries(watchLists[plugin.descriptor.id].orEmpty(), symbol, timeframe, chartPreferences.favorites)
        }
        LaunchedEffect(plugin, preload) { plugin.preloadHistory(preload) }
        DisposableEffect(plugin) { onDispose { plugin.preloadHistory(emptyList()) } }
        val orderInput = draft.copy(stopLossEnabled = draft.stopLossEnabled && !brackets.controls(true),
            takeProfitEnabled = draft.takeProfitEnabled && !brackets.controls(false))
        val builtOrder = runCatching {
            val input = if (sizeByRisk) {
                require(instrument.parsePrice(orderInput.stopLoss)?.let { it > 0 } == true) { "请输入有效止损价 SL" }
                orderInput.copy(stopLossEnabled = true).request(instrument, quantityOverride = instrument.minLotsHundredths)
            } else orderInput.request(instrument)
            input?.let { brackets.prices(it, instrument, session.account.currency, quote) }
        }
        val request = builtOrder.getOrNull()
        // Keep an editable chart draft even before SL sets a valid quantity. Only request can be submitted.
        val chartDraft = request ?: if (sizeByRisk) orderInput.copy(
            stopLossEnabled = instrument.parsePrice(orderInput.stopLoss)?.let { it > 0 } == true,
            takeProfitEnabled = orderInput.takeProfitEnabled && instrument.parsePrice(orderInput.takeProfit)?.let { it > 0 } == true,
        ).request(instrument, quantityOverride = 0L) else null
        val bracketError = builtOrder.exceptionOrNull()?.message
        val (capacity, refreshCapacity) = rememberQuantityCapacity(runtime.orderCapacity, plugin.descriptor.id, session, instrument, draft, account,
            market.tradingReady, localDemo)
        val preview = remember(session, instrument, request, market.revision, account, capacity, bracketError) {
            request?.let(session::preview)?.withCapacity(request, capacity)
                ?: bracketError?.let { OrderPreview(null, errors = listOf(it)) }
        }
        /**
         * The quantity limit [order] is checked against. The order panel's limit belongs to its own side; an order on the
         * other side, which only the chart's context menu creates, takes the last limit the server gave for that side, or none.
         */
        fun capacityFor(order: OrderRequest): QuantityCapacityState = if (localDemo || order.side == draft.side) capacity else
            runtime.orderCapacity.entries.value[OrderCapacityCache.key(plugin.descriptor.id, session.account.id, order.symbol, order.side)]
                ?.let { QuantityCapacityState(it.maximum, it.message) } ?: QuantityCapacityState()
        val indicatorSettings by runtime.indicators.settings.collectAsState()
        val visiblePositions = account.positions.filter { it.symbol == executionSymbol }
        val visibleTrades = account.trades.filter { it.symbol == executionSymbol }
        val visibleOrders = account.orders.filter { it.request.symbol == executionSymbol }

        LaunchedEffect(runtime) { runtime.run() }
        // Every chart window stays live; the broker serves the one being worked on first.
        val parkedSeries = parkedCharts.values.map { it.symbol to it.timeframe }
        LaunchedEffect(plugin, symbol, executionSymbol, timeframe, parkedSeries) { plugin.watchAll((listOf(symbol to timeframe) + parkedSeries).distinct()) }
        LaunchedEffect(plugin, symbol, executionSymbol, showOrderFlow) { plugin.watchOrderFlow(symbol.takeIf { showOrderFlow }) }
        DisposableEffect(plugin) { onDispose { plugin.watchOrderFlow(null) } }
        LaunchedEffect(symbol, executionSymbol) {
            if (previousExecutionSymbol != executionSymbol) {
                selectedPositionId = null; focusedTrade = null; focusedExecution = null
                protection = null; pendingEdit = null; pendingProtection = null; standaloneEdit = null
                previousExecutionSymbol = executionSymbol
            }
        }
        LaunchedEffect(accountKey) { showOrder = false; notice = null }
        LaunchedEffect(plugin, catalog.instruments.map { it.symbol }) {
            val saved = restoring
            val target = if (saved != null && catalog.instruments.any { it.symbol == saved }) saved.also { restoring = null } else symbol
            if (chosenSymbol != target) {
                chosenSymbol = target
                selectedPositionId = null; focusedTrade = null; focusedExecution = null; protection = null; pendingEdit = null; pendingProtection = null; standaloneEdit = null; confirmation = null
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
        // Each chart reopens with what it showed, whichever of them was the active one at the end.
        fun chartSlot(id: String): ChartSlot? = if (id == activeChart) ChartSlot(id, restoring ?: symbol, timeframe) else parkedCharts[id]
        val mainChart = chartSlot(MAIN_CHART) ?: ChartSlot(MAIN_CHART, symbol, timeframe)
        SideEffect { mainTimeframe[0] = mainChart.timeframe }
        // A symbol still being restored is the saved one itself, so saving it changes nothing until it loads or is given up.
        LaunchedEffect(plugin, mainChart) { runtime.chartPreferences.setLastChart(plugin.descriptor.id, mainChart.symbol, mainChart.timeframe) }
        val extraCharts = chartOrder.mapNotNull(::chartSlot)
        LaunchedEffect(plugin, extraCharts) {
            runtime.chartPreferences.setExtraCharts(plugin.descriptor.id, extraCharts.map { Triple(it.id, it.symbol, it.timeframe) })
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
            standaloneEdit = null
            confirmation = null
            mobileTab = 1
        }
        /** Makes [id] the chart the screen works on. It trades places with the active chart; each keeps its symbol and interval. */
        fun activateChart(id: String) {
            if (id == activeChart) return
            val target = parkedCharts[id] ?: return
            // The chart being left keeps what it was asked to show, also while that symbol is still being loaded.
            parkedCharts = parkedCharts - id + (activeChart to ChartSlot(activeChart, restoring ?: chosenSymbol, timeframe))
            activeChart = id
            if (target.symbol != chosenSymbol) {
                focusedTrade = null; focusedExecution = null; selectedPositionId = null
                protection = null; pendingEdit = null; pendingProtection = null; standaloneEdit = null; confirmation = null
            }
            // A symbol the broker has not loaded yet waits the way the saved chart does at start, and is not saved over meanwhile.
            if (catalog.instruments.any { it.symbol == target.symbol }) showSymbol(target.symbol) else restoring = target.symbol
            timeframe = target.timeframe
        }
        /** Opens one more chart window, on the symbol and interval of the chart being worked on. */
        fun openChart() {
            if (chartOrder.size >= ChartPreferences.MAX_EXTRA_CHARTS) { showNotice("最多同时打开 ${ChartPreferences.MAX_EXTRA_CHARTS} 个图表窗口", attention = true); return }
            val id = generateSequence(1) { it + 1 }.map { "c$it" }.first { it !in chartOrder }
            parkedCharts = parkedCharts + (id to ChartSlot(id, restoring ?: chosenSymbol, timeframe))
            chartOrder = chartOrder + id
        }
        /** Closes a chart window for good; the main chart takes over when it was the one being worked on. */
        fun closeChart(id: String) {
            if (id == activeChart) activateChart(MAIN_CHART)
            parkedCharts = parkedCharts - id
            chartOrder = chartOrder - id
            runtime.desktopLayout.setWindowBounds(chartWindowKey(id), null)
            scope.launch { runtime.desktopLayout.save() }
        }
        fun clearAccountEditors() {
            confirmation = null; protection = null; pendingEdit = null; pendingProtection = null; standaloneEdit = null
            focusedTrade = null; focusedExecution = null; selectedPositionId = null
            showOrder = false; showDraft = false; orderPanelState = MobileOrderPanelState.CLOSED
        }
        fun chooseBroker(value: String) {
            if (!busy && value != plugin.descriptor.id) { clearAccountEditors(); selectBroker(value) }
        }
        fun chooseAccount(value: String) {
            if (!busy && catalog.accounts.any { it.id == value }) {
                if (value != accountId) clearAccountEditors()
                manuallySelected = true
                chosenAccountId = value
                loginScope.launch { runtime.savedLogins.select(plugin.descriptor.id, value) }
            }
        }
        fun changeDraft(value: OrderDraft) {
            // Side/kind changes may seed reference protection for a manual ticket. Sizing uses the user's SL.
            draft = if (sizeByRisk && (value.side != draft.side || value.kind != draft.kind))
                value.copy(stopLoss = draft.stopLoss, stopLossEnabled = draft.stopLossEnabled)
            else value
            showDraft = true
        }
        fun perform(onAccepted: () -> Unit = {}, action: suspend () -> TradeResult) {
            if (busy) return
            busy = true
            val origin = accountName
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
                    else -> protectionEditError(plugin.capabilities, current, sl, tp) ?: positionProtectionError(spec, current, market.quotes[current.symbol], sl, tp)
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

        /**
         * One order changed on its own, apart from the position or entry it may belong to: a line dragged on the chart, or
         * the trade list's button. A drag goes out at once without confirmations; anything else opens the dialog.
         */
        fun changeAlone(order: PendingOrder, price: Long, lots: Long, fromChart: Boolean) {
            if (busy) return
            val current = session.state.value.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || !current.standaloneEditable) {
                showNotice("订单状态已变化，请重新选择", attention = true)
                return
            }
            if (fromChart && skipConfirmation) {
                val estimate = session.previewOrderChange(current.id, price, lots)
                if (!estimate.valid) { showNotice(estimate.errors.firstOrNull() ?: "改单价格无效", attention = true); return }
                perform { session.changeOrder(requestId(), current.id, current.version, price, lots) }
                return
            }
            val spec = catalog.instruments.firstOrNull { it.symbol == current.request.symbol } ?: return
            showContract(current.request.symbol)
            protection = null
            pendingProtection = null
            pendingEdit = null
            showDraft = false
            standaloneEdit = StandaloneOrderEdit(current, spec.formatPrice(price), spec.formatQuantity(lots))
        }

        fun editPending(order: PendingOrder, price: Long = order.request.entryPrice!!, fromChart: Boolean = false) {
            if (busy) return
            val current = session.state.value.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || current.role != order.role || current.request != order.request) {
                showNotice("订单状态或保护关联已变化，请重新选择", attention = true)
                return
            }
            // A stop or target that cannot move with its position moves on its own.
            if (current.standaloneEditable) { changeAlone(current, price, current.request.lotsHundredths, fromChart); return }
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
                // Chart windows exist only beside the desktop workspace. Without them the main chart is the one being worked on.
                val chartWindowsShown = desktop && panelWindows
                LaunchedEffect(chartWindowsShown, activeChart) { if (!chartWindowsShown) activateChart(MAIN_CHART) }
                val compactShort = compact && maxHeight < 550.dp
                // Compact windows wide enough for side navigation: landscape phones and portrait tablets.
                val rail = compact && (maxWidth >= 600.dp || (maxWidth >= 480.dp && maxWidth > maxHeight))
                val denseLandscape = compactShort && rail
                val landscapeDock = rail && maxWidth > maxHeight
                val tradeDockHeight = if (denseLandscape) 48.dp else MobileTradeBarHeight
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
                // The order page of compact screens is the one chosen in the settings: the sheet over the chart, which shares
                // the chart surface and never dims it, the dock measured alongside the chart, or a dialog.
                val chartFirst = compact && mobileTab == 1 && !showOrderFlow
                val orderLayout = tradingPreferences.orderPanelLayout
                val useOrderPopup = orderLayout == OrderPanelLayout.POPUP
                // The sheet and the dock stay on the chart page for as long as an order is being prepared; the popup does not.
                val orderOnChart = orderActive && !useOrderPopup
                val popupVisible = chartFirst && useOrderPopup && orderOpen && confirmation !is Confirmation.Place
                val panelOpen = popupVisible || chartFirst && orderOnChart && orderOpen
                // The receipt inside the dock's and the popup's form; the sheet shows it above its form, as it always did.
                val orderNotice: @Composable () -> Unit = {
                    notice?.let { current ->
                        Box(Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp)) {
                            FloatingNoticeCard(current, "trade-notice", { if (notice?.id == current.id) notice = null }, title = "操作提示")
                        }
                    }
                }
                fun closeOrder() {
                    focusManager.clearFocus()
                    orderPanelState = MobileOrderPanelState.CLOSED
                    // The sheet clears the draft once it has slid away, so its form does not change while it leaves.
                    if (orderLayout != OrderPanelLayout.SHEET) draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                    showDraft = false
                    if (confirmation is Confirmation.Place) confirmation = null
                }
                fun currentPlaceOrder(order: OrderRequest): OrderRequest {
                    if (!brackets.enabled || order.kind != OrderKind.MARKET) return order
                    val refreshed = brackets.prices(order, instrument, session.account.currency, plugin.market.value.quotes[order.symbol])
                    // A live quote can reduce a reviewed size; it cannot authorize a larger order.
                    return if (sizeByRisk) refreshed.copy(lotsHundredths = minOf(order.lotsHundredths, refreshed.lotsHundredths)) else refreshed
                }
                fun openOrderEditor() {
                    when {
                        compact -> orderPanelState = MobileOrderPanelState.EDITING
                        !wide && !orderDetached() -> showOrder = true
                        !runtime.desktopLayout.preferences.value.orderOpen -> {
                            runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true)
                            scope.launch { runtime.desktopLayout.save() }
                        }
                    }
                }
                fun placePreview(order: OrderRequest): OrderPreview {
                    val checked = session.preview(order).withCapacity(order, capacityFor(order))
                    if (!checked.valid) return checked
                    val error = brackets.refusal(order, instrument, session.account.currency, checked.entryPrice)
                    return if (error == null) checked else checked.copy(errors = listOf(error))
                }
                fun executeTrade(action: Confirmation) {
                    if (busy) return
                    when (action) {
                        is Confirmation.Place -> {
                            val order = try { currentPlaceOrder(action.request) }
                            catch (error: IllegalArgumentException) {
                                showNotice(error.message ?: "无法计算金额止损止盈", attention = true)
                                return
                            }
                            val checked = placePreview(order)
                            if (!checked.valid) { showNotice(checked.errors.firstOrNull() ?: "订单参数无效", attention = true); return }
                            perform({ if (compact && !action.fromChart) closeOrder() }) { session.placeOrder(requestId(), order) }
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
                    if (action is Confirmation.Place) {
                        // The guard's own classification, asked before a confirmation opens: an exit passes a lock and the limits.
                        runtime.accountRisk.refusal(plugin, session, RiskCheck.Place(action.request))?.let {
                            showNotice(it, attention = true)
                            return
                        }
                    }
                    if (skipConfirmation && action !is Confirmation.Reset) executeTrade(action)
                    else confirmation = action
                }
                val latestMobileRequest by rememberUpdatedState(request)
                val latestMobilePreview by rememberUpdatedState(preview)
                fun reviewMobileOrder() {
                    if (!busy && orderPanelState != MobileOrderPanelState.CLOSED && latestMobilePreview?.valid == true) {
                        latestMobileRequest?.let { reviewTrade(Confirmation.Place(it)) }
                    }
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
                        is LineAction.RemoveDraftProtection -> changeDraft(
                            if (sizeByRisk && action.stop) draft.withProtectionPrice(true, "")
                            else draft.withProtectionEnabled(instrument, quote, action.stop, false)
                        )
                        LineAction.CloseDraft -> if (compact) closeOrder() else showDraft = false
                    }
                }
                /**
                 * An order asked for on the chart: a limit or stop entry picked from its context menu at the clicked [price], or
                 * a market order from the trading keys. It uses the same quantity, account brackets and checks as the order panel.
                 */
                fun orderFromChart(side: Side, kind: OrderKind, price: Long?, fromKeys: Boolean = false) {
                    val entry = price?.let(instrument::formatPrice).orEmpty()
                    if (sizeByRisk) {
                        draft = OrderDraft(side, kind, draft.quantity, entry)
                        showDraft = true
                        openOrderEditor()
                        return
                    }
                    val order = OrderDraft(side, kind, draft.quantity, entry).request(instrument)
                    if (order == null) {
                        showNotice(if (instrument.executionSymbol == null) "${instrument.executionLabel}，暂不可下单" else "请先在下单面板输入有效数量", attention = true)
                        return
                    }
                    val protectedOrder = try { brackets.prices(order, instrument, session.account.currency, quote) }
                    catch (error: IllegalArgumentException) {
                        showNotice(error.message ?: "无法计算金额止损止盈", attention = true)
                        return
                    }
                    reviewTrade(Confirmation.Place(protectedOrder, fromChart = true, fromKeys = fromKeys))
                }
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && mobileTab == 3 && showActivity, onBackCompleted = { showActivity = false })
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && showOrderFlow, onBackCompleted = { showOrderFlow = false })
                val activate by rememberUpdatedState<(String) -> Unit> { id -> activateChart(id) }
                // One chart window. The active one shows the screen's symbol, interval, draft and selection; any other shows
                // its own symbol and interval and becomes the active one at the first press inside it.
                // What every chart shows, as this pass of the screen saw it. A chart's content decides from these alone, never from
                // the state behind them: in a window it is composed apart from the screen, and a newer active chart beside the
                // instrument of the previous one would show a wrong chart for a frame and drop its view.
                val workingChart = activeChart
                val shownCharts = parkedCharts + (workingChart to ChartSlot(workingChart, symbol, timeframe))
                val chartView: @Composable (String, Modifier) -> Unit = { chartId, m ->
                    val active = chartId == workingChart
                    val parked = shownCharts[chartId]
                    val shownInstrument = if (active) instrument else parked?.let { slot -> catalog.instruments.firstOrNull { it.symbol == slot.symbol } }
                    // A symbol this broker has not loaded: the window says so, and a press makes it the active chart, where another can be chosen.
                    if (shownInstrument == null) Box(m.background(colors.Surface).pointerInput(chartId) { detectTapGestures { activate(chartId) } }.testTag("chart-unavailable-$chartId"),
                        contentAlignment = Alignment.Center) {
                        EmptyState("品种未加载", "${parked?.symbol.orEmpty()} 不在当前交易商已加载的合约里。点一下这里，再在图表上另选品种。", icon = AppIcons.Candlestick)
                    } else {
                    val shownTimeframe = parked?.timeframe ?: timeframe
                    val shownExecution = shownInstrument.executionSymbol
                    val shownQuote = if (active) quote else market.quotes[shownExecution]
                    val shownCandles = remember(plugin, shownInstrument.symbol, shownTimeframe, market.revision) { plugin.history(shownInstrument.symbol, shownTimeframe) }
                    /** A step that belongs to the active chart. On any other chart it only makes that chart the active one, and the user repeats the step. */
                    fun onActive(block: () -> Unit) { if (active) block() else activateChart(chartId) }
                    // Minimizing hides only the form; the active draft remains editable on the chart.
                    val draftShown = active && if (compact) orderActive else showDraft
                    // Draft lines take a drop at once; pending orders and positions wait for the confirmation dialogs.
                    val actions = ChartOrderActions(
                        estimateDraft = { order ->
                            try {
                                val estimated = brackets.prices(order, instrument, session.account.currency, quote)
                                estimated to session.preview(estimated).withCapacity(estimated, capacityFor(estimated))
                            } catch (error: IllegalArgumentException) {
                                (if (sizeByRisk) order.copy(lotsHundredths = 0L) else order) to
                                    OrderPreview(null, errors = listOf(error.message ?: "无法计算订单预览"))
                            }
                        },
                        draftStopManaged = brackets.controls(true),
                        draftTargetManaged = brackets.controls(false),
                        onDraftEntry = { price -> onActive { changeDraft(draft.copy(entry = instrument.formatPrice(price))) } },
                        onDraftProtection = { stop, price ->
                            onActive { changeDraft(draft.withProtectionPrice(stop, instrument.formatPrice(price))) }
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
                        entryKinds = plugin.capabilities.orderKinds,
                        // A chart that is not the active one shows the quantity its symbol's order panel was left with.
                        entryLots = if (sizeByRisk) request?.lotsHundredths.takeIf { active }
                        else shownInstrument.parseQuantity(if (active) draft.quantity else
                            orderSessions["${shownInstrument.symbol}/$shownExecution"]?.draft?.value?.quantity ?: if (shownInstrument.quantityDecimals == 0) "1" else "0.10"),
                        onEntryOrder = { side, kind, price -> onActive { orderFromChart(side, kind, price) } },
                        estimateStandalone = { order, price -> session.previewOrderChange(order.id, price, order.request.lotsHundredths) },
                        onStandalone = { order, price -> changeAlone(order, price, order.request.lotsHundredths, fromChart = true) },
                        tradingKeys = tradingPreferences.tradingKeys.takeIf { tradingPreferences.keyboardTrading && OrderKind.MARKET in plugin.capabilities.orderKinds },
                        instantOrders = skipConfirmation,
                        stopRequiredForSizing = sizeByRisk,
                        onMarketOrder = { side -> onActive { orderFromChart(side, OrderKind.MARKET, null, fromKeys = true) } },
                    )
                    // Every chart has its own tools and selection; the drawings themselves are shared through the library.
                    val drawings = if (chartId == MAIN_CHART) runtime.drawings else remember(chartId) { com.unitrading.chart.drawing.ChartDrawings(runtime.drawingLibrary) }
                    BoxWithConstraints(m.pointerInput(chartId) {
                        // On the Initial pass and without consuming: the press that makes this chart the active one still does what it did.
                        awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) activate(chartId) } }
                    }.testTag("chart-view-$chartId")) {
                    val chartCompact = compact || (showOrderFlow && maxWidth < 600.dp)
                    val chartShort = chartCompact && maxHeight < 550.dp
                    Row(Modifier.fillMaxSize()) {
                    if (!chartCompact || drawings.toolbarOpen) com.unitrading.chart.drawing.DrawingToolbar(drawings, indicatorSettings.indicators.size, { scope.launch { runtime.indicators.apply(com.unitrading.app.IndicatorSettings()) } })
                    TradingChart(accountKey, shownInstrument, shownQuote, shownCandles, shownTimeframe, { frame -> activateChart(chartId); timeframe = frame },
                        if (active) visiblePositions else account.positions.filter { it.symbol == shownExecution },
                        if (active) visibleTrades else account.trades.filter { it.symbol == shownExecution },
                        selectedPositionId.takeIf { active }, focusedTrade.takeIf { active }, chartDraft.takeIf { draftShown }, preview.takeIf { draftShown },
                        if (active) visibleOrders else account.orders.filter { it.request.symbol == shownExecution }, actions,
                        Modifier.weight(1f).fillMaxHeight(), environment = plugin.descriptor.environment, executions = account.executions.filter { it.symbol == shownExecution }, indicators = if (drawings.hideIndicators) emptyList() else indicatorSettings.indicators, onIndicators = { showIndicators = true }, focusedExecution = focusedExecution.takeIf { active },
                        instruments = watchInstruments, onSymbol = { value -> activateChart(chartId); chooseSymbol(value) }, intervals = intervals, connected = market.connected,
                        indicatorPaneFractions = chartPreferences.indicatorPaneFractions,
                        onIndicatorPaneFractions = { fractions -> scope.launch { runtime.chartPreferences.setIndicatorPaneFractions(fractions) } },
                        onSide = { side -> onActive {
                            val base = if (compact && orderPanelState == MobileOrderPanelState.CLOSED)
                                OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10") else draft
                            changeDraft(base.withSide(side, instrument, quote))
                            openOrderEditor()
                        } },
                        // The legend's fold is remembered once the user toggles it; until then short phone screens start folded.
                        chrome = ChartChrome(chartCompact, chartPreferences.legendCollapsed ?: (compactShort || chartShort),
                            { folded -> scope.launch { runtime.chartPreferences.setLegendCollapsed(folded) } },
                            ordering = compact && orderOnChart && orderOpen, drawings = drawings, views = runtime.chartViews, toolbarEnd = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (compact && orderFlowSupported) ActionIcon(AppIcons.TrendingUp, "订单流分析", "open-order-flow") {
                                        closeOrder(); showOrderFlow = true
                                    }
                                    barStatus()
                                }
                            }))
                    }
                    }
                    }
                }
                val chart: @Composable (Modifier) -> Unit = { m -> chartView(MAIN_CHART, m) }
                // Notifications occupy overlay space, never alter chart/panel constraints, and do not dim the workspace.
                val notices: @Composable BoxScope.(top: Dp, limit: Dp, end: Boolean) -> Unit = { top, limit, end ->
                Column(Modifier.align(if (end) Alignment.TopEnd else Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = top)
                    .widthIn(max = 420.dp).fillMaxWidth().heightIn(max = limit)
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
                }
                // Everything a trade or a chart can ask for in a dialog; it opens in the window the user is working in.
                val tradeDialogs: @Composable () -> Unit = {
                if (showIndicators) IndicatorSettingsDialog(runtime.indicators) { showIndicators = false }
                if (showOrder || popupVisible) {
                    // The phone's popup closes the way the dock minimizes: by its close button, by back or by a tap beside
                    // it, the draft stays on the chart and "下单" opens it again. Only "取消订单" drops it.
                    val dismissOrder: () -> Unit = { if (popupVisible) orderPanelState = MobileOrderPanelState.MINIMIZED else if (!busy) showOrder = false }
                    Dialog(onDismissRequest = dismissOrder) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, {
                                if (popupVisible) reviewMobileOrder()
                                else request?.let { reviewTrade(Confirmation.Place(it)); showOrder = false }
                            }, Modifier.widthIn(max = 420.dp).fillMaxHeight(.94f).imePadding().testTag("order-popup"), environment = environment, capacity = capacity,
                                onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation, onClose = dismissOrder,
                                brackets = brackets, bracketOrder = request,
                                onChartAdjust = if (popupVisible) dismissOrder else null,
                                orderNotice = if (popupVisible) orderNotice else ({}),
                                onDiscard = if (popupVisible) ({ closeOrder() }) else null,
                                accountName = accountName.takeIf { popupVisible })
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
                    val placeOrder = (action as? Confirmation.Place)?.let { runCatching { currentPlaceOrder(it.request) } }
                    val liveOrder = placeOrder?.getOrNull()
                    val livePreview = liveOrder?.let(::placePreview) ?: placeOrder?.exceptionOrNull()?.let {
                        OrderPreview(null, errors = listOf(it.message ?: "无法计算金额止损止盈"))
                    }
                    val confirmLabel = when (action) { is Confirmation.Place -> action.request.side.label(); is Confirmation.Close -> "平仓"; is Confirmation.Cancel -> "撤单"; Confirmation.Reset -> "重置" }
                    val confirmFocus = remember { FocusRequester() }
                    val confirmColor = when (action) {
                        is Confirmation.Place -> actionFill(if (action.request.side == Side.BUY) colors.Up else colors.Down)
                        else -> actionFill(MaterialTheme.colorScheme.error)
                    }
                    AlertDialog(
                        onDismissRequest = { if (!busy) confirmation = null },
                        title = { Text(title) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("$loginName / $accountName / ${environment.label}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            when (action) {
                                is Confirmation.Place -> {
                                    val r = liveOrder ?: action.request
                                    r.sourceSymbol?.let { Text("图表 / 指标 $it → 下单 ${r.symbol}", color = colors.Accent, style = MaterialTheme.typography.bodySmall) }
                                    Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}", style = MaterialTheme.typography.titleSmall)
                                    EstimateRow("当前预估入场", livePreview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                                    EstimateRow("止损价", r.stopLoss?.let(instrument::formatPrice) ?: "未设置")
                                    EstimateRow("止盈价", r.takeProfit?.let(instrument::formatPrice) ?: "未设置")
                                    EstimateRow("预计保证金 USD", livePreview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                                    Text(when {
                                        sizeByRisk && r.kind == OrderKind.MARKET -> "提交时按最新报价复核风险，必要时减少手数。"
                                        brackets.enabled && r.kind == OrderKind.MARKET -> "提交时按最新报价重算自动止损止盈，实际成交价可能与预估不同。"
                                        else -> "提交时会按最新报价和账户状态重新校验，实际成交价可能与预估不同。"
                                    },
                                        style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                                    if (action.fromKeys) Text("键盘下单：按 Enter 确认，按 Esc 取消。", Modifier.testTag("confirm-keys-hint"), style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                                    livePreview?.errors?.forEach { Text(it, color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                                }
                                is Confirmation.Close -> Text("平掉 ${action.position.symbol} 的 #${shortId(action.position.id)} 全部 ${catalog.instruments.first { it.symbol == action.position.symbol }.formatQuantity(action.position.lotsHundredths)} ${catalog.instruments.first { it.symbol == action.position.symbol }.quantityUnit}，按当前可成交报价执行。")
                                is Confirmation.Cancel -> Text("撤销${action.order.role.label()} #${shortId(action.order.id)}。" +
                                    if (action.order.isProtection) "撤单后将移除这项保护，服务器也可能调整关联订单。" else "若已被触发，交易商将拒绝撤单。")
                                Confirmation.Reset -> Text("将覆盖两个交易商的全部本地模拟余额、仓位、挂单和历史，并重新生成演示交易。此操作不能撤销。")
                            }
                        } },
                        confirmButton = {
                            Button({
                                confirmation = null
                                executeTrade(action)
                            }, enabled = !busy && (livePreview == null || livePreview.valid), modifier = Modifier.testTag("confirm-action").focusRequester(confirmFocus),
                                colors = ButtonDefaults.buttonColors(containerColor = confirmColor, contentColor = contentOn(confirmColor))) { Text(confirmLabel) }
                            // An order asked for by a key is confirmed by one: Enter presses the focused button, Esc dismisses the dialog.
                            if ((action as? Confirmation.Place)?.fromKeys == true) LaunchedEffect(action) { runCatching { confirmFocus.requestFocus() } }
                        },
                        dismissButton = { TextButton({ confirmation = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                flattenReview?.let { review ->
                    FlattenConfirmation(review, onCancel = { flattenReview = null }) {
                        if (!busy) {
                            flattenReview = null
                            busy = true
                            flattening = true
                            scope.launch {
                                try {
                                    flattenResults = runtime.accountRisk.flattenAccounts(registry, review.targets)
                                    runtime.save()
                                } finally {
                                    flattening = false
                                    busy = false
                                }
                            }
                        }
                    }
                }
                if (flattening) AlertDialog(
                    onDismissRequest = {},
                    title = { Text("正在撤单和平仓") },
                    text = { Text("等待券商逐项确认。") },
                    confirmButton = {},
                    properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                )
                flattenResults?.let { FlattenResults(it) { flattenResults = null } }
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
                        }, Modifier.testTag("confirm-pending-entry"), enabled = !busy && validVersion && estimate?.valid == true, colors = brandButtonColors()) { Text(if (skipConfirmation) "应用改价" else "确认改价") } },
                        dismissButton = { TextButton({ pendingEdit = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                standaloneEdit?.let { edit ->
                    val spec = catalog.instruments.firstOrNull { it.symbol == edit.order.request.symbol } ?: return@let
                    StandaloneOrderDialog(edit, spec, account.orders.firstOrNull { it.id == edit.order.id }, plugin.capabilities,
                        { price, lots -> session.previewOrderChange(edit.order.id, price, lots) }, busy, skipConfirmation, { standaloneEdit = it },
                        onConfirm = { price, lots ->
                            standaloneEdit = null
                            perform { session.changeOrder(requestId(), edit.order.id, edit.order.version, price, lots) }
                        },
                        onDismiss = { if (!busy) standaloneEdit = null })
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
                    val priceError = if (validInput) positionProtectionError(spec, p, market.quotes[p.symbol], sl, tp) else null
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
                            priceError?.let { Text(it, color = colors.Loss, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("protection-price-error")) }
                        } },
                        confirmButton = { Button({
                            protection = null
                            perform { session.modifyProtection(requestId(), p.id, p.version, sl, tp) }
                        }, Modifier.testTag("confirm-protection"), enabled = validInput && validVersion && capabilityError == null && priceError == null && !busy, colors = brandButtonColors()) { Text(if (skipConfirmation) "应用修改" else "确认修改") } },
                        dismissButton = { TextButton({ protection = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                }
                val dialogsOpen = showIndicators || showOrder || popupVisible || confirmation != null || pendingEdit != null || pendingProtection != null || protection != null ||
                    standaloneEdit != null
                // A dialog stays in the window it opened in; notices follow the user from window to window. A window that
                // has gone hands both back to the main one.
                // Read as state where a window draws its overlay: that content belongs to another composition than this one.
                val dialogWindow by rememberUpdatedState(remember(dialogsOpen) { activeWindow }.takeIf { it in liveWindows })
                val noticeWindow by rememberUpdatedState(activeWindow.takeIf { it in liveWindows })
                val mainFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(mainFocused) { if (mainFocused) activeWindow = null }
                val windowOverlay: @Composable (String) -> Unit = { window ->
                    val focused = LocalWindowInfo.current.isWindowFocused
                    LaunchedEffect(focused) { if (focused) activeWindow = window }
                    DisposableEffect(window) {
                        liveWindows += window
                        onDispose { liveWindows -= window; if (activeWindow == window) activeWindow = null }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (noticeWindow == window) notices(12.dp, Dp.Unspecified, true)
                        if (dialogWindow == window) tradeDialogs()
                    }
                }
                // Overview, positions and orders, and analytics of the trading child: a tab on phones, a dialog on the desktop.
                val accountHub: @Composable (Modifier) -> Unit = { hubModifier ->
                    Column(hubModifier.background(colors.Shell).testTag("account-hub")) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("总览", "交易", "分析").forEachIndexed { index, label ->
                                FilterChip(accountTab == index, { accountTab = index }, label = { Text(label) },
                                    modifier = Modifier.testTag("account-tab-$index"))
                            }
                        }
                        AccountRiskNotice(riskMessage, riskTrigger, session.account.currency, Modifier.padding(horizontal = 12.dp))
                        when (accountTab) {
                            0 -> AccountOverviewPage(plugin, session.account, account, market, { if (!busy) accountTab = 1 },
                                Modifier.weight(1f).fillMaxWidth(), busy)
                            1 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                                PageTopBar("持仓与订单", accountName, actions = { barStatus() })
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val closeColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    OutlinedButton(
                                        onClick = { flattenReview = FlattenReview(false, listOf(FlattenTarget(plugin, session.account))) },
                                        modifier = Modifier.weight(1f).testTag("flatten-selected-account"),
                                        enabled = !busy && !session.account.readOnly && (account.positions.isNotEmpty() || account.orders.isNotEmpty()),
                                        colors = closeColors, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("一键平仓") }
                                    OutlinedButton(
                                        onClick = {
                                            // View-only accounts and logins that cannot trade now are listed as skipped, not as targets.
                                            val (targets, skipped) = bulkFlattenTargets(registry.plugins)
                                            if (targets.isNotEmpty() || skipped.isNotEmpty()) flattenReview = FlattenReview(true, targets, skipped)
                                        },
                                        modifier = Modifier.weight(1f).testTag("flatten-all-accounts"),
                                        enabled = !busy, colors = closeColors, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("一键平仓全部子账户") }
                                }
                                TradeTerminal(plugin, account, market, selectedPositionId, compact,
                                    onSelectPosition = { p -> focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null; mobileTab = 1; showOverview = false },
                                    onSelectTrade = { t -> focusedExecution = null; showContract(t.symbol); focusedTrade = t; mobileTab = 1; showOverview = false },
                                    onClose = { reviewTrade(Confirmation.Close(it)) }, onProtect = { editProtection(it) },
                                    onCancel = { reviewTrade(Confirmation.Cancel(it)) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = Modifier.weight(1f).fillMaxWidth(), onProtectOrder = { editPendingProtection(it) })
                            }
                            else -> key(plugin, accountId) {
                                if (!localDemo) LaunchedEffect(session) {
                                    runtime.analyticsCache.update(plugin.descriptor.id, accountId, session.state.value.trades)
                                }
                                AccountAnalyticsPage(session.account, analyticsTrades, Modifier.weight(1f).fillMaxWidth(),
                                    name = accountName, cached = !localDemo)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxSize()) {
                if (rail) MobileNavigationRail(mobileTab, selectTab, dense = denseLandscape)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (desktop) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(colors.Surface).testTag("app-toolbar").padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLogo(24.dp)
                            if (wide) Text("UniTrading", style = MaterialTheme.typography.titleMedium)
                            if (wide) EnvironmentBadge(environment)
                            AccountContextBar(registry, plugin, accountId, ::chooseBroker, ::chooseAccount, Modifier.weight(1f), enabled = !busy,
                                aliases = runtime.accountAliases)
                            TextButton({ showOverview = true }, Modifier.testTag("toggle-account-overview")) {
                                Icon(AppIcons.AccountCircle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("账户总览")
                            }
                            if (!localDemo) ConnectionStatusControl(market, compact = !wide, busy, syncAction)
                            if (localDemo) ActionIcon(if (paused) AppIcons.PlayArrow else AppIcons.Pause, if (paused) "继续行情" else "暂停行情", "toggle-demo-feed") { runtime.paused.value = !paused }
                            ActionIcon(AppIcons.ViewQuilt, "重置布局", "reset-desktop-layout") { showOrderFlow = false; runtime.desktopLayout.reset(); scope.launch { runtime.desktopLayout.save() } }
                            ActionIcon(AppIcons.Settings, "设置", "open-settings") { showActivity = false; showSettings = true }
                            if (!wide) Button({
                                showDraft = true
                                if (!orderDetached()) showOrder = true
                                else { runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true); scope.launch { runtime.desktopLayout.save() } }
                            }, Modifier.testTag("open-order"), colors = brandButtonColors()) { Text("下单") }
                        }
                        DividerLine()
                    }
                    if (compact) {
                        val showAccountBar = !(chartFirst && orderOnChart) && (!keyboardOpen || popupVisible)
                        val accountBarHeight by animateDpAsState(
                            if (showAccountBar) { if (denseLandscape) 41.dp else 57.dp } else 0.dp, tween(180), label = "account-bar-height")
                        Box(Modifier.fillMaxWidth().height(accountBarHeight).clipToBounds()) {
                            Column(Modifier.fillMaxWidth()) {
                                AccountContextBar(registry, plugin, accountId, ::chooseBroker, ::chooseAccount,
                                    Modifier.fillMaxWidth().background(colors.Surface).padding(horizontal = 6.dp),
                                    enabled = !busy && showAccountBar, dense = denseLandscape, aliases = runtime.accountAliases)
                                DividerLine()
                            }
                        }
                    }
                    if (desktop) {
                        DesktopWorkspace(runtime.desktopLayout, wide, Modifier.weight(1f).fillMaxWidth(),
                            market = { m, minimize -> MarketWatch(plugin, market, account, symbol, ::chooseSymbol, m, runtime.marketWatch, panel = true, onClose = minimize) },
                            chart = chart,
                            order = { m, minimize -> OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                { request?.let { reviewTrade(Confirmation.Place(it)) } }, m.background(colors.Surface), environment = environment,
                                capacity = capacity, onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation,
                                onClose = minimize, closeLabel = "收起下单面板", closeTag = "desktop-order-minimize", brackets = brackets, bracketOrder = request) },
                            terminal = { m ->
                                TradeTerminal(plugin, account, market, selectedPositionId, false,
                                    onSelectPosition = { p -> focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null },
                                    onSelectTrade = { t -> focusedExecution = null; showContract(t.symbol); focusedTrade = t; selectedPositionId = null },
                                    onClose = { reviewTrade(Confirmation.Close(it)) }, onProtect = { editProtection(it) }, onCancel = { reviewTrade(Confirmation.Cancel(it)) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = m, onProtectOrder = { editPendingProtection(it) })
                            }, terminalSummary = "持仓 ${account.positions.size} · 挂单 ${terminalOrders(account).rows.size}",
                            orderFlow = if (orderFlowSupported) { { m -> OrderFlowPanel(plugin, instrument, timeframe, m) { showOrderFlow = false } } } else null,
                            orderFlowOpen = showOrderFlow, onOrderFlowOpenChange = { showOrderFlow = it }, windowOverlay = windowOverlay,
                            windowMenuExtra = { close ->
                                DividerLine(Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(text = { Text("新建图表窗口") }, onClick = { close(); openChart() }, enabled = chartOrder.size < ChartPreferences.MAX_EXTRA_CHARTS,
                                    leadingIcon = { Icon(AppIcons.Add, contentDescription = null) }, modifier = Modifier.testTag("window-menu-new-chart"))
                            })
                        // The chart windows beside the main chart; closing one closes that chart for good.
                        val chartWindows = LocalPanelWindowHost.current
                        val layoutPreferences by runtime.desktopLayout.preferences.collectAsState()
                        if (chartWindows != null) chartOrder.forEach { id -> key(id) {
                            val slot = chartSlot(id)
                            val window = chartWindowKey(id)
                            chartWindows.Window(window, "UniTrading · 图表 · ${slot?.symbol.orEmpty()} · ${slot?.timeframe?.label.orEmpty()}", layoutPreferences.windows[window], DpSize(1000.dp, 680.dp),
                                onBounds = { runtime.desktopLayout.setWindowBounds(window, it); scope.launch { runtime.desktopLayout.save() } },
                                onClose = { closeChart(id) }) { WindowSurface(window, windowOverlay) { chartView(id, Modifier.fillMaxSize()) } }
                        } }
                    } else if (showOrderFlow) {
                        OrderFlowPanel(plugin, instrument, timeframe, Modifier.weight(1f).fillMaxWidth()) { showOrderFlow = false }
                    } else {
                        when (mobileTab) {
                            4 -> accountHub(Modifier.weight(1f).fillMaxWidth())
                            0 -> MarketWatch(plugin, market, account, symbol, ::chooseSymbol, Modifier.weight(1f), runtime.marketWatch, accountName) { barStatus() }
                            1 -> key(orderSession) {
                                val idleDock = if (rail) tradeDockHeight else tradeDockHeight + MobileNavigationHeight
                                // Below the chart while no order page takes that place: the order button, and the navigation unless it is a rail.
                                val idleBar: @Composable () -> Unit = {
                                    Box(Modifier.height(tradeDockHeight)) {
                                        MobileTradeBar(account, onOpen = {
                                            // A draft the popup left on the chart opens again as it is.
                                            if (!orderActive) draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                                            openOrderEditor()
                                        }, onPositions = { accountTab = 1; mobileTab = 4 }, dense = denseLandscape)
                                    }
                                    if (!rail) MobileNavigation(mobileTab, selectTab)
                                }
                                if (orderLayout == OrderPanelLayout.SHEET) {
                                    // The sheet keeps a full-height host while the chart dock changes continuously.
                                    val dockHeight by animateDpAsState(if (orderActive) MobileOrderPeekHeight else idleDock,
                                        animationSpec = tween(250), label = "chart-order-dock")
                                    MobileOrderSheet(instrument, quote, plugin.capabilities, draft, ::changeDraft,
                                        orderPanelState, { orderPanelState = it }, { closeOrder() }, ::reviewMobileOrder, preview?.valid == true && request != null,
                                        Modifier.weight(1f).fillMaxWidth(), busy = busy, skipConfirmation = skipConfirmation,
                                        onHidden = {
                                            if (orderPanelState == MobileOrderPanelState.CLOSED)
                                                draft = OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")
                                        },
                                        brackets = brackets, calculatedLots = request?.lotsHundredths, accountName = accountName,
                                        content = {
                                            notice?.let { current ->
                                                Box(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                                                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                    FloatingNoticeCard(current, "trade-notice", { if (notice?.id == current.id) notice = null }, title = "操作提示")
                                                }
                                            }
                                            OrderForm(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                                ::reviewMobileOrder, Modifier.weight(1f),
                                                environment = environment, capacity = capacity, onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation,
                                                brackets = brackets, bracketOrder = request)
                                        },
                                        body = {
                                            Column(Modifier.fillMaxSize()) {
                                                chart(Modifier.weight(1f))
                                                Box(Modifier.fillMaxWidth().height(dockHeight).clipToBounds()) {
                                                    androidx.compose.animation.AnimatedVisibility(!orderActive, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                                                        Column(Modifier.fillMaxWidth().requiredHeight(idleDock)) { idleBar() }
                                                    }
                                                }
                                            }
                                        })
                                } else MobileOrderDockLayout(
                                    Modifier.weight(1f).fillMaxWidth().then(if (orderOnChart && orderOpen) Modifier.imePadding() else Modifier),
                                    editing = orderOnChart && orderOpen, landscape = landscapeDock,
                                    keyboardOpen = keyboardOpen, advanced = advancedOrder,
                                    bottomHeight = if (orderOnChart) MobileOrderDockPeekHeight else idleDock,
                                    chart = { chart(Modifier.fillMaxSize()) },
                                    dock = {
                                        if (orderOnChart) {
                                            MobileOrderDock(instrument, quote, plugin.capabilities, accountName, draft, ::changeDraft,
                                                orderPanelState, { orderPanelState = it }, { closeOrder() }, ::reviewMobileOrder,
                                                preview?.valid == true && request != null, busy, skipConfirmation, keyboardOpen, brackets = brackets, calculatedLots = request?.lotsHundredths) {
                                                OrderForm(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                                    ::reviewMobileOrder, Modifier.weight(1f),
                                                    environment = environment, capacity = capacity, onRefreshCapacity = refreshCapacity,
                                                    skipConfirmation = skipConfirmation, dockAdvanced = advancedOrder,
                                                    onDockAdvanced = { open ->
                                                        advancedOrder = open
                                                        scope.launch { runtime.tradingPreferences.setOrderMoreSettings(open) }
                                                    },
                                                    brackets = brackets, bracketOrder = request,
                                                    orderNotice = orderNotice)
                                            }
                                        } else Column(Modifier.fillMaxSize()) { idleBar() }
                                    })
                            }
                            else -> if (showActivity) {
                                ActivityLogPage("$loginName · $accountName", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.weight(1f).fillMaxWidth())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker(""); scope.launch { runtime.analyticsCache.clear() } }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
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
                if (noticeWindow == null) notices(if (desktop) 72.dp else if (keyboardOpen) 8.dp else 56.dp, maxHeight * .65f, desktop)
                if (desktop && showOverview) {
                    Dialog(onDismissRequest = { showOverview = false }) {
                        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.extraLarge, color = colors.Shell) {
                            accountHub(Modifier.fillMaxSize().padding(top = 12.dp))
                        }
                    }
                }
                if (desktop && showSettings) {
                    Dialog(onDismissRequest = { if (showActivity) showActivity = false else showSettings = false }) {
                        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.extraLarge, color = colors.Surface) {
                            if (showActivity) {
                                ActivityLogPage("$loginName · $accountName", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.fillMaxSize())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true }, onIndicators = { showIndicators = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker(""); scope.launch { runtime.analyticsCache.clear() } }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.fillMaxSize(), onClose = { showSettings = false }, onConnectRithmic = connectRithmic, updates = updates)
                        }
                    }
                }
                if (dialogWindow == null) tradeDialogs()
                if (startupError != null && confirmation !is Confirmation.Reset) AlertDialog(
                    onDismissRequest = {}, title = { Text("模拟存档无法读取") },
                    text = { Text(startupError!! + "\n请先备份原文件。继续重置会覆盖所有模拟账户。") },
                    confirmButton = { TextButton({ confirmation = Confirmation.Reset }) { Text("检查重置操作") } },
                )
            }
        }
    }
    }
}
