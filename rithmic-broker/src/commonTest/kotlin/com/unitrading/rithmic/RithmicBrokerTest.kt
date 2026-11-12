package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlin.test.*

private fun credentials(environment: RithmicEnvironment = RithmicEnvironment.TEST) =
    RithmicCredentials(environment, FAKE_GATEWAY, environment.defaultSystemName, "tester@example.com", "test-password")

@OptIn(ExperimentalCoroutinesApi::class)
class RithmicBrokerTest {
    private fun TestScope.broker(server: FakeRithmicServer, environment: RithmicEnvironment = RithmicEnvironment.TEST) =
        RithmicBroker(credentials(environment), server, backgroundScope, { FAKE_TIME })

    private suspend fun TestScope.ready(server: FakeRithmicServer = FakeRithmicServer(), symbol: String = "MESZ6"): RithmicBroker {
        val broker = broker(server)
        val progress = mutableListOf<String>()
        broker.connect { progress += it }
        assertTrue(progress.isNotEmpty())
        broker.watch(symbol, Timeframe.M5)
        runCurrent()
        assertTrue(broker.market.value.quotes.containsKey("MESZ6"), broker.market.value.statusMessage)
        return broker
    }

    @Test fun connectLogsEveryPlantInAndLoadsAccountsRoutesSpecsQuotesAndBars() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        // Probe for system names first, then order, PnL, ticker and history plants.
        assertEquals(listOf(0, RithmicEnums.INFRA_ORDER, RithmicEnums.INFRA_PNL, RithmicEnums.INFRA_TICKER, RithmicEnums.INFRA_HISTORY), server.sockets.map { it.infra })
        assertTrue(server.sockets[0].closedByClient)
        val login = server.requests(Rt.REQUEST_LOGIN).first()
        assertEquals("tester@example.com", login.str(Rf.USER))
        assertEquals("test-password", login.str(Rf.PASSWORD))
        assertEquals("Rithmic Test", login.str(Rf.SYSTEM_NAME))
        assertEquals(RithmicCredentials.DEFAULT_APP_NAME, login.str(Rf.APP_NAME))
        assertEquals("5.55", login.str(Rf.TEMPLATE_VERSION))
        assertEquals(listOf(AccountInfo("ACC1", "Account ACC1", "USD")), broker.accounts)
        assertEquals(BrokerEnvironment.RITHMIC_TEST, broker.descriptor.environment)
        assertTrue(broker.descriptor.simulated)
        // Account-scoped requests carry the FCM/IB ids from the login info.
        server.requests(Rt.REQUEST_SUBSCRIBE_ORDER_UPDATES).single().let { assertEquals("FCM1", it.str(Rf.FCM_ID)); assertEquals("IB1", it.str(Rf.IB_ID)); assertEquals("ACC1", it.str(Rf.ACCOUNT_ID)) }
        val spec = broker.instruments.single { it.symbol == "MESZ6" }
        assertEquals(25, spec.tickSize)
        assertEquals(1_250_000, spec.tickValueMicrosPerLot)
        assertEquals(Quote(550000, 550025, FAKE_TIME + 250), broker.market.value.quotes["MESZ6"])
        server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).single().let { assertEquals("CME", it.str(Rf.EXCHANGE)); assertEquals(3, it.long(Rf.UPDATE_BITS)); assertEquals(RithmicEnums.REQUEST_SUBSCRIBE, it.int(Rf.REQUEST)) }
        server.requests(Rt.REQUEST_TIME_BAR_UPDATE).single().let { assertEquals(RithmicEnums.BAR_MINUTE, it.int(Rf.BAR_TYPE)); assertEquals(5, it.int(Rf.BAR_PERIOD)) }
        val replay = server.requests(Rt.REQUEST_TIME_BAR_REPLAY).single()
        assertEquals(RithmicEnums.TIME_ORDER_FORWARDS, replay.int(Rf.TIME_ORDER))
        assertEquals((FAKE_TIME / 1_000).toInt(), replay.int(Rf.FINISH_INDEX))
        val bars = broker.history("MESZ6", Timeframe.M5)
        assertEquals(3, bars.size)
        val lastClose = FAKE_TIME / 1_000 / 300 * 300 * 1_000
        assertEquals(lastClose - 300_000, bars.last().timeMs)
        assertEquals(Candle(lastClose - 300_000, 549900, 550100, 549850, 550000, 100), bars.last())
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
        val account = broker.openSession("ACC1").state.value
        assertEquals(25_000_000_000, account.balanceMicros)
        assertTrue(account.balanceKnown)
        assertEquals(12_500_000, account.dailyPnl?.totalMicros)
        assertEquals("H1", account.executions.single().id)
        assertEquals(Side.BUY, account.executions.single().side)
    }

    @Test fun marketOrderGoesOutOnTheExchangeRouteAndFillsBecomeExecutionsAndSounds() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val fills = mutableListOf<TradeFill>()
        backgroundScope.launch { broker.tradeFills.collect { fills += it } }
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)
        val preview = session.preview(request)
        assertTrue(preview.valid, preview.errors.joinToString())
        assertEquals(550025, preview.entryPrice)
        assertFalse(preview.marginKnown)
        val result = session.placeOrder("req-1", request)
        assertTrue(result.accepted, result.message)
        assertEquals("B1", result.entityId)
        val sent = server.requests(Rt.REQUEST_NEW_ORDER).single()
        assertEquals("MESZ6", sent.str(Rf.SYMBOL)); assertEquals("CME", sent.str(Rf.EXCHANGE)); assertEquals("simulator", sent.str(Rf.TRADE_ROUTE))
        assertEquals(1, sent.int(Rf.QUANTITY)); assertEquals(1L, sent.long(Rf.QUANTITY_64)); assertEquals(RithmicEnums.PRICE_MARKET, sent.int(Rf.PRICE_TYPE)); assertEquals(RithmicEnums.BUY, sent.int(Rf.TRANSACTION_TYPE))
        assertEquals(RithmicEnums.DURATION_DAY, sent.int(Rf.DURATION)); assertEquals(RithmicEnums.PLACEMENT_MANUAL, sent.int(Rf.MANUAL_OR_AUTO)); assertEquals("UT-req-1", sent.str(Rf.USER_TAG))
        assertEquals("ACC1", sent.str(Rf.ACCOUNT_ID)); assertEquals("FCM1", sent.str(Rf.FCM_ID))
        runCurrent()
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
        server.fill("B1", "F1", RithmicEnums.BUY, 1, 5500.25)
        server.position(1, 5500.25, "0")
        runCurrent()
        val account = session.state.value
        assertEquals(listOf("H1", "F1"), account.executions.map { it.id })
        assertEquals(550025, account.executions.last().price)
        assertTrue(account.orders.isEmpty())
        val position = account.positions.single()
        assertEquals("ACC1:MESZ6", position.id); assertEquals(Side.BUY, position.side); assertEquals(100, position.lotsHundredths); assertEquals(550025, position.entryPrice)
        assertEquals(listOf(TradeFill("ACC1", "B1", "F1", OrderRole.ENTRY)), fills)
        // Same request id with the same command is idempotent; a different command is refused.
        assertEquals(result, session.placeOrder("req-1", request))
        assertFalse(session.placeOrder("req-1", request.copy(side = Side.SELL)).accepted)
        assertEquals(1, server.requests(Rt.REQUEST_NEW_ORDER).size)
    }

    @Test fun limitOrderWithProtectionBecomesAStaticBracketInTicks() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 200, entryPrice = 549900, stopLoss = 549500, takeProfit = 550500)
        val preview = session.preview(request)
        assertTrue(preview.valid, preview.errors.joinToString())
        // Two contracts: 16 ticks × 1.25 USD × 2 and 24 ticks × 1.25 USD × 2, gross.
        assertEquals(-40_000_000, preview.stopLossPnlMicros)
        assertEquals(60_000_000, preview.takeProfitPnlMicros)
        val result = session.placeOrder("req-2", request)
        assertTrue(result.accepted, result.message)
        val sent = server.requests(Rt.REQUEST_BRACKET_ORDER).single()
        assertEquals(RithmicEnums.BRACKET_TARGET_AND_STOP_STATIC, sent.int(Rf.BRACKET_TYPE))
        assertEquals(16, sent.int(Rf.STOP_TICKS)); assertEquals(2, sent.int(Rf.STOP_QUANTITY)); assertEquals(2L, sent.long(Rf.STOP_QUANTITY_64))
        assertEquals(24, sent.int(Rf.TARGET_TICKS)); assertEquals(2, sent.int(Rf.TARGET_QUANTITY)); assertEquals(2L, sent.long(Rf.TARGET_QUANTITY_64))
        assertEquals(2L, sent.long(Rf.QUANTITY_64))
        assertEquals(5499.0, sent.double(Rf.PRICE)); assertEquals(RithmicEnums.PRICE_LIMIT, sent.int(Rf.PRICE_TYPE)); assertEquals(RithmicEnums.USER_TYPE_TRADER, sent.int(Rf.USER_TYPE))
        assertTrue(server.requests(Rt.REQUEST_NEW_ORDER).isEmpty())
        runCurrent()
        val pending = session.state.value.orders.single()
        assertEquals("B1", pending.id); assertEquals(OrderRole.ENTRY, pending.role); assertEquals(OrderKind.LIMIT, pending.request.kind); assertEquals(549900, pending.request.entryPrice)
        // Stop entries use the trigger price field; a stop with protection on the sell side measures ticks the other way.
        val stop = OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, entryPrice = 549000, stopLoss = 549500)
        assertTrue(session.preview(stop).valid, session.preview(stop).errors.joinToString())
        server.orderReply = FakeRithmicServer.OrderReply.ACCEPT
        assertTrue(session.placeOrder("req-3", stop).accepted)
        val bracket = server.requests(Rt.REQUEST_BRACKET_ORDER).last()
        assertEquals(RithmicEnums.PRICE_STOP_MARKET, bracket.int(Rf.PRICE_TYPE)); assertEquals(5490.0, bracket.double(Rf.TRIGGER_PRICE)); assertNull(bracket.double(Rf.PRICE))
        assertEquals(RithmicEnums.BRACKET_STOP_ONLY_STATIC, bracket.int(Rf.BRACKET_TYPE)); assertEquals(20, bracket.int(Rf.STOP_TICKS)); assertNull(bracket.int(Rf.TARGET_TICKS))
    }

    @Test fun serverRejectionIsReportedAsRejectionAndKeepsTradingOpen() = runTest {
        val server = FakeRithmicServer().apply { orderReply = FakeRithmicServer.OrderReply.REJECT }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val result = session.placeOrder("req-4", OrderRequest("MESZ6", Side.SELL, OrderKind.MARKET, 100))
        assertFalse(result.accepted)
        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertTrue(result.message.contains("quantity exceeds limit"), result.message)
        assertTrue(broker.market.value.tradingReady)
        assertTrue(session.state.value.events.any { it.message.contains("quantity exceeds limit") })
    }

    @Test fun silentServerLeavesTheCommandUnknownAndPausesWritesUntilTheOrderAppears() = runTest {
        val server = FakeRithmicServer().apply { orderReply = FakeRithmicServer.OrderReply.SILENT }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val result = session.placeOrder("req-5", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("核对"))
        server.orderReply = FakeRithmicServer.OrderReply.ACCEPT
        val blocked = session.placeOrder("req-6", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertFalse(blocked.accepted)
        assertEquals(1, server.requests(Rt.REQUEST_NEW_ORDER).size)
        // The late order notification carries the client tag; that settles the unknown command without any resend.
        server.notifyOrder("B77", "open") { string(Rf.USER_TAG, "UT-req-5"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME"); int32(Rf.TRANSACTION_TYPE, RithmicEnums.BUY); int32(Rf.QUANTITY, 1); int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_MARKET) }
        runCurrent()
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
        assertEquals(1, server.requests(Rt.REQUEST_NEW_ORDER).size)
        assertTrue(session.placeOrder("req-7", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
    }

    @Test fun exitPositionResolvesWhenTheServerReportsFlat() = runTest {
        val server = FakeRithmicServer().apply { positions += positionRow(2, 5500.0, "-25") }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val position = session.state.value.positions.single()
        assertEquals(200, position.lotsHundredths); assertEquals(Side.BUY, position.side); assertEquals(550000, position.entryPrice)
        assertEquals(25_000_000_000, session.state.value.balanceMicros)
        val result = session.closePosition("req-8", position.id)
        assertTrue(result.accepted, result.message)
        val exit = server.requests(Rt.REQUEST_EXIT_POSITION).single()
        assertEquals("MESZ6", exit.str(Rf.SYMBOL)); assertEquals("CME", exit.str(Rf.EXCHANGE)); assertEquals(RithmicEnums.PLACEMENT_MANUAL, exit.int(Rf.MANUAL_OR_AUTO))
        runCurrent()
        assertFalse(broker.market.value.tradingReady)
        server.position(0, 0.0)
        runCurrent()
        assertTrue(session.state.value.positions.isEmpty())
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
    }

    /** The live server left a resting bracket entry on a status outside the old list, which locked its price and protection. */
    @Test fun onlyACommandInFlightLocksAPendingEntry() = runTest {
        val server = FakeRithmicServer().apply { openOrders += limitEntry("E1", RithmicEnums.BUY, 1, 5490.0) }
        val session = ready(server).openSession("ACC1")
        assertTrue(session.state.value.orders.single().editable)
        for (status in listOf("order sent to exch", "open pending", "Open", "trigger pending")) {
            server.notifyOrder("E1", status)
            runCurrent()
            assertTrue(session.state.value.orders.single().editable, status)
        }
        server.notifyOrder("E1", "modify pending")
        runCurrent()
        val locked = session.state.value.orders.single()
        assertFalse(locked.editable)
        assertTrue("modify pending" in locked.note.orEmpty(), locked.note)
    }

    @Test fun pendingEntryCanBeRepricedAndCancelled() = runTest {
        val server = FakeRithmicServer().apply { openOrders += limitEntry("E1", RithmicEnums.BUY, 1, 5490.0) }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val order = session.state.value.orders.single()
        assertEquals("E1", order.id); assertEquals(OrderRole.ENTRY, order.role); assertTrue(order.editable); assertEquals(549000, order.request.entryPrice)
        assertNull(order.request.stopLoss)
        assertTrue(session.previewOrderModification("E1", 549100).valid)
        val modify = session.modifyOrder("req-9", "E1", order.version, 549100)
        assertTrue(modify.accepted, modify.message)
        val sent = server.requests(Rt.REQUEST_MODIFY_ORDER).single()
        assertEquals("E1", sent.str(Rf.BASKET_ID)); assertEquals(5491.0, sent.double(Rf.PRICE)); assertEquals(1, sent.int(Rf.QUANTITY)); assertEquals(RithmicEnums.PRICE_LIMIT, sent.int(Rf.PRICE_TYPE))
        server.notifyOrder("E1", "modified") { double(Rf.PRICE, 5491.0) }
        runCurrent()
        assertEquals(549100, session.state.value.orders.single().request.entryPrice)
        assertTrue(broker.market.value.tradingReady)
        val stale = session.modifyOrder("req-10", "E1", order.version, 549200)
        assertFalse(stale.accepted)
        val current = session.state.value.orders.single()
        val cancel = session.cancelOrder("req-11", current.id)
        assertTrue(cancel.accepted, cancel.message)
        assertEquals("E1", server.requests(Rt.REQUEST_CANCEL_ORDER).single().str(Rf.BASKET_ID))
        server.notifyOrder("E1", "complete") { string(Rf.COMPLETION_REASON, "C") }
        runCurrent()
        assertTrue(session.state.value.orders.isEmpty())
        assertTrue(broker.market.value.tradingReady)
    }

    /** PnL updates arrive all the time on the real server; one must not invalidate a protection edit the user has open. */
    @Test fun aPnlUpdateKeepsThePositionVersionSoProtectionStaysEditable() = runTest {
        val server = FakeRithmicServer().apply {
            positions += positionRow(1, 5500.0)
            openOrders += limitEntry("S1", RithmicEnums.SELL, 1, 5490.0, originalBasket = "E1", priceType = RithmicEnums.PRICE_STOP_MARKET)
            openOrders += limitEntry("T1", RithmicEnums.SELL, 1, 5510.0, originalBasket = "E1")
        }
        val session = ready(server).openSession("ACC1")
        val before = session.state.value.positions.single()
        server.position(1, 5500.0, pnl = "25")
        runCurrent()
        val after = session.state.value.positions.single()
        assertEquals(before.version, after.version)
        assertEquals(before.openedAtMs, after.openedAtMs)
        val result = session.modifyProtection("req-50", before.id, before.version, 549000, 551500)
        assertTrue(result.accepted, result.message)
        assertEquals("T1", server.requests(Rt.REQUEST_MODIFY_ORDER).single().str(Rf.BASKET_ID))
    }

    @Test fun bracketChildrenOfANetPositionAreItsProtectionAndModifyThatChild() = runTest {
        val server = FakeRithmicServer().apply {
            positions += positionRow(1, 5500.0)
            openOrders += limitEntry("S1", RithmicEnums.SELL, 1, 5490.0, originalBasket = "E1", priceType = RithmicEnums.PRICE_STOP_MARKET)
            openOrders += limitEntry("T1", RithmicEnums.SELL, 1, 5510.0, originalBasket = "E1")
        }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val position = session.state.value.positions.single()
        assertEquals(549000, position.stopLoss); assertEquals(551000, position.takeProfit)
        assertEquals("S1", position.stopLossOrderId); assertEquals("T1", position.takeProfitOrderId); assertTrue(position.protectionEditable)
        val roles = session.state.value.orders.associate { it.id to it.role }
        assertEquals(mapOf("S1" to OrderRole.STOP_LOSS, "T1" to OrderRole.TAKE_PROFIT), roles)
        assertTrue(session.state.value.orders.all { it.positionId == position.id })
        assertFalse(session.modifyProtection("req-12", position.id, position.version, 548500, 551500).accepted)
        val result = session.modifyProtection("req-13", position.id, position.version, 548500, 551000)
        assertTrue(result.accepted, result.message)
        val sent = server.requests(Rt.REQUEST_MODIFY_ORDER).single()
        assertEquals("S1", sent.str(Rf.BASKET_ID)); assertEquals(5485.0, sent.double(Rf.TRIGGER_PRICE)); assertEquals(RithmicEnums.PRICE_STOP_MARKET, sent.int(Rf.PRICE_TYPE)); assertNull(sent.double(Rf.PRICE))
        server.notifyOrder("S1", "modified") { double(Rf.TRIGGER_PRICE, 5485.0) }
        runCurrent()
        assertEquals(548500, session.state.value.positions.single().stopLoss)
        assertTrue(broker.market.value.tradingReady)
        // A second stop child makes the relation ambiguous: the position becomes read-only instead of guessing.
        server.plant(RithmicEnums.INFRA_ORDER).push { int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); string(Rf.ACCOUNT_ID, "ACC1"); limitEntry("S2", RithmicEnums.SELL, 1, 5480.0, originalBasket = "E9", priceType = RithmicEnums.PRICE_STOP_MARKET)() }
        runCurrent()
        assertFalse(session.state.value.positions.single().protectionEditable)
        // Both stops are still this position's protection for display, but neither can be edited as the stop.
        val stops = session.state.value.orders.filter { it.role == OrderRole.STOP_LOSS }
        assertEquals(setOf("S1", "S2"), stops.map { it.id }.toSet())
        assertTrue(stops.all { it.positionId == position.id && !it.editable && it.note != null })
    }

    /** Two stops leave the position's protection locked; each leg still changes on its own, by its basket id, its price only. */
    @Test fun bracketLegsLockedByTheirPositionChangeOnTheirOwn() = runTest {
        val server = FakeRithmicServer().apply {
            positions += positionRow(1, 5500.0)
            openOrders += limitEntry("S1", RithmicEnums.SELL, 1, 5490.0, originalBasket = "E1", priceType = RithmicEnums.PRICE_STOP_MARKET)
            openOrders += limitEntry("S2", RithmicEnums.SELL, 1, 5480.0, originalBasket = "E9", priceType = RithmicEnums.PRICE_STOP_MARKET)
            openOrders += limitEntry("T1", RithmicEnums.SELL, 1, 5510.0, originalBasket = "E1")
        }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        assertFalse(session.state.value.positions.single().protectionEditable)
        val orders = session.state.value.orders.associateBy { it.id }
        assertTrue(listOf("S1", "S2", "T1").all { orders.getValue(it).standaloneEditable }, "the lone target is locked with the position too")
        assertFalse(broker.capabilities.modifyOrderQuantity)
        assertEquals(listOf(CROSSED_ORDER_PRICE), session.previewOrderChange("S2", 550000, 100).errors, "a sell stop at the bid would trigger at once")
        assertFalse(session.previewOrderChange("S2", 548500, 200).valid, "the size stays")
        assertTrue(session.previewOrderChange("S2", 548500, 100).valid)
        val result = session.changeOrder("req-70", "S2", orders.getValue("S2").version, 548500, 100)
        assertTrue(result.accepted, result.message)
        val sent = server.requests(Rt.REQUEST_MODIFY_ORDER).single()
        assertEquals("S2", sent.str(Rf.BASKET_ID)); assertEquals(5485.0, sent.double(Rf.TRIGGER_PRICE))
        assertEquals(RithmicEnums.PRICE_STOP_MARKET, sent.int(Rf.PRICE_TYPE)); assertEquals(1, sent.int(Rf.QUANTITY))
    }

    private fun ProtoMessage.ints(field: Int) = values(field).map { (it as ProtoValue.Varint).value.toInt() }
    private fun ProtoMessage.doubles(field: Int) = values(field).map { Double.fromBits((it as ProtoValue.Fixed64).value) }
    private fun taggedStop(basket: String, tag: String = "${PROTECTION_TAG_PREFIX}first"): ProtoWriter.() -> Unit =
        { limitEntry(basket, RithmicEnums.SELL, 1, 5490.0, priceType = RithmicEnums.PRICE_STOP_MARKET)(); string(Rf.USER_TAG, tag) }

    @Test fun aStopAddedToABarePositionIsOnePlainTaggedStop() = runTest {
        val server = FakeRithmicServer().apply { positions += positionRow(1, 5500.0) }
        val session = ready(server).openSession("ACC1")
        val p = session.state.value.positions.single()
        assertTrue(p.protectionEditable)
        val result = session.modifyProtection("req-80", p.id, p.version, 549000, null)
        assertTrue(result.accepted, result.message)
        assertTrue(server.requests(Rt.REQUEST_OCO_ORDER).isEmpty())
        val sent = server.requests(Rt.REQUEST_NEW_ORDER).single()
        assertEquals("${PROTECTION_TAG_PREFIX}req-80", sent.str(Rf.USER_TAG)); assertEquals(RithmicEnums.SELL, sent.int(Rf.TRANSACTION_TYPE))
        assertEquals(RithmicEnums.PRICE_STOP_MARKET, sent.int(Rf.PRICE_TYPE)); assertEquals(5490.0, sent.double(Rf.TRIGGER_PRICE)); assertNull(sent.double(Rf.PRICE))
        assertEquals(1, sent.int(Rf.QUANTITY)); assertEquals("simulator", sent.str(Rf.TRADE_ROUTE))
        runCurrent()
        val after = session.state.value.positions.single()
        assertEquals(549000, after.stopLoss); assertTrue(after.protectionEditable)
        assertEquals(OrderRole.STOP_LOSS, session.state.value.orders.single().role)
        assertEquals(after.id, session.state.value.orders.single().positionId)
    }

    @Test fun aStopAndTargetAddedTogetherAreOneOcoRequestWithAFieldSlotPerLeg() = runTest {
        val server = FakeRithmicServer().apply { positions += positionRow(1, 5500.0) }
        val session = ready(server).openSession("ACC1")
        val p = session.state.value.positions.single()
        val result = session.modifyProtection("req-81", p.id, p.version, 549000, 551000)
        assertTrue(result.accepted, result.message)
        assertTrue(server.requests(Rt.REQUEST_NEW_ORDER).isEmpty())
        val oco = server.requests(Rt.REQUEST_OCO_ORDER).single()
        assertEquals(List(2) { "${PROTECTION_TAG_PREFIX}req-81" }, oco.strings(Rf.USER_TAG))
        assertEquals(listOf("MESZ6", "MESZ6"), oco.strings(Rf.SYMBOL)); assertEquals(listOf("CME", "CME"), oco.strings(Rf.EXCHANGE))
        assertEquals(listOf(1, 1), oco.ints(Rf.QUANTITY))
        assertEquals(listOf(0.0, 5510.0), oco.doubles(Rf.PRICE)); assertEquals(listOf(5490.0, 0.0), oco.doubles(Rf.TRIGGER_PRICE))
        assertEquals(listOf(RithmicEnums.PRICE_STOP_MARKET, RithmicEnums.PRICE_LIMIT), oco.ints(Rf.PRICE_TYPE))
        assertEquals(listOf(RithmicEnums.SELL, RithmicEnums.SELL), oco.ints(Rf.TRANSACTION_TYPE))
        assertEquals(listOf(RithmicEnums.DURATION_DAY, RithmicEnums.DURATION_DAY), oco.ints(Rf.DURATION))
        assertEquals(listOf("simulator", "simulator"), oco.strings(Rf.TRADE_ROUTE))
        assertEquals(listOf("ACC1"), oco.strings(Rf.ACCOUNT_ID))
        runCurrent()
        val after = session.state.value.positions.single()
        assertEquals(549000, after.stopLoss); assertEquals(551000, after.takeProfit); assertTrue(after.protectionEditable)
        assertEquals(setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT), session.state.value.orders.map { it.role }.toSet())
    }

    @Test fun addingTheSecondLineCancelsTheFirstThenSendsBothAsAnOcoPair() = runTest {
        val server = FakeRithmicServer().apply { positions += positionRow(1, 5500.0); openOrders += taggedStop("S1") }
        val session = ready(server).openSession("ACC1")
        val p = session.state.value.positions.single()
        assertEquals(549000, p.stopLoss); assertEquals("S1", p.stopLossOrderId); assertTrue(p.protectionEditable)
        assertEquals(OrderRole.STOP_LOSS, session.state.value.orders.single().role)
        server.onCancel = { basket -> server.notifyOrder(basket, "cancelled") { string(Rf.COMPLETION_REASON, "Cancelled") } }
        val result = session.modifyProtection("req-82", p.id, p.version, p.stopLoss, 551000)
        assertTrue(result.accepted, result.message)
        assertEquals("S1", server.requests(Rt.REQUEST_CANCEL_ORDER).single().str(Rf.BASKET_ID))
        val oco = server.requests(Rt.REQUEST_OCO_ORDER).single()
        assertEquals(listOf(0.0, 5510.0), oco.doubles(Rf.PRICE)); assertEquals(listOf(5490.0, 0.0), oco.doubles(Rf.TRIGGER_PRICE))
        assertTrue(server.requests.indexOf(server.requests(Rt.REQUEST_CANCEL_ORDER).single()) < server.requests.indexOf(oco))
        runCurrent()
        val after = session.state.value.positions.single()
        assertEquals(549000, after.stopLoss); assertEquals(551000, after.takeProfit); assertTrue(after.protectionEditable)
        assertNotEquals("S1", after.stopLossOrderId)
    }

    @Test fun aFirstLineThatFilledWhileBeingCancelledIsNotReplaced() = runTest {
        val server = FakeRithmicServer().apply { positions += positionRow(1, 5500.0); openOrders += taggedStop("S1") }
        val session = ready(server).openSession("ACC1")
        val p = session.state.value.positions.single()
        server.onCancel = { basket -> server.fill(basket, "F9", RithmicEnums.SELL, 1, 5490.0, priceType = RithmicEnums.PRICE_STOP_MARKET) }
        val result = session.modifyProtection("req-83", p.id, p.version, p.stopLoss, 551000)
        assertFalse(result.accepted)
        assertTrue("已成交" in result.message, result.message)
        assertTrue(server.requests(Rt.REQUEST_OCO_ORDER).isEmpty())
    }

    /** Two tagged legs from different requests were never placed as one OCO: neither moves with the position. */
    @Test fun aStopAndTargetOfDifferentFamiliesAreNotPairedProtection() = runTest {
        val server = FakeRithmicServer().apply {
            positions += positionRow(1, 5500.0)
            openOrders += taggedStop("S1")
            openOrders += { limitEntry("T1", RithmicEnums.SELL, 1, 5510.0)(); string(Rf.USER_TAG, "${PROTECTION_TAG_PREFIX}second") }
        }
        val session = ready(server).openSession("ACC1")
        val p = session.state.value.positions.single()
        assertFalse(p.protectionEditable)
        assertTrue("互不关联" in p.protectionNote.orEmpty(), p.protectionNote)
        assertEquals(549000, p.stopLoss); assertEquals(551000, p.takeProfit)
        val orders = session.state.value.orders
        assertTrue(orders.all { !it.editable && it.standaloneEditable && "互不关联" in it.note.orEmpty() }, "$orders")
        assertFalse(session.modifyProtection("req-84", p.id, p.version, 548500, p.takeProfit).accepted)
    }

    /** The quote is 5500.00 / 5500.25 against a 5490 entry, and the target has been reached without filling. */
    @Test fun aStopMovesToBreakEvenAndPastItButNotThroughTheMarket() = runTest {
        val server = FakeRithmicServer().apply {
            positions += positionRow(1, 5490.0)
            openOrders += limitEntry("S1", RithmicEnums.SELL, 1, 5480.0, originalBasket = "E1", priceType = RithmicEnums.PRICE_STOP_MARKET)
            openOrders += limitEntry("T1", RithmicEnums.SELL, 1, 5500.0, originalBasket = "E1")
        }
        val session = ready(server).openSession("ACC1")
        val position = session.state.value.positions.single()
        assertFalse(session.modifyProtection("req-60", position.id, position.version, 550000, position.takeProfit).accepted, "A stop at the bid would trigger at once")
        assertTrue(server.requests(Rt.REQUEST_MODIFY_ORDER).isEmpty())
        val even = session.modifyProtection("req-61", position.id, position.version, 549000, position.takeProfit)
        assertTrue(even.accepted, even.message)
        assertEquals(5490.0, server.requests(Rt.REQUEST_MODIFY_ORDER).single().double(Rf.TRIGGER_PRICE))
        server.notifyOrder("S1", "modified") { double(Rf.TRIGGER_PRICE, 5490.0) }
        runCurrent()
        val moved = session.state.value.positions.single()
        assertEquals(549000, moved.stopLoss)
        val past = session.modifyProtection("req-62", moved.id, moved.version, 549500, moved.takeProfit)
        assertTrue(past.accepted, past.message)
    }

    @Test fun aMarketOrderTargetMustClearItsFillPrice() = runTest {
        val session = ready(FakeRithmicServer()).openSession("ACC1")
        fun target(side: Side, price: Long) = session.preview(OrderRequest("MESZ6", side, OrderKind.MARKET, 100, takeProfit = price))
        assertFalse(target(Side.BUY, 550025).valid, "A buy fills at the ask")
        assertTrue(target(Side.BUY, 550050).valid)
        assertFalse(target(Side.SELL, 550000).valid, "A sell fills at the bid")
        assertTrue(target(Side.SELL, 549975).valid)
    }

    @Test fun pendingEntryBracketLevelsShowAsProtectionAndMoveByTicks() = runTest {
        val server = FakeRithmicServer().apply {
            openOrders += limitEntry("E2", RithmicEnums.BUY, 1, 5490.0)
            bracketStops["E2"] = 20L to 1L
            bracketTargets["E2"] = 40L to 1L
        }
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        val order = session.state.value.orders.single()
        assertEquals(548500, order.request.stopLoss); assertEquals(550000, order.request.takeProfit); assertTrue(order.protectionEditable)
        assertTrue(session.previewOrderProtection("E2", 548000, 550000).valid)
        assertFalse(session.modifyOrderProtection("req-14", "E2", order.version, 548000, 550500).accepted)
        val result = session.modifyOrderProtection("req-15", "E2", order.version, 548000, 550000)
        assertTrue(result.accepted, result.message)
        val sent = server.requests(Rt.REQUEST_UPDATE_STOP_LEVEL).single()
        assertEquals("E2", sent.str(Rf.BASKET_ID)); assertEquals(20, sent.int(Rf.LEVEL)); assertEquals(40, sent.int(Rf.STOP_TICKS))
        assertTrue(server.requests(Rt.REQUEST_UPDATE_TARGET_LEVEL).isEmpty())
        runCurrent()
        assertEquals(548000, session.state.value.orders.single().request.stopLoss)
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
        val target = session.modifyOrderProtection("req-16", "E2", session.state.value.orders.single().version, 548000, 550025)
        assertTrue(target.accepted, target.message)
        assertEquals(41, server.requests(Rt.REQUEST_UPDATE_TARGET_LEVEL).single().int(Rf.TARGET_TICKS))
    }

    /** Rithmic Test answers the front-month request with "no data"; the nearest unexpired contract found by product code stands in. */
    @Test fun continuousSymbolFallsBackToTheNearestExpiryWhenTheServerHasNoFrontMonth() = runTest {
        val server = FakeRithmicServer().apply { frontMonthNoData = true }
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("MES")
        runCurrent()
        val series = broker.instruments.single { it.isContinuous }
        assertEquals("MESZ6", series.tradeSymbol)
        assertEquals(null, series.tradeSymbolStatus)
        assertTrue(server.requests(Rt.REQUEST_SEARCH_SYMBOLS).any { it.str(Rf.PRODUCT_CODE) == "MES" && it.str(Rf.EXCHANGE) == "CME" })
    }

    /** A product search spans every exchange: the login's front month lives on the exchange it can route, whatever row comes first. */
    @Test fun continuousSymbolUsesTheExchangeTheLoginCanRouteNotTheFirstListing() = runTest {
        val server = FakeRithmicServer().apply { foreignListings = mapOf("MES2" to "XEX"); deniedExchanges = setOf("XEX"); frontMonthAlias = true }
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("@MES")
        runCurrent()
        val series = broker.instruments.single { it.isContinuous }
        assertEquals("MESZ6", series.tradeSymbol)
        assertEquals(null, series.tradeSymbolStatus)
        assertEquals(setOf("CME"), server.requests(Rt.REQUEST_FRONT_MONTH).map { it.str(Rf.EXCHANGE) }.toSet())
        assertTrue(server.requests(Rt.REQUEST_REFERENCE_DATA).none { it.str(Rf.EXCHANGE) == "XEX" || it.str(Rf.SYMBOL) == "MES" })
        assertFalse(broker.instruments.any { it.symbol == "MES" || it.symbol == "MES2" })
    }

    @Test fun continuousSymbolTriesTheNextExchangeWhenOneIsRefused() = runTest {
        val server = FakeRithmicServer().apply { foreignListings = mapOf("MES2" to "XEX"); deniedExchanges = setOf("XEX"); tradeRoutesListed = false }
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("@MES")
        runCurrent()
        assertEquals("MESZ6", broker.instruments.single { it.isContinuous }.tradeSymbol)
        assertEquals(listOf("XEX", "CME"), server.requests(Rt.REQUEST_FRONT_MONTH).map { it.str(Rf.EXCHANGE) })
        // A later refresh asks the exchange that answered, not the refused one.
        broker.searchInstruments("@MES")
        runCurrent()
        assertEquals(listOf("XEX", "CME", "CME"), server.requests(Rt.REQUEST_FRONT_MONTH).map { it.str(Rf.EXCHANGE) })
    }

    @Test fun refusedFrontMonthNamesEveryExchangeItTried() = runTest {
        val server = FakeRithmicServer().apply { foreignListings = mapOf("MES2" to "XEX"); deniedExchanges = setOf("XEX", "CME") }
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("@MES")
        runCurrent()
        val status = broker.market.value.statusMessage
        assertTrue("CME：permission denied" in status && "XEX：permission denied" in status, status)
    }

    /** The add-symbol list should show what the login can trade without a search; a refused product is left out quietly. */
    @Test fun connectFillsTheInstrumentListFromTheProductsOfRoutableExchanges() = runTest {
        val server = FakeRithmicServer().apply { knownSymbols["ESZ6"] = "CME"; productCodes = mapOf("CME" to listOf("MES", "ES", "XX"), "XEX" to listOf("YY")) }
        val broker = broker(server)
        broker.connect()
        runCurrent()
        val es = broker.instruments.single { it.symbol == "@ES" }
        assertEquals("ESZ6", es.tradeSymbol)
        assertEquals("ES future 连续合约", es.name)
        assertTrue(broker.instruments.any { it.symbol == "ESZ6" })
        assertFalse(broker.instruments.any { "XX" in it.symbol || "YY" in it.symbol })
        val request = server.requests(Rt.REQUEST_PRODUCT_CODES).single()
        assertEquals("CME", request.str(Rf.EXCHANGE))
        assertEquals(true, request.bool(Rf.GIVE_TOI_PRODUCTS_ONLY))
        assertTrue(broker.market.value.statusMessage.startsWith("已连接"), broker.market.value.statusMessage)
    }

    /** Product RMS rows name what the account may trade, and they carry no exchange. */
    @Test fun theAccountsProductRowsDriveTheInstrumentList() = runTest {
        val server = FakeRithmicServer().apply { knownSymbols["ESZ6"] = "CME"; productMaxOrder = mapOf("ES" to 3, "NO" to 3); productCodes = mapOf("CME" to listOf("ZZ")) }
        val broker = broker(server)
        broker.connect()
        runCurrent()
        assertEquals("ESZ6", broker.instruments.single { it.symbol == "@ES" }.tradeSymbol)
        assertTrue(server.requests(Rt.REQUEST_FRONT_MONTH).any { it.str(Rf.SYMBOL) == "ES" && it.str(Rf.EXCHANGE) == "CME" })
        assertFalse(broker.instruments.any { it.symbol == "@NO" })
        assertTrue(server.requests(Rt.REQUEST_PRODUCT_CODES).isEmpty())
    }

    @Test fun thePreviewShowsTheProductsCommissionForBothFills() = runTest {
        val server = FakeRithmicServer().apply { productMaxOrder = mapOf("MES" to 3); productCommission = mapOf("MES" to 0.75) }
        val session = ready(server).openSession("ACC1")
        val preview = session.preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 200))
        assertTrue(preview.feesKnown)
        assertEquals(3_000_000, preview.feesMicros)
    }

    @Test fun theBalanceIsTheServersAccountBalanceNotTheOpeningCash() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        accountUpdate(server, balance = "24930.50", cash = "25000.00", closed = "-68.00", open = "0.00")
        runCurrent()
        val account = broker.openSession("ACC1").state.value
        assertEquals(24_930_500_000, account.balanceMicros)
        assertEquals(24_930_500_000, account.equityMicros)
    }

    /** The dev kit does not say which of these two the server sends; floating P&L is equity minus balance in both. */
    @Test fun openPnlCountsOnceWhicheverWayTheBalanceIsReported() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val session = broker.openSession("ACC1")
        for (reported in listOf("25100.00", "25000.00")) {
            accountUpdate(server, balance = reported, cash = "25000.00", closed = "0.00", open = "100.00")
            runCurrent()
            assertEquals(25_000_000_000, session.state.value.balanceMicros)
            assertEquals(25_100_000_000, session.state.value.equityMicros)
            assertEquals(100_000_000, session.state.value.floatingPnlMicros)
        }
    }

    /** On the real server the basket id comes on a part before the terminal one; this left the session paused. */
    @Test fun aBasketIdOnAnEarlierReplyPartConfirmsTheOrder() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val result = broker.openSession("ACC1").placeOrder("req-40", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertTrue(result.accepted, result.message)
        assertEquals("B1", result.entityId)
        runCurrent()
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
    }

    @Test fun anOrderNotificationConfirmsAReplyWithoutABasketId() = runTest {
        val server = FakeRithmicServer().apply { orderReply = FakeRithmicServer.OrderReply.ACCEPT_WITHOUT_BASKET }
        val broker = ready(server)
        val result = broker.openSession("ACC1").placeOrder("req-41", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertTrue(result.accepted, result.message)
        runCurrent()
        assertTrue(broker.market.value.tradingReady, broker.market.value.statusMessage)
    }

    /** The real trade this was found on: a short entry, then its bracket stop. */
    @Test fun aBracketStopFillPairsWithItsEntryInTheHistory() = runTest {
        val server = FakeRithmicServer().apply { fillHistoryListed = false; productMaxOrder = mapOf("MES" to 3); productCommission = mapOf("MES" to 0.75) }
        val session = ready(server).openSession("ACC1")
        server.fill("E1", "F1", RithmicEnums.SELL, 1, 5500.0)
        server.position(-1, 5500.0)
        runCurrent()
        assertTrue(session.state.value.trades.isEmpty())
        server.fill("E3", "F2", RithmicEnums.BUY, 1, 5510.0, originalBasket = "E1", origPriceType = RithmicEnums.PRICE_STOP_MARKET)
        server.position(0, 5500.0)
        runCurrent()
        val trade = session.state.value.trades.single()
        assertEquals(Side.SELL, trade.side)
        assertEquals(550000, trade.entryPrice)
        assertEquals(551000, trade.exitPrice)
        assertEquals("止损", trade.reason)
        assertEquals(-50_000_000, trade.grossPnlMicros)
        assertEquals(1_500_000, trade.feesMicros)
        assertEquals(-51_500_000, trade.netPnlMicros)
        assertEquals("ACC1:MESZ6", trade.positionId)
    }

    /** A triggered stop reports as a market order, so its sound comes from the original type. */
    @Test fun aTriggeredStopPlaysAsAStopLoss() = runTest {
        val server = FakeRithmicServer().apply { fillHistoryListed = false }
        val broker = ready(server)
        val fills = mutableListOf<TradeFill>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect { fills += it } }
        server.fill("E3", "F2", RithmicEnums.BUY, 1, 5510.0, originalBasket = "E1", origPriceType = RithmicEnums.PRICE_STOP_MARKET)
        runCurrent()
        assertEquals(OrderRole.STOP_LOSS, fills.single().role)
        collector.cancel()
    }

    private fun accountUpdate(server: FakeRithmicServer, balance: String, cash: String, closed: String, open: String) = server.plant(RithmicEnums.INFRA_PNL).push {
        int32(Rf.TEMPLATE_ID, Rt.ACCOUNT_PNL_UPDATE); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.ACCOUNT_BALANCE, balance); string(Rf.CASH_ON_HAND, cash)
        string(Rf.CLOSED_POSITION_PNL, closed); string(Rf.OPEN_POSITION_PNL, open); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
    }

    @Test fun aProductLimitTightensTheAccountWideLimit() = runTest {
        val server = FakeRithmicServer().apply { maxOrderQuantity = 10; productMaxOrder = mapOf("MES" to 3, "ES" to 1) }
        val session = ready(server).openSession("ACC1")
        assertEquals(300, session.orderCapacity(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).maximumLotsHundredths)
        assertFalse(session.preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 400)).valid)
        assertTrue(session.preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 300)).valid)
    }

    @Test fun fillHistoryCoversTheLastSevenDays() = runTest {
        val server = FakeRithmicServer()
        ready(server)
        assertEquals((FAKE_TIME / 1_000 - 7 * 86_400).toInt(), server.requests(Rt.REQUEST_SHOW_FILL_HISTORY).single().int(Rf.START_INDEX))
    }

    /** Without this the app stops on its manual search page after every login. */
    @Test fun connectLoadsAStarterInstrumentSoTheAppOpensOnItsWorkbench() = runTest {
        val broker = broker(FakeRithmicServer().apply { fillHistoryListed = false })
        broker.connect()
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.tradeSymbol)
    }

    @Test fun connectStillSucceedsWhenTheStarterInstrumentIsUnavailable() = runTest {
        val broker = broker(FakeRithmicServer().apply { knownSymbols.clear(); fillHistoryListed = false })
        broker.connect()
        assertTrue(broker.market.value.connected)
        assertTrue(broker.instruments.isEmpty())
    }

    /** Pushes name the dated contract; a continuous chart must follow them. */
    @Test fun continuousChartFollowsTradesAndBarsOfItsDatedContract() = runTest {
        val server = FakeRithmicServer()
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("MES")
        runCurrent()
        broker.watch("@MES", Timeframe.M5)
        runCurrent()
        val lastClose = FAKE_TIME / 1_000 / 300 * 300 * 1_000
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.LAST_TRADE); string(Rf.SYMBOL, "MESZ6"); double(Rf.TRADE_PRICE, 5502.0); int32(Rf.TRADE_SIZE, 3); int32(Rf.SSBOE, ((lastClose + 60_000) / 1_000).toInt()) }
        runCurrent()
        assertEquals(Candle(lastClose, 550200, 550200, 550200, 550200, 3), broker.history("@MES", Timeframe.M5).last())
        server.plant(RithmicEnums.INFRA_HISTORY).push {
            int32(Rf.TEMPLATE_ID, Rt.TIME_BAR); string(Rf.SYMBOL, "MESZ6"); int32(Rf.BAR_MARKER, ((lastClose + 300_000) / 1_000).toInt())
            double(Rf.OPEN_PRICE, 5501.0); double(Rf.HIGH_PRICE, 5503.0); double(Rf.LOW_PRICE, 5500.0); double(Rf.CLOSE_PRICE, 5502.5); int64(Rf.BAR_VOLUME, 40)
        }
        runCurrent()
        assertEquals(Candle(lastClose, 550100, 550300, 550000, 550250, 40), broker.history("@MES", Timeframe.M5).last())
    }

    private class MemoryCandleStore : CandleCacheStore {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
    }

    private suspend fun TestScope.chart(server: FakeRithmicServer, store: CandleCacheStore): RithmicBroker {
        // disconnect() cancels the broker's scope, and these tests go on with a second broker.
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val broker = RithmicBroker(credentials(), server, scope, { FAKE_TIME }, candleCacheStore = store)
        broker.connect()
        broker.searchInstruments("MES")
        runCurrent()
        broker.watch("@MES", Timeframe.M5)
        runCurrent()
        return broker
    }

    /** Expired months have no bars on the real server, so history across rolls exists only under the bare product code. */
    @Test fun continuousChartReplaysTheFrontMonthAliasAndStaysLiveOnItsDatedContract() = runTest {
        val server = FakeRithmicServer().apply { frontMonthAlias = true }
        val broker = chart(server, MemoryCandleStore())
        assertEquals(5, broker.history("@MES", Timeframe.M5).size)
        val replay = server.requests(Rt.REQUEST_TIME_BAR_REPLAY).single()
        assertEquals("MES", replay.str(Rf.SYMBOL))
        assertEquals("CME", replay.str(Rf.EXCHANGE))
        assertEquals("MESZ6", server.requests(Rt.REQUEST_TIME_BAR_UPDATE).single().str(Rf.SYMBOL))
    }

    @Test fun continuousChartWithoutAnAliasShowsItsDatedContractAndKeepsThatOutOfTheCache() = runTest {
        val store = MemoryCandleStore()
        val server = FakeRithmicServer()
        val first = chart(server, store)
        assertEquals(3, first.history("@MES", Timeframe.M5).size)
        assertTrue("MESZ6" in first.market.value.statusMessage, first.market.value.statusMessage)
        first.watch("@MES", Timeframe.M15)
        runCurrent()
        // Back on a series that fell back in this session: straight to the dated contract, so its tail still loads.
        first.watch("@MES", Timeframe.M5)
        runCurrent()
        assertEquals(listOf("MES", "MESZ6", "MES", "MESZ6", "MESZ6"), server.requests(Rt.REQUEST_TIME_BAR_REPLAY).map { it.str(Rf.SYMBOL) })
        first.disconnect()
        runCurrent()
        assertTrue(store.values.isEmpty(), store.values.keys.joinToString())

        // A later run asks the alias again, with nothing cached to hold it back to the tail.
        val restored = FakeRithmicServer().apply { frontMonthAlias = true }
        assertEquals(5, chart(restored, store).history("@MES", Timeframe.M5).size)
    }

    /** A series cached before the alias holds one dated contract, and a cached series only grows at its end. */
    @Test fun continuousChartIgnoresASeriesCachedBeforeTheAlias() = runTest {
        val store = MemoryCandleStore()
        val old = Candle(FAKE_TIME - 86_400_000, 550000, 550000, 550000, 550000, 1)
        CandleCache(store, "rithmic-${credentials().brokerId}", RITHMIC_CHART_BARS).write("@MES" to Timeframe.M5, listOf(old))
        val broker = chart(FakeRithmicServer().apply { frontMonthAlias = true }, store)
        assertEquals(5, broker.history("@MES", Timeframe.M5).size)
        assertFalse(old in broker.history("@MES", Timeframe.M5))
    }

    @Test fun cachedContinuousChartExtendsFromTheAliasWithoutFallingBack() = runTest {
        val store = MemoryCandleStore()
        val server = FakeRithmicServer().apply { frontMonthAlias = true }
        chart(server, store).disconnect()
        runCurrent()
        assertTrue(store.values.isNotEmpty())

        // Nothing newer than the cache: an empty alias reply is no reason to replay the dated contract.
        val later = FakeRithmicServer().apply { frontMonthAlias = true; aliasBars = 0 }
        val broker = chart(later, store)
        assertEquals(5, broker.history("@MES", Timeframe.M5).size)
        assertEquals(listOf("MES"), later.requests(Rt.REQUEST_TIME_BAR_REPLAY).map { it.str(Rf.SYMBOL) })
    }

    /** The watch list's history is read whole in the background, so a chart opens from the cache and asks only for the bars since. */
    @Test fun listedSeriesAreReadInTheBackgroundAndOpenFromTheCache() = runTest {
        val store = MemoryCandleStore()
        val server = FakeRithmicServer().apply { frontMonthAlias = true }
        val broker = chart(server, store)
        broker.preloadHistory(listOf("@MES" to Timeframe.M5, "@MES" to Timeframe.M15, "MESZ6" to Timeframe.H1))
        runCurrent()
        assertEquals(5, broker.history("@MES", Timeframe.M15).size)
        assertEquals(3, broker.history("MESZ6", Timeframe.H1).size)
        // The shown series stays the chart's; a continuous one replays its alias, as it does on the chart.
        assertEquals(listOf("MES" to 5, "MES" to 15, "MESZ6" to 60), server.requests(Rt.REQUEST_TIME_BAR_REPLAY).map { it.str(Rf.SYMBOL) to it.int(Rf.BAR_PERIOD) })
        assertEquals(1, server.requests(Rt.REQUEST_TIME_BAR_UPDATE).size)
        assertEquals(3, store.values.size)

        broker.watch("@MES", Timeframe.M15)
        runCurrent()
        assertEquals(5, broker.history("@MES", Timeframe.M15).size)
        val tail = server.requests(Rt.REQUEST_TIME_BAR_REPLAY).last()
        assertEquals((broker.history("@MES", Timeframe.M15).last().timeMs / 1_000).toInt(), tail.int(Rf.START_INDEX))
    }

    @Test fun cachedSeriesComeFromTheDiskAndAFailedOneStaysOffTheStatusLine() = runTest {
        val store = MemoryCandleStore()
        val saved = listOf(Candle(FAKE_TIME - 3_600_000, 550000, 550100, 549900, 550000, 7))
        CandleCache(store, "rithmic-v2-${credentials().brokerId}", RITHMIC_CHART_BARS).write("MESZ6" to Timeframe.H1, saved)
        val server = FakeRithmicServer()
        val broker = chart(server, store)
        val replays = server.requests(Rt.REQUEST_TIME_BAR_REPLAY).size
        val status = broker.market.value.statusMessage
        broker.preloadHistory(listOf("MESZ6" to Timeframe.H1, "NOPE9" to Timeframe.H1))
        runCurrent()
        assertEquals(saved, broker.history("MESZ6", Timeframe.H1))
        assertTrue(broker.history("NOPE9", Timeframe.H1).isEmpty())
        assertEquals(replays, server.requests(Rt.REQUEST_TIME_BAR_REPLAY).size)
        assertEquals(status, broker.market.value.statusMessage)
    }

    @Test fun continuousSymbolTradesTheFrontMonthContractTheServerNames() = runTest {
        val server = FakeRithmicServer()
        val broker = broker(server)
        broker.connect()
        broker.searchInstruments("MES")
        runCurrent()
        val series = broker.instruments.single { it.isContinuous }
        assertEquals("@MES", series.symbol); assertEquals("MESZ6", series.tradeSymbol); assertEquals("MESZ6", series.priceSymbol)
        assertTrue(broker.instruments.any { it.symbol == "MESH7" })
        broker.watch("@MES", Timeframe.M5)
        runCurrent()
        assertEquals(3, broker.history("@MES", Timeframe.M5).size)
        assertEquals(listOf("MES", "MESZ6"), server.requests(Rt.REQUEST_TIME_BAR_REPLAY).map { it.str(Rf.SYMBOL) })
        assertTrue(broker.market.value.quotes.containsKey("MESZ6"))
        val session = broker.openSession("ACC1")
        assertFalse(session.preview(OrderRequest("@MES", Side.BUY, OrderKind.MARKET, 100)).valid)
        val routed = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100, sourceSymbol = "@MES")
        assertTrue(session.preview(routed).valid, session.preview(routed).errors.joinToString())
        assertFalse(session.preview(routed.copy(symbol = "MESH7")).valid)
    }

    @Test fun rejectedLoginFailsConnectWithTheServerReasonAndClosesEverySocket() = runTest {
        val server = FakeRithmicServer().apply { loginRejection = "invalid credentials" }
        val broker = broker(server)
        val error = assertFailsWith<RithmicRemoteException> { broker.connect() }
        assertTrue(error.message!!.contains("invalid credentials"), error.message)
        assertFalse(broker.market.value.connected)
        assertTrue(server.sockets.all { it.closedByClient })
        assertTrue(server.requests.none { it.templateId == Rt.REQUEST_ACCOUNT_LIST })
    }

    @Test fun unknownSystemNameIsRefusedBeforeAnyCredentialIsSent() = runTest {
        val server = FakeRithmicServer().apply { systemNames = listOf("Rithmic Paper Trading") }
        val broker = broker(server)
        assertFailsWith<RithmicRemoteException> { broker.connect() }
        assertTrue(server.requests.none { it.templateId == Rt.REQUEST_LOGIN })
    }

    @Test fun droppedPlantReconnectsAndResubscribesWithoutReplayingCommands() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        assertTrue(broker.openSession("ACC1").placeOrder("req-17", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
        runCurrent()
        val before = server.sockets.size
        server.plant(RithmicEnums.INFRA_TICKER).drop()
        runCurrent()
        assertFalse(broker.market.value.connected)
        assertFalse(broker.market.value.tradingReady)
        advanceTimeBy(3_100); runCurrent()
        assertTrue(broker.market.value.connected, broker.market.value.statusMessage)
        assertEquals(before + 5, server.sockets.size)
        assertEquals(2, server.requests(Rt.REQUEST_LOGIN).count { it.int(Rf.INFRA_TYPE) == RithmicEnums.INFRA_TICKER })
        assertEquals(2, server.requests(Rt.REQUEST_MARKET_DATA_UPDATE).count { it.int(Rf.REQUEST) == RithmicEnums.REQUEST_SUBSCRIBE })
        assertEquals(2, server.requests(Rt.REQUEST_TIME_BAR_UPDATE).size)
        assertEquals(1, server.requests(Rt.REQUEST_NEW_ORDER).size)
        assertTrue(broker.market.value.quotes.containsKey("MESZ6"))
    }

    @Test fun staleOrClearedQuotesNeverAllowTrading() = runTest {
        val server = FakeRithmicServer()
        var clock = FAKE_TIME
        val broker = RithmicBroker(credentials(), server, backgroundScope, { clock })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val session = broker.openSession("ACC1")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)
        assertTrue(session.preview(request).valid)
        clock += 16_000
        assertFalse(session.preview(request).valid)
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.BEST_BID_OFFER); string(Rf.SYMBOL, "MESZ6"); uint32(Rf.PRESENCE_BITS, 2); double(Rf.ASK_PRICE, 5501.0); int32(Rf.SSBOE, (clock / 1_000).toInt()) }
        runCurrent()
        assertEquals(Quote(550000, 550100, clock / 1_000 * 1_000), broker.market.value.quotes["MESZ6"])
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.BEST_BID_OFFER); string(Rf.SYMBOL, "MESZ6"); uint32(Rf.CLEAR_BITS, 1); int32(Rf.SSBOE, (clock / 1_000).toInt()) }
        runCurrent()
        assertNull(broker.market.value.quotes["MESZ6"])
        assertFalse(session.preview(request).valid)
    }

    @Test fun tradesExtendTheFormingBarUntilTheServerClosesIt() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val lastClose = FAKE_TIME / 1_000 / 300 * 300 * 1_000
        val tradeTime = lastClose + 60_000
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.LAST_TRADE); string(Rf.SYMBOL, "MESZ6"); double(Rf.TRADE_PRICE, 5502.0); int32(Rf.TRADE_SIZE, 3); int32(Rf.SSBOE, (tradeTime / 1_000).toInt()) }
        runCurrent()
        val forming = broker.history("MESZ6", Timeframe.M5).last()
        assertEquals(Candle(lastClose, 550200, 550200, 550200, 550200, 3), forming)
        server.plant(RithmicEnums.INFRA_HISTORY).push {
            int32(Rf.TEMPLATE_ID, Rt.TIME_BAR); string(Rf.SYMBOL, "MESZ6"); int32(Rf.BAR_MARKER, ((lastClose + 300_000) / 1_000).toInt())
            double(Rf.OPEN_PRICE, 5501.0); double(Rf.HIGH_PRICE, 5503.0); double(Rf.LOW_PRICE, 5500.0); double(Rf.CLOSE_PRICE, 5502.5); int64(Rf.BAR_VOLUME, 40)
        }
        runCurrent()
        assertEquals(Candle(lastClose, 550100, 550300, 550000, 550250, 40), broker.history("MESZ6", Timeframe.M5).last())
        assertEquals(4, broker.history("MESZ6", Timeframe.M5).size)
    }

    /** Each open chart keeps its own live bars: a bar names its interval, and a trade extends every intraday chart of its contract. */
    @Test fun severalChartsStayLiveAtOnceAndEachGetsItsOwnBars() = runTest {
        val server = FakeRithmicServer()
        val broker = ready(server)
        val m15 = Timeframe.M15
        val s30 = Timeframe.ofSeconds(30)
        broker.watchAll(listOf("MESZ6" to Timeframe.M5, "MESZ6" to m15, "MESZ6" to s30))
        runCurrent()
        fun updates() = server.requests(Rt.REQUEST_TIME_BAR_UPDATE).map { Triple(it.int(Rf.REQUEST), it.int(Rf.BAR_TYPE), it.int(Rf.BAR_PERIOD)) }
        assertEquals(listOf(Triple(RithmicEnums.REQUEST_SUBSCRIBE, RithmicEnums.BAR_MINUTE, 5), Triple(RithmicEnums.REQUEST_SUBSCRIBE, RithmicEnums.BAR_MINUTE, 15),
            Triple(RithmicEnums.REQUEST_SUBSCRIBE, RithmicEnums.BAR_SECOND, 30)), updates(), "the chart already open is not subscribed again")
        assertEquals(3, broker.history("MESZ6", m15).size)
        assertEquals(3, broker.history("MESZ6", s30).size)
        assertTrue(broker.history("MESZ6", s30).zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == 30_000L }, "second bars are timed by their own period")

        // A closed 15 minute bar goes to the 15 minute chart alone.
        val before5 = broker.history("MESZ6", Timeframe.M5)
        val close15 = FAKE_TIME / 1_000 / 900 * 900 * 1_000 + 900_000
        server.plant(RithmicEnums.INFRA_HISTORY).push {
            int32(Rf.TEMPLATE_ID, Rt.TIME_BAR); string(Rf.SYMBOL, "MESZ6"); int32(Rf.BAR_TYPE, RithmicEnums.BAR_MINUTE); string(Rf.BAR_PERIOD, "15")
            int32(Rf.BAR_MARKER, (close15 / 1_000).toInt())
            double(Rf.OPEN_PRICE, 5501.0); double(Rf.HIGH_PRICE, 5503.0); double(Rf.LOW_PRICE, 5500.0); double(Rf.CLOSE_PRICE, 5502.5); int64(Rf.BAR_VOLUME, 40)
        }
        runCurrent()
        assertEquals(Candle(close15 - 900_000, 550100, 550300, 550000, 550250, 40), broker.history("MESZ6", m15).last())
        assertEquals(before5, broker.history("MESZ6", Timeframe.M5))

        // A trade extends the forming bar of every intraday chart of the contract.
        val tradeTime = FAKE_TIME / 1_000 / 300 * 300 * 1_000 + 70_000
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.LAST_TRADE); string(Rf.SYMBOL, "MESZ6"); double(Rf.TRADE_PRICE, 5504.0); int32(Rf.TRADE_SIZE, 2); int32(Rf.SSBOE, (tradeTime / 1_000).toInt()) }
        runCurrent()
        assertEquals(550400L, broker.history("MESZ6", Timeframe.M5).last().close)
        assertEquals(550400L, broker.history("MESZ6", m15).last().close)
        broker.history("MESZ6", s30).last().let { bar ->
            assertEquals(tradeTime / 30_000 * 30_000, bar.timeMs, "the trade lands in its own thirty seconds")
            assertEquals(550400L to 550400L, bar.high to bar.close)
        }

        // Closing a window ends only its own subscription; the charts that stay are not asked for again.
        val replays = server.requests(Rt.REQUEST_TIME_BAR_REPLAY).size
        broker.watchAll(listOf("MESZ6" to Timeframe.M5, "MESZ6" to s30))
        runCurrent()
        assertEquals(Triple(RithmicEnums.REQUEST_UNSUBSCRIBE, RithmicEnums.BAR_MINUTE, 15), updates().last())
        assertEquals(4, updates().size)
        val closed = broker.history("MESZ6", m15)
        server.plant(RithmicEnums.INFRA_TICKER).push { int32(Rf.TEMPLATE_ID, Rt.LAST_TRADE); string(Rf.SYMBOL, "MESZ6"); double(Rf.TRADE_PRICE, 5505.0); int32(Rf.TRADE_SIZE, 1); int32(Rf.SSBOE, (tradeTime / 1_000).toInt() + 1) }
        runCurrent()
        assertEquals(550500L, broker.history("MESZ6", s30).last().close)
        assertEquals(closed, broker.history("MESZ6", m15), "a chart that is closed keeps what it had and takes no more trades")
        assertTrue(server.requests(Rt.REQUEST_TIME_BAR_REPLAY).size <= replays + 1, "only the chart being worked on may be read again")
    }

    @Test fun orderCapacityComesFromServerRmsLimits() = runTest {
        val broker = ready(FakeRithmicServer().apply { maxOrderQuantity = 3 })
        val session = broker.openSession("ACC1")
        assertEquals(300, session.orderCapacity(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).maximumLotsHundredths)
        assertFalse(session.preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 400)).valid)
        val unknown = ready(FakeRithmicServer().apply { maxOrderQuantity = null }).openSession("ACC1").orderCapacity(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertNull(unknown.maximumLotsHundredths)
        assertNotNull(unknown.message)
    }

    @Test fun liveEnvironmentIsNotSimulatedAndDisconnectLogsOut() = runTest {
        val server = FakeRithmicServer().apply { systemNames = listOf("Rithmic 01") }
        val broker = RithmicBroker(credentials(RithmicEnvironment.LIVE), server, backgroundScope, { FAKE_TIME })
        broker.connect()
        assertFalse(broker.descriptor.simulated)
        assertEquals(BrokerEnvironment.RITHMIC_LIVE, broker.descriptor.environment)
        assertTrue(broker.descriptor.environment.live)
        broker.disconnect()
        runCurrent()
        assertTrue(server.sockets.drop(1).all { it.closedByClient })
        assertEquals(4, server.requests(Rt.REQUEST_LOGOUT).size)
        assertTrue(server.closed)
    }
}
