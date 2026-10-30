package com.unitrading.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class DailyPnlUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun financialStripShowsBrokerTotalDateAndMissingDataExplicitly() {
        var snapshot by mutableStateOf(AccountSnapshot("a", 25_000_000_000, dailyPnl = DailyPnl(123_450_000, tradingDay = "2026-11-02", updatedAtMs = 0)))
        compose.setContent { TradingTheme { AccountStrip(snapshot) } }
        compose.onNodeWithTag("daily-pnl-value").assertTextEquals("+123.45")
        compose.onNodeWithTag("daily-pnl-source").assertTextContains("2026-11-02 交易日 · 快照 00:00:00 UTC")
        compose.runOnIdle { snapshot = snapshot.copy(accountId = "b", dailyPnl = DailyPnl(-67_890_000, updatedAtMs = 1000)) }
        compose.onNodeWithTag("daily-pnl-value").assertTextEquals("-67.89")
        compose.runOnIdle { snapshot = snapshot.copy(dailyPnl = null) }
        compose.onNodeWithTag("daily-pnl-value").assertTextEquals("—")
        compose.onNodeWithTag("daily-pnl-source").assertTextEquals("等待券商数据")
    }

    @Test fun phoneChartBarShowsDailyAndFloatingPnlSeparatelyAndLinksToTrading() {
        val spec = Instrument("MESZ6", "Micro S&P", "期货", 2, tickValueMicrosPerLot = 1250000)
        val snapshot = AccountSnapshot("a", 25_000_000_000, equityMicros = 25_010_000_000, dailyPnl = DailyPnl(-12_500_000, updatedAtMs = 0))
        var opened = false
        compose.setContent { TradingTheme { Column(Modifier.requiredWidth(400.dp)) {
            MobileTradeBar(spec, Quote(550000, 550025, 0), snapshot, {}, {}, { opened = true })
            AccountStrip(snapshot)
        } } }
        compose.onNodeWithTag("panel-daily-pnl").assertTextContains("-12.50")
        compose.onNodeWithTag("panel-positions").assertTextContains("+10.00")
        val target = File("../artifacts/daily-pnl-mobile.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", target)
        compose.onNodeWithTag("panel-daily-pnl").performClick()
        assertTrue(opened)
    }
}
