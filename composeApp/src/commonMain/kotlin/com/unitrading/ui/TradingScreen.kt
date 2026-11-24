package com.unitrading.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.chartSessionCandles
import com.unitrading.chart.chartSessionSource
import com.unitrading.chart.ChartLayerState
import com.unitrading.chart.ChartToggle
import com.unitrading.chart.ChartToolbar
import com.unitrading.chart.ChartChrome
import com.unitrading.chart.ChartOrderActions
import com.unitrading.chart.IntervalOptions
import com.unitrading.chart.LineAction
import com.unitrading.chart.TradingChart
import com.unitrading.tradovate.TradovateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Orders, edits, cancels and history work on Paper Trading; closing a position is unverified live, and conformance is pending. */
private const val RITHMIC_LOGIN_ENABLED = true

/** Compare snapshots by identity: quote-only revisions retain the same immutable candle list. */
private class CandleSnapshotKey(private val candles: List<Candle>) {
    override fun equals(other: Any?) = other is CandleSnapshotKey && candles === other.candles
    override fun hashCode() = 0
}
private class SessionResult(val candles: List<Candle>, val notice: String?)

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
    // Automatic flattens that ended unconfirmed or were cut off, each kept until it is dismissed, whichever page shows
    // meanwhile. The controller keeps them, so a screen built again (Android recreates it for dark mode, font size or
    // language) still shows the unread ones.
    val unreadFailures by runtime.accountRisk.unreadFailures.collectAsState()
    LaunchedEffect(runtime) { runtime.accountRisk.monitor(runtime.registry) }
    val theme by runtime.theme.settings.collectAsState()
    val aliases by runtime.accountAliases.aliases.collectAsState()
    /** The notice of [failure]. One shows at a time, so they share an id, below zero where no other notice has one. */
    fun failureNotice(failure: FlattenFailure) = TradingNotice(-1, aliases.account(failure.login, failure.account) +
        (if (failure.interrupted) " 自动平仓被中断，结果待核对" else " 自动平仓未完成") + "，请检查券商持仓和挂单。", NoticeTone.ATTENTION, persistent = true)
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
        // The pages in front of the trading screen have no notices of their own: an unread failure shows over them.
        val failureOverlay: @Composable BoxScope.() -> Unit = {
            unreadFailures.firstOrNull()?.let { failure ->
                FloatingNoticeCard(failureNotice(failure), "trade-notice", { runtime.accountRisk.acknowledge(failure) }, Modifier.align(Alignment.TopCenter)
                    .safeDrawingPadding().padding(start = 12.dp, end = 12.dp, top = 56.dp).widthIn(max = 420.dp), title = "操作提示")
            }
        }
        if (plugin == null) {
            Box(Modifier.fillMaxSize()) {
                LoginWelcome(runtime, { showConnection = true }, { showTheme = true }, onLoginRithmic = connectRithmic)
                failureOverlay()
            }
            return@TradingTheme
        }
        // A saved login comes back at the next start and the broker would not return its older trades again, so they stay
        // cached; a login that is not saved (Rithmic without "remember") does not come back by itself, so they are deleted.
        fun disconnectCurrent() {
            val id = plugin.descriptor.id
            val saved = runtime.savedLogins.saved(id)
            registry.unregister(id)
            // Unregistered first, so the screen's collector writes nothing more for it after the deletion.
            if (!saved) loginScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runtime.analyticsCache.clear(id) } }
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
                            SavedLoginControls(runtime) { selectBroker(registry.plugins.firstOrNull()?.descriptor?.id ?: "") }
                        }
                    }
                }
                Box(Modifier.fillMaxSize(), content = failureOverlay)
            }
            return@TradingTheme
        }
        var chosenAccountId by remember(plugin) { mutableStateOf(runtime.savedLogins.selectedAccount(plugin.descriptor.id) ?: catalog.accounts.first().id) }
        // Detached workspaces may select a different subaccount without replacing the main window's selection.
        var activeChart by remember(plugin) { mutableStateOf(MAIN_CHART) }
        val supportsDetachedAccounts = LocalChartPlatform.current == ChartPlatform.DESKTOP && LocalPanelWindowHost.current != null
        val mainAccountId = chosenAccountId.takeIf { id -> catalog.accounts.any { it.id == id } } ?: catalog.accounts.first().id
        val detachedAccounts = remember(plugin, supportsDetachedAccounts) { mutableStateMapOf<String, String>().apply {
            if (supportsDetachedAccounts) {
                val saved = runtime.workspaceAccounts.selections.value[plugin.descriptor.id].orEmpty()
                runtime.chartPreferences.preferences.value.detachedWorkspaces.forEach { put(it, saved[it] ?: mainAccountId) }
            }
        } }
        val unresolvedDetachedAccounts = remember(plugin, supportsDetachedAccounts) { mutableStateMapOf<String, Boolean>().apply {
            if (supportsDetachedAccounts && !runtime.workspaceAccounts.readable)
                runtime.chartPreferences.preferences.value.detachedWorkspaces.forEach { put(it, true) }
        } }
        val activeAccountWorkspace = if (activeChart == MAIN_CHART || activeChart.startsWith("split")) MAIN_CHART else activeChart.substringBefore("s")
        val accountId = detachedAccounts[activeAccountWorkspace]?.takeIf { id -> catalog.accounts.any { it.id == id } } ?: mainAccountId
        val localDemo = plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO
        val mainSession = remember(plugin, mainAccountId) { runtime.accountRisk.guard(plugin, plugin.openSession(mainAccountId)) }
        val session = remember(plugin, accountId, mainSession) {
            if (accountId == mainAccountId) mainSession else runtime.accountRisk.guard(plugin, plugin.openSession(accountId))
        }
        DisposableEffect(plugin, session) {
            runtime.livePnl.track(plugin, session)
            onDispose { runtime.livePnl.untrack(session) }
        }
        val account by key(session) { session.state.collectAsState() }
        val mainAccount by key(mainSession) { mainSession.state.collectAsState() }
        // What this device cached of the account's closed trades; the local demo keeps its whole history itself.
        val cachedByAccount = if (localDemo) emptyMap() else {
            val cached by runtime.analyticsCache.rows.collectAsState()
            // The only writer of the cache. A login that was just disconnected or forgotten is not written back.
            LaunchedEffect(session) {
                session.state.map { it.trades }.distinctUntilChanged().collect { trades ->
                    if (registry.plugins.any { it === plugin }) runtime.analyticsCache.update(plugin.descriptor.id, accountId, trades)
                }
            }
            LaunchedEffect(mainSession, session) {
                if (mainSession !== session) mainSession.state.map { it.trades }.distinctUntilChanged().collect { trades ->
                    if (registry.plugins.any { it === plugin }) runtime.analyticsCache.update(plugin.descriptor.id, mainAccountId, trades)
                }
            }
            cached[plugin.descriptor.id].orEmpty()
        }
        val cachedTrades = cachedByAccount[accountId].orEmpty()
        val mainCachedTrades = cachedByAccount[mainAccountId].orEmpty()
        val analyticsTrades = remember(cachedTrades, account.trades) { mergeAnalyticsTrades(cachedTrades, account.trades) }
        val mainAnalyticsTrades = remember(mainCachedTrades, mainAccount.trades) { mergeAnalyticsTrades(mainCachedTrades, mainAccount.trades) }
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
        val workspaceAccountError = if (supportsDetachedAccounts) {
            val value by runtime.workspaceAccounts.error.collectAsState()
            value
        } else null
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
        var indicatorChartId by remember(plugin) { mutableStateOf(MAIN_CHART) }
        var parkedCharts by remember(plugin) {
            mutableStateOf((savedChart.extraCharts + savedChart.workspaceCells).associate {
                it.id to ChartSlot(it.id, it.symbols[plugin.descriptor.id] ?: chosenSymbol, Timeframe.ofSeconds(it.timeframeSeconds))
            })
        }
        /** Each tab is a workspace with its own root chart and up to three subcharts. */
        var chartOrder by remember(plugin) { mutableStateOf(savedChart.extraCharts.map { it.id }) }
        var workspaceOrder by remember(plugin) {
            val available = listOf(MAIN_CHART) + savedChart.extraCharts.map { it.id }
            val saved = savedChart.workspaceOrder.filter { it in available }.distinct()
            mutableStateOf(saved + available.filterNot { it in saved })
        }
        val workspaceLayouts = remember(plugin) { mutableStateMapOf<String, ChartSplitLayout>().apply {
            savedChart.workspaceLayouts.forEach { (id, name) -> ChartSplitLayout.entries.firstOrNull { it.name == name }?.let { put(id, it) } }
        } }
        val workspaceRatios = remember(plugin) { mutableStateMapOf<String, MutableMap<String, Float>>().apply {
            (listOf(MAIN_CHART) + savedChart.extraCharts.map { it.id }).forEach { id ->
                put(id, mutableStateMapOf<String, Float>().apply { putAll(savedChart.workspaceRatios[id].orEmpty()) })
            }
        } }
        val canDetachWorkspaces = LocalChartPlatform.current == ChartPlatform.DESKTOP && LocalPanelWindowHost.current != null
        var detachedWorkspaces by remember(plugin, canDetachWorkspaces) {
            mutableStateOf(if (canDetachWorkspaces) savedChart.detachedWorkspaces.intersect(savedChart.extraCharts.map { it.id }.toSet() + MAIN_CHART) else emptySet())
        }
        var selectedWorkspace by remember(plugin) {
            val available = (listOf(MAIN_CHART) + savedChart.extraCharts.map { it.id }).filterNot { it in detachedWorkspaces }
            mutableStateOf(savedChart.selectedWorkspace.takeIf { it in available } ?: available.firstOrNull() ?: MAIN_CHART)
        }
        val workspaceSelected = remember(plugin) { mutableStateMapOf<String, String>().apply {
            (listOf(MAIN_CHART) + savedChart.extraCharts.map { it.id }).forEach { workspace ->
                val candidate = savedChart.workspaceSelections[workspace] ?: workspace
                val count = (workspaceLayouts[workspace] ?: ChartSplitLayout.SINGLE).count
                val validIds = (0 until count).map { index ->
                    if (index == 0) workspace else if (workspace == MAIN_CHART) "split$index" else "${workspace}s$index"
                }
                if (candidate in validIds && (candidate == MAIN_CHART || candidate in parkedCharts)) put(workspace, candidate)
            }
        } }
        fun workspaceOf(id: String): String = if (id == MAIN_CHART || id.startsWith("split")) MAIN_CHART else id.substringBefore("s")
        fun cellId(workspace: String, index: Int): String = if (index == 0) workspace
            else if (workspace == MAIN_CHART) "split$index" else "${workspace}s$index"
        fun layoutOf(workspace: String) = workspaceLayouts[workspace] ?: ChartSplitLayout.SINGLE
        var chartOpening by remember(plugin) { mutableStateOf(false) }
        var showResetLayout by remember { mutableStateOf(false) }
        // The chart's symbol menu lists the market page's watch list, in its order.
        val watchLists by runtime.marketWatch.lists.collectAsState()
        val watchSymbols = watchLists[plugin.descriptor.id].orEmpty().toSet()
        // Keep loading and verifying the saved list while mobile navigation shows another page. A chart window's symbol is
        // loaded, verified and quoted the same way, so its chart is complete before it is the one being worked on.
        val visibleWorkspaces = detachedWorkspaces + selectedWorkspace
        val visibleParkedCharts = parkedCharts.filterKeys { id ->
            val workspace = workspaceOf(id)
            workspace in visibleWorkspaces && (0 until layoutOf(workspace).count).any { cellId(workspace, it) == id }
        }
        val chartSymbols = visibleParkedCharts.values.mapTo(mutableSetOf()) { it.symbol }
        // Every visible tab needs its own live last trade, even while another workspace is selected.
        var tabletWorkspaceTabsVisible by remember { mutableStateOf(false) }
        if ((LocalChartPlatform.current == ChartPlatform.DESKTOP && LocalPanelWindowHost.current != null) || tabletWorkspaceTabsVisible) {
            (listOf(MAIN_CHART) + chartOrder).forEach { workspace ->
                val selected = workspaceSelected[workspace] ?: workspace
                val tabSymbol = if (selected == activeChart) restoring ?: symbol else parkedCharts[selected]?.symbol
                if (tabSymbol != null) chartSymbols.add(tabSymbol)
            }
        }
        LaunchedEffect(plugin, watchSymbols, chartSymbols) { plugin.watchQuotes(watchSymbols + chartSymbols) }
        DisposableEffect(plugin) { onDispose { plugin.watchQuotes(emptySet()) } }
        val watchInstruments = watchLists[plugin.descriptor.id].orEmpty().mapNotNull { saved -> catalog.instruments.firstOrNull { it.symbol == saved } }
        val orderSessions = remember(accountKey) { mutableMapOf<String, MobileOrderSession>() }
        val orderSession = remember(accountKey, activeChart, symbol, executionSymbol) { orderSessions.getOrPut("$activeChart/$symbol/$executionSymbol") { MobileOrderSession(instrument) } }
        var draft by orderSession.draft
        val draftVisibility = remember(accountKey) { mutableMapOf<String, MutableState<Boolean>>() }
        var showDraft by remember(accountKey, activeChart, symbol) { draftVisibility.getOrPut("$activeChart/$symbol") { mutableStateOf(false) } }
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
        val domSupported = plugin.orderFlow != null
        val orderFlowSupported = domSupported && plugin.descriptor.environment !in setOf(BrokerEnvironment.REMOTE_DEMO, BrokerEnvironment.LIVE)
        var showOrderFlow by remember(plugin) { mutableStateOf(false) }
        var showDom by remember(plugin) { mutableStateOf(false) }
        // A broker currently exposes one depth stream. Each detached window may open DOM,
        // but the stream follows the focused window and never displays another symbol's book.
        val detachedDomOpen = remember(plugin) { mutableStateMapOf<String, Boolean>() }
        val detachedOrderRequests = remember(plugin) { mutableStateMapOf<String, Int>() }
        var orderPanelState by orderSession.panelState
        // The docked order bar's "更多设置" fold. It opens at once and is saved, so the next order and the next start find it as it was left.
        // When the latest change cannot be saved the fold goes back to the saved one, as the preferences themselves do.
        var advancedOrder by remember(runtime) { mutableStateOf(runtime.tradingPreferences.preferences.value.orderMoreSettings) }
        var advancedChanges by remember(runtime) { mutableIntStateOf(0) }
        val orderOpen = orderPanelState == MobileOrderPanelState.EDITING
        val orderActive = orderPanelState != MobileOrderPanelState.CLOSED
        var busy by remember { mutableStateOf(false) }
        var flattenReview by remember { mutableStateOf<FlattenReview?>(null) }
        var flattenResults by remember { mutableStateOf<List<FlattenOutcome>?>(null) }
        var flattening by remember { mutableStateOf(false) }
        // A detached window must not change the account behind another window's pending order or edit.
        fun accountOperationPending() = busy || confirmation != null || flattenReview != null || flattening || flattenResults != null ||
            protection != null || pendingEdit != null || pendingProtection != null || standaloneEdit != null
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
        // The first unread failed flatten takes the notice's place whenever there is no other notice. A later notice only puts
        // it off, and an account switch, which clears the notice of the moment, leaves it: it goes when it is dismissed.
        fun shownNotice() = notice ?: unreadFailures.firstOrNull()?.let(::failureNotice)
        // Only the card of a failure takes that failure off. A receipt can be dismissed twice: the minimized sheet keeps its
        // form composed, with a second card of the notice that times out beside the one over the chart.
        fun dismissNotice(current: TradingNotice) {
            if (notice?.id == current.id) notice = null
            else unreadFailures.firstOrNull { failureNotice(it) == current }?.let(runtime.accountRisk::acknowledge)
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
        val historyVisible by runtime.chartPreferences.historyVisible.collectAsState()
        val priceAxis by runtime.chartPreferences.priceAxis.collectAsState()
        val lockedCrosshairTime by runtime.chartLockedTime.collectAsState()
        val intervals = IntervalOptions(chartPreferences.favorites, chartPreferences.custom,
            onToggleFavorite = { frame -> scope.launch { runtime.chartPreferences.toggleFavorite(frame) } },
            onAddCustom = { frame -> scope.launch { runtime.chartPreferences.addCustom(frame) } })
        // The watch list's history is read ahead and cached, so a chart that opens only fetches the bars since.
        val preload = remember(watchLists[plugin.descriptor.id], symbol, timeframe, chartPreferences.favorites) {
            historyPreloadSeries(watchLists[plugin.descriptor.id].orEmpty(), symbol, timeframe, chartPreferences.favorites)
        }
        LaunchedEffect(plugin, preload) { plugin.preloadHistory(preload) }
        DisposableEffect(plugin) { onDispose { plugin.preloadHistory(emptyList()) } }
        val hedging = plugin.capabilities.hedging
        val orderRule = riskRule ?: AccountRiskRule()
        /**
         * [order] as the account's cash brackets or its sizing make it at the quote [at], under the server's order limit
         * [limit] when that is known. An order that only reduces the position held goes as it is: the guard uses the same rule.
         */
        fun bracketed(order: OrderRequest, at: Quote?, limit: Long?): OrderRequest =
            orderRule.bracketed(order, instrument, session.account.currency, at, account, hedging, limit)
        // Sizing gives way to a quantity typed by hand while the draft works against the position held: these are the lots it may take off.
        val reducible = if (!sizeByRisk || hedging || executionSymbol == null) 0L else symbolExposure(account, executionSymbol).closable(draft.side)
        val (serverCapacity, refreshCapacity) = rememberQuantityCapacity(runtime.orderCapacity, plugin.descriptor.id, session, instrument, draft, account,
            market.tradingReady, localDemo)
        val orderInput = draft.copy(stopLossEnabled = draft.stopLossEnabled && !brackets.controls(true),
            takeProfitEnabled = draft.takeProfitEnabled && !brackets.controls(false))
        val builtOrder = runCatching {
            val input = when {
                !sizeByRisk -> orderInput.request(instrument)
                // A reduction has no use for the stop that sizes an entry, and takes off no more than the position.
                reducible > 0 -> orderInput.copy(stopLossEnabled = false).request(instrument)?.also { order ->
                    // Only a quantity outside 1..reducible makes it an opening order, which needs the stop this branch leaves out.
                    require(!orderRule.bracketsApply(account, hedging, order)) {
                        if (order.lotsHundredths <= 0) "请输入有效的减仓数量"
                        else "减仓数量不能超过持仓的 ${instrument.formatQuantity(reducible)} ${instrument.quantityUnit}；反手请先平仓"
                    }
                    // Beside the orders already working against the position it could fill after them and open the other
                    // side with no stop: the guard refuses that, so the form says it first. A stop order may go.
                    orderRule.reductionRefusal(account, hedging, order)?.let { throw IllegalArgumentException(it) }
                }
                else -> {
                    require(instrument.parsePrice(orderInput.stopLoss)?.let { it > 0 } == true) { "请输入有效止损价 SL" }
                    orderInput.copy(stopLossEnabled = true).request(instrument, quantityOverride = instrument.minLotsHundredths)
                }
            }
            input?.let { bracketed(it, quote, serverCapacity.maximum) }
        }
        val request = builtOrder.getOrNull()
        // Keep an editable chart draft even before SL sets a valid quantity. Only request can be submitted.
        val chartDraft = request ?: if (sizeByRisk && reducible == 0L) orderInput.copy(
            stopLossEnabled = instrument.parsePrice(orderInput.stopLoss)?.let { it > 0 } == true,
            takeProfitEnabled = orderInput.takeProfitEnabled && instrument.parsePrice(orderInput.takeProfit)?.let { it > 0 } == true,
        ).request(instrument, quantityOverride = 0L) else null
        val bracketError = builtOrder.exceptionOrNull()?.message
        // An order against the position goes without the account's brackets and the sizing stop; the minimized bar shows them off.
        val reduction = request != null && brackets.enabled && !orderRule.bracketsApply(account, hedging, request)
        // The order forms read every bound on the quantity from here: the server's limit, the position a reduction works
        // against, and the ceiling that cut a sized quantity short.
        val capacity = serverCapacity.copy(reducible = reducible.takeIf { it > 0 }, sizedUnder = request?.takeIf { sizeByRisk && reducible == 0L }?.let {
            brackets.sizingLimit(it, instrument, quote, orderRule.lotRoom(account, hedging, it), serverCapacity.maximum)
        })
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
        LaunchedEffect(runtime) { runtime.run() }
        // Every chart window stays live; the broker serves the one being worked on first.
        val watchedCharts = listOf(ChartSlot(activeChart, symbol, timeframe)) + visibleParkedCharts.values.filter { it.id != activeChart }
        val chartSources = watchedCharts.map { slot ->
            val regular = chartPreferences.sessionForChart(slot.id) == ChartSession.REGULAR
            (slot.symbol to chartSessionSource(slot.symbol, slot.timeframe, regular)) to regular
        }
        val liveSeries = chartSources.map { it.first }.distinct()
        val extendedSeries = chartSources.filter { it.second && it.first.second == Timeframe.M1 }.map { it.first }.toSet()
        LaunchedEffect(plugin, executionSymbol, liveSeries, extendedSeries) { plugin.watchAllWithExtendedHistory(liveSeries, extendedSeries) }
        val focusedDetached = detachedWorkspaces.firstOrNull { chartWindowKey(it) == activeWindow }
        val detachedDomActive = focusedDetached?.let { detachedDomOpen[it] == true && workspaceOf(activeChart) == it } == true
        val mainDomActive = focusedDetached == null && (showOrderFlow || showDom)
        LaunchedEffect(plugin, symbol, executionSymbol, mainDomActive, detachedDomActive, mobileTab) {
            plugin.watchOrderFlow(symbol.takeIf { mainDomActive || detachedDomActive })
        }
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

        val workspaceCellIds = (listOf(MAIN_CHART) + chartOrder).flatMap { ws -> (1..3).map { cellId(ws, it) } }
        val workspaceCells = workspaceCellIds.mapNotNull(::chartSlot)
        val savedLayouts = workspaceLayouts.toMap().mapValues { it.value.name }
        val savedRatios = workspaceRatios.mapValues { it.value.toMap() }
        val savedSelections = workspaceSelected.toMap()
        val savedDetachedAccounts = detachedAccounts.toMap()
        LaunchedEffect(plugin, workspaceCells, savedLayouts, savedRatios, savedSelections, detachedWorkspaces, selectedWorkspace, workspaceOrder) {
            runtime.chartPreferences.setWorkspaces(plugin.descriptor.id, savedLayouts, savedRatios,
                workspaceCells.map { Triple(it.id, it.symbol, it.timeframe) }, savedSelections, detachedWorkspaces, selectedWorkspace, workspaceOrder)
        }
        LaunchedEffect(plugin, savedDetachedAccounts, detachedWorkspaces, supportsDetachedAccounts) {
            if (supportsDetachedAccounts) runtime.workspaceAccounts.save(plugin.descriptor.id, savedDetachedAccounts.filterKeys { it in detachedWorkspaces })
        }

        val saveDesktopWorkspace by rememberUpdatedState<suspend () -> Boolean>({
            runtime.chartPreferences.setLastChart(plugin.descriptor.id, mainChart.symbol, mainChart.timeframe) &&
                runtime.chartPreferences.setExtraCharts(plugin.descriptor.id, extraCharts.map { Triple(it.id, it.symbol, it.timeframe) }) &&
                runtime.chartPreferences.setWorkspaces(plugin.descriptor.id, savedLayouts, savedRatios,
                    workspaceCells.map { Triple(it.id, it.symbol, it.timeframe) }, savedSelections, detachedWorkspaces, selectedWorkspace, workspaceOrder) &&
                (!supportsDetachedAccounts || runtime.workspaceAccounts.save(plugin.descriptor.id, savedDetachedAccounts.filterKeys { it in detachedWorkspaces })) &&
                runtime.desktopLayout.save() && runtime.flushChartDrawings()
        })
        SideEffect { runtime.desktopWorkspaceCount.value = 1 + chartOrder.size }
        DisposableEffect(runtime, plugin) {
            val saver: suspend () -> Boolean = { saveDesktopWorkspace() }
            runtime.desktopWorkspaceSaver = saver
            onDispose { if (runtime.desktopWorkspaceSaver === saver) { runtime.desktopWorkspaceSaver = null; runtime.desktopWorkspaceCount.value = 0 } }
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
            if (accountOperationPending()) return
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
            if (id != activeChart && accountOperationPending()) return
            workspaceSelected[workspaceOf(id)] = id
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
        fun openChart(detached: Boolean = false) {
            if (chartOpening || accountOperationPending()) return
            if (chartOrder.size >= ChartPreferences.MAX_EXTRA_CHARTS) { showNotice("最多同时打开 ${ChartPreferences.MAX_EXTRA_CHARTS} 个图表窗口", attention = true); return }
            val id = generateSequence(1) { it + 1 }.map { "c$it" }.first { it !in chartOrder }
            val sourceId = activeChart
            val sourceSymbol = restoring ?: chosenSymbol
            val sourceFrame = timeframe
            val sourceIndicators = runtime.indicatorsForChart(sourceId).settings.value
            chartOpening = true
            scope.launch {
                try {
                    runtime.drawingsForChart(id).copySymbolFrom(runtime.drawingsForChart(sourceId), sourceSymbol)
                    if (!runtime.indicatorsForChart(id).apply(sourceIndicators)) error("指标设置无法保存")
                    parkedCharts = parkedCharts + (id to ChartSlot(id, sourceSymbol, sourceFrame))
                    chartOrder = chartOrder + id
                    workspaceOrder = workspaceOrder + id
                    workspaceLayouts[id] = ChartSplitLayout.SINGLE
                    workspaceRatios[id] = mutableStateMapOf()
                    if (detached) { detachedAccounts[id] = accountId; unresolvedDetachedAccounts.remove(id); detachedWorkspaces = detachedWorkspaces + id }
                    else selectedWorkspace = id
                    activateChart(id)
                } catch (cancel: CancellationException) { throw cancel }
                  catch (error: Exception) { showNotice("新建窗口失败：${error.message ?: "请重试"}", attention = true) }
                finally { chartOpening = false }
            }
        }
        fun selectSplitLayout(workspace: String, layout: ChartSplitLayout) {
            if (accountOperationPending()) return
            val ids = (0 until layout.count).map { cellId(workspace, it) }
            if ((workspaceSelected[workspace] ?: workspace) !in ids) workspaceSelected[workspace] = workspace
            if (workspaceOf(activeChart) == workspace && activeChart !in ids) activateChart(workspace)
            val source = chartSlot(workspaceSelected[workspace] ?: workspace) ?: ChartSlot(workspace, symbol, timeframe)
            val missing = ids.filter { it != activeChart && it !in parkedCharts }
            parkedCharts = parkedCharts + missing.associateWith { ChartSlot(it, source.symbol, source.timeframe) }
            workspaceLayouts[workspace] = layout
        }
        fun reorderWorkspaces(visibleOrder: List<String>) {
            val currentVisible = workspaceOrder.filterNot { it in detachedWorkspaces }
            if (visibleOrder.size != currentVisible.size || visibleOrder.toSet() != currentVisible.toSet()) return
            val reordered = visibleOrder.iterator()
            workspaceOrder = workspaceOrder.map { if (it in detachedWorkspaces) it else reordered.next() }
        }
        fun selectWorkspace(id: String) {
            if (accountOperationPending()) return
            if (id in detachedWorkspaces) return
            activeWindow = null
            selectedWorkspace = id
            activateChart(workspaceSelected[id] ?: id)
        }
        fun detachWorkspace(id: String) {
            if (accountOperationPending()) return
            if (id in detachedWorkspaces) return
            val remaining = (listOf(MAIN_CHART) + chartOrder).filter { it != id && it !in detachedWorkspaces }
            if (remaining.isEmpty()) { showNotice("请先新建一个布局，再拖出当前布局", attention = true); return }
            detachedAccounts[id] = if (workspaceOf(activeChart) == id) accountId else mainAccountId
            unresolvedDetachedAccounts.remove(id)
            detachedWorkspaces = detachedWorkspaces + id
            if (selectedWorkspace == id) selectWorkspace(remaining.first())
        }
        fun dockWorkspace(id: String) {
            if (accountOperationPending()) return
            detachedWorkspaces = detachedWorkspaces - id
            detachedAccounts.remove(id)
            unresolvedDetachedAccounts.remove(id)
            detachedOrderRequests.remove(id)
            selectedWorkspace = id
            activateChart(workspaceSelected[id] ?: id)
        }
        fun closeChart(id: String) {
            if (accountOperationPending()) return
            if (id == MAIN_CHART) return
            val fallback = (listOf(MAIN_CHART) + chartOrder).firstOrNull { it != id && it !in detachedWorkspaces } ?: MAIN_CHART
            if (workspaceOf(activeChart) == id) activateChart(fallback)
            if (selectedWorkspace == id) selectedWorkspace = fallback
            detachedWorkspaces = detachedWorkspaces - id
            detachedAccounts.remove(id)
            unresolvedDetachedAccounts.remove(id)
            detachedOrderRequests.remove(id)
            parkedCharts = parkedCharts.filterKeys { workspaceOf(it) != id }
            workspaceLayouts.remove(id)
            workspaceRatios.remove(id)
            workspaceSelected.remove(id)
            chartOrder = chartOrder - id
            workspaceOrder = workspaceOrder - id
            runtime.desktopLayout.setWindowBounds(chartWindowKey(id), null)
            scope.launch { runtime.chartPreferences.clearChartSessions((0..3).map { cellId(id, it) }.toSet()); runtime.desktopLayout.save() }
        }
        val restoreDesktopWorkspace = LocalChartPlatform.current == ChartPlatform.DESKTOP && panelWindows
        LaunchedEffect(plugin, restoreDesktopWorkspace) {
            if (restoreDesktopWorkspace) activateChart(workspaceSelected[selectedWorkspace] ?: selectedWorkspace)
        }
        fun clearAccountEditors() {
            confirmation = null; protection = null; pendingEdit = null; pendingProtection = null; standaloneEdit = null
            // A review opened in another window must not survive a global account change.
            flattenReview = null
            focusedTrade = null; focusedExecution = null; selectedPositionId = null
            showOrder = false; showDraft = false; orderPanelState = MobileOrderPanelState.CLOSED
        }
        fun chooseBroker(value: String) {
            if (!accountOperationPending() && value != plugin.descriptor.id) {
                clearAccountEditors(); selectBroker(value)
            }
        }
        fun chooseAccount(value: String) {
            if (!accountOperationPending() && catalog.accounts.any { it.id == value }) {
                if (value != mainAccountId) clearAccountEditors()
                manuallySelected = true
                chosenAccountId = value
                loginScope.launch { runtime.savedLogins.select(plugin.descriptor.id, value) }
            }
        }
        fun chooseDetachedAccount(workspace: String, value: String) {
            if (accountOperationPending() ||
                workspace !in detachedWorkspaces || catalog.accounts.none { it.id == value } ||
                (plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.firstOrNull()) !== plugin) return
            if ((detachedAccounts[workspace] ?: mainAccountId) != value) clearAccountEditors()
            detachedAccounts[workspace] = value
            unresolvedDetachedAccounts.remove(workspace)
            activeWindow = chartWindowKey(workspace)
            activateChart(workspaceSelected[workspace] ?: workspace)
        }
        /** An account switch in another window can precede this window's next recomposition. */
        fun accountContextCurrent(): Boolean {
            val currentBroker = plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.firstOrNull()
            val workspace = workspaceOf(activeChart)
            if (workspace in unresolvedDetachedAccounts) return false
            val detached = detachedAccounts[workspace]
            if (detached != null && catalog.accounts.none { it.id == detached }) return false
            val currentAccount = detached ?: chosenAccountId.takeIf { id -> catalog.accounts.any { it.id == id } } ?: catalog.accounts.first().id
            return currentBroker === plugin && currentAccount == accountId
        }
        fun changeDraft(value: OrderDraft) {
            // Side/kind changes may seed reference protection for a manual ticket. Sizing uses the user's SL.
            draft = if (sizeByRisk && (value.side != draft.side || value.kind != draft.kind))
                value.copy(stopLoss = draft.stopLoss, stopLossEnabled = draft.stopLossEnabled)
            else value
            showDraft = true
        }
        fun perform(onAccepted: () -> Unit = {}, requireCurrentAccount: Boolean = true, action: suspend () -> TradeResult) {
            if (busy) return
            if (requireCurrentAccount && !accountContextCurrent()) {
                showNotice("账户已切换，请重新检查后操作", attention = true)
                return
            }
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
            val density = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(density)
            val keyboardOpen = imeBottom > 0
            val focusManager = LocalFocusManager.current
            val windowChromeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(windowChromeInsets)) {
                val nativeDesktop = LocalChartPlatform.current == ChartPlatform.DESKTOP
                // Measure the workspace outside IME padding. On platforms that resize the host view first,
                // defer a height decrease briefly until the IME visibility signal has had time to arrive.
                val unoccludedHeight = remember(maxWidth, nativeDesktop) { mutableStateOf(maxHeight) }
                val observedHeight = maxHeight
                LaunchedEffect(observedHeight, keyboardOpen) {
                    if (!keyboardOpen) {
                        if (observedHeight < unoccludedHeight.value) delay(1500)
                        unoccludedHeight.value = observedHeight
                    }
                }
                val layoutHeight = if (nativeDesktop) maxHeight else maxOf(unoccludedHeight.value, maxHeight)
                // Tablet sizes use the desktop workspace in both orientations. The actual window host is
                // still desktop-only, so tabs can be reordered on tablets but cannot become OS windows.
                val systemBars = WindowInsets.systemBars
                val direction = LocalLayoutDirection.current
                val sizeWithBars = with(density) {
                    DpSize(maxWidth + (systemBars.getLeft(density, direction) + systemBars.getRight(density, direction)).toDp(),
                        layoutHeight + (systemBars.getTop(density) + systemBars.getBottom(density)).toDp())
                }
                val tablet = !nativeDesktop && minOf(sizeWithBars.width, sizeWithBars.height) >= 600.dp
                val desktop = tablet || (maxWidth >= 900.dp && layoutHeight >= 600.dp)
                val desktopStyle = nativeDesktop || tablet
                val wide = desktop && maxWidth >= 1240.dp
                val compact = !desktop
                val accountPickerWidth = if (maxWidth >= 1200.dp) 320.dp else 240.dp
                val desktopPickerWidth = if (tablet) 264.dp else if (maxWidth >= 1200.dp) 340.dp else 280.dp
                val tabletWorkspaces = tablet
                SideEffect { tabletWorkspaceTabsVisible = tabletWorkspaces }
                val chartWindowsShown = (nativeDesktop && desktop && panelWindows) || tabletWorkspaces
                val splitAvailable = desktop || (maxWidth >= 600.dp && layoutHeight >= 550.dp)
                LaunchedEffect(chartWindowsShown) {
                    if (chartWindowsShown) activateChart(workspaceSelected[selectedWorkspace] ?: selectedWorkspace)
                }
                LaunchedEffect(chartWindowsShown, splitAvailable, activeChart) {
                    val workspace = workspaceOf(activeChart)
                    val visibleSplit = splitAvailable && workspace == MAIN_CHART &&
                        (0 until layoutOf(MAIN_CHART).count).any { cellId(MAIN_CHART, it) == activeChart }
                    if (!chartWindowsShown && !visibleSplit) activateChart(MAIN_CHART)
                }
                val compactShort = compact && layoutHeight < 550.dp
                // Compact windows wide enough for side navigation: landscape phones and portrait tablets.
                val rail = compact && (maxWidth >= 600.dp || (maxWidth >= 480.dp && maxWidth > layoutHeight))
                val denseLandscape = compactShort && rail
                val landscapeDock = rail && maxWidth > layoutHeight
                val tradeDockHeight = if (denseLandscape) 48.dp else MobileTradeBarHeight
                val environment = plugin.descriptor.environment
                val syncAction: (() -> Unit)? = if (plugin.capabilities.resynchronize) ({ syncAccount() }) else null
                val barStatus: @Composable () -> Unit = {
                    EnvironmentBadge(environment)
                    if (localDemo) Spacer(Modifier.width(12.dp)) else ConnectionStatusControl(market, compact = true, busy, syncAction)
                }
                val selectTab: (Int) -> Unit = { tab ->
                    if (mobileTab != tab) { showActivity = false; showDom = false }
                    showOrderFlow = false
                    mobileTab = tab
                }
                // The order page of compact screens is the one chosen in the settings: the sheet over the chart, which shares
                // the chart surface and never dims it, the dock measured alongside the chart, or a dialog.
                val chartFirst = compact && mobileTab == 1 && !showOrderFlow
                val unifiedChartBar = desktop || (tablet && chartFirst)
                val orderLayout = tradingPreferences.orderPanelLayout
                val useOrderPopup = orderLayout == OrderPanelLayout.POPUP
                // Context-menu orders use the right-hand ticket even in a narrower desktop/tablet workspace.
                var contextOrderSidebar by remember { mutableStateOf(false) }
                var contextOrderRequest by remember { mutableIntStateOf(0) }
                // The sheet and the dock stay on the chart page for as long as an order is being prepared; the popup does not.
                val orderOnChart = orderActive && !useOrderPopup
                val popupVisible = chartFirst && useOrderPopup && orderOpen && confirmation !is Confirmation.Place
                val panelOpen = popupVisible || chartFirst && orderOnChart && orderOpen
                // The receipt inside the dock's and the popup's form; the sheet shows it above its form, as it always did.
                val orderNotice: @Composable () -> Unit = {
                    shownNotice()?.let { current ->
                        Box(Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp)) {
                            FloatingNoticeCard(current, "trade-notice", { dismissNotice(current) }, title = "操作提示")
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
                    val refreshed = bracketed(order, plugin.market.value.quotes[order.symbol], capacityFor(order).maximum)
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
                fun openChartOrderEditor(chartId: String) {
                    val workspace = workspaceOf(chartId)
                    if (desktop && workspace in detachedWorkspaces)
                        detachedOrderRequests[workspace] = (detachedOrderRequests[workspace] ?: 0) + 1
                    else if (desktop) {
                        contextOrderSidebar = true
                        contextOrderRequest++
                        showOrder = false
                        showDom = false
                        runtime.desktopLayout.setDetached(WorkspacePanel.ORDER, false)
                        runtime.desktopLayout.setOpen(DesktopPanel.MARKET, false)
                        runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true)
                        scope.launch { runtime.desktopLayout.save() }
                    }
                    else openOrderEditor()
                }
                fun placePreview(order: OrderRequest): OrderPreview {
                    val checked = session.preview(order).withCapacity(order, capacityFor(order))
                    // A market order is repriced by the guard as it goes out. A priced entry has to meet the amounts as it
                    // stands; an order that just reduces the position has none to meet.
                    if (!checked.valid || order.kind == OrderKind.MARKET || !orderRule.bracketsApply(account, hedging, order)) return checked
                    val error = brackets.refusal(order, instrument, session.account.currency, checked.entryPrice)
                    return if (error == null) checked else checked.copy(errors = listOf(error))
                }
                fun executeTrade(action: Confirmation) {
                    if (busy) return
                    if (action !is Confirmation.Reset && !accountContextCurrent()) {
                        showNotice("账户已切换，请重新检查后操作", attention = true)
                        return
                    }
                    when (action) {
                        is Confirmation.Place -> {
                            if ((restoring != null && restoring != symbol) || action.request.symbol != executionSymbol) {
                                showNotice("当前图表品种已变更或尚未加载，请重新检查订单", attention = true)
                                return
                            }
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
                        Confirmation.Reset -> perform(requireCurrentAccount = false) { runtime.resetAll(); TradeResult(true, "已重置全部本地模拟数据") }
                    }
                }
                fun reviewTrade(action: Confirmation) {
                    if (busy) return
                    if (action !is Confirmation.Reset && !accountContextCurrent()) {
                        showNotice("账户已切换，请重新检查后操作", attention = true)
                        return
                    }
                    if (action is Confirmation.Place) {
                        if ((restoring != null && restoring != symbol) || action.request.symbol != executionSymbol) {
                            showNotice("当前图表品种已变更或尚未加载，请重新检查订单", attention = true)
                            return
                        }
                        // The guard's own classification, asked before a confirmation opens: an exit passes a lock and the limits.
                        runtime.accountRisk.refusal(plugin, session, RiskCheck.Place(action.request))?.let {
                            showNotice(it, attention = true)
                            return
                        }
                    }
                    if (skipConfirmation && action !is Confirmation.Reset) executeTrade(action)
                    else confirmation = action
                }
                // The order pages get this function as a reference and today compare it by instance. References to one local
                // function are equal whatever draft each captured, so a page that compared them by equality would submit the
                // draft it first saw; read through these states, every reference submits the draft as it is when pressed.
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
                        LineAction.SubmitDraft -> {
                            val order = request
                            if (order == null || preview?.valid != true || order.lotsHundredths <= 0L)
                                showNotice(preview?.errors?.firstOrNull() ?: "订单参数无效，请检查数量与报价", attention = true)
                            else reviewTrade(Confirmation.Place(order, fromChart = true))
                        }
                        LineAction.EditDraftQuantity -> Unit
                        LineAction.CloseDraft -> if (compact) closeOrder() else showDraft = false
                    }
                }
                /**
                 * An order requested by a trading shortcut. It uses the same quantity, account brackets and checks as the
                 * order panel; unlike a market order, a limit carries the hovered [price].
                 */
                fun orderFromChart(side: Side, kind: OrderKind, price: Long?, fromKeys: Boolean = false) {
                    val entry = price?.let(instrument::formatPrice).orEmpty()
                    if (sizeByRisk) {
                        draft = OrderDraft(side, kind, draft.quantity, entry)
                        showDraft = true
                        openChartOrderEditor(activeChart)
                        return
                    }
                    val order = OrderDraft(side, kind, draft.quantity, entry).request(instrument)
                    if (order == null) {
                        showNotice(if (instrument.executionSymbol == null) "${instrument.executionLabel}，暂不可下单" else "请先在下单面板输入有效数量", attention = true)
                        return
                    }
                    val protectedOrder = try { bracketed(order, quote, capacityFor(order).maximum) }
                    catch (error: IllegalArgumentException) {
                        showNotice(error.message ?: "无法计算金额止损止盈", attention = true)
                        return
                    }
                    reviewTrade(Confirmation.Place(protectedOrder, fromChart = true, fromKeys = fromKeys))
                }
                /** Context-menu choices only prepare a draft; the user submits it from the order panel. */
                fun prepareChartOrder(chartId: String, side: Side, kind: OrderKind, price: Long?) {
                    val entry = price?.takeUnless { kind == OrderKind.MARKET }?.let(instrument::formatPrice).orEmpty()
                    draft = OrderDraft(side, kind, draft.quantity, entry)
                    showDraft = true
                    activeWindow = workspaceOf(chartId).takeIf { it in detachedWorkspaces }?.let(::chartWindowKey)
                    openChartOrderEditor(chartId)
                }
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && mobileTab == 3 && showActivity, onBackCompleted = { showActivity = false })
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && showOrderFlow, onBackCompleted = { showOrderFlow = false })
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = compact && showDom, onBackCompleted = { showDom = false })
                // The popup put away with its draft on the chart: back drops the draft, as back on a minimized sheet or dock does.
                NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = chartFirst && useOrderPopup && orderPanelState == MobileOrderPanelState.MINIMIZED && !keyboardOpen,
                    onBackCompleted = { if (!busy) closeOrder() })
                val activate = rememberUpdatedState<(String) -> Unit> { id -> activateChart(id) }
                // One chart window. The active one shows the screen's symbol, interval, draft and selection; any other shows
                // its own symbol and interval and becomes the active one at the first press inside it.
                // What every chart shows, as this pass of the screen saw it. A chart's content decides from these alone, never from
                // the state behind them: in a window it is composed apart from the screen, and a newer active chart beside the
                // instrument of the previous one would show a wrong chart for a frame and drop its view.
                val workingChart = activeChart
                val shownCharts = parkedCharts + (workingChart to ChartSlot(workingChart, symbol, timeframe))
                val layerStates = remember(plugin) { mutableMapOf<String, ChartLayerState>() }
                fun layersFor(id: String) = layerStates.getOrPut(id) { ChartLayerState(historyVisible) }
                val chartView: @Composable (String, Modifier, Boolean) -> Unit = { chartId, m, shared ->
                    val active = chartId == workingChart
                    val chartWorkspace = workspaceOf(chartId)
                    val displayedAccountId = if (chartWorkspace in unresolvedDetachedAccounts) "" else detachedAccounts[chartWorkspace] ?: mainAccountId
                    val displayedSession = remember(plugin, displayedAccountId, session, mainSession, catalog.accounts) {
                        if (catalog.accounts.none { it.id == displayedAccountId }) null
                        else if (displayedAccountId == accountId) session
                        else if (displayedAccountId == mainAccountId) mainSession
                        else runtime.accountRisk.guard(plugin, plugin.openSession(displayedAccountId))
                    }
                    val displayedAccount = if (displayedSession == null) AccountSnapshot(displayedAccountId, 0, balanceKnown = false, equityKnown = false, marginKnown = false)
                    else {
                        val current by key(displayedSession) { displayedSession.state.collectAsState() }
                        current
                    }
                    val parked = shownCharts[chartId]
                    val shownInstrument = if (active) instrument else parked?.let { slot -> catalog.instruments.firstOrNull { it.symbol == slot.symbol } }
                    // A symbol this broker has not loaded: the window says so, and a press makes it the active chart, where another can be chosen.
                    if (shownInstrument == null) Box(m.background(colors.Surface).pointerInput(chartId) { detectTapGestures { activate.value(chartId) } }.testTag("chart-unavailable-$chartId"),
                        contentAlignment = Alignment.Center) {
                        EmptyState("品种未加载", "${parked?.symbol.orEmpty()} 不在当前交易商已加载的合约里。点一下这里，再在图表上另选品种。", icon = AppIcons.Candlestick)
                    } else {
                    val shownTimeframe = parked?.timeframe ?: timeframe
                    val shownExecution = shownInstrument.executionSymbol
                    val shownQuote = if (active) quote else market.quotes[shownExecution]
                    val regularSession = chartPreferences.sessionForChart(chartId) == ChartSession.REGULAR
                    val sourceTimeframe = chartSessionSource(shownInstrument.symbol, shownTimeframe, regularSession)
                    val rawCandles = remember(plugin, shownInstrument.symbol, sourceTimeframe, market.revision) { plugin.history(shownInstrument.symbol, sourceTimeframe) }
                    val sessionResult = if (regularSession && shownTimeframe.seconds < Timeframe.DAY) {
                        // Keep the previous result visible while a new history snapshot is aggregated. Changing chart
                        // identity resets it immediately, so old candles never appear under another symbol or period.
                        key(plugin, shownInstrument.symbol, shownTimeframe, regularSession) {
                            val result by produceState(SessionResult(emptyList(), null), CandleSnapshotKey(rawCandles)) {
                                val calculated = withContext(Dispatchers.Default) {
                                    chartSessionCandles(rawCandles, shownInstrument.symbol, shownTimeframe, true) {
                                        coroutineContext.ensureActive()
                                    }
                                }
                                value = SessionResult(calculated.first, calculated.second)
                            }
                            result.candles to result.notice
                        }
                    } else rawCandles to null
                    val shownCandles = sessionResult.first
                    /** A step that belongs to the active chart. On any other chart it only makes that chart the active one, and the user repeats the step. */
                    fun onActive(block: () -> Unit) {
                        if (!active) activateChart(chartId)
                        else if (displayedSession != null && displayedAccountId == accountId && accountContextCurrent()) block()
                    }
                    // Minimizing hides only the form; the active draft remains editable on the chart.
                    val draftShown = active && displayedAccountId == accountId && accountContextCurrent() && if (compact) orderActive else showDraft
                    // Draft lines take a drop at once; pending orders and positions wait for the confirmation dialogs.
                    val actions = ChartOrderActions(
                        estimateDraft = { order ->
                            try {
                                val estimated = bracketed(order, quote, capacityFor(order).maximum)
                                estimated to session.preview(estimated).withCapacity(estimated, capacityFor(estimated))
                            } catch (error: IllegalArgumentException) {
                                (if (sizeByRisk) order.copy(lotsHundredths = 0L) else order) to
                                    OrderPreview(null, errors = listOf(error.message ?: "无法计算订单预览"))
                            }
                        },
                        // A reduction on a sized account carries no stop, so its draft offers none to add either.
                        draftStopManaged = brackets.controls(true) || reducible > 0,
                        draftTargetManaged = brackets.controls(false),
                        onDraftEntry = { price -> onActive { changeDraft(draft.copy(entry = instrument.formatPrice(price))) } },
                        onDraftProtection = { stop, price ->
                            onActive { changeDraft(draft.withProtectionPrice(stop, instrument.formatPrice(price))) }
                        },
                        editPending = plugin.capabilities.modifyPendingOrders,
                        estimatePending = displayedSession?.let { target -> target::previewOrderModification } ?: { _, _ -> OrderPreview(null) },
                        onPendingEntry = { order, price -> onActive { editPending(order, price, fromChart = true) } },
                        editPendingProtection = plugin.capabilities.modifyPendingProtection,
                        estimatePendingProtection = displayedSession?.let { target -> target::previewOrderProtection } ?: { _, _, _ -> OrderPreview(null) },
                        onPendingProtection = { order, sl, tp -> onActive { editPendingProtection(order, sl, tp, fromChart = true) } },
                        onProtection = { p, sl, tp -> onActive { editProtection(p, sl, tp, fromChart = true) } },
                        onLineAction = { action -> onActive { runLineAction(action) } },
                        draftQuantity = draft.quantity,
                        onDraftQuantity = { value -> onActive { changeDraft(draft.copy(quantity = value)) } },
                        inlineDraftQuantity = desktopStyle && !sizeByRisk,
                        directDraftSubmit = desktopStyle,
                        addProtection = plugin.capabilities.addProtection,
                        addPositionProtection = plugin.capabilities.addPositionProtection,
                        removeProtection = plugin.capabilities.removeProtection,
                        entryKinds = plugin.capabilities.orderKinds,
                        // A chart that is not the active one shows the quantity its symbol's order panel was left with.
                        entryLots = if (sizeByRisk) request?.lotsHundredths.takeIf { active }
                        else shownInstrument.parseQuantity(if (active) draft.quantity else
                            orderSessions["$chartId/${shownInstrument.symbol}/$shownExecution"]?.draft?.value?.quantity ?: if (shownInstrument.quantityDecimals == 0) "1" else "0.10"),
                        onEntryOrder = { side, kind, price -> onActive { prepareChartOrder(chartId, side, kind, price) } },
                        onKeyboardEntryOrder = { side, kind, price -> onActive { orderFromChart(side, kind, price, fromKeys = true) } },
                        estimateStandalone = { order, price -> displayedSession?.previewOrderChange(order.id, price, order.request.lotsHundredths) ?: OrderPreview(null) },
                        onStandalone = { order, price -> onActive { changeAlone(order, price, order.request.lotsHundredths, fromChart = true) } },
                        tradingKeys = tradingPreferences.tradingKeys.takeIf { tradingPreferences.keyboardTrading && OrderKind.MARKET in plugin.capabilities.orderKinds },
                        instantOrders = skipConfirmation,
                        stopRequiredForSizing = sizeByRisk,
                        attachedProtection = listOfNotNull("SL".takeIf { brackets.controls(true) }, "TP".takeIf { brackets.controls(false) })
                            .joinToString("/").ifEmpty { null },
                        onMarketOrder = { side -> onActive { orderFromChart(side, OrderKind.MARKET, null, fromKeys = true) } },
                    )
                    // Every chart has its own tools and selection; the drawings themselves are shared through the library.
                    val drawings = runtime.drawingsForChart(chartId)
                    val indicatorController = runtime.indicatorsForChart(chartId)
                    runtime.undoForChart(chartId, scope)
                    val chartIndicatorSettings by indicatorController.settings.collectAsState()
                    BoxWithConstraints(m.pointerInput(chartId) {
                        // On the Initial pass and without consuming: the press that makes this chart the active one still does what it did.
                        awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) activate.value(chartId) } }
                    }.testTag("chart-view-$chartId")) {
                    // Split cells can be narrower than the desktop window that contains them.
                    val chartCompact = compact || maxWidth < 600.dp
                    val chartShort = chartCompact && maxHeight < 550.dp
                    Row(Modifier.fillMaxSize()) {
                    if (!shared && (!chartCompact || drawings.toolbarOpen)) com.unitrading.chart.drawing.DrawingToolbar(drawings, chartIndicatorSettings.indicators.size, { scope.launch { indicatorController.apply(com.unitrading.app.IndicatorSettings()) } })
                    TradingChart("$brokerId/$displayedAccountId/${plugin.hashCode()}", shownInstrument, shownQuote, shownCandles, shownTimeframe, { frame -> activateChart(chartId); timeframe = frame },
                        displayedAccount.positions.filter { it.symbol == shownExecution },
                        displayedAccount.trades.filter { it.symbol == shownExecution },
                        selectedPositionId.takeIf { active }, focusedTrade.takeIf { active }, chartDraft.takeIf { draftShown }, preview.takeIf { draftShown },
                        displayedAccount.orders.filter { it.request.symbol == shownExecution }, actions,
                        Modifier.weight(1f).fillMaxHeight(), environment = plugin.descriptor.environment, executions = displayedAccount.executions.filter { it.symbol == shownExecution }, indicators = if (drawings.hideIndicators) emptyList() else chartIndicatorSettings.indicators, onIndicators = { activateChart(chartId); indicatorChartId = chartId; showIndicators = true }, focusedExecution = focusedExecution.takeIf { active },
                        instruments = watchInstruments, onSymbol = { value -> activateChart(chartId); chooseSymbol(value) }, intervals = intervals, connected = market.connected,
                        indicatorPaneFractions = chartPreferences.indicatorPaneFractions,
                        onIndicatorPaneFractions = { fractions -> scope.launch { runtime.chartPreferences.setIndicatorPaneFractions(fractions) } },
                        onSide = { side -> onActive {
                            val base = if (compact && orderPanelState == MobileOrderPanelState.CLOSED)
                                OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10") else draft
                            changeDraft(base.withSide(side, instrument, quote))
                            openChartOrderEditor(chartId)
                        } },
                        // The legend's fold is remembered once the user toggles it; until then short phone screens start folded.
                        chrome = ChartChrome(chartCompact, if (desktopStyle) chartPreferences.chartLegendCollapsed[chartId] ?: false else chartPreferences.legendCollapsed ?: (compactShort || chartShort),
                            { folded -> scope.launch { if (desktopStyle) runtime.chartPreferences.setChartLegendCollapsed(chartId, folded) else runtime.chartPreferences.setLegendCollapsed(folded) } },
                            ordering = compact && orderOnChart && orderOpen, drawings = drawings, showDrawingFavorites = active && !shared, views = runtime.chartViews,
                            showHistory = if (!shared || chartId == MAIN_CHART) historyVisible else null, onShowHistory = { visible ->
                                runtime.chartPreferences.selectHistoryVisible(visible)
                                scope.launch { runtime.chartPreferences.saveHistoryVisibility() }
                            },
                            sharedToolbar = shared, layerState = if (shared) layersFor(chartId) else null,
                            desktopAxisStyle = desktopStyle, tabletCompactAxis = tablet, showNavigation = !desktopStyle,
                            showWatermark = desktopStyle && chartPreferences.showChartWatermark,
                            chartTimeZone = chartPreferences.chartTimeZone, sessionNotice = sessionResult.second,
                            chartTimeZoneLabel = chartPreferences.chartTimeZone.labelAt(if (market.connected) market.quotes.values.maxOfOrNull { it.timeMs } else null),
                            regularSession = regularSession && shownTimeframe.seconds < Timeframe.DAY && sessionResult.second == null,
                            onOpenSettings = { showActivity = false; showSettings = true },
                            priceAxis = priceAxis, onPriceAxis = { value ->
                                runtime.chartPreferences.selectPriceAxis(value)
                                scope.launch { runtime.chartPreferences.savePriceAxis() }
                            },
                            lockedCrosshairTime = lockedCrosshairTime,
                            onLockedCrosshairTime = { runtime.chartLockedTime.value = it },
                            templates = runtime.chartTemplates,
                            onApplyTemplate = { template -> scope.launch {
                                if (indicatorController.apply(IndicatorSettings(template.indicators))) {
                                    runtime.chartPreferences.selectPriceAxis(template.axis)
                                    runtime.chartPreferences.savePriceAxis()
                                }
                            } },
                            onIndicatorChanges = { configs -> scope.launch { indicatorController.apply(IndicatorSettings(configs)) } },
                            allIndicators = chartIndicatorSettings.indicators,
                            keyboardTrading = tradingPreferences.keyboardTrading,
                            onOpenOrderAt = if (OrderKind.LIMIT !in plugin.capabilities.orderKinds) null else { price: Long -> onActive {
                                changeDraft(draft.copy(kind = OrderKind.LIMIT, entry = instrument.formatPrice(price)))
                                showDraft = true
                                openChartOrderEditor(chartId)
                            } },
                            domVisible = active && showDom,
                            onDomToggle = if (!desktop && active && domSupported) ({ showDom = !showDom }) else null,
                            locked = chartPreferences.chartLocked && !chartCompact,
                            onLocked = if (chartCompact) null else { locked: Boolean -> scope.launch { runtime.chartPreferences.setChartLocked(locked) }; Unit }, toolbarEnd = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (compact && orderFlowSupported) ActionIcon(AppIcons.TrendingUp, "订单流分析", "open-order-flow") {
                                        closeOrder(); showOrderFlow = true
                                    }
                                    if (!localDemo) ConnectionStatusControl(market, compact = true, busy, syncAction)
                                }
                            }))

                    }
                    if (!desktop && active && showDom) {
                        DomPanel(plugin, shownInstrument, account.orders.filter { it.request.symbol == shownExecution },
                            Modifier.align(Alignment.CenterEnd).padding(top = 48.dp, bottom = 8.dp, end = 4.dp)
                                .fillMaxHeight(.83f).fillMaxWidth(.74f).widthIn(max = 320.dp)) { showDom = false }
                    }
                    }
                    }
                }
                // A toolbar follows the last-selected chart in its own workspace.
                @Composable fun sharedChartBar(workspace: String, modifier: Modifier, singleLine: Boolean) {
                    var replayInput by remember { mutableStateOf<ReplayInput?>(null) }
                    var replaySetup by remember { mutableStateOf(false) }
                    var replayPrepared by remember { mutableStateOf<ReplayPrepared?>(null) }
                    val replaySeries = remember { mutableStateMapOf<Pair<String, Timeframe>, List<Candle>>() }
                    val replayLoading = remember { mutableStateMapOf<Pair<String, Timeframe>, Boolean>() }
                    val replayJobs = remember { mutableMapOf<Pair<String, Timeframe>, kotlinx.coroutines.Job>() }
                    var replayGeneration by remember { mutableIntStateOf(0) }
                    var replayRevision by remember { mutableLongStateOf(0) }
                    var replayError by remember { mutableStateOf<String?>(null) }
                    val target = workspaceSelected[workspace] ?: workspace
                    val slot = shownCharts[target]
                    val selectedInstrument = if (target == workingChart) instrument else
                        catalog.instruments.firstOrNull { it.symbol == slot?.symbol } ?: instrument
                    val selectedFrame = slot?.timeframe ?: timeframe
                    val currentLayers = layersFor(target)
                    val indicatorController = runtime.indicatorsForChart(target)
                    val indicatorSettings by indicatorController.settings.collectAsState()
                    fun work(action: () -> Unit) { activateChart(target); action() }
                    ChartToolbar(selectedInstrument, watchInstruments.ifEmpty { listOf(selectedInstrument) },
                        { value -> work { chooseSymbol(value) } }, selectedFrame, { value -> work { timeframe = value } }, intervals,
                        listOf(
                            ChartToggle("仓位", currentLayers.showPositions) { currentLayers.showPositions = !currentLayers.showPositions },
                            ChartToggle("挂单", currentLayers.showOrders) { currentLayers.showOrders = !currentLayers.showOrders },
                            ChartToggle("历史", currentLayers.showHistory) {
                                currentLayers.showHistory = !currentLayers.showHistory
                                if (target == MAIN_CHART) {
                                    runtime.chartPreferences.selectHistoryVisible(currentLayers.showHistory)
                                    scope.launch { runtime.chartPreferences.saveHistoryVisibility() }
                                }
                            },
                            ChartToggle("数据表", currentLayers.showData) { currentLayers.showData = !currentLayers.showData },
                        ) + listOfNotNull(if (!desktop && domSupported) ChartToggle("DOM 盘口", showDom) { showDom = !showDom } else null),
                        ChartToggle(if (indicatorSettings.indicators.isEmpty()) "指标" else "指标 ${indicatorSettings.indicators.size}",
                            indicatorSettings.indicators.any { it.visible }) { work { indicatorChartId = target; showIndicators = true } },
                        modifier, compact = compact && !singleLine,
                        additionalControls = {
                            ChartLayoutMenu(layoutOf(workspace)) { selectSplitLayout(workspace, it) }
                            if (desktopStyle) ChartSessionControls(chartPreferences.sessionForChart(target),
                                onSession = { value -> scope.launch { runtime.chartPreferences.setChartSessionFor(target, value) } })
                            if (desktopStyle) AssistChip(onClick = { replaySetup = true }, modifier = Modifier.testTag("open-historical-replay"), label = { Text("回放") },
                                leadingIcon = { Icon(AppIcons.PlayArrow, null, Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
                        }, singleRow = singleLine)
                    val persistentReplayCache = LocalChartPlatform.current != ChartPlatform.IOS
                    if (replaySetup) ReplaySetupDialog(plugin, runtime.replayHistory, persistentReplayCache, catalog.instruments, selectedInstrument, chartPreferences.chartTimeZone,
                        market.simulatedTimeMs, onDismiss = { replaySetup = false }, onReady = { ready ->
                            replaySetup = false; replayPrepared = ready; replayGeneration++; replayError = ready.warnings.takeIf { it.isNotEmpty() }?.joinToString("；")
                            replayJobs.values.toList().forEach { it.cancel() }; replayJobs.clear()
                            replaySeries.clear(); replaySeries.putAll(ready.data); replayLoading.clear(); replayRevision++
                            val chosen = ready.instruments.firstOrNull { it.symbol == selectedInstrument.symbol } ?: ready.instruments.first()
                            replayInput = ReplayInput(chosen, selectedFrame, chartPreferences.sessionForChart(target) == ChartSession.REGULAR,
                                chartPreferences.chartTimeZone, ready.data[chosen.symbol to Timeframe.M1].orEmpty(), indicatorSettings.indicators.toList(),
                                Timeframe.M1, ready.instruments, intervals, priceAxis, skipConfirmation)
                        })
                    replayInput?.let { input -> HistoricalReplayDialog(input, replayRevision,
                        history = { sym, frame -> replaySeries[sym to frame].orEmpty() },
                        watch = { sym, frame ->
                            val series = sym to frame
                            val ready = replayPrepared
                            if (ready != null && ready.instruments.any { it.symbol == sym } && series !in replaySeries && series !in replayLoading) {
                                replayLoading[series] = true
                                val generation = replayGeneration
                                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                                    try {
                                        val (from, until) = ready.range.forFrame(frame)
                                        val result = runtime.replayHistory.load(plugin, sym, frame, from, until,
                                            enabled = ready.cacheEnabled, persistent = persistentReplayCache).result
                                        if (generation == replayGeneration) {
                                            replaySeries[series] = result.candles.sortedBy { it.timeMs }
                                            replayError = result.warning ?: if (result.candles.isEmpty()) "券商没有 $sym ${frame.label} 的历史数据" else null
                                            replayRevision++
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                    catch (failure: Exception) { if (generation == replayGeneration) replayError = "读取 $sym ${frame.label} 失败：${failure.message ?: "请重试"}" }
                                    finally { if (generation == replayGeneration) { replayLoading.remove(series); replayJobs.remove(series) } }
                                }
                                replayJobs[series] = job
                                job.start()
                            }
                        },
                        drawingDefaults = runtime.drawingLibrary,
                        historyError = replayError,
                        onDismiss = { replayGeneration++; replayJobs.values.toList().forEach { it.cancel() }; replayJobs.clear(); replaySeries.clear(); replayLoading.clear(); replayPrepared = null; replayError = null; replayInput = null }) }
                }
                @Composable fun workspaceView(workspace: String, m: Modifier, ownToolbar: Boolean) {
                    val selected = workspaceSelected[workspace] ?: workspace
                    val indicatorController = runtime.indicatorsForChart(selected)
                    val indicatorSettings by indicatorController.settings.collectAsState()
                    val currentDrawings = runtime.drawingsForChart(selected)
                    runtime.undoForChart(selected, scope)
                    Column(m) {
                        if (ownToolbar || !unifiedChartBar) {
                            sharedChartBar(workspace, Modifier.fillMaxWidth().padding(horizontal = 4.dp), ownToolbar)
                            DividerLine()
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            com.unitrading.chart.drawing.DrawingToolbar(currentDrawings, indicatorSettings.indicators.size,
                                { scope.launch { indicatorController.apply(IndicatorSettings()) } })
                            key(workspace) {
                                ChartSplitWorkspace(layoutOf(workspace), selected, { id, cellModifier -> chartView(id, cellModifier, true) },
                                    Modifier.weight(1f).fillMaxHeight(), chartId = { id ->
                                        if (id == "main") workspace else cellId(workspace, id.removePrefix("split").toInt())
                                    }, ratiosOverride = workspaceRatios[workspace], favoriteDrawings = currentDrawings)
                            }
                        }
                    }
                }
                val chart: @Composable (Modifier) -> Unit = { m ->
                    if (!splitAvailable) chartView(MAIN_CHART, m, false)
                    else workspaceView(if (chartWindowsShown) selectedWorkspace else MAIN_CHART, m, false)
                }
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
                    workspaceAccountError?.let { message ->
                        FloatingNoticeCard(TradingNotice(0, message, NoticeTone.ATTENTION, persistent = true), "workspace-account-storage-notice", null,
                            title = "子窗口账户设置")
                    }
                    shownNotice()?.takeUnless { panelOpen }?.let { current ->
                        FloatingNoticeCard(current, "trade-notice", { dismissNotice(current) }, title = "操作提示")
                    }
                    loginMessage?.let { message -> key(message) {
                        var dismissed by remember { mutableStateOf(false) }
                        if (!dismissed) FloatingNoticeCard(TradingNotice(0, message, persistent = true), "saved-login-status", { dismissed = true }, title = "登录状态")
                    } }
                }
                }
                // Everything a trade or a chart can ask for in a dialog; it opens in the window the user is working in.
                val tradeDialogs: @Composable () -> Unit = {
                if (showIndicators) key(indicatorChartId) { IndicatorSettingsDialog(runtime.indicatorsForChart(indicatorChartId)) { showIndicators = false } }
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
                                accountName = accountName)
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
                    if (desktopStyle && desktop && action is Confirmation.Place) {
                        val r = liveOrder ?: action.request
                        val bracketsApply = orderRule.bracketsApply(account, hedging, r)
                        DesktopOrderConfirmation(title, loginName, accountName, environment.label, instrument, r, livePreview,
                            placeConfirmationDisclosure(r, bracketsApply, brackets.enabled, sizeByRisk), action.fromKeys, busy,
                            onCancel = { confirmation = null }, onConfirm = { confirmation = null; executeTrade(action) })
                    } else AlertDialog(
                        onDismissRequest = { if (!busy) confirmation = null },
                        title = { Text(title) },
                        // On a phone held sideways the rows do not fit: they scroll, and the buttons stay below them.
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("$loginName / $accountName / ${environment.label}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            when (action) {
                                is Confirmation.Place -> {
                                    val r = liveOrder ?: action.request
                                    r.sourceSymbol?.let { Text("图表 / 指标 $it → 下单 ${r.symbol}", color = colors.Accent, style = MaterialTheme.typography.bodySmall) }
                                    Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}", style = MaterialTheme.typography.titleSmall)
                                    EstimateRow("当前预估入场", livePreview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                                    // Until a market order fills, its stop and target are estimates: the account's brackets are priced again
                                    // from the quote as it is sent, and a broker that places them by distance moves them with the fill.
                                    val estimated = if (r.kind == OrderKind.MARKET) "（预估）" else ""
                                    EstimateRow("止损价$estimated", r.stopLoss?.let(instrument::formatPrice) ?: "未设置")
                                    EstimateRow("止盈价$estimated", r.takeProfit?.let(instrument::formatPrice) ?: "未设置")
                                    EstimateRow("预计保证金 USD", livePreview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                                    val bracketsApply = orderRule.bracketsApply(account, hedging, r)
                                    Text(placeConfirmationDisclosure(r, bracketsApply, brackets.enabled, sizeByRisk),
                                        style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                                    if (action.fromKeys) Text("键盘下单：按 Enter 确认，按 Esc 取消。", Modifier.testTag("confirm-keys-hint"), style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                                    // An error says why 确认 is disabled, so it comes before the broker's notes.
                                    livePreview?.errors?.forEach { Text(it, Modifier.testTag("confirm-error"), color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                                    livePreview?.warnings?.forEach { Text(it, Modifier.testTag("confirm-warning"), color = colors.Caution, style = MaterialTheme.typography.bodySmall) }
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
                            estimate?.warnings?.forEach { Text(it, Modifier.testTag("pending-entry-warning"), color = colors.Caution, style = MaterialTheme.typography.bodySmall) }
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
                    val addNote = protectionAddNote(plugin.capabilities, p, sl, tp)
                    fun inputLabel(line: String, current: Long?) = when {
                        current == null -> "$line（留空不设置）"
                        plugin.capabilities.removeProtection -> "新$line（留空移除）"
                        else -> "新$line（保留原价或修改）"
                    }
                    fun estimate(price: Long?): String = price?.takeIf { it > 0 }?.let {
                        runCatching { "${money(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, it) - p.entryFeeMicros - commission(spec, p.lotsHundredths), true)} USD" }.getOrDefault("超出范围")
                    } ?: "未设置"
                    AlertDialog(
                        modifier = Modifier.testTag("protection-dialog"),
                        onDismissRequest = { if (!busy) protection = null },
                        title = { Text(if (addNote != null) "添加止损 / 止盈" else "修改止损 / 止盈") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${p.symbol} · ${p.side.label()} ${spec.formatQuantity(p.lotsHundredths)} ${spec.quantityUnit} · #${shortId(p.id)}", style = MaterialTheme.typography.titleSmall)
                            Text("原 SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            PriceInput(inputLabel("止损 SL", p.stopLoss), edit.stopLoss, { protection = edit.copy(stopLoss = it) }, "protect-sl")
                            PriceInput(inputLabel("止盈 TP", p.takeProfit), edit.takeProfit, { protection = edit.copy(takeProfit = it) }, "protect-tp")
                            if (addNote != null) Text(addNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("protection-add-note"))
                            else if (!plugin.capabilities.atomicProtectionEdit) Text("每次只能修改一条保护线。请等服务器确认后再修改另一条。", style = MaterialTheme.typography.bodySmall)
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
                    standaloneEdit != null || flattenReview != null || flattening || flattenResults != null
                // A dialog stays in the window it opened in; notices follow the user from window to window. A window that
                // has gone hands both back to the main one.
                // Read as state where a window draws its overlay: that content belongs to another composition than this one.
                val dialogWindow = rememberUpdatedState(remember(dialogsOpen) { activeWindow }.takeIf { it in liveWindows })
                val noticeWindow = rememberUpdatedState(activeWindow.takeIf { it in liveWindows })
                val latestTradeDialogs = rememberUpdatedState(tradeDialogs)
                val mainFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(mainFocused) {
                    if (mainFocused) {
                        activeWindow = null
                        if (!dialogsOpen && chartWindowsShown) activateChart(workspaceSelected[selectedWorkspace] ?: selectedWorkspace)
                    }
                }
                val windowOverlay: @Composable (String) -> Unit = { window ->
                    val focused = LocalWindowInfo.current.isWindowFocused
                    LaunchedEffect(focused) { if (focused) activeWindow = window }
                    DisposableEffect(window) {
                        liveWindows += window
                        onDispose { liveWindows -= window; if (activeWindow == window) activeWindow = null }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (noticeWindow.value == window) notices(12.dp, Dp.Unspecified, true)
                        if (dialogWindow.value == window) latestTradeDialogs.value()
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
                            0 -> AccountOverviewPage(plugin, session.account, account, analyticsTrades, market, { if (!busy) accountTab = 1 },
                                Modifier.weight(1f).fillMaxWidth(), busy)
                            1 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                                PageTopBar("持仓与订单", accountName, actions = { barStatus() })
                                // The same buttons as a position's own "平仓" below, for the whole child or every child.
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val closeColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    OutlinedButton(
                                        onClick = { flattenReview = FlattenReview(false, listOf(FlattenTarget(plugin, session.account))) },
                                        modifier = Modifier.weight(1f).testTag("flatten-selected-account"),
                                        enabled = !busy && !session.account.readOnly && (account.positions.isNotEmpty() || account.orders.isNotEmpty()),
                                        colors = closeColors,
                                    ) { Text("平仓此子账户") }
                                    OutlinedButton(
                                        onClick = {
                                            // View-only accounts and logins that cannot trade now are listed as skipped, not as targets.
                                            val (targets, skipped) = bulkFlattenTargets(registry.plugins)
                                            if (targets.isNotEmpty() || skipped.isNotEmpty()) flattenReview = FlattenReview(true, targets, skipped)
                                        },
                                        modifier = Modifier.weight(1f).testTag("flatten-all-accounts"),
                                        enabled = !busy, colors = closeColors,
                                    ) { Text("平仓全部子账户") }
                                }
                                TradeTerminal(plugin, account, market, selectedPositionId, compact,
                                    onSelectPosition = { p -> focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null; mobileTab = 1; showOverview = false },
                                    onSelectTrade = { t -> focusedExecution = null; showContract(t.symbol); focusedTrade = t; mobileTab = 1; showOverview = false },
                                    onClose = { reviewTrade(Confirmation.Close(it)) }, onProtect = { editProtection(it) },
                                    onCancel = { reviewTrade(Confirmation.Cancel(it)) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = Modifier.weight(1f).fillMaxWidth(), onProtectOrder = { editPendingProtection(it) })
                            }
                            else -> key(plugin, accountId) {
                                AccountAnalyticsPage(session.account, analyticsTrades, Modifier.weight(1f).fillMaxWidth(),
                                    name = accountName, cachedSinceMs = cachedTrades.minOfOrNull { it.closedAtMs })
                            }
                        }
                    }
                }
                val titleBarSlot = LocalDesktopTitleBarSlot.current
                val tabs = if (chartWindowsShown) workspaceOrder
                    .filterNot { it in detachedWorkspaces }.map { id ->
                        val tabChart = chartSlot(workspaceSelected[id] ?: id)
                        val tabSymbol = tabChart?.symbol ?: "图表"
                        val tabInstrument = catalog.instruments.firstOrNull { it.symbol == tabSymbol }
                        workspaceTabData(id, tabSymbol, tabInstrument, tabInstrument?.priceSymbol?.let { market.quotes[it] }, market.connected)
                    } else emptyList()
                // The native title row is a sibling composition. Publish its data and the current
                // workspace actions separately so live quotes cannot leave stale event handlers.
                val titleOwner = remember(titleBarSlot, plugin) { Any() }
                SideEffect {
                    if (titleBarSlot != null && nativeDesktop && desktop) {
                        titleBarSlot.publish(titleOwner, DesktopTitleTabs(tabs, selectedWorkspace, canDetachWorkspaces, tabletWorkspaces, !chartOpening && chartOrder.size < ChartPreferences.MAX_EXTRA_CHARTS),
                            ::selectWorkspace, ::detachWorkspace, ::closeChart, ::reorderWorkspaces, { openChart() })
                    }
                }
                val titleContent: @Composable () -> Unit = {
                    WorkspaceTabStrip(tabs, selectedWorkspace, ::selectWorkspace, ::detachWorkspace, ::closeChart, ::reorderWorkspaces,
                        Modifier.fillMaxWidth(), canDetach = canDetachWorkspaces, touchSized = tabletWorkspaces,
                        onAdd = { openChart() }, canAdd = !chartOpening && chartOrder.size < ChartPreferences.MAX_EXTRA_CHARTS)
                }
                DisposableEffect(titleBarSlot, nativeDesktop, desktop, titleOwner) {
                    onDispose { titleBarSlot?.clear(titleOwner) }
                }
                Box(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxSize()) {
                if (rail) MobileNavigationRail(mobileTab, selectTab, dense = denseLandscape)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Other desktop systems keep their native title bar; the Windows frame supplies its own title slot.
                    if (nativeDesktop && desktop && titleBarSlot == null) {
                        Row(Modifier.fillMaxWidth().height(40.dp).background(colors.Surface)
                            .testTag("desktop-title-toolbar").padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppLogo(22.dp)
                            Text("UniTrading", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Box(Modifier.weight(1f).fillMaxHeight()) { titleContent() }
                        }
                        DividerLine()
                    }
                    val desktopControls: @Composable (String?, Boolean, Boolean) -> Unit = { workspace, showFlatten, showAccounts ->
                            val selectedId = when {
                                workspace == null -> mainAccountId
                                workspace in unresolvedDetachedAccounts -> ""
                                else -> detachedAccounts[workspace] ?: mainAccountId
                            }
                            val selected = catalog.accounts.firstOrNull { it.id == selectedId }
                            val targetSession = remember(plugin, selected?.id) {
                                selected?.let { runtime.accountRisk.guard(plugin, plugin.openSession(it.id)) }
                            }
                            val targetSnapshot = if (targetSession == null) null else {
                                val value by key(targetSession) { targetSession.state.collectAsState() }
                                value
                            }
                            fun selectionStillCurrent(): Boolean {
                                if (workspace != null && workspace in unresolvedDetachedAccounts) return false
                                val currentBroker = plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.firstOrNull()
                                val current = if (workspace == null) chosenAccountId.takeIf { id -> catalog.accounts.any { it.id == id } }
                                    ?: catalog.accounts.first().id else detachedAccounts[workspace] ?: mainAccountId
                                return currentBroker === plugin && current == selectedId && catalog.accounts.any { it.id == selectedId }
                            }
                            val accountActionsReady = !accountOperationPending()
                            val flattenButtons: @Composable () -> Unit = {
                                // Keep destructive actions distinct from the two account selectors; both still open their original confirmations.
                                Row(Modifier.testTag("desktop-flatten-actions"), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(if (tablet) 8.dp else 12.dp)) {
                                    val actionColors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.error,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f))
                                    val shape = RoundedCornerShape(7.dp)
                                    val outline = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    OutlinedButton(onClick = {
                                        if (!accountOperationPending() &&
                                            targetSession != null && selectionStillCurrent())
                                            flattenReview = FlattenReview(false, listOf(FlattenTarget(plugin, targetSession.account)))
                                    },
                                        modifier = (if (tablet) Modifier.width(128.dp) else Modifier).height(if (tablet) 40.dp else 36.dp).testTag("flatten-selected-account"),
                                        enabled = accountActionsReady && targetSession != null && !targetSession.account.readOnly &&
                                            (targetSnapshot?.positions?.isNotEmpty() == true || targetSnapshot?.orders?.isNotEmpty() == true),
                                        shape = shape, border = outline, colors = actionColors, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                        Text("平仓此子账户", maxLines = 1)
                                    }
                                    OutlinedButton(onClick = {
                                        if (!accountOperationPending() && selectionStillCurrent()) {
                                            val (targets, skipped) = bulkFlattenTargets(registry.plugins)
                                            if (targets.isNotEmpty() || skipped.isNotEmpty()) flattenReview = FlattenReview(true, targets, skipped)
                                        }
                                    }, modifier = (if (tablet) Modifier.width(128.dp) else Modifier).height(if (tablet) 40.dp else 36.dp).testTag("flatten-all-accounts"), enabled = accountActionsReady && targetSession != null,
                                        shape = shape, border = outline, colors = actionColors, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                        Text("平仓全部账户", maxLines = 1)
                                    }
                                }
                            }
                            val accountSelectors: @Composable () -> Unit = {
                                AccountContextBar(registry, plugin, selectedId,
                                    { value -> if (workspace == null) chooseBroker(value) },
                                    { value -> if (workspace == null) chooseAccount(value) else chooseDetachedAccount(workspace, value) },
                                    Modifier.width(desktopPickerWidth).testTag("desktop-trade-accounts"), enabled = accountActionsReady,
                                    dense = true, aliases = runtime.accountAliases.takeIf { workspace == null }, emphasize = true, inline = true,
                                    inlineLoginLabel = "平台", allowLoginSwitch = workspace == null)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (showFlatten) flattenButtons()
                                if (showFlatten && showAccounts) Spacer(Modifier.width(32.dp))
                                if (showAccounts) accountSelectors()
                            }
                    }
                    if (tabletWorkspaces && (desktop || chartFirst)) {
                        Row(Modifier.fillMaxWidth().height(44.dp).background(colors.Surface)
                            .testTag("tablet-workspace-header").padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppLogo(22.dp)
                            Text("UniTrading", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Box(Modifier.weight(1f).fillMaxHeight()) { titleContent() }
                            desktopControls(null, true, false)
                            Spacer(Modifier.width(40.dp))
                        }
                        DividerLine()
                    }
                    if (unifiedChartBar) {
                        val toolbarStatus: @Composable () -> Unit = {
                            if (!desktopStyle) AccountContextBar(registry, plugin, accountId, ::chooseBroker, ::chooseAccount,
                                Modifier.width(accountPickerWidth), enabled = !busy,
                                aliases = runtime.accountAliases)
                            if (desktop) {
                                if (!desktopStyle) ActionIcon(AppIcons.AccountCircle, "账户总览", "toggle-account-overview") { showOverview = true }
                                if (!localDemo) ConnectionStatusControl(market, compact = true, busy, syncAction)
                                if (localDemo) ActionIcon(if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                                    if (paused) "继续行情" else "暂停行情", "toggle-demo-feed") { runtime.paused.value = !paused }
                                if (!desktopStyle) {
                                    ActionIcon(AppIcons.ViewQuilt, "重置布局", "reset-desktop-layout") {
                                        showOrderFlow = false; runtime.desktopLayout.reset(); scope.launch { runtime.desktopLayout.save() }
                                    }
                                    ActionIcon(AppIcons.Settings, "设置", "open-settings") { showActivity = false; showSettings = true }
                                }
                                if (!wide && !tablet) Button({
                                    showDraft = true
                                    if (!orderDetached()) showOrder = true
                                    else { runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true); scope.launch { runtime.desktopLayout.save() } }
                                }, Modifier.testTag("open-order"), colors = brandButtonColors()) { Text("下单") }
                            } else if (!localDemo) ConnectionStatusControl(market, compact = true, busy, syncAction)
                        }
                        Column(Modifier.fillMaxWidth().background(colors.Surface).testTag("app-toolbar")) {
                            Row(Modifier.fillMaxWidth().heightIn(min = if (tablet) 48.dp else 56.dp).padding(start = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (desktop && !desktopStyle && !tabletWorkspaces) AppLogo(22.dp)
                                sharedChartBar(if (chartWindowsShown) selectedWorkspace else MAIN_CHART, Modifier.weight(1f), true)
                                if (desktopStyle && desktop) {
                                    // Tablet flatten controls live in the workspace header; keep the account pickers
                                    // beside status, outside the scrolling chart tools.
                                    desktopControls(null, !tablet, true)
                                }
                                toolbarStatus()
                            }
                        }
                        DividerLine()
                    }
                    if (compact && !unifiedChartBar) {
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
                        @Composable fun workspaceAccount(workspace: String): Pair<AccountSession?, AccountSnapshot?> {
                            val id = if (workspace in unresolvedDetachedAccounts) "" else detachedAccounts[workspace] ?: mainAccountId
                            val target = remember(plugin, id, session, mainSession, catalog.accounts) {
                                if (catalog.accounts.none { it.id == id }) null
                                else if (id == accountId) session
                                else if (id == mainAccountId) mainSession
                                else runtime.accountRisk.guard(plugin, plugin.openSession(id))
                            }
                            val snapshot = if (target == null) null else {
                                val current by key(target) { target.state.collectAsState() }
                                current
                            }
                            return target to snapshot
                        }
                        fun readyForWorkspace(target: String): Boolean {
                            return activeChart == target && restoring == null && chosenSymbol == symbol && accountContextCurrent()
                        }
                        fun workInWorkspace(target: String, action: () -> Unit) {
                            if (accountOperationPending()) { showNotice("请先完成当前交易操作", attention = true); return }
                            if (!readyForWorkspace(target)) {
                                activateChart(target)
                                showNotice("正在切换窗口，请检查账户与合约后重试", attention = true)
                                return
                            }
                            activeWindow = workspaceOf(target).takeIf { it in detachedWorkspaces }?.let(::chartWindowKey)
                            action()
                        }
                        // Slots can be composed in a separate native window. Keep their callbacks tied to the
                        // current account and draft even when Compose reuses a lambda after switching windows.
                        val submitWorkspaceOrder = rememberUpdatedState<(String) -> Unit> { target ->
                            workInWorkspace(target) {
                                request?.let { reviewTrade(Confirmation.Place(it)) }
                                    ?: showNotice("订单参数尚未准备好，请检查后重试", attention = true)
                            }
                        }
                        val selectWorkspacePosition = rememberUpdatedState<(String, Position) -> Unit> { target, p ->
                            workInWorkspace(target) { focusedExecution = null; showContract(p.symbol); selectedPositionId = p.id; focusedTrade = null }
                        }
                        val selectWorkspaceTrade = rememberUpdatedState<(String, ClosedTrade) -> Unit> { target, t ->
                            workInWorkspace(target) { focusedExecution = null; showContract(t.symbol); focusedTrade = t; selectedPositionId = null }
                        }
                        val closeWorkspacePosition = rememberUpdatedState<(String, Position) -> Unit> { target, p ->
                            workInWorkspace(target) { reviewTrade(Confirmation.Close(p)) }
                        }
                        val protectWorkspacePosition = rememberUpdatedState<(String, Position) -> Unit> { target, p ->
                            workInWorkspace(target) { editProtection(p) }
                        }
                        val cancelWorkspaceOrder = rememberUpdatedState<(String, PendingOrder) -> Unit> { target, o ->
                            workInWorkspace(target) { reviewTrade(Confirmation.Cancel(o)) }
                        }
                        val editWorkspaceOrder = rememberUpdatedState<(String, PendingOrder) -> Unit> { target, o ->
                            workInWorkspace(target) { editPending(o) }
                        }
                        val protectWorkspaceOrder = rememberUpdatedState<(String, PendingOrder) -> Unit> { target, o ->
                            workInWorkspace(target) { editPendingProtection(o) }
                        }
                        @Composable fun workspaceOrder(target: String, m: Modifier, minimize: () -> Unit) {
                            if (!readyForWorkspace(target)) {
                                Column(m.background(colors.Surface).padding(16.dp).testTag("inactive-workspace-order-$target"),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("下单 · ${chartSlot(target)?.symbol.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                                    val wanted = detachedAccounts[workspaceOf(target)]
                                    Text(if (workspaceOf(target) in unresolvedDetachedAccounts || wanted != null && catalog.accounts.none { it.id == wanted }) "子账户不可用，请在此窗口重新选择子账户"
                                        else if (activeChart == target) "正在加载当前图表合约" else "切换到此窗口后显示对应合约的下单面板",
                                        color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                                    if (activeChart != target) Button({ activateChart(target) }, Modifier.testTag("activate-workspace-order-$target")) { Text("激活此窗口") }
                                }
                            } else OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                { submitWorkspaceOrder.value(target) },
                                m.background(colors.Surface), environment = environment, capacity = capacity,
                                onRefreshCapacity = refreshCapacity, skipConfirmation = skipConfirmation,
                                onClose = minimize, closeLabel = "收起下单面板", closeTag = "desktop-order-minimize", brackets = brackets, bracketOrder = request,
                                accountName = accountName)
                        }
                        @Composable fun workspaceTerminal(target: String, m: Modifier) {
                            val (targetSession, targetSnapshot) = workspaceAccount(workspaceOf(target))
                            val targetAccount = targetSnapshot ?: AccountSnapshot("", 0, balanceKnown = false, equityKnown = false, marginKnown = false)
                            val targetTrades = when (targetSession?.account?.id) {
                                accountId -> analyticsTrades
                                mainAccountId -> mainAnalyticsTrades
                                else -> mergeAnalyticsTrades(cachedByAccount[targetSession?.account?.id].orEmpty(), targetAccount.trades)
                            }
                            var section by remember(plugin, targetSession?.account?.id, target) { mutableStateOf(TerminalSection.POSITIONS) }
                            val sections = listOf(TerminalSection.POSITIONS to "持仓 ${targetAccount.positions.size}",
                                TerminalSection.ORDERS to "挂单 ${terminalOrders(targetAccount).rows.size}",
                                TerminalSection.EXECUTIONS to "成交 ${targetAccount.executions.size}",
                                TerminalSection.CLOSED_TRADES to "平仓记录 ${targetTrades.size}")
                            Column(m.background(colors.Surface)) {
                                if (desktopStyle) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState())
                                        .testTag("desktop-trade-actions"), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        sections.forEach { (value, label) ->
                                            FilterChip(section == value, { section = value }, label = { Text(label) },
                                                modifier = Modifier.testTag("desktop-trade-tab-${value.name}"))
                                        }
                                    }
                                    if (targetSession != null) AccountRiskNotice(runtime.accountRisk.restriction(plugin, targetSession),
                                        runtime.accountRisk.lockingTrigger(plugin, targetSession), targetSession.account.currency, Modifier.padding(horizontal = 12.dp))
                                }
                                TradeTerminal(plugin, targetAccount, market, selectedPositionId.takeIf { readyForWorkspace(target) }, false,
                                    onSelectPosition = { p -> selectWorkspacePosition.value(target, p) },
                                    onSelectTrade = { t -> selectWorkspaceTrade.value(target, t) },
                                    onClose = { p -> closeWorkspacePosition.value(target, p) },
                                    onProtect = { p -> protectWorkspacePosition.value(target, p) },
                                    onCancel = { o -> cancelWorkspaceOrder.value(target, o) },
                                    onEditOrder = { o -> editWorkspaceOrder.value(target, o) }, busy = busy,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    onProtectOrder = { o -> protectWorkspaceOrder.value(target, o) },
                                    section = section.takeIf { desktopStyle }, historyTrades = if (desktopStyle) targetTrades else targetAccount.trades)
                            }
                        }
                        val mainWorkspace = if (chartWindowsShown) selectedWorkspace else MAIN_CHART
                        val mainTarget = workspaceSelected[mainWorkspace] ?: mainWorkspace
                        val mainSlot = chartSlot(mainTarget)
                        val mainInstrument = catalog.instruments.firstOrNull { it.symbol == mainSlot?.symbol }
                            DesktopWorkspace(runtime.desktopLayout, wide || contextOrderSidebar, Modifier.weight(1f).fillMaxWidth(),
                            contextOrderRequest = contextOrderRequest,
                            tablet = tablet,
                            tabletAccountBar = if (tablet) { { m -> AccountStrip(mainAccount, m,
                                note = if (localDemo) "${if (paused) "模拟已暂停" else "模拟行情 ×15"} · 报价 ${clockLabel(market.simulatedTimeMs, true)} UTC · 无真实资金"
                                    else "状态以服务器为准 · ${environment.label}", connected = market.connected, compact = true) } } else null,
                            onCompactOrderOpen = if (tablet && !wide && !contextOrderSidebar) ({
                                contextOrderSidebar = true
                                contextOrderRequest++
                                showOrder = false
                                showDom = false
                                runtime.desktopLayout.setOpen(DesktopPanel.MARKET, false)
                                runtime.desktopLayout.setOpen(DesktopPanel.ORDER, true)
                                scope.launch { runtime.desktopLayout.save() }
                            }) else null,
                            market = { m, minimize -> MarketWatch(plugin, market, mainAccount, mainSlot?.symbol ?: symbol,
                                { value -> if (!dialogsOpen) { activateChart(mainTarget); chooseSymbol(value) } }, m, runtime.marketWatch, panel = true, onClose = minimize) },
                            chart = chart,
                            order = { m, minimize -> workspaceOrder(mainTarget, m, minimize) },
                            terminal = { m -> workspaceTerminal(mainTarget, m) }, terminalSummary = "持仓 ${mainAccount.positions.size} · 挂单 ${terminalOrders(mainAccount).rows.size}",
                            orderFlow = if (orderFlowSupported) { { m -> OrderFlowPanel(plugin, instrument, timeframe, m) { showOrderFlow = false } } } else null,
                            dom = if (domSupported && mainInstrument != null) { { m, minimize -> DomPanel(plugin, mainInstrument,
                                mainAccount.orders.filter { it.request.symbol == mainInstrument.executionSymbol }, m, minimize) } } else null,
                            domOpen = showDom, onDomOpenChange = { showDom = it },
                            orderFlowOpen = showOrderFlow, onOrderFlowOpenChange = { showOrderFlow = it }, windowOverlay = windowOverlay,
                            analytics = if (desktopStyle) { { m, close ->
                                Column(m.background(colors.Shell)) {
                                    PanelHeader("交易分析", Modifier.background(colors.Surface), subtitle = aliases.account(plugin.descriptor.id, mainSession.account),
                                        actions = { ActionIcon(AppIcons.Close, "关闭分析", "close-desktop-analytics", onClick = close) })
                                    DividerLine()
                                    key(plugin, mainAccountId) {
                                        AccountAnalyticsPage(mainSession.account, mainAnalyticsTrades, Modifier.weight(1f).fillMaxWidth(),
                                            name = aliases.account(plugin.descriptor.id, mainSession.account), cachedSinceMs = mainCachedTrades.minOfOrNull { it.closedAtMs }, showHeading = false)
                                    }
                                }
                            } } else null,
                            onNewChartWindow = if (nativeDesktop || tabletWorkspaces) ({ openChart() }) else null,
                            newChartWindowEnabled = !chartOpening && chartOrder.size < ChartPreferences.MAX_EXTRA_CHARTS,
                            onOpenSettings = if (desktopStyle) ({ showActivity = false; showSettings = true }) else null,
                            onResetLayout = if (desktopStyle) ({ showResetLayout = true }) else null,
                            windowMenuExtra = {})
                        // A detached tab keeps its own workspace and can be returned to the tab strip.
                        val chartWindows = LocalPanelWindowHost.current
                        val layoutPreferences by runtime.desktopLayout.preferences.collectAsState()
                        if (chartWindows != null) detachedWorkspaces.forEach { id -> key(id) {
                            val target = workspaceSelected[id] ?: id
                            val slot = chartSlot(target)
                            val detachedInstrument = catalog.instruments.firstOrNull { it.symbol == slot?.symbol }
                            val detachedQuote = workspaceTabData(id, slot?.symbol.orEmpty(), detachedInstrument,
                                detachedInstrument?.priceSymbol?.let { market.quotes[it] }, market.connected)
                            val window = chartWindowKey(id)
                            chartWindows.Window(window, "UniTrading · 图表 · ${slot?.symbol.orEmpty()} · ${slot?.timeframe?.label.orEmpty()}", layoutPreferences.windows[window], DpSize(1280.dp, 800.dp),
                                onBounds = { runtime.desktopLayout.setWindowBounds(window, it); scope.launch { runtime.desktopLayout.save() } },
                                onClose = { dockWorkspace(id) }) {
                                WindowSurface(window, windowOverlay) {
                                    val (_, childSnapshot) = workspaceAccount(id)
                                    val childAccount = childSnapshot ?: AccountSnapshot("", 0, balanceKnown = false, equityKnown = false, marginKnown = false)
                                    val childFocused = LocalWindowInfo.current.isWindowFocused
                                    LaunchedEffect(childFocused) {
                                        if (childFocused) {
                                            activeWindow = window
                                            if (!dialogsOpen) activateChart(workspaceSelected[id] ?: id)
                                        }
                                    }
                                    fun activateDetached() {
                                        activeWindow = window
                                        if (!dialogsOpen) activateChart(workspaceSelected[id] ?: id)
                                    }
                                    Column(Modifier.fillMaxSize()) {
                                        Row(Modifier.fillMaxWidth().background(colors.Surface), verticalAlignment = Alignment.CenterVertically) {
                                            var dragged by remember(id) { mutableStateOf(false) }
                                            Row(Modifier.weight(1f).heightIn(min = 42.dp).testTag("drag-workspace-$id")
                                                .pointerInput(id, chartWindows) {
                                                    detectDragGestures(onDragStart = { dragged = false }, onDragEnd = {
                                                        if (dragged && chartWindows.canDockAtPointer()) dockWorkspace(id)
                                                        dragged = false
                                                    }, onDragCancel = { dragged = false }) { change, _ -> dragged = true; change.consume() }
                                                }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(AppIcons.DragIndicator, null, Modifier.size(18.dp), tint = colors.Muted)
                                                Spacer(Modifier.width(5.dp))
                                                Text(slot?.symbol.orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                Spacer(Modifier.width(8.dp))
                                                Text(detachedQuote.price, Modifier.testTag("detached-workspace-price-$id"), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                                Spacer(Modifier.width(4.dp))
                                                Text(detachedQuote.change, Modifier.testTag("detached-workspace-change-$id"), style = MaterialTheme.typography.labelMedium,
                                                    color = when { detachedQuote.direction > 0 -> colors.UpText; detachedQuote.direction < 0 -> colors.DownText; else -> colors.Muted }, maxLines = 1)
                                                Spacer(Modifier.width(10.dp))
                                                Text("拖动到主窗口标签栏", style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1)
                                            }
                                            TextButton({ dockWorkspace(id) }, Modifier.testTag("dock-workspace-$id")) { Text("收回标签") }
                                        }
                                        BoxWithConstraints(Modifier.fillMaxWidth().background(colors.Surface)
                                            .pointerInput(window) {
                                                awaitPointerEventScope { while (true) {
                                                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) activeWindow = window
                                                } }
                                            }.testTag("detached-account-toolbar-$id")) {
                                            val wrapControls = maxWidth < 900.dp
                                            Column(Modifier.fillMaxWidth()) {
                                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically) {
                                                    sharedChartBar(id, Modifier.weight(1f), true)
                                                    if (!wrapControls) desktopControls(id, true, true)
                                                }
                                                if (wrapControls) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                                    .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    desktopControls(id, true, true)
                                                }
                                            }
                                        }
                                        DividerLine()
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            DetachedWorkspace(id,
                                                chart = { m -> workspaceView(id, m, false) },
                                                market = { m, close -> MarketWatch(plugin, market, childAccount, slot?.symbol ?: symbol,
                                                    { value -> if (!dialogsOpen) { activateDetached(); chooseSymbol(value) } }, m, runtime.marketWatch, panel = true, onClose = close) },
                                                order = { m, close -> workspaceOrder(target, m, close) },
                                                terminal = { m -> workspaceTerminal(target, m) },
                                                terminalSummary = "持仓 ${childAccount.positions.size} · 挂单 ${terminalOrders(childAccount).rows.size}",
                                                dom = if (domSupported && detachedInstrument != null) { { m, close ->
                                                    Column(m.background(colors.Surface)) {
                                                        DomPanel(plugin, detachedInstrument, childAccount.orders.filter { it.request.symbol == detachedInstrument.executionSymbol },
                                                            Modifier.weight(1f).fillMaxWidth(), close)
                                                        Text("盘口随当前活动窗口切换", Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                            .testTag("detached-dom-scope-$id"), color = colors.Muted, style = MaterialTheme.typography.labelSmall)
                                                    }
                                                } } else null,
                                                orderRequest = detachedOrderRequests[id] ?: 0,
                                                onDomOpenChange = { open -> if (open) detachedDomOpen[id] = true else detachedDomOpen.remove(id) },
                                                onActivate = ::activateDetached)
                                        }
                                        DividerLine()
                                        AccountStrip(childAccount, Modifier.testTag("detached-account-strip-$id"),
                                            note = "状态以服务器为准 · ${environment.label}", connected = market.connected)
                                    }
                                }
                            }
                        } }
                        if (showResetLayout) AlertDialog(onDismissRequest = { showResetLayout = false },
                            title = { Text("重置布局？") }, text = { Text("将恢复面板的位置和尺寸。图表内容、指标和绘图会保留。") },
                            confirmButton = { TextButton({
                                showResetLayout = false; showOrderFlow = false; runtime.desktopLayout.reset()
                                scope.launch { runtime.desktopLayout.save() }
                            }, Modifier.testTag("confirm-reset-desktop-layout")) { Text("确定重置") } },
                            dismissButton = { TextButton({ showResetLayout = false }, Modifier.testTag("cancel-reset-desktop-layout")) { Text("取消") } })
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
                                        }, onPositions = { accountTab = 1; mobileTab = 4 }, dense = denseLandscape,
                                            onDailyPnl = { accountTab = 0; mobileTab = 4 }, resume = useOrderPopup && orderActive)
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
                                        reduction = reduction,
                                        content = {
                                            shownNotice()?.let { current ->
                                                Box(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                                                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                    FloatingNoticeCard(current, "trade-notice", { dismissNotice(current) }, title = "操作提示")
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
                                                preview?.valid == true && request != null, busy, skipConfirmation, keyboardOpen, brackets = brackets, calculatedLots = request?.lotsHundredths,
                                                reduction = reduction) {
                                                OrderForm(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy,
                                                    ::reviewMobileOrder, Modifier.weight(1f),
                                                    environment = environment, capacity = capacity, onRefreshCapacity = refreshCapacity,
                                                    skipConfirmation = skipConfirmation, dockAdvanced = advancedOrder,
                                                    onDockAdvanced = { open ->
                                                        advancedOrder = open
                                                        val change = ++advancedChanges
                                                        scope.launch {
                                                            if (!runtime.tradingPreferences.setOrderMoreSettings(open) && change == advancedChanges)
                                                                advancedOrder = runtime.tradingPreferences.preferences.value.orderMoreSettings
                                                        }
                                                    },
                                                    brackets = brackets, bracketOrder = request,
                                                    // The minimized dock keeps its form; the notice shows above the chart meanwhile.
                                                    orderNotice = if (orderOpen) orderNotice else ({}))
                                            }
                                        } else Column(Modifier.fillMaxSize()) { idleBar() }
                                    })
                            }
                            else -> if (showActivity) {
                                ActivityLogPage("$loginName · $accountName", account.events,
                                    onBack = { showActivity = false }, modifier = Modifier.weight(1f).fillMaxWidth())
                            } else SettingsPage(runtime, plugin, accountId, market, busy,
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.weight(1f).fillMaxWidth(), onConnectRithmic = connectRithmic, barActions = { barStatus() }, updates = updates,
                                showOrderLayout = !nativeDesktop && !tablet, showChartSessionControls = !desktopStyle || !splitAvailable)
                        }
                    }
                    if (compact && !chartFirst && !rail) MobileNavigation(mobileTab, selectTab)
                    else if (desktop && !tablet) {
                        DividerLine()
                        AccountStrip(mainAccount, note = if (localDemo) "${if (paused) "模拟已暂停" else "模拟行情 ×15"} · 报价 ${clockLabel(market.simulatedTimeMs, true)} UTC · 无真实资金"
                            else "状态以服务器为准 · ${environment.label}", connected = market.connected)
                    }
                }
                }
                if (noticeWindow.value == null) notices(if (desktop) 72.dp else if (keyboardOpen) 8.dp else 56.dp, observedHeight * .65f, desktop)
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
                                ::chooseBroker, ::chooseAccount, onTheme = { showTheme = true },
                                onActivity = { showActivity = true }, onConnect = { showConnection = true }, onDisconnect = ::disconnectCurrent,
                                onForgotten = { selectBroker("") }, onSync = ::syncAccount, onReset = { confirmation = Confirmation.Reset },
                                modifier = Modifier.fillMaxSize(), onClose = { showSettings = false }, onConnectRithmic = connectRithmic, updates = updates,
                                showOrderLayout = !nativeDesktop && !tablet, showChartSessionControls = !desktopStyle || !splitAvailable)
                        }
                    }
                }
                if (dialogWindow.value == null) tradeDialogs()
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
}

