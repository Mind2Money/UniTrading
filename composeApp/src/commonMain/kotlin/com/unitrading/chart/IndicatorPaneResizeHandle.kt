package com.unitrading.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.unitrading.ui.TradingColors
import com.unitrading.ui.panelResizeCursor
import kotlin.math.roundToInt

/** A divider above each pane; dragging changes its share of the chart without panning candles. */
@Composable
internal fun IndicatorPaneResizeHandle(
    id: String, label: String, fraction: Float, minimum: Float, maximum: Float, chartHeight: Float,
    onChange: (Float) -> Unit, onFinish: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier,
) {
    val currentFraction by rememberUpdatedState(fraction)
    val currentMinimum by rememberUpdatedState(minimum)
    val currentMaximum by rememberUpdatedState(maximum)
    val currentHeight by rememberUpdatedState(chartHeight)
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val reset by rememberUpdatedState(onReset)
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Box(modifier.height(12.dp).testTag("resize-indicator-$id")
        .pointerHoverIcon(panelResizeCursor(false)).focusRequester(focus)
        .onKeyEvent { event ->
            val step = when (event.key) { Key.DirectionUp -> .02f; Key.DirectionDown -> -.02f; else -> 0f }
            if (step == 0f) false else {
                if (event.type == KeyEventType.KeyDown) change((currentFraction + step).coerceIn(currentMinimum, currentMaximum))
                if (event.type == KeyEventType.KeyUp) finish()
                true
            }
        }.focusable()
        .draggable(rememberDraggableState { pixels ->
            if (currentHeight > 0f) {
                dragFraction = (dragFraction - pixels / currentHeight).coerceIn(currentMinimum, currentMaximum)
                change(dragFraction)
            }
        }, Orientation.Vertical,
            onDragStarted = { dragFraction = currentFraction; dragging = true; focus.requestFocus() },
            onDragStopped = { dragging = false; finish() })
        .pointerInput(id) { detectTapGestures(onPress = { focus.requestFocus() }, onDoubleTap = { reset() }) }
        .semantics {
            contentDescription = "拖动调整${label}窗口高度，双击恢复默认"
            stateDescription = "${(fraction * 100).roundToInt()}%"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimum..maximum)
            setProgress { value ->
                if (value.isFinite()) { change(value.coerceIn(minimum, maximum)); finish(); true } else false
            }
            customActions = listOf(CustomAccessibilityAction("恢复默认高度") { reset(); true })
        }, contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(if (dragging) 3.dp else 2.dp)
            .background(if (dragging) TradingColors.Accent else TradingColors.Muted, RoundedCornerShape(2.dp)))
    }
}
