package com.unitrading.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.*

class OrderLabelLayoutTest {
    @Test fun labelsStayAtTheRightEdgeAndAvoidNearbyLabelsAndLegend() {
        val plot = Rect(12f, 18f, 900f, 400f)
        val legend = Rect(12f, 18f, 220f, 110f)
        val occupied = mutableListOf(legend)
        for (y in listOf(18f, 100f, 101f, 102f, 399f)) {
            val box = assertNotNull(placeOrderLabel(Size(240f, 24f), y, plot, occupied, 3f))
            assertEquals(plot.right, box.right)
            assertTrue(box.top >= plot.top && box.bottom <= plot.bottom)
            assertTrue(occupied.none { it.overlaps(box) })
            occupied.add(box)
        }
    }

    @Test fun narrowPlotsMoveLabelsBelowTheLegendWithoutEnteringThePriceScale() {
        val plot = Rect(12f, 18f, 220f, 300f)
        val legend = Rect(12f, 18f, 210f, 100f)
        val box = assertNotNull(placeOrderLabel(Size(190f, 40f), 30f, plot, listOf(legend), 3f))
        assertTrue(box.top >= legend.bottom + 3f)
        assertEquals(plot.right, box.right)
        assertTrue(box.left >= plot.left)
        assertNull(placeOrderLabel(Size(230f, 40f), 30f, plot, emptyList(), 3f))
        assertNull(placeOrderLabel(Size(190f, 40f), 30f, plot, listOf(plot), 3f))
    }

    @Test fun aLabelSitsOnItsLine() {
        val box = assertNotNull(placeOrderLabel(Size(100f, 20f), 200f, Rect(0f, 0f, 500f, 400f), emptyList(), 3f))
        assertEquals(200f, box.center.y)
    }

    private val metrics = OrderTagMetrics(height = 20f, padding = 6f, button = 22f, groupGap = 4f, stackGap = 3f)
    private val scale = PriceScale(0.0, 400.0, 0f, 400f)
    private val plot = Rect(0f, 0f, 500f, 400f)
    private val close = LineAction.CloseDraft
    private val add = LineAction.SetDraftProtection(true, true)
    private val cells = listOf(
        LineCell(CellKind.TITLE, "ab"),
        LineCell(CellKind.ADD_TARGET, "TP", add, "add target"),
        LineCell(CellKind.ADD_STOP, "SL", add, "add stop"),
        LineCell(CellKind.VALUE, "1"),
        LineCell(CellKind.VALUE, "abc"),
        LineCell(CellKind.CLOSE, action = close, hint = "close"),
    )
    private fun line(price: Long, cells: List<LineCell> = this.cells, edit: EditableLine? = null) = OrderLine(price, cells, LineTone.BUY, edit = edit)
    private fun layout(vararg lines: OrderLine, area: Rect = plot, reserved: List<Rect> = emptyList()) =
        layoutOrderTags(lines.toList(), scale, area, reserved, metrics) { it.length * 10f }

    @Test fun cellsRunLeftToRightWithGapsOnlyBetweenGroups() {
        val tag = layout(line(200)).single()
        val widths = listOf(32f, 32f, 32f, 22f, 42f, 22f)
        assertEquals(widths, tag.cells.map { it.bounds.width })
        assertEquals(plot.right, tag.bounds.right)
        assertEquals(tag.bounds.left, tag.cells.first().bounds.left)
        assertEquals(tag.bounds.right, tag.cells.last().bounds.right)
        assertEquals(200f, tag.bounds.center.y, "The tag sits on its line")
        fun gap(i: Int) = tag.cells[i + 1].bounds.left - tag.cells[i].bounds.right
        assertEquals(listOf(4f, 4f, 4f, 0f, 0f), (0..4).map(::gap))
    }

    @Test fun tagsWithoutRoomGiveUpTheAddButtonsThenQuantityBeforeDisappearing() {
        fun shown(width: Float) = layout(line(200), area = Rect(0f, 0f, width, 400f)).singleOrNull()?.cells?.map { it.cell.kind }
        assertEquals(cells.map { it.kind }, shown(500f))
        assertEquals(listOf(CellKind.TITLE, CellKind.VALUE, CellKind.VALUE, CellKind.CLOSE), shown(130f))
        assertEquals(listOf(CellKind.TITLE, CellKind.CLOSE), shown(60f))
        assertNull(shown(40f))
    }

    @Test fun nearbyTagsStackAndKeepTheirLineAndOffPlotLinesGetNone() {
        val tags = layout(line(200), line(202), line(500))
        assertEquals(2, tags.size, "The line above the plot has no tag")
        assertFalse(tags[0].bounds.overlaps(tags[1].bounds))
        assertEquals(scale.y(202), tags[1].lineY)
        assertNotEquals(tags[1].lineY, tags[1].bounds.center.y, "Moved aside, so the drawing adds a leader")
    }

    @Test fun buttonsAreFoundAndFingersGetATallerTarget() {
        val tags = layout(line(200))
        val button = tags.single().cells.last().bounds
        assertEquals("close", tags.buttonAt(button.center)?.cell?.hint)
        assertNull(tags.buttonAt(tags.single().cells[3].bounds.center), "A quantity box does nothing")
        val below = Offset(button.center.x, button.bottom + 6f)
        assertNull(tags.buttonAt(below))
        assertEquals("close", tags.buttonAt(below, slop = 8f)?.cell?.hint)
    }

    @Test fun aDragPicksTheTagUnderThePointerOrTheNearestLine() {
        val first = EditableLine.Draft(com.unitrading.broker.OrderRequest("X", com.unitrading.broker.Side.BUY, com.unitrading.broker.OrderKind.LIMIT, 100, 100))
        val second = EditableLine.Draft(com.unitrading.broker.OrderRequest("X", com.unitrading.broker.Side.SELL, com.unitrading.broker.OrderKind.LIMIT, 100, 300))
        val lines = listOf(line(100, edit = first), line(300, edit = second))
        val tags = layout(*lines.toTypedArray())
        assertEquals(LineGrab(first, true), grabAt(tags[0].bounds.center, tags, lines, scale, 22f))
        assertEquals(LineGrab(second, false), grabAt(Offset(20f, scale.y(300) + 10f), tags, lines, scale, 22f))
        assertNull(grabAt(Offset(20f, scale.y(200)), tags, lines, scale, 22f))
    }

    @Test fun aContextMenuNeedsALineWithSomethingToOffer() {
        val silent = line(100, listOf(LineCell(CellKind.TITLE, "ab")))
        val active = line(300)
        val lines = listOf(silent, active)
        val tags = layout(silent, active)
        assertEquals(active, lineAt(tags[1].bounds.center, tags, lines, scale, 8f))
        assertEquals(active, lineAt(Offset(20f, scale.y(300) + 4f), tags, lines, scale, 8f))
        assertNull(lineAt(Offset(20f, scale.y(300) + 20f), tags, lines, scale, 8f), "Too far from the line")
        assertNull(lineAt(Offset(20f, scale.y(100)), tags, lines, scale, 8f), "Nothing to offer")
    }
}
