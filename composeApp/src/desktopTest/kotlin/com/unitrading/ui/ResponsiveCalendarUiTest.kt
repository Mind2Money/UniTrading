package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.time.Clock
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ResponsiveCalendarUiTest {
    @Test fun phoneResizingKeepsChartZoomAndSelectedAccountTab() = runDesktopComposeUiTest(width = 1000, height = 1000) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        var width by mutableStateOf(400.dp); var height by mutableStateOf(800.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(width, height)) { TradingScreen(runtime) } } }
        fun chartState() = onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore(";low:")
        onNodeWithTag("chart-zoom-in").performClick()
        val before = chartState()
        for ((w,h) in listOf(800 to 360, 560 to 320, 400 to 800, 1000 to 500)) {
            runOnIdle { width = w.dp; height = h.dp }
            onNodeWithTag("trading-chart").assertIsDisplayed()
            assertEquals(before, chartState(), "resize should retain zoom")
            onNodeWithTag("open-order").assertIsDisplayed()
            if (w > h) {
                val rail = onNodeWithTag("mobile-navigation").fetchSemanticsNode().boundsInRoot
                assertEquals(56f, rail.width, 1f)
                val chart = onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
                assertTrue(chart.height >= h * .50f, "short landscape should reserve at least half the window for candles")
                val dock = onNodeWithTag("trade-bar").fetchSemanticsNode().boundsInRoot
                assertTrue(dock.height <= 49f)
            }
        }
        val target = File("../artifacts/responsive-landscape.png"); target.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
        onNodeWithTag("nav-4").performClick(); onNodeWithTag("account-tab-2").performClick()
        runOnIdle { width = 400.dp; height = 800.dp }
        onNodeWithTag("account-analytics").assertIsDisplayed()
        onNodeWithTag("account-tab-2").assertIsSelected()
    }

    @Test fun unknownPnlAndLargeFontsCannotStretchOneDayOrClaimPerfectWinRate() = runDesktopComposeUiTest(width = 600, height = 1000) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Chicago")).date
        val date = LocalDate(today.year, today.month, 10)
        val time = LocalDateTime(date.year, date.month, date.day, 12, 0).toInstant(TimeZone.of("America/Chicago")).toEpochMilliseconds()
        val trades = List(18) { i -> ClosedTrade("$i", "$i", "MESZ6", Side.BUY, 100, 1, 2, time-1000, time,
            if (i < 2) 11_245_000L else null, "fixture") }
        val account = OverviewAccount(AccountInfo("a", "Selected child"), AccountSnapshot("a", 0, trades = trades))
        var scale by mutableStateOf(1f)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
            TradingTheme { AccountAnalyticsPage(listOf(account), Modifier.requiredSize(400.dp, 900.dp)) }
        } }
        for (font in listOf(1f, 1.5f, 2f)) {
            runOnIdle { scale = font }
            onNodeWithTag("account-analytics").performScrollToNode(hasTestTag("calendar-day-$date"))
            val days = calendarDates(date).chunked(7).first { date in it }
            val sizes = days.map { onNodeWithTag("calendar-day-$it").fetchSemanticsNode().size }
            assertEquals(1, sizes.map { it.height }.distinct().size)
            assertTrue(sizes.maxOf { it.width } - sizes.minOf { it.width } <= 1)
            onAllNodesWithText("100%").assertCountEquals(0)
            onNode(hasText("待确认") and hasAnyAncestor(hasTestTag("calendar-day-$date")), useUnmergedTree = true).assertExists()
            onNode(hasText("已知2笔") and hasAnyAncestor(hasTestTag("calendar-day-$date")), useUnmergedTree = true).assertExists()
        }
        val target = File("../artifacts/calendar-large-font.png"); target.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
}
