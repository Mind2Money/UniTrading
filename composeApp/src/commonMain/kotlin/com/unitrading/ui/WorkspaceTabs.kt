package com.unitrading.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class WorkspaceTabData(val id: String, val symbol: String, val price: String, val change: String, val direction: Int)

internal data class DesktopTitleTabs(
    val tabs: List<WorkspaceTabData>, val selected: String, val canDetach: Boolean, val touchSized: Boolean, val canAdd: Boolean = true,
)

/** Publishes data to the native title row; its actions always point to the current workspace. */
internal class DesktopTitleBarSlot {
    var tabs: DesktopTitleTabs? by mutableStateOf(null)
        private set
    private var owner: Any? = null
    private var selectAction: (String) -> Unit = {}
    private var detachAction: (String) -> Unit = {}
    private var closeAction: (String) -> Unit = {}
    private var reorderAction: (List<String>) -> Unit = {}
    private var addAction: () -> Unit = {}

    fun publish(owner: Any, tabs: DesktopTitleTabs, onSelect: (String) -> Unit, onDetach: (String) -> Unit,
        onClose: (String) -> Unit, onReorder: (List<String>) -> Unit, onAdd: () -> Unit = {}) {
        this.owner = owner
        selectAction = onSelect; detachAction = onDetach; closeAction = onClose; reorderAction = onReorder
        addAction = onAdd
        this.tabs = tabs
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        selectAction = {}; detachAction = {}; closeAction = {}; reorderAction = {}; addAction = {}
        tabs = null
    }

    fun select(id: String) = selectAction(id)
    fun detach(id: String) = detachAction(id)
    fun close(id: String) = closeAction(id)
    fun reorder(ids: List<String>) = reorderAction(ids)
    fun add() = addAction()
}
internal val LocalDesktopTitleBarSlot = staticCompositionLocalOf<DesktopTitleBarSlot?> { null }

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

/** Snapshot tab positions at drag start; the list is committed only when a horizontal drag is released. */
private class WorkspaceTabDrag(
    val id: String, val ids: List<String>, val starts: List<Float>, val widths: List<Float>,
    val startScroll: Int, val grabX: Float,
) {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    val from = ids.indexOf(id)
    val pointerX get() = starts[from] + grabX + x
    fun detaching(threshold: Float) = abs(y) > threshold && abs(y) > abs(x)
    fun target(scroll: Int, threshold: Float): Int {
        if (detaching(threshold)) return from
        val center = starts[from] + widths[from] / 2f + x + scroll - startScroll
        var to = from
        while (to < starts.lastIndex && center > starts[to + 1] + widths[to + 1] / 2f) to++
        if (to == from) while (to > 0 && center < starts[to - 1] + widths[to - 1] / 2f) to--
        return to
    }
}

