package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.app.ReplayEquityPoint
import com.unitrading.app.ReplayRecord
import com.unitrading.broker.ClosedTrade
import com.unitrading.broker.Side
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ReplayDashboardUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dashboardShowsEquityPerformanceAndClosedTrades() {
        val trade = ClosedTrade("t1", "p1", "MESZ6", Side.BUY, 100, 10_000, 10_100,
            1_700_000_000_000, 1_700_000_060_000, 5_000_000, "手动平仓")
        val record = ReplayRecord("id", "@MES", "USD", 50_000_000_000, 1_700_000_060_000, listOf(trade),
            equity = listOf(ReplayEquityPoint(trade.openedAtMs, 50_000_000_000), ReplayEquityPoint(trade.closedAtMs, 50_005_000_000)), priceDecimals = 2)
        compose.setContent { TradingTheme { ReplayDashboard(record) } }
        compose.onNodeWithTag("replay-dashboard").assertExists()
        compose.onNodeWithText("净值曲线").assertExists()
        compose.onNodeWithText("月度表现").assertExists()
        compose.onNodeWithText("星期表现").assertExists()
        compose.onNodeWithTag("replay-dashboard").performScrollToNode(hasTestTag("replay-report-trade-count"))
        compose.onNodeWithTag("replay-report-trade-count").assertTextContains("1 笔", substring = true)
        compose.onNodeWithText("MESZ6 · 多 · 1.00").assertExists()
    }
}
