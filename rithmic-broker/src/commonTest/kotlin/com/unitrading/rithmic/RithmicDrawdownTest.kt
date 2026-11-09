package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.math.roundToLong
import kotlin.test.*

private fun micros(value: Double?): Long? = value?.let { (it * MICROS_PER_USD).roundToLong() }
private fun Long?.usd(): Double? = this?.let { it.toDouble() / MICROS_PER_USD }
private fun risk(criteria: String? = null, threshold: Double? = null, current: Double? = null, peak: Double? = null, maxMinBalance: Double? = null, minBalance: Double? = null, lossLimit: Double? = null) =
    RithmicRisk(criteria, micros(threshold), micros(maxMinBalance), micros(lossLimit), micros(minBalance), micros(current), micros(peak))
private fun money(balance: Double? = null, dayClosed: Double? = null, cash: Double? = null, closed: Double? = null, open: Double? = null, minBalance: Double? = null, lossPercent: Double? = null) =
    RithmicFinancials("ACC1", micros(balance), micros(cash), micros(open), micros(closed), null, null, micros(dayClosed), null, null, 0, micros(minBalance), micros(lossPercent))
private fun flat(rule: DrawdownRule?, value: Double) = accountDrawdown(rule, DrawdownAccountValue(micros(value)!!, DrawdownValueSource.CASH))!!
private fun credentials() = RithmicCredentials(RithmicEnvironment.TEST, FAKE_GATEWAY, RithmicEnvironment.TEST.defaultSystemName, "tester@example.com", "test-password")

@OptIn(ExperimentalCoroutinesApi::class)
class RithmicDrawdownTest {
    @Test fun theBrokersCurrentValueIsTheLineAndATrailingThresholdIsItsDistance() {
        val rule = rithmicDrawdownRule(risk("Trailing Minimum Account Balance", 2000.0, 48500.0, 50500.0), null)!!
        assertEquals(48500.0, rule.levelMicros.usd()); assertEquals(2000.0, rule.amountMicros.usd()); assertEquals(50500.0, rule.peakMicros.usd())
        assertEquals(DrawdownMode.REAL_TIME, rule.mode); assertFalse(rule.held); assertTrue(rule.reported); assertEquals(DrawdownLineSource.CURRENT_VALUE, rule.lineSource)
        assertEquals(2000.0, rithmicDrawdownRule(risk("Trailing Minimum Account Balance", null, 48500.0, 50500.0), null)!!.amountMicros.usd(), "the distance to the peak stands in for a missing threshold")
    }

    @Test fun aLimitedTrailIsHeldAtItsMaximumAndAPlainTrailNever() {
        val limited = rithmicDrawdownRule(risk("Limited Trailing Minimum Account Balance", 2000.0, 50100.0, 53000.0, 50100.0), null)!!
        assertTrue(limited.held); assertEquals(50100.0, limited.limitMicros.usd()); assertEquals(2000.0, limited.amountMicros.usd())
        assertFalse(rithmicDrawdownRule(risk("Limited Trailing Minimum Account Balance", 2000.0, 49000.0, 51000.0, 50100.0), null)!!.held)
        assertFalse(rithmicDrawdownRule(risk("Trailing Minimum Account Balance", 2000.0, 50100.0, 53000.0, 50100.0), null)!!.held, "only the limited criterion uses the maximum")
    }

    @Test fun aPlainMinimumBalanceIsAFixedLineWhoseThresholdIsTheFloorAndOnlyAPeakGivesItAScale() {
        val bare = rithmicDrawdownRule(risk("Minimum Account Balance", 47500.0), null)!!
        assertEquals(47500.0, bare.levelMicros.usd()); assertEquals(DrawdownMode.FIXED, bare.mode); assertNull(bare.amountMicros); assertNull(bare.peakMicros); assertEquals(DrawdownLineSource.THRESHOLD, bare.lineSource)
        assertEquals(2500.0, rithmicDrawdownRule(risk("Minimum Account Balance", 47500.0, 47500.0, 50000.0), null)!!.amountMicros.usd())
        val row = flat(bare, 48000.0)
        assertEquals(500.0, row.roomMicros.usd()); assertNull(row.danger, "no amount, no invented danger"); assertEquals(DrawdownValueSource.CASH, row.source)
    }

