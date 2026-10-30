package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class MarketWatchUiTest {
    @get:Rule val compose = createComposeRule()
    private val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
    private val spec = Instrument("XAUUSD", "Gold / US Dollar", "贵金属", 2, tickSize = 1, tickValueMicrosPerLot = 1000000)
    private val specs = listOf(spec, spec.copy(symbol = "XAUAUD", name = "Gold / Australian Dollar"),
        spec.copy(symbol = "Nikkei225", name = "Nikkei 225 Index"), spec.copy(symbol = "DJ30", name = "Dow Jones 30"))
    private val catalog = MutableStateFlow(BrokerCatalog(specs, demo.accounts))
    private val plugin = object : BrokerPlugin by demo {
        override val catalog = this@MarketWatchUiTest.catalog
        override val instruments get() = catalog.value.instruments
    }
    private val state = mutableStateOf(AccountSnapshot("A", 0, positions = listOf(
        Position("long", "XAUUSD", Side.BUY, 100, 388000, 0, marginMicros = 0),
        Position("short", "Nikkei225", Side.SELL, 100, 6210000, 0, marginMicros = 0)),
        orders = listOf(order("entry", "XAUUSD", OrderRole.ENTRY), order("sl", "XAUUSD", OrderRole.STOP_LOSS),
            order("tp", "XAUUSD", OrderRole.TAKE_PROFIT), order("pending", "XAUAUD", OrderRole.ENTRY),
            order("unknown", "DJ30", OrderRole.UNRESOLVED))))
    private val market = mutableStateOf(MarketSnapshot(quotes = mapOf(
        "XAUUSD" to Quote(387996, 388008, 75082000), "XAUAUD" to Quote(552000, 552035, 75081000),
        "Nikkei225" to Quote(6210950, 6212250, 75080000), "DJ30" to Quote(5181550, 5181650, 75080000))))
    private var chosen: String? = null

    private fun order(id: String, symbol: String, role: OrderRole) =
        PendingOrder(id, OrderRequest(symbol, Side.BUY, OrderKind.LIMIT, 100), 0, role = role)

    private fun render(width: Int = 400, fontScale: Float = 1f, preset: ThemePreset = ThemePreset.TRADINGV) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.9f, fontScale)) {
            TradingTheme(ThemeSettings(preset = preset)) {
                Box(Modifier.requiredSize(width.dp, 800.dp).testTag("market-test-host")) {
                    MarketWatch(plugin, market.value, state.value, "XAUUSD", { chosen = it })
                }
            }
        } }
    }
    private fun capture(name: String) {
        val target = File(System.getenv("UNITRADING_MARKET_ARTIFACTS") ?: "../artifacts/market-watch", "$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onNodeWithTag("market-test-host").captureToImage().toAwtImage(), "png", target)
    }

    @Test fun accountMarkersRefreshAndRowsOpenChartsWithoutTrading() {
        render()
        compose.onNodeWithTag("symbol-XAUUSD").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "多头持仓 1 笔，入场挂单 1 笔，止损单 1 笔，止盈单 1 笔"))
        compose.onNodeWithContentDescription("Bid 卖出 3879.96", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask 买入 3880.08", useUnmergedTree = true).assertIsDisplayed()
        capture("phone-dark")
        val before = state.value
        compose.onNodeWithTag("symbol-XAUUSD").performClick()
        compose.runOnIdle { assertEquals("XAUUSD", chosen); assertEquals(before, state.value); state.value = AccountSnapshot("B", 0) }
        compose.onNodeWithTag("market-corner-XAUUSD", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("symbol-XAUUSD").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "无持仓或挂单"))
        compose.runOnIdle { market.value = MarketSnapshot(connected = false) }
        compose.onNodeWithText("连接已断开").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Bid 卖出 暂无报价", useUnmergedTree = true).assertCountEquals(4)
    }

    @Test fun smallScreenLargeTypeKeepsBothWholePricesAndLegendAccessible() {
        render(320, 1.3f, ThemePreset.LIGHT)
        val bid = compose.onNodeWithContentDescription("Bid 卖出 3879.96", useUnmergedTree = true)
        val ask = compose.onNodeWithContentDescription("Ask 买入 3880.08", useUnmergedTree = true)
        bid.assertIsDisplayed(); ask.assertIsDisplayed()
        val bidBounds = bid.fetchSemanticsNode().boundsInRoot
        val askBounds = ask.fetchSemanticsNode().boundsInRoot
        assertTrue(bidBounds.right <= askBounds.left)
        capture("phone-small-light")
        compose.onNodeWithTag("market-marker-help").performClick()
        compose.onNodeWithText("行情标记").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("symbol-search").performTextReplacement("Gold")
        compose.onNodeWithTag("symbol-XAUUSD").assertExists()
        compose.onNodeWithTag("symbol-Nikkei225").assertDoesNotExist()
    }

    @Test fun continuousAliasUsesItsDatedContractAndClearsWhenUnresolved() {
        catalog.value = catalog.value.copy(instruments = listOf(spec.copy(symbol = "@GC", isContinuous = true, tradeSymbol = "XAUUSD")))
        render(240)
        compose.onNodeWithTag("market-corner-@GC", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Ask 买入 3880.08", useUnmergedTree = true).assertIsDisplayed()
        capture("desktop-narrow")
        compose.runOnIdle { catalog.value = catalog.value.copy(instruments = listOf(spec.copy(symbol = "@GC", isContinuous = true))) }
        compose.onNodeWithTag("market-corner-@GC", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Ask 买入 暂无报价", useUnmergedTree = true).assertIsDisplayed()
    }
}
