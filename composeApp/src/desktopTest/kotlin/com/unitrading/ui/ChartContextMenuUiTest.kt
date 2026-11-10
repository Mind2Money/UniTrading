package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.PriceScale
import com.unitrading.chart.drawing.DrawingTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Bars between 5480 and 5520 around a 5500.00 / 5500.25 quote, an empty account, and a record of every order sent. */
private class ContextMenuBroker : BrokerPlugin {
    val spec = Instrument("TESTZ6", "Offline futures", "离线测试", 2, 25, 1_250_000, minLotsHundredths = 100,
        lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0, estimatesIncludeFees = false)
    val time = 1_798_589_000_000L
    override val descriptor = BrokerDescriptor("offline-context-menu", "离线右键菜单测试", "Only synthetic fixtures", true, BrokerEnvironment.REMOTE_DEMO)
    override val capabilities = BrokerCapabilities(hedging = false)
    override val instruments = listOf(spec)
    override val accounts = listOf(AccountInfo("100", "离线测试账户"))
    override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
    override val market = MutableStateFlow(MarketSnapshot(mapOf(spec.symbol to Quote(550000, 550025, time)), time, connected = true))
    val placed = mutableListOf<OrderRequest>()
    /** What the broker's own check answers; an error stands for any order it would refuse. */
    var refusal: String? = null
    override fun history(symbol: String, timeframe: Timeframe) = (0..100).map { i ->
        val close = 550000L + (i % 9 - 4) * 500
        Candle(time - (100 - i) * timeframe.minutes * 60000L, close, close + 50, close - 50, close)
    }
    override fun openSession(accountId: String) = object : AccountSession {
        override val account = accounts.single()
        override val state = MutableStateFlow(AccountSnapshot("100", 25_000_000_000))
        override fun preview(request: OrderRequest) =
            refusal?.let { OrderPreview(null, errors = listOf(it), marginKnown = false, feesKnown = false) } ?: OrderPreview(request.entryPrice ?: 550025, marginKnown = false, feesKnown = false)
        override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(null, errors = listOf("unused"))
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
        override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult { placed += request; return TradeResult(true, "离线挂单已接受") }
        override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
        override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
    }
}

/** A right click on the empty plot opens the chart's own menu: a horizontal line and the entry orders that can rest at that price. */
@OptIn(ExperimentalTestApi::class)
class ChartContextMenuUiTest {
    @get:Rule val compose = createComposeRule()
    private val broker = ContextMenuBroker()
    private lateinit var runtime: TradingRuntime

    private fun render(skipConfirmation: Boolean = false) {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        if (skipConfirmation) runBlocking { assertTrue(runtime.tradingPreferences.setSkipConfirmation(true)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText(broker.descriptor.name).performClick()
        compose.waitForIdle()
    }
    private fun chart() = compose.onNodeWithTag("trading-chart")
    private fun chartState() = chart().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    /** Right of the legend, left of the price axis, on the row of [price]. */
    private fun pointAt(price: Long): Offset {
        val state = chartState()
        val scale = PriceScale(state.getValue("low").toDouble(), state.getValue("high").toDouble(), 18f * .6f, state.getValue("plotBottom").toFloat())
        return Offset(chart().fetchSemanticsNode().size.width * .7f, scale.y(price))
    }
    private fun rightClick(price: Long) {
        val at = pointAt(price)
        chart().performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.waitForIdle()
    }
    private fun item(name: String) = compose.onNodeWithTag("plot-menu-$name")

    @Test fun theMenuDrawsAHorizontalLineAtThePriceUnderThePointer() {
        render()
        rightClick(551_000)
        item("horizontal-line").assertTextContains("在 5510.00 画水平线").performClick()
        compose.waitForIdle()
        val line = runtime.drawings.drawings.single()
        assertEquals(DrawingTool.HORIZONTAL_LINE, line.tool)
        assertEquals(551_000.0, line.points.single().price)
        item("horizontal-line").assertDoesNotExist()
        compose.onNodeWithTag("drawing-bar").assertExists("the new line is selected, so its quick bar opens")
        assertTrue(broker.placed.isEmpty())

        // On the line the right click belongs to the drawing, as before.
        rightClick(551_000)
        compose.onNodeWithTag("drawing-menu-settings").assertExists()
        item("horizontal-line").assertDoesNotExist()
    }

    @Test fun underTheMarketItOffersABuyLimitAndASellStopAndTheOrderWaitsForTheConfirmation() {
        render()
        rightClick(549_000)
        val folder = File("../artifacts").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "chart-context-menu-chart.png"))
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "chart-context-menu.png"))
        item("SELL-LIMIT").assertDoesNotExist()
        item("BUY-STOP").assertDoesNotExist()
        item("SELL-STOP").assertTextContains("卖出突破 1 张 @ 5490.00")
        item("BUY-LIMIT").assertTextContains("买入限价 1 张 @ 5490.00").performClick()
        compose.waitForIdle()
        item("BUY-LIMIT").assertDoesNotExist()
        compose.onNodeWithText("确认 Tradovate Demo 下单").assertExists()
        compose.onNodeWithText("TESTZ6 · 买入限价 · 1 张").assertExists()
        assertTrue(broker.placed.isEmpty(), "Nothing reaches the broker before the confirmation")
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.LIMIT, 100, 549_000), broker.placed.single(), "the panel's quantity at the clicked price, without protection")
        assertTrue(runtime.drawings.drawings.isEmpty())
    }

    @Test fun overTheMarketItOffersASellLimitAndABuyStopWithThePanelQuantityAndSkipsTheConfirmationWhenToldTo() {
        render(skipConfirmation = true)
        compose.onNodeWithTag("order-quantity").performTextReplacement("3")
        compose.waitForIdle()
        rightClick(551_000)
        item("BUY-LIMIT").assertDoesNotExist()
        item("SELL-STOP").assertDoesNotExist()
        item("SELL-LIMIT").assertTextContains("卖出限价 3 张 @ 5510.00")
        item("BUY-STOP").assertTextContains("买入突破 3 张 @ 5510.00").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.STOP, 300, 551_000), broker.placed.single())
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
    }

    @Test fun anOrderTheBrokerRefusesNeverGoesOutEvenWithConfirmationsSkipped() {
        render(skipConfirmation = true)
        broker.refusal = "Limit/Stop 入场方向或距离无效"
        rightClick(549_000)
        item("SELL-STOP").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trade-notice").assertTextEquals("Limit/Stop 入场方向或距离无效")
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun aQuantityThatDoesNotParseLeavesTheLabelsWithoutItAndStopsTheOrder() {
        render(skipConfirmation = true)
        compose.onNodeWithTag("order-quantity").performTextReplacement("")
        compose.waitForIdle()
        rightClick(549_000)
        item("BUY-LIMIT").assertTextContains("买入限价 @ 5490.00").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trade-notice").assertTextEquals("请先在下单面板输入有效数量")
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun withoutAQuoteTheMenuKeepsOnlyTheHorizontalLine() {
        render()
        compose.runOnIdle { broker.market.value = broker.market.value.copy(quotes = emptyMap(), revision = 1) }
        compose.waitForIdle()
        rightClick(549_000)
        item("horizontal-line").assertExists()
        for (name in listOf("BUY-LIMIT", "SELL-LIMIT", "BUY-STOP", "SELL-STOP")) item(name).assertDoesNotExist()
    }
}
