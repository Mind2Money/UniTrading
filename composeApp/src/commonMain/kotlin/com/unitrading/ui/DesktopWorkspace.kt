package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import com.unitrading.app.DesktopPanel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The chart with the trade list under it, then the order ticket and the accounts and market panel, as on TradingView.
 * The Material rail folds the surrounding panels and opens order flow in the chart area's right half.
 */
@Composable
internal fun DesktopWorkspace(
    controller: DesktopLayoutController,
    wide: Boolean,
    modifier: Modifier = Modifier,
    accounts: @Composable () -> Unit,
    market: @Composable (Modifier) -> Unit,
    chart: @Composable (Modifier) -> Unit,
    order: @Composable (Modifier, minimize: () -> Unit) -> Unit,
    terminal: @Composable (Modifier) -> Unit,
    terminalSummary: String = "",
    orderFlow: (@Composable (Modifier) -> Unit)? = null,
    orderFlowOpen: Boolean = false,
    onOrderFlowOpenChange: (Boolean) -> Unit = {},
) {
    val preferred by controller.preferences.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    fun save() { scope.launch { controller.save() } }
    fun setOpen(panel: DesktopPanel, open: Boolean) { controller.setOpen(panel, open); save() }
    val defaults = DesktopLayoutPreferences()
    Column(modifier.testTag("desktop-workspace")) {
        error?.let { message ->
            Row(Modifier.fillMaxWidth().background(TradingColors.Raised).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TradingColors.Loss)
                TextButton({ save() }, Modifier.testTag("retry-layout-save")) { Text("重试保存") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val flowOpen = orderFlow != null && orderFlowOpen
            val layout = desktopPanelGeometry(maxWidth.value, maxHeight.value, wide, preferred, flowOpen)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("desktop-chart-area")) {
                        val flowWidth = orderFlowPanelDimension(maxWidth.value, preferred.orderFlowFraction)
                        val splitWidth = (maxWidth.value - PANEL_DIVIDER).coerceAtLeast(0f)
                        Row(Modifier.fillMaxSize()) {
                            chart(Modifier.weight(1f).fillMaxHeight().testTag("desktop-chart"))
                            if (flowOpen) {
                                PanelResizeHandle("订单流宽度", "resize-order-flow", horizontal = true, dimension = flowWidth, direction = -1f,
                                    onChange = { if (splitWidth > 0f) controller.update(preferred.copy(orderFlowFraction = it / splitWidth)) },
                                    onFinish = ::save,
                                    onReset = { controller.update(preferred.copy(orderFlowFraction = defaults.orderFlowFraction)); save() })
                                Box(Modifier.width(flowWidth.value.dp).fillMaxHeight().testTag("desktop-order-flow")) {
                                    orderFlow?.invoke(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    if (preferred.terminalOpen) {
                        PanelResizeHandle("交易区高度", "resize-terminal", horizontal = false, dimension = layout.terminal, direction = -1f,
                            onChange = { controller.update(preferred.copy(terminalHeight = it)) }, onFinish = ::save,
                            onReset = { controller.update(preferred.copy(terminalHeight = defaults.terminalHeight)); save() })
                        Column(Modifier.fillMaxWidth().height(layout.terminal.value.dp).testTag("desktop-terminal")) {
                            TerminalBar(terminalSummary, open = true) { setOpen(DesktopPanel.TERMINAL, false) }
                            terminal(Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        DividerLine()
                        Column(Modifier.fillMaxWidth().testTag("desktop-terminal")) { TerminalBar(terminalSummary, open = false) { setOpen(DesktopPanel.TERMINAL, true) } }
                    }
                }
                if (wide && preferred.orderOpen) {
                    PanelResizeHandle("下单栏宽度", "resize-order", horizontal = true, dimension = layout.order, direction = -1f,
                        onChange = { controller.update(preferred.copy(orderWidth = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(orderWidth = defaults.orderWidth)); save() })
                    order(Modifier.width(layout.order.value.dp).fillMaxHeight().testTag("desktop-order")) { setOpen(DesktopPanel.ORDER, false) }
                }
                if (preferred.marketOpen) {
                    PanelResizeHandle("行情栏宽度", "resize-sidebar", horizontal = true, dimension = layout.sidebar, direction = -1f,
                        onChange = { controller.update(preferred.copy(sidebarWidth = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(sidebarWidth = defaults.sidebarWidth)); save() })
                    Box(Modifier.width(layout.sidebar.value.dp).fillMaxHeight().background(TradingColors.Surface).testTag("desktop-sidebar")) {
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxWidth().height(layout.accounts.value.dp).testTag("desktop-accounts").verticalScroll(rememberScrollState())) { accounts() }
                            PanelResizeHandle("账户区高度", "resize-accounts", horizontal = false, dimension = layout.accounts,
                                onChange = { controller.update(preferred.copy(accountsHeight = it)) }, onFinish = ::save,
                                onReset = { controller.update(preferred.copy(accountsHeight = defaults.accountsHeight)); save() })
                            market(Modifier.weight(1f).fillMaxWidth().testTag("desktop-market"))
                        }
                        // In its own box: ActionIcon applies its modifier inside the tooltip, where alignment means nothing.
                        Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp)) {
                            ActionIcon(AppIcons.Close, "收起行情面板", "desktop-market-minimize", tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                                setOpen(DesktopPanel.MARKET, false)
                            }
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationRail(Modifier.width((PANEL_RAIL - 1f).dp).fillMaxHeight().testTag("desktop-panel-rail"),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    header = { Text("面板", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium) }) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    RailToggle(AppIcons.TrendingUp, "行情", "rail-market", preferred.marketOpen) { setOpen(DesktopPanel.MARKET, !preferred.marketOpen) }
                    if (wide) RailToggle(AppIcons.Add, "下单", "rail-order", preferred.orderOpen) { setOpen(DesktopPanel.ORDER, !preferred.orderOpen) }
                    RailToggle(if (preferred.terminalOpen) AppIcons.ReceiptLongFilled else AppIcons.ReceiptLong, "交易", "rail-terminal", preferred.terminalOpen) { setOpen(DesktopPanel.TERMINAL, !preferred.terminalOpen) }
                    if (orderFlow != null) RailToggle(if (flowOpen) AppIcons.CandlestickFilled else AppIcons.Candlestick, "订单流", "rail-order-flow", flowOpen) {
                        onOrderFlowOpenChange(!flowOpen)
                    }
                    }
                }
            }
        }
    }
}

/** The trade list's title bar. [summary] keeps the counts in view while the list is folded down to this bar. */
@Composable
private fun TerminalBar(summary: String, open: Boolean, onToggle: () -> Unit) {
    PanelHeader("交易", Modifier.background(TradingColors.Surface).testTag("desktop-terminal-bar"), summary.ifBlank { null }) {
        ActionIcon(if (open) AppIcons.ExpandMore else AppIcons.ExpandLess, if (open) "收起交易面板" else "展开交易面板", "desktop-terminal-toggle", onClick = onToggle)
    }
}

/** A native rail destination whose pill marks an open panel. Several panels can stay open, so each also reports as a toggle. */
@Composable
private fun RailToggle(icon: ImageVector, name: String, tag: String, open: Boolean, onToggle: () -> Unit) {
    Box(Modifier.width((PANEL_RAIL - 1f).dp).heightIn(min = 80.dp).testTag("$tag-item"), contentAlignment = Alignment.Center) {
        NavigationRailItem(open, onToggle, { Icon(icon, contentDescription = null) },
            Modifier.testTag(tag).semantics {
                toggleableState = if (open) ToggleableState.On else ToggleableState.Off
                stateDescription = if (open) "已展开" else "已收起"
            }, label = { Text(name, maxLines = 1) }, alwaysShowLabel = true)
    }
}

/** A visible eight-dp grip; dragging, keyboard arrows and accessibility all adjust the same bounded dimension. */
@Composable
private fun PanelResizeHandle(
    label: String, tag: String, horizontal: Boolean, dimension: PanelDimension,
    onChange: (Float) -> Unit, onFinish: () -> Unit, onReset: () -> Unit, direction: Float = 1f,
) {
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(dimension.value) }
    val focusRequester = remember { FocusRequester() }
    val reset by rememberUpdatedState(onReset)
    val current by rememberUpdatedState(dimension)
    val change by rememberUpdatedState(onChange)
    val enabled = dimension.max > dimension.min
    fun moveTo(value: Float) { onChange(value.coerceIn(dimension.min, dimension.max)) }
    val active = hovered || focused || dragging
    Box(
        (if (horizontal) Modifier.width(PANEL_DIVIDER.dp).fillMaxHeight() else Modifier.height(PANEL_DIVIDER.dp).fillMaxWidth())
            .testTag(tag).background(if (active) TradingColors.Raised else TradingColors.Shell)
            .pointerHoverIcon(panelResizeCursor(horizontal)).hoverable(interaction)
            .focusRequester(focusRequester).onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> if (horizontal) -8f else 0f
                    Key.DirectionRight -> if (horizontal) 8f else 0f
                    Key.DirectionUp -> if (!horizontal) -8f else 0f
                    Key.DirectionDown -> if (!horizontal) 8f else 0f
                    else -> 0f
                }
                if (!enabled || delta == 0f) false else {
                    if (event.type == KeyEventType.KeyDown) moveTo(dimension.value + delta * direction)
                    if (event.type == KeyEventType.KeyUp) onFinish()
                    true
                }
            }.focusable(enabled)
            .draggable(rememberDraggableState { pixels ->
                dragValue = (dragValue + pixels / density * direction).coerceIn(current.min, current.max)
                change(dragValue)
            }, if (horizontal) Orientation.Horizontal else Orientation.Vertical, enabled = enabled,
                onDragStarted = { dragValue = current.value; dragging = true; focusRequester.requestFocus() },
                onDragStopped = { dragging = false; onFinish() })
            .pointerInput(Unit) {
                // Focus on press: waiting for onTap's double-click timeout drops an immediately following arrow key.
                detectTapGestures(onPress = { if (current.max > current.min) focusRequester.requestFocus() }, onDoubleTap = { reset() })
            }
            .semantics {
                contentDescription = "拖动调整$label，双击恢复默认"
                stateDescription = "${dimension.value.roundToInt()} dp"
                progressBarRangeInfo = ProgressBarRangeInfo(dimension.value, dimension.min..dimension.max)
                setProgress { if (enabled && it.isFinite()) { moveTo(it); onFinish(); true } else false }
                customActions = listOf(CustomAccessibilityAction("恢复默认尺寸") { onReset(); true })
            }, contentAlignment = Alignment.Center,
    ) {
        Box((if (horizontal) Modifier.width(2.dp).height(32.dp) else Modifier.height(2.dp).width(32.dp))
            .background(if (active) TradingColors.Accent else TradingColors.Border, RoundedCornerShape(1.dp)))
    }
}
