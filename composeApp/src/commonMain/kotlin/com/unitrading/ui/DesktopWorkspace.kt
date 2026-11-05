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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun DesktopWorkspace(
    controller: DesktopLayoutController,
    wide: Boolean,
    modifier: Modifier = Modifier,
    accounts: @Composable () -> Unit,
    market: @Composable (Modifier) -> Unit,
    chart: @Composable (Modifier) -> Unit,
    order: @Composable (Modifier) -> Unit,
    terminal: @Composable (Modifier) -> Unit,
) {
    val preferred by controller.preferences.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    fun save() { scope.launch { controller.save() } }
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
                Column(Modifier.width(layout.sidebar.value.dp).fillMaxHeight().background(TradingColors.Shell).testTag("desktop-sidebar")) {
                    Column(Modifier.fillMaxWidth().height(layout.accounts.value.dp).testTag("desktop-accounts").verticalScroll(rememberScrollState())) { accounts() }
                    PanelResizeHandle("账户区高度", "resize-accounts", horizontal = false, dimension = layout.accounts,
                        onChange = { controller.update(preferred.copy(accountsHeight = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(accountsHeight = defaults.accountsHeight)); save() })
                    market(Modifier.weight(1f).fillMaxWidth().testTag("desktop-market"))
                }
                PanelResizeHandle("左侧栏宽度", "resize-sidebar", horizontal = true, dimension = layout.sidebar,
                    onChange = { controller.update(preferred.copy(sidebarWidth = it)) }, onFinish = ::save,
                    onReset = { controller.update(preferred.copy(sidebarWidth = defaults.sidebarWidth)); save() })
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        chart(Modifier.weight(1f).fillMaxHeight().testTag("desktop-chart"))
                        if (wide) {
                            PanelResizeHandle("下单栏宽度", "resize-order", horizontal = true, dimension = layout.order, direction = -1f,
                                onChange = { controller.update(preferred.copy(orderWidth = it)) }, onFinish = ::save,
                                onReset = { controller.update(preferred.copy(orderWidth = defaults.orderWidth)); save() })
                            order(Modifier.width(layout.order.value.dp).fillMaxHeight().testTag("desktop-order"))
                        }
                    }
                    PanelResizeHandle("交易区高度", "resize-terminal", horizontal = false, dimension = layout.terminal, direction = -1f,
                        onChange = { controller.update(preferred.copy(terminalHeight = it)) }, onFinish = ::save,
                        onReset = { controller.update(preferred.copy(terminalHeight = defaults.terminalHeight)); save() })
                    terminal(Modifier.fillMaxWidth().height(layout.terminal.value.dp).testTag("desktop-terminal"))
                }
            }
        }
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
