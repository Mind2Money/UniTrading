package com.unitrading.ui

import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class TestClock(var time: Instant = Instant.parse("2026-11-11T16:00:00Z")) : Clock {
    override fun now() = time
}

/**
 * A broker whose accounts a test sets directly: by default a net account, as Tradovate and Rithmic are, that reports its
 * trading day with the day P&L. Every command that reaches it is recorded in [events], and every order in [placed] as
 * it was sent; a cancel or a close takes effect on the snapshot at once unless the test says otherwise. A market order
 * is previewed at the quote of the moment, which a test moves with [quote].
 */
internal class RiskFixture(
    val clock: TestClock = TestClock(),
    val source: OfflineOrderBroker = OfflineOrderBroker(),
    hedging: Boolean = false,
    tradingDayPnl: Boolean = true,
    login: String = source.descriptor.id,
    children: List<AccountInfo>? = null,
) : BrokerPlugin by source {
    private val first = source.openSession(source.accounts.single().id)
    override val descriptor = source.descriptor.copy(id = login)
    override val capabilities = source.capabilities.copy(hedging = hedging, tradingDayPnl = tradingDayPnl)
    override val accounts = children ?: listOf(first.account, AccountInfo("200", "Other child"))
    override val catalog = MutableStateFlow(BrokerCatalog(source.instruments, accounts))
    val request = OrderRequest(source.spec.symbol, Side.BUY, OrderKind.MARKET, 100)
    val events = mutableListOf<String>()
    val placed = mutableListOf<OrderRequest>()
    /** A note the broker adds to every preview, as Rithmic does for a market order whose brackets it places by distance. */
    var previewWarning: String? = null
    /** The quote the broker previews a market order at while it is ahead of [market]: Tradovate takes a quote in before it publishes it. */
    var previewQuote: Quote? = null
    /** The server's order limit in hundredths of a lot; null while the broker gives none. */
    var capacity: Long? = null
    var rejectCancel = false
    /** Runs instead of taking a closed position off the snapshot, for a close that fails or leaves another position behind. */
    var onClose: ((String) -> TradeResult)? = null
    /** Runs instead of taking a cancelled order off the snapshot, for an order that fills before the cancel arrives. */
    var onCancel: ((String) -> TradeResult)? = null
    var onRefresh: (String) -> Unit = {}
    override suspend fun refreshDailyPnl(accountId: String) = onRefresh(accountId)
    val snapshots = accounts.associate { it.id to MutableStateFlow(AccountSnapshot(it.id, 25_000_000_000L)) }
    val sessions = accounts.associate { child ->
        child.id to object : AccountSession by first {
            override val account = child
            override val state = snapshots.getValue(child.id)
            private fun sent(event: String): TradeResult { events += event; return TradeResult(true, "accepted") }
            override fun preview(request: OrderRequest) = first.preview(request).copy(
                entryPrice = request.entryPrice ?: (previewQuote ?: market.value.quotes[request.symbol])?.let { if (request.side == Side.BUY) it.ask else it.bid },
                warnings = listOfNotNull(previewWarning))
            override suspend fun orderCapacity(request: OrderRequest, lastKnown: Long?) = OrderCapacity(capacity)
            override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
                placed += request
                return sent("place:${child.id}")
            }
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) =
                sent("modify:$orderId:$entryPrice")
            override suspend fun changeOrder(requestId: String, orderId: String, expectedVersion: Long, price: Long, lotsHundredths: Long) =
                sent("change:$orderId:$price:$lotsHundredths")
            override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
                sent("protect-order:$orderId:$stopLoss:$takeProfit")
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
                sent("protect:$positionId:$stopLoss:$takeProfit")
            override suspend fun cancelOrder(requestId: String, orderId: String): TradeResult {
                events += "cancel:$orderId"
                if (rejectCancel) return TradeResult(false, "unknown", outcome = CommandOutcome.UNKNOWN)
                onCancel?.let { return it(orderId) }
                state.value = state.value.copy(orders = state.value.orders.filterNot { it.id == orderId })
                return TradeResult(true, "cancelled")
            }
            override suspend fun closePosition(requestId: String, positionId: String): TradeResult {
                events += "close:$positionId"
                onClose?.let { return it(positionId) }
                state.value = state.value.copy(positions = state.value.positions.filterNot { it.id == positionId })
                return TradeResult(true, "closed")
            }
        }
    }
    override fun openSession(accountId: String) = sessions.getValue(accountId)
    fun pnl(id: String, amount: Long, day: String = "2026-11-11") {
        val state = snapshots.getValue(id)
        state.value = state.value.copy(dailyPnl = DailyPnl(amount, tradingDay = day, updatedAtMs = clock.time.toEpochMilliseconds()))
    }
    fun hold(id: String, positions: List<Position> = emptyList(), orders: List<PendingOrder> = emptyList()) {
        val state = snapshots.getValue(id)
        state.value = state.value.copy(positions = positions, orders = orders)
    }
    fun orderIds(id: String) = snapshots.getValue(id).value.orders.map { it.id }
    fun quote(bid: Long, ask: Long, symbol: String = source.spec.symbol) {
        source.market.value = source.market.value.let { it.copy(quotes = it.quotes + (symbol to Quote(bid, ask, source.time)), revision = it.revision + 1) }
    }
}

internal fun riskPosition(side: Side, lots: Long, id: String = "held", symbol: String = "TESTZ6", stop: Long? = null, target: Long? = null) =
    Position(id, symbol, side, lots, 550_000, 0, stopLoss = stop, takeProfit = target, marginMicros = 0)

