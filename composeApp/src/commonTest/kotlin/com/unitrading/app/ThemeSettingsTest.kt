package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ThemeSettingsTest {
    @Test fun savedColorsSurviveRuntimeRestartWithoutAddingDemoBrokers() = runTest {
        val store = MemorySnapshotStore()
        val first = TradingRuntime(store)
        assertTrue(first.registry.plugins.isEmpty())
        assertTrue(first.brokers.isEmpty())
        val custom = ThemeSettings(ThemePreset.LIGHT, mapOf("PAGE" to "#EEEEEE", "UP" to "#123456", "PROFIT" to "#654321"))
        assertTrue(first.theme.apply(custom))
        val reopened = TradingRuntime(store)
        assertEquals(custom, reopened.theme.settings.value)
        assertTrue(reopened.registry.plugins.isEmpty())
        assertNull(store.read("atlas-demo"))
    }
    @Test fun tradingVPresetRoundTripsThroughTheStore() = runTest {
        val store = MemorySnapshotStore()
        assertTrue(ThemeController(store).apply(ThemeSettings(ThemePreset.TRADINGV, mapOf("GRID" to "#222222"))))
        val reopened = ThemeController(store).settings.value
        assertEquals(ThemePreset.TRADINGV, reopened.preset)
        assertEquals("#222222", reopened.colors["GRID"])
    }
    @Test fun invalidColorsAndDiskFailureDoNotReplaceSavedTheme() = runTest {
        val store = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("disk full") }
        }
        val controller = ThemeController(store)
        assertFailsWith<IllegalArgumentException> { controller.apply(ThemeSettings(colors = mapOf("PAGE" to "#GG00FF"))) }
        assertFalse(controller.apply(ThemeSettings(ThemePreset.LIGHT)))
        assertEquals(ThemeSettings(), controller.settings.value)
        assertNotNull(controller.error.value)
    }
    @Test fun corruptThemeDoesNotBlockStartupOrReadOldDemoSnapshots() = runTest {
        val reads = mutableListOf<String>()
        val store = object : SnapshotStore {
            override fun read(key: String): String { reads += key; return "broken" }
            override fun write(key: String, value: String) = error("Must not overwrite")
        }
        val runtime = TradingRuntime(store)
        // The analytics cache is read in the background to trim it; a broken one is left as it is.
        runtime.analyticsTrim.join()
        assertEquals(listOf("theme-v1", "analytics-trades-v1"), reads)
        assertNull(runtime.startupError.value)
        assertNotNull(runtime.theme.error.value)
    }
}
