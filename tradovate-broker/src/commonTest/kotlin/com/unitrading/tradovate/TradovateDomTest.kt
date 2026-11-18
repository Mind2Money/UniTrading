package com.unitrading.tradovate

import com.unitrading.broker.Instrument
import kotlinx.coroutines.test.*
import com.unitrading.broker.DepthStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class TradovateDomTest {
    private val instrument = Instrument("MESZ6", "MES", "Future", 6, 250_000, 1_250_000)
    private fun parse(value: String) = parseTradovateDom(Json.parseToJsonElement(value).jsonObject, instrument)
    @Test fun snapshotIsSortedAndUsesFixedPointPrices() {
        val result = parse("""{"timestamp":"2026-11-18T09:00:00Z","bids":[{"price":5500,"size":4},{"price":5500.25,"size":3}],"offers":[{"price":5501,"size":8},{"price":5500.5,"size":5}]}""")!!
        assertEquals(listOf(5_500_250_000L, 5_500_000_000L), result.bids.map { it.price })
        assertEquals(listOf(5_500_500_000L, 5_501_000_000L), result.asks.map { it.price })
        assertEquals(listOf(3L, 4L), result.bids.map { it.size })
    }
    @Test fun partialCrossedMalformedAndFractionalBooksAreRejected() {
        for (packet in listOf(
            """{"timestamp":"2026-11-18T09:00:00Z","bids":[]}""",
            """{"timestamp":"2026-11-18T09:00:00Z","bids":[{"price":5500,"size":2}],"offers":[{"price":5500,"size":2}]}""",
            """{"timestamp":"2026-11-18T09:00:00Z","bids":[{"price":5500.1,"size":2}],"offers":[]}""",
            """{"timestamp":"2026-11-18T09:00:00Z","bids":[{"price":5500,"size":2.5}],"offers":[]}""",
            """{"timestamp":"bad","bids":[],"offers":[]}""")) assertNull(parse(packet))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun subscriptionPublishesOnlyTheActiveContractAndUnsubscribesOnClose() = runTest {
        var clock = 1_798_589_000_000L
        val wire = MockTradovateWire()
        val session = TradovateSession(TradovateEnvironment.DEMO, "test-access-token-1234567890", null, 42, "test", 4_102_444_800_000)
        val broker = TradovateBroker(session, wire, backgroundScope, now = { clock })
        broker.connect(); runCurrent()
        broker.watchOrderFlow("MESZ6"); runCurrent()
        assertTrue("md/subscribeDOM" in wire.market.sent)
        val timestamp = kotlin.time.Instant.fromEpochMilliseconds(clock)
        wire.market.queue.send("""a[{"e":"md","d":{"doms":[{"contractId":200,"timestamp":"$timestamp","bids":[{"price":5500,"size":4}],"offers":[{"price":5500.25,"size":5}]}]}}]""")
        runCurrent()
        val live = broker.orderFlow.value
        assertEquals(DepthStatus.AVAILABLE, live.depthStatus)
        assertEquals("MESZ6", live.symbol)
        assertEquals(4L, live.bestBid?.size)
        assertEquals(5L, live.bestAsk?.size)
        // An older packet must not replace the current displayed book.
        val older = kotlin.time.Instant.fromEpochMilliseconds(clock - 1_000)
        wire.market.queue.send("""a[{"e":"md","d":{"doms":[{"contractId":200,"timestamp":"$older","bids":[{"price":5499,"size":99}],"offers":[{"price":5500.25,"size":1}]}]}}]""")
        runCurrent()
        assertEquals(4L, broker.orderFlow.value.bestBid?.size)
        // Once its quote is too old, hide it rather than presenting stale levels as live.
        clock += 16_000
        advanceTimeBy(1_001); runCurrent()
        assertEquals(DepthStatus.WAITING, broker.orderFlow.value.depthStatus)
        assertTrue(broker.orderFlow.value.bids.isEmpty())
        broker.watchOrderFlow(null); runCurrent()
        assertTrue("md/unsubscribeDOM" in wire.market.sent)
        assertNull(broker.orderFlow.value.symbol)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }

}
