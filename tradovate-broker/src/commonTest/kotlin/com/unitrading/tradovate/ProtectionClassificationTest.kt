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
/** A plain exit whose version carries the app's protection tag, as Tradovate echoes the text the order was placed with. */
private fun tagged(id: Long, type: String, action: String = "Sell", tag: String = "${PROTECTION_TAG_PREFIX}t1"): Pair<JsonObject, JsonObject> =
    order(id, type, action).let { (order, version) -> order to JsonObject(version + ("text" to JsonPrimitive(tag))) }
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
        // Buy exits protect no long: a long here would have no stop or target of its own.
        ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).let { assertNull(it.stopId); assertNull(it.targetId) }
    }
    @Test fun onlyNativeBracketsCoveringAnUnfilledWorkingEntryCanMoveBeforeTheFill() {
        fun book(entryStatus: String = "Working", bracketQuantity: Long = 1, atm: Boolean = false, extraStop: Boolean = false) = EntityBook().apply {
            apply("order", json("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"$entryStatus","timestamp":"$stamp"}"""))
            apply("orderVersion", json("""{"id":1700,"orderId":700,"orderQty":1,"orderType":"Limit","price":5480}"""))
            val parent = if (atm) "" else ",\"parentId\":700"
            putOrder(order(701, "Limit", quantity = bracketQuantity, extra = parent)); putOrder(order(702, "Stop", quantity = bracketQuantity, extra = parent))
            if (extraStop) putOrder(order(703, "Stop", extra = parent))
            if (atm) {
                apply("orderStrategy", strategy())
                for ((id, label) in listOf(700L to "0", 701L to "256", 702L to "257")) apply("orderStrategyLink", link(id, label))
            }
        }
        assertTrue(ProtectionClassifier(book(), 100).pendingBracketsEditable(700))
        assertFalse(ProtectionClassifier(book(bracketQuantity = 2), 100).pendingBracketsEditable(700))
        assertFalse(ProtectionClassifier(book(entryStatus = "PendingReplace"), 100).pendingBracketsEditable(700))
        assertFalse(ProtectionClassifier(book(extraStop = true), 100).pendingBracketsEditable(700))
        val atm = ProtectionClassifier(book(atm = true), 100)
        assertEquals(OrderRole.STOP_LOSS, atm.relation(702).role)
        assertFalse(atm.pendingBracketsEditable(700), "ATM legs are priced by the strategy at the fill")
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
        // The old long's sell exits are no stop or target of the short; the short has none until one is added.
        ProtectionClassifier(book, 100).forPosition(200, Side.SELL, 1).let { assertNull(it.stopId); assertNull(it.targetId) }
    }
    @Test fun aPositionWithoutExitsCanTakeProtection() {
        val p = ProtectionClassifier(EntityBook().apply { apply("position", position()) }, 100).forPosition(200, Side.BUY, 1)
        assertTrue(p.editable)
        assertNull(p.stopId); assertNull(p.targetId); assertNull(p.revision)
    }
    @Test fun aPlainExitTheAppTaggedProtectsThePositionAcrossModifies() {
        val book = EntityBook().apply { apply("position", position()); putOrder(tagged(701, "Stop")) }
        val classifier = ProtectionClassifier(book, 100)
        assertEquals(OrderRole.STOP_LOSS, classifier.relation(701).role)
        assertTrue(classifier.relation(701).activated)
        val p = classifier.forPosition(200, Side.BUY, 1)
        assertEquals(701, p.stopId); assertNull(p.targetId); assertTrue(p.editable)
        // A modify writes a version without the text; the order it changed is still the one the app tagged.
        book.apply("orderVersion", json("""{"id":3000,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5491}"""))
        assertEquals(701, ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).stopId)
        // An untagged plain exit beside it is still unresolved and locks the position.
        book.putOrder(order(702, "Limit"))
        assertEquals(OrderRole.UNRESOLVED, ProtectionClassifier(book, 100).relation(702).role)
        assertFalse(ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1).editable)
    }
    @Test fun aTaggedExitThatDoesNotReduceThePositionProtectsNothing() {
        val book = EntityBook().apply { apply("position", position(-1)); putOrder(tagged(701, "Stop")) }
        val classifier = ProtectionClassifier(book, 100)
        assertFalse(classifier.relation(701).activated)
        val p = classifier.forPosition(200, Side.SELL, 1)
        assertNull(p.stopId); assertTrue(p.editable)
    }
    @Test fun twoTaggedExitsWithoutAnOcoLinkLockThePositionsProtection() {
        val book = EntityBook().apply {
            apply("position", position()); putOrder(tagged(701, "Stop")); putOrder(tagged(702, "Limit", tag = "${PROTECTION_TAG_PREFIX}t2"))
        }
        val p = ProtectionClassifier(book, 100).forPosition(200, Side.BUY, 1)
        assertFalse(p.editable)
        assertTrue("互不关联" in p.note.orEmpty(), p.note)
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
    @Test fun anUnlinkedExitLeavesRecognizedProtectionOnThePositionButLocked() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire); runCurrent()
        val extra = order(703, "Stop")
        wire.account.entity("order", extra.first); wire.account.entity("orderVersion", extra.second); runCurrent()
        val state = broker.openSession("100").state.value
        val p = state.positions.single()
        assertNull(p.stopLossOrderId, "The stop can no longer be singled out")
        assertFalse(p.protectionEditable)
        val roles = state.orders.associateBy { it.id }
        assertEquals(OrderRole.UNRESOLVED, roles.getValue("703").role)
        assertNull(roles.getValue("703").positionId, "An order the app cannot place stays out of the position")
        for (id in listOf("701", "702")) {
            assertEquals(p.id, roles.getValue(id).positionId, "Still recognized as this position's protection")
            assertFalse(roles.getValue(id).editable)
        }
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    /** The locked stop and target and the unresolved order each change on their own, by their id, price and size. */
    @Test fun exitsThatCannotMoveWithTheirPositionChangeOnTheirOwn() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire)
        val extra = order(703, "Stop")
        wire.account.entity("order", extra.first); wire.account.entity("orderVersion", extra.second); runCurrent()
        val session = broker.openSession("100")
        assertFalse(session.state.value.positions.single().protectionEditable)
        val orders = session.state.value.orders.associateBy { it.id }
        assertTrue(listOf("701", "702", "703").all { orders.getValue(it).standaloneEditable }, "${orders.values}")
        assertTrue(broker.capabilities.modifyOrderQuantity)
        // The 5500.00 bid: a sell stop at it would trigger at once, a sell limit at it would fill.
        assertEquals(listOf(CROSSED_ORDER_PRICE), session.previewOrderChange("703", 5_500_000_000, 100).errors)
        assertEquals(listOf(CROSSED_ORDER_PRICE), session.previewOrderChange("701", 5_500_000_000, 100).errors)
        assertFalse(session.previewOrderChange("703", 5_480_000_000, 150).valid, "whole contracts only")
        val larger = session.previewOrderChange("703", 5_480_000_000, 300)
        assertTrue(larger.valid, "${larger.errors}")
        assertTrue(larger.warnings.any { "反向持仓" in it }, "${larger.warnings}")
        val stop = orders.getValue("703")
        assertFalse(session.changeOrder("stale-703", "703", stop.version + 7, 5_480_000_000, 200).accepted)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
        val result = session.changeOrder("alone-703", "703", stop.version, 5_480_000_000, 200)
        assertTrue(result.accepted, result.message)
        val request = wire.requests.single { it.path == "order/modifyorder" }
        assertEquals(703, request.body?.long("orderId"))
        assertEquals(2, request.body?.long("orderQty"))
        assertEquals("Stop", request.body?.str("orderType"))
        assertEquals(5_480_000_000, request.body?.priceValue("stopPrice"))
    }

    /** A protected position whose stop and target move together never offers them on their own. */
    @Test fun pairedProtectionIsNotOfferedOnItsOwn() = runTest {
        val wire = MockTradovateWire()
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        inject(wire); runCurrent()
        val session = broker.openSession("100")
        assertTrue(session.state.value.positions.single().protectionEditable)
        assertTrue(session.state.value.orders.none { it.standaloneEditable })
        assertFalse(session.previewOrderChange("702", 5_480_000_000, 100).valid)
        assertFalse(session.changeOrder("paired", "702", session.state.value.orders.first { it.id == "702" }.version, 5_480_000_000, 100).accepted)
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
    /** A long of one MESZ6 at 5500 with no exits, against the 5500 / 5500.25 quote. */
    private suspend fun TestScope.bare(wire: MockTradovateWire): AccountSession {
        val broker = broker(wire)
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.account.entity("position", position()); runCurrent()
        return broker.openSession("100")
    }
    private suspend fun TestScope.withTaggedStop(wire: MockTradovateWire): AccountSession {
        val session = bare(wire)
        val (stop, version) = tagged(701, "Stop", tag = "${PROTECTION_TAG_PREFIX}first")
        wire.account.entity("order", stop); wire.account.entity("orderVersion", version); runCurrent()
        return session
    }
    private fun orderPaths(wire: MockTradovateWire) = wire.requests.map { it.path }.filter { it.startsWith("order/") }

    @Test fun aStopAddedToABarePositionIsOnePlainTaggedStop() = runTest {
        val wire = MockTradovateWire()
        val session = bare(wire)
        val p = session.state.value.positions.single()
        assertTrue(p.protectionEditable)
        val result = session.modifyProtection("add-stop", p.id, p.version, 5_490_000_000, null)
        assertTrue(result.accepted, result.message)
        assertEquals(listOf("order/placeorder"), orderPaths(wire))
        val body = wire.requests.single { it.path == "order/placeorder" }.body!!
        assertEquals("Sell", body.str("action")); assertEquals("Stop", body.str("orderType")); assertEquals(5_490_000_000, body.priceValue("stopPrice"))
        assertEquals(1, body.long("orderQty")); assertEquals("${PROTECTION_TAG_PREFIX}add-stop", body.str("text")); assertNull(body["other"])
        // The server's order, tagged as placed, is then the position's stop loss.
        val (order, version) = order(700, "Stop")
        wire.account.entity("order", order); wire.account.entity("orderVersion", JsonObject(version + ("text" to JsonPrimitive(body.str("text")!!)))); runCurrent()
        val after = session.state.value.positions.single()
        assertEquals("700", after.stopLossOrderId); assertEquals(5_490_000_000, after.stopLoss); assertTrue(after.protectionEditable)
        assertEquals(OrderRole.STOP_LOSS, session.state.value.orders.single().role)
    }
    @Test fun aStopAndTargetAddedTogetherAreOneOcoPair() = runTest {
        val wire = MockTradovateWire()
        val session = bare(wire)
        val p = session.state.value.positions.single()
        val result = session.modifyProtection("add-both", p.id, p.version, 5_490_000_000, 5_510_000_000)
        assertTrue(result.accepted, result.message)
        assertEquals(listOf("order/placeoco"), orderPaths(wire))
        val body = wire.requests.single { it.path == "order/placeoco" }.body!!
        assertEquals("Sell", body.str("action")); assertEquals("Stop", body.str("orderType")); assertEquals(5_490_000_000, body.priceValue("stopPrice"))
        val other = body["other"]!!.jsonObject
        assertEquals("Sell", other.str("action")); assertEquals("Limit", other.str("orderType")); assertEquals(5_510_000_000, other.priceValue("price"))
        assertEquals("${PROTECTION_TAG_PREFIX}add-both", other.str("text"))
        for ((id, type, peer) in listOf(Triple(711L, "Stop", 712L), Triple(712L, "Limit", 711L))) {
            val (order, version) = order(id, type, extra = ",\"ocoId\":$peer")
            wire.account.entity("order", order); wire.account.entity("orderVersion", version)
        }
        runCurrent()
        val after = session.state.value.positions.single()
        assertEquals("711", after.stopLossOrderId); assertEquals("712", after.takeProfitOrderId); assertTrue(after.protectionEditable)
        assertTrue(session.state.value.orders.all { it.positionId == after.id && it.editable })
    }
    @Test fun addingTheSecondLineCancelsTheFirstThenPlacesBothAsAnOcoPair() = runTest {
        val wire = MockTradovateWire()
        val session = withTaggedStop(wire)
        val p = session.state.value.positions.single()
        assertEquals(5_490_000_000, p.stopLoss); assertEquals("701", p.stopLossOrderId); assertTrue(p.protectionEditable)
        wire.onOrder = { path, body ->
            if (path == "order/cancelorder") wire.account.entity("order",
                json("""{"id":${body!!.long("orderId")},"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Canceled","timestamp":"$stamp"}"""))
        }
        val result = session.modifyProtection("add-target", p.id, p.version, p.stopLoss, 5_510_000_000)
        assertTrue(result.accepted, result.message)
        assertEquals(listOf("order/cancelorder", "order/placeoco"), orderPaths(wire))
        assertEquals(701, wire.requests.single { it.path == "order/cancelorder" }.body!!.long("orderId"))
        val oco = wire.requests.single { it.path == "order/placeoco" }.body!!
        assertEquals(5_490_000_000, oco.priceValue("stopPrice")); assertEquals(5_510_000_000, oco["other"]!!.jsonObject.priceValue("price"))
        assertEquals(1, oco.long("orderQty"))
    }
    @Test fun aFirstLineThatFilledWhileBeingCancelledIsNotReplaced() = runTest {
        val wire = MockTradovateWire()
        val session = withTaggedStop(wire)
        val p = session.state.value.positions.single()
        wire.onOrder = { path, _ ->
            if (path == "order/cancelorder") {
                wire.account.entity("order", json("""{"id":701,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Filled","timestamp":"$stamp"}"""))
                wire.account.entity("fill", json("""{"id":801,"orderId":701,"contractId":200,"action":"Sell","qty":1,"price":5490,"active":true,"timestamp":"$stamp"}"""))
            }
        }
        val result = session.modifyProtection("late-target", p.id, p.version, p.stopLoss, 5_510_000_000)
        assertFalse(result.accepted)
        assertTrue("已成交" in result.message, result.message)
        assertEquals(listOf("order/cancelorder"), orderPaths(wire))
    }
    @Test fun aLineIsNotRemovedHereAndTwoExistingLinesStillMoveOneAtATime() = runTest {
        val wire = MockTradovateWire()
        val session = withTaggedStop(wire)
        val p = session.state.value.positions.single()
        assertFalse(session.modifyProtection("remove", p.id, p.version, null, null).accepted)
        assertTrue(orderPaths(wire).isEmpty())
        val moved = session.modifyProtection("move", p.id, p.version, 5_491_000_000, null)
        assertTrue(moved.accepted, moved.message)
        assertEquals(listOf("order/modifyorder"), orderPaths(wire))
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
