package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

private const val TEST_TIME = 1_798_589_000_000L
private const val STEP = 5 * 60_000L
private fun fakeToken(user: Long = 42) = "eyJhbGciOiJub25lIn0." + Base64.UrlSafe.encode("{\"sub\":$user,\"exp\":4102444800}".encodeToByteArray()).trimEnd('=') + ".test-signature-only"

/** Indicators accept periods up to 1000 bars, so the chart feed has to request and keep more than that. */
class ChartHistoryDepthTest {
    private suspend fun TestScope.ready(wire: MockTradovateWire): TradovateBroker {
        val session = TradovateSession(TradovateEnvironment.DEMO, fakeToken(), fakeToken(), 42, "test", 4_102_444_800_000)
        val broker = TradovateBroker(session, wire, backgroundScope, now = { TEST_TIME })
        broker.connect()
        broker.watch("MESZ6", Timeframe.M5)
        runCurrent()
        return broker
    }

    @Test fun chartRequestsAndKeepsTwiceTheLargestIndicatorPeriod() = runTest {
        assertTrue(CHART_BARS >= 2_000)
        val wire = MockTradovateWire()
        val broker = ready(wire)
        val request = Json.parseToJsonElement(wire.market.bodies.getValue("md/getChart")).jsonObject
        assertEquals(CHART_BARS.toLong(), request.getValue("timeRange").jsonObject.getValue("asMuchAsElements").jsonPrimitive.long)
        assertEquals(1, broker.history("MESZ6", Timeframe.M5).size)

        val extra = CHART_BARS + 500
        val bars = (1..extra).joinToString(",") { i ->
            """{"timestamp":"${kotlin.time.Instant.fromEpochMilliseconds(TEST_TIME + i * STEP)}","open":5500,"high":5501,"low":5499.75,"close":5500.25}"""
        }
        wire.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":12,"bars":[$bars]}]}}]""")
        runCurrent()
        val history = broker.history("MESZ6", Timeframe.M5)
        assertEquals(CHART_BARS, history.size)
        assertEquals(TEST_TIME + (extra - CHART_BARS + 1) * STEP, history.first().timeMs)
        assertEquals(TEST_TIME + extra * STEP, history.last().timeMs)
        assertTrue(history.zipWithNext().all { (earlier, later) -> later.timeMs - earlier.timeMs == STEP })
    }
}
