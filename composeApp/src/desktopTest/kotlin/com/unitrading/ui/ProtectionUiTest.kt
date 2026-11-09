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
import com.unitrading.chart.OrderTagButtons
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

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ProtectionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun render(broker: OfflineProtectionBroker) {
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText(broker.descriptor.name).performClick()
    }
    private fun chart() = compose.onNodeWithTag("trading-chart")
    private fun chartState() = chart().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun button(hint: String): Offset = chart().fetchSemanticsNode().config[OrderTagButtons].let { assertNotNull(it[hint], "No button \"$hint\" in ${it.keys}") }.center
    private fun tapButton(hint: String) {
        val at = button(hint)
        chart().performTouchInput { click(at) }
        compose.waitForIdle()
    }

    @Test fun positionRowCarriesItsRecognizedProtectionAndThePendingSectionDoesNot() {
        val broker = OfflineProtectionBroker()
        render(broker)
        compose.onNodeWithTag("trade-terminal").performScrollToKey("position-100:200")
        for ((id, label) in listOf("701" to "TP 止盈", "702" to "SL 止损")) {
            // The clickable position row merges its text for screen readers, so the chips are only separate in the unmerged tree.
            compose.onNode(hasTestTag("pending-order-$id") and hasAnyAncestor(hasTestTag("position-100:200")), useUnmergedTree = true).assertExists()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("order-role-$id")), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("毛 +100.00 USD").assertExists()
        compose.onNodeWithText("毛 -50.00 USD").assertExists()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("section-orders")
        compose.onNodeWithText("挂单 0").assertExists()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("position-100:200")
        compose.onNodeWithTag("cancel-order-702").performClick()
        compose.onNodeWithTag("confirm-action").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("protect-position-100:200").performClick()
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
    @Test fun chartButtonsAskForTheSameConfirmationAsTheTradeList() {
        val broker = OfflineProtectionBroker()
        render(broker)
        tapButton("撤销止损 #702")
        compose.onNodeWithText("确认撤单").assertExists()
        compose.onNodeWithText("撤销SL 止损 #702", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        tapButton("平仓 买入 1 张 #200")
        compose.onNodeWithText("确认平仓").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, broker.protectionCalls)
    }
    @Test fun anOrderOfUnknownPurposeCanBeCancelledFromTheChart() {
        render(OfflineProtectionBroker(unresolved = true))
        tapButton("撤单 卖出突破 1 张 #702")
        compose.onNodeWithText("撤销关联待确认 #702", substring = true).assertExists()
    }
    @Test fun aRightClickOffersTheLineButtonsByName() {
        render(OfflineProtectionBroker())
        val at = button("撤销止损 #702")
        chart().performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.waitForIdle()
        compose.onNodeWithTag("line-menu-0").assertTextContains("撤销止损 #702").performClick()
        compose.onNodeWithText("确认撤单").assertExists()
        compose.onNodeWithTag("line-menu-0").assertDoesNotExist()
    }
    @Test fun theWheelZoomsTimeOverThePlotAndPriceOverTheAxis() {
        render(OfflineProtectionBroker())
        val size = chart().fetchSemanticsNode().size
        val before = chartState()
        chart().performMouseInput { moveTo(Offset(size.width * .4f, size.height * .5f)); scroll(-3f) }
        compose.waitForIdle()
        val zoomed = chartState()
        assertTrue(zoomed.getValue("bars").toDouble() < before.getValue("bars").toDouble(), "Rolling away zooms in")
        assertEquals(0.0, zoomed.getValue("offset").toDouble(), "The chart keeps following the newest bar")
        assertEquals("true", zoomed.getValue("auto"))
        chart().performMouseInput { moveTo(Offset(size.width - 10f, size.height * .3f)); scroll(4f) }
        compose.waitForIdle()
        val axis = chartState()
        assertEquals(zoomed.getValue("bars"), axis.getValue("bars"))
        assertEquals("false", axis.getValue("auto"))
        assertTrue(axis.getValue("high").toDouble() - axis.getValue("low").toDouble() > zoomed.getValue("high").toDouble() - zoomed.getValue("low").toDouble())
    }
    @Test fun aDraftTagTurnsProtectionOnAndCanBeDiscarded() {
        render(OfflineProtectionBroker())
        compose.onNodeWithTag("legend-buy").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("order-tp-enabled").assertIsOff()
        tapButton("添加止盈")
        compose.onNodeWithTag("order-tp-enabled").assertIsOn()
        tapButton("取消止盈")
        compose.onNodeWithTag("order-tp-enabled").assertIsOff()
        tapButton("关闭下单预览")
        assertFalse("关闭下单预览" in chart().fetchSemanticsNode().config[OrderTagButtons])
    }
    @Test fun offlineScreenshotShowsPositionProtectionWithoutDuplicateEntryLines() {
        val broker = OfflineProtectionBroker()
        render(broker)
        compose.onNodeWithTag("trade-terminal").performScrollToKey("position-100:200")
        val file = File("../artifacts/offline-tpsl.png")
        file.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", file)
        assertEquals(0, broker.entryCalls)
        assertEquals(0, broker.protectionCalls)
    }
}
