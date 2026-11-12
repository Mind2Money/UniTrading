package com.unitrading.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.app.AccountBrackets
import com.unitrading.broker.*

internal enum class MobileOrderPanelState { CLOSED, EDITING, MINIMIZED }

/** Per-symbol session retained while browsing other charts within the same account. */
internal class MobileOrderSession(instrument: Instrument) {
    val draft = mutableStateOf(OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10"))
    val panelState = mutableStateOf(MobileOrderPanelState.CLOSED)
}

/** A single entry point leaves the chart usable until an order is started. */
@Composable
internal fun MobileTradeBar(
    account: AccountSnapshot,
    onOpen: () -> Unit,
    onPositions: () -> Unit,
    modifier: Modifier = Modifier, dense: Boolean = false,
) {
    val colors = TradingColors
    val known = account.equityKnown && account.balanceKnown
    Surface(modifier.fillMaxWidth().testTag("trade-bar"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = if (dense) 0.dp else 10.dp, bottom = if (dense) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val metric = Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onPositions)
            Metric("持仓 ${account.positions.size} · 浮动", if (known) money(account.floatingPnlMicros, true) else "—",
                metric.testTag("panel-positions").padding(horizontal = 8.dp, vertical = if (dense) 0.dp else 6.dp), if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink)
            Metric("今日盈亏 · 券商", account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—",
                metric.testTag("panel-daily-pnl").padding(horizontal = 8.dp, vertical = if (dense) 0.dp else 6.dp), colors.pnl(account.dailyPnl?.totalMicros))
            Button(onOpen, Modifier.heightIn(min = 48.dp).testTag("open-order"), colors = brandButtonColors(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(AppIcons.Add, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("下单")
            }
        }
    }
}

/**
 * The sheet order page. Keep the official sheet mounted so every open/close moves through its native Hidden anchor.
 * [accountName] takes the quote's place under the symbol while the sheet is expanded, where the side buttons show the
 * prices and the account bar above the chart is hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileOrderSheet(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean,
    modifier: Modifier = Modifier, busy: Boolean = false, skipConfirmation: Boolean = false,
    onHidden: () -> Unit = {},
    brackets: AccountBrackets = AccountBrackets(), calculatedLots: Long? = null, accountName: String? = null,
    reduction: Boolean = false,
    content: @Composable ColumnScope.() -> Unit, body: @Composable () -> Unit,
) {
    // The chart always occupies the same composition slot, including when the sheet is closed.
    Box(modifier) {
        body()
        MobileOrderSheetSurface(
            instrument, quote, capabilities, draft, onDraft, panelState, onStateChange, onClose, onSubmit, canSubmit,
            Modifier.matchParentSize(), busy, skipConfirmation, onHidden, brackets, calculatedLots, accountName, reduction, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileOrderSheetSurface(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean, modifier: Modifier, busy: Boolean, skipConfirmation: Boolean,
    onHidden: () -> Unit, brackets: AccountBrackets, calculatedLots: Long?, accountName: String?, reduction: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TradingColors
    val focus = LocalFocusManager.current
    val latestState by rememberUpdatedState(panelState)
    val latestChange by rememberUpdatedState(onStateChange)
    val latestHidden by rememberUpdatedState(onHidden)
    var applyingRequest by remember { mutableStateOf(true) }
    var minimizeFlingRequest by remember { mutableIntStateOf(0) }
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden, skipHiddenState = false,
        confirmValueChange = remember { { value ->
            val allowed = value != SheetValue.Hidden || latestState == MobileOrderPanelState.CLOSED
            if (!allowed) {
                // A fling past the peek must settle at the peek. A veto alone springs back up.
                applyingRequest = true
                minimizeFlingRequest++
                latestChange(MobileOrderPanelState.MINIMIZED)
            }
            allowed
        } },
    )
    LaunchedEffect(panelState, minimizeFlingRequest) {
        applyingRequest = true
        try {
            if (panelState != MobileOrderPanelState.EDITING) focus.clearFocus()
            when (panelState) {
                MobileOrderPanelState.CLOSED -> {
                    sheetState.hide()
                    if (latestState == MobileOrderPanelState.CLOSED && !sheetState.isVisible) latestHidden()
                }
                MobileOrderPanelState.MINIMIZED -> sheetState.partialExpand()
                MobileOrderPanelState.EDITING -> sheetState.expand()
            }
        } finally { applyingRequest = false }
    }
    LaunchedEffect(sheetState) {
        snapshotFlow { Triple(sheetState.currentValue, sheetState.targetValue, applyingRequest) }
            .collect { (current, target, applying) ->
                if (!applying && current != SheetValue.Hidden && latestState != MobileOrderPanelState.CLOSED &&
                    current == target && current != latestState.sheetValue) {
                    if (current != SheetValue.Expanded) focus.clearFocus()
                    latestChange(current.panelValue)
                }
            }
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = panelState != MobileOrderPanelState.CLOSED && !keyboardOpen,
        onBackCompleted = {
            if (panelState == MobileOrderPanelState.EDITING) onStateChange(MobileOrderPanelState.MINIMIZED)
            else if (!busy) onClose()
        })
    BoxWithConstraints(modifier.imePadding().clipToBounds()
        .then(if (!sheetState.isVisible && !sheetState.isAnimationRunning && panelState == MobileOrderPanelState.CLOSED) Modifier.clearAndSetSemantics { } else Modifier)) {
        val expandedHeight = if (keyboardOpen || maxHeight < 550.dp) maxHeight else maxHeight * .56f
        val compact = sheetState.currentValue == SheetValue.PartiallyExpanded && panelState != MobileOrderPanelState.CLOSED
        BottomSheetScaffold(
            scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState),
            sheetPeekHeight = MobileOrderPeekHeight,
            sheetSwipeEnabled = panelState != MobileOrderPanelState.CLOSED,
            sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, sheetContentColor = colors.Ink, containerColor = Color.Transparent,
            sheetDragHandle = {
                Box(Modifier.fillMaxWidth().height(16.dp).testTag("order-sheet-handle"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).background(colors.Muted.copy(alpha = .4f), CircleShape))
                }
            },
            sheetContent = {
                Column(Modifier.fillMaxWidth().height((expandedHeight - 16.dp).coerceAtLeast(MobileOrderPeekHeight))
                    .testTag("order-sheet").semantics {
                        stateDescription = when (panelState) {
                            MobileOrderPanelState.CLOSED -> "已关闭"
                            MobileOrderPanelState.MINIMIZED -> "已最小化"
                            MobileOrderPanelState.EDITING -> "已展开"
                        }
                    }) {
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp)
                        .then(if (compact) Modifier.testTag("minimized-order") else Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { onStateChange(MobileOrderPanelState.EDITING) }
                            .testTag("restore-order").semantics { contentDescription = "展开下单面板" }, verticalArrangement = Arrangement.Center) {
                            Text(instrument.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (compact) "${compactQuantity(instrument, draft, brackets, calculatedLots)} ${instrument.quantityUnit} · ${draft.side.label()}" else
                                accountName ?: quote?.let { "${instrument.formatPrice(it.bid)} / ${instrument.formatPrice(it.ask)}" } ?: "—",
                                style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // A fixed action slot avoids moving/truncating the symbol when controls change.
                        Crossfade(compact, Modifier.width(144.dp).height(48.dp), animationSpec = tween(120), label = "order-header") { collapsed ->
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (collapsed) CompactOrderActions(instrument, quote, draft, onDraft, brackets, busy, skipConfirmation,
                                    canSubmit && !busy && panelState != MobileOrderPanelState.CLOSED, onSubmit, reduction)
                                else ActionIcon(AppIcons.ExpandMore, "最小化下单面板", "minimize-order", enabled = panelState != MobileOrderPanelState.CLOSED) {
                                    onStateChange(MobileOrderPanelState.MINIMIZED)
                                }
                            }
                        }
                        ActionIcon(AppIcons.Close, "关闭并放弃本次下单", "close-order", enabled = !busy, onClick = onClose)
                    }
                    MobileOrderTypes(instrument, quote, capabilities, draft, onDraft, !busy, showSide = compact)
                    // Keep scroll/input state across minimization, but hide offscreen accessibility actions.
                    Column(Modifier.weight(1f).then(if (compact) Modifier.clearAndSetSemantics { } else Modifier.testTag("order-panel"))) {
                        if (sheetState.isVisible || sheetState.isAnimationRunning || panelState != MobileOrderPanelState.CLOSED) content()
                    }
                }
            },
        ) { /* Chart is composed once by MobileOrderSheet, beneath this transparent scaffold. */ }
    }
}