/** Tabs remain independent workspaces; drag horizontally to reorder or vertically to detach. */
@Composable
internal fun WorkspaceTabStrip(
    tabs: List<WorkspaceTabData>, selected: String,
    onSelect: (String) -> Unit, onDetach: (String) -> Unit, onClose: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier, canDetach: Boolean = true, touchSized: Boolean = false,
    onAdd: (() -> Unit)? = null, canAdd: Boolean = true,
) {
    val detach by rememberUpdatedState(onDetach)
    val reorder by rememberUpdatedState(onReorder)
    val currentTabs by rememberUpdatedState(tabs)
    val colors = MaterialTheme.colorScheme
    val palette = TradingColors
    val density = LocalDensity.current
    val threshold = with(density) { 35.dp.toPx() }
    val spacing = with(density) { 4.dp.toPx() }
    val edgeZone = with(density) { 40.dp.toPx() }
    val speed = with(density) { 300.dp.toPx() }
    val scroll = rememberScrollState()
    LaunchedEffect(tabs.size) {
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }
    val placements = remember { mutableMapOf<String, LayoutCoordinates>() }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var drag by remember { mutableStateOf<WorkspaceTabDrag?>(null) }
    val held = drag
    val viewLeft = viewport?.positionInWindow()?.x ?: 0f
    val viewRight = viewLeft + (viewport?.size?.width ?: 0)
    val edge = if (held == null || held.detaching(threshold)) 0 else when {
        held.x < 0f && held.pointerX < viewLeft + edgeZone -> -1
        held.x > 0f && held.pointerX > viewRight - edgeZone -> 1
        else -> 0
    }
    LaunchedEffect(edge, held?.id) {
        val end = when (edge) { -1 -> 0; 1 -> scroll.maxValue; else -> return@LaunchedEffect }
        val duration = (abs(end - scroll.value) * 1000f / speed).roundToInt().coerceAtLeast(1)
        scroll.animateScrollTo(end, tween(duration, easing = LinearEasing))
    }
    Row(modifier.fillMaxHeight().onGloballyPositioned { viewport = it }.horizontalScroll(scroll).testTag("chart-workspace-tabs"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { index, tab -> key(tab.id) {
            val active = tab.id == selected
            val shape = RoundedCornerShape(7.dp)
            val mine = held?.takeIf { it.id == tab.id }
            val target = held?.target(scroll.value, threshold) ?: index
            val shift = when {
                held == null -> 0f
                mine != null && !held.detaching(threshold) -> held.x + scroll.value - held.startScroll
                mine != null -> 0f
                index in (held.from + 1)..target -> -(held.widths[held.from] + spacing)
                index in target until held.from -> held.widths[held.from] + spacing
                else -> 0f
            }
            Row(Modifier.height(if (touchSized) 42.dp else 32.dp).widthIn(min = 150.dp, max = 300.dp)
                .onGloballyPositioned { placements[tab.id] = it }.zIndex(if (mine != null) 1f else 0f)
                .graphicsLayer { translationX = shift; if (mine != null) shadowElevation = 5.dp.toPx() }
                .clip(shape).background(if (active) colors.secondaryContainer else colors.surfaceContainerHigh)
                .border(1.dp, if (active || mine != null) colors.primary.copy(alpha = .6f) else colors.outlineVariant, shape)
                .pointerInput(tab.id, canDetach) {
                    detectDragGestures(onDragStart = { position ->
                        val ids = currentTabs.map { it.id }
                        val rows = ids.mapNotNull { placements[it] }
                        if (rows.size == ids.size && tab.id in ids) drag = WorkspaceTabDrag(tab.id, ids,
                            rows.map { it.positionInWindow().x }, rows.map { it.size.width.toFloat() }, scroll.value, position.x)
                    }, onDragEnd = {
                        val finished = drag?.takeIf { it.id == tab.id }
                        drag = null
                        if (finished != null && finished.ids == currentTabs.map { it.id }) {
                            if (finished.detaching(threshold)) { if (canDetach) detach(tab.id) }
                            else {
                                val to = finished.target(scroll.value, threshold)
                                if (to != finished.from) reorder(finished.ids.toMutableList().apply { add(to, removeAt(finished.from)) })
                            }
                        }
                    }, onDragCancel = { drag = null }) { change, amount ->
                        drag?.takeIf { it.id == tab.id }?.let { it.x += amount.x; it.y += amount.y }
                        change.consume()
                    }
                }.clickable { onSelect(tab.id) }.testTag("workspace-tab-${tab.id}")
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction("向左移动 ${tab.symbol}") {
                            reorder(currentTabs.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                        }.takeIf { index > 0 },
                        CustomAccessibilityAction("向右移动 ${tab.symbol}") {
                            reorder(currentTabs.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                        }.takeIf { index < tabs.lastIndex },
                    )
                }.padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
                if (canDetach) IconButton({ onDetach(tab.id) }, Modifier.size(25.dp).testTag("detach-workspace-${tab.id}")) {
                    Icon(AppIcons.OpenInNew, "拖出图表布局", Modifier.size(15.dp))
                }
                if (tab.id != "main") IconButton({ onClose(tab.id) }, Modifier.size(if (touchSized) 40.dp else 25.dp).testTag("close-workspace-${tab.id}")) {
                    Icon(AppIcons.Close, "关闭图表布局", Modifier.size(15.dp))
                }
            }
        } }
        if (onAdd != null) IconButton(onClick = onAdd, enabled = canAdd,
            modifier = Modifier.size(if (touchSized) 42.dp else 32.dp).testTag("chart-new-workspace")) {
            Icon(AppIcons.Add, "新建窗口", Modifier.size(20.dp))
        }
    }
}
