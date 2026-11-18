package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote

/** A title slot owned by the desktop window, populated only while the trading workspace exists. */
internal class DesktopTitleBarSlot {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
}
internal val LocalDesktopTitleBarSlot = staticCompositionLocalOf<DesktopTitleBarSlot?> { null }

internal data class WorkspaceTabData(val id: String, val symbol: String, val price: String, val change: String, val direction: Int)

/** Never substitute a bid, ask or historical candle for a missing last trade. */
internal fun workspaceTabData(id: String, symbol: String, instrument: Instrument?, quote: Quote?, connected: Boolean): WorkspaceTabData {
    val live = quote.takeIf { connected }
    val price = live?.lastTrade?.let { instrument?.formatPrice(it) }?.let(::groupPriceDigits) ?: "—"
    val (change, direction) = marketChangePercent(live)
    return WorkspaceTabData(id, symbol, price, change, direction)
}

internal fun groupPriceDigits(price: String): String {
    val negative = price.startsWith('-')
    val unsigned = if (negative) price.drop(1) else price
    val parts = unsigned.split('.', limit = 2)
    val whole = parts[0].reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + whole + (if (parts.size == 2) ".${parts[1]}" else "")
}

/** Tabs remain independent chart workspaces; dragging a tab vertically or its button detaches it. */
@Composable
internal fun WorkspaceTabStrip(
    tabs: List<WorkspaceTabData>, selected: String,
    onSelect: (String) -> Unit, onDetach: (String) -> Unit, onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detach by rememberUpdatedState(onDetach)
    val colors = MaterialTheme.colorScheme
    val palette = TradingColors
    Row(modifier.fillMaxHeight().horizontalScroll(rememberScrollState()).testTag("chart-workspace-tabs"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { tab -> key(tab.id) {
            var dragY by remember(tab.id) { mutableFloatStateOf(0f) }
            val active = tab.id == selected
            val shape = RoundedCornerShape(7.dp)
            Row(Modifier.height(32.dp).widthIn(min = 150.dp, max = 300.dp).clip(shape)
                .background(if (active) colors.secondaryContainer else colors.surfaceContainerHigh)
                .border(1.dp, if (active) colors.primary.copy(alpha = .45f) else colors.outlineVariant, shape)
                .pointerInput(tab.id) {
                    detectDragGestures(onDragStart = { dragY = 0f }, onDragEnd = {
                        if (kotlin.math.abs(dragY) > 35.dp.toPx()) detach(tab.id)
                    }) { change, amount -> dragY += amount.y; change.consume() }
                }.clickable { onSelect(tab.id) }.testTag("workspace-tab-${tab.id}")
                .padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tab.symbol, Modifier.widthIn(max = 80.dp).testTag("workspace-symbol-${tab.id}"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(tab.price, Modifier.widthIn(max = 82.dp).testTag("workspace-price-${tab.id}"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                Spacer(Modifier.width(5.dp))
                val arrow = when { tab.direction > 0 -> "▲ "; tab.direction < 0 -> "▼ "; else -> "" }
                Text(arrow + tab.change, Modifier.widthIn(max = 70.dp).testTag("workspace-change-${tab.id}"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                    color = when { tab.direction > 0 -> palette.UpText; tab.direction < 0 -> palette.DownText; else -> palette.Muted })
                IconButton({ onDetach(tab.id) }, Modifier.size(25.dp).testTag("detach-workspace-${tab.id}")) {
                    Icon(AppIcons.OpenInNew, "拖出图表布局", Modifier.size(15.dp))
                }
                if (tab.id != "main") IconButton({ onClose(tab.id) }, Modifier.size(25.dp).testTag("close-workspace-${tab.id}")) {
                    Icon(AppIcons.Close, "关闭图表布局", Modifier.size(15.dp))
                }
            }
        } }
    }
}
