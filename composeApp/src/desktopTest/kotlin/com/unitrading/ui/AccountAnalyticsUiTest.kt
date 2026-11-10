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
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.time.Clock
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class AccountAnalyticsUiTest {
    private fun ComposeUiTest.capture(name: String) {
        val file = File("../artifacts/$name.png"); file.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }
    @Test fun accountHubHasOneTradeDestination() = runDesktopComposeUiTest(width = 400, height = 800) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) } } }
        onNodeWithTag("nav-2").assertDoesNotExist()
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("account-tab-1").performClick()
        onNodeWithTag("trade-terminal").assertIsDisplayed()
        capture("account-merged-trading")
        onNodeWithTag("account-tab-0").performClick()
        onNodeWithTag("account-overview").assertIsDisplayed()
        onNodeWithTag("account-tab-2").performClick()
        onNodeWithTag("account-analytics").assertIsDisplayed()
    }
    @Test fun phoneCalendarShowsActualDailyResultsAndNavigatesMonths() = runDesktopComposeUiTest(width = 400, height = 800) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Chicago")).date
        val month = LocalDate(today.year, today.month, 1)
        val trades = listOf(73_500_000L, 13_250_000L, -98_250_000L, 56_500_000L).mapIndexed { index, pnl ->
            val time = LocalDateTime(today.year, today.month, index + 2, 12, 0).toInstant(TimeZone.of("America/Chicago")).toEpochMilliseconds()
            ClosedTrade("t$index", "p$index", "MESZ6", Side.BUY, 100, 100, 110, time - 60000, time, pnl, "fixture")
        }
        setContent { TradingTheme(ThemeSettings(ThemePreset.TRADINGV)) { AccountAnalyticsPage(AccountInfo("a", "Demo 50K"), trades, Modifier.requiredSize(400.dp, 800.dp)) } }
        capture("account-analytics-summary")
        onNodeWithText("75.00%").assertExists()
        val date = month.plus(1, DateTimeUnit.DAY)
        onNodeWithTag("account-analytics").performScrollToNode(hasTestTag("calendar-day-$date"))
        onNodeWithTag("calendar-day-$date").performClick()
        capture("account-pnl-calendar")
        onNodeWithTag("account-analytics").performScrollToNode(hasText("$date"))
        onNodeWithText("$date").assertIsDisplayed()
        onNodeWithTag("account-analytics").performScrollToNode(hasTestTag("calendar-next"))
        onNodeWithTag("calendar-next").performClick()
        val next = month.plus(1, DateTimeUnit.MONTH)
        onNodeWithTag("calendar-month").assertTextEquals("${next.year}年${next.month.number}月")
        onNodeWithTag("calendar-previous").performClick()
        onNodeWithTag("analytics-toggle-trades").performClick()
        onNodeWithTag("account-analytics").performScrollToNode(hasText("本月平仓记录"))
        onNodeWithText("本月平仓记录").assertIsDisplayed()
    }
    @Test fun phoneShowsMultipleFavoriteIntervalsAndSecondsSurviveSelection() = runDesktopComposeUiTest(width = 400, height = 800) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        runBlocking { runtime.chartPreferences.apply(ChartPreferences().withFavorites(listOf(Timeframe.M1, Timeframe.M5, Timeframe.ofSeconds(15)))) }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) } } }
        onNodeWithTag("interval-inline-15s").performScrollTo().performClick()
        onNodeWithTag("interval-inline-1").assertExists()
        onNodeWithTag("interval-inline-5").assertExists()
        capture("chart-seconds-toolbar")
        onNodeWithTag("interval-menu").performScrollTo().performClick()
        onNodeWithTag("interval-option-30s").performScrollTo().performClick()
        onNodeWithTag("interval-inline-30s").performScrollTo().assertTextContains("30s")
    }
}
