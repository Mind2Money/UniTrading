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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
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
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    val known = account.equityKnown && account.balanceKnown
    Surface(modifier.fillMaxWidth().testTag("trade-bar"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val metric = Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onPositions)
            Metric("持仓 ${account.positions.size} · 浮动", if (known) money(account.floatingPnlMicros, true) else "—",
                metric.testTag("panel-positions").padding(horizontal = 8.dp, vertical = 6.dp), if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink)
            Metric("今日盈亏 · 券商", account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—",
                metric.testTag("panel-daily-pnl").padding(horizontal = 8.dp, vertical = 6.dp), colors.pnl(account.dailyPnl?.totalMicros))
            Button(onOpen, Modifier.heightIn(min = 48.dp).testTag("open-order"), colors = brandButtonColors(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(AppIcons.Add, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("下单")
            }
        }
    }
}

/** Keep the official sheet mounted so every open/close moves through its native Hidden anchor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileOrderSheet(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean,
    modifier: Modifier = Modifier, busy: Boolean = false, skipConfirmation: Boolean = false,
    onHidden: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit, body: @Composable () -> Unit,
) {
    // The chart always occupies the same composition slot, including when the sheet is closed.
    Box(modifier) {
        body()
        MobileOrderSheetSurface(
            instrument, quote, capabilities, draft, onDraft, panelState, onStateChange, onClose, onSubmit, canSubmit,
            Modifier.matchParentSize(), busy, skipConfirmation, onHidden, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileOrderSheetSurface(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean, modifier: Modifier, busy: Boolean, skipConfirmation: Boolean,
    onHidden: () -> Unit, content: @Composable ColumnScope.() -> Unit,
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
                            Text(if (compact) "${draft.quantity} ${instrument.quantityUnit} · ${draft.side.label()}" else
                                quote?.let { "${instrument.formatPrice(it.bid)} / ${instrument.formatPrice(it.ask)}" } ?: "—",
                                style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // A fixed action slot avoids moving/truncating the symbol when controls change.
                        Crossfade(compact, Modifier.width(144.dp).height(48.dp), animationSpec = tween(120), label = "order-header") { collapsed ->
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (collapsed) {
                                    listOf(true, false).forEach { stop ->
                                        val checked = if (stop) draft.stopLossEnabled else draft.takeProfitEnabled
                                        val protectionColor = if (stop) colors.Stop else colors.Target
                                        // Keep the native 48dp touch target; only the visual badge is compact.
                                        IconToggleButton(checked = checked,
                                            onCheckedChange = { onDraft(draft.withProtectionEnabled(instrument, quote, stop, it)) },
                                            enabled = !busy, modifier = Modifier.size(48.dp)
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
                                    val submitEnabled = canSubmit && !busy && panelState != MobileOrderPanelState.CLOSED
                                    ActionIcon(AppIcons.Send, if (skipConfirmation) "立即下单" else "检查并下单", "compact-submit-order", enabled = submitEnabled,
                                        tint = if (submitEnabled) (if (draft.side == Side.BUY) colors.Up else colors.Down) else colors.Muted.copy(alpha = .4f)) {
                                        focus.clearFocus(); onSubmit()
                                    }
                                } else ActionIcon(AppIcons.ExpandMore, "最小化下单面板", "minimize-order", enabled = panelState != MobileOrderPanelState.CLOSED) {
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

internal val MobileOrderPeekHeight = 112.dp
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

/**
 * Order kind, plus the side while minimized: the sheet's peek stays a complete ticket, and once expanded the side buttons
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
