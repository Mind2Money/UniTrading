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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import com.unitrading.app.DesktopPanel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The chart with the trade list under it, then the order ticket and the accounts and market panel, as on TradingView.
 * The icon column at the right edge folds any of the three panels; the chart takes the room they leave.
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
            val layout = desktopPanelGeometry(maxWidth.value, maxHeight.value, wide, preferred)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    chart(Modifier.weight(1f).fillMaxWidth().testTag("desktop-chart"))
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
                    Box(Modifier.width(layout.sidebar.value.dp).fillMaxHeight().background(TradingColors.Shell).testTag("desktop-sidebar")) {
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxWidth().height(layout.accounts.value.dp).testTag("desktop-accounts").verticalScroll(rememberScrollState())) { accounts() }
                            PanelResizeHandle("账户区高度", "resize-accounts", horizontal = false, dimension = layout.accounts,
                                onChange = { controller.update(preferred.copy(accountsHeight = it)) }, onFinish = ::save,
                                onReset = { controller.update(preferred.copy(accountsHeight = defaults.accountsHeight)); save() })
                            market(Modifier.weight(1f).fillMaxWidth().testTag("desktop-market"))
                        }
                        // In its own box: ActionIcon applies its modifier inside the tooltip, where alignment means nothing.
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                            ActionIcon(AppIcons.Close, "收起行情面板", "desktop-market-minimize", Modifier.size(32.dp), tint = TradingColors.Muted, iconSize = 18.dp) {
                                setOpen(DesktopPanel.MARKET, false)
                            }
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.width((PANEL_RAIL - 1f).dp).fillMaxHeight().background(TradingColors.Shell).padding(top = 4.dp).testTag("desktop-panel-rail"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RailToggle(AppIcons.TrendingUp, "行情面板", "rail-market", preferred.marketOpen) { setOpen(DesktopPanel.MARKET, !preferred.marketOpen) }
                    if (wide) RailToggle(AppIcons.Add, "下单面板", "rail-order", preferred.orderOpen) { setOpen(DesktopPanel.ORDER, !preferred.orderOpen) }
                    RailToggle(AppIcons.ReceiptLong, "交易面板", "rail-terminal", preferred.terminalOpen) { setOpen(DesktopPanel.TERMINAL, !preferred.terminalOpen) }
                }
            }
        }
    }
}

/** The trade list's title bar. [summary] keeps the counts in view while the list is folded down to this bar. */
@Composable
private fun TerminalBar(summary: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(PANEL_BAR.dp).background(TradingColors.Surface).padding(start = 16.dp, end = 4.dp).testTag("desktop-terminal-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("交易", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ActionIcon(if (open) AppIcons.ExpandMore else AppIcons.ExpandLess, if (open) "收起交易面板" else "展开交易面板", "desktop-terminal-toggle", Modifier.size(32.dp),
            tint = TradingColors.Muted, iconSize = 20.dp, onClick = onToggle)
    }
}

/** One panel's switch in the icon column, filled while the panel is open. */
@Composable
private fun RailToggle(icon: ImageVector, name: String, tag: String, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ActionIcon(icon, if (open) "收起$name" else "展开$name", tag, Modifier.size(40.dp).background(if (open) scheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small),
        tint = if (open) scheme.onSecondaryContainer else TradingColors.Muted, iconSize = 22.dp, onClick = onToggle)
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
