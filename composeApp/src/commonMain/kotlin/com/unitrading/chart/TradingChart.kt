package com.unitrading.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.chart.drawing.*
import com.unitrading.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

private enum class ChartDragMode { PRICE, TIME, PLOT, PANE, NONE }
private data class PriceTag(val price: Long, val ink: Color, val background: Color, val priority: Int = 1)
private sealed interface PriceObject {
    val rank: Long
    data class Indicator(val item: ComputedIndicator, override val rank: Long) : PriceObject
    data class Drawing(val scene: DrawingScene, override val rank: Long) : PriceObject
}

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
    orders: List<PendingOrder>,
    actions: ChartOrderActions,
    modifier: Modifier = Modifier,
    environment: BrokerEnvironment = BrokerEnvironment.LOCAL_DEMO,
    executions: List<Execution> = emptyList(),
    focusedExecution: Execution? = null,
    connected: Boolean = true,
    onSide: (Side) -> Unit = {},
    instruments: List<Instrument> = emptyList(),
    onSymbol: (String) -> Unit = {},
    intervals: IntervalOptions = IntervalOptions(),
    chrome: ChartChrome = ChartChrome(),
    indicators: List<IndicatorConfig> = emptyList(),
    onIndicators: (() -> Unit)? = null,
    indicatorPaneFractions: Map<String, Float> = emptyMap(),
    onIndicatorPaneFractions: (Map<String, Float>) -> Unit = {},
) {
    val colors = TradingColors
    val compact = chrome.compact
    val mobilePlatform = !(chrome.desktopAxisStyle ?: (LocalChartPlatform.current == ChartPlatform.DESKTOP))
    val views = chrome.views
    val viewKey = chartViewKey(instrument.symbol, timeframe)
    /** This chart's bar count before a change of series: a series it has not shown before opens with it, on the newest bar. */
    val shownCount = remember { doubleArrayOf(views?.count ?: ChartViewport().count) }
    // A series shown before opens as it was left. A view of the past finds its right edge among the bars by time, so while
    // the bars are still on their way it waits for them in [restoring].
    val savedView = remember(accountKey, instrument.symbol, timeframe) { views?.view(viewKey) }
    var restoring by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(savedView?.takeIf { it.edgeTimeMs != null && candles.isEmpty() }) }
    var viewport by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(savedView?.viewport(candles) ?: ChartViewport(count = shownCount[0])) }
    // A price axis set by hand comes back only with a view of the past: a view on the newest bar opens on the automatic
    // axis, centred on the current price, since the price has usually moved away from where the kept axis was.
    var manualScale by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(savedView?.takeIf { it.edgeTimeMs != null }?.priceScale()) }
    /** Whether a kept axis was checked against the bars it opened on; see [PriceScale.suits]. */
    var manualChecked by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(manualScale == null) }
    /** The middle of the automatic axis while it follows the price; see [PriceScale.followedCenter]. */
    val autoCenter = remember(accountKey, instrument.symbol, timeframe) { doubleArrayOf(Double.NaN) }
    /** Locked, the wheel moves the chart back and forth through time instead of zooming it. */
    val locked = chrome.locked
    var previousCandles by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf(candles) }
    val localLayers = remember { ChartLayerState() }
    val layerState = chrome.layerState ?: localLayers
    var localShowHistory by layerState::showHistory
    val showHistory = if (chrome.layerState != null) localShowHistory else chrome.showHistory ?: localShowHistory
    var showPositions by layerState::showPositions
    var showOrders by layerState::showOrders
    var showData by layerState::showData
    fun setHistory(visible: Boolean) {
        if (chrome.layerState != null || chrome.showHistory == null) localShowHistory = visible
        if (chrome.showHistory != null) chrome.onShowHistory(visible)
    }
    var previousHideTrading by remember(chrome.drawings) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(chrome.drawings?.hideTrading) {
        chrome.drawings?.hideTrading?.let { hide ->
            if (previousHideTrading != null || hide) { showPositions = !hide; showOrders = !hide }
            previousHideTrading = hide
        }
    }
    var crosshair by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<Int?>(null) }
    /** Pointer row of the crosshair; null after keyboard moves, which keep only the vertical line. */
    var crosshairY by remember(accountKey, instrument.symbol, timeframe) { mutableStateOf<Float?>(null) }
    var drag by remember(accountKey, instrument.symbol, instrument.executionSymbol, timeframe) { mutableStateOf<LineDrag?>(null) }
    var size by remember { mutableStateOf(IntSize(900, 400)) }
    var chartMeasured by remember { mutableStateOf(false) }
    /** Where the floating toolbar and legend sit over the plot; line labels keep clear of it. */
    var legendArea by remember { mutableStateOf<Rect?>(null) }
    /** Where the mouse rests; the tag button under it lights up and names itself. */
    var hoverPoint by remember { mutableStateOf<Offset?>(null) }
    var lineMenu by remember(accountKey, instrument.symbol) { mutableStateOf<LineMenu?>(null) }
    var plotMenu by remember(accountKey, instrument.symbol) { mutableStateOf<PlotMenu?>(null) }
    /** Whether key presses reach the chart: the trading keys only trade while they do. */
    var chartFocused by remember { mutableStateOf(false) }
    /** Trading keys that are down, so the repeats of a held key place nothing. */
    val heldTradingKeys = remember { mutableSetOf<Key>() }
    var selectedTrade by remember(accountKey, instrument.symbol) { mutableStateOf<ClosedTrade?>(null) }
    var selectedExecution by remember(accountKey, instrument.symbol) { mutableStateOf<Execution?>(null) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val clipboard = LocalClipboardManager.current
    var showObjectTree by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showChartSettings by remember { mutableStateOf(false) }
    var quantityMenu by remember(accountKey, instrument.symbol) { mutableStateOf(false) }
    val closeIcon = rememberVectorPainter(AppIcons.Close)
    val density = LocalDensity.current
    val axis = chrome.priceAxis
    var axisMenu by remember { mutableStateOf(false) }
    var axisPage by remember { mutableStateOf(0) }
    // Tablets reclaim chart width, while long symbols and prices still receive enough space.
    val priceColumn = if (chrome.tabletCompactAxis) {
        val samples = listOfNotNull(instrument.symbol, quote?.bid?.let(instrument::formatPrice),
            quote?.ask?.let(instrument::formatPrice), candles.maxOfOrNull { it.high }?.let(instrument::formatPrice),
            candles.minOfOrNull { it.low }?.let(instrument::formatPrice), "00:00:00")
        maxOf(with(density) { 68.dp.toPx() }, samples.maxOf { textMeasurer.measure(it, TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum")).size.width.toFloat() } + with(density) { 12.dp.toPx() })
    } else with(density) { 84.dp.toPx() }
    val innerMargin = with(density) { 12.dp.toPx() }
    val left = if (axis.left) priceColumn else innerMargin
    val right = (size.width - if (axis.left) innerMargin else priceColumn).coerceAtLeast(left + 1)
    // Phone controls stay above the plot; the desktop-style workspace puts them below the axis.
    val top = with(density) { (if (mobilePlatform) 58.dp else 18.dp).toPx() }
    val bottom = (size.height - with(density) { (if (mobilePlatform) 30.dp else 54.dp).toPx() }).coerceAtLeast(top + 1)
    val plotWidth = right - left
    LaunchedEffect(chartMeasured, plotWidth, viewport.count, candles.size) {
        if (chartMeasured) {
            val fittedViewport = viewport.fitPixelWidth(plotWidth, candles.size)
            if (fittedViewport != viewport) viewport = fittedViewport
        }
    }
    val range = viewport.range(candles.size)
    val visible = if (range.isEmpty()) emptyList() else candles.slice(range)
    // Indicators use every loaded bar; separate panes take the bottom of the drawable area, above the time axis.
    val computedIndicators = remember(candles, indicators, timeframe) { computeIndicators(indicators, candles, timeframe) }
    val localObjectLayers = remember { ChartObjectLayers(MemorySnapshotStore()) }
    val objectLayers = chrome.objectLayers ?: localObjectLayers
    val objectRanks = objectLayers.forSymbol(instrument.symbol)
    val layerScope = rememberCoroutineScope()
    val layerHandlerOwner = remember { Any() }
    val allIndicatorIds by rememberUpdatedState((chrome.allIndicators ?: indicators).map { it.id })
    DisposableEffect(chrome.drawings, objectLayers, instrument.symbol) {
        val controller = chrome.drawings
        val move: (String, Int) -> Unit = { id, delta ->
            if (controller != null) layerScope.launch { objectLayers.moveBy(instrument.symbol, drawingLayerKey(id), allIndicatorIds, controller.drawings.map { it.id }, delta) }
        }
        controller?.attachOrderHandler(layerHandlerOwner, move)
        onDispose { controller?.detachOrderHandler(layerHandlerOwner) }
    }
    val overlays = computedIndicators.filter { it.overlay }
    val panes = computedIndicators.filterNot { it.overlay }.sortedByDescending { objectRanks[indicatorLayerKey(it.config.id)] ?: 0L }
    var paneFractions by remember(indicatorPaneFractions) { mutableStateOf(indicatorPaneFractions) }
    val minimumPaneHeight = with(density) { 56.dp.toPx() }
    val paneLayout = indicatorPaneLayout(top, bottom, panes.map { it.config.id }, paneFractions, minimumPaneHeight)
    val plotBottom = paneLayout.firstOrNull()?.top ?: bottom
    val currentPlotBottom by rememberUpdatedState(plotBottom)
    val selectedPosition = positions.firstOrNull { it.id == selectedPositionId } ?: positions.firstOrNull()
    val remote = environment != BrokerEnvironment.LOCAL_DEMO
    var wallClockMs by remember { mutableStateOf(0L) }
    LaunchedEffect(remote, candles.isEmpty()) {
        if (remote && candles.isNotEmpty()) while (true) {
            wallClockMs = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val nowMs = if (remote) wallClockMs.takeIf { it > 0 } else quote?.timeMs
    val chartQuote = if (chrome.regularSession && (nowMs == null || !isRegularChartTime(nowMs))) {
        candles.lastOrNull()?.close?.let { close -> quote?.copy(bid = close, ask = close) }
    } else quote
    /** Prices the automatic axis keeps in view: the quote, the position and its protection, orders and the draft. */
    val tradingPrices = buildList {
        chartQuote?.let { add(it.bid); add(it.ask) }
        if (showPositions) selectedPosition?.let { p -> add(p.entryPrice); p.stopLoss?.let(::add); p.takeProfit?.let(::add) }
        if (showOrders) orders.forEach { order ->
            order.request.entryPrice?.let(::add)
            if (order.role == OrderRole.ENTRY) { order.request.stopLoss?.let(::add); order.request.takeProfit?.let(::add) }
        }
        draft?.entryPrice?.let(::add)
        if (preview?.valid == true) preview.entryPrice?.let(::add)
        draft?.stopLoss?.let(::add)
        draft?.takeProfit?.let(::add)
    }
    val extras = if (axis.chartOnly) emptyList() else tradingPrices + overlayScaleValues(overlays, range)
    // A newly enabled protection must be reachable even after the user manually zoomed the price axis.
    // Only activation/direction/type changes fit the range; moving a line keeps its frozen drag scale.
    LaunchedEffect(accountKey, instrument.symbol, draft?.side, draft?.kind, draft?.stopLoss != null, draft?.takeProfit != null) {
        val manual = manualScale
        val prices = listOfNotNull(draft?.stopLoss, draft?.takeProfit).filter { it > 0 }
        if (manual != null && drag == null && prices.any { it < manual.low || it > manual.high }) {
            val low = minOf(manual.low, prices.min().toDouble())
            val high = maxOf(manual.high, prices.max().toDouble())
            val padding = (high - low) * .08
            manualScale = manual.copy(low = maxOf(0.0, low - padding), high = high + padding)
        }
    }
    val fitted = PriceScale.from(visible, extras, top, plotBottom)
    // While the newest bar is on screen the automatic axis keeps the current price in the middle of the plot.
    val livePrice = chartQuote?.bid?.takeIf { it > 0 } ?: candles.lastOrNull()?.close
    val autoMiddle = livePrice?.takeIf { !axis.chartOnly && !axis.fitAllBars && candles.isNotEmpty() && range.last == candles.lastIndex }
        ?.let { PriceScale.followedCenter(autoCenter[0].takeUnless { c -> c.isNaN() }, it, fitted.high - fitted.low) }
    SideEffect { autoCenter[0] = autoMiddle ?: Double.NaN }
    // Older bars far from the price may leave a centred axis; the newest fifth of the screen always stays in view.
    val automatic = autoMiddle?.let {
        val recent = visible.takeLast(maxOf(10, visible.size / 5)).flatMap { bar -> listOf(bar.low, bar.high) }
        PriceScale.centered(visible, tradingPrices + recent, it, top, plotBottom)
    } ?: fitted
    // A kept axis that no longer suits the bars (the market moved away, other price units) gives way to the automatic one.
    LaunchedEffect(manualChecked, candles.isEmpty()) {
        if (!manualChecked && candles.isNotEmpty()) {
            if (manualScale?.suits(fitted) == false) manualScale = null
            manualChecked = true
        }
    }
    // The reference is the first visible bar's open. Only axis text is rebased; broker prices stay absolute.
    val axisReference = visible.firstOrNull()?.open?.takeIf { it > 0 } ?: 1L
    fun axisPrice(price: Long): String = when (axis.mode) {
        PriceScaleMode.PERCENT -> formatFixed((((price.toDouble() / axisReference) - 1.0) * 10_000).roundToLong(), 2) + "%"
        PriceScaleMode.INDEXED -> formatFixed((price.toDouble() / axisReference * 10_000).roundToLong(), 2)
        else -> instrument.formatPrice(price)
    }
    val logarithmic = axis.mode == PriceScaleMode.LOGARITHMIC && visible.all { it.low > 0 }
    val baseScale = drag?.scale ?: manualScale?.takeIf { manualChecked || it.suits(fitted) }?.copy(top = top, bottom = plotBottom) ?: automatic
    val safeBase = if (logarithmic && baseScale.high <= 1.0) automatic else baseScale
    val configuredScale = if (drag != null) baseScale else safeBase.copy(
        low = if (logarithmic) safeBase.low.coerceAtLeast(1.0) else safeBase.low,
        logarithmic = logarithmic, inverted = axis.inverted)
    val ratio = remember(axis.lockRatio, axis.mode, instrument.symbol, timeframe) { doubleArrayOf(Double.NaN) }
    val scale = if (axis.lockRatio && drag == null) {
        if (!ratio[0].isFinite()) ratio[0] = configuredScale.barRatio(viewport.count, plotWidth)
        configuredScale.withBarRatio(ratio[0], viewport.count, plotWidth)
    } else configuredScale
    var previousAxis by remember { mutableStateOf(Triple(axis.mode, axis.inverted, axis.left)) }
    LaunchedEffect(axis.mode, axis.inverted, axis.left) {
        val next = Triple(axis.mode, axis.inverted, axis.left)
        if (next != previousAxis) { drag = null; manualScale = null; previousAxis = next }
    }

    val currentScale by rememberUpdatedState(scale)
    val drawingLayer = chrome.drawings?.let { rememberDrawingLayer(it, instrument.symbol, instrument, timeframe, candles, timeframe.millis, viewport, scale, Rect(left, top, right, plotBottom), bottom, colors, chrome.chartTimeZone.zoneId) }
    val currentVisible by rememberUpdatedState(visible)
    val currentCandles by rememberUpdatedState(candles)
    val currentTrades by rememberUpdatedState(trades)
    val currentExecutions by rememberUpdatedState(executions)
    val currentActions by rememberUpdatedState(actions)
    val currentQuote by rememberUpdatedState(quote)
    val currentDrawingLayer by rememberUpdatedState(drawingLayer)
    val currentTotal by rememberUpdatedState(candles.size)
    val currentLocked by rememberUpdatedState(locked)
    val orderVersions = orders.map { listOf(it.id, it.version, it.role, it.positionId, it.editable, it.filledLotsHundredths, it.request, it.protectionEditable) }
    val movedDraft = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft -> line.request.copy(entryPrice = edit.price)
            is EditableLine.DraftProtection -> line.request.withProtection(line.stop, edit.price)
            else -> null
        }
    }
    val draftDrag = movedDraft?.let(actions.estimateDraft)
    val activeDraft = draftDrag?.first ?: draft
    val dragEstimate = drag?.let { edit ->
        when (val line = edit.line) {
            is EditableLine.Draft, is EditableLine.DraftProtection -> draftDrag?.second
            is EditableLine.Pending -> actions.estimatePending(line.order.id, edit.price)
            is EditableLine.PendingProtection -> line.order.request.withProtection(line.stop, edit.price)
                .let { actions.estimatePendingProtection(line.order.id, it.stopLoss, it.takeProfit) }
            is EditableLine.Protection -> null
            is EditableLine.Standalone -> actions.estimateStandalone(line.order, edit.price)
        }
    }
    val activePreview = if (drag?.line is EditableLine.Draft || drag?.line is EditableLine.DraftProtection) dragEstimate else preview
    // A market draft enters at the side of the quote it trades against, so its lines show even while the estimate is invalid.
    val draftEntry = activeDraft?.let { it.entryPrice ?: activePreview?.entryPrice ?: quote?.let { q -> if (it.side == Side.BUY) q.ask else q.bid } }
    val lines = orderLines(instrument, positions, selectedPosition, orders, activeDraft, activePreview, draftEntry, drag,
        OrderLineOptions(showPositions, showOrders, actions.editPending, actions.editPendingProtection, actions.addProtection, actions.removeProtection,
            actions.addPositionProtection, actions.draftStopManaged, actions.draftTargetManaged, actions.directDraftSubmit, actions.inlineDraftQuantity), quote)
    val currentLines by rememberUpdatedState(lines)
    drawingLayer?.bindChart(
        orderLineAt = { point -> currentLines.any { it.edit != null && abs(currentScale.y(it.price) - point.y) <= 8 * density.density } },
        onZoomRange = { from, to -> viewport = zoomedViewport(from, to, currentTotal); manualScale = null },
        indicatorValuesAt = { index -> overlays.flatMap { item -> item.series.mapNotNull { series -> series.values.getOrNull(index)?.takeUnless { it.isNaN() } } } },
    )
    val readout = drag?.let {
        dragReadout(instrument, it, dragEstimate, lines.firstOrNull { line -> line.moving },
            sizedLots = draftDrag?.first?.lotsHundredths.takeIf { actions.stopRequiredForSizing })
    }
    // While a phone order panel is open the legend leaves the order lines' labels free, returning only for a drag's estimate.
    val legendShown = !chrome.ordering || readout != null
    val labelStyle = TextStyle(color = colors.Muted, fontSize = 11.sp, fontFeatureSettings = "tnum")
    val tagTexts = HashMap<String, TextLayoutResult>()
    fun tagText(value: String) = tagTexts.getOrPut(value) { textMeasurer.measure(value, labelStyle) }
    val tagMetrics = with(density) {
        OrderTagMetrics(maxOf((if (compact) 24.dp else 20.dp).toPx(), tagText("0").size.height + 4.dp.toPx()), 6.dp.toPx(), (if (compact) 28.dp else 22.dp).toPx(), 4.dp.toPx(), 3.dp.toPx())
    }
    val orderTags = layoutOrderTags(lines, scale, Rect(left + with(density) { 3.dp.toPx() }, top, right - with(density) { 8.dp.toPx() }, plotBottom),
        listOfNotNull(legendArea?.takeIf { legendShown }), tagMetrics) { tagText(it).size.width.toFloat() }
    val currentTags by rememberUpdatedState(orderTags)
    val hoveredButton = hoverPoint?.let { orderTags.buttonAt(it) }
    val chartCursor = when {
        hoveredButton?.cell?.action != null -> PointerIcon.Hand
        drag != null || hoverPoint?.let { it.x in left..right && it.y in top..plotBottom && grabAt(it, orderTags, lines, scale, 22 * density.density) != null } == true -> panelResizeCursor(false)
        else -> PointerIcon.Default
    }
    val periodMs = timeframe.millis
    val step = plotWidth / viewport.count.toFloat()
    val candleWidth = candleWidths(step, density.density)
    val currentViewport by rememberUpdatedState(viewport)
    /**
     * The bar slot under [x], counted from the first bar on screen. It does not stop at the loaded bars: in the empty space
     * before the oldest bar it is negative, after the newest one it exceeds the last index of [visible].
     */
    fun visibleIndex(x: Float, view: ChartViewport = currentViewport): Int =
        view.slotAt(((x - left) / plotWidth).toDouble(), currentTotal) - view.range(currentTotal).first
    /** The first and last slot the crosshair can stand on, counted like [visibleIndex]. */
    val crosshairSlots = viewport.slots(candles.size).let { (it.first - range.first)..(it.last - range.first) }
    /**
     * The bar the legend and the pane titles read: the one under the crosshair, the nearest one while the crosshair stands
     * past either end of the bars, the newest without a crosshair.
     */
    val readoutIndex = (crosshair ?: visible.lastIndex).coerceAtMost(visible.lastIndex).coerceAtLeast(0)

    LaunchedEffect(focusedTrade?.id, instrument.symbol, timeframe) {
        if (focusedTrade != null && focusedTrade.symbol == instrument.executionSymbol) {
            selectedTrade = focusedTrade
            selectedExecution = null
            val index = candles.indexOfLast { it.timeMs <= focusedTrade.closedAtMs }
            if (index >= 0) viewport = viewport.copy(offset = candles.size - index - viewport.count / 2).bounded(candles.size)
        }
    }
    LaunchedEffect(focusedExecution?.id, instrument.symbol, timeframe, candles.firstOrNull()?.timeMs) {
        if (focusedExecution != null && focusedExecution.symbol == instrument.executionSymbol) {
            selectedExecution = focusedExecution
            selectedTrade = null
            val index = candles.indexOfLast { it.timeMs <= focusedExecution.timeMs }
            if (index >= 0) viewport = viewport.copy(offset = candles.size - index - viewport.count / 2).bounded(candles.size)
        }
    }
    LaunchedEffect(selectedPosition?.id, selectedPosition?.version, positions.map { it.id }, orderVersions) { drag = null }
    LaunchedEffect(candles.size, candles.lastOrNull()?.timeMs) {
        val waiting = restoring
        viewport = if (waiting != null && candles.isNotEmpty()) waiting.viewport(candles).also { restoring = null } else viewport.following(previousCandles, candles)
        previousCandles = candles
    }
    // Kept as it changes, for when this series is shown again; a view still waiting for its bars is not replaced meanwhile.
    LaunchedEffect(viewKey, viewport, manualScale?.low, manualScale?.high) {
        if (views != null && candles.isNotEmpty() && restoring == null) views.keep(viewKey, SavedChartView.of(viewport, candles, manualScale))
    }
    SideEffect { shownCount[0] = viewport.count }

    // Folds at once on a toggle; the screen saves the choice and hands it back through [chrome] when the chart is rebuilt.
    var collapsed by remember(chrome.legendCollapsed) { mutableStateOf(chrome.legendCollapsed) }
    val countdown = candles.lastOrNull()?.let { last -> nowMs?.let { barCountdown(last.timeMs, periodMs, it) } }
    val layers = listOf(
        ChartToggle("仓位", showPositions) { showPositions = !showPositions },
        ChartToggle("挂单", showOrders) { showOrders = !showOrders },
        ChartToggle("历史", showHistory) {
            setHistory(!showHistory)
        },
        ChartToggle("数据表", showData) { showData = !showData },
    ) + listOfNotNull(chrome.onDomToggle?.let { toggle -> ChartToggle("DOM 盘口", chrome.domVisible, toggle) })
    val indicatorToggle = onIndicators?.let { open -> ChartToggle(if (indicators.isEmpty()) "指标" else "指标 ${indicators.size}", indicators.any { it.visible }, open) }
    val pickable = instruments.ifEmpty { listOf(instrument) }
    // Phones pin one toolbar row above the chart in every mode; wider layouts float the full toolbar over the plot.
    val toolbar: @Composable (Modifier) -> Unit = { m ->
        ChartToolbar(instrument, pickable, onSymbol, timeframe, onTimeframe, intervals, layers, indicatorToggle, m, compact, chrome.toolbarEnd)
    }

    Column(modifier.background(colors.Surface)) {
        if (instrument.isContinuous) Text("连续图表 ${instrument.symbol} · 下单 / 买卖报价 ${instrument.executionLabel}" +
            if (instrument.tradeSymbol != null && instrument.tradeSymbolStatus?.startsWith("价格匹配") == true) " · 价格匹配，非券商确认" else "",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("continuous-route"), style = MaterialTheme.typography.labelSmall, color = colors.Accent)
        if (compact && !chrome.sharedToolbar) {
            toolbar(Modifier.fillMaxWidth())
            DividerLine()
        }
        if (showData) {
            if (!compact && !chrome.sharedToolbar) {
                toolbar(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
                DividerLine()
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${environment.label} · ${if (timeframe.seconds >= Timeframe.DAY) "交易日" else chrome.chartTimeZoneLabel ?: chrome.chartTimeZone.label} / OHLC（${instrument.symbol}）", style = MaterialTheme.typography.labelMedium, color = colors.Muted)
            }
            LazyColumn(Modifier.weight(1f).testTag("ohlc-table")) {
                items(candles.asReversed(), key = { it.timeMs }) { c ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(chartBarDateTime(c.timeMs, timeframe, chrome.chartTimeZone.zoneId), style = MaterialTheme.typography.labelSmall, color = colors.Muted)
                        Text("开 ${instrument.formatPrice(c.open)}    高 ${instrument.formatPrice(c.high)}\n低 ${instrument.formatPrice(c.low)}    收 ${instrument.formatPrice(c.close)}" +
                            (c.volume?.let { "    量 ${formatVolume(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                    DividerLine()
                }
            }
        } else if (candles.isEmpty()) {
            if (!compact && !chrome.sharedToolbar) {
                toolbar(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
                DividerLine()
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyState("暂无行情", chrome.sessionNotice ?: "等待交易商提供历史 K 线。", icon = com.unitrading.ui.AppIcons.Candlestick) }
        } else {
            // The toolbar, legend and navigation float over the plot instead of taking their own banner above it.
            val crosshairState = crosshair?.let { index -> "$index@" + (crosshairY?.takeIf { it in top..plotBottom }?.let { scale.price(it, instrument.tickSize).toString() } ?: "none") } ?: "none"
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize().background(colors.Chart).onSizeChanged { size = it; chartMeasured = true }.testTag("trading-chart")
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, axis.left, axis.mode, axis.lockRatio, axis.fitAllBars) {
                            // Initial pass wins over drawing/order-line gestures. Once two fingers take
                            // ownership, consume through the LAST finger up: never commit a leftover drag.
                            awaitPointerEventScope {
                                var pinchOwned = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val fingers = event.changes.filter { it.pressed && it.type == PointerType.Touch }
                                    if (!pinchOwned && fingers.size >= 2 && fingers.all {
                                        it.position.x in left..right && it.position.y in top..currentPlotBottom
                                    }) {
                                        pinchOwned = true
                                        drag = null; crosshair = null; crosshairY = null; hoverPoint = null; lineMenu = null; plotMenu = null
                                        currentDrawingLayer?.controller?.cancelPointerGesture()
                                    }
                                    if (!pinchOwned) continue
                                    val stable = fingers.filter { it.previousPressed }.take(2)
                                    if (stable.size == 2) {
                                        val a = stable[0]; val b = stable[1]
                                        val before = (a.previousPosition + b.previousPosition) / 2f
                                        val after = (a.position + b.position) / 2f
                                        val oldDistance = (a.previousPosition - b.previousPosition).getDistance()
                                        val distance = (a.position - b.position).getDistance()
                                        if (oldDistance > 8 * density.density && distance > 8 * density.density) {
                                            val factor = (distance / oldDistance).toDouble().coerceIn(.2, 5.0)
                                            viewport = viewport.pinch(factor, (before.x - left) / plotWidth, (after.x - left) / plotWidth, currentTotal, maxVisibleCandleCount(plotWidth))
                                            // In auto-fit, two fingers change the time view only: the price axis
                                            // keeps fitting the newly visible bars rather than becoming manual.
                                            if (!(axis.fitAllBars && manualScale == null)) {
                                                val s = currentScale
                                                manualScale = s.pinch(factor, (before.y - top) / (currentPlotBottom - top),
                                                    (after.y - top) / (currentPlotBottom - top), instrument.tickSize * 4.0)
                                                ratio[0] = Double.NaN
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) pinchOwned = false
                                }
                            }
                        }
                        .then(drawingLayer?.let { Modifier.drawingCursor(it).drawingInput(it) } ?: Modifier)
                        .semantics {
                            contentDescription = "${instrument.name}K线。双指张合缩放，双指移动平移；拖动图表平移；" + (if (locked) "图表已锁定，滚轮前后移动图表；" else "滚轮缩放时间，在右侧价格轴上滚动缩放价格；") + "上下滑动右侧价格轴缩放价格，左右滑动底部时间轴缩放时间。拖动指标窗口上沿调整高度。订单线可拖动预览，线上按钮可平仓或撤单，右键或长按打开同样的操作，从 TP、SL 按钮拖动到目标价设置止盈止损。在空白处点右键或长按可选择订单并打开下单栏。" +
                                (actions.tradingKeys?.let {
                                    if (actions.stopRequiredForSizing) "图表获得焦点后按 ${it.buy} 或 ${it.sell} 打开下单栏填写止损价。"
                                    else "已开启键盘下单：图表获得焦点后按 ${it.buy} 市价买入，按 ${it.sell} 市价卖出。"
                                } ?: "")
                            stateDescription = "bars:${viewport.count};offset:${viewport.offset};low:${scale.low};high:${scale.high};auto:${manualScale == null};locked:$locked;panes:${panes.size};overlays:${overlays.size};cross:$crosshairState;plotBottom:$plotBottom;lockedTime:${chrome.lockedCrosshairTime ?: "none"};barStep:$step;barBody:${candleWidth.body};plotTop:$top;countdown:${countdown ?: "none"};history:${if (showHistory) trades.size + executions.size else 0}"
                            customActions = lines.flatMap { it.cells }.mapNotNull { cell -> cell.action?.let { action -> CustomAccessibilityAction(cell.hint) { currentActions.onLineAction(action); true } } }
                            set(OrderTagButtons, orderTags.flatMap { it.cells }.filter { it.cell.action != null || it.cell.drag != null }.associate { it.cell.hint to it.bounds })
                        }
                        .pointerHoverIcon(chartCursor)
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, axis.left, axis.mode, axis.lockRatio) {
                            /** The menu of the order line at [point], or else the plot's own; whether one opened. */
                            fun openChartMenu(point: Offset, touch: Boolean): Boolean {
                                if ((if (axis.left) point.x < left else point.x > right) && point.y in top..currentPlotBottom) { axisPage = 0; axisMenu = true; return true }
                                if (point.x !in left..right || point.y !in top..currentPlotBottom) return false
                                val line = lineAt(point, currentTags, currentLines, currentScale, (if (touch) 14 else 8) * density.density)
                                if (line != null) lineMenu = LineMenu(point, line.cells.filter { it.action != null })
                                else {
                                    // Nothing under the pointer: the plot's own menu, at the price the crosshair shows there.
                                    val price = currentScale.price(point.y, instrument.tickSize)
                                    val orders = plotOrders(price, currentQuote, currentActions.entryKinds)
                                    if (touch && orders.isEmpty() && currentDrawingLayer == null) return false
                                    val index = currentViewport.slotAt(((point.x - left) / plotWidth).toDouble(), currentTotal)
                                    plotMenu = PlotMenu(point, price, orders, desktop = !touch, timeMs = slotTime(currentCandles, index, periodMs))
                                }
                                return true
                            }
                            // On the Initial pass, so the tap and drag detectors below never see the press that opens a menu.
                            // A right click and a finger held still on the plot open the same menus.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    // The drawing layer saw the press first: it opened a drawing's menu, cancelled a placement, or
                                    // took a press on a drawing it holds, whose menu it opens itself when the finger stays.
                                    if (event.changes.any { it.isConsumed }) continue
                                    val down = event.changes.firstOrNull() ?: continue
                                    if (event.buttons.isSecondaryPressed) {
                                        if (openChartMenu(down.position, touch = false)) event.changes.forEach { it.consume() }
                                        continue
                                    }
                                    if (down.type != PointerType.Touch || event.changes.count { it.pressed } != 1) continue
                                    val point = down.position
                                    // The finger has to stay down, alone and still, for the long-press time; a lift, a slide or a
                                    // second finger leaves the gesture to the tap, pan and pinch detectors.
                                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val next = awaitPointerEvent(PointerEventPass.Initial)
                                            val finger = next.changes.firstOrNull { it.id == down.id }
                                            if (next.changes.any { it.isConsumed } || next.changes.count { it.pressed } != 1 || finger == null || !finger.pressed ||
                                                (finger.position - point).getDistance() > viewConfiguration.touchSlop) break
                                        }
                                        true
                                    }
                                    if (released != null) continue
                                    // A drawing under the finger first, as for a right click, then an order line, then the plot.
                                    val layer = currentDrawingLayer
                                    val ctx = layer?.ctx
                                    val opened = (layer != null && ctx != null && layer.controller.openMenuAt(point, ctx, layer.orderLineAt)) ||
                                        openChartMenu(point, touch = true)
                                    if (!opened) { layer?.controller?.showTooltip(point); continue }
                                    // The crosshair shows the price the menu names; the rest of the gesture belongs to the menu.
                                    crosshair = visibleIndex(point.x); crosshairY = point.y
                                    do {
                                        val next = awaitPointerEvent(PointerEventPass.Initial)
                                        next.changes.forEach { it.consume() }
                                    } while (next.changes.any { it.pressed })
                                }
                            }
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, axis.left, axis.mode, axis.lockRatio) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Exit) { crosshair = null; hoverPoint = null }
                                    else if (event.type == PointerEventType.Scroll) {
                                        val change = event.changes.firstOrNull() ?: continue
                                        val point = change.position
                                        val ticks = change.scrollDelta
                                        change.consume()
                                        if (drag != null) continue
                                        if ((if (axis.left) point.x < left else point.x >= right) && point.y < currentPlotBottom && !currentLocked) {
                                            manualScale = currentScale.zoomWheel(ticks.y, instrument.tickSize * 4.0)
                                            ratio[0] = Double.NaN
                                        }
                                        else {
                                            // Sideways scrolling (Shift + wheel, or a trackpad) moves through time; rolling zooms, or on a
                                            // locked chart moves through time too: rolling towards the user goes later, as Shift + wheel does.
                                            val sideways = abs(ticks.x) > abs(ticks.y)
                                            viewport = if (sideways || currentLocked) viewport.pan(-(if (sideways) ticks.x else ticks.y) * plotWidth * .1f, plotWidth, currentTotal)
                                                else viewport.zoomAt(ticks.y, ((point.x - left) / plotWidth).coerceIn(0f, 1f), currentTotal, maxVisibleCandleCount(plotWidth))
                                            if (crosshair != null) crosshair = visibleIndex(point.x, viewport)
                                        }
                                    }
                                    else if ((event.type == PointerEventType.Move || event.type == PointerEventType.Enter) && event.changes.none { it.pressed }) {
                                        val pointer = event.changes.firstOrNull()?.position
                                        hoverPoint = pointer
                                        if (pointer != null && drag == null) { crosshair = visibleIndex(pointer.x); crosshairY = pointer.y }
                                    }
                                }
                            }
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, showHistory, panes.size, axis.left, axis.mode, axis.lockRatio) {
                            detectTapGestures(onTap = { point ->
                                val button = currentTags.buttonAt(point, if (compact) 8 * density.density else 0f)
                                if (button != null) {
                                    if (button.cell.action == LineAction.EditDraftQuantity) quantityMenu = true
                                    else button.cell.action?.let(currentActions.onLineAction)
                                    return@detectTapGestures
                                }
                                val bars = currentVisible
                                if (bars.isNotEmpty() && point.x in left..right && point.y < bottom) {
                                    val index = visibleIndex(point.x)
                                    crosshair = index
                                    crosshairY = point.y
                                    // A tap in the empty space beside the bars has no bar to pick trades from and clears the selection.
                                    val barTime = bars.getOrNull(index)?.let { it.timeMs until (it.timeMs + periodMs) } ?: LongRange.EMPTY
                                    if (showHistory) selectedTrade = currentTrades.firstOrNull { trade -> trade.closedAtMs in barTime }
                                    if (showHistory) {
                                        selectedExecution = currentExecutions.filter { it.timeMs in barTime }
                                            .minByOrNull { abs(currentScale.y(it.price) - point.y) }?.takeIf { abs(currentScale.y(it.price) - point.y) <= 24 * density.density }
                                        if (selectedExecution != null) selectedTrade = null
                                    }
                                }
                            }, onLongPress = { point -> crosshair = visibleIndex(point.x); crosshairY = point.y })
                        }
                        .pointerInput(accountKey, instrument.symbol, timeframe, size, selectedPosition?.id, selectedPosition?.version, orderVersions, draft, panes.size, axis.left, axis.mode, axis.lockRatio, axis.fitAllBars) {
                            var mode = ChartDragMode.NONE
                            var labelDragOffset = 0f
                            detectDragGestures(
                                orientationLock = null,
                                onDragStart = { down, _, _ ->
                                    val point = down.position
                                    crosshair = null
                                    mode = when {
                                        lineMenu != null || plotMenu != null -> ChartDragMode.NONE
                                        (if (axis.left) point.x < left else point.x >= right) && point.y < currentPlotBottom -> ChartDragMode.PRICE
                                        point.y >= bottom && point.x < right -> ChartDragMode.TIME
                                        point.x in left..right && point.y in top..currentPlotBottom -> ChartDragMode.PLOT
                                        point.x in left..right && point.y in currentPlotBottom..bottom -> ChartDragMode.PANE
                                        else -> ChartDragMode.NONE
                                    }
                                    val s = currentScale
                                    val grab = if (mode == ChartDragMode.PLOT) grabAt(point, currentTags, currentLines, s, 22 * density.density, if (compact) 8 * density.density else 0f) else null
                                    if (grab != null) {
                                        labelDragOffset = if (grab.onTag) point.y - s.y(grab.line.price) else 0f
                                        drag = LineDrag(grab.line, s, grab.line.price)
                                    }
                                    if (mode == ChartDragMode.PRICE || mode == ChartDragMode.PLOT && drag == null && !(axis.fitAllBars && manualScale == null)) manualScale = s
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val active = drag
                                    if (active != null) {
                                        drag = active.copy(price = active.scale.price(change.position.y - labelDragOffset, instrument.tickSize))
                                    } else when (mode) {
                                        ChartDragMode.PRICE -> { manualScale = currentScale.zoom(amount.y, instrument.tickSize * 4.0); ratio[0] = Double.NaN }
                                        ChartDragMode.TIME -> viewport = viewport.zoom(amount.x, plotWidth, currentTotal)
                                        ChartDragMode.PLOT -> {
                                            viewport = viewport.pan(amount.x, plotWidth, currentTotal)
                                            // Vertical finger/mouse drift while moving through time must not
                                            // cancel auto-fit. A manually set axis still pans in both directions.
                                            if (!(axis.fitAllBars && manualScale == null)) manualScale = (manualScale ?: currentScale).pan(amount.y)
                                        }
                                        ChartDragMode.PANE -> viewport = viewport.pan(amount.x, plotWidth, currentTotal)
                                        ChartDragMode.NONE -> Unit
                                    }
                                },
                                onDragEnd = { _ ->
                                    drag?.takeIf { it.price != it.line.price }?.let { edit ->
                                        val act = currentActions
                                        when (val line = edit.line) {
                                            is EditableLine.Protection -> act.onProtection(line.position,
                                                if (line.stop) edit.price else line.position.stopLoss, if (line.stop) line.position.takeProfit else edit.price)
                                            is EditableLine.Pending -> act.onPendingEntry(line.order, edit.price)
                                            is EditableLine.PendingProtection -> line.order.request.withProtection(line.stop, edit.price)
                                                .let { act.onPendingProtection(line.order, it.stopLoss, it.takeProfit) }
                                            is EditableLine.Draft -> act.onDraftEntry(edit.price)
                                            is EditableLine.DraftProtection -> act.onDraftProtection(line.stop, edit.price)
                                            is EditableLine.Standalone -> act.onStandalone(line.order, edit.price)
                                        }
                                    }
                                    drag = null
                                },
                                onDragCancel = { drag = null },
                            )
                        }
                        .onFocusChanged { chartFocused = it.isFocused; if (!it.isFocused) heldTradingKeys.clear() }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp) heldTradingKeys.remove(event.key)
                            val side = actions.tradingKeys?.side(event.key, event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed)
                            if (event.type != KeyEventType.KeyDown) false
                            // A key held down repeats its press, and an order that goes out at once must go out once: the repeats
                            // are swallowed until the key comes up. An order that asks first opens a dialog, which takes the keys.
                            else if (event.isAltPressed && !event.isShiftPressed && !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.R) {
                                viewport = ChartViewport(); manualScale = null; ratio[0] = Double.NaN; selectedTrade = null; selectedExecution = null; true
                            }
                            else if (event.isShiftPressed && !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.T && chrome.onOpenOrderAt != null) {
                                hoverPoint?.takeIf { it.x in left..right && it.y in top..plotBottom }?.let { chrome.onOpenOrderAt.invoke(scale.price(it.y, instrument.tickSize)) }; true
                            }
                            else if (chrome.keyboardTrading && event.isAltPressed && event.isShiftPressed && !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.S) {
                                if (heldTradingKeys.add(event.key)) hoverPoint?.takeIf { it.x in left..right && it.y in top..plotBottom }?.let { point ->
                                    val price = scale.price(point.y, instrument.tickSize)
                                    if (quote != null && OrderKind.LIMIT in actions.entryKinds) actions.onKeyboardEntryOrder(Side.SELL, OrderKind.LIMIT, price)
                                }
                                true
                            }
                            else if (side != null) { if ((!actions.instantOrders && !actions.stopRequiredForSizing) || heldTradingKeys.add(event.key)) actions.onMarketOrder(side); true }
                            else when (event.key) {
                                Key.DirectionLeft -> { crosshair = ((crosshair ?: visible.lastIndex) - 1).coerceAtLeast(crosshairSlots.first); crosshairY = null; true }
                                Key.DirectionRight -> { crosshair = ((crosshair ?: 0) + 1).coerceAtMost(crosshairSlots.last); crosshairY = null; true }
                                Key.Escape -> { drag = null; crosshair = null; true }
                                else -> false
                            }
                        }.focusable()
                ) {
                    fun text(value: String, x: Float, y: Float, color: Color = colors.Muted) {
                        val layout = textMeasurer.measure(value, labelStyle.copy(color = color))
                        drawText(layout, topLeft = Offset(x, y.coerceIn(0f, (size.height - layout.size.height).toFloat().coerceAtLeast(0f))))
                    }
                    fun x(time: Long): Float? {
                        if (visible.isEmpty() || time < visible.first().timeMs || time >= visible.last().timeMs + periodMs) return null
                        val index = visible.indexOfLast { it.timeMs <= time }
                        return left + (range.first + index - viewport.start(candles.size) + 0.5).toFloat() * step
                    }
                    val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
                    val labelPadding = 3.dp.toPx()
                    val priceTags = mutableListOf<PriceTag>()
                    val priceIntervals = priceAxisIntervals(plotBottom - top, density.density, labelHeight)
                    val drawnPriceLabels = mutableSetOf<String>()
                    for (i in 0..priceIntervals) {
                        val y = top + (plotBottom - top) * i / priceIntervals
                        drawLine(colors.Grid, Offset(left, y), Offset(right, y), 1f)
                        val label = axisPrice(scale.price(y, instrument.tickSize))
                        if (drawnPriceLabels.add(label)) {
                            val labelX = if (axis.left) (left - textMeasurer.measure(label, labelStyle).size.width - 8f).coerceAtLeast(0f) else right + 8f
                            text(label, labelX, y - labelHeight / 2)
                        }
                    }
                    for (i in 0..4) {
                        if (visible.isEmpty()) break
                        val index = ((visible.size - 1) * i / 4)
                        val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        drawLine(colors.Grid, Offset(x, top), Offset(x, bottom), 1f)
                        text(axisTimeLabel(visible[index].timeMs, timeframe, chrome.chartTimeZone.zoneId), (x - 14f).coerceIn(left, right - 28f), bottom + 9f)
                    }
                    val drawingFrame = drawingLayer?.let { prepareLayerFrame(it) { id -> objectRanks[drawingLayerKey(id)] ?: 0L } }
                    val plotRect = Rect(left, top, right, plotBottom)
                    val priceObjects = buildList<PriceObject> {
                        overlays.forEach { add(PriceObject.Indicator(it, objectRanks[indicatorLayerKey(it.config.id)] ?: 0L)) }
                        drawingFrame?.scenes?.forEach { add(PriceObject.Drawing(it, objectRanks[drawingLayerKey(it.drawing.id)] ?: 0L)) }
                    }.sortedBy { it.rank }
                    fun paintPriceObjects(behindCandles: Boolean) {
                        priceObjects.forEach { item ->
                            if ((item.rank < 0L) == behindCandles) when (item) {
                                is PriceObject.Indicator -> drawIndicatorOverlays(listOf(item.item), range, scale, colors) { index -> left + (index - viewport.start(candles.size) + .5).toFloat() * step }
                                is PriceObject.Drawing -> if (drawingFrame != null) drawDrawingScene(item.scene, drawingFrame, plotRect, textMeasurer)
                            }
                        }
                    }
                    clipRect(left, top, right, plotBottom) {
                    if (chrome.showWatermark) {
                        val mark = textMeasurer.measure("${instrument.symbol} · ${timeframe.label}", TextStyle(color = colors.Muted.copy(alpha = .12f), fontSize = 36.sp))
                        drawText(mark, topLeft = Offset((left + right - mark.size.width) / 2f, (top + plotBottom - mark.size.height) / 2f))
                    }
                    if (activeDraft != null && draftEntry != null) {
                        val entryY = scale.y(draftEntry)
                        listOf(activeDraft.stopLoss to colors.Down, activeDraft.takeProfit to colors.Up).forEach { (price, color) ->
                            if (price != null) {
                                val y = scale.y(price)
                                drawRect(color.copy(alpha = 0.07f), Offset(left + plotWidth * .62f, minOf(y, entryY)), Size(plotWidth * .38f, abs(y - entryY)))
                            }
                        }
                    }
                    paintPriceObjects(true)
                    visible.forEachIndexed { index, candle ->
                        val center = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        val color = if (candle.close >= candle.open) colors.UpBody else colors.DownBody
                        val width = candleWidth.body
                        val rising = candle.close >= candle.open
                        drawLine(if (rising) colors.UpWick else colors.DownWick, Offset(center, scale.y(candle.high)), Offset(center, scale.y(candle.low)), candleWidth.wick)
                        val y = minOf(scale.y(candle.open), scale.y(candle.close))
                        val height = maxOf(abs(scale.y(candle.open) - scale.y(candle.close)), 1.6f)
                        // At subpixel density use the outline color so hollow candles remain visible on a light chart.
                        val denseColor = if (rising) colors.UpBorder else colors.DownBorder
                        drawRect(if (step < 2f) denseColor else color, Offset(center - width / 2, y), Size(width, height))
                        // Borders would overlap neighbouring candles when a slot is less than two pixels wide.
                        if (step >= 2f) drawRect(if (rising) colors.UpBorder else colors.DownBorder, Offset(center - width / 2, y), Size(width, height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = minOf(density.density, width)))
                    }
                    paintPriceObjects(false)
                    drawingFrame?.let { drawDrawingControls(it, plotRect, textMeasurer, colors.Chart) }
                    chartQuote?.let {
                        val y = scale.y(it.bid)
                        if (axis.showPriceLine) drawLine(colors.Accent.copy(alpha = .65f), Offset(left, y), Offset(right, y), 1f)
                        if (axis.showBidAsk) drawLine(colors.Muted.copy(alpha = .35f), Offset(left, scale.y(it.ask)), Offset(right, scale.y(it.ask)), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
                        if (axis.showLastPrice) priceTags.add(PriceTag(it.bid, colors.OnBrand, colors.Brand, priority = 2))
                    }
                    if (showHistory) executions.forEach { execution ->
                        val x = x(execution.timeMs)
                        if (x != null) {
                            val y = scale.y(execution.price)
                            val up = execution.side == Side.BUY
                            val marker = Path().apply {
                                moveTo(x, y + if (up) -6f else 6f)
                                lineTo(x - 5f, y + if (up) 5f else -5f)
                                lineTo(x + 5f, y + if (up) 5f else -5f)
                                close()
                            }
                            drawPath(marker, colors.Surface, style = Stroke(4f))
                            drawPath(marker, if (up) colors.Up else colors.Down)
                            if (execution.id == selectedExecution?.id) drawCircle(colors.Accent, 9.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                        }
                    }
                    if (showHistory) trades.forEach { trade ->
                        fun marker(time: Long, price: Long, opening: Boolean) {
                            val x = x(time) ?: return
                            val y = scale.y(price)
                            val upward = if (opening) trade.side == Side.BUY else trade.side == Side.SELL
                            val color = if (upward) colors.Up else colors.Down
                            val path = Path().apply {
                                moveTo(x, y + if (upward) -6 else 6)
                                lineTo(x - 5, y + if (upward) 5 else -5)
                                lineTo(x + 5, y + if (upward) 5 else -5)
                                close()
                            }
                            drawPath(path, colors.Surface, style = Stroke(4f))
                            drawPath(path, color)
                        }
                        marker(trade.openedAtMs, trade.entryPrice, true)
                        marker(trade.closedAtMs, trade.exitPrice, false)
                        if (trade.id == selectedTrade?.id) {
                            val x1 = x(trade.openedAtMs)
                            val x2 = x(trade.closedAtMs)
                            if (x1 != null && x2 != null) drawLine(colors.Muted, Offset(x1, scale.y(trade.entryPrice)), Offset(x2, scale.y(trade.exitPrice)), 1.5f)
                        }
                    }
                    lines.forEach { line ->
                        val y = scale.y(line.price)
                        if (y !in top..plotBottom) return@forEach
                        val color = colors.lineColor(line.tone)
                        drawLine(color.copy(alpha = line.alpha), Offset(left, y), Offset(right, y), strokeWidth = if (line.moving) 2.2f else 1.3f,
                            pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null)
                        priceTags.add(PriceTag(line.price, contentOn(color), color))
                    }
                    val tagStyle = OrderTagStyle(colors, 3.dp.toPx(), 13.dp.toPx(), closeIcon) { tagText(it) }
                    orderTags.forEach { drawOrderTag(it, tagStyle, right, hoveredButton) }
                    }
                    panes.forEachIndexed { index, pane ->
                        val bounds = paneLayout[index]
                        val paneRank = objectRanks[indicatorLayerKey(pane.config.id)] ?: 0L
                        drawingFrame?.let { frame -> drawFullHeightScenes(frame.scenes.filter { (objectRanks[drawingLayerKey(it.drawing.id)] ?: 0L) < paneRank }, plotRect, bounds.top, bounds.bottom) }
                        drawIndicatorPanes(listOf(pane), candles, range, range.first + readoutIndex, instrument, left, right, listOf(bounds), step,
                            textMeasurer, labelStyle, colors, axis.left) { bar -> left + (bar - viewport.start(candles.size) + .5).toFloat() * step }
                        drawingFrame?.let { frame -> drawFullHeightScenes(frame.scenes.filter { (objectRanks[drawingLayerKey(it.drawing.id)] ?: 0L) >= paneRank } + listOfNotNull(frame.draft), plotRect, bounds.top, bounds.bottom) }
                    }
                    drawLine(colors.Border, Offset(if (axis.left) left else right, top), Offset(if (axis.left) left else right, bottom))
                    drawLine(colors.Border, Offset(left, bottom), Offset(right, bottom))
                    drawingLayer?.let { drawLayerMarks(it, labelStyle, textMeasurer, if (axis.left) 0f else right + 4.dp.toPx(), if (axis.left) left - 4.dp.toPx() else this.size.width, bottom + 1f) { price -> axisPrice(price.roundToLong()) } }
                    val tagGap = labelHeight + labelPadding * 2 + 2.dp.toPx()
                    val capacity = ((plotBottom - top) / tagGap).toInt().coerceAtLeast(1)
                    val tags = priceTags.filter { scale.y(it.price) in top..plotBottom }.sortedByDescending { it.priority }.distinctBy { it.price }.take(capacity).sortedBy { scale.y(it.price) }
                    val firstCenter = top + labelHeight / 2 + labelPadding
                    val lastCenter = maxOf(firstCenter, plotBottom - labelHeight / 2 - labelPadding)
                    val lineHeight = labelHeight + labelPadding * 2
                    // Reserve a line above the live price for the contract name and one below for the countdown, so a
                    // position or order tag right next to the price moves aside instead of hiding them.
                    val centers = spreadLabelCenters(tags.map { scale.y(it.price) }, firstCenter, lastCenter, tagGap,
                        tags.map { if (it.priority == 2) lineHeight else 0f }, tags.map { if (it.priority == 2 && countdown != null && axis.showCountdown) lineHeight else 0f })
                    val tagLeft = if (axis.left) 0f else right + 4.dp.toPx()
                    val tagWidth = (if (axis.left) left - 4.dp.toPx() else size.width - tagLeft).coerceAtLeast(1f)
                    tags.zip(centers).forEachIndexed { index, (tag, center) ->
                        drawLine(colors.Muted, Offset(if (axis.left) left else right, scale.y(tag.price)), Offset(if (axis.left) tagLeft + tagWidth else tagLeft, center), 1f)
                        drawRoundRect(tag.background, Offset(tagLeft, center - lineHeight / 2), Size(tagWidth, lineHeight), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                        text(axisPrice(tag.price), tagLeft + labelPadding, center - labelHeight / 2, tag.ink)
                        if (tag.priority == 2) {
                            // The live price tag also names the contract above and counts the bar down below, unless a neighbour needs the room.
                            val roomAbove = centers.getOrNull(index - 1)?.let { center - it } ?: Float.MAX_VALUE
                            val roomBelow = centers.getOrNull(index + 1)?.let { it - center } ?: Float.MAX_VALUE
                            if (roomAbove >= lineHeight * 2) {
                                drawRect(tag.background, Offset(tagLeft, center - lineHeight * 1.5f), Size(tagWidth, lineHeight))
                                text(instrument.symbol, tagLeft + labelPadding, center - lineHeight * 1.5f + labelPadding, tag.ink)
                            }
                            if (axis.showCountdown && countdown != null && roomBelow >= lineHeight * 2) {
                                drawRect(tag.background, Offset(tagLeft, center + lineHeight / 2), Size(tagWidth, lineHeight))
                                text(countdown, tagLeft + labelPadding, center + lineHeight / 2 + labelPadding, tag.ink)
                            }
                        }
                    }
                    // A view that moved under a resting crosshair (zoom buttons, a jump to a trade) can leave its slot outside the plot.
                    val lockedIndex = chrome.lockedCrosshairTime?.takeIf { candles.isNotEmpty() && it >= candles.first().timeMs && it < candles.last().timeMs + periodMs }
                        ?.let { time -> candles.indexOfLast { it.timeMs <= time } - range.first }
                    val displayedCrosshair = if (chrome.lockedCrosshairTime == null) crosshair else lockedIndex
                    displayedCrosshair?.takeIf { it in crosshairSlots && drawingLayer?.showsCrosshair != false }?.let { index ->
                        val slotTimeMs = slotTime(candles, range.first + index, periodMs) ?: return@let
                        val x = left + (range.first + index - viewport.start(candles.size) + .5).toFloat() * step
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        val line = colors.Ink.copy(alpha = if (drawingLayer?.showsCrosshairLines != false) .55f else 0f)
                        drawLine(line, Offset(x, top), Offset(x, bottom), 1f, pathEffect = dash)
                        val timeLabel = crosshairTimeLabel(slotTimeMs, timeframe, chrome.chartTimeZone.zoneId)
                        val timeWidth = textMeasurer.measure(timeLabel, labelStyle).size.width + labelPadding * 2
                        val timeLeft = (x - timeWidth / 2).coerceIn(left, maxOf(left, right - timeWidth))
                        drawRect(colors.Ink, Offset(timeLeft, bottom + 1f), Size(timeWidth, lineHeight))
                        text(timeLabel, timeLeft + labelPadding, bottom + 1f + labelPadding, colors.Surface)
                        val y = crosshairY?.takeIf { chrome.lockedCrosshairTime == null } ?: return@let
                        val value = when {
                            y in top..plotBottom -> axisPrice(scale.price(y, instrument.tickSize))
                            y in plotBottom..bottom -> paneValueLabel(panes, y, paneLayout, range, instrument, labelHeight, labelPadding)
                            else -> null
                        } ?: return@let
                        drawLine(line, Offset(left, y), Offset(right, y), 1f, pathEffect = dash)
                        drawRect(colors.Ink, Offset(tagLeft, y - lineHeight / 2), Size(tagWidth, lineHeight))
                        text(value, tagLeft + labelPadding, y - labelHeight / 2, colors.Surface)
                    }
                    hoveredButton?.let { button ->
                        val tip = textMeasurer.measure(button.cell.hint, labelStyle.copy(color = colors.Surface))
                        val box = Size(tip.size.width + labelPadding * 2, tip.size.height + labelPadding * 2)
                        val tipLeft = (button.bounds.center.x - box.width / 2).coerceIn(left, maxOf(left, right - box.width))
                        val above = button.bounds.top - box.height - labelPadding
                        val tipTop = if (above >= top) above else button.bounds.bottom + labelPadding
                        drawRoundRect(colors.Ink, Offset(tipLeft, tipTop), box, CornerRadius(labelPadding, labelPadding))
                        drawText(tip, topLeft = Offset(tipLeft + labelPadding, tipTop + labelPadding))
                    }
                }
                if (actions.inlineDraftQuantity) orderTags.firstOrNull { it.line.preview && it.cells.any { cell -> cell.cell.action == LineAction.EditDraftQuantity } }
                    ?.cells?.firstOrNull { it.cell.action == LineAction.EditDraftQuantity }?.let { placed ->
                        Box(Modifier.offset(x = with(density) { placed.bounds.left.toDp() }, y = with(density) { placed.bounds.bottom.toDp() })) {
                            DropdownMenu(expanded = quantityMenu, onDismissRequest = { quantityMenu = false }) {
                                var value by remember(accountKey, instrument.symbol, quantityMenu) { mutableStateOf(actions.draftQuantity) }
                                fun valid(text: String): Boolean {
                                    val n = instrument.parseQuantity(text) ?: return false
                                    return n > 0 && n in instrument.minLotsHundredths..instrument.maxLotsHundredths && instrument.lotStepHundredths > 0 && n % instrument.lotStepHundredths == 0L
                                }
                                fun update(text: String) { value = text; if (valid(text)) actions.onDraftQuantity(text) }
                                fun step(up: Boolean) {
                                    val current = instrument.parseQuantity(value)?.takeIf { valid(value) } ?: instrument.minLotsHundredths
                                    val next = if (up) (current + instrument.lotStepHundredths).coerceAtMost(instrument.maxLotsHundredths)
                                        else (current - instrument.lotStepHundredths).coerceAtLeast(instrument.minLotsHundredths)
                                    update(instrument.formatQuantity(next))
                                }
                                val stepQuantity by rememberUpdatedState<(Boolean) -> Unit>(::step)
                                Column(Modifier.width(220.dp).padding(12.dp).pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type != PointerEventType.Scroll) continue
                                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                            if (delta != 0f) { stepQuantity(delta < 0f); event.changes.forEach { it.consume() } }
                                        }
                                    }
                                }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("订单大小", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        IconButton(onClick = { quantityMenu = false }, modifier = Modifier.size(28.dp)) {
                                            Icon(AppIcons.Close, contentDescription = "关闭数量编辑", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(value = value, onValueChange = { text ->
                                            val allowed = text.length <= 12 && text.all { it in '0'..'9' || (instrument.quantityDecimals > 0 && it == '.') }
                                            if (allowed) update(text)
                                        }, label = { Text("数量（${instrument.quantityUnit}）") }, singleLine = true, isError = !valid(value),
                                            keyboardOptions = KeyboardOptions(keyboardType = if (instrument.quantityDecimals == 0) KeyboardType.Number else KeyboardType.Decimal),
                                            textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("chart-draft-quantity"))
                                        Column { TextButton(onClick = { step(true) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp).testTag("chart-quantity-up")) { Text("+") }
                                            TextButton(onClick = { step(false) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp).testTag("chart-quantity-down")) { Text("−") } }
                                    }
                                    if (!valid(value)) Text("请输入大于零且符合合约步长的数量", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                Box(Modifier.align(if (mobilePlatform) {
                    if (axis.left) Alignment.TopStart else Alignment.TopEnd
                } else {
                    if (axis.left) Alignment.BottomStart else Alignment.BottomEnd
                }).padding(start = if (axis.left) 18.dp else 0.dp, end = if (axis.left) 0.dp else 18.dp,
                    bottom = if (mobilePlatform) 0.dp else 2.dp)) {
                    IconButton(onClick = { axisPage = 0; axisMenu = true }, modifier = Modifier.size(48.dp)
                        .background(colors.Surface.copy(alpha = .94f), RoundedCornerShape(10.dp)).testTag("price-axis-menu-button")) {
                        Icon(AppIcons.Settings, contentDescription = "价格轴设置", modifier = Modifier.size(if (mobilePlatform) 24.dp else 25.dp), tint = colors.Accent)
                    }
                    PriceAxisMenu(axisMenu, axisPage, axis, manualScale == null && axis.fitAllBars, logarithmic || visible.all { it.low > 0 },
                        onPage = { axisPage = it }, onDismiss = { axisMenu = false },
                        onChange = { chrome.onPriceAxis(it) }, onAuto = { chrome.onPriceAxis(axis.copy(fitAllBars = true)); manualScale = null; ratio[0] = Double.NaN; axisMenu = false })
                }
                crosshairY?.takeIf { axis.showPlus && it in top..plotBottom && plotMenu == null }?.let { y ->
                    val axisX = if (axis.left) left - with(density) { 24.dp.toPx() } else right
                    TextButton(onClick = {
                        val price = currentScale.price(y, instrument.tickSize)
                        val available = plotOrders(price, currentQuote, currentActions.entryKinds)
                        if (available.isNotEmpty() || currentDrawingLayer != null) plotMenu = PlotMenu(Offset(axisX, y), price, available)
                    }, modifier = Modifier.offset(x = with(density) { axisX.toDp() }, y = with(density) { y.toDp() } - 12.dp)
                        .size(24.dp).testTag("price-axis-plus"), contentPadding = PaddingValues(0.dp)) { Text("+") }
                }
                lineMenu?.let { menu -> LineContextMenu(menu, { lineMenu = null }) { action -> actions.onLineAction(action) } }
                plotMenu?.let { menu ->
                    PlotContextMenu(menu, instrument, actions.entryLots, { plotMenu = null },
                        onLine = drawingLayer?.let { layer -> { layer.addHorizontalLine(menu.at, menu.price) } },
                        // A market order takes no price: it fills at the quote, as the trading keys' does.
                        onOrder = { order -> actions.onEntryOrder(order.side, order.kind, menu.price.takeUnless { order.kind == OrderKind.MARKET }) },
                        stopRequiredForSizing = actions.stopRequiredForSizing, attached = actions.attachedProtection,
                        desktopActions = if (!menu.desktop) null else PlotMenuActions(
                            reset = { viewport = ChartViewport(); manualScale = null; ratio[0] = Double.NaN; selectedTrade = null; selectedExecution = null },
                            copyPrice = { clipboard.setText(AnnotatedString(instrument.formatPrice(menu.price))) },
                            pasteDrawing = chrome.drawings?.takeIf { it.canPaste }?.let { drawings -> { drawings.paste() } },
                            openOrder = chrome.onOpenOrderAt?.let { open -> { open(menu.price) } },
                            toggleTimeLock = chrome.onLockedCrosshairTime?.takeIf { menu.timeMs != null || chrome.lockedCrosshairTime != null }?.let { change ->
                                { change(if (chrome.lockedCrosshairTime == null) menu.timeMs else null) }
                            }, timeLocked = chrome.lockedCrosshairTime != null,
                            showTable = { showData = true }, showObjects = chrome.drawings?.let { { showObjectTree = true } },
                            showTemplates = chrome.templates?.let { { showTemplates = true } },
                            removeIndicators = chrome.onIndicatorChanges?.let { change -> { change(emptyList()) } }, indicatorCount = (chrome.allIndicators ?: indicators).size,
                            removeDrawings = chrome.drawings?.let { drawings -> { drawings.removeAllDrawings() } },
                            drawingCount = chrome.drawings?.drawingCount() ?: 0,
                            keyboardTrading = chrome.keyboardTrading,
                            settings = chrome.onOpenSettings,
                        ))
                }
                val bar = visible.getOrNull(readoutIndex)
                val previous = if (bar == null) null else candles.getOrNull(range.first + readoutIndex - 1)
                if (legendShown) Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = 96.dp)
                    .onPlaced { legendArea = Rect(it.positionInParent(), it.size.toSize()) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!compact && !chrome.sharedToolbar) toolbar(Modifier)
                    ChartLegend(instrument, timeframe, bar, previous, quote, connected, collapsed, { collapsed = !collapsed; chrome.onLegendCollapsed(collapsed) }, onSide, readout, compact = compact,
                        keysHint = actions.tradingKeys?.let {
                            tradingKeysHint(it, chartFocused, instrument, actions.entryLots, actions.instantOrders, actions.stopRequiredForSizing, actions.attachedProtection)
                        }, keysLive = chartFocused, zoneId = chrome.chartTimeZone.zoneId, zoneLabel = chrome.chartTimeZoneLabel ?: chrome.chartTimeZone.label)
                    if (!collapsed && overlays.isNotEmpty()) IndicatorLegend(indicatorReadouts(overlays, range.first + readoutIndex, instrument))
                }
                val navigationBottom = 38.dp + with(density) { (bottom - plotBottom).toDp() }
                if (chrome.showNavigation) ChartNavigation(
                    // The buttons use the same newest-bar anchor as the mouse wheel, so
                    // zooming out fills the chart instead of creating empty future space.
                    onZoomIn = { viewport = viewport.zoomAt(-2.5f, .5f, candles.size, maxVisibleCandleCount(plotWidth)) },
                    onZoomOut = { viewport = viewport.zoomAt(2.5f, .5f, candles.size, maxVisibleCandleCount(plotWidth)) },
                    onEarlier = { viewport = viewport.copy(offset = viewport.offset + viewport.count / 2).bounded(candles.size) },
                    onLater = { viewport = viewport.copy(offset = viewport.offset - viewport.count / 2).bounded(candles.size) },
                    onReset = { viewport = ChartViewport(); manualScale = null; selectedTrade = null; selectedExecution = null },
                    locked = locked.takeIf { chrome.onLocked != null && !compact },
                    onLocked = { chrome.onLocked?.invoke(!locked) },
                    // On phones the small control sits bottom-left, away from the newest bars at the right edge.
                    modifier = if (compact) Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = navigationBottom)
                        else Modifier.align(Alignment.BottomCenter).padding(bottom = navigationBottom),
                    compact = compact,
                )
                paneLayout.takeUnless { chrome.drawings?.lockIndicators == true }.orEmpty().forEachIndexed { index, pane ->
                    key(pane.id) {
                        val chartHeight = bottom - top
                        val minimum = minimumPaneFraction(paneLayout.size, chartHeight, minimumPaneHeight)
                        val maximum = (MAX_PANE_AREA_FRACTION - paneLayout.filter { it.id != pane.id }.sumOf { (it.height / chartHeight).toDouble() }.toFloat()).coerceAtLeast(minimum)
                        IndicatorPaneResizeHandle(pane.id, panes[index].config.kind.label, pane.height / chartHeight,
                            minimum, maximum, chartHeight,
                            onChange = { fraction ->
                                crosshair = null
                                paneFractions = resizeIndicatorPane(paneLayout, pane.id, fraction, chartHeight, paneFractions, minimumPaneHeight)
                            },
                            onFinish = { onIndicatorPaneFractions(paneFractions) },
                            onReset = {
                                paneFractions = resizeIndicatorPane(paneLayout, pane.id, minOf(.2f, .5f / panes.size), chartHeight, paneFractions, minimumPaneHeight)
                                onIndicatorPaneFractions(paneFractions)
                            },
                            modifier = Modifier.offset(x = with(density) { left.toDp() }, y = with(density) { pane.top.toDp() } - 6.dp)
                                .width(with(density) { plotWidth.toDp() }),
                        )
                    }
                }
                drawingLayer?.let { DrawingChartOverlays(it, compact, chrome.showDrawingFavorites) }
                if (showObjectTree && chrome.drawings != null) ChartObjectsDialog(chrome.drawings, chrome.allIndicators ?: indicators,
                    objectLayers, chrome.onIndicatorChanges, { showObjectTree = false })
                if (showTemplates && chrome.templates != null && chrome.onApplyTemplate != null) ChartTemplatesDialog(chrome.templates,
                    chrome.allIndicators ?: indicators, axis, { template ->
                        chrome.onApplyTemplate.invoke(template)
                        if (template.axis.fitAllBars) { manualScale = null; ratio[0] = Double.NaN }
                    }, { showTemplates = false })
                if (showChartSettings) ChartSettingsDialog(axis, showHistory, chrome.onPriceAxis,
                    { visible -> setHistory(visible) },
                    { chrome.onPriceAxis(axis.copy(fitAllBars = true)); manualScale = null; ratio[0] = Double.NaN }, onIndicators,
                    { showChartSettings = false })
            }
            selectedPosition?.protectionNote?.let { note ->
                Text(note, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.Muted)
            }
            selectedTrade?.let { trade ->
                Text("选中历史  #${trade.id} · ${if (trade.directionKnown) trade.side.label() else "买卖配对"} ${instrument.formatQuantity(trade.lotsHundredths)} ${instrument.quantityUnit} · ${trade.netPnlMicros?.let { money(it, true) } ?: "毛 " + (trade.grossPnlMicros?.let { money(it, true) } ?: "—") + "（费用未知）"} USD · ${trade.reason}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
            selectedExecution?.let { fill ->
                Text("选中成交 #${fill.id} · ${chartDateTime(fill.timeMs, zoneId = chrome.chartTimeZone.zoneId)} ${chrome.chartTimeZoneLabel ?: chrome.chartTimeZone.label} · ${fill.side.label()} ${instrument.formatQuantity(fill.lotsHundredths)} ${instrument.quantityUnit} · ${instrument.formatPrice(fill.price)}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("selected-execution"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * How [TradingChart] arranges its controls. [legendCollapsed] folds the legend with its indicator list, and
 * [onLegendCollapsed] receives each toggle so the screen can remember it. [compact] (phones) pins one toolbar row above
 * the plot, closed by [toolbarEnd], instead of the floating toolbar, and the legend stays hidden while [ordering] (the
 * order panel is open). Grouped in one parameter because at 32 parameters D8 emitted a TradingChart that ART's verifier
 * rejected (VerifyError on Android); [ChartOrderActions] groups the order-line callbacks for the same reason.
 */
@androidx.compose.runtime.Stable
class ChartLayerState(initialHistory: Boolean = true) {
    var showPositions by mutableStateOf(true)
    var showOrders by mutableStateOf(true)
    var showHistory by mutableStateOf(initialHistory)
    var showData by mutableStateOf(false)
}

class ChartChrome(
    val compact: Boolean = false,
    val legendCollapsed: Boolean = false,
    val onLegendCollapsed: (Boolean) -> Unit = {},
    val ordering: Boolean = false,
    val toolbarEnd: @Composable () -> Unit = {},
    /** The left toolbar's drawings for this chart; null leaves the chart without them. */
    val drawings: ChartDrawings? = null,
    /** A split workspace shows one shared favorite bar on the currently selected chart. */
    val showDrawingFavorites: Boolean = true,
    /** Where the chart keeps how it showed each series, so it opens again as it was left; null starts every series afresh. */
    val views: ChartViewMemory? = null,
    /** Locked, the mouse wheel moves the chart through time instead of zooming it. */
    val locked: Boolean = false,
    /** Receives the lock button's choice; null leaves the button out (phones, which have no wheel). */
    val onLocked: ((Boolean) -> Unit)? = null,
    val showHistory: Boolean? = null,
    val onShowHistory: (Boolean) -> Unit = {},
    val priceAxis: PriceAxisSettings = PriceAxisSettings(),
    val onPriceAxis: (PriceAxisSettings) -> Unit = {},
    val lockedCrosshairTime: Long? = null,
    val onLockedCrosshairTime: ((Long?) -> Unit)? = null,
    val templates: com.unitrading.app.ChartTemplateController? = null,
    val onApplyTemplate: ((com.unitrading.app.ChartTemplate) -> Unit)? = null,
    val onIndicatorChanges: ((List<IndicatorConfig>) -> Unit)? = null,
    val allIndicators: List<IndicatorConfig>? = null,
    val onOpenOrderAt: ((Long) -> Unit)? = null,
    val keyboardTrading: Boolean = false,
    val domVisible: Boolean = false,
    val onDomToggle: (() -> Unit)? = null,
    val sharedToolbar: Boolean = false,
    val layerState: ChartLayerState? = null,
    /** Tablets can use the desktop axis placement while retaining touch input. */
    val desktopAxisStyle: Boolean? = null,
    val tabletCompactAxis: Boolean = false,
    val showNavigation: Boolean = true,
    val showWatermark: Boolean = false,
    val sessionNotice: String? = null,
    val regularSession: Boolean = false,
    val chartTimeZone: com.unitrading.app.ChartTimeZone = com.unitrading.app.ChartTimeZone.SHANGHAI,
    val chartTimeZoneLabel: String? = null,
    val onOpenSettings: (() -> Unit)? = null,
    val objectLayers: ChartObjectLayers? = null,
)

/** `2026-11-10 09:30` in UTC, or `2026-11-10 09:30:05` with [seconds]. */
fun dateTime(timeMs: Long, seconds: Boolean = false): String =
    Instant.fromEpochMilliseconds(timeMs).toString().take(if (seconds) 19 else 16).replace('T', ' ')


/** Format an absolute instant as Beijing local time; stored timestamps remain UTC instants. */
fun beijingDateTime(timeMs: Long, seconds: Boolean = false): String = chartDateTime(timeMs, seconds)

fun chartDateTime(timeMs: Long, seconds: Boolean = false, zoneId: String = "Asia/Shanghai"): String {
    val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.of(zoneId))
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${local.date} ${two(local.hour)}:${two(local.minute)}" + if (seconds) ":${two(local.second)}" else ""
}

/** Daily-or-longer broker bars carry a trading date rather than an intraday instant. */
fun chartBarDateTime(timeMs: Long, timeframe: Timeframe, zoneId: String = "Asia/Shanghai"): String = when {
    timeframe.seconds >= Timeframe.DAY -> dateTime(timeMs).take(10)
    else -> chartDateTime(timeMs, seconds = timeframe.isSeconds, zoneId = zoneId)
}

/** Parse a chart drawing coordinate typed as Beijing local time into the underlying UTC instant. */
fun parseBeijingChartTime(text: String): Long? = parseChartTime(text, "Asia/Shanghai")

fun parseChartTime(text: String, zoneId: String): Long? = runCatching {
    val value = text.trim()
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    val local = LocalDateTime.parse(value.replace(' ', 'T') + ":00")
    val zone = TimeZone.of(zoneId)
    val instant = local.toInstant(zone)
    require(instant.toLocalDateTime(zone) == local)
    val ms = instant.toEpochMilliseconds()
    require(listOf(-7_200_000L, -3_600_000L, -1_800_000L, 1_800_000L, 3_600_000L, 7_200_000L).none { Instant.fromEpochMilliseconds(ms + it).toLocalDateTime(zone) == local })
    ms
}.getOrNull()

/** Chart crosshair: Beijing time intraday, trading date for daily and longer bars. */
fun crosshairTimeLabel(timeMs: Long, timeframe: Timeframe, zoneId: String = "Asia/Shanghai"): String = chartBarDateTime(timeMs, timeframe, zoneId)