/** The sheet's peek: its drag handle, the summary row and the order type strip. */
internal val MobileOrderPeekHeight = 112.dp
/** The minimized dock: the same two rows under a divider, with no handle. */
internal val MobileOrderDockPeekHeight = 97.dp
internal val MobileTradeBarHeight = 68.dp

@OptIn(ExperimentalMaterial3Api::class)
private val MobileOrderPanelState.sheetValue: SheetValue get() = when (this) {
    MobileOrderPanelState.CLOSED -> SheetValue.Hidden
    MobileOrderPanelState.MINIMIZED -> SheetValue.PartiallyExpanded
    MobileOrderPanelState.EDITING -> SheetValue.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
private val SheetValue.panelValue: MobileOrderPanelState get() = when (this) {
    SheetValue.Hidden -> MobileOrderPanelState.CLOSED
    SheetValue.PartiallyExpanded -> MobileOrderPanelState.MINIMIZED
    SheetValue.Expanded -> MobileOrderPanelState.EDITING
}

/** The docked order page: measure the chart and order controls into disjoint rectangles without remounting the chart on rotation. */
@Composable
internal fun MobileOrderDockLayout(
    modifier: Modifier,
    editing: Boolean,
    landscape: Boolean,
    keyboardOpen: Boolean,
    advanced: Boolean,
    bottomHeight: Dp,
    chart: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    Layout(content = {
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { chart() }
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { dock() }
    }, modifier = modifier) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (editing && landscape) {
            val dockWidth = (width * .42f).toInt().coerceIn(minOf(280.dp.roundToPx(), width / 2), width / 2)
            val chartWidth = width - dockWidth
            val plot = children[0].measure(Constraints.fixed(chartWidth, height))
            val panel = children[1].measure(Constraints.fixed(dockWidth, height))
            layout(width, height) {
                plot.placeRelative(0, 0)
                panel.placeRelative(chartWidth, 0)
            }
        } else {
            val panelHeight = if (editing) {
                val fraction = when {
                    keyboardOpen -> .75f
                    advanced -> .56f
                    else -> .47f
                }
                val minimum = if (keyboardOpen) 188.dp else 276.dp
                val chartReserve = if (keyboardOpen) minOf(72.dp.roundToPx(), height / 5) else minOf(155.dp.roundToPx(), height / 2)
                val target = (height * fraction).toInt().coerceAtLeast(minimum.roundToPx())
                // Folded, the dock is no taller than its side buttons, quantity, SL / TP switches and "更多设置" need.
                val preferredHeight = if (!keyboardOpen && !advanced) target.coerceAtMost(376.dp.roundToPx()) else target
                preferredHeight.coerceAtMost(height - chartReserve)
            } else bottomHeight.roundToPx().coerceAtMost(height)
            val chartHeight = height - panelHeight
            val plot = children[0].measure(Constraints.fixed(width, chartHeight))
            val panel = children[1].measure(Constraints.fixed(width, panelHeight))
            layout(width, height) {
                plot.placeRelative(0, 0)
                panel.placeRelative(0, chartHeight)
            }
        }
    }
}

