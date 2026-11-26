Warning: truncated output (original token count: 50080)
Total output lines: 2438

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
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
        var showHyperpropCheck by remember { mutableStateOf(false) }
        if (showHyperpropCheck) HyperpropConnectionDialog { showHyperpropCheck = false }
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
                LoginWelcome(runtime, { showConnection = true }, { showTheme = true }, onLoginRithmic = connectRithmic,
                    onCheckHyperprop = { showHyperpropCheck = true })
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
                            OutlinedButton({ showHyperpropCheck = true }, Modifier.fillMaxWidth()) { Text("Hyperprop 个人 API 连接测试") }
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
        // A user fold is independent of the temporary hiding used by the order panel and keyboard.
        var phoneChartAccountsCollapsed by rememberSaveable { mutableStateOf(false) }
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
                val estimate = sessio…20080 tokens truncated…         standaloneEdit != null || flattenReview != null || flattening || flattenResults != null
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
                        val showAccountBar = !(chartFirst && (orderOnChart || phoneScreen && phoneChartAccountsCollapsed)) && (!keyboardOpen || popupVisible)
                        val accountBarHeight by animateDpAsState(
                            if (showAccountBar) { if (denseLandscape) 41.dp else 57.dp } else 0.dp, tween(180), label = "account-bar-height")
                        Box(Modifier.fillMaxWidth().height(accountBarHeight).clipToBounds()) {
                            if (accountBarHeight > 0.dp) Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().background(colors.Surface).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AccountContextBar(registry, plugin, accountId, ::chooseBroker, ::chooseAccount,
                                        Modifier.weight(1f), enabled = !busy && showAccountBar, dense = denseLandscape, aliases = runtime.accountAliases)
                                    if (phoneScreen && chartFirst) ActionIcon(AppIcons.ExpandLess, "收起账户栏", "collapse-chart-accounts",
                                        modifier = Modifier.size(40.dp), iconSize = 20.dp) { phoneChartAccountsCollapsed = true }
                                }
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
                        @Composable fun workspaceOrder(target: String, m: Modifier, minimize: () -> Unit, floating: Boolean = false) {
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
                                accountName = accountName, floating = floating, compactFloating = floating && tablet)
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
                        if (desktopStyle && chartFullscreen) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("fullscreen-chart-workspace")) {
                                chart(Modifier.fillMaxSize())
                                if (fullscreenTicket) FullscreenOrderOverlay(mainSlot?.symbol ?: symbol, { fullscreenTicket = false }, compact = tablet) { m ->
                                    workspaceOrder(mainTarget, m, { fullscreenTicket = false }, floating = true)
                                }
                            }
                        } else DesktopWorkspace(runtime.desktopLayout, wide || contextOrderSidebar, Modifier.weight(1f).fillMaxWidth(),
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
                                { value -> if (!dialogsOpen) { activateChart(mainTarget); chooseSymbol(value) } }, m, runtime.marketWatch, panel = true, onClose = minimize, chartCloses = chartCloses) },
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
                            chartWindows.ChartWindow(window, "UniTrading · 图表 · ${slot?.symbol.orEmpty()} · ${slot?.timeframe?.label.orEmpty()}", layoutPreferences.windows[window], DpSize(1280.dp, 800.dp),
                                onBounds = { runtime.desktopLayout.setWindowBounds(window, it); scope.launch { runtime.desktopLayout.save() } },
                                onClose = { dockWorkspace(id) }, header = { headerModifier ->
                                    var dragged by remember(id) { mutableStateOf(false) }
                                    Row(headerModifier.heightIn(min = 40.dp)
                                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                                        .background(MaterialTheme.colorScheme.surface).testTag("drag-workspace-$id")
                                        .pointerInput(id, chartWindows) {
                                            detectDragGestures(onDragStart = { dragged = false }, onDragEnd = {
                                                if (dragged && chartWindows.canDockAtPointer()) dockWorkspace(id)
                                                dragged = false
                                            }, onDragCancel = { dragged = false }) { change, _ -> dragged = true; change.consume() }
                                        }.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(AppIcons.DragIndicator, null, Modifier.size(18.dp), tint = colors.Muted)
                                        Spacer(Modifier.width(5.dp))
                                        Text(slot?.symbol.orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Spacer(Modifier.width(8.dp))
                                        Text(detachedQuote.price, Modifier.testTag("detached-workspace-price-$id"), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        Text(detachedQuote.change, Modifier.testTag("detached-workspace-change-$id"), style = MaterialTheme.typography.labelMedium,
                                            color = when { detachedQuote.direction > 0 -> colors.UpText; detachedQuote.direction < 0 -> colors.DownText; else -> colors.Muted }, maxLines = 1)
                                        ActionIcon(AppIcons.OpenInNew, "收回标签", "dock-workspace-$id") { dockWorkspace(id) }
                                    }
                                }) {
                                WindowSurface(window, windowOverlay) {
                                    val (_, childSnapshot) = workspaceAccount(id)
                                    val childAccount = childSnapshot ?: AccountSnapshot("", 0, balanceKnown = false, equityKnown = false, marginKnown = false)
                                    val childFocused = LocalWindowInfo.current.isWindowFocused
                                    val childFull = workspaceFullscreen(id)
                                    val childFocus = remember(id) { FocusRequester() }
                                    LaunchedEffect(childFull) { if (childFull) childFocus.requestFocus() }
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
                                    Column(Modifier.fillMaxSize().focusRequester(childFocus).onPreviewKeyEvent { event ->
                                        if (childFull && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                            detachedFullscreen[id] = false; detachedFullscreenTicket.remove(id); true
                                        } else false
                                    }.focusable()) {
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
                                                    { value -> if (!dialogsOpen) { activateDetached(); chooseSymbol(value) } }, m, runtime.marketWatch, panel = true, onClose = close, chartCloses = chartCloses) },
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
                                                onActivate = ::activateDetached, fullscreen = childFull)
                                            if (childFull && detachedFullscreenTicket[id] == true)
                                                FullscreenOrderOverlay(slot?.symbol.orEmpty(), { detachedFullscreenTicket.remove(id) }, compact = tablet) { m ->
                                                    workspaceOrder(target, m, { detachedFullscreenTicket.remove(id) }, floating = true)
                                                }
                                        }
                                        if (!childFull) {
                                            DividerLine()
                                            AccountStrip(childAccount, Modifier.testTag("detached-account-strip-$id"),
                                                note = "状态以服务器为准 · ${environment.label}", connected = market.connected)
                                        }
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
                            0 -> MarketWatch(plugin, market, account, symbol, ::chooseSymbol, Modifier.weight(1f), runtime.marketWatch, accountName, chartCloses = chartCloses) { barStatus() }
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
                                modifier = Modifier.weight(1f).fillMaxWidth(), onConnectRithmic = connectRithmic, onCheckHyperprop = { showHyperpropCheck = true }, barActions = { barStatus() }, updates = updates,
                                showOrderLayout = !nativeDesktop && !tablet, showChartSessionControls = !desktopStyle || !splitAvailable,
                                showDesktopExitSetting = nativeDesktop)
                        }
                    }
                    if (compact && !chartFirst && !rail) MobileNavigation(mobileTab, selectTab)
                    else if (desktop && !tablet && !chartFullscreen) {
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
                                modifier = Modifier.fillMaxSize(), onClose = { showSettings = false }, onConnectRithmic = connectRithmic, onCheckHyperprop = { showHyperpropCheck = true }, updates = updates,
                                showOrderLayout = !nativeDesktop && !tablet, showChartSessionControls = !desktopStyle || !splitAvailable,
                                showDesktopExitSetting = nativeDesktop)
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

