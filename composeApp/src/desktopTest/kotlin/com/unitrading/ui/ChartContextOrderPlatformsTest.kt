package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Android and iOS share the menu path; neither a phone nor a tablet may place from that menu. */
@OptIn(ExperimentalTestApi::class)
class ChartContextOrderPlatformsTest {
    private fun exercise(platform: ChartPlatform, width: Int, height: Int) =
        runDesktopComposeUiTest(width = width, height = height) {
            val broker = OfflineOrderBroker()
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
            runBlocking { assertTrue(runtime.tradingPreferences.setSkipConfirmation(true)) }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform,
                    LocalPanelWindowHost provides null) {
                    Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
                }
            }
            val chart = onNodeWithTag("trading-chart")
            val size = chart.fetchSemanticsNode().size
            fun state() = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
            fun zoomIn() = chart.performTouchInput {
                down(0, Offset(size.width * .32f, size.height * .40f)); down(1, Offset(size.width * .58f, size.height * .60f))
                repeat(10) { i ->
                    updatePointerTo(0, Offset(size.width * (.32f - .012f * (i + 1)), size.height * (.40f - .008f * (i + 1))))
                    updatePointerTo(1, Offset(size.width * (.58f + .012f * (i + 1)), size.height * (.60f + .008f * (i + 1))))
                    move()
                }
                up(0); up(1)
            }
            if (platform != ChartPlatform.DESKTOP) {
                onNodeWithTag("chart-reset").assertIsDisplayed()
                zoomIn()
                waitForIdle()
                assertTrue(state().getValue("bars").toDouble() < 80.0)
                chart.performTouchInput { longClick(Offset(size.width * .70f, size.height * .70f)) }
                onNodeWithTag("plot-menu-horizontal-line").assertDoesNotExist()
                onNodeWithTag("plot-reset").assertTextContains("重置视图").performClick()
                waitForIdle()
                assertEquals(80.0, state().getValue("bars").toDouble())
                zoomIn()
                onNodeWithTag("chart-reset").performClick()
                waitForIdle()
                assertEquals(80.0, state().getValue("bars").toDouble())
            }
            chart.performTouchInput { longClick(Offset(size.width * .70f, size.height * .70f)) }
            onNodeWithTag("plot-menu-SELL-MARKET").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("order-side-SELL").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("order-side-SELL").onFirst().assertIsSelected()
            onAllNodesWithTag("order-kind-MARKET").onFirst().assertIsSelected()
            if (width >= 900) {
                onNodeWithTag("desktop-order").assertIsDisplayed()
                onNodeWithTag("order-popup").assertDoesNotExist()
            }
            assertTrue(broker.placed.isEmpty(), "Choosing a menu item must not place an order, even when confirmation is skipped")
        }

    @Test fun androidPhoneOpensTheOrderForm() = exercise(ChartPlatform.ANDROID, 420, 850)
    @Test fun iosPhoneOpensTheOrderForm() = exercise(ChartPlatform.IOS, 420, 850)
    @Test fun androidTabletOpensTheOrderForm() = exercise(ChartPlatform.ANDROID, 1280, 800)
    @Test fun ipadOpensTheOrderForm() = exercise(ChartPlatform.IOS, 1280, 800)
    @Test fun narrowDesktopOpensTheRightHandOrderForm() = exercise(ChartPlatform.DESKTOP, 1000, 800)
    @Test fun narrowAndroidTabletOpensTheRightHandOrderForm() = exercise(ChartPlatform.ANDROID, 1000, 800)
}
