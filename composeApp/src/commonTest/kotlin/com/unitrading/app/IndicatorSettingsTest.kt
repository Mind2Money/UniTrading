package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.DEFAULT_INDICATOR_GUIDE_COLOR
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class IndicatorSettingsTest {
    private val custom = IndicatorSettings(listOf(
        IndicatorConfig("1", IndicatorKind.EXPMA, mapOf("period" to 30.0), mapOf("EXPMA" to "#FF0000")),
        IndicatorConfig("2", IndicatorKind.BOLL, mapOf("period" to 20.0, "width" to 2.5)),
        IndicatorConfig("3", IndicatorKind.RSI, visible = false, referenceLevels = listOf(25.5, 50.0, 75.25),
            referenceColors = mapOf("25.5" to "#123456", "75.25" to "#ABCDEF")),
        IndicatorConfig("4", IndicatorKind.RSI, referenceLevels = emptyList()),
        IndicatorConfig("5", IndicatorKind.RSI, referenceColors = mapOf("30" to "#FEDCBA")),
    ))

    @Test fun savedIndicatorsSurviveRuntimeRestartIndependentlyOfTheme() = runTest {
        val store = MemorySnapshotStore()
        val first = TradingRuntime(store)
        assertEquals(IndicatorSettings(), first.indicators.settings.value)
        assertTrue(first.indicators.apply(custom))
        val reopened = TradingRuntime(store)
        assertEquals(custom, reopened.indicators.settings.value)
        val rsi = reopened.indicators.settings.value.indicators[2]
        assertEquals("#123456", rsi.guideColor(25.5))
        assertEquals(DEFAULT_INDICATOR_GUIDE_COLOR, rsi.guideColor(50.0))
        assertEquals("#ABCDEF", rsi.guideColor(75.25))
        assertEquals(ThemeSettings(), reopened.theme.settings.value)
        assertNull(store.read("atlas-demo"))
        assertEquals(custom, Json.decodeFromString<IndicatorSettings>(store.read("indicators-v1")!!))
    }

    @Test fun oldSettingsKeepDefaultGuidesAndEmptyLevelsStayDisabledAfterRestart() = runTest {
        val original = """{"indicators":[{"id":"rsi","kind":"RSI"},{"id":"kdj","kind":"KDJ"}]}"""
        val store = MemorySnapshotStore().apply { write("indicators-v1", original) }
        val controller = IndicatorController(store)
        assertNull(controller.error.value)
        assertEquals(listOf(30.0, 70.0), controller.settings.value.indicators[0].guides)
        assertEquals(emptyMap(), controller.settings.value.indicators[0].referenceColors)
        assertEquals(DEFAULT_INDICATOR_GUIDE_COLOR, controller.settings.value.indicators[0].guideColor(30.0))
        assertEquals(listOf(20.0, 80.0), controller.settings.value.indicators[1].guides)
        assertEquals(original, store.read("indicators-v1"))
        val edited = controller.settings.value.indicators.map { if (it.kind == IndicatorKind.RSI) it.copy(referenceLevels = emptyList()) else it }
        assertTrue(controller.apply(IndicatorSettings(edited)))
        val reopened = IndicatorController(store)
        assertEquals(emptyList(), reopened.settings.value.indicators[0].guides)
        assertEquals(listOf(20.0, 80.0), reopened.settings.value.indicators[1].guides)
    }

    @Test fun legacyCustomLevelsWithoutColorsStillLoad() {
        val original = """{"indicators":[{"id":"rsi","kind":"RSI","referenceLevels":[25.5,75.25]}]}"""
        val store = MemorySnapshotStore().apply { write("indicators-v1", original) }
        val controller = IndicatorController(store)
        assertNull(controller.error.value)
        val rsi = controller.settings.value.indicators.single()
        assertEquals(listOf(25.5, 75.25), rsi.guides)
        assertTrue(rsi.guides.all { rsi.guideColor(it) == DEFAULT_INDICATOR_GUIDE_COLOR })
        assertEquals(original, store.read("indicators-v1"))
    }

    @Test fun invalidSavedGuideColorsFallBackWithoutOverwritingStorage() {
        for (config in listOf(
            IndicatorConfig("rsi", IndicatorKind.RSI, referenceColors = mapOf("30" to "red")),
            IndicatorConfig("rsi", IndicatorKind.RSI, referenceColors = mapOf("50" to "#123456")),
            IndicatorConfig("rsi", IndicatorKind.RSI, referenceLevels = emptyList(), referenceColors = mapOf("30" to "#123456")),
            IndicatorConfig("kdj", IndicatorKind.KDJ, referenceColors = mapOf("20" to "#123456")),
        )) {
            val original = Json.encodeToString(IndicatorSettings(listOf(config)))
            val store = MemorySnapshotStore().apply { write("indicators-v1", original) }
            val controller = IndicatorController(store)
            assertEquals(IndicatorSettings(), controller.settings.value)
            assertNotNull(controller.error.value)
            assertEquals(original, store.read("indicators-v1"))
        }
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
