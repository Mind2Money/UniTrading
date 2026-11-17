package com.unitrading.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class IndicatorLineRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun linePatternAndWidthChangeRenderedPixels() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Canvas(Modifier.size(200.dp, 90.dp).background(Color.Black).testTag("lines")) {
                    val values = DoubleArray(180) { 0.0 }
                    drawSeriesLine(values, 0..179, Color.White, IndicatorLineStyle(1f), { it.toFloat() + 10f }) { 15f }
                    drawSeriesLine(values, 0..179, Color.White, IndicatorLineStyle(5f, IndicatorLinePattern.DASHED), { it.toFloat() + 10f }) { 40f }
                    drawSeriesLine(values, 0..179, Color.White, IndicatorLineStyle(3f, IndicatorLinePattern.DOTTED), { it.toFloat() + 10f }) { 65f }
                }
            }
        }
        val pixels = compose.onNodeWithTag("lines").captureToImage().toPixelMap()
        fun lit(x: Int, y: Int) = pixels[x, y].red > .5f
        fun transitions(y: Int) = (12 until 187).count { lit(it, y) != lit(it + 1, y) }
        assertTrue(transitions(15) <= 2, "solid line should be continuous")
        assertTrue(transitions(40) >= 10, "dashed line should contain visible gaps")
        assertTrue(transitions(65) >= 10, "dotted line should contain visible gaps")
        assertTrue(lit(20, 38), "5 dp line should be visibly thicker than a 1 dp line")
        assertTrue(!lit(20, 13), "1 dp line should not fill the same height")
    }
}
