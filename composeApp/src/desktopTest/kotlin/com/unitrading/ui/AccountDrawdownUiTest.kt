package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class AccountDrawdownUiTest {
    @get:Rule val compose = createComposeRule()

    private val drawdown = AccountDrawdown(DrawdownRule(23_500_000_000, 1_500_000_000, 25_000_000_000, mode = DrawdownMode.REAL_TIME), 375_000_000, 0.75, DrawdownValueSource.QUOTES)
    private val funded = AccountSnapshot("a", 23_900_000_000, equityMicros = 23_875_000_000, drawdown = drawdown)

    /** The status bar's tooltip anchor merges its figures, so they are found in the unmerged tree there. */
    private fun room() = compose.onNodeWithTag("drawdown-room-value", useUnmergedTree = true)
    private fun danger() = compose.onNodeWithTag("drawdown-danger", useUnmergedTree = true)

    private fun capture(name: String) {
        val target = File("../artifacts/$name.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", target)
    }

    @Test fun theStatusBarShowsTheRoomTheRuleAndTheDangerAndFollowsTheAccount() {
        var snapshot by mutableStateOf(funded)
        var connected by mutableStateOf(true)
        compose.setContent { TradingTheme { AccountStrip(snapshot, Modifier.requiredWidth(1400.dp), connected = connected) } }
        room().assertTextEquals("375.00")
        compose.onNodeWithText("可回撤额度（TDD · 线 23500.00）", useUnmergedTree = true).assertIsDisplayed()
        danger().assertContentDescriptionEquals("危险度 75% · 行情实时")
        compose.onNodeWithText("75%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("危险度（行情实时）", useUnmergedTree = true).assertIsDisplayed()
        capture("account-drawdown-strip")
        compose.runOnIdle { connected = false }
        danger().assertContentDescriptionEquals("危险度 75% · 行情中断")
        compose.runOnIdle { connected = true; snapshot = snapshot.copy(accountId = "b", drawdown = drawdown.copy(roomMicros = -46_500_000, danger = 1.0)) }
        room().assertTextEquals("-46.50")
        compose.onNodeWithText("100%", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theStatusBarNeverShowsAnUnknownFigureAsZero() {
        var snapshot by mutableStateOf(AccountSnapshot("demo", 25_000_000_000))
        compose.setContent { TradingTheme { AccountStrip(snapshot, Modifier.requiredWidth(1400.dp)) } }
        room().assertTextEquals("—")
        danger().assertContentDescriptionEquals("危险度 —")
        compose.onNodeWithText("可回撤额度", useUnmergedTree = true).assertIsDisplayed()
        // A rule the broker names without a line: the rule shows, room and danger stay unknown.
        compose.runOnIdle { snapshot = snapshot.copy(drawdown = AccountDrawdown(DrawdownRule(mode = DrawdownMode.OTHER, reported = true, criteria = "Loss Limit"))) }
        room().assertTextEquals("—")
        compose.onNodeWithText("可回撤额度（其他规则 · 券商未上报爆号线）", useUnmergedTree = true).assertIsDisplayed()
        // A line with nothing to measure it against: the room shows, danger stays unknown.
        compose.runOnIdle { snapshot = snapshot.copy(drawdown = AccountDrawdown(DrawdownRule(47_500_000_000, mode = DrawdownMode.FIXED, reported = true), 500_000_000, null, DrawdownValueSource.CASH)) }
        room().assertTextEquals("500.00")
        danger().assertContentDescriptionEquals("危险度 —")
    }

    /** A demo broker reporting [drawdown], whose link the test can drop. */
    private fun broker(feed: MutableStateFlow<MarketSnapshot>): TradingRuntime {
        val source = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }.registry.plugins.first()
        feed.value = source.market.value.copy(connected = true)
        val plugin = object : BrokerPlugin by source {
            override val market = feed
            override fun openSession(accountId: String): AccountSession {
                val delegate = source.openSession(accountId)
                return object : AccountSession by delegate { override val state = MutableStateFlow(delegate.state.value.copy(drawdown = drawdown)) }
            }
        }
        return TradingRuntime(MemorySnapshotStore(), listOf(plugin)).apply { paused.value = true }
    }

    @Test fun theWorkspaceStatusBarTakesTheFiguresAndTheLinkFromTheBroker() {
        val feed = MutableStateFlow(MarketSnapshot())
        val runtime = broker(feed)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        room().assertTextEquals("375.00")
        danger().assertContentDescriptionEquals("危险度 75% · 行情实时")
        feed.value = feed.value.copy(connected = false)
        compose.waitForIdle()
        danger().assertContentDescriptionEquals("危险度 75% · 行情中断")
    }

    @Test fun thePhoneTradePageTakesTheFiguresAndTheLinkFromTheBroker() {
        val feed = MutableStateFlow(MarketSnapshot())
        val runtime = broker(feed)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) { Box(Modifier.requiredSize(400.dp, 740.dp)) { TradingScreen(runtime) } } }
        compose.onNodeWithTag("nav-2").performClick().assertIsSelected()
        compose.onNodeWithTag("drawdown-room-value").assertTextEquals("375.00")
        compose.onNodeWithTag("drawdown-detail").assertIsDisplayed()
        feed.value = feed.value.copy(connected = false)
        compose.waitForIdle()
        compose.onNodeWithTag("drawdown-danger").assertContentDescriptionEquals("危险度 75% · 行情中断")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun hoverSpellsOutHowTheLineComesAbout() {
        compose.setContent { TradingTheme { AccountStrip(funded, Modifier.requiredWidth(1400.dp)) } }
        room().performMouseInput { enter(center); moveBy(androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.waitUntilAtLeastOneExists(hasText("爆号线 23500.00：最高净值 25000.00 − 追踪回撤 1500.00"), 5_000)
        room().performMouseInput { exit() }
    }

    @Test fun thePhoneCardSpellsOutTheRuleWithNothingHidden() {
        var snapshot by mutableStateOf(funded)
        compose.setContent { TradingTheme { AccountSummary(snapshot, Modifier.requiredWidth(360.dp)) } }
        compose.onNodeWithTag("drawdown-room-value").assertTextEquals("375.00").assertIsDisplayed()
        compose.onNodeWithTag("drawdown-rule").assertTextEquals("TDD · 线 23500.00").assertIsDisplayed()
        compose.onNodeWithTag("drawdown-danger").assertContentDescriptionEquals("危险度 75% · 行情实时").assertIsDisplayed()
        compose.onNodeWithTag("drawdown-detail").assertTextEquals("爆号线 23500.00：最高净值 25000.00 − 追踪回撤 1500.00").assertIsDisplayed()
        capture("account-drawdown-phone")
        compose.runOnIdle { snapshot = snapshot.copy(drawdown = null) }
        compose.onNodeWithTag("drawdown-room-value").assertTextEquals("—")
        compose.onNodeWithTag("drawdown-rule").assertDoesNotExist()
        compose.onNodeWithTag("drawdown-detail").assertTextEquals("未取得券商的回撤规则")
    }
}
