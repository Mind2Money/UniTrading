package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.BrokerEnvironment
import com.unitrading.broker.BrokerPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ChartControlLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val source = OfflineOrderBroker()
    private val remote = object : BrokerPlugin by source {
        override val descriptor = source.descriptor.copy(id = "tradovate-demo-layout", environment = BrokerEnvironment.REMOTE_DEMO)
        override val market = MutableStateFlow(source.market.value.copy(connectionLatencyMs = 232, statusMessage = "测试连接正常"))
    }
    private val runtime = TradingRuntime(MemorySnapshotStore(), listOf(remote)).apply { paused.value = true }

    private fun render(platform: ChartPlatform, width: Dp, height: Dp, fontScale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalChartPlatform provides platform) {
            Box(Modifier.requiredSize(width, height)) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun state() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun assertMobileControlIsAwayFromOrder() {
        val chart = bounds("trading-chart")
        val settings = bounds("price-axis-menu-button")
        val order = bounds("open-order")
        assertTrue(settings.width >= 48f && settings.height >= 48f, "the settings target must be easy to tap")
        assertTrue(settings.top >= chart.top && settings.bottom <= chart.top + state().getValue("plotTop").toFloat(), "the control stays above the price plot")
        assertTrue(settings.bottom + 50f < order.top, "the settings and order buttons are well separated")
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).performClick()
        compose.waitForIdle()
        assertTrue(source.placed.isEmpty())
    }
    private fun assertOnlyConnectionIndicator() {
        compose.onNodeWithTag("connection-status").assertIsDisplayed().performClick()
        compose.onNodeWithText("测试连接正常").assertIsDisplayed()
        compose.onNodeWithText("远程 Demo", substring = true).assertDoesNotExist()
        compose.onNodeWithText("232 ms", substring = true).assertDoesNotExist()
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    }
    @Test fun androidPortraitSeparatesControlsAndShowsStatusDot() {
        render(ChartPlatform.ANDROID, 400.dp, 800.dp)
        assertMobileControlIsAwayFromOrder()
        assertOnlyConnectionIndicator()
    }
    @Test fun androidNarrowAndLargeTextKeepsStatusUsable() {
        render(ChartPlatform.ANDROID, 320.dp, 720.dp, fontScale = 1.4f)
        assertMobileControlIsAwayFromOrder()
        assertOnlyConnectionIndicator()
    }
    @Test fun iosUsesTheSameSafeMobileControlPlacement() {
        render(ChartPlatform.IOS, 400.dp, 800.dp)
        assertMobileControlIsAwayFromOrder()
        assertOnlyConnectionIndicator()
    }
    @Test fun androidTabletUsesTheDesktopAxisControlPlacement() {
        render(ChartPlatform.ANDROID, 1280.dp, 800.dp)
        val chart = bounds("trading-chart")
        val settings = bounds("price-axis-menu-button")
        assertTrue(settings.width >= 48f && settings.height >= 48f)
        assertTrue(settings.top >= chart.top + state().getValue("plotBottom").toFloat())
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).performClick()
        assertTrue(source.placed.isEmpty())
    }
    @Test fun desktopUsesLargeButtonBelowAxisOnEitherSide() {
        render(ChartPlatform.DESKTOP, 960.dp, 700.dp)
        fun verify(left: Boolean) {
            val chart = bounds("trading-chart")
            val settings = bounds("price-axis-menu-button")
            assertTrue(settings.width >= 48f && settings.height >= 48f)
            assertTrue(settings.top >= chart.top + state().getValue("plotBottom").toFloat())
            if (left) assertTrue(settings.left < chart.left + 84f) else assertTrue(settings.right > chart.right - 84f)
        }
        verify(false)
        runBlocking {
            runtime.chartPreferences.selectPriceAxis(runtime.chartPreferences.priceAxis.value.copy(left = true))
            assertTrue(runtime.chartPreferences.savePriceAxis())
        }
        compose.waitForIdle()
        verify(true)
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).performClick()
        assertOnlyConnectionIndicator()
        assertTrue(source.placed.isEmpty())
    }
}
