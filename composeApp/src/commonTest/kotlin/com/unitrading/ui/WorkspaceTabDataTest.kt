package com.unitrading.ui

import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote
import kotlin.test.*

class WorkspaceTabDataTest {
    private val contract = Instrument("MESZ6", "Micro S&P", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000)

    @Test fun lastTradeAndSettlementGiveFormattedPriceAndChange() {
        val data = workspaceTabData("main", "MESZ6", contract, Quote(734700, 734725, 1, lastTrade = 734775, settlement = 735879), true)
        assertEquals("7,347.75", data.price)
        assertEquals("−0.15%", data.change)
        assertEquals(-1, data.direction)
        assertEquals("-1,234.50", groupPriceDigits("-1234.50"))
    }

    @Test fun missingOrDisconnectedTradesNeverInventLastPriceOrChange() {
        val quote = Quote(734700, 734725, 1, settlement = 735879)
        assertEquals("—", workspaceTabData("main", "MESZ6", contract, quote, true).price)
        assertEquals("—", workspaceTabData("main", "MESZ6", contract, quote, true).change)
        val disconnected = workspaceTabData("main", "MESZ6", contract, quote.copy(lastTrade = 734775), false)
        assertEquals("—", disconnected.price)
        assertEquals("—", disconnected.change)
    }

    @Test fun titleActionsFollowTheCurrentWorkspaceEvenIfDisplayedTabsAreUnchanged() {
        val slot = DesktopTitleBarSlot()
        val first = Any()
        val second = Any()
        val tabs = DesktopTitleTabs(listOf(WorkspaceTabData("main", "MES", "—", "—", 0)), "main", true, false)
        val events = mutableListOf<String>()
        slot.publish(first, tabs, { events += "old-select" }, { events += "old-detach" },
            { events += "old-close" }, { events += "old-reorder" })
        slot.publish(second, tabs, { events += "select:$it" }, { events += "detach:$it" },
            { events += "close:$it" }, { events += "reorder:${it.joinToString()}" })
        slot.clear(first) // A disposed previous workspace must not clear the new workspace's title row.
        slot.select("main"); slot.detach("main"); slot.close("main"); slot.reorder(listOf("main"))
        assertEquals(listOf("select:main", "detach:main", "close:main", "reorder:main"), events)
        assertEquals(tabs, slot.tabs)
        slot.clear(second)
        assertNull(slot.tabs)
    }
}
