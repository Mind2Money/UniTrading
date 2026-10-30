package com.unitrading.demo

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PendingModificationTest {
    private fun broker() = DemoBroker(DemoConfigs.all.first(), seedTrades = false)

    @Test fun allFourPendingKindsCanMoveAndKeepProtection() = runTest {
        for (kind in listOf(OrderKind.LIMIT, OrderKind.STOP)) for (side in Side.entries) {
            val b = broker()
            val s = b.openSession(b.accounts.first().id)
            val spec = b.instruments.first()
            val q = b.market.value.quotes.getValue(spec.symbol)
            val above = (side == Side.BUY) == (kind == OrderKind.STOP)
            val entry = (if (side == Side.BUY) q.ask else q.bid) + if (above) 500 else -500
            val sl = entry + if (side == Side.BUY) -400 else 400
            val tp = entry + if (side == Side.BUY) 800 else -800
            val request = OrderRequest(spec.symbol, side, kind, 10, entry, sl, tp)
            assertTrue(s.placeOrder("place", request).accepted)
            val old = s.state.value.orders.single()
            val price = entry + if (above) 100 else -100
            val before = s.state.value.balanceMicros
            assertTrue(s.previewOrderModification(old.id, price).valid)
            assertTrue(s.modifyOrder("move", old.id, old.version, price).accepted)
            val updated = s.state.value.orders.single()
            assertEquals(old.id, updated.id)
            assertEquals(2, updated.version)
            assertEquals(request.copy(entryPrice = price), updated.request)
            assertEquals(before, s.state.value.balanceMicros)
            assertEquals(0, s.state.value.positions.size)
            assertEquals(b.exportState(), DemoBroker(DemoConfigs.all.first(), b.exportState()).exportState())
        }
    }

    @Test fun invalidVersionsPricesAndOtherAccountsCannotModify() = runTest {
        val b = broker()
        val s = b.openSession(b.accounts.first().id)
        val q = b.market.value.quotes.getValue("EURUSD")
        assertTrue(s.placeOrder("place", OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 10, q.ask - 500)).accepted)
        val old = s.state.value.orders.single()
        assertFalse(s.modifyOrder("bad-price", old.id, old.version, q.ask + 100).accepted)
        assertFalse(s.modifyOrder("stale", old.id, 0, q.ask - 600).accepted)
        assertFalse(b.openSession(b.accounts[1].id).modifyOrder("foreign", old.id, old.version, q.ask - 600).accepted)
        assertEquals(old, s.state.value.orders.single())
        val result = s.modifyOrder("move", old.id, old.version, q.ask - 600)
        assertTrue(result.accepted)
        assertEquals(result, s.modifyOrder("move", old.id, old.version, q.ask - 600))
        assertEquals(2, s.state.value.orders.single().version)
        assertFalse(s.modifyOrder("move", old.id, old.version, q.ask - 700).accepted)
        assertFalse(s.modifyOrder("old-version", old.id, old.version, q.ask - 700).accepted)
    }

    @Test fun filledOrCancelledOrdersRejectLateDrag() = runTest {
        val b = broker()
        val s = b.openSession(b.accounts.first().id)
        val q = b.market.value.quotes.getValue("EURUSD")
        s.placeOrder("place", OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 10, q.ask - 500))
        val old = s.state.value.orders.single()
        b.setQuote("EURUSD", q.ask - 520, q.ask - 500, q.timeMs + 1_000)
        assertTrue(s.state.value.orders.isEmpty())
        assertFalse(s.modifyOrder("late", old.id, old.version, q.ask - 600).accepted)
        assertFalse(s.previewOrderModification(old.id, q.ask - 600).valid)
        val current = b.market.value.quotes.getValue("EURUSD")
        s.placeOrder("place2", OrderRequest("EURUSD", Side.BUY, OrderKind.STOP, 10, current.ask + 500))
        val cancelled = s.state.value.orders.single()
        s.cancelOrder("cancel", cancelled.id)
        assertFalse(s.modifyOrder("after-cancel", cancelled.id, cancelled.version, current.ask + 600).accepted)
    }

    @Test fun pendingStopAndTargetMoveBeforeTheFillAndProtectTheFilledPosition() = runTest {
        val b = broker()
        val s = b.openSession(b.accounts.first().id)
        val q = b.market.value.quotes.getValue("EURUSD")
        val entry = q.ask - 500
        assertTrue(s.placeOrder("place", OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 10, entry, entry - 400, entry + 800)).accepted)
        val old = s.state.value.orders.single()
        val preview = s.previewOrderProtection(old.id, entry - 300, entry + 900)
        assertTrue(preview.valid, preview.errors.toString())
        assertEquals(s.preview(old.request.copy(stopLoss = entry - 300, takeProfit = entry + 900)).stopLossPnlMicros, preview.stopLossPnlMicros)
        assertFalse(s.previewOrderProtection(old.id, entry + 100, entry + 900).valid, "A stop above a buy entry is rejected")
        assertFalse(s.modifyOrderProtection("wrong-side", old.id, old.version, entry + 100, entry + 900).accepted)
        assertFalse(s.modifyOrderProtection("stale", old.id, 0, entry - 300, entry + 900).accepted)
        assertFalse(b.openSession(b.accounts[1].id).modifyOrderProtection("foreign", old.id, old.version, entry - 300, entry + 900).accepted)
        assertEquals(old, s.state.value.orders.single())
        val result = s.modifyOrderProtection("move", old.id, old.version, entry - 300, entry + 900)
        assertTrue(result.accepted, result.message)
        assertEquals(result, s.modifyOrderProtection("move", old.id, old.version, entry - 300, entry + 900))
        val updated = s.state.value.orders.single()
        assertEquals(old.request.copy(stopLoss = entry - 300, takeProfit = entry + 900), updated.request)
        assertEquals(2, updated.version)
        assertTrue(s.modifyOrderProtection("remove-target", updated.id, updated.version, entry - 300, null).accepted)
        assertNull(s.state.value.orders.single().request.takeProfit)
        assertEquals(b.exportState(), DemoBroker(DemoConfigs.all.first(), b.exportState()).exportState())
        b.setQuote("EURUSD", entry - 12, entry, q.timeMs + 1_000)
        assertEquals(entry - 300, s.state.value.positions.single().stopLoss)
        assertNull(s.state.value.positions.single().takeProfit)
        assertFalse(s.modifyOrderProtection("after-fill", updated.id, 3, entry - 200, null).accepted)
    }

    @Test fun existingOrderCanBeEditedAtCapacity() = runTest {
        val b = broker()
        val s = b.openSession(b.accounts.first().id)
        val q = b.market.value.quotes.getValue("EURUSD")
        repeat(200) { index -> assertTrue(s.placeOrder("p-$index", OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 1, q.ask - 500)).accepted) }
        val order = s.state.value.orders.first()
        assertTrue(s.previewOrderModification(order.id, q.ask - 600).valid)
        assertTrue(s.modifyOrder("move", order.id, order.version, q.ask - 600).accepted)
        assertEquals(200, s.state.value.orders.size)
    }
}
