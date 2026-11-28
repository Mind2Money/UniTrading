package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ThemePreset
import com.unitrading.app.ThemeSettings
import com.unitrading.app.TradingRuntime
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabletReplayUiTest {
    private fun checkTablet(platform: ChartPlatform, width: Int, height: Int) =
        runDesktopComposeUiTest(width = width, height = height) {
            val broker = OfflineOrderBroker()
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply {
                paused.value = true
                theme.settings.value = ThemeSettings(preset = ThemePreset.LIGHT)
            }
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
            } }
            onNodeWithTag("open-historical-replay").performScrollTo().performClick()
            val setup = onNodeWithTag("replay-setup").fetchSemanticsNode().boundsInRoot
            assertTrue(setup.width <= 650f && setup.height <= 700f)
            onNodeWithTag("replay-risk-row").assertExists()
            val out = File("../artifacts/tablet-replay").also { it.mkdirs() }
            ImageIO.write(onNodeWithTag("replay-setup").captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-setup.png"))
            onNodeWithTag("replay-begin").performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("replay-order-ticket").fetchSemanticsNodes().isNotEmpty() }
            val replay = onNodeWithTag("historical-replay").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(replay.width - width) < 2f && abs(replay.height - height) < 2f, "Replay should fill the available window: $replay")
            onNodeWithTag("replay-account-strip").assertExists()
            onNodeWithTag("replay-daily-pnl").assertExists()
            onNodeWithTag("replay-account-strip-toggle").performClick()
            onNodeWithTag("replay-daily-pnl").assertDoesNotExist()
            onNodeWithTag("replay-account-strip-toggle").performClick()
            onNodeWithTag("replay-daily-pnl").assertExists()
            onNodeWithTag("replay-tablet-drawing-toggle").performClick()
            onNodeWithTag("drawing-toolbar").assertDoesNotExist()
            onNodeWithTag("replay-tablet-drawing-toggle").performClick()
            onNodeWithTag("drawing-toolbar").assertExists()
            val controls = onNodeWithTag("replay-controls").fetchSemanticsNode().boundsInRoot
            val startButton = onNodeWithTag("replay-reset").fetchSemanticsNode().boundsInRoot
            val periodButton = onNodeWithTag("replay-period-menu").fetchSemanticsNode().boundsInRoot
            assertTrue(onNodeWithTag("replay-play").fetchSemanticsNode().boundsInRoot.height >= 48f)
            assertTrue(abs((startButton.left + periodButton.right) / 2f - controls.center.x) < 12f, "Replay controls must be centered")
            val panel = onNodeWithTag("replay-side-panel").fetchSemanticsNode().boundsInRoot
            val fields = onNodeWithTag("order-fields").fetchSemanticsNode().boundsInRoot
            val actions = onNodeWithTag("order-actions").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.width <= 272f && fields.bottom <= actions.top + 1f)
            onNodeWithTag("review-order").assertIsDisplayed()
            onNodeWithTag("order-more").performScrollTo().performClick()
            onNodeWithTag("reference-protection").performScrollTo().assertIsDisplayed()
            onNodeWithTag("order-more").performScrollTo().performClick()
            ImageIO.write(onNodeWithTag("historical-replay").captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-order.png"))
            onNodeWithTag("replay-reset").performClick()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-start-menu.png"))
            onNodeWithTag("replay-start-earliest").performClick()
            onNodeWithTag("replay-speed-menu").performClick()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-speed-menu.png"))
            onNodeWithTag("replay-speed-7").performScrollTo().performClick()
            onNodeWithTag("replay-speed-menu").assertTextContains("7x")
            onNodeWithTag("replay-speed-menu").performClick()
            onNodeWithTag("replay-speed-0.3").performScrollTo().performClick()
            onNodeWithTag("replay-speed-menu").assertTextContains("0.3x")
            onNodeWithTag("replay-period-menu").performClick()
            onNodeWithTag("replay-step-1s").assertIsNotEnabled()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-period-menu.png"))
            onNodeWithTag("replay-step-15").assertDoesNotExist()
            onNodeWithTag("replay-step-1").performScrollTo().performClick()
            onNodeWithTag("replay-order-collapse").performClick()
            val chart = onAllNodesWithTag("trading-chart").onLast()
            val size = chart.fetchSemanticsNode().size
            chart.performTouchInput { longClick(Offset(size.width * .6f, size.height * .6f)) }
            onNodeWithTag("compact-plot-menu").assertExists()
            val menu = onNodeWithTag("compact-plot-menu").fetchSemanticsNode().boundsInRoot
            assertTrue(menu.width <= 300f)
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(out, "${platform.name.lowercase()}-${width}x${height}-menu.png"))
            onNodeWithTag("plot-menu-BUY-MARKET").performClick()
            onNodeWithTag("replay-order-ticket").assertExists()
            assertTrue(broker.placed.isEmpty(), "Replay drafts must never reach the broker")
            onNodeWithTag("replay-next").performClick()
            onNodeWithTag("replay-close").performClick()
            onNodeWithText("关闭回放？").assertExists()
        }

    @Test fun androidLandscape() = checkTablet(ChartPlatform.ANDROID, 1024, 768)
    @Test fun androidPortrait() = checkTablet(ChartPlatform.ANDROID, 800, 1200)
    @Test fun ipadLandscape() = checkTablet(ChartPlatform.IOS, 1024, 768)
}
