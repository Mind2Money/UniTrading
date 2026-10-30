package com.unitrading.ui

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import kotlin.test.*

class IndicatorDraftTest {
    private val rsi = IndicatorDraft.from(IndicatorConfig("rsi", IndicatorKind.RSI))

    @Test fun referenceValuesAcceptDecimalsSeparatorsAndAnEmptyList() {
        assertEquals(listOf(30.0, 70.0), rsi.toConfig()?.guides)
        assertEquals(listOf(0.0, 25.5, 50.0, 75.25, 100.0),
            rsi.copy(referenceLevels = "100， 25.5; 0；75.25, 50").toConfig()?.guides)
        val disabled = assertNotNull(rsi.copy(referenceLevels = "  ").toConfig())
        assertEquals(emptyList(), disabled.guides)
        assertEquals("", IndicatorDraft.from(disabled).referenceLevels)
        assertEquals(disabled, IndicatorDraft.from(disabled).toConfig())
    }

    @Test fun invalidReferenceValuesBlockTheWholeSettingsDraft() {
        for (text in listOf("-0.01", "100.01", "25.555", "NaN", "Infinity", "abc", "30,,70", "30,", ",70", "30,30.00", "1e1", "999999999999999999999")) {
            val invalid = rsi.copy(referenceLevels = text)
            assertNull(invalid.toConfig(), text)
            assertNull(listOf(invalid).toSettings(), text)
        }
    }

    @Test fun referenceLevelsAreIndependentForEachRsiAndDontAffectOtherKinds() {
        val settings = assertNotNull(listOf(rsi.copy(referenceLevels = "20, 80"),
            rsi.copy(id = "second", referenceLevels = "40,60"),
            IndicatorDraft.from(IndicatorConfig("kdj", IndicatorKind.KDJ))).toSettings())
        assertEquals(listOf(listOf(20.0, 80.0), listOf(40.0, 60.0), listOf(20.0, 80.0)), settings.indicators.map { it.guides })
        assertNull(settings.indicators.last().referenceLevels)
    }
}
