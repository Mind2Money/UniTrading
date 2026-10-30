package com.unitrading.ui

import com.unitrading.app.DesktopLayoutPreferences
import kotlin.test.*

class DesktopPanelGeometryTest {
    @Test fun oversizedPreferencesKeepTheChartAndMarketUsable() {
        val huge = DesktopLayoutPreferences(4000f, 4000f, 4000f, 4000f)
        for ((width, wide) in listOf(900f to false, 1240f to true, 1600f to true)) {
            val layout = desktopPanelGeometry(width, 650f, wide, huge)
            val gaps = PANEL_DIVIDER * if (wide) 2 else 1
            assertTrue(width - layout.sidebar.value - layout.order.value - gaps >= 320f)
            assertTrue(650f - layout.terminal.value - PANEL_DIVIDER >= 240f)
            assertTrue(650f - layout.accounts.value - PANEL_DIVIDER >= 140f)
            listOf(layout.sidebar, layout.order, layout.accounts, layout.terminal).forEach {
                assertTrue(it.value in it.min..it.max)
            }
        }
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
