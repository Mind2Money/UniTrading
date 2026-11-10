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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import com.unitrading.app.DesktopPanel
import com.unitrading.app.WorkspacePanel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The chart with the trade list under it, then the order ticket and the market panel, as on TradingView.
 * The Material rail folds the surrounding panels and opens order flow in the chart area's right half.
 *
 * Where the platform has windows, any panel can leave for a window of its own from the rail's window menu; closing that
 * window brings it back. A panel that left takes no room here, and what stays shares the space. [windowOverlay] is drawn
 * over the content of each such window, for the dialogs and notices that belong to the window the user works in, and
 * [windowMenuExtra] adds the caller's own entries to the window menu.
 */
@Composable
internal fun DesktopWorkspace(
    controller: DesktopLayoutController,
    wide: Boolean,
    modifier: Modifier = Modifier,
    market: @Composable (Modifier, minimize: () -> Unit) -> Unit,
    chart: @Composable (Modifier) -> Unit,
    order: @Composable (Modifier, minimize: () -> Unit) -> Unit,
    terminal: @Composable (Modifier) -> Unit,
    terminalSummary: String = "",
    orderFlow: (@Composable (Modifier) -> Unit)? = null,
    orderFlowOpen: Boolean = false,
    onOrderFlowOpenChange: (Boolean) -> Unit = {},
    windowOverlay: @Composable (window: String) -> Unit = {},
    windowMenuExtra: @Composable (close: () -> Unit) -> Unit = {},
) {
    val preferred by controller.preferences.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    fun save() { scope.launch { controller.save() } }
    fun setOpen(panel: DesktopPanel, open: Boolean) { controller.setOpen(panel, open); save() }
    val defaults = DesktopLayoutPreferences()
    val host = LocalPanelWindowHost.current
    // Without windows to move to, a saved detachment is ignored and every panel stays here.
    val detached = if (host == null) emptySet() else preferred.detached
    fun setDetached(panel: WorkspacePanel, value: Boolean) { controller.setDetached(panel, value); save() }
    val flowOpen = orderFlow != null && orderFlowOpen
    val flowInline = flowOpen && WorkspacePanel.ORDER_FLOW !in detached
    val chartInline = WorkspacePanel.CHART !in detached
    val terminalInline = WorkspacePanel.TERMINAL !in detached
    val orderInline = wide && preferred.orderOpen && WorkspacePanel.ORDER !in detached
    val sidebarInline = preferred.marketOpen && WorkspacePanel.MARKET !in detached
    Column(modifier.testTag("desktop-workspace")) {
        error?.let { message ->
            Row(Modifier.fillMaxWidth().background(TradingColors.Raised).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TradingColors.Loss)
                TextButton({ save() }, Modifier.testTag("retry-layout-save")) { Text("重试保存") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val layout = desktopPanelGeometry(maxWidth.value, maxHeight.value, wide, preferred.copy(marketOpen = sidebarInline, orderOpen = orderInline), flowInline)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // With the chart in its own window and no order flow beside it, the trade list takes the chart's room.
                    val chartArea = chartInline || flowInline
                    if (chartArea) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("desktop-chart-area")) {
                        val flowWidth = orderFlowPanelDimension(maxWidth.value, preferred.orderFlowFraction)
                        val splitWidth = (maxWidth.value - PANEL_DIVIDER).coerceAtLeast(0f)
                        Row(Modifier.fillMaxSize()) {
                            if (chartInline) chart(Modifier.weight(1f).fillMaxHeight().testTag("desktop-chart"))
                            if (flowInline) {
                                if (chartInline) PanelResizeHandle("订单流宽度", "resize-order-flow", horizontal = true, dimension = flowWidth, direction = -1f,
                                    onChange = { if (splitWidth > 0f) controller.update(preferred.copy(orderFlowFraction = it / splitWidth)) },
                                    onFinish = ::save,
                                    onReset = { controller.update(preferred.copy(orderFlowFraction = defaults.orderFlowFraction)); save() })
                                Box((if (chartInline) Modifier.width(flowWidth.value.dp) else Modifier.weight(1f)).fillMaxHeight().testTag("desktop-order-flow")) {
                                    orderFlow?.invoke(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    if (!chartInline) DetachedBar(WorkspacePanel.CHART) { setDetached(WorkspacePanel.CHART, false) }
                    if (terminalInline && preferred.terminalOpen) {
                        if (chartArea) PanelResizeHandle("交易区高度", "resize-terminal", horizontal = false, dimension = layout.terminal, direction = -1f,
                            onChange = { controller.update(preferred.copy(terminalHeight = it)) }, onFinish = ::save,
                            onReset = { controller.update(preferred.copy(terminalHeight = defaults.terminalHeight)); save() })
                        Column((if (chartArea) Modifier.height(layout.terminal.value.dp) else Modifier.weight(1f)).fillMaxWidth().testTag("desktop-terminal")) {
                            TerminalBar(terminalSummary, open = true) { setOpen(DesktopPanel.TERMINAL, false) }
                            terminal(Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        if (terminalInline) {
                            DividerLine()
                            Column(Modifier.fillMaxWidth().testTag("desktop-terminal")) { TerminalBar(terminalSummary, open = false) { setOpen(DesktopPanel.TERMINAL, true) } }
                        }
                        if (!chartArea) Spacer(Modifier.weight(1f))
                    }
                }
                if (orderInline) {
                    PanelResizeHandle("下单栏宽度", "resize-order", horizontal = true, dimension = layout.order, direction = -1f,
                        onChange = { controller.update(preferred.copy(orderWidth = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(orderWidth = defaults.orderWidth)); save() })
                    order(Modifier.width(layout.order.value.dp).fillMaxHeight().testTag("desktop-order")) { setOpen(DesktopPanel.ORDER, false) }
                }
                if (sidebarInline) {
                    PanelResizeHandle("行情栏宽度", "resize-sidebar", horizontal = true, dimension = layout.sidebar, direction = -1f,
                        onChange = { controller.update(preferred.copy(sidebarWidth = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(sidebarWidth = defaults.sidebarWidth)); save() })
                    Box(Modifier.width(layout.sidebar.value.dp).fillMaxHeight().background(TradingColors.Surface).testTag("desktop-sidebar")) {
                        market(Modifier.fillMaxSize().testTag("desktop-market")) { setOpen(DesktopPanel.MARKET, false) }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationRail(Modifier.width((PANEL_RAIL - 1f).dp).fillMaxHeight().testTag("desktop-panel-rail"),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    header = { Text("面板", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium) }) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    RailToggle(AppIcons.TrendingUp, "行情", "rail-market", preferred.marketOpen) { setOpen(DesktopPanel.MARKET, !preferred.marketOpen) }
                    // The ticket only fits beside a wide chart, but its own window has room at any width.
                    if (wide || WorkspacePanel.ORDER in detached) RailToggle(AppIcons.Add, "下单", "rail-order", preferred.orderOpen) { setOpen(DesktopPanel.ORDER, !preferred.orderOpen) }
                    RailToggle(if (preferred.terminalOpen) AppIcons.ReceiptLongFilled else AppIcons.ReceiptLong, "交易", "rail-terminal", preferred.terminalOpen) { setOpen(DesktopPanel.TERMINAL, !preferred.terminalOpen) }
                    if (orderFlow != null) RailToggle(if (flowOpen) AppIcons.CandlestickFilled else AppIcons.Candlestick, "订单流", "rail-order-flow", flowOpen) {
                        onOrderFlowOpenChange(!flowOpen)
                    }
                    }
                    if (host != null) WindowMenu(detached, WorkspacePanel.entries.filter { it != WorkspacePanel.ORDER_FLOW || orderFlow != null },
                        { panel -> setDetached(panel, panel !in detached) }, windowMenuExtra)
                }
            }
        }
        if (host != null) {
            @Composable
            fun window(panel: WorkspacePanel, width: Int, height: Int, content: @Composable () -> Unit) {
                host.Window(panel.name, "UniTrading · ${panel.title}", preferred.windows[panel.name], DpSize(width.dp, height.dp),
                    onBounds = { controller.setWindowBounds(panel.name, it); save() }, onClose = { setDetached(panel, false) }) {
                    WindowSurface(panel.name, windowOverlay, content)
                }
            }
            // A folded panel has no window either: the rail's toggles fold and unfold it wherever it lives.
            if (WorkspacePanel.CHART in detached) window(WorkspacePanel.CHART, 1000, 680) { chart(Modifier.fillMaxSize()) }
            if (WorkspacePanel.MARKET in detached && preferred.marketOpen) window(WorkspacePanel.MARKET, 380, 640) {
                market(Modifier.fillMaxSize()) { setOpen(DesktopPanel.MARKET, false) }
            }
            if (WorkspacePanel.ORDER in detached && preferred.orderOpen) window(WorkspacePanel.ORDER, 360, 760) { order(Modifier.fillMaxSize()) { setOpen(DesktopPanel.ORDER, false) } }
            if (WorkspacePanel.TERMINAL in detached && preferred.terminalOpen) window(WorkspacePanel.TERMINAL, 960, 360) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("交易", Modifier.background(TradingColors.Surface), terminalSummary.ifBlank { null })
                    terminal(Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (WorkspacePanel.ORDER_FLOW in detached && flowOpen) window(WorkspacePanel.ORDER_FLOW, 760, 680) { orderFlow?.invoke(Modifier.fillMaxSize()) }
        }
    }
}

/** What fills a window that holds part of the workspace: the app's background, the content, and the caller's overlay above it. */
@Composable
internal fun WindowSurface(window: String, overlay: @Composable (String) -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("window-$window"), color = TradingColors.Shell) {
        Box(Modifier.fillMaxSize()) { content(); overlay(window) }
    }
}

/** Stands where a panel was while it is in a window of its own, and brings it back. */
@Composable
private fun DetachedBar(panel: WorkspacePanel, onDock: () -> Unit) {
    PanelHeader(panel.title, Modifier.background(TradingColors.Surface).testTag("detached-${panel.name}"), "在独立窗口中显示") {
        TextButton(onDock, Modifier.testTag("dock-${panel.name}")) { Text("收回") }
    }
}

/** The rail's last entry: which panels are in windows of their own. The menu stays open, so several can be moved in a row. */
@Composable
private fun WindowMenu(detached: Set<WorkspacePanel>, panels: List<WorkspacePanel>, onToggle: (WorkspacePanel) -> Unit, extra: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.width((PANEL_RAIL - 1f).dp).heightIn(min = 80.dp).testTag("rail-windows-item"), contentAlignment = Alignment.Center) {
        NavigationRailItem(detached.isNotEmpty(), { expanded = true }, { Icon(AppIcons.OpenInNew, contentDescription = null) },
            Modifier.testTag("rail-windows").semantics { stateDescription = if (detached.isEmpty()) "没有独立窗口" else "${detached.size} 个面板在独立窗口中" },
            label = { Text("窗口", maxLines = 1) }, alwaysShowLabel = true)
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 200.dp)) {
            Text("在独立窗口中显示", Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = TradingColors.Muted)
            panels.forEach { panel ->
                val out = panel in detached
                DropdownMenuItem(text = { Text(panel.title) }, onClick = { onToggle(panel) },
                    trailingIcon = { if (out) Icon(AppIcons.Check, contentDescription = "已在独立窗口中", tint = TradingColors.Accent) },
                    modifier = Modifier.testTag("window-menu-${panel.name}").semantics { stateDescription = if (out) "独立窗口" else "主窗口" })
            }
            extra { expanded = false }
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
