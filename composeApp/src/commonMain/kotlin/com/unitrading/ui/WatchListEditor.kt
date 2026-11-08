package com.unitrading.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

private val RowHeight = 48.dp
private const val AutoScrollRowsPerSecond = 6f

/** A row being dragged. [top] follows the pointer in viewport pixels and may run past the viewport. */
private class RowDrag(val symbol: String, val from: Int, val rowHeight: Int, val startTop: Float) {
    var top by mutableFloatStateOf(startTop)
        private set

    /** Trails [top] by up to half a row, so comparing the two gives the direction of travel without following a shaky hand. */
    var anchor by mutableFloatStateOf(startTop)
        private set

    fun move(pixels: Float) {
        top += pixels
        anchor = anchor.coerceIn(top - rowHeight / 2f, top + rowHeight / 2f)
    }
}

/** The row just released, [offset] pixels from its slot. Every drop is a new instance, which restarts all rows at rest. */
private class RowDrop(val symbol: String, val offset: Float)

private fun List<String>.moved(from: Int, to: Int) = toMutableList().apply { add(to, removeAt(from)) }

private fun rowShift(index: Int, from: Int, to: Int, rowHeight: Int) = when (index) {
    in (from + 1)..to -> -rowHeight
    in to until from -> rowHeight
    else -> 0
}

/**
 * The watch list while editing. A drag previews the new order and saves it on release, so one drag is one write and a
 * cancelled drag changes nothing. This is a plain Column: a LazyColumn would dispose the held row and its gesture once
 * auto-scroll carries its slot off screen, and would jump on drop to keep its first visible key. [firstRow] is the row
 * to open at.
 */
@Composable
internal fun WatchListEditor(symbols: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier, firstRow: Int = 0) {
    val scroll = rememberScrollState(firstRow * with(LocalDensity.current) { RowHeight.roundToPx() })
    // The saved list reaches this composable a moment after a change; until then the rows keep the new order.
    var pending by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(symbols) { pending = null }
    val rows = pending ?: symbols
    val currentRows by rememberUpdatedState(rows)
    val save by rememberUpdatedState(onChange)
    var drag by remember { mutableStateOf<RowDrag?>(null) }
    var drop by remember { mutableStateOf<RowDrop?>(null) }
    fun change(next: List<String>) { pending = next; save(next) }

    /** The held row's top in list pixels, kept inside the list and the viewport, or no further out than where it was grabbed. */
    fun listTop(held: RowDrag): Float {
        val inView = held.top.coerceIn(minOf(held.startTop, 0f), maxOf(held.startTop, (scroll.viewportSize - held.rowHeight).toFloat()))
        return (inView + scroll.value).coerceIn(0f, (currentRows.lastIndex * held.rowHeight).toFloat())
    }
    fun slot(held: RowDrag) = (listTop(held) / held.rowHeight).roundToInt()
    fun release(held: RowDrag, dropped: Boolean) {
        val to = if (dropped) slot(held) else held.from
        drop = RowDrop(held.symbol, listTop(held) - to * held.rowHeight)
        drag = null
        if (to != held.from) change(currentRows.moved(held.from, to))
    }
    val target by remember { derivedStateOf { drag?.let { slot(it) } } }

    // Holding the row against the edge it is travelling towards scrolls the list. Only that edge: a row grabbed while it
    // already touches an edge must not start scrolling before it has moved.
    val edge by remember { derivedStateOf {
        val held = drag ?: return@derivedStateOf 0
        val reach = held.rowHeight / 2f
        when {
            held.top < held.anchor && held.top < reach -> -1
            held.top > held.anchor && held.top + held.rowHeight > scroll.viewportSize - reach -> 1
            else -> 0
        }
    } }
    LaunchedEffect(edge) {
        val rowHeight = drag?.rowHeight ?: return@LaunchedEffect
        val end = when (edge) { -1 -> 0; 1 -> scroll.maxValue; else -> return@LaunchedEffect }
        val distance = abs(end - scroll.value) / rowHeight.toFloat()
        scroll.animateScrollTo(end, tween((distance * 1000 / AutoScrollRowsPerSecond).roundToInt(), easing = LinearEasing))
    }

    val scheme = MaterialTheme.colorScheme
    val heldShape = MaterialTheme.shapes.small
    Column(modifier.verticalScroll(scroll)) {
        rows.forEachIndexed { index, symbol ->
            key(symbol, drop) {
                val held = drag?.takeIf { it.symbol == symbol }
                val shift = drag?.let { rowShift(index, it.from, target ?: it.from, it.rowHeight) } ?: 0
                val slide = remember { Animatable(drop?.takeIf { it.symbol == symbol }?.offset ?: 0f) }
                LaunchedEffect(shift) { slide.animateTo(shift.toFloat(), spring(stiffness = Spring.StiffnessMedium)) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = RowHeight).zIndex(if (held != null) 1f else 0f)
                        .graphicsLayer {
                            translationY = held?.let { listTop(it) - it.from * it.rowHeight } ?: slide.value
                            if (held != null) { shadowElevation = 6.dp.toPx(); shape = heldShape; clip = true }
                        }
                        .background(if (held != null) scheme.surfaceContainerHighest else Color.Transparent)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).heightIn(min = RowHeight).testTag("market-row-$symbol").pointerHoverIcon(PointerIcon.Hand)
                            .pointerInput(Unit) {
                                fun mine() = drag?.takeIf { it.symbol == symbol }
                                detectRowDrag(
                                    onStart = {
                                        if (drag == null) {
                                            val from = currentRows.indexOf(symbol)
                                            drag = RowDrag(symbol, from, size.height, (from * size.height - scroll.value).toFloat())
                                        }
                                    },
                                    onMove = { pixels -> mine()?.move(pixels) },
                                    onEnd = { dropped -> mine()?.let { release(it, dropped) } },
                                )
                            }
                            .semantics(mergeDescendants = true) {
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction("上移 $symbol") { change(rows.moved(index, index - 1)); true }.takeIf { index > 0 },
                                    CustomAccessibilityAction("下移 $symbol") { change(rows.moved(index, index + 1)); true }.takeIf { index < rows.lastIndex },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(RowHeight).testTag("market-drag-$symbol"), Alignment.Center) {
                            Icon(AppIcons.DragIndicator, "拖动排序", tint = scheme.onSurfaceVariant)
                        }
                        Text(symbol, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionIcon(AppIcons.RemoveCircle, "移除 $symbol", "market-remove-$symbol", tint = TradingColors.Loss) {
                        if (drag == null) change(currentRows - symbol)
                    }
                }
            }
        }
    }
}

/** A mouse drags from anywhere in the row; a finger only from the handle, because anywhere else it scrolls the list. */
private suspend fun PointerInputScope.detectRowDrag(onStart: () -> Unit, onMove: (Float) -> Unit, onEnd: (dropped: Boolean) -> Unit) {
    val handleWidth = RowHeight.toPx()
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
        onStart()
        onMove(travelled.y)
        onEnd(drag(down.id) { onMove(it.positionChange().y); it.consume() })
    }
}
