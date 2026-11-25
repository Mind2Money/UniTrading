package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.PriceAxisSettings
import com.unitrading.chart.PriceScaleMode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChartTemplatesTest {
    @Test fun pineSourceSurvivesTemplateAndChartStorageWithoutLeakingToAnotherChart() = runTest {
        val store = MemorySnapshotStore()
        val pine = IndicatorConfig("pine", IndicatorKind.PINE, visible = false,
            pineSource = "//@version=6\nindicator(\"自定义指标\", overlay=true)\nplot(close, color=#FF8800)")
        val template = ChartTemplate("Pine layout", listOf(pine), PriceAxisSettings())
        assertTrue(ChartTemplateController(store).save(template))
        val saved = ChartTemplateController(store).templates.value.single()
        assertEquals(template, saved)
        val runtime = TradingRuntime(store)
        assertTrue(runtime.indicatorsForChart("split1").apply(IndicatorSettings(saved.indicators)))
        val reopened = TradingRuntime(store)
        assertEquals(listOf(pine), reopened.indicatorsForChart("split1").settings.value.indicators)
        assertEquals(emptyList(), reopened.indicatorsForChart("split2").settings.value.indicators)
    }

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
