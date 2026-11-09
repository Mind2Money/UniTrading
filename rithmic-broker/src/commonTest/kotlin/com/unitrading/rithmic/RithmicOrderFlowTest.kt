package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private fun flowMessage(build: ProtoWriter.() -> Unit) = decodeProto(ProtoWriter().apply(build).toByteArray())

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RithmicOrderFlowTest {
    private val spec = RithmicSpec(Instrument("MESZ6", "MES", "CME", 2, 25, 1_250_000), "CME", PriceScale(2), "MES", null)

    @Test fun tapePreservesUnknownAggressorsAndBoundsTheContiguousWindow() {
        val flow = RithmicOrderFlow()
        flow.reset("MESZ6", "test")
        flow.trade(550000, 2, 1000, RithmicEnums.BUY)
        flow.trade(550025, 3, 1001, RithmicEnums.SELL)
        flow.trade(550000, 4, 1002, null)
        flow.trade(550000, 0, 1003, RithmicEnums.BUY)
        assertEquals(listOf(Side.BUY, Side.SELL, null), flow.snapshot(true).trades.map { it.aggressor })
        repeat(ORDER_FLOW_TRADE_LIMIT) { flow.trade(550000, 1, 2000L + it, 99) }
        val window = flow.snapshot(true)
        assertEquals(ORDER_FLOW_TRADE_LIMIT, window.trades.size)
        assertTrue(window.truncated)
        assertEquals(2000L, window.startedAtMs)
        flow.reset("MESH7", "switched")
        assertTrue(flow.snapshot(true).trades.isEmpty())
        assertFalse(flow.snapshot(true).truncated)
    }

    @Test fun bookTransactionsAreAtomicAndZeroSizeDeletesLevels() {
        val flow = RithmicOrderFlow()
        flow.reset("MESZ6", "test")
        fun book(type: Int, build: ProtoWriter.() -> Unit = {}) = flow.book(flowMessage {
            int32(Rf.BOOK_UPDATE_TYPE, type); build()
        }, spec)
        assertFalse(book(RithmicEnums.BOOK_BEGIN) {
            double(Rf.BOOK_BID_PRICE, 5500.0); int32(Rf.BOOK_BID_SIZE, 10)
            double(Rf.BOOK_ASK_PRICE, 5500.25); int32(Rf.BOOK_ASK_SIZE, 12)
        })
        assertTrue(book(RithmicEnums.BOOK_END))
        assertFalse(book(RithmicEnums.BOOK_BEGIN) { double(Rf.BOOK_BID_PRICE, 5500.0); int32(Rf.BOOK_BID_SIZE, 0) })
        assertEquals(listOf(DepthLevel(550000, 10)), flow.snapshot(true).bids)
        assertFalse(book(RithmicEnums.BOOK_MIDDLE) { double(Rf.BOOK_BID_PRICE, 5499.75); int32(Rf.BOOK_BID_SIZE, 25) })
        assertTrue(book(RithmicEnums.BOOK_END) { double(Rf.BOOK_ASK_PRICE, 5500.25); int32(Rf.BOOK_ASK_SIZE, 22) })
        assertEquals(listOf(DepthLevel(549975, 25)), flow.snapshot(true).bids)
        assertEquals(listOf(DepthLevel(550025, 22)), flow.snapshot(true).asks)
        assertEquals(DepthStatus.AVAILABLE, flow.snapshot(true).depthStatus)
        book(RithmicEnums.BOOK_CLEAR)
        assertTrue(flow.snapshot(true).bids.isEmpty())
        assertEquals(DepthStatus.WAITING, flow.snapshot(true).depthStatus)
        book(RithmicEnums.BOOK_NONE)
        assertEquals(DepthStatus.UNAVAILABLE, flow.snapshot(true).depthStatus)
        assertTrue(flow.snapshot(true).asks.isEmpty())
    }

    @Test fun malformedBookDoesNotExposeAPartialUpdate() {
        val flow = RithmicOrderFlow()
        flow.reset("MESZ6", "test")
        flow.book(flowMessage {
            int32(Rf.BOOK_UPDATE_TYPE, RithmicEnums.BOOK_BEGIN)
            double(Rf.BOOK_BID_PRICE, 5500.0); int32(Rf.BOOK_BID_SIZE, 10)
            double(Rf.BOOK_ASK_PRICE, 5500.25) // missing corresponding size
        }, spec)
        assertEquals(DepthStatus.WAITING, flow.snapshot(true).depthStatus)
        assertTrue(flow.snapshot(true).bids.isEmpty())
        assertTrue(flow.snapshot(true).asks.isEmpty())
    }

    @Test fun unverifiedAggregateSnapshotKeepsTapeAndBboButLocksDepthUntilClear() {
        val flow = RithmicOrderFlow()
        flow.reset("MESZ6", "test")
        fun book(type: Int, build: ProtoWriter.() -> Unit = {}) = flow.book(flowMessage {
            int32(Rf.BOOK_UPDATE_TYPE, type); build()
        }, spec)
        fun ProtoWriter.levels() {
            double(Rf.BOOK_BID_PRICE, 5500.0); int32(Rf.BOOK_BID_SIZE, 10)
            double(Rf.BOOK_ASK_PRICE, 5500.25); int32(Rf.BOOK_ASK_SIZE, 12)
        }
        book(RithmicEnums.BOOK_BEGIN) { levels() }; book(RithmicEnums.BOOK_END)
        flow.bbo(550000, 10, 550025, 12)
        flow.trade(550025, 3, 1000, RithmicEnums.BUY)
        assertTrue(book(RithmicEnums.BOOK_SNAPSHOT) { levels() })
        val degraded = flow.snapshot(true)
        assertEquals(DepthStatus.UNAVAILABLE, degraded.depthStatus)
        assertEquals("聚合深度快照边界尚未验证，仅显示买一 / 卖一", degraded.statusMessage)
        assertTrue(degraded.bids.isEmpty()); assertTrue(degraded.asks.isEmpty())
        assertEquals(DepthLevel(550000, 10), degraded.bestBid)
        assertEquals(DepthLevel(550025, 12), degraded.bestAsk)
        assertEquals(listOf(MarketTrade(1000, 550025, 3, Side.BUY)), degraded.trades)
        assertFalse(book(RithmicEnums.BOOK_SOLO) { levels() })
        assertFalse(book(RithmicEnums.BOOK_BEGIN) { levels() })
        assertFalse(book(RithmicEnums.BOOK_MIDDLE) { levels() })
        assertFalse(book(RithmicEnums.BOOK_END))
        assertEquals(DepthStatus.UNAVAILABLE, flow.snapshot(true).depthStatus)
        assertTrue(flow.snapshot(true).bids.isEmpty())
        book(RithmicEnums.BOOK_CLEAR)
        book(RithmicEnums.BOOK_BEGIN) { levels() }; book(RithmicEnums.BOOK_END)
        assertEquals(DepthStatus.AVAILABLE, flow.snapshot(true).depthStatus)
        assertEquals(listOf(DepthLevel(550000, 10)), flow.snapshot(true).bids)
        assertEquals(1, flow.snapshot(true).trades.size)
        book(RithmicEnums.BOOK_SNAPSHOT)
        flow.reset("MESZ6", "new subscription")
        book(RithmicEnums.BOOK_BEGIN) { levels() }; book(RithmicEnums.BOOK_END)
        assertEquals(DepthStatus.AVAILABLE, flow.snapshot(true).depthStatus)
    }

    private suspend fun TestScope.connected(server: FakeRithmicServer = FakeRithmicServer()): RithmicBroker {
        val credentials = RithmicCredentials(RithmicEnvironment.TEST, FAKE_GATEWAY, "Rithmic Test", "u", "p")
        return RithmicBroker(credentials, server, backgroundScope, { FAKE_TIME }).also { it.connect() }
    }

    private fun FakeRithmicServer.pushTrade(symbol: String = "MESZ6", size: Int = 3, side: Int? = RithmicEnums.BUY, snapshot: Boolean = false) {
        plant(RithmicEnums.INFRA_TICKER).push {
            int32(Rf.TEMPLATE_ID, Rt.LAST_TRADE); string(Rf.SYMBOL, symbol)
            double(Rf.TRADE_PRICE, 5500.25); int32(Rf.TRADE_SIZE, size); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
            side?.let { int32(Rf.TRANSACTION_TYPE, it) }; bool(Rf.IS_SNAPSHOT, snapshot)
        }
    }

    @Test fun brokerCollectsTapeWithoutAChartAndStopsWhenUnwatched() = runTest {
        val server = FakeRithmicServer()
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals("MESZ6", broker.orderFlow.value.symbol)
        assertEquals(DepthLevel(550000, 10), broker.orderFlow.value.bestBid)
        assertEquals(DepthLevel(550025, 12), broker.orderFlow.value.bestAsk)
        val depthRequest = server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).single { it.long(Rf.UPDATE_BITS) == 4L }
        assertEquals(RithmicEnums.REQUEST_SUBSCRIBE, depthRequest.int(Rf.REQUEST))
        server.pushTrade(snapshot = true)
        server.pushTrade(side = RithmicEnums.SELL)
        server.pushTrade(side = null)
        runCurrent()
        assertTrue(broker.orderFlow.value.trades.isEmpty()) // publication is throttled
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(Side.SELL, null), broker.orderFlow.value.trades.map { it.aggressor })
        assertEquals(6L, broker.orderFlow.value.trades.sumOf { it.size })
        broker.watchOrderFlow(null); runCurrent()
        assertNull(broker.orderFlow.value.symbol)
        assertFalse(broker.orderFlow.value.connected)
        assertTrue(broker.orderFlow.value.trades.isEmpty())
        assertTrue(server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).any { it.long(Rf.UPDATE_BITS) == 4L && it.int(Rf.REQUEST) == RithmicEnums.REQUEST_UNSUBSCRIBE })
        server.pushTrade(); advanceTimeBy(101); runCurrent()
        assertTrue(broker.orderFlow.value.trades.isEmpty())
    }

    @Test fun depthPermissionDenialDoesNotDisableQuotesTradingOrTape() = runTest {
        val server = FakeRithmicServer().apply { denyDepth = true }
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        assertEquals(DepthStatus.UNAVAILABLE, broker.orderFlow.value.depthStatus)
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
        assertFalse(broker.market.value.statusMessage.contains("permission"))
        assertNotNull(broker.market.value.quotes["MESZ6"])
        assertTrue(broker.openSession("ACC1").preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).valid)
        server.pushTrade(); advanceTimeBy(101); runCurrent()
        assertEquals(1, broker.orderFlow.value.trades.size)
        assertEquals(DepthStatus.UNAVAILABLE, broker.orderFlow.value.depthStatus)
    }

    @Test fun closingAndReopeningBeforeTheWorkerRunsStartsANewWindow() = runTest {
        val server = FakeRithmicServer()
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        server.pushTrade(); advanceTimeBy(101); runCurrent()
        assertEquals(1, broker.orderFlow.value.trades.size)
        broker.watchOrderFlow(null)
        broker.watchOrderFlow("MESZ6")
        runCurrent()
        assertEquals("MESZ6", broker.orderFlow.value.symbol)
        assertTrue(broker.orderFlow.value.trades.isEmpty())
        assertTrue(server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).any { it.long(Rf.UPDATE_BITS) == 4L && it.int(Rf.REQUEST) == RithmicEnums.REQUEST_UNSUBSCRIBE })
    }

    @Test fun timedOutDepthSubscriptionIsStillUnsubscribedWhenClosing() = runTest {
        val server = FakeRithmicServer().apply { silentDepth = true }
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(DepthStatus.UNAVAILABLE, broker.orderFlow.value.depthStatus)
        assertTrue(broker.market.value.tradingReady)
        broker.watchOrderFlow(null); runCurrent()
        assertTrue(server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).any { it.long(Rf.UPDATE_BITS) == 4L && it.int(Rf.REQUEST) == RithmicEnums.REQUEST_UNSUBSCRIBE })
    }

    @Test fun crossedBboIsNotExposedAsAnExecutableOrderFlowQuote() = runTest {
        val server = FakeRithmicServer()
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        server.plant(RithmicEnums.INFRA_TICKER).push {
            int32(Rf.TEMPLATE_ID, Rt.BEST_BID_OFFER); string(Rf.SYMBOL, "MESZ6")
            uint32(Rf.PRESENCE_BITS, 3); double(Rf.BID_PRICE, 5501.0); int32(Rf.BID_SIZE, 5)
            double(Rf.ASK_PRICE, 5500.0); int32(Rf.ASK_SIZE, 7); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
        }
        advanceTimeBy(101); runCurrent()
        assertNull(broker.orderFlow.value.bestBid)
        assertNull(broker.orderFlow.value.bestAsk)
        assertNull(broker.market.value.quotes["MESZ6"])
    }

    @Test fun brokerPublishesServerBookLevelsAndClearsUnavailableDepth() = runTest {
        val server = FakeRithmicServer()
        val broker = connected(server)
        broker.watchOrderFlow("MESZ6"); runCurrent()
        server.plant(RithmicEnums.INFRA_TICKER).push {
            int32(Rf.TEMPLATE_ID, Rt.ORDER_BOOK); string(Rf.SYMBOL, "MESZ6")
            int32(Rf.BOOK_UPDATE_TYPE, RithmicEnums.BOOK_BEGIN)
            double(Rf.BOOK_BID_PRICE, 5499.75); int32(Rf.BOOK_BID_SIZE, 20)
            double(Rf.BOOK_BID_PRICE, 5500.0); int32(Rf.BOOK_BID_SIZE, 10)
            double(Rf.BOOK_ASK_PRICE, 5500.25); int32(Rf.BOOK_ASK_SIZE, 12)
        }
        server.plant(RithmicEnums.INFRA_TICKER).push {
            int32(Rf.TEMPLATE_ID, Rt.ORDER_BOOK); string(Rf.SYMBOL, "MESZ6"); int32(Rf.BOOK_UPDATE_TYPE, RithmicEnums.BOOK_END)
        }
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(DepthLevel(550000, 10), DepthLevel(549975, 20)), broker.orderFlow.value.bids)
        assertEquals(DepthStatus.AVAILABLE, broker.orderFlow.value.depthStatus)
        server.plant(RithmicEnums.INFRA_TICKER).push {
            int32(Rf.TEMPLATE_ID, Rt.ORDER_BOOK); string(Rf.SYMBOL, "MESZ6"); int32(Rf.BOOK_UPDATE_TYPE, RithmicEnums.BOOK_NONE)
        }
        runCurrent()
        assertTrue(broker.orderFlow.value.bids.isEmpty())
        assertEquals(DepthStatus.UNAVAILABLE, broker.orderFlow.value.depthStatus)
    }

    @Test fun brokerResetsOnContractSwitchAndReconnect() = runTest {
        val server = FakeRithmicServer()
        val broker = connected(server)
        broker.searchInstruments("MES")
        broker.watchOrderFlow("MESZ6"); runCurrent()
        server.pushTrade(); advanceTimeBy(101); runCurrent()
        assertEquals(1, broker.orderFlow.value.trades.size)
        broker.watchOrderFlow("MESH7"); runCurrent()
        assertEquals("MESH7", broker.orderFlow.value.symbol)
        assertTrue(broker.orderFlow.value.trades.isEmpty())
        server.pushTrade(); advanceTimeBy(101); runCurrent()
        assertTrue(broker.orderFlow.value.trades.isEmpty())
        server.pushTrade("MESH7"); advanceTimeBy(101); runCurrent()
        assertEquals(1, broker.orderFlow.value.trades.size)
        server.plant(RithmicEnums.INFRA_TICKER).drop(); runCurrent()
        assertFalse(broker.orderFlow.value.connected)
        assertTrue(broker.orderFlow.value.trades.isEmpty())
        advanceTimeBy(3100); runCurrent()
        assertEquals("MESH7", broker.orderFlow.value.symbol)
        assertTrue(broker.orderFlow.value.connected)
        assertTrue(broker.orderFlow.value.trades.isEmpty())
    }
}
