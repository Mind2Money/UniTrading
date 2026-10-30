package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private class OfflineProtectionBroker(unresolved: Boolean = false) : BrokerPlugin {
    val spec = Instrument("TESTZ6", "Offline futures", "离线测试", 2, 25, 1_250_000, minLotsHundredths = 100,
        lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0, estimatesIncludeFees = false)
    val time = 1_798_589_000_000L
    override val descriptor = BrokerDescriptor("offline-protection", "离线保护单测试", "Only synthetic fixtures", true, BrokerEnvironment.REMOTE_DEMO)
    override val capabilities = BrokerCapabilities(hedging = false)
    override val instruments = listOf(spec)
    override val accounts = listOf(AccountInfo("100", "离线测试账户"))
    override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
    override val market = MutableStateFlow(MarketSnapshot(mapOf(spec.symbol to Quote(550500, 550525, time)), time, connected = true))
    val position = Position("100:200", spec.symbol, Side.BUY, 100, 550000, time - 300000,
        stopLoss = if (unresolved) null else 549000, takeProfit = if (unresolved) null else 552000, marginMicros = 0,
        protectionEditable = !unresolved, feesKnown = false, stopLossOrderId = if (unresolved) null else "702", takeProfitOrderId = if (unresolved) null else "701")
    val orders = listOf(
        PendingOrder("701", OrderRequest(spec.symbol, Side.SELL, OrderKind.LIMIT, 100, 552000), time,
            role = if (unresolved) OrderRole.UNRESOLVED else OrderRole.TAKE_PROFIT, positionId = if (unresolved) null else position.id, editable = !unresolved),
        PendingOrder("702", OrderRequest(spec.symbol, Side.SELL, OrderKind.STOP, 100, 549000), time,
            role = if (unresolved) OrderRole.UNRESOLVED else OrderRole.STOP_LOSS, positionId = if (unresolved) null else position.id, editable = !unresolved),
    )
    val accountState = MutableStateFlow(AccountSnapshot("100", 25_000_000_000, positions = listOf(position), orders = orders))
    var entryCalls = 0
    var protectionCalls = 0
    override fun history(symbol: String, timeframe: Timeframe) = (0..100).map { i ->
        val close = 550000L + i % 5 * 25
        Candle(time - (100 - i) * timeframe.minutes * 60000L, close, close + 50, close - 50, close)
    }
    override fun openSession(accountId: String) = object : AccountSession {
        override val account = accounts.single()
        override val state = accountState
        override fun preview(request: OrderRequest) = OrderPreview(550525, marginKnown = false, feesKnown = false)
        override fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview {
            entryCalls++
            return OrderPreview(null, errors = listOf("Fixture must not use entry flow for TP/SL"))
        }
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long): TradeResult { entryCalls++; return TradeResult(false, "offline") }
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?): TradeResult {
            protectionCalls++
            state.value = state.value.copy(positions = listOf(position.copy(stopLoss = stopLoss, takeProfit = takeProfit, version = 2)))
            return TradeResult(true, "离线保护价已更新")
        }
        override suspend fun placeOrder(requestId: String, request: OrderRequest) = error("No orders permitted in this offline UI fixture")
        override suspend fun closePosition(requestId: String, positionId: String) = error("No closing permitted in this fixture")
        override suspend fun cancelOrder(requestId: String, orderId: String) = error("No cancellation permitted in this fixture")
    }
}

class ProtectionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun render(broker: OfflineProtectionBroker) {
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText(broker.descriptor.name).performClick()
    }
    @Test fun pendingTableUsesTpSlLabelsAndProtectionDialog() {
        val broker = OfflineProtectionBroker()
        render(broker)
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-701")
        compose.onNode(hasText("TP 止盈", substring = true) and hasAnyAncestor(hasTestTag("trade-terminal"))).assertExists()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-702")
        compose.onNode(hasText("SL 止损", substring = true) and hasAnyAncestor(hasTestTag("trade-terminal"))).assertExists()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-701")
        compose.onNodeWithTag("edit-order-701").assertTextContains("止盈价").performClick()
        compose.onNodeWithTag("protection-dialog").assertExists()
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, broker.entryCalls)
        assertEquals(0, broker.protectionCalls)
    }
    @Test fun tpDragUsesProtectionFlowWithoutSubmittingBeforeConfirmation() {
        val broker = OfflineProtectionBroker()
        render(broker)
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(broker.history("TESTZ6", Timeframe.M5).takeLast(80), listOf(550500, 550525, 550000, 549000, 552000), 18f * .6f, bounds.height - 30f * .6f)
        val y = scale.y(552000)
        chart.performTouchInput { down(Offset(bounds.width * .55f, y)); moveBy(Offset(0f, -6f)); moveBy(Offset(0f, -18f)); up() }
        compose.onNodeWithTag("protection-dialog").assertExists()
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        assertEquals(0, broker.entryCalls)
        assertEquals(0, broker.protectionCalls)
        compose.onNodeWithTag("confirm-protection").performClick()
        compose.waitUntil(5000) { broker.protectionCalls == 1 }
        assertEquals(0, broker.entryCalls)
    }
    @Test fun unresolvedRowsCannotBeEditedAsEntries() {
        val broker = OfflineProtectionBroker(unresolved = true)
        render(broker)
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-701")
        compose.onNode(hasText("关联待确认") and hasAnyAncestor(hasTestTag("order-role-701"))).assertExists()
        compose.onNodeWithTag("edit-order-701").assertIsNotEnabled()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-702")
        compose.onNode(hasText("关联待确认") and hasAnyAncestor(hasTestTag("order-role-702"))).assertExists()
        compose.onNodeWithTag("edit-order-702").assertIsNotEnabled()
        assertEquals(0, broker.entryCalls)
        assertEquals(0, broker.protectionCalls)
    }
    @Test fun offlineScreenshotShowsPositionProtectionWithoutDuplicateEntryLines() {
        val broker = OfflineProtectionBroker()
        render(broker)
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-701")
        val file = File("../artifacts/offline-tpsl.png")
        file.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", file)
        assertEquals(0, broker.entryCalls)
        assertEquals(0, broker.protectionCalls)
    }
}
