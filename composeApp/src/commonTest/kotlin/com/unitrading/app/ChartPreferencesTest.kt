package com.unitrading.app

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChartPreferencesTest {
    @Test fun secondsFavoritesCustomAndLastIntervalSurviveRestartWithoutLosingLegacyMinutes() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15],"lastTimeframeMinutes":5}""") }
        val controller = ChartPreferencesController(store)
        assertTrue(controller.toggleFavorite(Timeframe.seconds(15)))
        assertTrue(controller.addCustom(Timeframe.seconds(7)))
        assertTrue(controller.setLastChart("tradovate", "MES", Timeframe.seconds(7)))
        val saved = ChartPreferencesController(store).preferences.value
        assertEquals(listOf(5, 15), saved.favoriteMinutes)
        assertEquals(listOf(15), saved.favoriteSeconds)
        assertEquals(Timeframe.seconds(7), saved.lastTimeframe)
        assertTrue(Timeframe.seconds(7) in saved.custom)
        assertTrue(controller.setLastChart("tradovate", null, Timeframe.M5))
        assertNull(ChartPreferencesController(store).preferences.value.lastTimeframeSeconds)
    }

    @Test fun indicatorPaneSizesPersistAndLegacyPreferencesUseDefaultSizes() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15]}""") }
        val controller = ChartPreferencesController(store)
        assertEquals(emptyMap(), controller.preferences.value.indicatorPaneFractions)
        assertTrue(controller.setIndicatorPaneFractions(mapOf("rsi" to .45f, "macd" to .15f)))
        assertEquals(mapOf("rsi" to .45f, "macd" to .15f), ChartPreferencesController(store).preferences.value.indicatorPaneFractions)
        assertEquals(listOf(5, 15), controller.preferences.value.favoriteMinutes)
        for (invalid in listOf(0f, -.1f, .76f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { controller.setIndicatorPaneFractions(mapOf("rsi" to invalid)) }
        }
    }
    @Test fun lastChartSymbolPerBrokerAndIntervalPersistAcrossRestart() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15]}""") }
        val first = ChartPreferencesController(store)
        assertNull(first.preferences.value.lastTimeframe, "older files open with the default interval")
        assertTrue(first.setLastChart("tradovate-demo-1", "@GC", Timeframe(60)))
        assertTrue(first.setLastChart("atlas-demo", "XAUUSD", Timeframe.M15))
        assertTrue(first.setLastChart("tradovate-demo-1", null, Timeframe(240)), "a pending restore keeps the saved symbol")
        val reopened = ChartPreferencesController(store).preferences.value
        assertEquals(mapOf("tradovate-demo-1" to "@GC", "atlas-demo" to "XAUUSD"), reopened.lastSymbols)
        assertEquals(Timeframe(240), reopened.lastTimeframe)
        assertEquals(listOf(5, 15), reopened.favoriteMinutes)
        assertFailsWith<IllegalArgumentException> { first.setLastChart("tradovate-demo-1", "", Timeframe(60)) }
        assertFailsWith<IllegalArgumentException> { first.apply(ChartPreferences(lastTimeframeMinutes = 0)) }
    }
    @Test fun favoritesAndCustomIntervalsPersistAcrossRestart() = runTest {
        val store = MemorySnapshotStore()
        val first = TradingRuntime(store)
        assertEquals(Timeframe.defaultFavorites, first.chartPreferences.preferences.value.favorites)
        assertTrue(first.chartPreferences.toggleFavorite(Timeframe(240)))
        assertTrue(first.chartPreferences.toggleFavorite(Timeframe.M1))
        assertTrue(first.chartPreferences.addCustom(Timeframe(7)))
        assertTrue(first.chartPreferences.addCustom(Timeframe(7)), "re-adding is a no-op")
        assertTrue(first.chartPreferences.addCustom(Timeframe.M15), "presets are never duplicated as custom intervals")
        val reopened = TradingRuntime(store)
        assertEquals(listOf(5, 15, 60, 240), reopened.chartPreferences.preferences.value.favoriteMinutes)
        assertEquals(listOf(7), reopened.chartPreferences.preferences.value.customMinutes)
        assertEquals(listOf(Timeframe(7)), reopened.chartPreferences.preferences.value.custom)
    }
    @Test fun legendFoldIsRememberedAcrossRestartAndOlderFilesStillLoad() = runTest {
        val store = MemorySnapshotStore()
        store.write("chart-v1", """{"favoriteMinutes":[5,15],"customMinutes":[]}""")
        val first = TradingRuntime(store)
        assertNull(first.chartPreferences.preferences.value.legendCollapsed, "a file saved before the legend was remembered keeps each layout's default")
        assertTrue(first.chartPreferences.setLegendCollapsed(true))
        val reopened = TradingRuntime(store).chartPreferences
        assertEquals(true, reopened.preferences.value.legendCollapsed)
        assertEquals(listOf(5, 15), reopened.preferences.value.favoriteMinutes)
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
        assertFalse(controller.addCustom(Timeframe(7)))
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
        for (minutes in listOf(2, 3, 10, 30, 45, 120, 180, 240)) assertTrue(controller.toggleFavorite(Timeframe(minutes)))
        assertEquals(ChartPreferences.MAX_FAVORITES, controller.preferences.value.favoriteMinutes.size)
        assertFalse(controller.toggleFavorite(Timeframe(360)))
        assertNotNull(controller.error.value)
    }
}
