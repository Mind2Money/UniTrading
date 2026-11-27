package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Non-modal in-window ticket; only the ticket itself handles input, leaving the remaining chart interactive. */
@Composable
internal fun FullscreenOrderOverlay(symbol: String, onClose: () -> Unit, compact: Boolean = false, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val colors = TradingColors
        val w = minOf(if (compact) 288.dp else 380.dp, (maxWidth - 16.dp).coerceAtLeast(1.dp))
        val h = minOf(if (compact) 460.dp else 720.dp, (maxHeight - 16.dp).coerceAtLeast(1.dp))
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - w).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - h).toPx().coerceAtLeast(0f) }
        var left by remember { mutableFloatStateOf(Float.NaN) }
        var top by remember { mutableFloatStateOf(Float.NaN) }
        val x = if (left.isNaN()) maxX / 2f else left.coerceIn(0f, maxX)
        val y = if (top.isNaN()) maxY / 2f else top.coerceIn(0f, maxY)
        Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(w).height(h)
            .testTag("fullscreen-order-ticket"), shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(44.dp).pointerInput(maxX, maxY) {
                    detectDragGestures { change, amount ->
                        left = ((if (left.isNaN()) x else left) + amount.x).coerceIn(0f, maxX)
                        top = ((if (top.isNaN()) y else top) + amount.y).coerceIn(0f, maxY)
                        change.consume()
                    }
                }.testTag("fullscreen-ticket-drag").padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.DragIndicator, null, Modifier.size(18.dp), tint = colors.Muted)
                    Text("下单 · $symbol", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    ActionIcon(AppIcons.Close, "关闭悬浮下单窗", "fullscreen-ticket-close", onClick = onClose)
                }
                DividerLine()
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
