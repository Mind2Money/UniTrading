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
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithText("图表 / 指标 @TEST → 下单 ${dated.symbol}").assertExists()
        compose.runOnIdle {
            changes.value = changes.value.copy(instruments = listOf(alias.copy(tradeSymbol = null, tradeSymbolStatus = "主力无法确认：日成交量查询超时")) + demo.instruments)
        }
        compose.onNodeWithTag("continuous-route").assertTextContains("主力无法确认：日成交量查询超时", substring = true)
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.runOnIdle { assertNull(submitted) }
    }
}