/** A working order; with the default role it is whatever the user placed, which no broker pairs with a position. */
internal fun riskOrder(
    id: String, side: Side, kind: OrderKind, lots: Long, price: Long, role: OrderRole = OrderRole.ENTRY, filled: Long = 0,
    stop: Long? = null, symbol: String = "TESTZ6", target: Long? = null,
) = PendingOrder(id, OrderRequest(symbol, side, kind, lots, price, stopLoss = stop, takeProfit = target), 0, role = role, filledLotsHundredths = filled)

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRiskTest {
    /** Saves on the test's own scheduler, so nothing in a test waits on a real thread or a real clock. */
    private fun TestScope.controller(store: SnapshotStore = MemorySnapshotStore(), clock: Clock = TestClock()) =
        AccountRiskController(store, clock, StandardTestDispatcher(testScheduler))

    private fun trigger(risk: AccountRiskController, broker: RiskFixture, id: String) = risk.rule(broker, id).trigger
    private val sell = OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100)
    private val exitStop = sell.copy(kind = OrderKind.STOP, lotsHundredths = 200, entryPrice = 548_000)
    private val exitTarget = sell.copy(kind = OrderKind.LIMIT, lotsHundredths = 200, entryPrice = 552_000)
    private val stillAllowed = "；仍可平仓、撤单、收紧止损或挂减仓单。"

    @Test fun thresholdLatchesBeforeOrderAndSurvivesRestartUntilNewBrokerDay() = runTest {
        val broker = RiskFixture()
        val store = MemorySnapshotStore()
        val risk = controller(store, broker.clock)
        val raw = broker.openSession("100")
        val session = risk.guard(broker, raw)
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000L)))
        assertFalse(session.placeOrder("missing", broker.request).accepted)
        broker.pnl("100", -200_000_000)
        assertFalse(session.placeOrder("loss", broker.request).accepted)
        assertTrue(risk.guard(broker, broker.openSession("200")).placeOrder("other", broker.request).accepted)
        broker.pnl("100", 0)
        val restarted = controller(store, broker.clock).guard(broker, raw)
        assertFalse(restarted.placeOrder("rebound", broker.request).accepted)
        assertTrue(restarted.closePosition("manual", "held").accepted)
        broker.pnl("100", 0, "2026-11-12")
        assertTrue(restarted.placeOrder("next", broker.request).accepted)
        broker.clock.time += 3.minutes
        assertFalse(restarted.placeOrder("stale", broker.request).accepted)
        assertEquals(listOf("place:200", "close:held", "place:100"), broker.events)
    }

    @Test fun exitsPassALockAndMissingPnlWhileEntriesAreRefused() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val session = risk.guard(broker, broker.openSession("100"))
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000)))
        broker.pnl("100", -250_000_000)
        val locked = "2026-11-11 交易日已锁定 · 达到亏损额度$stillAllowed"
        assertEquals(TradeResult(false, locked), session.placeOrder("add", broker.request))
        assertNotNull(trigger(risk, broker, "100"))
        assertTrue(session.placeOrder("stop", exitStop).accepted, "a stop for the position held")
        assertTrue(session.placeOrder("target", exitTarget).accepted, "a take-profit limit")
        assertTrue(session.placeOrder("scale-out", sell).accepted, "a partial close")
        assertEquals(locked, session.placeOrder("reverse", sell.copy(lotsHundredths = 300)).message, "more than the position opens the other side")
        assertEquals(locked, session.placeOrder("elsewhere", sell.copy(symbol = "OTHRZ6")).message, "nothing is held there")
        // The screen asks the same question before it opens a confirmation.
        assertNull(risk.refusal(broker, session, RiskCheck.Place(exitStop)))
        assertEquals(locked, risk.refusal(broker, session, RiskCheck.Place(broker.request)))

        // The other child is under its limit, but its P&L snapshot grows older than two minutes: after every fill Tradovate
        // leaves an account without one for a few seconds.
        val other = risk.guard(broker, broker.openSession("200"))
        broker.hold("200", listOf(riskPosition(Side.SELL, 200)))
        assertNull(risk.configure(broker, "200", RiskLimits(200_000_000)))
        broker.pnl("200", 0)
        assertTrue(other.placeOrder("fresh", sell).accepted)
        broker.clock.time += 3.minutes
        val waiting = "等待券商当日盈亏数据，已暂停开仓和改单$stillAllowed"
        assertEquals(TradeResult(false, waiting), other.placeOrder("add", sell))
        val cover = broker.request.copy(kind = OrderKind.STOP, lotsHundredths = 200, entryPrice = 552_000)
        assertTrue(other.placeOrder("stop", cover).accepted, "a buy stop guards the short")
        assertTrue(other.placeOrder("scale-out", broker.request).accepted)
        assertNull(trigger(risk, broker, "200"))
        assertEquals(listOf("place:100", "place:100", "place:100", "place:200", "place:200", "place:200"), broker.events)
    }

    @Test fun aStopMayTightenButNotLoosenOrGoWhileLocked() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val session = risk.guard(broker, broker.openSession("100"))
        broker.hold("100", listOf(riskPosition(Side.BUY, 200, stop = 548_000), riskPosition(Side.SELL, 100, "sold", "OTHRZ6", stop = 552_000)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000)))
        // Not locked: any change goes to the broker.
        broker.pnl("100", 0)
        assertTrue(session.modifyProtection("free", "held", 1, null, null).accepted)
        broker.pnl("100", -250_000_000)
        assertFalse(session.placeOrder("latch", broker.request).accepted)
        assertTrue(session.modifyProtection("tighten", "held", 1, 549_000, null).accepted)
        assertTrue(session.modifyProtection("target", "held", 1, 548_000, 553_000).accepted, "a take profit moves freely")
        assertFalse(session.modifyProtection("loosen", "held", 1, 547_975, null).accepted)
        assertFalse(session.modifyProtection("remove", "held", 1, null, 553_000).accepted)
        assertTrue(session.modifyProtection("tighten-short", "sold", 1, 551_000, null).accepted)
        assertFalse(session.modifyProtection("loosen-short", "sold", 1, 552_025, null).accepted)
        assertFalse(session.modifyProtection("gone", "closed", 1, 549_000, null).accepted, "an unknown position cannot be checked")
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)),
            listOf(riskOrder("entry", Side.BUY, OrderKind.LIMIT, 100, 549_000, stop = 547_000)))
        assertTrue(session.modifyProtection("add", "held", 1, 547_000, null).accepted, "a first stop only reduces risk")
        assertTrue(session.modifyOrderProtection("entry-tighten", "entry", 1, 548_000, null).accepted)
        assertFalse(session.modifyOrderProtection("entry-loosen", "entry", 1, 546_000, null).accepted)
        // Data that is only missing pauses the same way: the stop still moves up, never down.
        broker.pnl("100", 0, "2026-11-12")
        assertTrue(session.modifyProtection("released", "held", 1, null, null).accepted)
        broker.clock.time += 3.minutes
        broker.hold("100", listOf(riskPosition(Side.BUY, 200, stop = 548_000)))
        assertTrue(session.modifyProtection("stale-tighten", "held", 1, 549_000, null).accepted)
        assertFalse(session.modifyProtection("stale-loosen", "held", 1, 547_000, null).accepted)
        assertEquals(listOf("protect:held:null:null", "protect:held:549000:null", "protect:held:548000:553000", "protect:sold:551000:null",
            "protect:held:547000:null", "protect-order:entry:548000:null", "protect:held:null:null", "protect:held:549000:null"), broker.events)
    }

    @Test fun whileLockedAnExitOrderOnlyMovesTowardsTheMarketAndAnEntryCanOnlyBeCancelled() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val session = risk.guard(broker, broker.openSession("100"))
        // The stop and the target were placed by hand after the entry: to the broker they are ordinary orders.
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)), listOf(
            riskOrder("stop", Side.SELL, OrderKind.STOP, 200, 548_000),
            riskOrder("target", Side.SELL, OrderKind.LIMIT, 200, 552_000),
            riskOrder("add", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000)))
        broker.pnl("100", -250_000_000)
        assertFalse(session.placeOrder("latch", broker.request).accepted)
        assertTrue(session.modifyOrder("tighten", "stop", 1, 549_000).accepted)
        assertFalse(session.modifyOrder("loosen", "stop", 1, 547_000).accepted)
        assertTrue(session.changeOrder("target", "target", 1, 553_000, 200).accepted, "a limit exit moves freely")
        assertTrue(session.changeOrder("smaller", "stop", 1, 548_000, 100).accepted)
        assertFalse(session.changeOrder("bigger", "stop", 1, 548_000, 300).accepted, "beyond the position it would open the other side")
        assertFalse(session.modifyOrder("entry", "add", 1, 549_500).accepted)
        assertFalse(session.changeOrder("entry-smaller", "add", 1, 549_000, 100).accepted)
        assertEquals("挂单已更新，请重新选择。", session.modifyOrder("gone", "filled", 1, 549_000).message)
        assertTrue(session.cancelOrder("cancel", "add").accepted)
        assertEquals(listOf("modify:stop:549000", "change:target:553000:200", "change:stop:548000:100", "cancel:add"), broker.events)
    }

    @Test fun lotCapCountsTheWorstCaseOfEachSymbolAndLetsExitsThrough() = runTest {
        val broker = RiskFixture()
        val store = MemorySnapshotStore()
        assertNull(controller(store).configureOrders(broker, "100", AccountOrderLimits(500, setOf("MES", "CLZ6"))))
        val risk = controller(store)
        val session = risk.guard(broker, broker.openSession("100"))
        // Long 3 with 1 more still to fill: 4 at worst. The bracket stop the broker paired with the position stays out.
        broker.hold("100", listOf(riskPosition(Side.BUY, 300)), listOf(
            riskOrder("entry", Side.BUY, OrderKind.LIMIT, 200, 549_000, filled = 100),
            riskOrder("bracket", Side.SELL, OrderKind.STOP, 300, 548_000, OrderRole.STOP_LOSS)))
        assertTrue(session.placeOrder("equal", broker.request).accepted)
        val cap = session.placeOrder("cap", broker.request.copy(lotsHundredths = 101))
        assertEquals(TradeResult(false, "超过子账户最大手数 5.00（含持仓和未成交开仓挂单）。"), cap)
        assertTrue(session.placeOrder("otherMonth", broker.request.copy(symbol = "CLH7")).accepted, "another symbol adds to the same total")
        assertFalse(session.placeOrder("otherMonthCap", broker.request.copy(symbol = "CLH7", lotsHundredths = 101)).accepted)
        // A stop, a target and a partial close add nothing, and neither does moving or shrinking an order.
        assertTrue(session.placeOrder("stop", exitStop.copy(lotsHundredths = 300)).accepted)
        assertTrue(session.placeOrder("target", exitTarget.copy(lotsHundredths = 300)).accepted)
        assertTrue(session.placeOrder("scale-out", sell).accepted)
        assertTrue(session.modifyOrder("move", "entry", 1, 549_500).accepted)
        assertFalse(session.changeOrder("grow", "entry", 1, 549_000, 301).accepted, "5.01 at worst")
        assertTrue(session.changeOrder("grow-to-cap", "entry", 1, 549_000, 300).accepted)
        // Blocked symbols: the root covers its months and the continuous code, a dated code only itself.
        assertEquals("该子账户禁止交易品种 MES。", session.placeOrder("root", broker.request.copy(symbol = "MESZ6")).message)
        assertFalse(session.placeOrder("continuous", broker.request.copy(sourceSymbol = "@MES")).accepted)
        assertFalse(session.placeOrder("exact", broker.request.copy(symbol = "CLZ6")).accepted)
        assertTrue(risk.guard(broker, broker.openSession("200")).placeOrder("otherChild", broker.request.copy(symbol = "MESZ6")).accepted)
        assertTrue(session.cancelOrder("reduce", "entry").accepted)
        assertTrue(session.closePosition("close", "held").accepted)

        // A cap lowered under what is already held stops nothing that reduces it.
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(100)))
        broker.hold("100", listOf(riskPosition(Side.BUY, 300)), listOf(riskOrder("stop", Side.SELL, OrderKind.STOP, 300, 548_000)))
        assertTrue(session.placeOrder("over-target", exitTarget.copy(lotsHundredths = 300)).accepted)
        assertTrue(session.placeOrder("over-scale-out", sell).accepted)
        assertTrue(session.modifyOrder("over-move", "stop", 1, 549_000).accepted)
        assertFalse(session.placeOrder("over-add", broker.request).accepted)
    }

    @Test fun aBlockedSymbolStillTakesExitsForThePositionHeldThere() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val session = risk.guard(broker, broker.openSession("100"))
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(blockedSymbols = setOf("TEST"))))
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)), listOf(
            riskOrder("stop", Side.SELL, OrderKind.STOP, 200, 548_000),
            riskOrder("add", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        val blocked = TradeResult(false, "该子账户禁止交易品种 TEST。")
        assertTrue(session.placeOrder("target", exitTarget).accepted)
        assertTrue(session.placeOrder("scale-out", sell).accepted)
        assertTrue(session.modifyOrder("drag-stop", "stop", 1, 547_000).accepted, "the limits do not judge where a stop goes")
        assertEquals(blocked, session.placeOrder("add", broker.request))
        assertEquals(blocked, session.placeOrder("reverse", sell.copy(lotsHundredths = 300)))
        assertEquals(blocked, session.modifyOrder("move-entry", "add", 1, 549_500))
        assertEquals(blocked, session.changeOrder("grow-stop", "stop", 1, 548_000, 300))
        assertEquals(listOf("place:100", "place:100", "modify:stop:547000"), broker.events)
    }

    @Test fun aHedgingAccountKeepsTheGrossLotCapAndHasNoExitOrders() = runTest {
        val broker = RiskFixture(hedging = true)
        val risk = controller()
        val session = risk.guard(broker, broker.openSession("100"))
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(500)))
        broker.hold("100", listOf(riskPosition(Side.SELL, 300)), listOf(
            riskOrder("entry", Side.BUY, OrderKind.LIMIT, 200, 549_000, filled = 100),
            riskOrder("exit", Side.BUY, OrderKind.STOP, 300, 552_000, OrderRole.STOP_LOSS)))
        // Every order opens a position of its own there, so both sides add up: 3 held and 1 working.
        assertTrue(session.placeOrder("equal", broker.request).accepted)
        assertFalse(session.placeOrder("cap", broker.request.copy(lotsHundredths = 101)).accepted)
        assertFalse(session.placeOrder("other-side", sell.copy(lotsHundredths = 101)).accepted)
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(blockedSymbols = setOf("TEST"))))
        assertFalse(session.placeOrder("blocked", broker.request).accepted, "an order against the position is a new position there")
        assertTrue(session.closePosition("close", "held").accepted)
        assertEquals(listOf("place:100", "close:held"), broker.events)
    }

    @Test fun aProtectionOrderMayNotGrowBeyondThePositionItProtects() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val session = risk.guard(broker, broker.openSession("100"))
        // A bracket leg the broker could not pair with the whole position: it has the stop role and is edited on its own.
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)), listOf(riskOrder("leg", Side.SELL, OrderKind.STOP, 100, 548_000, OrderRole.STOP_LOSS)))
        assertTrue(session.changeOrder("unlimited", "leg", 1, 548_000, 600).accepted, "without a rule nothing is checked")
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(200)))
        assertTrue(session.changeOrder("move", "leg", 1, 547_000, 100).accepted)
        assertTrue(session.changeOrder("whole-position", "leg", 1, 548_000, 200).accepted)
        // Six lots against a long of two would leave the account short four, twice the cap.
        assertEquals("超过子账户最大手数 2.00（含持仓和未成交开仓挂单）。", session.changeOrder("beyond", "leg", 1, 548_000, 600).message)
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(blockedSymbols = setOf("TEST"))))
        assertTrue(session.changeOrder("blocked-move", "leg", 1, 549_000, 100).accepted)
        assertEquals("该子账户禁止交易品种 TEST。", session.changeOrder("blocked-beyond", "leg", 1, 548_000, 300).message)
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits()))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000)))
        broker.pnl("100", -250_000_000)
        assertFalse(session.changeOrder("locked-beyond", "leg", 1, 548_000, 300).accepted)
        assertTrue(session.changeOrder("locked-whole", "leg", 1, 548_000, 200).accepted)
        assertEquals(listOf("change:leg:548000:600", "change:leg:547000:100", "change:leg:548000:200", "change:leg:549000:100",
            "change:leg:548000:200"), broker.events)
    }

    @Test fun anOrderThatOnlyReducesThePositionGoesWithoutBracketsAndAnEntryGetsThem() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val session = risk.guard(broker, broker.openSession("100"))
        assertNull(risk.configureBrackets(broker, "100", AccountBrackets(loss = 100_000_000, profit = 200_000_000)))
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)))
        // Long two. A partial close, a target and a stop for it need no stop or target of their own, and Tradovate and
        // Rithmic take none on an order in a contract that has a position.
        for (exit in listOf(sell, exitTarget, exitStop)) assertTrue(session.placeOrder("exit", exit).accepted, "$exit")
        assertEquals(listOf(sell, exitTarget, exitStop), broker.placed)
        // What opens or adds still carries them. A resting entry is checked as it stands.
        val add = broker.request.copy(kind = OrderKind.LIMIT, entryPrice = 549_000)
        assertEquals(TradeResult(false, "该子账户的订单必须带止损"), session.placeOrder("bare", add))
        val bracketed = add.copy(stopLoss = 547_000, takeProfit = 553_000)
        assertTrue(session.placeOrder("entry", bracketed).accepted)
        assertEquals("当前预估亏损超出子账户风险设置，请重新检查订单", session.placeOrder("loose", bracketed.copy(stopLoss = 546_975)).message)
        // Three against a long of two would leave the account short one: no exit, so at the market it is priced like any entry.
        assertTrue(session.placeOrder("reverse", sell.copy(lotsHundredths = 300)).accepted)
        assertEquals(listOf(bracketed, sell.copy(lotsHundredths = 300, stopLoss = 550_650, takeProfit = 548_650)), broker.placed.drop(3))
        assertFalse(session.placeOrder("elsewhere", sell.copy(symbol = "OTHRZ6", kind = OrderKind.LIMIT, entryPrice = 552_000)).accepted, "nothing is held there")
        // A broker that takes no bracket beside a position, as Tradovate and Rithmic, still refuses an order that adds, in
        // its own words; the exits never ask it.
        val netPosition = "该合约已有净仓，不能再叠加括号保护"
        broker.source.refusal = netPosition
        assertEquals(TradeResult(false, netPosition), session.placeOrder("add", broker.request))
        assertTrue(session.placeOrder("exit-again", sell).accepted)
        // On a hedging account an order against the long opens a short of its own, which the amounts protect.
        val hedged = RiskFixture(hedging = true).apply { hold("100", listOf(riskPosition(Side.BUY, 200))) }
        assertTrue(risk.guard(hedged, hedged.openSession("100")).placeOrder("against", sell).accepted)
        assertEquals(listOf(sell.copy(stopLoss = 552_000, takeProfit = 546_000)), hedged.placed)
    }

    @Test fun aMarketOrderIsPricedAgainFromTheQuoteItIsSentAt() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val session = risk.guard(broker, broker.openSession("100"))
        assertNull(risk.configureBrackets(broker, "100", AccountBrackets(loss = 100_000_000, profit = 200_000_000)))
        // Reviewed at an ask of 5500.25: the stop 80 ticks under it loses the amount to the cent.
        val reviewed = broker.request.copy(stopLoss = 548_025, takeProfit = 554_025)
        // The ask is one tick higher when the command arrives. Against the stop it was reviewed with, the order would
        // lose 101.25; it goes out with the stop and target of the new quote.
        broker.quote(550_025, 550_050)
        assertEquals(TradeResult(true, "accepted"), session.placeOrder("moved", reviewed))
        assertEquals(reviewed.copy(stopLoss = 548_050, takeProfit = 554_050), broker.placed.single())
        // The broker may also hold a quote it has not published yet. Its own check then enters a tick away from the
        // price the stop was worked out at, which is no reason to refuse the order either.
        broker.previewQuote = Quote(550_050, 550_075, 0)
        assertEquals(TradeResult(true, "accepted"), session.placeOrder("ahead", reviewed))
        assertEquals(reviewed.copy(stopLoss = 548_050, takeProfit = 554_050), broker.placed.last())
        broker.previewQuote = null
        // An order that reaches the guard without any gets them the same way, and a leg the amounts do not manage is kept.
        assertTrue(session.placeOrder("bare", sell).accepted)
        assertEquals(sell.copy(stopLoss = 552_025, takeProfit = 546_025), broker.placed.last())
        assertNull(risk.configureBrackets(broker, "100", AccountBrackets(loss = 100_000_000)))
        assertTrue(session.placeOrder("own-target", reviewed).accepted)
        assertEquals(reviewed.copy(stopLoss = 548_050), broker.placed.last())
        // A resting entry keeps the price it was reviewed at, so it is still checked as it stands.
        val resting = reviewed.copy(kind = OrderKind.LIMIT, entryPrice = 549_000, stopLoss = 546_975)
        assertFalse(session.placeOrder("resting", resting).accepted)
        // Without a quote nothing can be priced.
        broker.source.market.value = broker.source.market.value.copy(quotes = emptyMap())
        assertEquals(TradeResult(false, "需要有效入场价或报价"), session.placeOrder("blind", reviewed))

        // Sized from a loss of 100: 40 ticks of stop cost 50 a contract, so two contracts were reviewed.
        val other = risk.guard(broker, broker.openSession("200"))
        assertNull(risk.configureBrackets(broker, "200", AccountBrackets(sizingRisk = 100_000_000, sizingMaxLots = 1_000)))
        val sized = broker.request.copy(lotsHundredths = 200, stopLoss = 549_025)
        broker.quote(550_500, 550_525)
        assertTrue(other.placeOrder("further", sized).accepted)
        assertEquals(sized.copy(lotsHundredths = 100), broker.placed.last(), "60 ticks now cost 75 a contract")
        // A quote that moved towards the stop would buy four, and never sends more than was reviewed.
        broker.quote(549_500, 549_525)
        assertTrue(other.placeOrder("closer", sized).accepted)
        assertEquals(sized, broker.placed.last())
        broker.quote(549_000, 549_025)
        assertEquals("买入止损价须低于入场价", other.placeOrder("through", sized).message)
        assertEquals("请输入有效止损价 SL", other.placeOrder("bare", broker.request).message)
        assertEquals(6, broker.placed.size)
    }

    @Test fun aSizedOrderStaysUnderItsCeilingAndARuleWithoutOneRefusesEntries() = runTest {
        val broker = RiskFixture()
        val store = MemorySnapshotStore()
        val risk = controller(store)
        val session = risk.guard(broker, broker.openSession("100"))
        assertEquals("请输入有效的金额；按风险计算手数还需要单笔最大数量。",
            risk.configureBrackets(broker, "100", AccountBrackets(sizingRisk = 300_000_000)))
        assertNull(risk.configureBrackets(broker, "100", AccountBrackets(sizingRisk = 300_000_000, sizingMaxLots = 500)))
        // Ten ticks of stop cost 12.50 a contract: 300 would buy 24 contracts. The ceiling stops at 5, whatever was reviewed.
        val tight = broker.request.copy(lotsHundredths = 2_400, stopLoss = 549_775)
        assertTrue(session.placeOrder("market", tight).accepted)
        assertEquals(tight.copy(lotsHundredths = 500), broker.placed.single())
        val resting = tight.copy(kind = OrderKind.LIMIT, entryPrice = 549_000, stopLoss = 548_750)
        assertEquals("当前预估风险超过子账户设置，请重新检查订单", session.placeOrder("resting", resting.copy(lotsHundredths = 600)).message)
        assertTrue(session.placeOrder("resting-at-ceiling", resting.copy(lotsHundredths = 500)).accepted)

        // A rule saved before the ceiling existed sizes nothing, and still lets a position be reduced.
        val older = AccountRiskRule(brackets = AccountBrackets(sizingRisk = 300_000_000))
        store.write("account-risk-v1", Json.encodeToString(mapOf(broker.descriptor.id to mapOf("100" to older))))
        val restored = controller(store).guard(broker, broker.openSession("100"))
        val missing = TradeResult(false, "请先在设置中填写以损定量的单笔最大数量")
        assertEquals(missing, restored.placeOrder("market", tight))
        assertEquals(missing, restored.placeOrder("resting", resting))
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)))
        assertTrue(restored.placeOrder("scale-out", sell).accepted)
        assertEquals(3, broker.placed.size)
    }

    @Test fun anEditMayTightenButNotLoosenPastTheCashAmounts() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val session = risk.guard(broker, broker.openSession("100"))
        // Long one from 5500 with the stop 80 ticks under it and the target 160 above; one more contract waits at 5490
        // with the same distances. Each loses 100 at its stop and makes 200 at its target.
        val entry = riskOrder("entry", Side.BUY, OrderKind.LIMIT, 100, 549_000, stop = 547_000, target = 553_000)
        broker.hold("100", listOf(riskPosition(Side.BUY, 100, stop = 548_000, target = 554_000)), listOf(entry))
        assertTrue(session.modifyProtection("free", "held", 1, 540_000, null).accepted, "without a rule nothing is checked")
        assertNull(risk.configureBrackets(broker, "100", AccountBrackets(loss = 100_000_000, profit = 200_000_000)))
        val loss = "修改后的预估亏损超出子账户风险设置；可以收紧止损或减少数量"
        val profit = "修改后的预估利润低于子账户设置；止盈只能设得更远"
        fun refused(reason: String, result: TradeResult) = assertEquals(TradeResult(false, reason), result)

        refused(loss, session.modifyProtection("loosen", "held", 1, 547_975, 554_000))
        refused("该子账户设有风险金额，止损不能移除", session.modifyProtection("remove", "held", 1, null, 554_000))
        refused(profit, session.modifyProtection("nearer", "held", 1, 548_000, 553_975))
        refused("该子账户设有利润金额，止盈不能移除", session.modifyProtection("no-target", "held", 1, 548_000, null))
        assertTrue(session.modifyProtection("tighten", "held", 1, 549_000, 555_000).accepted)
        // The entry dragged away from its stop, its stop dragged away from it, and a second contract on the same stop.
        refused(loss, session.modifyOrder("up", "entry", 1, 549_025))
        refused(loss, session.modifyOrderProtection("loosen", "entry", 1, 546_975, 553_000))
        refused(loss, session.changeOrder("more", "entry", 1, 549_000, 200))
        refused("该子账户设有风险金额，止损不能移除", session.modifyOrderProtection("remove", "entry", 1, null, 553_000))
        refused(profit, session.modifyOrderProtection("nearer", "entry", 1, 547_000, 552_975))
        assertTrue(session.modifyOrder("down", "entry", 1, 548_500).accepted, "nearer its stop and further from its target")
        assertTrue(session.modifyOrderProtection("tighten", "entry", 1, 548_000, 554_000).accepted)
        assertTrue(session.changeOrder("same", "entry", 1, 549_000, 100).accepted)

        // Two contracts on a stop that already loses 200, and no target: older than the rule. They may come closer and go
        // no further than they are. A first target has to make the profit amount, which two contracts do 80 ticks up.
        broker.hold("100", listOf(riskPosition(Side.BUY, 200, stop = 548_000)),
            // An order placed by hand as the stop of that position carries none itself: the amounts do not reach it.
            listOf(riskOrder("own-stop", Side.SELL, OrderKind.STOP, 200, 547_000)))
        refused(loss, session.modifyProtection("older-loosen", "held", 1, 547_975, null))
        refused(profit, session.modifyProtection("older-near-target", "held", 1, 548_500, 551_975))
        assertTrue(session.modifyProtection("older-tighten", "held", 1, 548_500, 552_000).accepted)
        assertTrue(session.modifyOrder("own-stop", "own-stop", 1, 546_000).accepted)

        // Under sizing the loss amount is the risk, and no target is asked for.
        val other = risk.guard(broker, broker.openSession("200"))
        assertNull(risk.configureBrackets(broker, "200", AccountBrackets(sizingRisk = 100_000_000, sizingMaxLots = 1_000)))
        broker.hold("200", orders = listOf(riskOrder("sized", Side.BUY, OrderKind.LIMIT, 200, 549_000, stop = 548_000)))
        refused(loss, other.changeOrder("more", "sized", 1, 549_000, 300))
        refused(loss, other.modifyOrder("up", "sized", 1, 549_025))
        assertTrue(other.modifyOrderProtection("target", "sized", 1, 548_000, 549_025).accepted)
        assertEquals(listOf("protect:held:540000:null", "protect:held:549000:555000", "modify:entry:548500", "protect-order:entry:548000:554000",
            "change:entry:549000:100", "protect:held:548500:552000", "modify:own-stop:546000", "protect-order:sized:548000:549025"), broker.events)
    }

    @Test fun thePnlLockNeedsABrokerThatReportsItsTradingDay() = runTest {
        // As Rithmic and the local demo: day P&L at best, and no trading day with it.
        val broker = RiskFixture(tradingDayPnl = false)
        val store = MemorySnapshotStore()
        val risk = controller(store, broker.clock)
        assertEquals("$PNL_LOCK_UNSUPPORTED。", risk.configure(broker, "100", RiskLimits(200_000_000)))
        assertEquals(AccountRiskRule(), risk.rule(broker, "100"))
        // A rule an older file left there never pauses trading, is not monitored, and can go although it says frozen.
        val left = AccountRiskRule(RiskLimits(200_000_000, action = RiskAction.FLATTEN), LocalDate.parse("2099-01-01"),
            RiskTrigger(LocalDate.parse("2026-11-11"), -250_000_000, FlattenStatus.WAITING))
        store.write("account-risk-v1", Json.encodeToString(mapOf(broker.descriptor.id to mapOf("100" to left))))
        val restored = controller(store, broker.clock)
        val session = restored.guard(broker, broker.openSession("100"))
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)))
        broker.onRefresh = { fail("no P&L is asked of a broker that cannot give its trading day") }
        val monitor = backgroundScope.launch { restored.monitor(BrokerRegistry(listOf(broker))) }
        advanceTimeBy(30_000)
        monitor.cancelAndJoin()
        assertNull(restored.restriction(broker, session))
        assertNull(restored.lockingTrigger(broker, session))
        assertFalse(restored.frozen(broker, "100"))
        assertTrue(session.placeOrder("entry", broker.request).accepted)
        assertEquals(listOf("place:100"), broker.events)
        assertNull(restored.configure(broker, "100", null))
        assertEquals(AccountRiskRule(), controller(store, broker.clock).rule(broker, "100"))
    }

    @Test fun theFreezeFollowsTheBrokerTradingDayAndNotTheDeviceClock() = runTest {
        // Monday 08:00 in Beijing is Sunday 18:00 in Chicago: the calendar there still says Sunday the 15th, while the
        // broker already trades Monday's session.
        val clock = TestClock(Instant.parse("2026-11-16T00:00:00Z"))
        val broker = RiskFixture(clock)
        val store = MemorySnapshotStore()
        val risk = controller(store, clock)
        val limits = RiskLimits(200_000_000, 300_000_000)
        assertNotNull(risk.configure(broker, "100", null, RiskFreeze.WEEK))
        assertNotNull(risk.configure(broker, "100", RiskLimits(profit = 300_000_000), RiskFreeze.WEEK))
        assertEquals("等待券商交易日数据，暂不能冻结设置。", risk.configure(broker, "100", limits, RiskFreeze.WEEK))
        assertNull(risk.rule(broker, "100").limits)
        broker.pnl("100", 0, "2026-11-16")
        assertNull(risk.configure(broker, "100", limits, RiskFreeze.WEEK))
        val restored = controller(store, clock)
        assertEquals(LocalDate.parse("2026-11-23"), restored.rule(broker, "100").frozenUntil, "the Monday after the broker's trading day")
        assertTrue(restored.frozen(broker, "100"))
        assertNotNull(restored.configure(broker, "100", null))
        assertNotNull(restored.configure(broker, "100", RiskLimits(400_000_000)))
        broker.pnl("200", 0, "2026-11-13")
        assertNull(restored.configure(broker, "200", RiskLimits(400_000_000), RiskFreeze.DAY))
        assertEquals(LocalDate.parse("2026-11-14"), restored.rule(broker, "200").frozenUntil)
        // Setting the device clock past the end changes nothing: without a fresh broker day the rule stays frozen.
        clock.time = Instant.parse("2026-12-01T00:00:00Z")
        assertTrue(restored.frozen(broker, "100"))
        assertEquals("风控设置已冻结，暂不能修改。", restored.configure(broker, "100", null))
        broker.pnl("100", 0, "2026-11-20")
        assertTrue(restored.frozen(broker, "100"), "Friday of the frozen week")
        assertNotNull(restored.configure(broker, "100", null))
        // Friday's freeze of the other child ends with the next trading day the broker reports, a Monday.
        broker.pnl("200", 0, "2026-11-16")
        assertFalse(restored.frozen(broker, "200"))
        broker.pnl("100", 0, "2026-11-23")
        assertFalse(restored.frozen(broker, "100"))
        assertNull(restored.configure(broker, "100", null))
        assertEquals(AccountRiskRule(), restored.rule(broker, "100"))
        assertEquals(200_500_000L, riskAmount("200.50"))
        assertEquals(999_999_999_990_000L, riskAmount("999999999.99"))
        for (text in listOf("0", "0.00", "-5", "100.001", "1000000000", "1.", "abc", "")) assertNull(riskAmount(text), text)
    }

    @Test fun unreadableRulesStopEntriesOnEveryAccountUntilTheyAreReset() = runTest {
        val broker = RiskFixture()
        val newer = """{"offline-orders":{"100":{"fromANewerBuild":1}}}"""
        val store = MemorySnapshotStore().apply { write("account-risk-v1", newer) }
        val risk = controller(store)
        assertTrue(risk.storageUnreadable.value)
        // An account that never had a rule: nothing says the unreadable file did not restrict it.
        val session = risk.guard(broker, broker.openSession("200"))
        broker.hold("200", listOf(riskPosition(Side.BUY, 100, stop = 548_000)))
        val unreadable = "本机风控记录无法读取，已暂停开仓和改单；仍可平仓、撤单、收紧止损或挂减仓单。"
        assertEquals(TradeResult(false, unreadable), session.placeOrder("entry", broker.request))
        assertTrue(session.placeOrder("exit", sell).accepted)
        assertTrue(session.modifyProtection("tighten", "held", 1, 549_000, null).accepted)
        assertFalse(session.modifyProtection("loosen", "held", 1, 547_000, null).accepted)
        // On a hedging account an order against the position opens another one, so no reducing order is offered there.
        val hedged = RiskFixture(hedging = true).apply { hold("100", listOf(riskPosition(Side.BUY, 100, stop = 548_000))) }
        val hedgedSession = risk.guard(hedged, hedged.openSession("100"))
        assertEquals("本机风控记录无法读取，已暂停开仓和改单；仍可平仓、撤单或收紧止损。", hedgedSession.placeOrder("against", sell).message)
        assertTrue(hedgedSession.modifyProtection("tighten", "held", 1, 549_000, null).accepted)
        assertTrue(hedged.events.none { it.startsWith("place") })
        assertNotNull(risk.configure(broker, "200", RiskLimits(200_000_000)))
        assertNotNull(risk.configureOrders(broker, "200", AccountOrderLimits(500)))
        assertNotNull(risk.configureBrackets(broker, "200", AccountBrackets(loss = 100_000_000)))
        assertEquals(newer, store.read("account-risk-v1"), "nothing overwrites the file before the user asks for it")

        assertTrue(risk.resetUnreadable())
        assertFalse(risk.storageUnreadable.value)
        assertEquals(newer, store.read("account-risk-v1-unreadable"))
        assertEquals("{}", store.read("account-risk-v1"))
        assertTrue(session.placeOrder("entry", broker.request).accepted)
        assertNull(risk.configureOrders(broker, "200", AccountOrderLimits(500)))
        val restarted = controller(store)
        assertFalse(restarted.storageUnreadable.value)
        assertEquals(500L, restarted.rule(broker, "200").orderLimits.maxLots)
        assertTrue(restarted.resetUnreadable(), "nothing to reset")
        assertEquals(500L, restarted.rule(broker, "200").orderLimits.maxLots)
    }

    @Test fun profitableFlattenCancelsEntriesBeforeClosingAndDoesNotResendOnRestart() = runTest {
        val broker = RiskFixture()
        val store = MemorySnapshotStore()
        val risk = controller(store, broker.clock)
        broker.pnl("100", 300_000_000)
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)), listOf(riskOrder("entry", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, 300_000_000, RiskAction.FLATTEN)))
        val registry = BrokerRegistry(listOf(broker))
        val monitor = backgroundScope.launch { risk.monitor(registry) }
        withTimeout(5_000) { risk.rules.first { trigger(risk, broker, "100")?.flattenStatus == FlattenStatus.FLAT } }
        monitor.cancelAndJoin()
        assertEquals(listOf("cancel:entry", "close:held"), broker.events)
        // The state is saved by name, not as the sentence the screen shows for it.
        assertTrue(""""flattenStatus":"FLAT"""" in store.read("account-risk-v1")!!, store.read("account-risk-v1"))
        val restarted = controller(store, broker.clock)
        assertEquals(FlattenStatus.FLAT, trigger(restarted, broker, "100")?.flattenStatus)
        val restartedMonitor = backgroundScope.launch { restarted.monitor(registry) }
        advanceTimeBy(30_000)
        restartedMonitor.cancelAndJoin()
        assertEquals(listOf("cancel:entry", "close:held"), broker.events)
    }

    @Test fun monitorRefreshesInactiveChildAndClearsOldLatchOnConfirmedNextDay() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val refreshed = mutableListOf<String>()
        broker.onRefresh = { id -> refreshed += id; broker.pnl(id, -210_000_000) }
        assertNull(risk.configure(broker, "200", RiskLimits(200_000_000)))
        backgroundScope.launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        withTimeout(5_000) { risk.rules.first { trigger(risk, broker, "200") != null } }
        assertEquals(listOf("200"), refreshed)
        assertNull(risk.configure(broker, "200", null))
        broker.pnl("200", 0, "2026-11-12")
        withTimeout(5_000) { risk.rules.first { trigger(risk, broker, "200") == null } }
        broker.snapshots.getValue("200").let { it.value = it.value.copy(dailyPnl = null) }
        assertNull(risk.restriction(broker, broker.openSession("200")))
    }

    @Test fun bulkFlattenScopesReviewedChildrenAndCancelsUnfilledAndOrphanedOrders() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val registry = BrokerRegistry(listOf(broker))
        for (id in broker.snapshots.keys) broker.hold(id, listOf(riskPosition(Side.BUY, 100, "p$id")), listOf(
            riskOrder("entry$id", Side.BUY, OrderKind.LIMIT, 100, 549_000),
            riskOrder("stop$id", Side.SELL, OrderKind.STOP, 100, 548_000, OrderRole.STOP_LOSS)))
        val one = risk.flattenAccounts(registry, listOf(FlattenTarget(broker, broker.accounts.first())))
        assertEquals(listOf(FlattenOutcome(FlattenTarget(broker, broker.accounts.first()), true)), one)
        assertEquals(listOf("cancel:entry100", "close:p100", "cancel:stop100"), broker.events)
        assertEquals(1, broker.snapshots.getValue("200").value.positions.size)
        val all = risk.flattenAccounts(registry, broker.accounts.map { FlattenTarget(broker, it) })
        assertTrue(all.all { it.confirmed })
        assertEquals(listOf("cancel:entry100", "close:p100", "cancel:stop100", "cancel:entry200", "close:p200", "cancel:stop200"), broker.events)
    }

    @Test fun flattenKeepsAStopTheUserPlacedUntilTheCloseIsConfirmed() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val registry = BrokerRegistry(listOf(broker))
        val target = listOf(FlattenTarget(broker, broker.accounts.first()))
        // Two symbols, each with a stop placed by hand after the entry, and one order that would add to a position.
        broker.hold("100", listOf(riskPosition(Side.BUY, 100), riskPosition(Side.SELL, 100, "sold", "OTHRZ6")), listOf(
            riskOrder("stop", Side.SELL, OrderKind.STOP, 100, 548_000),
            riskOrder("add", Side.BUY, OrderKind.LIMIT, 100, 549_000),
            riskOrder("cover", Side.BUY, OrderKind.STOP, 100, 552_000, symbol = "OTHRZ6")))
        broker.onClose = { TradeResult(false, "market closed") }
        assertEquals(listOf(FlattenOutcome(target.single(), false)), risk.flattenAccounts(registry, target))
        assertEquals(listOf("cancel:add", "close:held"), broker.events)
        assertEquals(listOf("stop", "cover"), broker.orderIds("100"), "a close that fails leaves both positions with their stops")

        broker.events.clear()
        broker.onClose = null
        assertTrue(risk.flattenAccounts(registry, target).single().confirmed)
        // Each stop goes as soon as its own position is confirmed closed, and not before.
        assertEquals(listOf("close:held", "cancel:stop", "close:sold", "cancel:cover"), broker.events)
        assertEquals(AccountSnapshot("100", 25_000_000_000L), broker.snapshots.getValue("100").value)
    }

    @Test fun anExitThatFillsWhileItsPositionClosesIsClosedInAnotherPass() = runTest {
        val broker = RiskFixture()
        val risk = controller()
        val state = broker.snapshots.getValue("100")
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)), listOf(riskOrder("stop", Side.SELL, OrderKind.STOP, 100, 548_000)))
        // The stop fills in the moment its position closes: the account is short, and the cancel comes too late.
        broker.onCancel = {
            state.value = state.value.copy(orders = emptyList(), positions = listOf(riskPosition(Side.SELL, 100, "reversed")))
            TradeResult(false, "already filled")
        }
        val registry = BrokerRegistry(listOf(broker))
        val target = listOf(FlattenTarget(broker, broker.accounts.first()))
        assertTrue(risk.flattenAccounts(registry, target).single().confirmed)
        assertEquals(listOf("close:held", "cancel:stop", "close:reversed"), broker.events)
        // A cancel that is refused while the order stays is still a failure, after the usual wait.
        broker.events.clear()
        broker.hold("100", orders = listOf(riskOrder("stuck", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        broker.onCancel = { TradeResult(false, "refused") }
        assertFalse(risk.flattenAccounts(registry, target).single().confirmed)
        assertEquals(listOf("cancel:stuck"), broker.events)

        // A net account has one position for a contract. When the stop fills together with the close, the account is
        // short under the id it was long under, and no snapshot in between shows it flat.
        fun reverse(): TradeResult {
            state.value = state.value.copy(orders = emptyList(),
                positions = state.value.positions.map { it.copy(side = if (it.side == Side.BUY) Side.SELL else Side.BUY) })
            return TradeResult(true, "closed")
        }
        broker.events.clear()
        broker.onCancel = null
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)), listOf(riskOrder("stop", Side.SELL, OrderKind.STOP, 100, 548_000)))
        broker.onClose = { broker.onClose = null; reverse() }
        assertTrue(risk.flattenAccounts(registry, target).single().confirmed)
        assertEquals(listOf("close:held", "close:held"), broker.events)
        assertEquals(AccountSnapshot("100", 25_000_000_000L), state.value)
        // A position that comes back every time is given up after the third pass.
        broker.events.clear()
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)))
        broker.onClose = { reverse() }
        assertFalse(risk.flattenAccounts(registry, target).single().confirmed)
        assertEquals(listOf("close:held", "close:held", "close:held"), broker.events)
    }

    @Test fun failedAccountDoesNotPreventFlatteningAnotherReviewedChild() = runTest {
        val broker = RiskFixture().apply { rejectCancel = true }
        val risk = controller()
        broker.hold("100", orders = listOf(PendingOrder("uncertain", broker.request, 0)))
        broker.hold("200", listOf(riskPosition(Side.BUY, 100, "other")))
        val outcomes = risk.flattenAccounts(BrokerRegistry(listOf(broker)), broker.accounts.map { FlattenTarget(broker, it) })
        assertEquals(listOf(false, true), outcomes.map { it.confirmed })
        assertEquals(listOf("cancel:uncertain", "close:other"), broker.events)
        val unauthorized = risk.flattenAccounts(BrokerRegistry(emptyList()), listOf(FlattenTarget(broker, broker.accounts.first())))
        assertEquals(false to "账户已断开、切换或为只读。", unauthorized.single().let { it.confirmed to it.note })
        assertEquals(2, broker.events.size)
    }

    @Test fun bulkFlattenLeavesOutViewOnlyAccountsAndLoginsThatCannotTrade() {
        val trading = RiskFixture(children = listOf(AccountInfo("100", "Trading"), AccountInfo("101", "View only", readOnly = true)))
        val offline = RiskFixture(login = "offline-login")
        offline.source.market.value = offline.source.market.value.copy(connected = false)
        val syncing = RiskFixture(login = "syncing-login", children = listOf(AccountInfo("300", "Not ready")))
        syncing.source.market.value = syncing.source.market.value.copy(tradingReady = false)
        val (targets, skipped) = bulkFlattenTargets(listOf(trading, offline, syncing))
        assertEquals(listOf("offline-orders" to "100"), targets.map { it.key })
        assertEquals(listOf(
            ("offline-orders" to "101") to FlattenSkip.READ_ONLY,
            ("offline-login" to "100") to FlattenSkip.DISCONNECTED,
            ("offline-login" to "200") to FlattenSkip.DISCONNECTED,
            ("syncing-login" to "300") to FlattenSkip.DISCONNECTED,
        ), skipped.map { it.first.key to it.second })
    }

    @Test fun unknownCancelLeavesLockAndDoesNotSendCloseOrRetry() = runTest {
        val broker = RiskFixture().apply { rejectCancel = true }
        val risk = controller(clock = broker.clock)
        broker.pnl("100", -250_000_000)
        val raw = broker.openSession("100")
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)), listOf(riskOrder("entry", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, action = RiskAction.FLATTEN)))
        backgroundScope.launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        withTimeout(5_000) { risk.rules.first { trigger(risk, broker, "100")?.flattenStatus == FlattenStatus.FAILED } }
        // Many more rounds of the monitor: a flatten that failed is never tried again.
        advanceTimeBy(30_000)
        assertFalse(risk.guard(broker, raw).placeOrder("blocked", broker.request).accepted)
        assertEquals(listOf("cancel:entry"), broker.events)
    }

    @Test fun anAutomaticFlattenThatFailsLeavesTheStopWorkingAndTheAccountStillTakesExits() = runTest {
        val broker = RiskFixture()
        val risk = controller(clock = broker.clock)
        val session = risk.guard(broker, broker.openSession("100"))
        // Long two, protected by a stop placed by hand after the entry. The limit is reached and the close is refused.
        broker.hold("100", listOf(riskPosition(Side.BUY, 200)), listOf(riskOrder("stop", Side.SELL, OrderKind.STOP, 200, 548_000)))
        broker.onClose = { TradeResult(false, "market closed") }
        broker.pnl("100", -250_000_000)
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, action = RiskAction.FLATTEN)))
        backgroundScope.launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        withTimeout(5_000) { risk.rules.first { trigger(risk, broker, "100")?.flattenStatus == FlattenStatus.FAILED } }
        advanceTimeBy(30_000)
        assertEquals(listOf("close:held"), broker.events)
        assertEquals(listOf("stop"), broker.orderIds("100"), "the position keeps its stop")
        // The lock holds, and what protects or reduces the position still goes through.
        assertFalse(session.placeOrder("add", broker.request).accepted)
        assertTrue(session.modifyOrder("tighten", "stop", 1, 549_000).accepted)
        assertTrue(session.placeOrder("target", exitTarget).accepted)
        assertEquals(listOf("close:held", "modify:stop:549000", "place:100"), broker.events)
    }

    @Test fun aTriggerThatCannotBeSavedLocksThisRunAndSendsNothingByItself() = runTest {
        val broker = RiskFixture()
        var full = false
        val saved = MemorySnapshotStore()
        val store = object : SnapshotStore {
            override fun read(key: String) = saved.read(key)
            override fun write(key: String, value: String) = if (full) error("disk full") else saved.write(key, value)
        }
        val risk = controller(store, broker.clock)
        broker.hold("100", listOf(riskPosition(Side.BUY, 100)))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, action = RiskAction.FLATTEN)))
        full = true
        broker.pnl("100", -250_000_000)
        backgroundScope.launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        advanceTimeBy(30_000)
        assertEquals(FlattenStatus.UNSAVED, trigger(risk, broker, "100")?.flattenStatus)
        assertFalse(risk.guard(broker, broker.openSession("100")).placeOrder("entry", broker.request).accepted)
        assertTrue(broker.events.isEmpty(), "no order is sent for a lock that is not on disk")
        assertNull(controller(saved, broker.clock).rule(broker, "100").trigger)
    }
}
