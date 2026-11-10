package com.unitrading.app

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChartPreferencesTest {
    @Test fun indicatorPaneSizesPersistAndLegacyPreferencesUseDefaultSizes() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15]}""") }
        val controller = ChartPreferencesController(store)
        assertEquals(emptyMap(), controller.preferences.value.indicatorPaneFractions)
        assertTrue(controller.setIndicatorPaneFractions(mapOf("rsi" to .45f, "macd" to .15f)))
        assertEquals(mapOf("rsi" to .45f, "macd" to .15f), ChartPreferencesController(store).preferences.value.indicatorPaneFractions)
        assertEquals(listOf(Timeframe.M5, Timeframe.M15), controller.preferences.value.favorites)
        for (invalid in listOf(0f, -.1f, .76f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { controller.setIndicatorPaneFractions(mapOf("rsi" to invalid)) }
        }
    }
    @Test fun lastChartSymbolPerBrokerAndIntervalPersistAcrossRestart() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15]}""") }
        val first = ChartPreferencesController(store)
        assertNull(first.preferences.value.lastTimeframe, "older files open with the default interval")
        assertTrue(first.setLastChart("tradovate-demo-1", "@GC", Timeframe.ofMinutes(60)))
        assertTrue(first.setLastChart("atlas-demo", "XAUUSD", Timeframe.M15))
        assertTrue(first.setLastChart("tradovate-demo-1", null, Timeframe.ofMinutes(240)), "a pending restore keeps the saved symbol")
        val reopened = ChartPreferencesController(store).preferences.value
        assertEquals(mapOf("tradovate-demo-1" to "@GC", "atlas-demo" to "XAUUSD"), reopened.lastSymbols)
        assertEquals(Timeframe.ofMinutes(240), reopened.lastTimeframe)
        assertEquals(listOf(Timeframe.M5, Timeframe.M15), reopened.favorites)
        assertFailsWith<IllegalArgumentException> { first.setLastChart("tradovate-demo-1", "", Timeframe.ofMinutes(60)) }
        assertFailsWith<IllegalArgumentException> { first.apply(ChartPreferences(lastTimeframeMinutes = 0)) }
        assertFailsWith<IllegalArgumentException> { first.apply(ChartPreferences(lastTimeframeSeconds = 0)) }
    }
    @Test fun chartWindowsPersistWithASymbolPerBrokerAndTheirInterval() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15],"lastSymbols":{"tradovate-demo-1":"@GC"},"lastTimeframeSeconds":300}""") }
        val first = ChartPreferencesController(store)
        assertTrue(first.preferences.value.extraCharts.isEmpty(), "a file from before chart windows opens with the main chart alone")
        assertTrue(first.setExtraCharts("tradovate-demo-1", listOf(Triple("c1", "@ES", Timeframe.ofSeconds(30)), Triple("c2", "@NQ", Timeframe.H1))))
        // The same windows at another broker show that broker's symbols; the first broker's are kept for when it is back.
        assertTrue(first.setExtraCharts("atlas-demo", listOf(Triple("c1", "XAUUSD", Timeframe.M15), Triple("c2", "EURUSD", Timeframe.H1))))
        val reopened = ChartPreferencesController(store).preferences.value
        assertEquals(listOf(
            SavedChart("c1", mapOf("tradovate-demo-1" to "@ES", "atlas-demo" to "XAUUSD"), 900),
            SavedChart("c2", mapOf("tradovate-demo-1" to "@NQ", "atlas-demo" to "EURUSD"), 3_600),
        ), reopened.extraCharts)
        assertEquals(mapOf("tradovate-demo-1" to "@GC"), reopened.lastSymbols, "the main chart's symbol is saved apart")
        assertEquals(Timeframe.M5, reopened.lastTimeframe)

        // A closed window is forgotten, with the symbols it showed anywhere.
        assertTrue(first.setExtraCharts("atlas-demo", listOf(Triple("c2", "EURUSD", Timeframe.H1))))
        assertEquals(listOf("c2"), ChartPreferencesController(store).preferences.value.extraCharts.map { it.id })
        val base = first.preferences.value
        val tooMany = (1..ChartPreferences.MAX_EXTRA_CHARTS + 1).map { SavedChart("c$it", mapOf("atlas-demo" to "EURUSD"), 60) }
        for (invalid in listOf(tooMany, listOf(SavedChart("c1", timeframeSeconds = 60), SavedChart("c1", timeframeSeconds = 300)), listOf(SavedChart("", timeframeSeconds = 60)),
            listOf(SavedChart("c1", timeframeSeconds = 0)), listOf(SavedChart("c1", mapOf("atlas-demo" to ""), 60)))) {
            assertFailsWith<IllegalArgumentException> { first.apply(base.copy(extraCharts = invalid)) }
        }
        store.write("chart-v1", """{"extraCharts":[{"id":"c1","timeframeSeconds":0}]}""")
        val broken = ChartPreferencesController(store)
        assertNotNull(broken.error.value)
        assertEquals(ChartPreferences(), broken.preferences.value)
    }

    @Test fun favoritesAndCustomIntervalsPersistAcrossRestart() = runTest {
        val store = MemorySnapshotStore()
        val first = TradingRuntime(store)
        assertEquals(Timeframe.defaultFavorites, first.chartPreferences.preferences.value.favorites)
        assertTrue(first.chartPreferences.toggleFavorite(Timeframe.ofMinutes(240)))
        assertTrue(first.chartPreferences.toggleFavorite(Timeframe.M1))
        assertTrue(first.chartPreferences.addCustom(Timeframe.ofMinutes(7)))
        assertTrue(first.chartPreferences.addCustom(Timeframe.ofMinutes(7)), "re-adding is a no-op")
        assertTrue(first.chartPreferences.addCustom(Timeframe.M15), "presets are never duplicated as custom intervals")
        val reopened = TradingRuntime(store)
        assertEquals(listOf(5, 15, 60, 240).map(Timeframe::ofMinutes), reopened.chartPreferences.preferences.value.favorites)
        assertEquals(listOf(Timeframe.ofMinutes(7)), reopened.chartPreferences.preferences.value.custom)
    }
    @Test fun secondIntervalsAreSavedBesideMinuteOnesAndAFileFromBeforeThemStillLoads() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,240],"customMinutes":[7],"lastTimeframeMinutes":240}""") }
        val first = ChartPreferencesController(store)
        assertEquals(listOf(Timeframe.M5, Timeframe.ofMinutes(240)), first.preferences.value.favorites, "minutes saved by an older version")
        assertEquals(listOf(Timeframe.ofMinutes(7)), first.preferences.value.custom)
        assertEquals(Timeframe.ofMinutes(240), first.preferences.value.lastTimeframe)
        assertTrue(first.toggleFavorite(Timeframe.ofSeconds(30)))
        assertTrue(first.addCustom(Timeframe.ofSeconds(45)))
        assertTrue(first.addCustom(Timeframe.ofSeconds(30)), "a preset is not added as a custom interval")
        assertTrue(first.setLastChart("atlas-demo", "EURUSD", Timeframe.ofSeconds(5)))
        val reopened = ChartPreferencesController(store).preferences.value
        assertEquals(listOf(Timeframe.M5, Timeframe.ofMinutes(240), Timeframe.ofSeconds(30)), reopened.favorites)
        assertEquals(listOf(Timeframe.ofMinutes(7), Timeframe.ofSeconds(45)), reopened.custom)
        assertEquals(Timeframe.ofSeconds(5), reopened.lastTimeframe)
        assertTrue(first.toggleFavorite(Timeframe.ofSeconds(30)))
        assertEquals(listOf(Timeframe.M5, Timeframe.ofMinutes(240)), ChartPreferencesController(store).preferences.value.favorites)
    }
    @Test fun legendFoldIsRememberedAcrossRestartAndOlderFilesStillLoad() = runTest {
        val store = MemorySnapshotStore()
        store.write("chart-v1", """{"favoriteMinutes":[5,15],"customMinutes":[]}""")
        val first = TradingRuntime(store)
        assertNull(first.chartPreferences.preferences.value.legendCollapsed, "a file saved before the legend was remembered keeps each layout's default")
        assertTrue(first.chartPreferences.setLegendCollapsed(true))
        val reopened = TradingRuntime(store).chartPreferences
        assertEquals(true, reopened.preferences.value.legendCollapsed)
        assertEquals(listOf(Timeframe.M5, Timeframe.M15), reopened.preferences.value.favorites)
        assertTrue(reopened.setLegendCollapsed(false))
        assertEquals(false, ChartPreferencesController(store).preferences.value.legendCollapsed)
    }
    @Test fun invalidOrCorruptPreferencesFallBackToDefaults() = runTest {
        val controller = ChartPreferencesController(object : SnapshotStore {
            override fun read(key: String) = "{bad"
            override fun write(key: String, value: String) { error("disk full") }
        })
        assertEquals(ChartPreferences(), controller.preferences.value)
        assertNotNull(controller.error.value)
        assertFailsWith<IllegalArgumentException> { controller.apply(ChartPreferences(favoriteMinutes = listOf(0))) }
        assertFailsWith<IllegalArgumentException> { controller.apply(ChartPreferences(favoriteMinutes = listOf(5, 5))) }
        assertFailsWith<IllegalArgumentException> { controller.apply(ChartPreferences(favoriteMinutes = listOf(Int.MAX_VALUE))) }
        assertFailsWith<IllegalArgumentException> { controller.apply(ChartPreferences(favoriteSeconds = listOf(30, 30))) }
        assertFailsWith<IllegalArgumentException> { controller.apply(ChartPreferences(customSeconds = listOf(Timeframe.MAX_SECONDS + 1))) }
        assertFalse(controller.addCustom(Timeframe.ofMinutes(7)))
        assertEquals(ChartPreferences(), controller.preferences.value)
    }
    @Test fun favoritesAreCappedAndPreferencesAreReadLazily() = runTest {
        val reads = mutableListOf<String>()
        val runtime = TradingRuntime(object : SnapshotStore {
            override fun read(key: String): String? { reads += key; return null }
            override fun write(key: String, value: String) {}
        })
        assertEquals(listOf("theme-v1"), reads)
        val controller = runtime.chartPreferences
        assertEquals(listOf("theme-v1", "chart-v1"), reads)
        for (minutes in listOf(2, 3, 10, 30, 45, 120, 180, 240)) assertTrue(controller.toggleFavorite(Timeframe.ofMinutes(minutes)))
        assertEquals(ChartPreferences.MAX_FAVORITES, controller.preferences.value.favorites.size)
        assertFalse(controller.toggleFavorite(Timeframe.ofMinutes(360)))
        assertNotNull(controller.error.value)
    }
}
