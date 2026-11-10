package com.unitrading.app

import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
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

    @Test fun historyIsReadAheadForTheShownIntervalFirstThenTheShownSymbolThenTheRest() {
        val starred = listOf(Timeframe.H1, Timeframe.M1, Timeframe.M5)
        assertEquals(
            listOf("@ES" to Timeframe.M5, "@MES" to Timeframe.M5, "@MES" to Timeframe.M1, "@MES" to Timeframe.H1, "@ES" to Timeframe.M1, "@ES" to Timeframe.H1),
            historyPreloadSeries(listOf("@ES", "@MES"), "@MES", Timeframe.M5, starred))
        // An interval that is not starred still leads; a chart outside the list adds no series of its own.
        assertEquals(listOf("@ES" to Timeframe.M15, "@ES" to Timeframe.M1),
            historyPreloadSeries(listOf("@ES"), "MESZ6", Timeframe.M15, listOf(Timeframe.M1)))
        assertTrue(historyPreloadSeries(emptyList(), "@MES", Timeframe.M5, starred).isEmpty())
    }

    @Test fun emptyStartupCatalogDoesNotPermanentlyInitializeAnEmptyList() {
        val prefs = MarketWatchPreferences(MemorySnapshotStore())
        prefs.initialize("demo", emptyList(), "@MES")
        assertNull(prefs.lists.value["demo"])
        prefs.initialize("demo", listOf(spec), "@MES")
        assertEquals(listOf("@MES"), prefs.lists.value["demo"])
    }
}
