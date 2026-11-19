package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopAccountPanelsUiTest {
    @Test fun desktopRailShowsAnalysisAndTradingTabsKeepFlattenConfirmations() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        val broker = runtime.registry.plugins.first()
        val first = broker.accounts.first()
        val positionCount = broker.openSession(first.id).state.value.positions.size
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("toggle-account-overview").assertDoesNotExist()
        onNodeWithTag("rail-analytics").assertExists().performClick()
        onNodeWithTag("desktop-analytics").assertIsDisplayed()
        onNodeWithTag("account-analytics").assertIsDisplayed()
        onNodeWithTag("analytics-coverage").assertTextContains(first.name, substring = true)
        onNodeWithTag("rail-market").performClick()
        onNodeWithTag("desktop-market").assertIsDisplayed()
        onNodeWithTag("desktop-analytics").assertDoesNotExist()

        for ((tab, section) in listOf("POSITIONS" to "terminal-section-positions", "ORDERS" to "terminal-section-orders",
            "EXECUTIONS" to "terminal-section-executions", "CLOSED_TRADES" to "terminal-section-history")) {
            onNodeWithTag("desktop-trade-tab-$tab").performScrollTo().performClick()
            onNodeWithTag(section).assertIsDisplayed()
        }
        val toolbar = onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = onNodeWithTag("desktop-flatten-actions").fetchSemanticsNode().boundsInRoot
        val accounts = onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(controls.top >= toolbar.top && controls.bottom <= toolbar.bottom + 1f, "Flatten actions stay in the chart toolbar")
        kotlin.test.assertTrue(accounts.top >= toolbar.top && accounts.bottom <= toolbar.bottom + 1f, "Both account selectors stay in the chart toolbar")
        kotlin.test.assertTrue(controls.right + 10f <= accounts.left, "Flatten actions have a gap from the account selectors")
        val tradeShot = File("../artifacts/desktop-account-panels/trade-actions.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", tradeShot)
        runOnIdle { runBlocking { runtime.theme.apply(com.unitrading.app.ThemeSettings(com.unitrading.app.ThemePreset.LIGHT)) } }
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(tradeShot.parentFile, "toolbar-light.png"))
        onNodeWithTag("flatten-selected-account").assertIsDisplayed().assertIsEnabled().performClick()
        onNodeWithText("平仓此子账户？").assertExists()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        assertEquals(positionCount, broker.openSession(first.id).state.value.positions.size, "Cancel never sends flatten orders")
        onNodeWithTag("flatten-all-accounts").assertIsDisplayed().performClick()
        onNodeWithText("平仓全部子账户？").assertExists()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        assertEquals(positionCount, broker.openSession(first.id).state.value.positions.size)

        val second = broker.accounts.last()
        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("quick-account-${second.id}").performClick()
        val secondBefore = broker.openSession(second.id).state.value
        kotlin.test.assertTrue(secondBefore.positions.isNotEmpty() || secondBefore.orders.isNotEmpty(), "The second fixture account can be flattened")
        onNodeWithTag("flatten-selected-account").assertIsEnabled().performClick()
        onNodeWithText("平仓此子账户？").assertExists()
        onNodeWithText(second.id, substring = true).assertExists()
        onNodeWithText(first.id, substring = true).assertDoesNotExist()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        assertEquals(secondBefore.positions.size, broker.openSession(second.id).state.value.positions.size, "Cancel never flattens the switched account")
        onNodeWithTag("rail-analytics").performClick()
        onNodeWithTag("analytics-coverage").assertTextContains(second.name, substring = true)
        waitForIdle()
        val out = File("../artifacts/desktop-account-panels/analysis.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", out)
    }

    @Test fun compactDesktopToolbarKeepsAccountAndFlattenActionsUsable() = runDesktopComposeUiTest(width = 900, height = 650) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP) {
            Box(Modifier.requiredSize(900.dp, 650.dp)) { TradingScreen(runtime) }
        } }
        val toolbar = onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
        val chart = onNodeWithTag("chart-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = onNodeWithTag("desktop-flatten-actions").fetchSemanticsNode().boundsInRoot
        val accounts = onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(chart.width > 100f, "Chart tools remain scrollable at the minimum desktop width")
        kotlin.test.assertTrue(chart.right <= controls.left + 1f && controls.right + 10f <= accounts.left)
        kotlin.test.assertTrue(accounts.right <= toolbar.right && controls.top >= toolbar.top && accounts.bottom <= toolbar.bottom + 1f)
        onNodeWithTag("flatten-all-accounts").assertIsDisplayed()
        onNodeWithTag("quick-login-picker").assertIsDisplayed()
        onNodeWithTag("quick-account-picker").assertIsDisplayed().performClick()
        onNodeWithTag("quick-account-${runtime.registry.plugins.first().accounts.first().id}").performClick()
        onNodeWithTag("desktop-terminal-toggle").performClick()
        onNodeWithTag("flatten-all-accounts").assertIsDisplayed().performClick()
        onNodeWithText("平仓全部子账户？").assertExists()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        onNodeWithTag("quick-account-picker").assertIsDisplayed()
    }

    @Test fun phoneStillHasItsAccountPage() = runDesktopComposeUiTest(width = 412, height = 860) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(412.dp, 860.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("rail-analytics").assertDoesNotExist()
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("account-hub").assertIsDisplayed()
        onNodeWithTag("account-tab-2").performClick()
        onNodeWithTag("account-analytics").assertIsDisplayed()
    }
}
