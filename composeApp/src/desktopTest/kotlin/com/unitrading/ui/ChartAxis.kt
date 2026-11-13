package com.unitrading.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.unitrading.chart.PriceScale

/** The price axis the chart shows now, read from its state; [density] is the test's, for the plot's 18dp top margin. */
internal fun SemanticsNodeInteraction.chartAxis(density: Float): PriceScale {
    val state = fetchSemanticsNode().config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    return PriceScale(state.getValue("low").toDouble(), state.getValue("high").toDouble(), 18f * density, state.getValue("plotBottom").toFloat())
}
