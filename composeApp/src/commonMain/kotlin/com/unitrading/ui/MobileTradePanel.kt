package com.unitrading.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.broker.*
import com.unitrading.chart.Chevron

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
    Column(modifier.fillMaxWidth().background(colors.Surface).testTag("trade-bar")) {
        DividerLine()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onPositions).testTag("panel-positions"), verticalArrangement = Arrangement.Center) {
                Text("持仓 ${account.positions.size} · 浮动", fontSize = 11.sp, color = colors.Muted, maxLines = 1)
                val known = account.equityKnown && account.balanceKnown
                Text(if (known) money(account.floatingPnlMicros, true) else "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink, maxLines = 1)
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onPositions).testTag("panel-daily-pnl"), verticalArrangement = Arrangement.Center) {
                Text("今日盈亏 · 券商", fontSize = 11.sp, color = colors.Muted, maxLines = 1)
                Text(account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.pnl(account.dailyPnl?.totalMicros), maxLines = 1)
            }
            Button(onOpen, Modifier.heightIn(min = 48.dp).testTag("open-order"), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 24.dp)) {
                Text("下单", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Chevron(down = false, LocalContentColor.current)
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
            sheetContainerColor = colors.Surface, sheetContentColor = colors.Ink, containerColor = androidx.compose.ui.graphics.Color.Transparent,
            sheetDragHandle = {
                Box(Modifier.fillMaxWidth().height(16.dp).testTag("order-sheet-handle"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(24.dp, 3.dp).background(colors.Muted.copy(alpha = .45f), RoundedCornerShape(2.dp)))
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
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 4.dp)
                        .then(if (compact) Modifier.testTag("minimized-order") else Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onStateChange(MobileOrderPanelState.EDITING) }
                            .testTag("restore-order").semantics { contentDescription = "展开下单面板" }, verticalArrangement = Arrangement.Center) {
                            Text(instrument.symbol, color = colors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (compact) "${draft.quantity} ${instrument.quantityUnit} · ${draft.side.label()}" else
                                quote?.let { "${instrument.formatPrice(it.bid)} / ${instrument.formatPrice(it.ask)}" } ?: "—",
                                fontSize = 10.sp, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                            Surface(Modifier.size(30.dp, 26.dp), shape = RoundedCornerShape(6.dp),
                                                color = if (checked) protectionColor.copy(alpha = .12f) else colors.Raised.copy(alpha = .5f),
                                                border = BorderStroke(1.dp, if (checked) protectionColor.copy(alpha = .45f) else colors.Border.copy(alpha = .65f))) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(if (stop) "SL" else "TP", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                                        color = (if (checked) protectionColor else colors.Muted).copy(alpha = if (busy) .4f else 1f))
                                                }
                                            }
                                        }
                                    }
                                    val submitEnabled = canSubmit && !busy && panelState != MobileOrderPanelState.CLOSED
                                    val submitColor = if (submitEnabled) colors.Accent else colors.Muted.copy(alpha = .4f)
                                    IconButton({ focus.clearFocus(); onSubmit() }, Modifier.size(48.dp)
                                        .testTag("compact-submit-order").semantics { contentDescription = if (skipConfirmation) "立即下单" else "检查并下单" },
                                        enabled = submitEnabled) {
                                        Canvas(Modifier.size(18.dp)) {
                                            val stroke = 1.6.dp.toPx()
                                            val tip = Offset(size.width * .8f, size.height * .5f)
                                            drawLine(submitColor, Offset(size.width * .2f, size.height * .5f), tip, stroke, StrokeCap.Round)
                                            drawLine(submitColor, Offset(size.width * .5f, size.height * .2f), tip, stroke, StrokeCap.Round)
                                            drawLine(submitColor, tip, Offset(size.width * .5f, size.height * .8f), stroke, StrokeCap.Round)
                                        }
                                    }
                                } else PanelIconButton(close = false, enabled = panelState != MobileOrderPanelState.CLOSED,
                                    onClick = { onStateChange(MobileOrderPanelState.MINIMIZED) })
                            }
                        }
                        PanelIconButton(close = true, enabled = !busy, onClick = onClose)
                    }
                    MobileOrderTypes(instrument, quote, capabilities, draft, onDraft, !busy)
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

/** Type and direction stay available in the peek region, matching the reference video. */
@Composable
private fun MobileOrderTypes(instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).selectableGroup()
        .testTag("order-type-strip")) {
        OrderKind.entries.filter { it in capabilities.orderKinds }.forEach { kind ->
            val sides = if (kind == OrderKind.MARKET) listOf<Side?>(null) else listOf(Side.BUY, Side.SELL)
            sides.forEach { side ->
                val selected = draft.kind == kind && (side == null || draft.side == side)
                val label = if (side == null) "市场执行" else side.label() + if (kind == OrderKind.LIMIT) "限价" else "止损"
                Box(Modifier.fillMaxHeight().selectable(selected, enabled, Role.Tab) {
                    val sided = if (side == null) draft else draft.withSide(side, instrument, quote)
                    val next = if (side != null && side != draft.side && kind == draft.kind)
                        sided.copy(kind = OrderKind.MARKET).withKind(instrument, quote, kind)
                    else sided.withKind(instrument, quote, kind)
                    onDraft(next)
                }.testTag(if (side == null || side == Side.BUY) "order-kind-${kind.name}" else "order-kind-${kind.name}-SELL")
                    .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    val color = if ((side ?: draft.side) == Side.BUY) TradingColors.Up else TradingColors.Down
                    Text(label, fontSize = 12.sp, color = if (selected) color else TradingColors.Muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun PanelIconButton(close: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val color = TradingColors.Muted.copy(alpha = if (enabled) 1f else .4f)
    IconButton(onClick, Modifier.size(48.dp).testTag(if (close) "close-order" else "minimize-order")
        .semantics { contentDescription = if (close) "关闭并放弃本次下单" else "最小化下单面板" }, enabled = enabled) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.6.dp.toPx()
            if (close) {
                drawLine(color, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), stroke, StrokeCap.Round)
            } else drawLine(color, Offset(size.width * .15f, size.height * .65f), Offset(size.width * .85f, size.height * .65f), stroke, StrokeCap.Round)
        }
    }
}