@Composable
internal fun MobileOrderDock(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities, accountName: String,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean, busy: Boolean,
    skipConfirmation: Boolean, keyboardOpen: Boolean,
    brackets: AccountBrackets = AccountBrackets(),
    calculatedLots: Long? = null,
    reduction: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TradingColors
    val focus = LocalFocusManager.current
    val collapsed = panelState == MobileOrderPanelState.MINIMIZED
    fun setMinimized() {
        focus.clearFocus()
        onStateChange(MobileOrderPanelState.MINIMIZED)
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !keyboardOpen,
        onBackCompleted = { if (!collapsed) setMinimized() else if (!busy) onClose() })
    Surface(Modifier.fillMaxSize().testTag("order-dock").semantics {
        stateDescription = if (collapsed) "已最小化" else "已展开"
    }, color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = colors.Ink) {
        Column(Modifier.fillMaxSize()) {
            DividerLine()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 4.dp)
                .then(if (collapsed) Modifier.testTag("minimized-order") else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight()
                    .then(if (collapsed) Modifier.clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button) { onStateChange(MobileOrderPanelState.EDITING) }
                        .testTag("restore-order").semantics { contentDescription = "展开下单面板" }
                    else Modifier), verticalArrangement = Arrangement.Center) {
                    // The docked form has no line for the two codes, so the title names the chart's code with the contract
                    // that trades, or with why there is none yet.
                    Text(if (instrument.isContinuous) "${instrument.symbol} · ${instrument.executionLabel}" else instrument.symbol,
                        Modifier.testTag("order-dock-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (collapsed) "${compactQuantity(instrument, draft, brackets, calculatedLots)} ${instrument.quantityUnit} · ${draft.side.label()}" else accountName,
                        style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (collapsed) CompactOrderActions(instrument, quote, draft, onDraft, brackets, busy, skipConfirmation, canSubmit && !busy, onSubmit, reduction)
                else ActionIcon(AppIcons.ExpandMore, "最小化下单面板", "minimize-order", onClick = ::setMinimized)
                ActionIcon(AppIcons.Close, "关闭并放弃本次下单", "close-order", enabled = !busy, onClick = onClose)
            }
            MobileOrderTypes(instrument, quote, capabilities, draft, onDraft, !busy, showSide = collapsed)
            if (!collapsed) Column(Modifier.weight(1f).testTag("order-panel"), content = content)
        }
    }
}

