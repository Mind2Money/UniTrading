package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AppearanceNavigationTest {
    @get:Rule val compose = createComposeRule()
    private fun render(runtime: TradingRuntime, mobile: Boolean = false) {
        runtime.paused.value = true
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(if (mobile) .75f else .6f)) {
            Box((if (mobile) Modifier.requiredSize(400.dp, 740.dp) else Modifier.fillMaxSize()).testTag("test-host")) { TradingScreen(runtime) }
        } }
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    @Test fun freshInstallStartsAtLoginAndThemeCanBeSavedWithoutAccount() {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store)
        render(runtime)
        compose.onNodeWithTag("welcome-login").assertExists()
        compose.onNodeWithTag("welcome-login-rithmic").assertExists()
        compose.onNodeWithText("连接 Tradovate 或 Rithmic 后查看账户、行情和图表。").assertExists()
        compose.onNodeWithTag("broker-picker").assertDoesNotExist()
        compose.onNodeWithTag("review-order").assertDoesNotExist()
        compose.onNodeWithTag("open-theme").performClick()
        compose.onNodeWithTag("theme-LIGHT").performClick()
        compose.onNodeWithTag("theme-color-PAGE").performScrollTo().performTextReplacement("#EDF1F7")
        compose.onNodeWithTag("theme-color-UP").performScrollTo().performTextReplacement("#008877")
        capture("theme-custom-preview")
        compose.onNodeWithTag("theme-save").performClick()
        compose.waitUntil(5000) { runtime.theme.settings.value.preset == ThemePreset.LIGHT }
        assertEquals("#008877", ThemeController(store).settings.value.colors["UP"])
        capture("login-light")
        compose.onNodeWithTag("open-theme").performClick()
        compose.onNodeWithTag("theme-DARK").performClick()
        compose.onNodeWithTag("theme-cancel").performClick()
        assertEquals(ThemePreset.LIGHT, runtime.theme.settings.value.preset)
    }
    @Test fun tradingVPresetAppliesTradingViewBlackPaletteAndPersists() {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = true)
        render(runtime)
        compose.onNodeWithTag("trading-chart").assertExists()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("open-theme").performScrollTo().performClick()
        compose.onNodeWithTag("theme-TRADINGV").performClick()
        compose.onNodeWithTag("theme-color-CHART").performScrollTo().assertTextContains("#0F0F0F")
        compose.onNodeWithTag("theme-color-ACCENT").performScrollTo().assertTextContains("#2962FF")
        capture("theme-tradingv-preview")
        compose.onNodeWithTag("theme-save").performClick()
        compose.waitUntil(5000) { runtime.theme.settings.value.preset == ThemePreset.TRADINGV }
        assertEquals(ThemePreset.TRADINGV, ThemeController(store).settings.value.preset)
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("trading-chart").assertExists()
        capture("trading-tradingv")
    }
    @Test fun materialNavigationKeepsFourDestinationsAndShowsThemeInAccount() {
        render(TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true), mobile = true)
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("nav-1").assertIsSelected()
        capture("mobile-navigation-chart")
        compose.onNodeWithTag("nav-0").performClick().assertIsSelected()
        compose.onNodeWithTag("market-quotes").assertIsDisplayed()
        // Search belongs to the add-symbol screen, reached from the watchlist's + action.
        compose.onNodeWithTag("symbol-search").assertDoesNotExist()
        compose.onNodeWithTag("market-add").assertIsDisplayed().performClick()
        compose.onNodeWithTag("symbol-search").assertIsDisplayed().performTextReplacement("EURUSD")
        compose.onNodeWithTag("search-EURUSD").assertIsDisplayed()
        compose.onNodeWithTag("market-back").performClick()
        compose.onNodeWithTag("market-quotes").assertIsDisplayed()
        compose.onNodeWithTag("symbol-search").assertDoesNotExist()
        compose.onNodeWithTag("nav-0").assertIsSelected()
        compose.onNodeWithTag("nav-2").performClick().assertIsSelected()
        compose.onNodeWithTag("trade-terminal").assertExists()
        compose.onNodeWithTag("nav-3").performClick().assertIsSelected()
        compose.onNodeWithTag("open-theme").performScrollTo().performClick()
        compose.onNodeWithTag("theme-settings").assertExists()
    }
    @Test fun axisGesturesZoomAndPlotPansWithoutTradingThenResetRestoresAuto() {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        render(runtime)
        // Hide overlays so free pan is tested away from editable order lines.
        compose.onNodeWithTag("chart-layers").performClick()
        compose.onNodeWithTag("layer-0").performClick()
        compose.onNodeWithTag("layer-1").performClick()
        compose.onNodeWithTag("chart-layers").performClick()
        val chart = compose.onNodeWithTag("trading-chart")
        fun values(): Map<String, String> = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
        val before = values()
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { swipe(Offset(size.width - 12f, size.height * .6f), Offset(size.width - 12f, size.height * .3f)) }
        val priceZoom = values()
        assertEquals(before["bars"], priceZoom["bars"])
        assertTrue(priceZoom.getValue("high").toDouble() - priceZoom.getValue("low").toDouble() < before.getValue("high").toDouble() - before.getValue("low").toDouble())
        chart.performTouchInput { swipe(Offset(size.width * .3f, size.height - 5f), Offset(size.width * .6f, size.height - 5f)) }
        val timeZoom = values()
        assertTrue(timeZoom.getValue("bars").toDouble() < priceZoom.getValue("bars").toDouble())
        assertEquals(priceZoom["low"], timeZoom["low"])
        chart.performTouchInput { swipe(Offset(size.width * .4f, size.height * .4f), Offset(size.width * .6f, size.height * .6f)) }
        val pan = values()
        assertNotEquals(timeZoom["offset"], pan["offset"])
        assertNotEquals(timeZoom["low"], pan["low"])
        compose.onNodeWithTag("protection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        compose.onNodeWithTag("chart-reset").performClick()
        assertEquals("true", values()["auto"])
        assertEquals("80.0", values()["bars"])
        assertEquals("0.0", values()["offset"])
    }
}
