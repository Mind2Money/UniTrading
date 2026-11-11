package com.unitrading.ui

import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class AccountRiskTest {
    private class TestClock : Clock {
        var time = Instant.parse("2026-11-11T16:00:00Z")
        override fun now() = time
    }

    private class Fixture(val clock: TestClock, val source: OfflineOrderBroker = OfflineOrderBroker()) : BrokerPlugin by source {
        private val first = source.openSession(source.accounts.single().id)
        override val accounts = listOf(first.account, AccountInfo("200", "Other child"))
        override val catalog = MutableStateFlow(BrokerCatalog(source.instruments, accounts))
        val request = OrderRequest(source.spec.symbol, Side.BUY, OrderKind.MARKET, 100)
        val events = mutableListOf<String>()
        var rejectCancel = false
        var onRefresh: (String) -> Unit = {}
        override suspend fun refreshDailyPnl(accountId: String) = onRefresh(accountId)
        val snapshots = accounts.associate { it.id to MutableStateFlow(AccountSnapshot(it.id, 25_000_000_000L)) }
        val sessions = accounts.associate { child ->
            child.id to object : AccountSession by first {
                override val account = child
                override val state = snapshots.getValue(child.id)
                override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
                    events += "place:${child.id}"
                    return TradeResult(true, "accepted")
                }
                override suspend fun cancelOrder(requestId: String, orderId: String): TradeResult {
                    events += "cancel:$orderId"
                    if (rejectCancel) return TradeResult(false, "unknown", outcome = CommandOutcome.UNKNOWN)
                    state.value = state.value.copy(orders = state.value.orders.filterNot { it.id == orderId })
                    return TradeResult(true, "cancelled")
                }
                override suspend fun closePosition(requestId: String, positionId: String): TradeResult {
                    events += "close:$positionId"
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
    }

    @Test fun thresholdLatchesBeforeOrderAndSurvivesRestartUntilNewBrokerDay() = runBlocking {
        val clock = TestClock()
        val broker = Fixture(clock)
        val store = MemorySnapshotStore()
        val risk = AccountRiskController(store, clock)
        val raw = broker.openSession("100")
        val session = risk.guard(broker, raw)
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000L)))
        assertFalse(session.placeOrder("missing", broker.request).accepted)
        broker.pnl("100", -200_000_000)
        assertFalse(session.placeOrder("loss", broker.request).accepted)
        assertTrue(risk.guard(broker, broker.openSession("200")).placeOrder("other", broker.request).accepted)
        broker.pnl("100", 0)
        val restarted = AccountRiskController(store, clock).guard(broker, raw)
        assertFalse(restarted.placeOrder("rebound", broker.request).accepted)
        assertTrue(restarted.closePosition("manual", "held").accepted)
        broker.pnl("100", 0, "2026-11-12")
        assertTrue(restarted.placeOrder("next", broker.request).accepted)
        clock.time += kotlin.time.Duration.parse("3m")
        assertFalse(restarted.placeOrder("stale", broker.request).accepted)
        assertEquals(listOf("place:200", "close:held", "place:100"), broker.events)
    }

    @Test fun weeklyFreezeRequiresLossAndRejectsEditsAndDisablingAcrossRestarts() = runBlocking {
        val clock = TestClock()
        val broker = Fixture(clock)
        val store = MemorySnapshotStore()
        val risk = AccountRiskController(store, clock)
        assertNotNull(risk.configure(broker, "100", null, RiskFreeze.WEEK))
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, 300_000_000), RiskFreeze.WEEK))
        val restored = AccountRiskController(store, clock)
        assertEquals(LocalDate.parse("2026-11-16"), restored.rule(broker, "100").frozenUntil)
        assertNotNull(restored.configure(broker, "100", null))
        assertNotNull(restored.configure(broker, "100", RiskLimits(400_000_000)))
        assertNull(restored.configure(broker, "200", RiskLimits(400_000_000), RiskFreeze.DAY))
        clock.time = Instant.parse("2026-11-16T06:00:00Z")
        assertNull(restored.configure(broker, "100", null))
        assertEquals(200_500_000L, riskAmount("200.50"))
        assertNull(riskAmount("0"))
        assertNull(riskAmount("100.001"))
    }

    @Test fun profitableFlattenCancelsEntriesBeforeClosingAndDoesNotResendOnRestart() = runBlocking {
        val clock = TestClock()
        val broker = Fixture(clock)
        val store = MemorySnapshotStore()
        val risk = AccountRiskController(store, clock)
        broker.pnl("100", 300_000_000)
        val state = broker.snapshots.getValue("100")
        state.value = state.value.copy(
            positions = listOf(Position("held", broker.request.symbol, Side.BUY, 100, 550000, 0, marginMicros = 0)),
            orders = listOf(PendingOrder("entry", broker.request.copy(kind = OrderKind.LIMIT, entryPrice = 549000), 0)),
        )
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, 300_000_000, RiskAction.FLATTEN)))
        val registry = BrokerRegistry(listOf(broker))
        val monitor = launch { risk.monitor(registry) }
        try {
            withTimeout(5_000) {
                risk.rules.first { it[broker.descriptor.id]?.get("100")?.trigger?.flattenStatus?.contains("已确认") == true }
            }
        } finally { monitor.cancelAndJoin() }
        assertEquals(listOf("cancel:entry", "close:held"), broker.events)
        val restarted = AccountRiskController(store, clock)
        val restartedMonitor = launch { restarted.monitor(registry) }
        delay(1_100)
        restartedMonitor.cancelAndJoin()
        assertEquals(listOf("cancel:entry", "close:held"), broker.events)
    }

    @Test fun monitorRefreshesInactiveChildAndClearsOldLatchOnConfirmedNextDay() = runBlocking {
        val clock = TestClock()
        val broker = Fixture(clock)
        val risk = AccountRiskController(MemorySnapshotStore(), clock)
        val refreshed = mutableListOf<String>()
        broker.onRefresh = { id -> refreshed += id; broker.pnl(id, -210_000_000) }
        assertNull(risk.configure(broker, "200", RiskLimits(200_000_000)))
        val monitor = launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        try {
            withTimeout(5_000) { risk.rules.first { it[broker.descriptor.id]?.get("200")?.trigger != null } }
            assertEquals(listOf("200"), refreshed)
            assertNull(risk.configure(broker, "200", null))
            broker.pnl("200", 0, "2026-11-12")
            withTimeout(5_000) { risk.rules.first { it[broker.descriptor.id]?.get("200")?.trigger == null } }
            broker.snapshots.getValue("200").let { it.value = it.value.copy(dailyPnl = null) }
            assertNull(risk.restriction(broker, broker.openSession("200")))
        } finally { monitor.cancelAndJoin() }
    }

    @Test fun orderLimitsPersistAndCountGrossPositionsAndRemainingEntryQuantity() = runBlocking {
        val broker = Fixture(TestClock())
        val store = MemorySnapshotStore()
        val risk = AccountRiskController(store)
        assertNull(risk.configureOrders(broker, "100", AccountOrderLimits(500, setOf("MES", "CLZ6"))))
        val restored = AccountRiskController(store)
        val account = broker.snapshots.getValue("100")
        val open = broker.request.copy(kind = OrderKind.LIMIT, lotsHundredths = 200, entryPrice = 549000)
        account.value = account.value.copy(
            positions = listOf(Position("held", broker.request.symbol, Side.SELL, 300, 550000, 0, marginMicros = 0)),
            orders = listOf(PendingOrder("entry", open, 0, filledLotsHundredths = 100),
                PendingOrder("exit", open.copy(lotsHundredths = 300), 0, role = OrderRole.STOP_LOSS)),
        )
        val session = restored.guard(broker, broker.openSession("100"))
        assertTrue(session.placeOrder("equal", broker.request).accepted)
        assertFalse(session.placeOrder("cap", broker.request.copy(lotsHundredths = 101)).accepted)
        assertFalse(session.placeOrder("root", broker.request.copy(symbol = "MESZ6")).accepted)
        assertFalse(session.placeOrder("continuous", broker.request.copy(sourceSymbol = "@MES")).accepted)
        assertFalse(session.placeOrder("exact", broker.request.copy(symbol = "CLZ6")).accepted)
        assertTrue(session.placeOrder("otherMonth", broker.request.copy(symbol = "CLH7")).accepted)
        assertTrue(restored.guard(broker, broker.openSession("200")).placeOrder("otherChild", broker.request.copy(symbol = "MESZ6")).accepted)
        assertTrue(session.cancelOrder("reduce", "entry").accepted)
        assertTrue(session.closePosition("close", "held").accepted)
        assertEquals(listOf("place:100", "place:100", "place:200", "cancel:entry", "close:held"), broker.events)
    }

    @Test fun bulkFlattenScopesReviewedChildrenAndCancelsUnfilledAndOrphanedOrders() = runBlocking {
        val broker = Fixture(TestClock())
        val risk = AccountRiskController(MemorySnapshotStore())
        val registry = BrokerRegistry(listOf(broker))
        for ((id, state) in broker.snapshots) {
            state.value = state.value.copy(
                positions = listOf(Position("p$id", broker.request.symbol, Side.BUY, 100, 550000, 0, marginMicros = 0)),
                orders = listOf(PendingOrder("entry$id", broker.request.copy(kind = OrderKind.LIMIT, entryPrice = 549000), 0),
                    PendingOrder("stop$id", broker.request.copy(kind = OrderKind.STOP, entryPrice = 548000), 0, role = OrderRole.STOP_LOSS)),
            )
        }
        val one = risk.flattenAccounts(registry, listOf(FlattenTarget(broker, broker.accounts.first())))
        assertTrue(one.single().confirmed)
        assertEquals(listOf("cancel:entry100", "close:p100", "cancel:stop100"), broker.events)
        assertEquals(1, broker.snapshots.getValue("200").value.positions.size)
        val all = risk.flattenAccounts(registry, broker.accounts.map { FlattenTarget(broker, it) })
        assertTrue(all.all { it.confirmed })
        assertEquals(listOf("cancel:entry100", "close:p100", "cancel:stop100", "cancel:entry200", "close:p200", "cancel:stop200"), broker.events)
    }

    @Test fun failedAccountDoesNotPreventFlatteningAnotherReviewedChild() = runBlocking {
        val broker = Fixture(TestClock()).apply { rejectCancel = true }
        val risk = AccountRiskController(MemorySnapshotStore())
        val first = broker.snapshots.getValue("100")
        first.value = first.value.copy(orders = listOf(PendingOrder("uncertain", broker.request, 0)))
        val second = broker.snapshots.getValue("200")
        second.value = second.value.copy(positions = listOf(Position("other", broker.request.symbol, Side.BUY, 100, 550000, 0, marginMicros = 0)))
        val outcomes = risk.flattenAccounts(BrokerRegistry(listOf(broker)), broker.accounts.map { FlattenTarget(broker, it) })
        assertEquals(listOf(false, true), outcomes.map { it.confirmed })
        assertEquals(listOf("cancel:uncertain", "close:other"), broker.events)
        val unauthorized = risk.flattenAccounts(BrokerRegistry(emptyList()), listOf(FlattenTarget(broker, broker.accounts.first())))
        assertFalse(unauthorized.single().confirmed)
        assertEquals(2, broker.events.size)
    }

    @Test fun unknownCancelLeavesLockAndDoesNotSendCloseOrRetry() = runBlocking {
        val clock = TestClock()
        val broker = Fixture(clock).apply { rejectCancel = true }
        val risk = AccountRiskController(MemorySnapshotStore(), clock)
        broker.pnl("100", -250_000_000)
        val raw = broker.openSession("100")
        val state = broker.snapshots.getValue("100")
        state.value = state.value.copy(
            positions = listOf(Position("held", broker.request.symbol, Side.BUY, 100, 550000, 0, marginMicros = 0)),
            orders = listOf(PendingOrder("entry", broker.request.copy(kind = OrderKind.LIMIT, entryPrice = 549000), 0)),
        )
        assertNull(risk.configure(broker, "100", RiskLimits(200_000_000, action = RiskAction.FLATTEN)))
        val monitor = launch { risk.monitor(BrokerRegistry(listOf(broker))) }
        try {
            withTimeout(5_000) {
                risk.rules.first { it[broker.descriptor.id]?.get("100")?.trigger?.flattenStatus?.startsWith("未确认全部") == true }
            }
            delay(1_100)
            assertFalse(risk.guard(broker, raw).placeOrder("blocked", broker.request).accepted)
        } finally { monitor.cancelAndJoin() }
        assertEquals(listOf("cancel:entry"), broker.events)
    }
}