/** The quantity a minimized ticket names: the draft's, or the one sized from the account's risk amount. */
private fun compactQuantity(instrument: Instrument, draft: OrderDraft, brackets: AccountBrackets, calculatedLots: Long?) =
    if (brackets.sizingRisk == null) draft.quantity else calculatedLots?.let(instrument::formatQuantity) ?: "—"

/**
 * What a minimized ticket keeps beside its summary, in the sheet's peek and in the dock alike: the stop loss and take
 * profit switches and the submit arrow. A protection that the account's cash brackets or loss-based sizing decide shows
 * what will be sent and cannot be switched here; on a [reduction], which goes without them, it shows as off.
 */
@Composable
private fun CompactOrderActions(
    instrument: Instrument, quote: Quote?, draft: OrderDraft, onDraft: (OrderDraft) -> Unit, brackets: AccountBrackets,
    busy: Boolean, skipConfirmation: Boolean, submitEnabled: Boolean, onSubmit: () -> Unit, reduction: Boolean = false,
) {
    val colors = TradingColors
    val focus = LocalFocusManager.current
    listOf(true, false).forEach { stop ->
        val requiredStop = stop && brackets.sizingRisk != null
        val managed = brackets.controls(stop) || requiredStop
        val checked = when {
            managed && reduction -> false
            requiredStop -> draft.stopLoss.isNotBlank()
            else -> managed || if (stop) draft.stopLossEnabled else draft.takeProfitEnabled
        }
        val protectionColor = if (stop) colors.Stop else colors.Target
        // Keep the native 48dp touch target; only the visual badge is compact.
        IconToggleButton(checked = checked,
            onCheckedChange = { onDraft(draft.withProtectionEnabled(instrument, quote, stop, it)) },
            enabled = !busy && !managed, modifier = Modifier.size(48.dp)
                .testTag(if (stop) "compact-sl" else "compact-tp")
                .semantics { contentDescription = if (stop) "止损 SL" else "止盈 TP" },
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = Color.Transparent, checkedContainerColor = Color.Transparent)) {
            Surface(Modifier.size(32.dp, 28.dp), shape = MaterialTheme.shapes.small,
                color = if (checked) protectionColor.copy(alpha = .18f) else Color.Transparent,
                border = BorderStroke(1.dp, if (checked) protectionColor else MaterialTheme.colorScheme.outline)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (stop) "SL" else "TP", style = MaterialTheme.typography.labelMedium,
                        color = (if (checked) protectionColor else colors.Muted).copy(alpha = if (busy) .4f else 1f))
                }
            }
        }
    }
    ActionIcon(AppIcons.Send, if (skipConfirmation) "立即下单" else "检查并下单", "compact-submit-order", enabled = submitEnabled,
        tint = if (submitEnabled) (if (draft.side == Side.BUY) colors.Up else colors.Down) else colors.Muted.copy(alpha = .4f)) {
        focus.clearFocus(); onSubmit()
    }
}

/**
 * Order kind, plus the side while minimized: the minimized ticket stays a complete one, and once expanded the side buttons
 * with their live prices take over the side.
 */
@Composable
private fun MobileOrderTypes(instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit, enabled: Boolean, showSide: Boolean) {
    val colors = TradingColors
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).testTag("order-type-strip"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderKindChoice(instrument, quote, capabilities, draft, onDraft, enabled, Modifier.weight(3f), height = 36.dp)
        if (showSide) SegmentedChoice(listOf(Side.SELL, Side.BUY), draft.side, { it.label() }, { "order-peek-side-${it.name}" }, { side ->
            // The entry belongs to the side it was placed for: a resting price is rebuilt for the other side.
            val sided = draft.withSide(side, instrument, quote)
            onDraft(if (side == draft.side || draft.kind == OrderKind.MARKET) sided else sided.copy(kind = OrderKind.MARKET).withKind(instrument, quote, draft.kind))
        }, Modifier.weight(2f), enabled, height = 36.dp, activeColor = { if (it == Side.BUY) colors.UpText else colors.DownText })
    }
}
