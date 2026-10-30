package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

private const val SOUND_TIME = 1_798_589_000_000L
private fun soundJson(value: String) = Json.parseToJsonElement(value).jsonObject
private fun soundOrder(id: Long, type: String, account: Long = 100, parent: Boolean = true): Pair<JsonObject, JsonObject> =
    soundJson("""{"id":$id,"accountId":$account,"contractId":200,"action":"Sell","ordStatus":"Working"${if (parent) ",\"parentId\":700" else ""}}""") to
        soundJson("""{"id":${id + 1000},"orderId":$id,"orderQty":2,"orderType":"$type","price":5510,"stopPrice":5490}""")
private fun soundBook(parent: Boolean = true): EntityBook = EntityBook().apply {
    apply("position", soundJson("""{"id":900,"accountId":100,"contractId":200,"netPos":2}"""))
    if (parent) apply("order", soundJson("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}"""))
    for ((order, version) in listOf(soundOrder(701, "Limit", parent = parent), soundOrder(702, "Stop", parent = parent))) {
        apply("order", order); apply("orderVersion", version)
    }
}
private fun soundFill(order: Long = 702, id: Long = 800, time: Long = SOUND_TIME + 1000, active: Boolean = true) =
    soundJson("""{"id":$id,"orderId":$order,"contractId":200,"action":"Sell","qty":1,"price":5490,"active":$active,"timestamp":"${Instant.fromEpochMilliseconds(time)}"}""")
private fun props(type: String, value: JsonObject, deleted: Boolean = false) = buildJsonObject {
    put("entityType", type); put("eventType", if (deleted) "Deleted" else "Updated"); put("entity", value)
}
private fun TradeFillTracker.apply(book: EntityBook, type: String, value: JsonObject, deleted: Boolean = false): List<TradeFill> {
    val data = props(type, value, deleted)
    book.event(data)
    return event(book, setOf(100), data)
}

class TradeFillTrackerTest {
    @Test fun submittedMarketEntryWaitsForAnActualFill() {
        val book = EntityBook()
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.prepareEntry(book, 100, 200, "Sell", 1, "UT-entry")
        tracker.acceptEntry(100, "UT-entry", 702)
        assertTrue(tracker.drain(book, setOf(100)).isEmpty())
        val (order, version) = soundOrder(702, "Market", parent = false)
        tracker.apply(book, "order", JsonObject(order + ("ordStatus" to JsonPrimitive("Filled"))))
        tracker.apply(book, "orderVersion", version)
        assertEquals(OrderRole.ENTRY, tracker.apply(book, "fill", soundFill()).single().role)
        assertTrue(tracker.apply(book, "fill", soundFill(id = 801)).isEmpty())
    }

    @Test fun preexistingPendingEntryIsRecognizedButACompletedManualCloseIsNot() {
        for (closing in listOf(false, true)) {
            val book = soundBook(parent = false)
            if (!closing) book.apply("position", soundJson("""{"id":900,"netPos":0}"""))
            val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
            tracker.apply(book, "position", soundJson("""{"id":900,"netPos":0}"""))
            tracker.apply(book, "order", soundJson("""{"id":702,"ordStatus":"Filled"}"""))
            val result = tracker.apply(book, "fill", soundFill())
            if (closing) assertTrue(result.isEmpty()) else assertEquals(OrderRole.ENTRY, result.single().role)
        }
    }

    @Test fun reversalSoundsOnlyWhenFillsExceedTheQuantityBeingClosed() {
        val book = soundBook(parent = false)
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.prepareEntry(book, 100, 200, "Sell", 3, "UT-reverse")
        tracker.acceptEntry(100, "UT-reverse", 702)
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
        assertTrue(tracker.apply(book, "fill", soundFill(id = 801)).isEmpty())
        val opening = tracker.apply(book, "fill", soundFill(id = 802)).single()
        assertEquals(OrderRole.ENTRY, opening.role)
        assertEquals("802", opening.executionId)
    }

    @Test fun reduceOnlyAndRejectedRequestsDoNotBecomeEntrySounds() {
        val book = soundBook(parent = false)
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.prepareEntry(book, 100, 200, "Sell", 2, "UT-reduce")
        tracker.acceptEntry(100, "UT-reduce", 702)
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
        tracker.prepareEntry(book, 100, 200, "Sell", 3, "UT-rejected")
        tracker.rejectEntry(100, "UT-rejected")
        tracker.acceptEntry(100, "UT-rejected", 702)
        assertTrue(tracker.apply(book, "fill", soundFill(id = 801)).isEmpty())
    }

