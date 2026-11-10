package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class ContinuousContractUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chartUsesContinuousSymbolWhileConfirmationPinsDatedTargetAndRolloverClearsIt() {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val dated = demo.instruments.first()
        val alias = dated.copy(symbol = "@TEST", isContinuous = true, tradeSymbol = dated.symbol)
        val changes = MutableStateFlow(BrokerCatalog(listOf(alias) + demo.instruments, demo.accounts))
        var chartSymbol: String? = null
        var previewed: OrderRequest? = null
        var submitted: OrderRequest? = null
        val plugin = object : BrokerPlugin by demo {
            override val catalog = changes
            override val instruments get() = changes.value.instruments
            override fun history(symbol: String, timeframe: Timeframe): List<Candle> {
                chartSymbol = symbol
                return demo.history(dated.symbol, timeframe)
            }
            override fun openSession(accountId: String): AccountSession {
                val session = demo.openSession(accountId)
                return object : AccountSession by session {
                    override fun preview(request: OrderRequest): OrderPreview { previewed = request; return session.preview(request) }
                    override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
                        submitted = request
                        return TradeResult(false, "test only")
                    }
                }
            }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin)).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("continuous-route").assertTextContains("下单 / 买卖报价 ${dated.symbol}", substring = true)
        compose.runOnIdle {
            assertEquals("@TEST", chartSymbol)
            assertEquals(dated.symbol, previewed?.symbol)
            assertEquals("@TEST", previewed?.sourceSymbol)
        }
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("图表 / 指标 @TEST → 下单 ${dated.symbol}").assertExists()
        compose.runOnIdle {
            changes.value = changes.value.copy(instruments = listOf(alias.copy(tradeSymbol = null, tradeSymbolStatus = "主力无法确认：日成交量查询超时")) + demo.instruments)
        }
        compose.onNodeWithTag("continuous-route").assertTextContains("主力无法确认：日成交量查询超时", substring = true)
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.runOnIdle { assertNull(submitted) }
    }

    /** A dated chart has only that contract's own history, so a list row must not move the chart off its continuous series. */
    @Test fun positionRowsShowTheContinuousChartOfTheirContract() {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val dated = demo.instruments[0]
        val old = demo.instruments[1]
        val alias = dated.copy(symbol = "@TEST", isContinuous = true, tradeSymbol = dated.symbol)
        val changes = MutableStateFlow(BrokerCatalog(listOf(alias) + demo.instruments, demo.accounts))
        val rows = listOf(Position("current", dated.symbol, Side.BUY, 100, 110000, 1_798_589_000_000L, marginMicros = 0),
            Position("old", old.symbol, Side.BUY, 100, 110000, 1_798_589_000_000L, marginMicros = 0))
        var chartSymbol: String? = null
        val plugin = object : BrokerPlugin by demo {
            override val catalog = changes
            override val instruments get() = changes.value.instruments
            // The screen asks for every open chart at once; the first is the one being worked on.
            override fun watchAll(series: List<Pair<String, Timeframe>>) { chartSymbol = series.first().first }
            override fun history(symbol: String, timeframe: Timeframe) = demo.history(dated.symbol, timeframe)
            override fun openSession(accountId: String): AccountSession {
                val session = demo.openSession(accountId)
                return object : AccountSession by session {
                    override val state = MutableStateFlow(session.state.value.copy(positions = rows))
                }
            }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin)).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.runOnIdle { assertEquals("@TEST", chartSymbol) }
        compose.onNodeWithTag("position-current").performClick()
        compose.runOnIdle { assertEquals("@TEST", chartSymbol) }
        // A contract no continuous series trades any more has only its dated chart.
        compose.onNodeWithTag("position-old").performClick()
        compose.runOnIdle { assertEquals(old.symbol, chartSymbol) }
        compose.onNodeWithTag("position-current").performClick()
        compose.runOnIdle { assertEquals("@TEST", chartSymbol) }
    }

    private fun openSavedChart(demo: BrokerPlugin, saved: Instrument, catalog: List<Instrument>, watched: List<String>, charts: MutableList<String>): MutableStateFlow<BrokerCatalog> {
        val changes = MutableStateFlow(BrokerCatalog(catalog, demo.accounts))
        val plugin = object : BrokerPlugin by demo {
            override val catalog = changes
            override val instruments get() = changes.value.instruments
            override fun watchAll(series: List<Pair<String, Timeframe>>) { charts += series.first().first }
            override fun history(symbol: String, timeframe: Timeframe) = demo.history(saved.symbol, timeframe)
        }
        val store = MemorySnapshotStore()
        store.write("market-watch-v1", """{"${demo.descriptor.id}":[${watched.joinToString(",") { "\"$it\"" }}]}""")
        store.write("chart-v1", """{"lastSymbols":{"${demo.descriptor.id}":"${saved.symbol}"}}""")
        val runtime = TradingRuntime(store, listOf(plugin)).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        return changes
    }

    /** A list click used to leave a dated chart saved as the one to reopen; its continuous series loads later than the contract. */
    @Test fun savedDatedChartMovesToItsContinuousSeriesOnceTheCatalogNamesIt() {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val dated = demo.instruments.first()
        val charts = mutableListOf<String>()
        val changes = openSavedChart(demo, dated, demo.instruments, listOf("@TEST"), charts)
        compose.runOnIdle { assertEquals(dated.symbol, charts.last()) }
        // Unconfirmed, as after a restart: only the last confirmed contract links the two.
        val alias = dated.copy(symbol = "@TEST", isContinuous = true, tradeSymbol = null, quoteSymbol = dated.symbol)
        compose.runOnIdle { changes.value = changes.value.copy(instruments = listOf(alias) + demo.instruments) }
        compose.runOnIdle { assertEquals("@TEST", charts.last()) }
    }

    @Test fun datedChartOnTheWatchListStaysDated() {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val dated = demo.instruments.first()
        val alias = dated.copy(symbol = "@TEST", isContinuous = true, tradeSymbol = dated.symbol)
        val charts = mutableListOf<String>()
        openSavedChart(demo, dated, listOf(alias) + demo.instruments, listOf(dated.symbol, "@TEST"), charts)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(setOf(dated.symbol), charts.toSet()) }
    }
}
