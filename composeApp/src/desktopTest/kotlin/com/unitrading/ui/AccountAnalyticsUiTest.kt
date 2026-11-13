package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlin.test.assertTrue

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
        onNodeWithTag("analytics-coverage").assertTextContains("已加载平仓记录", substring = true)
    }
    /** The cache holds what this device happened to synchronize, so the header names its first day and its limits, not a span of days. */
    @Test fun headerSaysWhatTheFiguresCoverAndFromWhichDayTheCacheStarts() = runDesktopComposeUiTest(width = 400, height = 800) {
        val since = LocalDateTime(2026, 10, 20, 23, 30).toInstant(TimeZone.of("America/Chicago")).toEpochMilliseconds()
        var cachedSince by mutableStateOf<Long?>(since)
        setContent { TradingTheme { AccountAnalyticsPage(AccountInfo("a", "Demo 50K"), emptyList(), Modifier.requiredSize(400.dp, 800.dp), cachedSinceMs = cachedSince) } }
        onNodeWithTag("analytics-coverage").assertTextEquals("Demo 50K · 券商已同步记录 + 本机缓存（自 2026-10-20 起，仅含本机运行并选中该子账户时同步到的记录） · USD")
        onNodeWithText("30 天", substring = true).assertDoesNotExist()
        capture("account-analytics-cached-header")
        // The day is the one of the zone the page groups trades by.
        onNodeWithText("北京时间").performClick()
        onNodeWithTag("analytics-coverage").assertTextContains("自 2026-10-21 起", substring = true)
        runOnIdle { cachedSince = null }
        onNodeWithTag("analytics-coverage").assertTextEquals("Demo 50K · 已加载平仓记录 · USD")
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
        // The starred intervals stand by length although they were saved as 1m, 5m, 15s; the one shown without a star comes after them.
        fun x(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x
        assertTrue(x("interval-inline-15s") < x("interval-inline-1") && x("interval-inline-1") < x("interval-inline-5"))
        assertTrue(x("interval-inline-5") < x("interval-inline-30s"), "an interval without a star does not push the starred ones aside")
    }
}