    @Test fun aLossOrProfitLimitHasNoBalanceLineAndNothingReportedStaysNoLine() {
        for (criteria in listOf("Loss Limit", "Soft Loss Limit", "Profit Limit")) {
            val rule = rithmicDrawdownRule(risk(criteria, 2000.0, 1500.0), null)!!
            assertNull(rule.levelMicros, "$criteria is an amount"); assertEquals(DrawdownMode.OTHER, rule.mode); assertEquals(criteria, rule.criteria)
        }
        assertNull(rithmicDrawdownRule(risk("Trailing Minimum Account Balance", 2000.0), null)!!.levelMicros, "a trailing distance alone is not a level")
        assertNull(rithmicDrawdownRule(risk(null, 2000.0), null), "a threshold under no known criterion is not taken for a level")
        assertNull(rithmicDrawdownRule(risk("Minimum Account Balance", 0.0, 0.0), null)!!.levelMicros)
        assertNull(rithmicDrawdownRule(null, null))
        val unknown = rithmicDrawdownRule(risk(null, null, 48000.0), null)!!
        assertEquals(48000.0, unknown.levelMicros.usd()); assertNull(unknown.mode)
    }

    @Test fun onlyAPlainMinimumBalanceMakesTheThresholdAFloor() {
        for (criteria in listOf("Percentage of Cash on Hand Lost", "Percentage of Margin Reserved Lost", "Percentage of Overnight Margin Lost", "Equity to Margin %", "Always Liquidate")) {
            val rule = rithmicDrawdownRule(risk(criteria, 1500.0, minBalance = 23500.0), null)!!
            assertNull(rule.levelMicros, "$criteria has no floor in its threshold"); assertNull(rule.amountMicros); assertEquals(1500.0, rule.thresholdMicros.usd()); assertEquals(DrawdownMode.OTHER, rule.mode)
        }
    }

    @Test fun theEndOfDayCriteriaMeasureFromTheirThresholdAndALimitedOneIsHeld() {
        val plain = rithmicDrawdownRule(risk("End of Day Drawdown", 2000.0, 48500.0), null)!!
        assertEquals(48500.0, plain.levelMicros.usd()); assertEquals(2000.0, plain.amountMicros.usd()); assertEquals(DrawdownMode.END_OF_DAY, plain.mode)
        assertNull(rithmicDrawdownRule(risk("End of Day Drawdown", 2000.0), null)!!.levelMicros, "the distance alone is not a level")
        assertTrue(rithmicDrawdownRule(risk("Limited End of Day Drawdown", 2000.0, 50100.0, null, 50100.0), null)!!.held)
    }

    @Test fun aRuleOfSeveralCriteriaReadsAsEodWithTheMinimumBalanceAsItsLine() {
        val settings = risk("Multiple Simultaneous Criteria", 1500.0, null, null, 25000.0, 23500.0)
        val rule = rithmicDrawdownRule(settings, null)!!
        assertEquals(23500.0, rule.levelMicros.usd()); assertEquals(1500.0, rule.amountMicros.usd()); assertEquals(DrawdownMode.END_OF_DAY, rule.mode)
        assertEquals(25000.0, rule.limitMicros.usd()); assertFalse(rule.held); assertEquals(DrawdownLineSource.MIN_BALANCE, rule.lineSource); assertEquals("Multiple Simultaneous Criteria", rule.criteria)
        val above = flat(rule, 25023.0)
        assertEquals(1523.0, above.roomMicros.usd()); assertEquals(0.0, above.danger, "a full drawdown of room or more")
        assertEquals(0.75, flat(rule, 23875.0).danger!!, 1e-9)
        assertEquals(24000.0, rithmicDrawdownRule(settings, money(minBalance = 24000.0))!!.levelMicros.usd(), "the P&L message's minimum is the live one")
        assertEquals(23500.0, rithmicDrawdownRule(settings, money(minBalance = 0.0))!!.levelMicros.usd(), "a zero there leaves the RMS row's")
        val reported = rithmicDrawdownRule(risk("Multiple Simultaneous Criteria", 1500.0, 24500.0, null, 25000.0, 23500.0), null)!!
        assertEquals(24500.0, reported.levelMicros.usd()); assertEquals(DrawdownLineSource.CURRENT_VALUE, reported.lineSource)
        assertTrue(rithmicDrawdownRule(risk("Multiple Simultaneous Criteria", 1500.0, null, null, 25000.0, 25000.0), null)!!.held, "the line stops at the cap")
        val bare = rithmicDrawdownRule(risk("Multiple Simultaneous Criteria", 1500.0), null)!!
        assertNull(bare.levelMicros, "no minimum balance, no line"); assertEquals(DrawdownMode.END_OF_DAY, bare.mode); assertEquals(1500.0, bare.amountMicros.usd())
        val room = flat(bare, 25023.0)
        assertNull(room.roomMicros); assertNull(room.danger)
    }

