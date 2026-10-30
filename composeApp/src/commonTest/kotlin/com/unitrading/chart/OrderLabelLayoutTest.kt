package com.unitrading.chart

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
}
