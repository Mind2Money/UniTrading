package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class IndicatorSettingsTest {
    private val custom = IndicatorSettings(listOf(
        IndicatorConfig("1", IndicatorKind.EXPMA, mapOf("period" to 30.0), mapOf("EXPMA" to "#FF0000")),
        IndicatorConfig("2", IndicatorKind.BOLL, mapOf("period" to 20.0, "width" to 2.5)),
        IndicatorConfig("3", IndicatorKind.RSI, visible = false),
    ))

    @Test fun savedIndicatorsSurviveRuntimeRestartIndependentlyOfTheme() = runTest {
        val store = MemorySnapshotStore()
        val first = TradingRuntime(store)
        assertEquals(IndicatorSettings(), first.indicators.settings.value)
        assertTrue(first.indicators.apply(custom))
        val reopened = TradingRuntime(store)
        assertEquals(custom, reopened.indicators.settings.value)
        assertEquals(ThemeSettings(), reopened.theme.settings.value)
        assertNull(store.read("atlas-demo"))
        assertEquals(custom, Json.decodeFromString<IndicatorSettings>(store.read("indicators-v1")!!))
    }

    @Test fun invalidStacksAndDiskFailureKeepTheSavedStack() = runTest {
        val store = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("disk full") }
        }
        val controller = IndicatorController(store)
        assertFailsWith<IllegalArgumentException> { controller.apply(IndicatorSettings(listOf(IndicatorConfig("1", IndicatorKind.MA), IndicatorConfig("1", IndicatorKind.RSI)))) }
        assertFailsWith<IllegalArgumentException> { controller.apply(IndicatorSettings(List(13) { IndicatorConfig("$it", IndicatorKind.MA) })) }
        assertFailsWith<IllegalArgumentException> { controller.apply(IndicatorSettings(listOf(IndicatorConfig("1", IndicatorKind.MA, mapOf("period" to 0.0))))) }
        assertFalse(controller.apply(custom))
        assertEquals(IndicatorSettings(), controller.settings.value)
        assertNotNull(controller.error.value)
    }

    @Test fun corruptOrUnknownSavedIndicatorsFallBackToAnEmptyStack() {
        for (saved in listOf("broken", """{"indicators":[{"id":"1","kind":"VWAP"}]}""", """{"indicators":[{"id":"1","kind":"RSI","params":{"period":0.0}}]}""")) {
            val store = MemorySnapshotStore().apply { write("indicators-v1", saved) }
            val controller = IndicatorController(store)
            assertEquals(IndicatorSettings(), controller.settings.value, saved)
            assertNotNull(controller.error.value, saved)
            assertEquals(saved, store.read("indicators-v1"), "fallback must not overwrite the stored value")
        }
    }
}
