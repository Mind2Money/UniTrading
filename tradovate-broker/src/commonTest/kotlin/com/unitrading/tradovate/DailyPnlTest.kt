package com.unitrading.tradovate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DailyPnlTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val cash = obj("""{"id":1,"accountId":100,"currencyId":1,"amount":25000,"timestamp":"2026-10-30T22:00:00Z","tradeDate":{"year":2026,"month":11,"day":2}}""")

    @Test fun usesTheBrokerTotalAndTradingDateWithoutReconstructingPnl() {
        val daily = mapDailyPnl(obj("""{"totalPnL":-12.345678,"realizedPnL":100,"openPnL":20,"netLiq":27000,"totalCashValue":25000}"""), cash, 123)!!
        assertEquals(-12_345_678, daily.totalMicros)
        assertEquals(100_000_000, daily.realizedMicros)
        assertEquals(20_000_000, daily.openMicros)
        assertEquals("2026-11-02", daily.tradingDay, "Use the supplied session date even when the local calendar differs")
        assertEquals(123, daily.updatedAtMs)
        assertEquals(0, mapDailyPnl(obj("""{"totalPnL":0}"""), null, 0)!!.totalMicros)
    }

    @Test fun absentFailedAndInvalidDataRemainUnknownInsteadOfZero() {
        assertNull(mapDailyPnl(obj("""{"realizedPnL":50,"openPnL":20}"""), cash, 0))
        assertNull(mapDailyPnl(obj("""{"totalPnL":null}"""), cash, 0))
        assertNull(mapDailyPnl(obj("""{"totalPnL":"NaN"}"""), cash, 0))
        assertNull(mapDailyPnl(obj("""{"totalPnL":99999999999999999}"""), cash, 0))
        assertNull(mapDailyPnl(obj("""{"totalPnL":1,"errorText":"Access denied"}"""), cash, 0))
        val invalidDay = obj("""{"tradeDate":{"year":2026,"month":2,"day":30}}""")
        assertNull(mapDailyPnl(obj("""{"totalPnL":1}"""), invalidDay, 0)!!.tradingDay)
    }

    @Test fun accountSnapshotsRefreshOnServerEventsAndKeepAccountsIsolated() = runTest {
        var now = Instant.parse("2026-10-30T22:01:00Z").toEpochMilliseconds()
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("cashBalances" to JsonArray(listOf(cash))))
            balanceSnapshots[100] = obj("""{"totalCashValue":25000,"netLiq":25000,"totalPnL":123.45}""")
            balanceSnapshots[101] = obj("""{"totalCashValue":50000,"netLiq":50000,"totalPnL":-67.89}""")
        }
        val credentials = TradovateSession(TradovateEnvironment.DEMO, "synthetic-daily-pnl-token-123456", null, 42, "test", 4_102_444_800_000)
        val broker = TradovateBroker(credentials, wire, backgroundScope, now = { now })
        broker.connect()
        val first = broker.openSession("100")
        val second = broker.openSession("101")
        assertEquals(123_450_000, first.state.value.dailyPnl!!.totalMicros)
        assertEquals(-67_890_000, second.state.value.dailyPnl!!.totalMicros)
        assertEquals("2026-11-02", first.state.value.dailyPnl!!.tradingDay)
        assertTrue(broker.capabilities.tradingDayPnl, "the P&L lock is only offered on a broker that says it reports its trading day")
        now += 60_000
        wire.balanceSnapshots[100] = obj("""{"totalCashValue":25000,"netLiq":25000,"totalPnL":0}""")
        wire.account.entity("cashBalance", cash)
        runCurrent()
        assertNull(first.state.value.dailyPnl, "An invalidated financial snapshot must not remain current")
        advanceTimeBy(15_000); runCurrent()
        assertEquals(0, first.state.value.dailyPnl!!.totalMicros)
        assertEquals(now, first.state.value.dailyPnl!!.updatedAtMs)
        wire.balanceSnapshots[100] = obj("""{"totalCashValue":25000,"netLiq":25000}""")
        broker.resynchronize()
        assertNull(first.state.value.dailyPnl)
        assertEquals(-67_890_000, second.state.value.dailyPnl!!.totalMicros)
        assertTrue(wire.requests.none { it.method == "POST" && it.path.startsWith("order/") })
    }

    @Test fun aBalanceEventDuringRefreshDiscardsTheOldResponseAndFetchesAgain() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("cashBalances" to JsonArray(listOf(cash))))
            balanceSnapshots[100] = obj("""{"totalPnL":10}""")
        }
        val credentials = TradovateSession(TradovateEnvironment.DEMO, "synthetic-daily-pnl-token-123456", null, 42, "test", 4_102_444_800_000)
        val broker = TradovateBroker(credentials, wire, backgroundScope)
        broker.connect()
        val session = broker.openSession("100")
        val old = CompletableDeferred<JsonObject>()
        wire.deferredBalances[100] = old
        wire.account.entity("cashBalance", cash)
        advanceTimeBy(5_000); runCurrent()
        assertFalse(100L in wire.deferredBalances, "the old response is in flight")
        val nextDay = JsonObject(cash + ("tradeDate" to obj("""{"year":2026,"month":11,"day":3}""")))
        wire.balanceSnapshots[100] = obj("""{"totalPnL":20}""")
        wire.account.entity("cashBalance", nextDay)
        runCurrent()
        old.complete(obj("""{"totalPnL":999}"""))
        runCurrent()
        assertNull(session.state.value.dailyPnl)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(20_000_000, session.state.value.dailyPnl!!.totalMicros)
        assertEquals("2026-11-03", session.state.value.dailyPnl!!.tradingDay)
    }
}
