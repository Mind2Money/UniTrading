package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private const val TIME = 1_798_589_000_000L

@OptIn(ExperimentalCoroutinesApi::class)
class TradovateDrawdownTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun rows(text: String) = Json.parseToJsonElement(text)
    private fun session() = TradovateSession(TradovateEnvironment.DEMO, "synthetic-drawdown-token-123456", null, 42, "test", 4_102_444_800_000)

    private fun rule(peak: String, amount: String, limit: String = "null", mode: String? = null) = tradovateDrawdownRule(
        obj("""{"id":7,"maxNetLiq":$peak}"""),
        obj("""{"id":7,"trailingMaxDrawdown":$amount,"trailingMaxDrawdownLimit":$limit,"trailingMaxDrawdownMode":${mode?.let { "\"$it\"" } ?: "null"}}"""))
    private fun at(rule: DrawdownRule?, usd: Long, source: DrawdownValueSource = DrawdownValueSource.CASH) = accountDrawdown(rule, DrawdownAccountValue(usd * 1_000_000, source))!!

    @Test fun theLineIsTheWebTradersColumnInEveryTrailingMode() {
        val trail = rule("150578.5", "4500", mode = "RealTime")!!
        assertEquals(146_078_500_000, trail.levelMicros, "the peak less the trailing amount")
        assertEquals(DrawdownMode.REAL_TIME, trail.mode)
        assertFalse(trail.reported)
        val held = rule("156000", "4500", "150000", "RealTime")!!
        assertEquals(150_000_000_000, held.levelMicros, "the line stops at its limit")
        assertTrue(held.held)
        val rising = rule("150000", "4500", "150000", "EOD")!!
        assertEquals(145_500_000_000, rising.levelMicros, "a trail still under the limit keeps rising")
        assertFalse(rising.held)
        assertEquals(DrawdownMode.END_OF_DAY, rising.mode)
        assertTrue(rule("103000", "3000", "100000", "SteppedRealTime")!!.held, "the limit itself is where the line stops")
        assertEquals(DrawdownMode.STEPPED_END_OF_DAY, rule("103000", "3000", mode = "SteppedEOD")!!.mode)
        assertNull(rule("103000", "3000", mode = "SomethingNew")!!.mode, "an unknown mode is named generically, not guessed")
    }

    @Test fun anAccountWithoutBothBrokerFiguresHasNoLineNeverALineAtZero() {
        assertNull(rule("null", "4500"))
        assertNull(rule("150000", "null"))
        assertNull(rule("150000", "0"), "a zero amount is no trailing rule")
        assertNull(rule("\"bad\"", "4500"))
        assertNull(tradovateDrawdownRule(null, null))
    }

    @Test fun theRoomFollowsTheValueOnOneScaleForTddAndEod() {
        val tdd = rule("150000", "4500", mode = "RealTime")
        val marked = at(tdd, 148_500, DrawdownValueSource.QUOTES)
        assertEquals(3_000_000_000, marked.roomMicros)
        assertEquals(1.0 / 3.0, marked.danger!!, 1e-9)
        assertEquals(0.0, at(tdd, 150_000).danger, "at the peak nothing is used")
        val eod = rule("50000", "2500", mode = "EOD")
        assertEquals(4_000_000_000, at(eod, 51_500).roomMicros, "the day's profit leaves the line where the close put it")
        assertEquals(0.0, at(eod, 51_500).danger)
        assertEquals(0.8, at(eod, 48_000).danger!!, 1e-9)
    }

    @Test fun aLineThatHasStoppedMeasuresDangerByTheTrailingAmount() {
        val held = rule("160000", "4500", "150000", "RealTime")
        assertEquals(0.0, at(held, 155_000).danger, "more than a full amount above the line")
        assertEquals(0.9, at(held, 150_450).danger!!, 1e-9)
    }

    @Test fun theValueIsTheMarkedEquityThenTheBrokersSnapshotThenUnknown() {
        assertEquals(DrawdownAccountValue(5, DrawdownValueSource.QUOTES), tradovateAccountValue(5, 9, flat = false))
        assertEquals(DrawdownAccountValue(5, DrawdownValueSource.CASH), tradovateAccountValue(5, 9, flat = true))
        assertEquals(DrawdownAccountValue(9, DrawdownValueSource.SNAPSHOT), tradovateAccountValue(null, 9, flat = false))
        assertNull(tradovateAccountValue(null, null, flat = false))
    }

    private fun drawdownWire(positions: String = "[]") = MockTradovateWire().apply {
        snapshot = JsonObject(snapshot + mapOf(
            "accountRiskStatuses" to rows("""[{"id":100,"maxNetLiq":26000}]"""),
            "userAccountAutoLiqs" to rows("""[{"id":100,"trailingMaxDrawdown":1500,"trailingMaxDrawdownLimit":25500,"trailingMaxDrawdownMode":"EOD"}]"""),
            "positions" to rows(positions)))
    }

    @Test fun theSyncAsksForBothRowsAndAFlatAccountStandsOnItsCash() = runTest {
        val wire = drawdownWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { TIME })
        broker.connect(); runCurrent()
        val types = obj(wire.account.bodies.getValue("user/syncrequest"))["entityTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("accountRiskStatus" in types && "userAccountAutoLiq" in types, types.toString())
        val drawdown = broker.openSession("100").state.value.drawdown!!
        assertEquals(24_500_000_000, drawdown.rule.levelMicros)
        assertEquals(500_000_000, drawdown.roomMicros)
        assertEquals(2.0 / 3.0, drawdown.danger!!, 1e-9)
        assertEquals(DrawdownValueSource.CASH, drawdown.source)
        assertNull(broker.openSession("101").state.value.drawdown, "an account the broker sent no row for has no rule")
    }

    @Test fun aRiskRowMovesTheLineAndTouchesNothingElse() = runTest {
        val wire = drawdownWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { TIME })
        broker.connect(); runCurrent()
        val account = broker.openSession("100")
        val balanceReads = wire.requests.count { it.path.startsWith("cashBalance/") }
        val marginBefore = account.state.value.marginKnown
        wire.account.entity("accountRiskStatus", obj("""{"id":100,"maxNetLiq":26500}""")); runCurrent()
        assertEquals(25_000_000_000, account.state.value.drawdown!!.rule.levelMicros)
        assertEquals(1.0, account.state.value.drawdown!!.danger, "at the line")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(balanceReads, wire.requests.count { it.path.startsWith("cashBalance/") }, "a risk row is no reason to read the balances again")
        assertEquals(marginBefore, account.state.value.marginKnown, "the financial snapshot stays")
    }

    @Test fun aNewRowReplacesTheLastSoARemovedLimitDoesNotLinger() = runTest {
        val wire = drawdownWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { TIME })
        broker.connect(); runCurrent()
        val account = broker.openSession("100")
        assertEquals(25_500_000_000, account.state.value.drawdown!!.rule.limitMicros)
        wire.account.entity("userAccountAutoLiq", obj("""{"id":100,"trailingMaxDrawdown":1500,"trailingMaxDrawdownMode":"EOD"}""")); runCurrent()
        assertNull(account.state.value.drawdown!!.rule.limitMicros)
    }

    @Test fun aMalformedRowLeavesTheDrawdownUnknownAndTheConnectionUp() = runTest {
        val wire = drawdownWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { TIME })
        broker.connect(); runCurrent()
        val account = broker.openSession("100")
        wire.account.entity("accountRiskStatus", obj("""{"maxNetLiq":1}""")); runCurrent()
        assertEquals(24_500_000_000, account.state.value.drawdown!!.rule.levelMicros, "a row without an id is dropped")
        wire.account.entity("accountRiskStatus", obj("""{"id":100,"maxNetLiq":"not a number"}""")); runCurrent()
        assertNull(account.state.value.drawdown)
        assertTrue(broker.market.value.connected)
    }

    @Test fun anOpenPositionMarksTheRoomByQuotesAndFallsBackToTheSnapshot() = runTest {
        var time = TIME
        val wire = drawdownWire("""[{"id":1,"accountId":100,"contractId":200,"netPos":1,"netPrice":5490}]""").apply {
            balanceSnapshots[100] = obj("""{"totalCashValue":25000,"netLiq":24900,"initialMargin":0}""")
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { time })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val account = broker.openSession("100")
        val marked = account.state.value.drawdown!!
        assertEquals(25_050_000_000, account.state.value.equityMicros, "cash plus ten points on one MES")
        assertEquals(550_000_000, marked.roomMicros, "the room is the equity beside it less the line")
        assertEquals(DrawdownValueSource.QUOTES, marked.source)
        time += 60_000
        wire.account.entity("accountRiskStatus", obj("""{"id":100,"maxNetLiq":26000}""")); runCurrent()
        val stale = account.state.value.drawdown!!
        assertEquals(400_000_000, stale.roomMicros, "without a fresh quote the broker's snapshot stands")
        assertEquals(DrawdownValueSource.SNAPSHOT, stale.source)
    }
}
