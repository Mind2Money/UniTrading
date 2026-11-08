package com.unitrading.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

private val GripSize = 48.dp
private val EdgeZone = 48.dp
private val KeptInView = 48.dp
private val AutoScrollPerSecond = 288.dp

/** The scrolling area around a [ReorderableColumn]. Put [scrolling] on that area in place of verticalScroll. */
@Stable
internal class ReorderViewport(val scroll: ScrollState) {
    var coordinates: LayoutCoordinates? = null
        private set
    val scrolling = Modifier.onPlaced { coordinates = it }.verticalScroll(scroll)
}

@Composable
internal fun rememberReorderViewport(initial: Int = 0): ReorderViewport {
    val scroll = rememberScrollState(initial)
    return remember(scroll) { ReorderViewport(scroll) }
}

/** One item of a [ReorderableColumn]. [grip] goes on the part that starts a drag, with [ReorderGrip] as its first child. */
internal class ReorderableItem<T>(val index: Int, val value: T, val held: Boolean, val dragging: Boolean, val grip: Modifier)

/** The drag handle at the start of an item's grip; the only place a finger can start a drag. */
@Composable
internal fun ReorderGrip(tag: String? = null) {
    Box(Modifier.size(GripSize).then(if (tag != null) Modifier.testTag(tag) else Modifier), Alignment.Center) {
        Icon(AppIcons.DragIndicator, "拖动排序", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

/** The item being dragged and the layout it started from, in pixels. [top] and [pointer] are in window coordinates. */
private class HeldItem(
    val key: Any, val from: Int, val tops: List<Float>, val heights: List<Int>,
    val viewTop: Float, val viewBottom: Float, val startTop: Float, val grabY: Float, val startScroll: Int,
) {
    var moved by mutableFloatStateOf(0f)
        private set

    /** Trails [moved] by a little slack, so comparing the two gives the direction of travel without following a shaky hand. */
    var anchor by mutableFloatStateOf(0f)
        private set

    val height get() = heights[from]
    val top get() = startTop + moved
    val pointer get() = top + grabY

    fun move(pixels: Float, slack: Float) {
        moved += pixels
        anchor = anchor.coerceIn(moved - slack, moved + slack)
    }

    /** Where the item would land with its top at [listTop]: it passes an item once its leading edge crosses that item's middle. */
    fun slotAt(listTop: Float): Int {
        var to = from
        while (to < tops.lastIndex && listTop + height > tops[to + 1] + heights[to + 1] / 2f) to++
        if (to == from) while (to > 0 && listTop < tops[to - 1] + heights[to - 1] / 2f) to--
        return to
    }

    fun landingTop(to: Int) = if (to > from) tops[to] + heights[to] - height else tops[to]

    fun shiftOf(index: Int, to: Int, spacing: Float) = when (index) {
        in (from + 1)..to -> -(height + spacing)
        in to until from -> height + spacing
        else -> 0f
    }
}

/** The item just released, [offset] pixels from its slot. Every drop is a new instance, which restarts all items at rest. */
private class ItemDrop(val key: Any, val offset: Float)

/**
 * A column whose items reorder by dragging. A drag previews the new order and calls [onReorder] once on release, so a
 * cancelled drag changes nothing. Items can differ in height. This is a plain Column: a LazyColumn would dispose the held
 * item and its gesture once auto-scroll carries its slot off screen, and would jump on drop to keep its first visible key.
 */
@Composable
internal fun <T> ReorderableColumn(
    viewport: ReorderViewport, items: List<T>, key: (T) -> Any, label: (T) -> String, onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier, spacing: Dp = 0.dp, heldShape: Shape = MaterialTheme.shapes.small,
    content: @Composable (ReorderableItem<T>) -> Unit,
) {
    val scroll = viewport.scroll
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val zone = with(LocalDensity.current) { EdgeZone.toPx() }
    val strip = with(LocalDensity.current) { KeptInView.toPx() }
    val speed = with(LocalDensity.current) { AutoScrollPerSecond.toPx() }
    val currentItems by rememberUpdatedState(items)
    val currentKey by rememberUpdatedState(key)
    val reorder by rememberUpdatedState(onReorder)
    var held by remember { mutableStateOf<HeldItem?>(null) }
    var drop by remember { mutableStateOf<ItemDrop?>(null) }
    var list by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val placed = remember { mutableMapOf<Any, LayoutCoordinates>() }

    fun begin(k: Any, grabY: Float) {
        val parent = list ?: return
        val view = viewport.coordinates?.boundsInWindow() ?: return
        if (held != null) return
        val rows = currentItems.map { placed[currentKey(it)] ?: return }
        val from = currentItems.indexOfFirst { currentKey(it) == k }
        held = HeldItem(k, from, rows.map { parent.localPositionOf(it, Offset.Zero).y }, rows.map { it.size.height },
            view.top, view.bottom, rows[from].positionInWindow().y, grabY, scroll.value)
    }

    /** The held item's top in list pixels: it follows the pointer, keeps a strip in view unless grabbed further out, and stays inside the list. */
    fun listTop(item: HeldItem): Float {
        val kept = minOf(item.height.toFloat(), strip)
        val onScreen = item.top.coerceIn(minOf(item.startTop, item.viewTop + kept - item.height), maxOf(item.startTop, item.viewBottom - kept))
        return (item.tops[item.from] + onScreen - item.startTop + scroll.value - item.startScroll)
            .coerceIn(item.tops.first(), item.tops.last() + item.heights.last() - item.height)
    }

    fun release(item: HeldItem, dropped: Boolean) {
        val top = listTop(item)
        val to = if (dropped) item.slotAt(top) else item.from
        drop = ItemDrop(item.key, top - item.landingTop(to))
        held = null
        if (to != item.from) reorder(currentItems.moved(item.from, to))
    }

    val target by remember { derivedStateOf { held?.let { it.slotAt(listTop(it)) } } }

    // A pointer held at the edge it is travelling towards scrolls the list. Only that edge: an item grabbed near an edge
    // must not start scrolling before it has moved, and a tall item must not scroll just because its far end reached one.
    val edge by remember { derivedStateOf {
        val item = held ?: return@derivedStateOf 0
        when {
            item.moved < item.anchor && item.pointer < item.viewTop + zone -> -1
            item.moved > item.anchor && item.pointer > item.viewBottom - zone -> 1
            else -> 0
        }
    } }
    LaunchedEffect(edge) {
        val end = when (edge) { -1 -> 0; 1 -> scroll.maxValue; else -> return@LaunchedEffect }
        scroll.animateScrollTo(end, tween((abs(end - scroll.value) * 1000 / speed).roundToInt(), easing = LinearEasing))
    }

    Column(modifier.onPlaced { list = it }, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, value ->
            val k = key(value)
            key(k, drop) {
                val mine = held?.takeIf { it.key == k }
                val shift = held?.let { it.shiftOf(index, target ?: it.from, spacingPx) } ?: 0f
                val slide = remember { Animatable(drop?.takeIf { it.key == k }?.offset ?: 0f) }
                LaunchedEffect(shift) { slide.animateTo(shift, spring(stiffness = Spring.StiffnessMedium)) }
                Box(Modifier.onPlaced { placed[k] = it }.zIndex(if (mine != null) 1f else 0f).graphicsLayer {
                    translationY = mine?.let { listTop(it) - it.tops[it.from] } ?: slide.value
                    if (mine != null) { shadowElevation = 6.dp.toPx(); shape = heldShape; clip = true }
                }) {
                    val grip = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(Unit) {
                            fun active() = held?.takeIf { it.key == k }
                            detectReorderDrag(
                                onStart = { grabY -> begin(k, grabY) },
                                onMove = { pixels -> active()?.move(pixels, zone / 2) },
                                onEnd = { dropped -> active()?.let { release(it, dropped) } },
                            )
                        }
                        .semantics(mergeDescendants = true) {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("上移 ${label(value)}") { reorder(items.moved(index, index - 1)); true }.takeIf { index > 0 },
                                CustomAccessibilityAction("下移 ${label(value)}") { reorder(items.moved(index, index + 1)); true }.takeIf { index < items.lastIndex },
                            )
                        }
                    content(ReorderableItem(index, value, mine != null, held != null, grip))
                }
            }
        }
    }
}

/** A mouse drags from anywhere in the grip; a finger only from its handle, because anywhere else it scrolls the list. */
private suspend fun PointerInputScope.detectReorderDrag(onStart: (grabY: Float) -> Unit, onMove: (Float) -> Unit, onEnd: (dropped: Boolean) -> Unit) {
    val handleWidth = GripSize.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val mouse = down.type == PointerType.Mouse
        if (!mouse && down.position.x > handleWidth) return@awaitEachGesture
        val slop = if (mouse) viewConfiguration.touchSlop / 8 else viewConfiguration.touchSlop
        var travelled = Offset.Zero
        while (travelled.getDistance() < slop) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            travelled += change.positionChange()
            if (travelled.getDistance() >= slop) change.consume()
        }
        onStart(down.position.y)
        onMove(travelled.y)
        onEnd(drag(down.id) { onMove(it.positionChange().y); it.consume() })
    }
}
