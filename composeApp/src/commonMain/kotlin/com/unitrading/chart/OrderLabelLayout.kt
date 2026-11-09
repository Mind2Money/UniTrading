package com.unitrading.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsPropertyKey
import kotlin.math.abs

/** Keep labels beside the price scale, centred on their line. Nearby prices stack vertically and connect back to their actual line. */
internal fun placeOrderLabel(size: Size, lineY: Float, plot: Rect, occupied: List<Rect>, gap: Float): Rect? {
    if (size.width > plot.width || size.height > plot.height) return null
    val x = plot.right - size.width
    val preferred = (lineY - size.height / 2).coerceIn(plot.top, plot.bottom - size.height)
    val candidates = listOf(preferred, plot.top, plot.bottom - size.height) + occupied.flatMap {
        listOf(it.bottom + gap, it.top - size.height - gap)
    }
    return candidates.asSequence().filter { it >= plot.top && it + size.height <= plot.bottom }
        .map { Rect(Offset(x, it), size) }
        .filter { box -> occupied.none { it.overlaps(box.inflate(gap / 2)) } }
        .minByOrNull { abs(it.top - preferred) }
}

/** Pixel sizes of a line's tag: cell [height], text [padding], the square [button] width, and the gaps between groups and stacked tags. */
internal class OrderTagMetrics(val height: Float, val padding: Float, val button: Float, val groupGap: Float, val stackGap: Float)

internal data class PlacedCell(val cell: LineCell, val bounds: Rect)

/** A tag where it landed. [lineY] is its line, which a tag moved aside by a neighbour no longer sits on. */
internal data class PlacedTag(val line: OrderLine, val lineY: Float, val bounds: Rect, val cells: List<PlacedCell>)

private fun OrderTagMetrics.width(cell: LineCell, textWidth: (String) -> Float) =
    if (cell.kind == CellKind.CLOSE) button else textWidth(cell.text) + padding * 2

private fun OrderTagMetrics.width(cells: List<LineCell>, textWidth: (String) -> Float) =
    cells.sumOf { width(it, textWidth).toDouble() }.toFloat() + groupGap * (cells.distinctBy { it.kind.group }.size - 1)

private fun CellKind.isAdd() = this == CellKind.ADD_TARGET || this == CellKind.ADD_STOP

/** The cells that fit [available]: the add buttons go first, then quantity and type. With no room for the rest the tag is left out. */
private fun OrderTagMetrics.fitting(cells: List<LineCell>, available: Float, textWidth: (String) -> Float) =
    sequenceOf(cells, cells.filterNot { it.kind.isAdd() }, cells.filterNot { it.kind.isAdd() || it.kind == CellKind.VALUE })
        .firstOrNull { width(it, textWidth) <= available }

/** Lays the tags out over the plot in drawing order, keeping clear of [reserved] and of each other. Lines off the plot get none. */
internal fun layoutOrderTags(lines: List<OrderLine>, scale: PriceScale, plot: Rect, reserved: List<Rect>, metrics: OrderTagMetrics, textWidth: (String) -> Float): List<PlacedTag> {
    val occupied = reserved.toMutableList()
    return lines.mapNotNull { line ->
        val y = scale.y(line.price)
        if (y !in plot.top..plot.bottom) return@mapNotNull null
        val cells = metrics.fitting(line.cells, plot.width, textWidth) ?: return@mapNotNull null
        val bounds = placeOrderLabel(Size(metrics.width(cells, textWidth), metrics.height), y, plot, occupied, metrics.stackGap) ?: return@mapNotNull null
        occupied.add(bounds)
        var x = bounds.left
        var group = cells.first().kind.group
        PlacedTag(line, y, bounds, cells.map { cell ->
            if (cell.kind.group != group) { x += metrics.groupGap; group = cell.kind.group }
            val right = x + metrics.width(cell, textWidth)
            PlacedCell(cell, Rect(x, bounds.top, right, bounds.bottom)).also { x = right }
        })
    }
}

/** The button under [point]; [slop] makes it taller for fingers. The tag drawn last wins a tie, as it is on top. */
internal fun List<PlacedTag>.buttonAt(point: Offset, slop: Float = 0f): PlacedCell? = asReversed().firstNotNullOfOrNull { tag ->
    tag.cells.firstOrNull { it.cell.action != null && point.x >= it.bounds.left && point.x < it.bounds.right && point.y >= it.bounds.top - slop && point.y < it.bounds.bottom + slop }
}

internal data class LineGrab(val line: EditableLine, val onTag: Boolean)

/** What a drag from [point] picks up: an editable line's tag, or the nearest editable line within [tolerance]. The line drawn last wins a tie. */
internal fun grabAt(point: Offset, tags: List<PlacedTag>, lines: List<OrderLine>, scale: PriceScale, tolerance: Float): LineGrab? {
    tags.lastOrNull { it.line.edit != null && it.bounds.contains(point) }?.let { return LineGrab(it.line.edit!!, true) }
    val nearest = lines.asReversed().mapNotNull { it.edit }.minByOrNull { abs(scale.y(it.price) - point.y) } ?: return null
    return LineGrab(nearest, false).takeIf { abs(scale.y(nearest.price) - point.y) < tolerance }
}

/** The line a context menu opened at [point] belongs to: the tag under the pointer, or a line within [tolerance], if it has anything to offer. */
internal fun lineAt(point: Offset, tags: List<PlacedTag>, lines: List<OrderLine>, scale: PriceScale, tolerance: Float): OrderLine? {
    val line = tags.lastOrNull { it.bounds.contains(point) }?.line
        ?: lines.asReversed().minByOrNull { abs(scale.y(it.price) - point.y) }?.takeIf { abs(scale.y(it.price) - point.y) < tolerance }
    return line?.takeIf { candidate -> candidate.cells.any { it.action != null } }
}

/** Where each tag button sits, by its hint. The tags are canvas drawing, so nothing else tells a test where to press. */
internal val OrderTagButtons = SemanticsPropertyKey<Map<String, Rect>>("OrderTagButtons")
