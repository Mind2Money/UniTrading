package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.app.CredentialStore
import com.unitrading.broker.*
import com.unitrading.chart.dateTime
import com.unitrading.tradovate.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class RemoteWorkbenchTest {
    @Test fun pairedHistoryExpandsLegsAndLocatesChartWithoutShowingRawExecutions() {
        val source = remote()
        val at = 1_798_589_000_000L
        val fill = Execution("10", "MESZ6", Side.BUY, 300, 5_500_000_000, at, "20")
        val pair = ClosedTrade("pair-1", "p", "MESZ6", Side.BUY, 100, 5_499_000_000, 5_500_000_000, at - 300_000, at,
            netPnlMicros = null, reason = "服务器成交配对", grossPnlMicros = 5_000_000)
        val snapshot = MutableStateFlow(source.openSession(source.accounts.single().id).state.value.copy(
            executions = listOf(fill, fill.copy(id = "11")), trades = listOf(pair)))
        val plugin = object : BrokerPlugin by source {
            override fun history(symbol: String, timeframe: Timeframe) = (0..100).map {
                Candle(at - (100 - it) * timeframe.minutes * 60_000L, 5_500_000_000, 5_501_000_000, 5_499_000_000, 5_500_000_000)
            }
            override fun openSession(accountId: String): AccountSession {
                val delegate = source.openSession(accountId)
                return object : AccountSession by delegate {
                    override val state = snapshot
                }
            }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("trade-terminal").performScrollToKey("section-history")
        compose.onNodeWithText("历史 1").assertExists()
        compose.onNodeWithTag("execution-10").assertDoesNotExist()
        compose.onNodeWithTag("execution-11").assertDoesNotExist()
        compose.onNodeWithTag("paired-trade-details-pair-1").assertDoesNotExist()
        compose.onNodeWithText("毛 +5.00 USD").performScrollTo().assertExists()
        compose.onNodeWithText("费用未知，净盈亏待确认").assertExists()
        compose.onNodeWithTag("paired-trade-pair-1").performClick()
        compose.onNodeWithText("入场 · 买入   1 张   5499.00").performScrollTo().assertExists()
        compose.onNodeWithText("出场 · 卖出   1 张   5500.00").performScrollTo().assertExists()
        val inDetails = hasAnyAncestor(hasTestTag("paired-trade-details-pair-1"))
        compose.onNode(hasText("${dateTime(pair.openedAtMs)} UTC") and inDetails).assertExists()
        compose.onNode(hasText("${dateTime(pair.closedAtMs)} UTC") and inDetails).assertExists()
        val target = File("../artifacts/paired-trade-history.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", target)
        compose.onNodeWithTag("locate-trade-pair-1").performScrollTo().performClick()
        compose.onNodeWithText("选中历史  #pair-1", substring = true).assertExists()
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        compose.onNodeWithTag("paired-trade-pair-1").performScrollTo().performClick()
        compose.onNodeWithTag("paired-trade-details-pair-1").assertDoesNotExist()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(trades = emptyList()) }
        compose.onNodeWithTag("trade-terminal").performScrollToKey("section-history")
        compose.onNodeWithText("历史 0").assertExists()
        compose.onNodeWithText("暂无已配对交易").assertExists()
        compose.onNodeWithTag("execution-10").assertDoesNotExist()
    }
    @Test fun removingSelectedInstrumentFallsBackAndEmptyCatalogCanRecover() {
        val source = remote()
        val changes = MutableStateFlow(source.catalog.value)
        val plugin = object : BrokerPlugin by source {
            override val catalog = changes
            override val instruments get() = changes.value.instruments
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("order-quantity").performTextReplacement("3")
        val replacement = source.instruments.single().copy(symbol = "ALTZ6")
        compose.runOnIdle { changes.value = changes.value.copy(instruments = listOf(replacement)) }
        compose.onAllNodesWithText("ALTZ6").onFirst().assertExists()
        compose.onNodeWithTag("order-quantity").assertTextContains("1")
        compose.runOnIdle { changes.value = changes.value.copy(instruments = emptyList()) }
        compose.onNodeWithText("查询合约").assertExists()
        compose.runOnIdle { changes.value = changes.value.copy(instruments = listOf(replacement)) }
        compose.onNodeWithTag("order-quantity").assertExists()
    }
    @get:Rule val compose = createComposeRule()
    @Test fun expiredSavedLoginIsVisibleAndClearingRequiresConfirmation() {
        val store = object : CredentialStore {
            var payload: String? = """{"sessions":[{"environment":"DEMO","accessToken":"synthetic-test-token-1234567890","mdAccessToken":null,"userId":42,"userName":"test","expiresAtMs":1}]}"""
            override suspend fun read() = payload
            override suspend fun write(value: String) { payload = value }
            override suspend fun clear() { payload = null }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), credentialStore = store, includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.waitUntil(5_000) { runtime.savedLogins.message.value?.contains("已过期") == true }
        compose.onNodeWithTag("saved-login-status").assertTextContains("已过期", substring = true)
        compose.onNodeWithTag("open-accounts").performClick()
        compose.onNodeWithText("本机已保存 1 个 Tradovate 登录").assertExists()
        compose.onNodeWithTag("forget-saved-logins").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        kotlin.test.assertEquals(1, runtime.savedLogins.savedCount.value)
        compose.onNodeWithTag("forget-saved-logins").performScrollTo().performClick()
        compose.onNodeWithText("确认清除").performClick()
        compose.waitUntil(5_000) { runtime.savedLogins.savedCount.value == 0 }
        kotlin.test.assertFalse(store.payload.orEmpty().contains("synthetic-test-token"))
    }
    private fun remote(empty: Boolean = false): BrokerPlugin = object : BrokerPlugin {
        override val descriptor = BrokerDescriptor("remote-test", "Remote test", "Server data", false, BrokerEnvironment.LIVE)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val instruments = if (empty) emptyList() else listOf(Instrument("MESZ6", "Micro S&P", "Futures", 6, 250000, 1250000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0, displayPriceDecimals = 2, estimatesIncludeFees = false))
        override val accounts = if (empty) emptyList() else listOf(AccountInfo("123", "Remote account"))
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(connected = true, tradingReady = true, statusMessage = "服务器状态"))
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
        override fun openSession(accountId: String) = object : AccountSession {
            override val account = accounts.first()
            override val state = MutableStateFlow(AccountSnapshot(accountId, 0, balanceKnown = false, equityKnown = false, marginKnown = false))
            override fun preview(request: OrderRequest) = OrderPreview(5_500_000_000, marginKnown = false, feesKnown = false)
            override fun previewOrderModification(orderId: String, entryPrice: Long) = preview(OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, entryPrice))
            override suspend fun placeOrder(requestId: String, request: OrderRequest) = TradeResult(false, "Test fixture: no orders")
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "Test fixture")
            override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "Test fixture")
            override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "Test fixture")
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "Test fixture")
        }
    }
    private fun render(plugin: BrokerPlugin) {
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText("Remote test").performClick()
    }
    @Test fun remoteEnvironmentAndIntegerContractsAreVisibleBeforeConfirmation() {
        render(remote())
        compose.onAllNodesWithText("Live 实盘").onFirst().assertExists()
        compose.onNodeWithTag("order-quantity").assertTextContains("1")
        compose.onNodeWithText("数量（张）").assertExists()
        compose.onNodeWithText("全部为本地生成的模拟行情").assertDoesNotExist()
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithText("确认 Tradovate Live · 真实账户 下单").assertExists()
        compose.onNodeWithText("未知 / 服务器校验").assertExists()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun capturedSessionProgressIsVisibleAndLoginCanBeCancelled() {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        var cancelled = false
        var dismissed = false
        val login = object : TradovateLogin {
            override suspend fun signIn(environment: TradovateEnvironment, onProgress: (String) -> Unit): TradovateSession {
                onProgress("网页登录已成功，正在等待保存密码")
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        compose.setContent { TradingTheme { TradovateConnectionDialog(runtime, login, {}, { dismissed = true }) } }
        compose.onNodeWithText("打开官方登录页").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("网页登录已成功，正在等待保存密码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login-progress").assertTextEquals("网页登录已成功，正在等待保存密码")
        compose.onNodeWithTag("cancel-login").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { cancelled && dismissed }
    }
    @Test fun emptyRemoteCatalogDoesNotCrashOrCreateMockInstruments() {
        render(remote(empty = true))
        compose.onNodeWithText("查询合约").assertExists()
        compose.onNodeWithText("账户和合约由交易商提供。列表为空时，应用不会用本地数据填充。").assertExists()
        compose.onNodeWithTag("review-order").assertDoesNotExist()
    }
}
