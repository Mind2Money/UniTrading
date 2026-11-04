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
        assertEquals(RithmicPlant.TEMPLATE_VERSION, login.str(Rf.TEMPLATE_VERSION))
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
        assertEquals(1, sent.int(Rf.QUANTITY)); assertEquals(RithmicEnums.PRICE_MARKET, sent.int(Rf.PRICE_TYPE)); assertEquals(RithmicEnums.BUY, sent.int(Rf.TRANSACTION_TYPE))
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
        assertEquals(16, sent.int(Rf.STOP_TICKS)); assertEquals(2, sent.int(Rf.STOP_QUANTITY))
        assertEquals(24, sent.int(Rf.TARGET_TICKS)); assertEquals(2, sent.int(Rf.TARGET_QUANTITY))
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
        assertEquals("MESZ6", server.requests(Rt.REQUEST_TIME_BAR_REPLAY).single().str(Rf.SYMBOL))
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
