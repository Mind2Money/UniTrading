package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private const val AT = 1_798_589_000_000L
private val stamp get() = kotlin.time.Instant.fromEpochMilliseconds(AT).toString()
private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
private fun position(net: Long = 1, account: Long = 100, contract: Long = 200) = json("""{"id":900,"accountId":$account,"contractId":$contract,"netPos":$net,"netPrice":5500,"timestamp":"$stamp"}""")
private fun order(id: Long, type: String, action: String = "Sell", quantity: Long = 1, account: Long = 100, contract: Long = 200, extra: String = ""): Pair<JsonObject, JsonObject> =
    json("""{"id":$id,"accountId":$account,"contractId":$contract,"action":"$action","ordStatus":"Working","timestamp":"$stamp"$extra}""") to
        json("""{"id":${id + 1000},"orderId":$id,"orderQty":$quantity,"orderType":"$type","price":5510,"stopPrice":5490}""")
private fun strategy(brackets: Int = 1, account: Long = 100, contract: Long = 200) = buildJsonObject {
    put("id", 5000); put("accountId", account); put("contractId", contract); put("action", "Buy"); put("orderStrategyTypeId", 2)
    put("params", buildJsonObject { putJsonArray("brackets") { repeat(brackets) { add(buildJsonObject { put("qty", 1); put("profitTarget", 10); put("stopLoss", -5) }) } } }.toString())
}
private fun link(order: Long, label: String) = json("""{"id":${order + 5000},"orderStrategyId":5000,"orderId":$order,"label":"$label"}""")
private fun EntityBook.putOrder(pair: Pair<JsonObject, JsonObject>) { apply("order", pair.first); apply("orderVersion", pair.second) }
private fun atmBook(): EntityBook = EntityBook().apply {
    apply("position", position())
    apply("order", json("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled","timestamp":"$stamp"}"""))
    putOrder(order(701, "Limit")); putOrder(order(702, "Stop"))
    apply("orderStrategy", strategy())
    apply("orderStrategyLink", link(700, "0")); apply("orderStrategyLink", link(701, "256")); apply("orderStrategyLink", link(702, "257"))
}

class ProtectionClassificationTest {
    @Test fun atmLinksIdentifyTargetAndStopWithoutParentOrOcoFields() {
        val book = atmBook()
        val classifier = ProtectionClassifier(book, 100)
        assertEquals(OrderRole.TAKE_PROFIT, classifier.relation(701).role)
        assertEquals(OrderRole.STOP_LOSS, classifier.relation(702).role)
        val p = classifier.forPosition(200, Side.BUY, 1)
        assertEquals(702, p.stopId)
        assertEquals(701, p.targetId)
        assertTrue(p.editable)
        assertNull(book.item("order", 701)!!["parentId"], "Projection must not invent fields in raw broker data")
        assertNull(book.item("order", 702)!!["ocoId"])
    }
    @Test fun directOcoWorksWithoutParentAndSupportsSharedOrReciprocalIds() {
        for (reciprocal in listOf(true, false)) {
            val book = EntityBook().apply {
                apply("position", position())
                putOrder(order(701, "Limit", extra = ",\"ocoId\":${if (reciprocal) 702 else 77}"))
                putOrder(order(702, "Stop", extra = ",\"ocoId\":${if (reciprocal) 701 else 77}"))
            }
            val classifier = ProtectionClassifier(book, 100)
            assertEquals(OrderRole.TAKE_PROFIT, classifier.relation(701).role)
            assertEquals(OrderRole.STOP_LOSS, classifier.relation(702).role)
            assertTrue(classifier.forPosition(200, Side.BUY, 1).editable)
        }
    }
    @Test fun shortPositionUsesBuyStopAndBuyLimitProtection() {
        val book = EntityBook().apply {
            apply("position", position(-1))
            putOrder(order(701, "Limit", "Buy", extra = ",\"ocoId\":702"))
            putOrder(order(702, "Stop", "Buy", extra = ",\"ocoId\":701"))
        }
        assertTrue(ProtectionClassifier(book, 100).forPosition(200, Side.SELL, 1).editable)
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).editable)
    }
    @Test fun unrelatedOppositeOrdersAreUnresolvedNotGuessedProtectionOrEntry() {
        val book = EntityBook().apply {
            apply("position", position())
            putOrder(order(701, "Limit")); putOrder(order(702, "Stop"))
            putOrder(order(703, "Limit", "Buy"))
        }
        val classifier = ProtectionClassifier(book, 100)
        assertEquals(OrderRole.UNRESOLVED, classifier.relation(701).role)
        assertEquals(OrderRole.UNRESOLVED, classifier.relation(702).role)
        assertFalse(classifier.entryEditable(701))
        assertTrue(classifier.entryEditable(703))
        assertNull(classifier.forPosition(200, Side.BUY, 1).stopId)
    }
    @Test fun sameParentAloneDoesNotInventAnOcoBetweenTwoIndependentExits() {
        val book = EntityBook().apply {
            apply("position", position())
            apply("order", json("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}"""))
            putOrder(order(701, "Limit", extra = ",\"parentId\":700")); putOrder(order(702, "Stop", extra = ",\"parentId\":700"))
        }
        val classifier = ProtectionClassifier(book, 100)
        assertEquals(OrderRole.STOP_LOSS, classifier.relation(702).role)
        assertFalse(classifier.forPosition(200, Side.BUY, 1).editable)
    }
    @Test fun crossAccountAndCrossContractStrategyMembersCannotBind() {
        for (scope in listOf(101L to 200L, 100L to 201L)) {
            val book = atmBook()
            book.apply("order", order(702, "Stop", account = scope.first, contract = scope.second).first)
            val classifier = ProtectionClassifier(book, 100)
            assertEquals(OrderRole.UNRESOLVED, classifier.relation(701).role)
            assertFalse(classifier.forPosition(200, Side.BUY, 1).editable)
        }
    }
    @Test fun partialStrategyUpdatesStayUnresolvedUntilComplete() {
        val book = atmBook()
        book.apply("orderStrategyLink", link(702, "257"), deleted = true)
        assertEquals(OrderRole.UNRESOLVED, ProtectionClassifier(book, 100).relation(701).role)
        book.apply("orderStrategyLink", link(702, "257"))
        assertTrue(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).editable)
        book.apply("orderStrategy", strategy(2))
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).editable)
    }
    @Test fun strategyLabelsAreOpaqueAndNotUsedAsPriceOrRoleHeuristics() {
        val book = atmBook()
        book.apply("orderStrategyLink", link(701, "any-server-label"))
        book.apply("orderStrategyLink", link(702, "another-label"))
        assertEquals(OrderRole.STOP_LOSS, ProtectionClassifier(book, 100).relation(702).role)
        assertEquals(OrderRole.TAKE_PROFIT, ProtectionClassifier(book, 100).relation(701).role)
    }
    @Test fun sizeMismatchAndProcessingOrdersAreNotEditable() {
        val book = atmBook()
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 2).editable)
        book.apply("order", json("""{"id":702,"ordStatus":"PendingReplace"}"""))
        val p = ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1)
        assertEquals(702, p.stopId)
        assertFalse(p.editable)
    }
    @Test fun partialFillsUseRemainingCoverageAndInvalidateTheRevision() {
        val book = atmBook()
        val before = ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1)
        book.apply("orderVersion", json("""{"id":3000,"orderId":702,"orderQty":2,"orderType":"Stop","stopPrice":5490}"""))
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).editable)
        book.apply("fill", json("""{"id":800,"orderId":702,"contractId":200,"qty":1,"active":true}"""))
        val classifier = ProtectionClassifier(book, 100)
        val after = classifier.forPosition(200, Side.BUY, 1)
        assertEquals(1, classifier.remainingQuantity(702))
        assertTrue(after.editable)
        assertNotEquals(before.revision, after.revision)
    }
    @Test fun multipleFamiliesDoNotSelectAnArbitraryStopOrTarget() {
        val book = atmBook()
        book.putOrder(order(801, "Limit", extra = ",\"ocoId\":802"))
        book.putOrder(order(802, "Stop", extra = ",\"ocoId\":801"))
        val p = ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1)
        assertNull(p.stopId); assertNull(p.targetId); assertFalse(p.editable)
    }
    @Test fun aFilledProtectionLegBlocksItsSiblingWhilePositionUpdateIsPending() {
        val book = atmBook()
        book.apply("order", json("""{"id":702,"ordStatus":"Filled"}"""))
        val classifier = ProtectionClassifier(book, 100)
        assertEquals(OrderRole.TAKE_PROFIT, classifier.relation(701).role)
        assertFalse(classifier.forPosition(200, Side.BUY, 1).editable)
    }
    @Test fun aFlatOrReversedPositionDoesNotEnableAnOldProtectionOrder() {
        val book = atmBook()
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 0).editable)
        book.apply("position", position(-1))
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.SELL, 1).editable)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionBrokerIntegrationTest {
    private fun TestScope.broker(wire: MockTradovateWire) = TradovateBroker(
        TradovateSession(TradovateEnvironment.DEMO, "offline-token-not-used-on-network", null, 42, "offline", 4_102_444_800_000),
        wire, backgroundScope, now = { AT })
    private suspend fun inject(wire: MockTradovateWire) {
        wire.account.entity("position", position())
        wire.account.entity("order", json("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled","timestamp":"$stamp"}"""))
        for (pair in listOf(order(701, "Limit"), order(702, "Stop"))) {
            wire.account.entity("order", pair.first); wire.account.entity("orderVersion", pair.second)
        }
        wire.account.entity("orderStrategy", strategy())
        for ((id, label) in listOf(700L to "0", 701L to "256", 702L to "257")) wire.account.entity("orderStrategyLink", link(id, label))
    }
    @Test fun atmSnapshotIsClassifiedAndStrategyEntitiesAreRequested() = runTest {
        val wire = MockTradovateWire()
        val book = atmBook()
        wire.snapshot = JsonObject(wire.snapshot + mapOf(
            "positions" to JsonArray(book.list("position")),
            "orders" to JsonArray(book.list("order")),
            "orderVersions" to JsonArray(book.list("orderVersion")),
            "orderStrategies" to JsonArray(book.list("orderStrategy")),
            "orderStrategyLinks" to JsonArray(book.list("orderStrategyLink")),
        ))
        val broker = broker(wire)
        broker.connect(); runCurrent()
        val requested = json(wire.account.bodies.getValue("user/syncrequest"))["entityTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("orderStrategy" in requested && "orderStrategyLink" in requested)
        val state = broker.openSession("100").state.value
        assertEquals("702", state.positions.single().stopLossOrderId)
        assertEquals("701", state.positions.single().takeProfitOrderId)
        assertEquals(setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT), state.orders.map { it.role }.toSet())
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    @Test fun atmStateProjectsToPositionAndTypedPendingOrdersWithoutWriting() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire); runCurrent()
        val session = broker.openSession("100")
        val p = session.state.value.positions.single()
        assertEquals(5_490_000_000, p.stopLoss)
        assertEquals(5_510_000_000, p.takeProfit)
        assertEquals("702", p.stopLossOrderId)
        assertEquals("701", p.takeProfitOrderId)
        assertTrue(p.protectionEditable)
        assertEquals(setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT), session.state.value.orders.map { it.role }.toSet())
        assertTrue(session.state.value.orders.all { it.positionId == p.id && it.editable })
        assertTrue(wire.requests.none { it.path.startsWith("order/") }, "Classification must never send an order command")
        val stop = session.state.value.orders.first { it.role == OrderRole.STOP_LOSS }
        assertFalse(session.previewOrderModification(stop.id, 5_491_000_000).valid)
        assertFalse(session.modifyOrder("wrong-entry-route", stop.id, stop.version, 5_491_000_000).accepted)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    @Test fun changedLinksInvalidateProtectionEditsAndDoNotTriggerAutomaticCommands() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire); runCurrent()
        val session = broker.openSession("100")
        val p = session.state.value.positions.single()
        wire.account.entity("orderStrategy", strategy(2)); runCurrent()
        assertFalse(session.state.value.positions.single().protectionEditable)
        assertTrue(session.state.value.positions.single().version > p.version)
        assertFalse(session.modifyProtection("stale-link", p.id, p.version, 5_491_000_000, p.takeProfit).accepted)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    @Test fun simulatedProtectionPriceEditPreservesOriginalTotalQuantityAfterPartialFill() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire)
        wire.account.entity("orderVersion", json("""{"id":3000,"orderId":702,"orderQty":2,"orderType":"Stop","stopPrice":5490}"""))
        wire.account.entity("fill", json("""{"id":800,"orderId":702,"contractId":200,"action":"Sell","qty":1,"price":5490,"active":true,"timestamp":"$stamp"}"""))
        runCurrent()
        val session = broker.openSession("100")
        val p = session.state.value.positions.single()
        assertTrue(p.protectionEditable)
        assertTrue(session.modifyProtection("offline-stop", p.id, p.version, 5_491_000_000, p.takeProfit).accepted)
        val request = wire.requests.single { it.path == "order/modifyorder" }
        assertEquals(702, request.body?.long("orderId"))
        assertEquals(2, request.body?.long("orderQty"), "Changing price must not erase already-filled quantity")
    }
}
