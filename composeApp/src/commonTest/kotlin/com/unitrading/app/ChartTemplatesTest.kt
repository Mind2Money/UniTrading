package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.PriceAxisSettings
import com.unitrading.chart.PriceScaleMode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChartTemplatesTest {
    @Test fun savesReplacesAndRemovesTemplatesWithoutTouchingOtherSettings() = runTest {
        val store = MemorySnapshotStore()
        store.write("chart-v1", """{"showHistory":false}""")
        val templates = ChartTemplateController(store)
        val axis = PriceAxisSettings(mode = PriceScaleMode.PERCENT, inverted = true)
        val indicator = IndicatorConfig("one", IndicatorKind.MA)
        assertTrue(templates.save(ChartTemplate("  日内  ", listOf(indicator), axis)))
        val restored = ChartTemplateController(store)
        assertEquals(listOf(ChartTemplate("日内", listOf(indicator), axis)), restored.templates.value)
        assertFalse(ChartPreferencesController(store).historyVisible.value)
        assertTrue(restored.save(ChartTemplate("日内", emptyList(), PriceAxisSettings())))
        assertEquals(1, restored.templates.value.size)
        assertTrue(restored.templates.value.single().indicators.isEmpty())
        assertFalse(restored.save(ChartTemplate("  ", emptyList(), axis)))
        assertTrue(restored.delete("日内"))
        assertTrue(ChartTemplateController(store).templates.value.isEmpty())
    }
}