    @Test fun theLastCloseIsTheBalanceLessTheDaysClosedPnlAndUnknownWithoutIt() {
        assertEquals(25023.0, closingBalanceMicros(money(25023.0, 0.0)).usd())
        assertEquals(25023.0, closingBalanceMicros(money(25323.0, 300.0)).usd())
        assertEquals(25023.0, closingBalanceMicros(money(24723.0, -300.0)).usd())
        assertNull(closingBalanceMicros(money(25323.0)), "a day's gains are no close")
        assertNull(closingBalanceMicros(null))
    }

    @Test fun anEndOfDayLineIsTheLastCloseLessTheDrawdownHeldAtTheCapAndNeverBelowTheBrokersFigures() {
        val eod = risk("Multiple Simultaneous Criteria", 1500.0, null, null, 25000.0, 23500.0)
        val rule = rithmicDrawdownRule(eod, money(25023.0, 0.0))!!
        assertEquals(23523.0, rule.levelMicros.usd()); assertEquals(DrawdownLineSource.LAST_CLOSE, rule.lineSource); assertEquals(25023.0, rule.peakMicros.usd()); assertEquals(1500.0, rule.amountMicros.usd()); assertFalse(rule.held)
        val row = flat(rule, 25023.0)
        assertEquals(1500.0, row.roomMicros.usd(), "one full drawdown above the line"); assertEquals(0.0, row.danger)
        val losing = rithmicDrawdownRule(eod, money(24723.0, -300.0))!!
        assertEquals(23523.0, losing.levelMicros.usd(), "the day's P&L does not move the line")
        val room = flat(losing, 24723.0)
        assertEquals(1200.0, room.roomMicros.usd(), "the drawdown plus the day's P&L"); assertEquals(0.2, room.danger!!, 1e-9)
        val capped = rithmicDrawdownRule(eod, money(26800.0, 0.0))!!
        assertEquals(25000.0, capped.levelMicros.usd()); assertTrue(capped.held)
        val lower = rithmicDrawdownRule(eod, money(24800.0, 0.0))!!
        assertEquals(23500.0, lower.levelMicros.usd(), "the broker's minimum stands under a lower close"); assertEquals(DrawdownLineSource.MIN_BALANCE, lower.lineSource); assertNull(lower.peakMicros)
        assertEquals(23500.0, rithmicDrawdownRule(eod, money(25023.0))!!.levelMicros.usd(), "without the day's P&L there is no close")
        assertEquals(23600.0, rithmicDrawdownRule(risk("Multiple Simultaneous Criteria", 1500.0, 23600.0, null, 25000.0, 23500.0), money(25023.0, 0.0))!!.levelMicros.usd(), "the broker's current value comes first")
        val named = rithmicDrawdownRule(risk("End of Day Drawdown", 2000.0), money(50500.0, 0.0))!!
        assertEquals(48500.0, named.levelMicros.usd()); assertEquals(DrawdownMode.END_OF_DAY, named.mode); assertNull(named.limitMicros)
        assertNull(rithmicDrawdownRule(risk("Trailing Minimum Account Balance", 2000.0), money(50500.0, 0.0))!!.levelMicros, "only an end-of-day rule reads a close")
    }

    @Test fun aZeroLossPercentIsAReportAndTheRulesOwnFiguresAreKept() {
        val rule = rithmicDrawdownRule(risk("Percentage of Cash on Hand Lost", 1500.0, minBalance = 23500.0, lossLimit = 90000.0, maxMinBalance = 25000.0), money(lossPercent = 0.0))!!
        assertEquals(0, rule.lossPercentMicros, "zero is a report, not a gap")
        assertEquals(90000.0, rule.lossLimitMicros.usd()); assertEquals(25000.0, rule.balanceCapMicros.usd()); assertEquals(23500.0, rule.minBalanceMicros.usd())
    }

