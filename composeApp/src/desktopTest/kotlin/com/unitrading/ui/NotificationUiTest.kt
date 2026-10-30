package com.unitrading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class NotificationUiTest {
    @get:Rule val compose = createComposeRule()

    private class Broker : BrokerPlugin {
        override val descriptor = BrokerDescriptor("notice-test", "Tradovate Demo", "Synthetic notification fixture", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(resynchronize = true)
        override val instruments = listOf(Instrument("MESZ6", "Micro S&P", "期货", 6, 250_000, 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0, displayPriceDecimals = 2))
        override val accounts = listOf(AccountInfo("test", "测试账户"))
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(mapOf("MESZ6" to Quote(5_500_000_000, 5_500_250_000, 0)), connected = true,
            tradingReady = true, statusMessage = "已连接：服务器行情 · 净额持仓"))
        var syncCalls = 0
        var orderCalls = 0
        var failSync = false
        var result = TradeResult(true, "Tradovate 已受理，最终结果以服务器订单/成交更新为准")
        override suspend fun resynchronize() { syncCalls++; if (failSync) error("offline") }
        override fun history(symbol: String, timeframe: Timeframe) = (0..79).map {
            val open = 5_500_000_000L + (it % 8 - 4) * 250_000
            Candle(it * 300_000L, open, open + 500_000, open - 500_000, open + (it % 3 - 1) * 250_000)
        }
        override fun openSession(accountId: String) = object : AccountSession {
            override val account = accounts.first()
            override val state = MutableStateFlow(AccountSnapshot(accountId, 25_000_000_000))
            override fun preview(request: OrderRequest) = OrderPreview(5_500_250_000)
            override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(entryPrice)
            override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult { orderCalls++; return result }
            override suspend fun closePosition(requestId: String, positionId: String) = result
            override suspend fun cancelOrder(requestId: String, orderId: String) = result
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = result
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = result
        }
    }

    private fun render(broker: Broker, phone: Boolean = false) {
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        runBlocking { runtime.theme.apply(ThemeSettings(ThemePreset.TRADINGV)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.7f)) {
            Box(Modifier.requiredSize(if (phone) 400.dp else 1400.dp, if (phone) 800.dp else 900.dp)) { TradingScreen(runtime) }
        } }
    }
    private fun chartBounds() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
    private fun capture(name: String) {
        val file = File("../artifacts/$name.png"); file.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun desktopToolbarAndFloatingReceiptNeverChangeChartDimensions() {
        val broker = Broker()
        render(broker)
        assertTrue(compose.onNodeWithTag("app-toolbar").fetchSemanticsNode().size.height <= 44 * .7f + 1)
        compose.onNodeWithText(broker.market.value.statusMessage).assertDoesNotExist()
        val before = chartBounds()
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.onNodeWithTag("trade-notice").assertTextContains("最终结果以服务器订单/成交更新为准", substring = true)
        assertEquals(1, broker.orderCalls)
        assertEquals(before, chartBounds())
        val overlay = compose.onNodeWithTag("trade-notice-card").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue(overlay.top > toolbar.bottom)
        capture("desktop-floating-notice")
        compose.onNodeWithTag("dismiss-trade-notice").performClick()
        compose.onNodeWithTag("trade-notice").assertDoesNotExist()
        assertEquals(before, chartBounds())
    }

    @Test fun connectionDetailsKeepSyncAccessibleAndFailuresStayUntilDismissed() {
        val broker = Broker().apply { failSync = true }
        render(broker)
        val before = chartBounds()
        compose.onNodeWithTag("connection-status").performClick()
        compose.onNodeWithText(broker.market.value.statusMessage).assertIsDisplayed()
        compose.onNodeWithTag("resynchronize-account").performClick()
        assertEquals(1, broker.syncCalls)
        compose.onNodeWithTag("trade-notice").assertTextContains("同步失败", substring = true)
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("trade-notice").assertExists()
        assertEquals(before, chartBounds())
        compose.onNodeWithTag("dismiss-trade-notice").performClick()
        compose.onNodeWithTag("trade-notice").assertDoesNotExist()
        compose.runOnIdle { broker.market.value = broker.market.value.copy(connected = false, tradingReady = false, statusMessage = "连接已断开") }
        compose.onNodeWithTag("connection-status").assertTextContains("已断开")
        compose.onNodeWithTag("connection-status").performClick()
        compose.onNodeWithTag("resynchronize-account").assertIsNotEnabled()
    }

    @Test fun phoneConnectionDetailsAndReceiptOverlayKeepTheChartAndNavigation() {
        val broker = Broker()
        render(broker, phone = true)
        val before = chartBounds()
        compose.onNodeWithTag("connection-status").performClick()
        compose.onNodeWithTag("resynchronize-account").performClick()
        compose.onNodeWithTag("trade-notice").assertTextEquals("账户状态已同步")
        compose.onNodeWithTag("mobile-navigation").assertIsDisplayed()
        assertEquals(before, chartBounds())
        capture("mobile-floating-notice")
    }

    @Test fun ordinaryNoticesExpireAndRepeatedMessagesRestartTheTimer() {
        var notice by mutableStateOf<TradingNotice?>(TradingNotice(1, "相同回执"))
        compose.setContent { TradingTheme { Box(Modifier.requiredSize(400.dp, 300.dp)) {
            notice?.let { FloatingNoticeCard(it, "timed-notice", { notice = null }) }
        } } }
        compose.onNodeWithTag("timed-notice").assertExists()
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { notice = TradingNotice(2, "相同回执") }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("timed-notice").assertExists()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithTag("timed-notice").assertDoesNotExist()
    }

    @Test fun theCardBlocksClicksBehindItAndLeavesTheRestOfTheWorkspaceInteractive() {
        var underlyingClicks = 0
        var dismissed = false
        compose.setContent { TradingTheme { Box(Modifier.requiredSize(500.dp, 400.dp)) {
            Box(Modifier.fillMaxSize().testTag("underlay").clickable { underlyingClicks++ })
            if (!dismissed) FloatingNoticeCard(TradingNotice(1, "结果待核对", persistent = true), "blocking-notice", { dismissed = true },
                Modifier.align(Alignment.TopEnd).width(320.dp))
        } } }
        compose.onNodeWithTag("blocking-notice").performTouchInput { click() }
        assertEquals(0, underlyingClicks)
        compose.onNodeWithTag("underlay").performTouchInput { click(Offset(10f, height - 10f)) }
        assertEquals(1, underlyingClicks)
        compose.onNodeWithTag("dismiss-blocking-notice").performClick()
        assertTrue(dismissed)
    }
}
