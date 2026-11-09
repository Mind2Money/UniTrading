package com.unitrading.ui

import com.unitrading.app.DesktopLayoutPreferences
import kotlin.test.*

class DesktopPanelGeometryTest {
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
