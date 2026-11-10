package com.unitrading.demo

import com.unitrading.broker.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class DemoBrokerTest {
    @Test
    fun longAndShortUseExecutableSidesAndBothFees() = runTest {
        for (side in Side.entries) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val before = session.state.value
            val request = order(side)
            val preview = session.preview(request)
            assertTrue(preview.valid)
            assertEquals(4_000_000L, preview.feesMicros) // Round trip; entry is charged now.
            val result = session.placeOrder("open", request)
            assertTrue(result.accepted, result.message)
            val position = session.state.value.positions.single()
            assertEquals(if (side == Side.BUY) 10_010L else 10_000L, position.entryPrice)
            assertEquals(before.balanceMicros - 2_000_000, session.state.value.balanceMicros)
            assertEquals(-10_000_000L, session.state.value.floatingPnlMicros)
            assertEquals(before.balanceMicros - 12_000_000, session.state.value.equityMicros)
            assertEquals(preview.marginMicros, position.marginMicros)
            assertEquals(session.state.value.equityMicros - position.marginMicros, session.state.value.freeMarginMicros)
            assertTrue(session.closePosition("close", position.id).accepted)
            val closed = session.state.value.trades.single()
            assertEquals(if (side == Side.BUY) 10_000L else 10_010L, closed.exitPrice)
            assertEquals(-14_000_000L, closed.netPnlMicros)
            assertEquals(before.balanceMicros - 14_000_000, session.state.value.balanceMicros)
            assertEquals(0L, session.state.value.marginMicros)
            assertEquals(session.state.value.balanceMicros, session.state.value.equityMicros)
        }
    }

    @Test
    fun positivePnlAndFractionalLotsRemainExact() = runTest {
        for (side in Side.entries) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val result = session.placeOrder("open", order(side, lots = 5))
            assertTrue(result.accepted)
            if (side == Side.BUY) feed(broker, 10_100, 10_110) else feed(broker, 9_900, 9_910)
            assertEquals(4_500_000L, session.state.value.floatingPnlMicros)
            assertTrue(session.closePosition("close", result.entityId!!).accepted)
            assertEquals(4_300_000L, session.state.value.trades.single().netPnlMicros)
            assertEquals(TEST_CONFIG.startingBalanceMicros + 4_300_000, session.state.value.balanceMicros)
        }
        val config = TEST_CONFIG.copy(instruments = TEST_CONFIG.instruments.map {
            it.copy(minLotsHundredths = 1, lotStepHundredths = 1)
        })
        val broker = freshBroker(config)
        val session = broker.openSession("account-a")
        val result = session.placeOrder("centilot", order(lots = 1))
        assertTrue(result.accepted)
        session.closePosition("close", result.entityId!!)
        assertEquals(-140_000L, session.state.value.trades.single().netPnlMicros)
    }

    @Test
    fun accountsAndBrokersDoNotSharePositionsOrRequestIds() = runTest {
        val broker = freshBroker()
        val a = broker.openSession("account-a")
        val b = broker.openSession("account-b")
        val untouched = b.state.value
        val open = a.placeOrder("same-id", order())
        assertEquals(untouched, b.state.value)
        assertFalse(b.closePosition("foreign", open.entityId!!).accepted)
        assertTrue(b.placeOrder("same-id", order(Side.SELL)).accepted)
        assertEquals(Side.BUY, a.state.value.positions.single().side)
        assertEquals(Side.SELL, b.state.value.positions.single().side)
        assertNotEquals(a.state.value.positions.single().id, b.state.value.positions.single().id)
        assertFailsWith<IllegalStateException> { broker.openSession("other-broker-account") }
        val other = freshBroker(TEST_CONFIG.copy(id = "other-demo"))
        val otherSession = other.openSession("account-a")
        assertTrue(otherSession.placeOrder("same-id", order()).accepted)
        assertNotEquals(a.state.value.positions.single().id, otherSession.state.value.positions.single().id)
    }

    @Test
    fun invalidVolumeTickStopsAndOverflowAreRejectedWithoutMutation() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val invalid = listOf(
            order(lots = 0), order(lots = 4), order(lots = 6), order(lots = 10_005), order(lots = Long.MAX_VALUE),
            order().copy(symbol = "MISSING"), order().copy(entryPrice = 10_000),
            order().copy(stopLoss = 9_985), order().copy(stopLoss = 10_020),
            order().copy(takeProfit = 10_015), order().copy(takeProfit = 9_970),
            order().copy(stopLoss = 9_973),
            order(kind = OrderKind.LIMIT, entry = 9_995),
            order(kind = OrderKind.STOP, entry = 10_023),
            order(kind = OrderKind.STOP, entry = Long.MAX_VALUE),
        )
        for ((index, request) in invalid.withIndex()) {
            assertFalse(session.preview(request).valid, "preview $index")
            assertFalse(session.placeOrder("invalid-$index", request).accepted, "place $index")
        }
        assertEquals(TEST_CONFIG.startingBalanceMicros, session.state.value.balanceMicros)
        assertTrue(session.state.value.positions.isEmpty())
        assertTrue(session.state.value.orders.isEmpty())
        assertEquals(invalid.size, session.state.value.events.size)
        val valid = order().copy(stopLoss = 9_980, takeProfit = 10_020)
        assertTrue(session.preview(valid).valid)
        assertTrue(session.placeOrder("valid", valid).accepted)
    }

    @Test
    fun fourPendingDirectionsUseBidAskAndActualGapPrices() = runTest {
        data class Case(val side: Side, val kind: OrderKind, val entry: Long,
            val beforeBid: Long, val beforeAsk: Long, val hitBid: Long, val hitAsk: Long, val fill: Long)
        val cases = listOf(
            Case(Side.BUY, OrderKind.LIMIT, 9_950, 9_945, 9_960, 9_930, 9_940, 9_940),
            Case(Side.SELL, OrderKind.LIMIT, 10_060, 10_050, 10_070, 10_070, 10_080, 10_070),
            Case(Side.BUY, OrderKind.STOP, 10_060, 10_040, 10_055, 10_050, 10_075, 10_075),
            Case(Side.SELL, OrderKind.STOP, 9_950, 9_955, 9_970, 9_930, 9_960, 9_930),
        )
        for (case in cases) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val request = order(case.side, case.kind, entry = case.entry)
            val result = session.placeOrder("pending", request)
            assertTrue(result.accepted, result.message)
            assertEquals(0L, session.state.value.marginMicros)
            assertEquals(TEST_CONFIG.startingBalanceMicros, session.state.value.balanceMicros)
            feed(broker, case.beforeBid, case.beforeAsk)
            assertEquals(1, session.state.value.orders.size)
            assertTrue(session.state.value.positions.isEmpty())
            feed(broker, case.hitBid, case.hitAsk)
            assertTrue(session.state.value.orders.isEmpty())
            assertEquals(case.fill, session.state.value.positions.single().entryPrice)
            assertEquals(result, session.placeOrder("pending", request))
            assertEquals(1, session.state.value.positions.size)
        }
    }

    @Test
    fun pendingDoesNotReserveMarginAndRejectsWhenTriggerCannotBeFunded() = runTest {
        val broker = freshBroker(TEST_CONFIG.copy(startingBalanceMicros = 1_000 * MICROS_PER_USD))
        val session = broker.openSession("account-a")
        val request = order(kind = OrderKind.LIMIT, entry = 9_950)
        assertTrue(session.preview(request).valid)
        assertTrue(session.placeOrder("pending", request).accepted)
        assertEquals(0L, session.state.value.marginMicros)
        feed(broker, 9_940, 9_950)
        assertTrue(session.state.value.positions.isEmpty())
        assertTrue(session.state.value.orders.isEmpty())
        assertEquals(1_000 * MICROS_PER_USD, session.state.value.balanceMicros)
        assertTrue(session.state.value.events.last().message.contains("触发被拒绝"))
        assertTrue(session.state.value.events.last().message.contains("保证金不足"))
    }

    @Test
    fun marketMarginIncludesEntrySpreadAndFee() = runTest {
        for ((balance, accepted) in listOf(1_012L to false, 1_013L to true)) {
            val broker = freshBroker(TEST_CONFIG.copy(startingBalanceMicros = balance * MICROS_PER_USD))
            val session = broker.openSession("account-a")
            assertEquals(accepted, session.preview(order()).valid)
            assertEquals(accepted, session.placeOrder("open", order()).accepted)
            if (accepted) assertEquals(0L, session.state.value.freeMarginMicros)
        }
    }

    @Test
    fun slAndTpUseActualGapExitForBothSides() = runTest {
        for (side in Side.entries) for (stop in listOf(true, false)) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val request = if (side == Side.BUY) order(side).copy(stopLoss = 9_950, takeProfit = 10_060)
                else order(side).copy(stopLoss = 10_060, takeProfit = 9_950)
            assertTrue(session.placeOrder("open", request).accepted)
            val exit = when {
                side == Side.BUY && stop -> { feed(broker, 9_900, 9_910); 9_900L }
                side == Side.BUY -> { feed(broker, 10_100, 10_110); 10_100L }
                stop -> { feed(broker, 10_090, 10_100); 10_100L }
                else -> { feed(broker, 9_890, 9_900); 9_900L }
            }
            assertTrue(session.state.value.positions.isEmpty())
            val trade = session.state.value.trades.single()
            assertEquals(exit, trade.exitPrice)
            assertEquals(if (stop) "止损" else "止盈", trade.reason)
            assertEquals(grossPnl(TEST_CONFIG.instruments.first(), side, 100, trade.entryPrice, exit) - 4_000_000,
                trade.netPnlMicros)
        }
    }

    @Test
    fun protectionDoesNotTriggerOnWrongQuoteSide() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        assertTrue(session.placeOrder("long", order().copy(takeProfit = 10_060)).accepted)
        feed(broker, 10_050, 10_070) // Ask crossed the long TP; bid did not.
        assertEquals(1, session.state.value.positions.size)
        feed(broker, 10_060, 10_080)
        assertTrue(session.state.value.positions.isEmpty())
        feed(broker, 10_000, 10_010)
        assertTrue(session.placeOrder("short", order(Side.SELL).copy(takeProfit = 9_950)).accepted)
        feed(broker, 9_940, 9_960) // Bid crossed the short TP; ask did not.
        assertEquals(1, session.state.value.positions.size)
        feed(broker, 9_930, 9_950)
        assertTrue(session.state.value.positions.isEmpty())
    }

    @Test
    fun triggeredOrderChecksNewProtectionInSameTick() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val request = order(kind = OrderKind.STOP, entry = 10_060).copy(stopLoss = 10_020, takeProfit = 10_120)
        assertTrue(session.placeOrder("pending", request).accepted)
        feed(broker, 10_160, 10_170)
        assertTrue(session.state.value.orders.isEmpty())
        assertTrue(session.state.value.positions.isEmpty())
        val trade = session.state.value.trades.single()
        assertEquals(10_170L, trade.entryPrice)
        assertEquals(10_160L, trade.exitPrice)
        assertEquals("止盈", trade.reason)
    }

    @Test
    fun triggerRejectsUnhitProtectionThatIsNowTooClose() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        assertTrue(session.placeOrder("pending", order(kind = OrderKind.STOP, entry = 10_060)
            .copy(takeProfit = 10_120)).accepted)
        feed(broker, 10_110, 10_115)
        assertTrue(session.state.value.orders.isEmpty())
        assertTrue(session.state.value.positions.isEmpty())
        assertTrue(session.state.value.events.last().message.contains("止盈方向或最小距离"))
    }

    @Test
    fun profitableProtectionUsesCurrentCloseQuoteAndVersion() = runTest {
        for (side in Side.entries) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val id = session.placeOrder("open", order(side)).entityId!!
            if (side == Side.BUY) feed(broker, 10_200, 10_210) else feed(broker, 9_800, 9_810)
            val stop = if (side == Side.BUY) 10_100L else 9_900L
            val target = if (side == Side.BUY) 10_300L else 9_700L
            assertEquals(1L, session.state.value.positions.single().version)
            val result = session.modifyProtection("protect", id, 1, stop, target)
            assertTrue(result.accepted, result.message)
            assertEquals(2L, session.state.value.positions.single().version)
            assertEquals(stop, session.state.value.positions.single().stopLoss)
            assertEquals(result, session.modifyProtection("protect", id, 1, stop, target))
            assertEquals(2L, session.state.value.positions.single().version)
            assertFalse(session.modifyProtection("stale", id, 1, null, null).accepted)
            if (side == Side.BUY) feed(broker, 10_220, 10_230) else feed(broker, 9_780, 9_790)
            assertEquals(2L, session.state.value.positions.single().version)
            assertTrue(session.closePosition("close", id).accepted)
            assertFalse(session.modifyProtection("closed", id, 2, null, null).accepted)
        }
    }

    @Test
    fun marketProtectionClearsTheWholeSpread() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        feed(broker, 10_000, 10_040)
        assertFalse(session.preview(order().copy(takeProfit = 10_025)).valid, "Inside the spread, under the buy fill")
        assertFalse(session.preview(order().copy(takeProfit = 10_040)).valid, "Exactly at the buy fill")
        assertTrue(session.preview(order().copy(takeProfit = 10_045)).valid)
        assertFalse(session.preview(order(Side.SELL).copy(takeProfit = 10_015)).valid, "Inside the spread, over the sell fill")
        assertFalse(session.preview(order(Side.SELL).copy(takeProfit = 10_000)).valid, "Exactly at the sell fill")
        assertTrue(session.preview(order(Side.SELL).copy(takeProfit = 9_995)).valid)
    }

    @Test
    fun stopMovesToBreakEvenOrPastItWhileTheTargetIsInsideTheMinimumDistance() = runTest {
        for (side in Side.entries) {
            val broker = freshBroker()
            val session = broker.openSession("account-a")
            val buy = side == Side.BUY
            val target = if (buy) 10_300L else 9_700L
            val id = session.placeOrder("open", order(side).copy(takeProfit = target)).entityId!!
            val entry = session.state.value.positions.single().entryPrice
            // The close price is two ticks from the target; the minimum distance is four.
            if (buy) feed(broker, 10_290, 10_300) else feed(broker, 9_700, 9_710)
            val even = session.modifyProtection("even", id, 1, entry, target)
            assertTrue(even.accepted, even.message)
            assertEquals(entry, session.state.value.positions.single().stopLoss)
            val past = entry + if (buy) 100 else -100
            val locked = session.modifyProtection("past", id, 2, past, target)
            assertTrue(locked.accepted, locked.message)
            assertEquals(past, session.state.value.positions.single().stopLoss)
            val through = session.modifyProtection("through", id, 3, if (buy) 10_290 else 9_710, target)
            assertFalse(through.accepted)
            assertTrue("止损须" in through.message, through.message)
            assertEquals(past, session.state.value.positions.single().stopLoss)
            assertEquals(broker.exportState(), DemoBroker(TEST_CONFIG, broker.exportState()).exportState())
        }
    }

    @Test
    fun allCommandsAreIdempotentAndConflictingReuseIsRejected() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val opened = session.placeOrder("open", order())
        val afterOpen = session.state.value
        assertEquals(opened, session.placeOrder("open", order()))
        assertEquals(afterOpen, session.state.value)
        assertFalse(session.placeOrder("open", order(Side.SELL)).accepted)
        assertEquals(opened, session.placeOrder("open", order()))
        assertEquals(1, session.state.value.positions.size)
        val closed = session.closePosition("close", opened.entityId!!)
        val afterClose = session.state.value
        assertEquals(closed, session.closePosition("close", opened.entityId!!))
        assertEquals(afterClose, session.state.value)
        val pending = session.placeOrder("pending", order(kind = OrderKind.LIMIT, entry = 9_950))
        val cancel = session.cancelOrder("cancel", pending.entityId!!)
        assertTrue(cancel.accepted)
        val afterCancel = session.state.value
        assertEquals(cancel, session.cancelOrder("cancel", pending.entityId!!))
        assertEquals(afterCancel, session.state.value)
        assertTrue(afterCancel.events.last().message.startsWith("撤单"))
        val rejected = session.placeOrder("reject", order(lots = 6))
        val afterReject = session.state.value
        assertFalse(rejected.accepted)
        assertEquals(rejected, session.placeOrder("reject", order(lots = 6)))
        assertEquals(afterReject, session.state.value)
        val restored = DemoBroker(TEST_CONFIG, broker.exportState())
        val resumed = restored.openSession("account-a")
        assertEquals(opened, resumed.placeOrder("open", order()))
        assertEquals(closed, resumed.closePosition("close", opened.entityId!!))
        assertEquals(cancel, resumed.cancelOrder("cancel", pending.entityId!!))
        assertEquals(rejected, resumed.placeOrder("reject", order(lots = 6)))
        assertEquals(afterReject, resumed.state.value)
    }

    @Test
    fun concurrentRetriesSerializeToOneFill() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val results = coroutineScope {
            List(40) { async { session.placeOrder("same", order()) } }.awaitAll()
        }
        assertTrue(results.all { it.accepted })
        assertEquals(1, results.distinct().size)
        assertEquals(1, session.state.value.positions.size)
        assertEquals(TEST_CONFIG.startingBalanceMicros - 2_000_000, session.state.value.balanceMicros)
    }

    @Test
    fun disconnectedAndStaleQuotesRejectButCancelIsStillAvailable() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val position = session.placeOrder("open", order()).entityId!!
        val pending = session.placeOrder("pending", order(kind = OrderKind.LIMIT, entry = 9_950)).entityId!!
        broker.setConnected(false)
        assertFalse(session.preview(order()).valid)
        assertFalse(session.placeOrder("disconnected", order()).accepted)
        assertFalse(session.closePosition("disconnected-close", position).accepted)
        assertFalse(session.modifyProtection("disconnected-modify", position, 1, 9_950, null).accepted)
        assertTrue(session.cancelOrder("cancel", pending).accepted)
        val quoteBefore = broker.market.value.quotes
        val timeBefore = broker.market.value.simulatedTimeMs
        broker.advance(75)
        assertEquals(timeBefore + 75_000, broker.market.value.simulatedTimeMs)
        assertEquals(quoteBefore, broker.market.value.quotes)
        broker.setConnected(true)
        assertFalse(session.placeOrder("stale", order()).accepted)
        assertFalse(session.closePosition("stale-close", position).accepted)
        assertFalse(session.modifyProtection("stale-modify", position, 1, 9_950, null).accepted)
        feed(broker, 10_000, 10_010)
        assertTrue(session.closePosition("fresh-close", position).accepted)
        // Advancing only another symbol also makes this symbol stale.
        broker.setQuote("ALTUSD", 20_000, 20_010, broker.market.value.simulatedTimeMs + 60_001)
        assertFalse(session.preview(order()).valid)
    }

    @Test
    fun invalidQuoteAndBackwardTimeRollBackAtomically() = runTest {
        val broker = freshBroker()
        val before = broker.exportState()
        val time = broker.market.value.simulatedTimeMs
        assertFailsWith<IllegalArgumentException> { broker.setQuote(SYMBOL, 10_010, 10_000, time) }
        assertFailsWith<IllegalArgumentException> { broker.setQuote(SYMBOL, 10_001, 10_010, time) }
        assertFailsWith<IllegalArgumentException> { broker.setQuote(SYMBOL, 10_000, 10_010, time - 1) }
        assertFailsWith<IllegalArgumentException> { broker.setQuote(SYMBOL, 10_000, Long.MAX_VALUE, time) }
        assertEquals(before, broker.exportState())
    }

    @Test
    fun restorePreservesRngTimeIdsHistoryAndProtectionResults() = runTest {
        val original = freshBroker()
        val session = original.openSession("account-a")
        val id = session.placeOrder("open", order()).entityId!!
        val protection = session.modifyProtection("protection", id, 1, 9_950, 10_100)
        session.placeOrder("pending", order(Side.SELL, OrderKind.LIMIT, entry = 10_200))
        val json = original.exportState()
        val restored = DemoBroker(TEST_CONFIG, json, seedTrades = true)
        assertEquals(json, restored.exportState())
        val resumed = restored.openSession("account-a")
        assertEquals(protection, resumed.modifyProtection("protection", id, 1, 9_950, 10_100))
        original.advance(75)
        restored.advance(75)
        assertEquals(original.exportState(), restored.exportState())
        assertEquals(session.placeOrder("next", order(Side.SELL)), resumed.placeOrder("next", order(Side.SELL)))
        assertEquals(original.exportState(), restored.exportState())
        assertEquals(original.history(SYMBOL, Timeframe.H1), restored.history(SYMBOL, Timeframe.H1))
    }

    @Test
    fun malformedAndIncompatibleSnapshotsThrowInsteadOfResetting() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val closed = session.placeOrder("closed-open", order()).entityId!!
        session.closePosition("closed", closed)
        session.placeOrder("open", order())
        val valid = broker.exportState()
        val root = Json.parseToJsonElement(valid).jsonObject
        val broken = mutableListOf("", "null", "{}", valid.dropLast(1))
        broken += patch(root, "schema", JsonPrimitive(999)).toString()
        broken += patch(root, "brokerId", JsonPrimitive("wrong-broker")).toString()
        broken += patch(root, "rng", JsonPrimitive(0)).toString()
        broken += patch(root, "nextId", JsonPrimitive(1)).toString()
        broken += mutateState(root) { patch(it, "balanceMicros", JsonPrimitive(42)) }.toString()
        broken += mutateState(root) { patch(it, "equityMicros", JsonPrimitive(42)) }.toString()
        broken += mutateState(root) { patch(it, "marginMicros", JsonPrimitive(42)) }.toString()
        broken += mutateState(root) { state ->
            val positions = state.getValue("positions").jsonArray
            patch(state, "positions", JsonArray(listOf(patch(positions.single().jsonObject, "entryFeeMicros", JsonPrimitive(0)))))
        }.toString()
        broken += mutateState(root) { state ->
            val trade = state.getValue("trades").jsonArray.single().jsonObject
            patch(state, "trades", JsonArray(listOf(patch(trade, "netPnlMicros", JsonPrimitive(1)))))
        }.toString()
        val market = root.getValue("market").jsonObject
        val quotes = market.getValue("quotes").jsonObject
        val quote = quotes.getValue(SYMBOL).jsonObject
        broken += patch(root, "market", patch(market, "quotes", patch(quotes, SYMBOL,
            patch(quote, "bid", JsonPrimitive(9_995))))).toString()
        for ((index, json) in broken.withIndex()) {
            assertFailsWith<IllegalArgumentException>("corruption $index") { DemoBroker(TEST_CONFIG, json) }
        }
        assertFailsWith<IllegalArgumentException> { DemoBroker(TEST_CONFIG.copy(id = "wrong"), valid) }
        assertFailsWith<IllegalArgumentException> {
            DemoBroker(TEST_CONFIG.copy(instruments = TEST_CONFIG.instruments.map { it.copy(commissionMicrosPerLot = 1) }), valid)
        }
        assertEquals(valid, broker.exportState())
    }

    @Test
    fun allTimeframesAggregateTheSameOrderedBidTicks() = runTest {
        val broker = freshBroker()
        assertTrue(broker.history(SYMBOL, Timeframe.M1).size >= 1_440)
        val start = (broker.market.value.simulatedTimeMs / 60_000 + 1) * 60_000
        for ((index, price) in listOf(10_000L, 10_050L, 9_950L, 10_025L, 10_010L).withIndex()) {
            broker.setQuote(SYMBOL, price, price + 100, start + index * 15_000L)
        }
        val minutes = broker.history(SYMBOL, Timeframe.M1)
        assertEquals(Candle(start, 10_000, 10_050, 9_950, 10_025), minutes.first { it.timeMs == start })
        assertEquals(10_010L, minutes.last().close)
        val before = broker.exportState()
        for (timeframe in Timeframe.presets.filterNot { it.isSeconds }) {
            val width = timeframe.millis
            val expected = minutes.groupBy { it.timeMs / width * width }.map { (time, group) ->
                Candle(time, group.first().open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close)
            }
            assertEquals(expected, broker.history(SYMBOL, timeframe), timeframe.label)
        }
        // Second intervals group the same ticks more finely: one bar per tick at 15 seconds, two ticks per bar at 30.
        assertEquals(listOf(10_000L, 10_050L, 9_950L, 10_025L, 10_010L).mapIndexed { index, price -> Candle(start + index * 15_000L, price, price, price, price) },
            broker.history(SYMBOL, Timeframe.ofSeconds(15)).filter { it.timeMs >= start })
        assertEquals(listOf(Candle(start, 10_000, 10_050, 10_000, 10_050), Candle(start + 30_000, 9_950, 10_025, 9_950, 10_025), Candle(start + 60_000, 10_010, 10_010, 10_010, 10_010)),
            broker.history(SYMBOL, Timeframe.ofSeconds(30)).filter { it.timeMs >= start })
        for (timeframe in Timeframe.presets.filter { it.isSeconds }) {
            val bars = broker.history(SYMBOL, timeframe)
            assertTrue(bars.all { it.timeMs % timeframe.millis == 0L } && bars.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }, timeframe.label)
            assertEquals(10_010L, bars.last().close, timeframe.label)
        }
        assertEquals(before, broker.exportState())
    }

    @Test
    fun stopOutFiresAtExactlyFiftyPercentIncludingExitFee() = runTest {
        val broker = freshBroker(TEST_CONFIG.copy(startingBalanceMicros = 2_002 * MICROS_PER_USD))
        val session = broker.openSession("account-a")
        assertTrue(session.placeOrder("short", order(Side.SELL)).accepted)
        assertEquals(1_000 * MICROS_PER_USD, session.state.value.marginMicros)
        feed(broker, 11_485, 11_495) // Equity 505, margin 1,000: just above 50%.
        assertEquals(1, session.state.value.positions.size)
        feed(broker, 11_490, 11_500) // Equity exactly 500 before closing.
        assertTrue(session.state.value.positions.isEmpty())
        assertEquals(498 * MICROS_PER_USD, session.state.value.balanceMicros)
        assertTrue(session.state.value.trades.single().reason.startsWith("强平"))
    }

    @Test
    fun stopOutClosesWorstLossFirstAndStopsAfterRecovery() = runTest {
        val broker = freshBroker(TEST_CONFIG.copy(startingBalanceMicros = 5_000 * MICROS_PER_USD))
        val session = broker.openSession("account-a")
        val first = session.placeOrder("first", order()).entityId!!
        feed(broker, 12_000, 12_010)
        val second = session.placeOrder("second", order()).entityId!!
        feed(broker, 9_050, 9_060)
        assertEquals(first, session.state.value.positions.single().id)
        assertEquals(second, session.state.value.trades.single().positionId)
        assertTrue(session.state.value.equityMicros > session.state.value.marginMicros / 2)
    }

    @Test
    fun configuredDemosHaveRealSeededTradesAndRestoreDoesNotReseed() = runTest {
        assertEquals(listOf("Atlas Demo", "Meridian Demo"), DemoConfigs.all.map { it.name })
        for (config in DemoConfigs.all) {
            assertEquals(2, config.accounts.size)
            val seeded = DemoBroker(config)
            val unseeded = DemoBroker(config, seedTrades = false)
            assertEquals(seeded.market.value, unseeded.market.value)
            for (instrument in config.instruments) {
                assertEquals(seeded.history(instrument.symbol, Timeframe.M1), unseeded.history(instrument.symbol, Timeframe.M1))
            }
            for (account in config.accounts) {
                val state = seeded.openSession(account.id).state.value
                assertEquals(2, state.trades.size)
                assertEquals(1, state.positions.size)
                assertTrue(state.events.any { it.message.startsWith("演示数据") })
                val allTicks = Json.parseToJsonElement(seeded.exportState()).jsonObject.getValue("ticks").jsonObject
                for (trade in state.trades) {
                    val history = allTicks.getValue(trade.symbol).jsonArray.map { it.jsonObject }
                    val entry = history.first { it.getValue("timeMs").jsonPrimitive.long == trade.openedAtMs }
                    val exit = history.first { it.getValue("timeMs").jsonPrimitive.long == trade.closedAtMs }
                    assertEquals(trade.entryPrice, entry.getValue(if (trade.side == Side.BUY) "ask" else "bid").jsonPrimitive.long)
                    assertEquals(trade.exitPrice, exit.getValue(if (trade.side == Side.BUY) "bid" else "ask").jsonPrimitive.long)
                }
            }
            val exported = seeded.exportState()
            assertEquals(exported, DemoBroker(config, exported).exportState())
        }
        assertNotEquals(DemoConfigs.all[0].instruments.first { it.symbol == "XAUUSD" }.commissionMicrosPerLot,
            DemoConfigs.all[1].instruments.first { it.symbol == "XAUUSD" }.commissionMicrosPerLot)
    }

    @Test
    fun eventRetentionNeverExpiresRequestResults() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        val first = session.placeOrder("earliest", order(lots = 6))
        repeat(210) { session.placeOrder("reject-$it", order(lots = 6)) }
        assertEquals(200, session.state.value.events.size)
        val restored = DemoBroker(TEST_CONFIG, broker.exportState())
        val account = restored.openSession("account-a")
        val before = account.state.value
        assertEquals(first, account.placeOrder("earliest", order(lots = 6)))
        assertEquals(before, account.state.value)
    }

    @Test
    fun resetIsDeterministicAndExistingSessionReceivesResetState() = runTest {
        val broker = freshBroker()
        val session = broker.openSession("account-a")
        session.placeOrder("old", order())
        broker.advance(45)
        broker.reset(seedTrades = false)
        val clean = DemoBroker(TEST_CONFIG, seedTrades = false)
        assertEquals(clean.exportState(), broker.exportState())
        assertEquals(clean.openSession("account-a").state.value, session.state.value)
        assertTrue(session.placeOrder("old", order()).accepted)
        assertEquals(1, session.state.value.positions.size)
        val now = broker.market.value.simulatedTimeMs
        broker.advance()
        assertEquals(now + 15_000, broker.market.value.simulatedTimeMs)
    }

    private suspend fun freshBroker(config: DemoConfig = TEST_CONFIG): DemoBroker {
        val broker = DemoBroker(config, seedTrades = false)
        feed(broker, 10_000, 10_010)
        return broker
    }

    private suspend fun feed(broker: DemoBroker, bid: Long, ask: Long) =
        broker.setQuote(SYMBOL, bid, ask, broker.market.value.simulatedTimeMs + 15_000)

    private fun order(side: Side = Side.BUY, kind: OrderKind = OrderKind.MARKET,
        lots: Long = 100, entry: Long? = null,
    ) = OrderRequest(SYMBOL, side, kind, lots, entry)

    private fun patch(obj: JsonObject, key: String, value: JsonElement) = JsonObject(obj + (key to value))
    private fun mutateState(root: JsonObject, transform: (JsonObject) -> JsonObject): JsonObject {
        val accounts = root.getValue("accounts").jsonArray.toMutableList()
        val first = accounts.first().jsonObject
        accounts[0] = patch(first, "state", transform(first.getValue("state").jsonObject))
        return patch(root, "accounts", JsonArray(accounts))
    }

    companion object {
        private const val SYMBOL = "TESTUSD"
        private val TEST_CONFIG = DemoConfig(
            id = "test-demo", name = "Test", description = "Deterministic test broker",
            instruments = listOf("TESTUSD", "ALTUSD").map {
                Instrument(it, it, "Test", 2, tickSize = 5, tickValueMicrosPerLot = 5_000_000,
                    minLotsHundredths = 5, maxLotsHundredths = 10_000, lotStepHundredths = 5,
                    leverage = 10, minStopTicks = 4, commissionMicrosPerLot = 2_000_000)
            },
            accounts = listOf(AccountInfo("account-a", "A"), AccountInfo("account-b", "B")),
            initialBids = mapOf("TESTUSD" to 10_000L, "ALTUSD" to 20_000L),
            spreadTicks = mapOf("TESTUSD" to 2L, "ALTUSD" to 2L),
            volatilityTicks = mapOf("TESTUSD" to 1, "ALTUSD" to 1),
            randomSeed = 1234567, startTimeMs = 0,
        )
    }
}