    @Test fun terminalAtmExitRemainsProtectionAfterThePositionDisappears() {
        val book = soundBook(parent = false).apply {
            apply("order", soundJson("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}"""))
            apply("orderStrategy", soundJson("""{"id":5000,"accountId":100,"contractId":200,"action":"Buy","orderStrategyTypeId":2,"params":{"brackets":[{"qty":2,"profitTarget":10,"stopLoss":-5}]}}"""))
            for (id in 700..702) apply("orderStrategyLink", soundJson("""{"id":$id,"orderId":$id,"orderStrategyId":5000}"""))
        }
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.apply(book, "position", soundJson("""{"id":900,"netPos":0}"""))
        tracker.apply(book, "order", soundJson("""{"id":702,"ordStatus":"Filled"}"""))
        assertEquals(OrderRole.STOP_LOSS, tracker.apply(book, "fill", soundFill()).single().role)
    }
    @Test fun onlyConfirmedFillsEmitOncePerOrderIncludingPartialFills() {
        for ((order, role) in listOf(701L to OrderRole.TAKE_PROFIT, 702L to OrderRole.STOP_LOSS)) {
            val book = soundBook()
            val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
            assertTrue(tracker.apply(book, "order", soundJson("""{"id":$order,"ordStatus":"Filled"}""")).isEmpty())
            val event = tracker.apply(book, "fill", soundFill(order)).single()
            assertEquals(TradeFill("100", "$order", "800", role), event)
            assertTrue(tracker.apply(book, "fill", soundFill(order)).isEmpty())
            assertTrue(tracker.apply(book, "fill", soundFill(order, id = 801)).isEmpty())
        }
    }

    @Test fun baselineReconnectionAndHistoricalCorrectionsStaySilent() {
        val book = soundBook().apply { apply("fill", soundFill(time = SOUND_TIME - 5000)) }
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
        assertTrue(tracker.apply(book, "fill", soundFill(id = 801)).isEmpty())
        tracker.baseline(book, setOf(100), SOUND_TIME + 2000)
        assertTrue(tracker.apply(book, "fill", soundFill(id = 802)).isEmpty())
        assertTrue(tracker.apply(book, "fill", soundFill(order = 701, id = 900, time = SOUND_TIME - 10_000)).isEmpty())
    }

    @Test fun fillCanArriveBeforeItsOrderVersionAndParent() {
        val book = EntityBook()
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
        val (order, version) = soundOrder(702, "Stop")
        assertTrue(tracker.apply(book, "order", JsonObject(order + ("ordStatus" to JsonPrimitive("Filled")))).isEmpty())
        assertTrue(tracker.apply(book, "orderVersion", version).isEmpty())
        val result = tracker.apply(book, "order", soundJson("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}"""))
        assertEquals(OrderRole.STOP_LOSS, result.single().role)
    }

    @Test fun standaloneEntryOrdersManualClosesAndCancellationDoNotPlay() {
        val book = soundBook(parent = false)
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
        assertTrue(tracker.apply(book, "fill", soundFill(701, id = 801)).isEmpty())
        assertTrue(tracker.apply(book, "order", soundJson("""{"id":701,"ordStatus":"Cancelled"}""")).isEmpty())
        val (order, version) = soundOrder(703, "Market", parent = false)
        tracker.apply(book, "order", order); tracker.apply(book, "orderVersion", version)
        assertTrue(tracker.apply(book, "fill", soundFill(703, id = 802)).isEmpty())
    }

    @Test fun invalidOrForeignFillsAreNotReported() {
        val book = soundBook()
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        assertTrue(tracker.apply(book, "fill", soundFill(active = false)).isEmpty())
        assertTrue(tracker.apply(book, "fill", JsonObject(soundFill(id = 801) + ("contractId" to JsonPrimitive(201)))).isEmpty())
        assertTrue(tracker.apply(book, "fill", JsonObject(soundFill(id = 802) + ("action" to JsonPrimitive("Buy")))).isEmpty())
        tracker.apply(book, "order", soundJson("""{"id":702,"accountId":999}"""))
        assertTrue(tracker.apply(book, "fill", soundFill(id = 803)).isEmpty())
    }

