package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.*
import kotlin.test.*

class OrderCapacityTest {
    private fun result(reason: String) = buildJsonObject { put("rejectReason", reason) }
    // Shape observed using saved Demo credentials: successful checks omit rejectReason.
    private fun implicitSuccess() = Json.parseToJsonElement("""{"details":{
        "beforeInitialMarginReq":0,"beforeInitialFullMarginReq":0,"beforeOpenCollateralReq":0,"beforeNetPos":0,
        "isExitOrder":false,"initialMarginReq":50,"initialFullMarginReq":2000,"openCollateralReq":0,
        "totalCash":25000,"futuresOpenPnL":0,"optionsOpenPnL":0,"riskCredit":0,"netLiqValue":25000,
        "futureCommAndFees":1,"totalCashRequired":51,"excess":24949,"hypoLongPos":1,"hypoShortPos":0},"fees":{}}""").jsonObject

    @Test fun realServerSuccessWithoutRejectReasonFindsTheQuantityBoundary() = runTest {
        assertEquals(500, discoverOrderCapacity { quantity ->
            if (quantity <= 5) implicitSuccess() else result("MaxPosLimitReached")
        }.maximumLotsHundredths)
        // An explicit rejection always overrides the presence of risk details.
        assertEquals(0, discoverOrderCapacity { JsonObject(implicitSuccess() + result("NotEnoughLiquidity")) }.maximumLotsHundredths)
    }

    @Test fun anUnchangedLimitIsConfirmedWithTwoChecks() = runTest {
        val calls = mutableListOf<Long>()
        assertEquals(500, discoverOrderCapacity(500) { quantity ->
            calls.add(quantity)
            if (quantity <= 5) implicitSuccess() else result("MaxPosLimitReached")
        }.maximumLotsHundredths)
        assertEquals(listOf(5L, 6L), calls)
    }

    @Test fun aChangedLimitFallsBackToTheFullSearch() = runTest {
        for ((known, actual) in listOf(500L to 3L, 300L to 9L, 0L to 4L, null to 4L)) {
            assertEquals(actual * 100, discoverOrderCapacity(known) { quantity -> result(if (quantity <= actual) "Success" else "MaxPosLimitReached") }.maximumLotsHundredths)
        }
        assertEquals(0, discoverOrderCapacity(500) { result("MaxPosLimitReached") }.maximumLotsHundredths)
        assertNull(discoverOrderCapacity(500) { result("NoQuote") }.maximumLotsHundredths, "Uncertainty is not a confirmation")
    }

    @Test fun missingReasonStillRequiresACompleteNumericRiskEvaluation() = runTest {
        val success = implicitSuccess()
        val details = success["details"]!!.jsonObject
        for (invalid in listOf(JsonObject(emptyMap()), buildJsonObject { putJsonObject("details") {} },
            JsonObject(success + ("details" to JsonObject(details - "excess"))),
            JsonObject(success + ("details" to JsonObject(details + ("excess" to JsonPrimitive("24949"))))),
            JsonObject(success + ("errorText" to JsonPrimitive("denied"))))) {
            assertNull(discoverOrderCapacity { invalid }.maximumLotsHundredths)
        }
    }

