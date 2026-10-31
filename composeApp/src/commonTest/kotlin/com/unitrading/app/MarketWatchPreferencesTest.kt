package com.unitrading.app

import com.unitrading.broker.Instrument
import kotlin.test.*

class MarketWatchPreferencesTest {
    private val spec = Instrument("@MES", "Micro S&P", "连续合约", 2, tickValueMicrosPerLot = 1, isContinuous = true)

    @Test fun orderRemovalAndEmptyListSurviveRestartWithoutMixingBrokers() {
        val store = MemorySnapshotStore()
        val prefs = MarketWatchPreferences(store)
        prefs.initialize("demo", listOf(spec, spec.copy(symbol = "@ES")), "@MES")
        prefs.update("demo", listOf("@ES", "@MES", "@ES"))
        prefs.update("live", listOf("@GC"))
        val restored = MarketWatchPreferences(store)
        assertEquals(listOf("@ES", "@MES"), restored.lists.value["demo"])
        assertEquals(listOf("@GC"), restored.lists.value["live"])
        restored.update("demo", emptyList())
        val empty = MarketWatchPreferences(store)
        empty.initialize("demo", listOf(spec), "@MES")
        assertTrue(empty.lists.value.getValue("demo").isEmpty())
    }

    @Test fun emptyStartupCatalogDoesNotPermanentlyInitializeAnEmptyList() {
        val prefs = MarketWatchPreferences(MemorySnapshotStore())
        prefs.initialize("demo", emptyList(), "@MES")
        assertNull(prefs.lists.value["demo"])
        prefs.initialize("demo", listOf(spec), "@MES")
        assertEquals(listOf("@MES"), prefs.lists.value["demo"])
    }
}