    @Test fun previouslyConfirmedOcoStillIdentifiesAFillAfterPositionGoesFlat() {
        val book = soundBook(parent = false).apply {
            apply("order", soundJson("""{"id":701,"ocoId":702}"""))
            apply("order", soundJson("""{"id":702,"ocoId":701}"""))
        }
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.apply(book, "position", soundJson("""{"id":900,"netPos":0}"""))
        tracker.apply(book, "order", soundJson("""{"id":702,"ordStatus":"Filled"}"""))
        tracker.apply(book, "order", soundJson("""{"id":701,"ordStatus":"Cancelled"}"""))
        assertEquals(OrderRole.STOP_LOSS, tracker.apply(book, "fill", soundFill()).single().role)
    }

    @Test fun changedProtectionIdentityCannotUseACachedClassification() {
        val book = soundBook()
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.apply(book, "order", soundJson("""{"id":702,"parentId":9999}"""))
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
    }

    @Test fun deletedPendingFillDoesNotReappearWhenMetadataArrives() {
        val book = EntityBook()
        val tracker = TradeFillTracker().apply { baseline(book, setOf(100), SOUND_TIME) }
        tracker.apply(book, "fill", soundFill())
        tracker.apply(book, "fill", soundJson("""{"id":800}"""), deleted = true)
        val full = soundBook()
        for (type in listOf("order", "orderVersion", "position")) for (value in full.list(type)) {
            assertTrue(tracker.apply(book, type, value).isEmpty())
        }
        assertTrue(tracker.apply(book, "fill", soundFill()).isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionSoundBrokerTest {
    @Test fun acceptedEntryWaitsForServerExecutionAndPartialFillsStaySilent() = runTest {
        val wire = MockTradovateWire()
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO, "offline-sound-test-token-not-used-on-network", null, 42, "test", 4_102_444_800_000),
            wire, backgroundScope, now = { SOUND_TIME })
        val events = mutableListOf<TradeFill>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect(events::add) }
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 200)
        val result = broker.openSession("100").placeOrder("sound-entry", request)
        assertTrue(result.accepted, result.message)
        assertTrue(events.isEmpty(), "Server acceptance is not an execution")
        val fill = JsonObject(soundFill(order = 700) + ("action" to JsonPrimitive("Buy")))
        wire.account.entity("fill", fill)
        wire.account.entity("order", soundJson("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}"""))
        runCurrent()
        assertEquals(OrderRole.ENTRY, events.single().role)
        wire.account.entity("fill", fill)
        wire.account.entity("fill", JsonObject(fill + ("id" to JsonPrimitive(801))))
        runCurrent()
        assertEquals(1, events.size)
        assertEquals(1, wire.requests.count { it.method == "POST" && it.path == "order/placeorder" })
    }

    @Test fun adapterEmitsLiveTradeFillButNeverSnapshotReplay() = runTest {
        val wire = MockTradovateWire()
        val book = soundBook()
        wire.snapshot = JsonObject(wire.snapshot + mapOf(
            "orders" to JsonArray(book.list("order")), "orderVersions" to JsonArray(book.list("orderVersion")),
        ))
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO, "offline-sound-test-token-not-used-on-network", null, 42, "test", 4_102_444_800_000),
            wire, backgroundScope, now = { SOUND_TIME })
        val events = mutableListOf<TradeFill>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect(events::add) }
        broker.connect(); runCurrent()
        assertTrue(events.isEmpty())
        wire.account.entity("order", soundJson("""{"id":702,"ordStatus":"Filled"}"""))
        wire.account.entity("fill", soundFill()); runCurrent()
        assertEquals(OrderRole.STOP_LOSS, events.single().role)
        wire.account.entity("fill", soundFill()); wire.account.entity("fill", soundFill(id = 801)); runCurrent()
        assertEquals(1, events.size)
        wire.snapshot = JsonObject(wire.snapshot + ("fills" to JsonArray(listOf(soundFill()))))
        broker.resynchronize(); runCurrent()
        wire.account.entity("fill", soundFill()); runCurrent()
        assertEquals(1, events.size)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
}
