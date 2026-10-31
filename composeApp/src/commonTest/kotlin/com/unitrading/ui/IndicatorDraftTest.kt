package com.unitrading.ui

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.DEFAULT_INDICATOR_GUIDE_COLOR
import kotlin.test.*

class IndicatorDraftTest {
    private val rsi = IndicatorDraft.from(IndicatorConfig("rsi", IndicatorKind.RSI))

    private fun rows(vararg values: String) = values.map { ReferenceLevelDraft(it) }

    @Test fun referenceValuesAcceptDecimalsAndAnEmptyList() {
        assertEquals(listOf(30.0, 70.0), rsi.toConfig()?.guides)
        assertTrue(rsi.referenceLevels.all { it.color == DEFAULT_INDICATOR_GUIDE_COLOR })
        assertEquals(listOf(0.0, 25.5, 50.0, 75.25, 100.0),
            rsi.copy(referenceLevels = rows("100", " 25.5 ", "0", "75.25", "50")).toConfig()?.guides)
        val disabled = assertNotNull(rsi.copy(referenceLevels = emptyList()).toConfig())
        assertEquals(emptyList(), disabled.guides)
        assertEquals(emptyMap(), disabled.referenceColors)
        assertEquals(emptyList(), IndicatorDraft.from(disabled).referenceLevels)
        assertEquals(disabled, IndicatorDraft.from(disabled).toConfig())
    }

    @Test fun invalidReferenceValuesBlockTheWholeSettingsDraft() {
        for (text in listOf("", "  ", "-0.01", "100.01", "25.555", "NaN", "Infinity", "abc", "30,70", "1e1", "999999999999999999999")) {
            val invalid = rsi.copy(referenceLevels = rows(text))
            assertNull(invalid.toConfig(), text)
            assertNull(listOf(invalid).toSettings(), text)
        }
        for (values in listOf(rows("30", "30.00"), rows("0", "-0.00"))) {
            val duplicate = rsi.copy(referenceLevels = values)
            assertNull(duplicate.parseReferenceLevels())
            assertNull(listOf(duplicate).toSettings())
        }
    }

    @Test fun invalidIndividualColorsBlockSaving() {
        for (color in listOf("", "red", "#FFF", "123456", "#12345G", "#12345678")) {
            val row = ReferenceLevelDraft("25.5", color)
            assertFalse(row.colorValid(), color)
            assertNull(listOf(rsi.copy(referenceLevels = rsi.referenceLevels + row)).toSettings(), color)
        }
    }

    @Test fun addingRemovingAndSortingRowsPreservesIndividualColors() {
        val added = rsi.copy(referenceLevels = rsi.referenceLevels + ReferenceLevelDraft())
        assertNull(added.toConfig(), "a new blank row needs a value or removal")
        assertEquals(rsi.toConfig(), added.copy(referenceLevels = added.referenceLevels.dropLast(1)).toConfig())

        val draft = rsi.copy(referenceLevels = listOf(ReferenceLevelDraft("75.25", "#aabbcc"), ReferenceLevelDraft("25.50", "#112233"),
            ReferenceLevelDraft("50", "#445566")))
        val config = assertNotNull(draft.toConfig())
        assertEquals(listOf(25.5, 50.0, 75.25), config.guides)
        assertEquals(mapOf("25.5" to "#112233", "50" to "#445566", "75.25" to "#AABBCC"), config.referenceColors)
        val reopened = IndicatorDraft.from(config)
        assertEquals(listOf(ReferenceLevelDraft("25.5", "#112233"), ReferenceLevelDraft("50", "#445566"),
            ReferenceLevelDraft("75.25", "#AABBCC")), reopened.referenceLevels)
        assertEquals(config, reopened.toConfig())
        val removed = assertNotNull(draft.copy(referenceLevels = draft.referenceLevels.filterIndexed { i, _ -> i != 1 }).toConfig())
        assertEquals(listOf(50.0, 75.25), removed.guides)
        assertEquals(mapOf("50" to "#445566", "75.25" to "#AABBCC"), removed.referenceColors)
        val moved = assertNotNull(draft.copy(referenceLevels = draft.referenceLevels.map { if (it.value == "25.50") it.copy(value = "90") else it }).toConfig())
        assertEquals("#112233", moved.guideColor(90.0))
        assertFalse("25.5" in moved.referenceColors)
    }

    @Test fun referenceLevelsAreIndependentForEachRsiAndDontAffectOtherKinds() {
        val settings = assertNotNull(listOf(rsi.copy(referenceLevels = listOf(ReferenceLevelDraft("20", "#123456"), ReferenceLevelDraft("80"))),
            rsi.copy(id = "second", referenceLevels = listOf(ReferenceLevelDraft("20", "#ABCDEF"), ReferenceLevelDraft("60"))),
            IndicatorDraft.from(IndicatorConfig("kdj", IndicatorKind.KDJ))).toSettings())
        assertEquals(listOf(listOf(20.0, 80.0), listOf(20.0, 60.0), listOf(20.0, 80.0)), settings.indicators.map { it.guides })
        assertEquals("#123456", settings.indicators[0].guideColor(20.0))
        assertEquals("#ABCDEF", settings.indicators[1].guideColor(20.0))
        assertNull(settings.indicators.last().referenceLevels)
        assertEquals(emptyMap(), settings.indicators.last().referenceColors)
        for (kind in IndicatorKind.entries.filter { it != IndicatorKind.RSI }) {
            val config = assertNotNull(IndicatorDraft.from(IndicatorConfig(kind.name, kind)).toConfig())
            assertNull(config.referenceLevels)
            assertEquals(emptyMap(), config.referenceColors)
            assertEquals(kind.guides, config.guides)
        }
    }
}