    @Test fun theValueIsTheBalanceAndTheBrokersOpenPnlThenTheAccountFigure() {
        fun position(open: Long?) = RithmicPosition("ACC1", "MESZ6", "CME", 1, 5500.0, open, 0)
        val money = money(25000.0, cash = 25000.0, closed = 0.0, open = -300.0)
        assertEquals(DrawdownAccountValue(25_000_000_000, DrawdownValueSource.CASH), rithmicAccountValue(money(25000.0), emptyList()), "a flat account needs no open P&L")
        assertEquals(DrawdownAccountValue(24_700_000_000, DrawdownValueSource.BROKER), rithmicAccountValue(money, listOf(position(-300_000_000))))
        assertEquals(DrawdownAccountValue(24_700_000_000, DrawdownValueSource.SNAPSHOT), rithmicAccountValue(money, listOf(position(null))), "a position without its P&L leaves the account figure")
        assertNull(rithmicAccountValue(money(25000.0), listOf(position(null))), "with neither the value stays unknown")
        assertNull(rithmicAccountValue(null, emptyList()))
    }

    @Test fun theRiskMessagesKeepWhatTheyDoNotCarry() {
        fun message(build: ProtoWriter.() -> Unit) = decodeProto(ProtoWriter().apply(build).toByteArray())
        val row = parseRiskUpdate(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.AUTO_LIQ_THRESHOLD_CURRENT_VALUE, "23000") }, null)
        assertEquals(23000.0, row.currentMicros.usd()); assertNull(row.peakMicros)
        val empty = parseRiskUpdate(message { string(Rf.AUTO_LIQ_THRESHOLD_CURRENT_VALUE, ""); string(Rf.AUTO_LIQ_PEAK_BALANCE, "25400") }, row)
        assertEquals(23000.0, empty.currentMicros.usd(), "an empty field is unknown, not a reset"); assertEquals(25400.0, empty.peakMicros.usd())
        val settings = parseRiskSettings(listOf(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.AUTO_LIQUIDATE_CRITERIA, "  Loss\u0007 Limit "); double(Rf.AUTO_LIQUIDATE_THRESHOLD, 1500.0); double(Rf.LOSS_LIMIT, Double.NaN) }), "ACC1", empty)!!
        assertEquals("Loss Limit", settings.criteria); assertEquals(1500.0, settings.thresholdMicros.usd()); assertNull(settings.lossLimitMicros, "not a number stays unknown")
        assertEquals(23000.0, settings.currentMicros.usd(), "the settings keep the line")
        assertSame(empty, parseRiskSettings(listOf(message { string(Rf.ACCOUNT_ID, "OTHER") }), "ACC1", empty), "another account's row changes nothing")
    }

    @Test fun theAccountPnlMessageKeepsItsMinimumBalanceAndLossPercent() {
        fun message(build: ProtoWriter.() -> Unit) = decodeProto(ProtoWriter().apply(build).toByteArray())
        val first = parseFinancials(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.MIN_ACCOUNT_BALANCE, "23600"); string(Rf.PERCENT_MAX_ALLOWABLE_LOSS, "12.5") }, null, 1)!!
        assertEquals(23600.0, first.minBalanceMicros.usd()); assertEquals(12.5, first.lossPercentMicros.usd())
        val next = parseFinancials(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.MIN_ACCOUNT_BALANCE, "") }, first, 2)!!
        assertEquals(23600.0, next.minBalanceMicros.usd()); assertEquals(12.5, next.lossPercentMicros.usd())
    }

    private suspend fun TestScope.connected(server: FakeRithmicServer): RithmicBroker {
        val broker = RithmicBroker(credentials(), server, backgroundScope, { FAKE_TIME })
        broker.connect()
        runCurrent()
        return broker
    }

    private fun trailing(server: FakeRithmicServer) = server.apply {
        autoLiqCriteria = "Limited Trailing Minimum Account Balance"; autoLiqThreshold = 2000.0; autoLiqMaxMin = 50100.0; autoLiqLines["ACC1"] = "23000"
    }

    private fun accountUpdate(server: FakeRithmicServer, balance: String, cash: String = balance, closed: String = "0", open: String = "0", dayClosed: String? = null, minBalance: String? = null, lossPercent: String? = null) =
        server.plant(RithmicEnums.INFRA_PNL).push {
            int32(Rf.TEMPLATE_ID, Rt.ACCOUNT_PNL_UPDATE); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.ACCOUNT_BALANCE, balance); string(Rf.CASH_ON_HAND, cash)
            string(Rf.CLOSED_POSITION_PNL, closed); string(Rf.OPEN_POSITION_PNL, open); dayClosed?.let { string(Rf.DAY_CLOSED_PNL, it) }
            minBalance?.let { string(Rf.MIN_ACCOUNT_BALANCE, it) }; lossPercent?.let { string(Rf.PERCENT_MAX_ALLOWABLE_LOSS, it) }
            int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
        }

    @Test fun anAccountWithoutRiskFiguresHasNoDrawdown() = runTest {
        assertNull(connected(FakeRithmicServer()).openSession("ACC1").state.value.drawdown)
    }

    @Test fun aLoginReadsTheLineAndTheSettingsSubscribesAndAnUpdateMovesTheLine() = runTest {
        val server = trailing(FakeRithmicServer())
        val account = connected(server).openSession("ACC1")
        val login = account.state.value.drawdown!!
        assertEquals(23000.0, login.rule.levelMicros.usd()); assertEquals(2000.0, login.rule.amountMicros.usd()); assertEquals(50100.0, login.rule.limitMicros.usd()); assertEquals(DrawdownMode.REAL_TIME, login.rule.mode)
        assertEquals(2000.0, login.roomMicros.usd(), "the balance less the line"); assertEquals(0.0, login.danger); assertEquals(DrawdownValueSource.CASH, login.source)
        val request = server.requests(Rt.REQUEST_ACCOUNT_RMS_UPDATES).single()
        assertEquals("subscribe", request.str(Rf.REQUEST), "the request field is text here")
        assertEquals(RithmicEnums.RMS_UPDATE_AUTO_LIQ_CURRENT_VALUE, request.int(Rf.UPDATE_BITS))
        assertEquals("FCM1", request.str(Rf.FCM_ID)); assertEquals("IB1", request.str(Rf.IB_ID)); assertEquals("ACC1", request.str(Rf.ACCOUNT_ID))
        server.pushRms("ACC1", "23400", "25400"); runCurrent()
        assertEquals(23400.0, account.state.value.drawdown!!.rule.levelMicros.usd()); assertEquals(25400.0, account.state.value.drawdown!!.rule.peakMicros.usd())
        assertEquals(0.2, account.state.value.drawdown!!.danger!!, 1e-9)
        server.pushRms("ACC1", null, "25600"); runCurrent()
        assertEquals(23400.0, account.state.value.drawdown!!.rule.levelMicros.usd(), "an update without the line keeps it"); assertEquals(25600.0, account.state.value.drawdown!!.rule.peakMicros.usd())
    }

    @Test fun aSystemThatRefusesOrIgnoresTheRmsUpdatesLosesNothingButTheUpdates() = runTest {
        for (reply in listOf(FakeRithmicServer.RmsUpdates.REJECT, FakeRithmicServer.RmsUpdates.SILENT)) {
            val server = trailing(FakeRithmicServer()).apply { rmsUpdatesReply = reply }
            val broker = connected(server)
            assertTrue(broker.market.value.connected, "$reply: ${broker.market.value.statusMessage}")
            assertEquals(23000.0, broker.openSession("ACC1").state.value.drawdown!!.rule.levelMicros.usd(), "the account list's line stands")
            advanceTimeBy(16_000); runCurrent()
            assertTrue(broker.market.value.connected, "$reply: ${broker.market.value.statusMessage}")
        }
    }

    @Test fun anAccountWithoutACurrentValueTakesItsLineFromTheMinimumBalanceUntilTheBrokerPushesOne() = runTest {
        val server = FakeRithmicServer().apply {
            autoLiqCriteria = "Multiple Simultaneous Criteria"; autoLiqThreshold = 1500.0; autoLiqMaxMin = 25000.0; rmsLossLimit = 90000.0; rmsMinBalance = 23500.0; pnlLossPercent = "0"; snapshotBalance = "24000"
        }
        val account = connected(server).openSession("ACC1")
        val rule = account.state.value.drawdown!!.rule
        assertEquals(23500.0, rule.levelMicros.usd()); assertEquals(DrawdownLineSource.MIN_BALANCE, rule.lineSource); assertEquals(1500.0, rule.amountMicros.usd()); assertEquals(90000.0, rule.lossLimitMicros.usd()); assertEquals(0, rule.lossPercentMicros, "zero is a report, not a gap")
        accountUpdate(server, "24000", minBalance = "23600", lossPercent = "12.5"); runCurrent()
        assertEquals(23600.0, account.state.value.drawdown!!.rule.levelMicros.usd()); assertEquals(12_500_000, account.state.value.drawdown!!.rule.lossPercentMicros)
        server.pushRms("ACC1", "23700", "25200"); runCurrent()
        assertEquals(23700.0, account.state.value.drawdown!!.rule.levelMicros.usd()); assertEquals(DrawdownLineSource.CURRENT_VALUE, account.state.value.drawdown!!.rule.lineSource)
        assertEquals(1500.0, account.state.value.drawdown!!.rule.amountMicros.usd()); assertEquals(90000.0, account.state.value.drawdown!!.rule.lossLimitMicros.usd(), "an update keeps the settings")
    }

    @Test fun theLineIsReadAgainByTheNextLogin() = runTest {
        val server = trailing(FakeRithmicServer())
        val broker = connected(server)
        server.autoLiqLines["ACC1"] = "23800"
        server.plant(RithmicEnums.INFRA_TICKER).drop(); runCurrent()
        advanceTimeBy(3_100); runCurrent()
        assertTrue(broker.market.value.connected, broker.market.value.statusMessage)
        assertEquals(23800.0, broker.openSession("ACC1").state.value.drawdown!!.rule.levelMicros.usd())
        assertEquals(2, server.requests(Rt.REQUEST_ACCOUNT_RMS_UPDATES).size, "the new login subscribes again")
    }

    @Test fun theRoomStandsOnTheBalanceAndTheBrokersOpenPnlWithoutCountingItTwice() = runTest {
        val server = trailing(FakeRithmicServer()).apply { positions += positionRow(2, 5480.0, "-300") }
        val account = connected(server).openSession("ACC1")
        // A balance that already holds the open P&L, as cash and closed P&L show.
        accountUpdate(server, "24700", cash = "25000", open = "-300"); runCurrent()
        val drawdown = account.state.value.drawdown!!
        assertEquals(1700.0, drawdown.roomMicros.usd(), "24,700 less the line"); assertEquals(0.15, drawdown.danger!!, 1e-9); assertEquals(DrawdownValueSource.BROKER, drawdown.source)
        assertEquals(24_700_000_000, account.state.value.equityMicros)
        server.pushRms("ACC1", "23500", "25500"); runCurrent()
        assertEquals(1200.0, account.state.value.drawdown!!.roomMicros.usd(), "the room under the raised line")
    }

    @Test fun theSessionReadsTheLineFromTheLivePnlMessageItMovesAtTheRollAndFallsWithALostDay() = runTest {
        val server = FakeRithmicServer().apply {
            autoLiqCriteria = "Multiple Simultaneous Criteria"; autoLiqThreshold = 1500.0; autoLiqMaxMin = 25000.0; rmsMinBalance = 23500.0; snapshotBalance = "25023"; snapshotDayClosed = "0"
        }
        val account = connected(server).openSession("ACC1")
        fun rule() = account.state.value.drawdown!!.rule
        assertEquals(23523.0, rule().levelMicros.usd()); assertEquals(DrawdownLineSource.LAST_CLOSE, rule().lineSource)
        fun day(balance: String, closed: String) { accountUpdate(server, balance, dayClosed = closed); runCurrent() }
        day("25323", "300")
        assertEquals(23523.0, rule().levelMicros.usd(), "a day's gains do not move the line")
        day("25323", "0")
        assertEquals(23823.0, rule().levelMicros.usd(), "the line after the roll")
        day("24900", "-423")
        assertEquals(23823.0, rule().levelMicros.usd(), "a day's loss does not move it either")
        day("24900", "0")
        assertEquals(23500.0, rule().levelMicros.usd(), "the line after the lost day, at the broker's minimum")
        assertEquals(DrawdownLineSource.MIN_BALANCE, rule().lineSource)
    }
}
