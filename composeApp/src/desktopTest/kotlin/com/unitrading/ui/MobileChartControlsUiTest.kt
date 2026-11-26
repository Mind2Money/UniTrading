package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MobileChartControlsUiTest {
    @Test fun collapsedAccountControlStaysReachableWhenThePhoneRotates() = runDesktopComposeUiTest(width = 900, height = 900) {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        var landscape by mutableStateOf(false)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.15f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(if (landscape) Modifier.requiredSize(900.dp, 420.dp) else Modifier.requiredSize(420.dp, 900.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("collapse-chart-accounts").performClick()
        runOnIdle { landscape = true }
        onNodeWithTag("expand-chart-accounts").assertIsDisplayed().performClick()
        onNodeWithTag("quick-account-picker").assertIsDisplayed()
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun foldingAccountsReturnsChartSpaceAndRestoresTheSameSelection() = runDesktopComposeUiTest(width = 420, height = 900) {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(420.dp, 900.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("quick-login-picker").assertIsDisplayed()
        onNodeWithTag("quick-account-picker").assertTextContains("离线测试账户", substring = true)
        val folder = File("../artifacts/mobile-chart-controls").also { it.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "accounts-expanded.png"))
        val before = onNodeWithTag("trading-chart").fetchSemanticsNode().size.height
        onNodeWithTag("collapse-chart-accounts").assertContentDescriptionEquals("收起账户栏").performClick()
        onNodeWithTag("expand-chart-accounts").assertContentDescriptionEquals("展开账户栏").assertIsDisplayed()
        onNodeWithTag("quick-account-picker").assertDoesNotExist()
        val after = onNodeWithTag("trading-chart").fetchSemanticsNode().size.height
        assertTrue(after >= before + 45, "Collapsing the bar must give its height back to the chart")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "accounts-collapsed.png"))
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("order-panel").assertExists()
        onNodeWithTag("minimize-order").performClick()
        onNodeWithTag("close-order").performClick()
        onNodeWithTag("expand-chart-accounts").assertExists()
        onNodeWithTag("nav-0").performClick()
        onNodeWithTag("quick-account-picker").assertIsDisplayed()
        onNodeWithTag("nav-1").performClick()
        onNodeWithTag("expand-chart-accounts").performClick()
        onNodeWithTag("quick-account-picker").assertTextContains("离线测试账户", substring = true)
        onNodeWithTag("collapse-chart-accounts").assertExists()
        assertTrue(broker.placed.isEmpty())
    }

    private fun verifyPhoneMenu(platform: ChartPlatform, mouse: Boolean) = runDesktopComposeUiTest(width = 420, height = 900) {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform) {
            Box(Modifier.requiredSize(420.dp, 900.dp)) { TradingScreen(runtime) }
        } }
        val chart = onNodeWithTag("trading-chart")
        val rect = chart.fetchSemanticsNode().size
        val at = Offset(rect.width * .7f, rect.height * .65f)
        if (mouse) chart.performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        else chart.performTouchInput { longClick(at) }
        onNodeWithTag("phone-plot-menu").assertExists()
        onNodeWithTag("plot-reset").assertExists()
        onNodeWithTag("plot-menu-BUY-MARKET").assertExists()
        onNodeWithTag("plot-menu-SELL-MARKET").assertExists()
        onNodeWithTag("plot-menu-BUY-LIMIT").assertExists()
        onNodeWithTag("plot-menu-SELL-LIMIT").assertExists()
        onNodeWithTag("plot-objects").assertIsEnabled()
        onNodeWithTag("plot-templates").assertIsEnabled()
        onNodeWithTag("plot-remove-drawings").assertExists()
        onNodeWithTag("plot-settings").assertDoesNotExist()
        onNodeWithTag("plot-remove-indicators").assertDoesNotExist()
        onNodeWithTag("plot-open-order").assertDoesNotExist()
        val market = onNodeWithTag("plot-menu-BUY-MARKET").fetchSemanticsNode().boundsInRoot
        assertTrue(market.height <= 46f, "Phone menu rows should be compact")
        if (platform == ChartPlatform.ANDROID && !mouse) {
            val folder = File("../artifacts/mobile-chart-controls").also { it.mkdirs() }
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "phone-context-menu.png"))
        }
        onNodeWithTag("plot-objects").performScrollTo().performClick()
        onNodeWithTag("chart-object-tree").assertExists()
        onNode(hasText("关闭") and hasAnyAncestor(hasTestTag("chart-object-tree"))).performClick()
        if (mouse) chart.performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        else chart.performTouchInput { longClick(at) }
        onNodeWithTag("plot-menu-BUY-LIMIT").performClick()
        onNodeWithTag("order-kind-LIMIT").assertIsSelected()
        onNodeWithTag("order-entry").assertExists()
        assertTrue(broker.placed.isEmpty(), "A menu selection must only prepare a draft")
    }

    @Test fun androidLongPressUsesTheCompactMenu() = verifyPhoneMenu(ChartPlatform.ANDROID, mouse = false)
    @Test fun iosLongPressUsesTheCompactMenu() = verifyPhoneMenu(ChartPlatform.IOS, mouse = false)
    @Test fun phoneMouseRightClickUsesTheSameCompactMenu() = verifyPhoneMenu(ChartPlatform.ANDROID, mouse = true)
}