    @Test fun staleLocalQuotesDoNotBlockServerCapacityButStillBlockTradingPreview() = runTest {
        var now = 1_798_589_000_000L
        val wire = MockTradovateWire().apply {
            capacityCheck = { body ->
                if (body.array("orders").single().long("orderQty")!! <= 5) implicitSuccess() else result("MaxPosLimitReached")
            }
        }
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO, "test-access-token-1234567890", null, 42, "test", 4102444800000),
            wire, backgroundScope, { now })
        broker.connect()
        broker.watch("MESZ6", Timeframe.M5)
        runCurrent()
        now += 60_000
        val session = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)
        assertEquals(500, session.orderCapacity(request).maximumLotsHundredths)
        assertFalse(session.preview(request).valid)
        assertTrue(wire.requests.filter { it.path.startsWith("order/") }.all { it.path == "order/dryrun" })
        assertTrue(session.state.value.positions.isEmpty())
        assertTrue(session.state.value.orders.isEmpty())
    }

    @Test fun findsTheTighterMarginAndRiskBoundIncludingBeyondTheOldThousandContractCap() = runTest {
        for ((funds, perContract, limit) in listOf(Triple(2500L, 500L, 10L), Triple(10000L, 500L, 5L), Triple(50L, 500L, 5L),
            Triple(500L, 500L, 5L), Triple(2_000_000L, 500L, 2500L))) {
            val calls = mutableListOf<Long>()
            val capacity = discoverOrderCapacity { quantity ->
                calls.add(quantity)
                result(when {
                    quantity > limit -> "MaxPosLimitReached"
                    quantity > funds / perContract -> "NotEnoughLiquidity"
                    else -> "Success"
                })
            }
            val expected = minOf(funds / perContract, limit)
            assertEquals(expected * 100, capacity.maximumLotsHundredths)
            assertTrue(calls.size < 30)
            assertTrue(calls.all { it in 1..Int.MAX_VALUE.toLong() })
        }
    }

    @Test fun uncertaintyDoesNotMasqueradeAsZeroOrAContractLimit() = runTest {
        for (reason in listOf("Unauthorized", "NoQuote", "RiskCheckTimeout", "Unsupported", "UnknownReason", "MaxPosLimitMisconfigured")) {
            assertNull(discoverOrderCapacity { result(reason) }.maximumLotsHundredths)
        }
        assertNull(discoverOrderCapacity { buildJsonObject {} }.maximumLotsHundredths)
        assertNull(discoverOrderCapacity { throw TradovateRemoteException(429, "rate limited") }.maximumLotsHundredths)
        assertFailsWith<CancellationException> { discoverOrderCapacity { throw CancellationException() } }
        assertEquals(0, discoverOrderCapacity { result("TradingLocked") }.maximumLotsHundredths)
    }

    @Test fun changingCapacityDuringSearchDiscardsTheBoundary() = runTest {
        var calls = 0
        assertNull(discoverOrderCapacity { quantity ->
            calls++
            result(if (quantity <= if (calls <= 5) 5 else 3) "Success" else "MaxOrderQtyLimitReached")
        }.maximumLotsHundredths)
    }

    @Test fun liquidationOnlyStillAllowsTheServerApprovedClosingQuantity() = runTest {
        assertEquals(300, discoverOrderCapacity { quantity -> result(if (quantity <= 3) "Success" else "LiquidationOnly") }.maximumLotsHundredths)
        var calls = 0
        assertNull(discoverOrderCapacity { result(if (++calls == 1) "Success" else "TradingLocked") }.maximumLotsHundredths)
    }

    @Test fun dryRunUsesTheSelectedAccountContractSideAndPriceWithoutCreatingProtectionOrders() {
        val request = OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 5500000000, 5501000000, 5499000000)
        val body = capacityBody(101, 200, request, 5)
        assertEquals(101, body.long("accountId"))
        val order = body.array("orders").single()
        assertEquals(200, order.long("contractId"))
        assertEquals("Sell", order.str("action"))
        assertEquals("Stop", order.str("orderType"))
        assertEquals(5, order.long("orderQty"))
        assertEquals(5500000000, order.priceValue("stopPrice"))
        assertEquals(setOf("contractId", "action", "orderQty", "orderType", "stopPrice"), order.keys)
    }

    @Test fun brokerUsesReadOnlyRiskChecksAndRecomputesForAccountAndDirection() = runTest {
        val wire = MockTradovateWire()
        wire.capacityCheck = { body ->
            val order = body.array("orders").single()
            val maximum = if (body.long("accountId") == 101L) 2 else if (order.str("action") == "Sell") 8 else 5
            result(if (order.long("orderQty")!! <= maximum) "Success" else "MaxTotalPosLimitReached")
        }
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO, "test-access-token-1234567890", null, 42, "test", 4102444800000),
            wire, backgroundScope, { 1_798_589_000_000L })
        broker.connect()
        broker.watch("MESZ6", Timeframe.M5)
        runCurrent()
        val buy = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 99900)
        assertEquals(500, broker.openSession("100").orderCapacity(buy).maximumLotsHundredths)
        assertEquals(800, broker.openSession("100").orderCapacity(buy.copy(side = Side.SELL)).maximumLotsHundredths)
        assertEquals(200, broker.openSession("101").orderCapacity(buy).maximumLotsHundredths)
        assertTrue(wire.requests.filter { it.path.startsWith("order/") }.all { it.path == "order/dryrun" })
        assertTrue(broker.openSession("100").state.value.positions.isEmpty())
        assertTrue(broker.openSession("100").state.value.orders.isEmpty())
        var pushed = false
        wire.capacityCheck = {
            if (!pushed) {
                pushed = true
                backgroundScope.launch {
                    wire.account.entity("cashBalance", Json.parseToJsonElement("""{"id":1,"accountId":100,"currencyId":1,"amount":100}""").jsonObject)
                }
            }
            result("Success")
        }
        assertNull(broker.openSession("100").orderCapacity(buy).maximumLotsHundredths)
    }
}
