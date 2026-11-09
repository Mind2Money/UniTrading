package com.unitrading.ui

import com.unitrading.app.DesktopLayoutPreferences
import kotlin.test.*

class DesktopPanelGeometryTest {
    @Test fun orderFlowStartsAtHalfOfTheContentWidthAndKeepsADraggableRange() {
        val half = orderFlowPanelDimension(1008f, .5f)
        assertEquals(500f, half.value)
        assertEquals(240f, half.min)
        assertEquals(760f, half.max)
        assertEquals(250f, orderFlowPanelDimension(1008f, .25f).value)
        assertEquals(750f, orderFlowPanelDimension(1008f, .75f).value)
        assertEquals(half.min, orderFlowPanelDimension(1008f, 0f).value)
        assertEquals(half.max, orderFlowPanelDimension(1008f, 1f).value)
    }

    @Test fun orderFlowClampsBothSidesWhenTheWindowShrinksAndRestoresTheFraction() {
        val fraction = .75f
        val original = orderFlowPanelDimension(1008f, fraction)
        for (width in listOf(1500f, 1008f, 488f, 400f, 8f, 0f)) {
            val available = (width - PANEL_DIVIDER).coerceAtLeast(0f)
            val flow = orderFlowPanelDimension(width, fraction)
            val chart = available - flow.value
            assertTrue(flow.value in flow.min..flow.max, "Order-flow width is bounded at $width dp")
            assertTrue(chart in flow.min..flow.max, "The chart keeps the same minimum at $width dp")
            assertTrue(flow.value >= 0f && chart >= 0f)
            if (available < 480f) assertEquals(available / 2f, flow.value)
        }
        assertEquals(original, orderFlowPanelDimension(1008f, fraction))
    }

    @Test fun invalidOrderFlowFractionsCannotEscapeMeasuredBounds() {
        assertEquals(240f, orderFlowPanelDimension(1008f, -1f).value)
        assertEquals(760f, orderFlowPanelDimension(1008f, 2f).value)
        assertEquals(500f, orderFlowPanelDimension(1008f, Float.NaN).value)
    }

    @Test fun orderFlowKeepsTwoCompactHalvesWithoutChangingDefaultSidePanels() {
        for ((width, wide) in listOf(900f to false, 1240f to true, 1600f to true)) {
            val preferred = DesktopLayoutPreferences()
            val before = desktopPanelGeometry(width, 650f, wide, preferred)
            val open = desktopPanelGeometry(width, 650f, wide, preferred, orderFlowOpen = true)
            assertEquals(before.sidebar.value, open.sidebar.value, "Default market width stays in place at $width dp")
            assertEquals(before.order.value, open.order.value)
            assertEquals(before.accounts.value, open.accounts.value)
            assertEquals(before.terminal.value, open.terminal.value)
            val huge = desktopPanelGeometry(width, 650f, wide,
                DesktopLayoutPreferences(sidebarWidth = 4000f, orderWidth = 4000f), orderFlowOpen = true)
            val gaps = PANEL_DIVIDER * if (wide) 2 else 1
            assertTrue(width - huge.sidebar.value - huge.order.value - gaps - PANEL_RAIL >= 488f)
        }
    }

    @Test fun oversizedPreferencesKeepTheChartAndMarketUsable() {
        val huge = DesktopLayoutPreferences(4000f, 4000f, 4000f, 4000f)
        for ((width, wide) in listOf(900f to false, 1240f to true, 1600f to true)) {
            val layout = desktopPanelGeometry(width, 650f, wide, huge)
            val gaps = PANEL_DIVIDER * if (wide) 2 else 1
            assertTrue(width - layout.sidebar.value - layout.order.value - gaps - PANEL_RAIL >= 320f)
            assertTrue(650f - layout.terminal.value - PANEL_DIVIDER >= 240f)
            assertTrue(650f - layout.accounts.value - PANEL_DIVIDER >= 140f)
            listOf(layout.sidebar, layout.order, layout.accounts, layout.terminal).forEach {
                assertTrue(it.value in it.min..it.max)
            }
        }
    }

    @Test fun foldedPanelsLeaveTheirWidthToTheChartAndKeepTheirSizes() {
        val preferred = DesktopLayoutPreferences(450f, 500f, 300f, 300f)
        val noMarket = desktopPanelGeometry(1300f, 900f, true, preferred.copy(marketOpen = false))
        assertEquals(0f, noMarket.sidebar.value)
        assertEquals(500f, noMarket.order.value, "The order panel may now use the room the market panel left")
        val noOrder = desktopPanelGeometry(1300f, 900f, true, preferred.copy(orderOpen = false))
        assertEquals(0f, noOrder.order.value)
        assertEquals(450f, noOrder.sidebar.value)
        val tight = desktopPanelGeometry(1300f, 900f, true, preferred)
        assertTrue(tight.order.value < 500f, "With both open the chart keeps its minimum first")
        assertEquals(0f, desktopPanelGeometry(1000f, 900f, false, preferred).order.value, "A narrow window has no order column")
        assertEquals(preferred.copy(marketOpen = false, orderOpen = false, terminalOpen = false).sidebarWidth, 450f)
    }

    @Test fun smallWindowsClampWithoutOverwritingPreferredDimensions() {
        val preferred = DesktopLayoutPreferences(450f, 500f, 600f, 600f)
        val small = desktopPanelGeometry(1240f, 300f, true, preferred)
        assertTrue(small.order.value < preferred.orderWidth)
        assertTrue(small.accounts.value < preferred.accountsHeight)
        assertTrue(small.terminal.value < preferred.terminalHeight)
        val large = desktopPanelGeometry(1800f, 1000f, true, preferred)
        assertEquals(preferred.orderWidth, large.order.value)
        assertEquals(preferred.accountsHeight, large.accounts.value)
        assertEquals(preferred.terminalHeight, large.terminal.value)
    }
}
