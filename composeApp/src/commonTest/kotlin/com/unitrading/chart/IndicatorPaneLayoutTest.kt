package com.unitrading.chart

import kotlin.test.*

class IndicatorPaneLayoutTest {
    @Test fun oldSettingsKeepTheOriginalPaneHeights() {
        for (count in 1..12) {
            val panes = indicatorPaneLayout(20f, 1020f, List(count) { "$it" }, emptyMap())
            assertEquals(1000f * minOf(.2f, .5f / count), panes.first().height, .001f)
            assertEquals(1020f, panes.last().bottom, .001f)
        }
        assertTrue(indicatorPaneLayout(0f, 400f, emptyList(), emptyMap()).isEmpty())
    }

    @Test fun resizingOnePaneKeepsOtherHeightsAndReservesPriceSpace() {
        val ids = listOf("rsi", "macd")
        val original = indicatorPaneLayout(20f, 1020f, ids, emptyMap())
        val saved = resizeIndicatorPane(original, "rsi", .4f, 1000f, mapOf("hidden" to .3f))
        val resized = indicatorPaneLayout(20f, 1020f, ids, saved)
        assertEquals(400f, resized[0].height, .001f)
        assertEquals(original[1].height, resized[1].height, .001f)
        assertEquals(.3f, saved["hidden"])
        assertEquals(420f, resized[0].top, .001f)
        val maxed = indicatorPaneLayout(20f, 1020f, ids, resizeIndicatorPane(resized, "rsi", 2f, 1000f, saved))
        assertEquals(270f, maxed.first().top, .001f)
        assertEquals(200f, maxed[1].height, .001f)
        val shrunk = indicatorPaneLayout(20f, 1020f, ids, resizeIndicatorPane(resized, "rsi", -1f, 1000f, saved))
        assertEquals(80f, shrunk[0].height, .001f)
        assertEquals(saved, resizeIndicatorPane(resized, "rsi", Float.NaN, 1000f, saved))
    }

    @Test fun enablingMorePanesAndChangingChartHeightKeepsGeometryBounded() {
        val ids = List(12) { "$it" }
        val preferences = ids.associateWith { .75f }
        for (height in listOf(100f, 400f, 1000f)) {
            val panes = indicatorPaneLayout(0f, height, ids, preferences)
            assertTrue(panes.first().top >= height * .25f - .001f)
            assertEquals(height, panes.last().bottom, .001f)
            assertTrue(panes.all { it.height >= height * minimumPaneFraction(ids.size) - .001f })
            panes.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top, .001f) }
        }
    }

    @Test fun shortChartsReserveTitleAndValueSpaceWhenPossible() {
        val panes = indicatorPaneLayout(0f, 200f, listOf("rsi"), mapOf("rsi" to .08f), minimumHeight = 56f)
        assertEquals(56f, panes.single().height, .001f)
        assertEquals(100f, indicatorPaneLayout(0f, 200f, listOf("rsi"), emptyMap(), minimumHeight = 140f).single().height, .001f)
    }
}
