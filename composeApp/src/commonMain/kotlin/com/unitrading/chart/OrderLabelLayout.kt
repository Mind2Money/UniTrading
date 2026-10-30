package com.unitrading.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/** Keep labels beside the price scale. Nearby prices stack vertically and connect back to their actual line. */
internal fun placeOrderLabel(size: Size, lineY: Float, plot: Rect, occupied: List<Rect>, gap: Float): Rect? {
    if (size.width > plot.width || size.height > plot.height) return null
    val x = plot.right - size.width
    val preferred = (lineY - size.height - gap).coerceIn(plot.top, plot.bottom - size.height)
    val candidates = listOf(preferred, plot.top, plot.bottom - size.height) + occupied.flatMap {
        listOf(it.bottom + gap, it.top - size.height - gap)
    }
    return candidates.asSequence().filter { it >= plot.top && it + size.height <= plot.bottom }
        .map { Rect(Offset(x, it), size) }
        .filter { box -> occupied.none { it.overlaps(box.inflate(gap / 2)) } }
        .minByOrNull { abs(it.top - preferred) }
}
