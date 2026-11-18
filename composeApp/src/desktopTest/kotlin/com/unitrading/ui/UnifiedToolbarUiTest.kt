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
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UnifiedToolbarUiTest {
    private fun screenshotTest(width: Int, height: Int, platform: ChartPlatform, name: String) =
        runDesktopComposeUiTest(width = width, height = height) {
            val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform) {
                    Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
                }
            }
            onNodeWithTag("app-toolbar").assertIsDisplayed()
            onAllNodesWithTag("chart-toolbar").assertCountEquals(1)
            onNodeWithTag("quick-login-picker").assertIsDisplayed()
            onNodeWithTag("quick-account-picker").assertIsDisplayed()
            val header = onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
            val chartBar = onNodeWithTag("chart-toolbar").fetchSemanticsNode().boundsInRoot
            val login = onNodeWithTag("quick-login-picker").fetchSemanticsNode().boundsInRoot
            assertTrue(chartBar.top >= header.top && chartBar.bottom <= header.bottom + 1f)
            assertTrue(login.top >= header.top && login.bottom <= header.bottom + 1f)
            onNodeWithTag("quick-account-picker").performClick()
            onNodeWithTag("quick-account-${runtime.registry.plugins.first().accounts.first().id}").performClick()
            onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
            onNodeWithTag("chart-layout-TWO_COLUMNS").performClick()
            onAllNodesWithTag("trading-chart").assertCountEquals(2)
            waitForIdle()
            val file = File("../artifacts/unified-toolbar/$name.png").also { it.parentFile.mkdirs() }
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
        }

    @Test fun androidTabletUsesOneRow() = screenshotTest(1440, 900, ChartPlatform.ANDROID, "android-tablet-preview")
    @Test fun ipadUsesOneRow() = screenshotTest(1024, 768, ChartPlatform.IOS, "ipad-preview")
    @Test fun desktopUsesOneRow() = screenshotTest(1600, 900, ChartPlatform.DESKTOP, "desktop-preview")

    @Test fun landscapePhoneKeepsItsExistingAccountBar() = runDesktopComposeUiTest(width = 840, height = 400) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(840.dp, 400.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("app-toolbar").assertDoesNotExist()
        onNodeWithTag("quick-account-picker").assertIsDisplayed()
    }
}
