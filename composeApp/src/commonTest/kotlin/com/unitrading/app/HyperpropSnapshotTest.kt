package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class HyperpropSnapshotTest {
    private val spec = Instrument("MESZ6", "MES", "futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000)
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun mapsShortAndFractionalFillWithoutInventingProtection() {
        val positions = hyperpropPositions(obj("""{"success":true,"data":[{"account_id":"a","contract":"MESZ6","quantity":-2,"avg_entry_price":5485.25,"state_version":7}]}"""), "a", listOf(spec))
        assertEquals(Side.SELL, positions.single().side); assertEquals(548525L, positions.single().entryPrice)
        assertFalse(positions.single().protectionEditable); assertEquals(7L, positions.single().version)
        val orders = hyperpropWorkingOrders(obj("""{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","quantity":3,"filled_quantity":1,"side":"buy","order_type":"limit","limit_price":5485.25,"status":"partially_filled"}]}"""), "a", listOf(spec))
        assertEquals(100L, orders.single().filledLotsHundredths); assertEquals(OrderRole.UNRESOLVED, orders.single().role)
        assertFalse(orders.single().editable)
    }
    @Test fun unknownOrUnavailableDataDoesNotAppearFlat() {
        assertFails { hyperpropPositions(obj("""{"success":false,"data":null}"""), "a", listOf(spec)) }
        assertFails { hyperpropPositions(obj("""{"success":true,"data":[{"account_id":"a","contract":"NQZ6","quantity":1,"avg_entry_price":2}]}"""), "a", listOf(spec)) }
        assertFails { hyperpropWorkingOrders(obj("""{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","quantity":1,"side":"buy","order_type":"limit","limit_price":5485.26,"status":"working"}]}"""), "a", listOf(spec)) }
    }
    @Test fun bindsOnlyExplicitlyLinkedFullPositionProtections() {
        val positions = hyperpropPositions(obj("""{"success":true,"data":[{"position_id":"p1","account_id":"a","contract":"MESZ6","quantity":1,"avg_entry_price":5485.25}]}"""), "a", listOf(spec))
        val rows = obj("""{"success":true,"data":[{"id":"sl1","position_id":"p1","bracket_type":"stop_loss","account_id":"a","contract":"MESZ6","quantity":1,"filled_quantity":0,"side":"sell","order_type":"stop","stop_price":5480,"status":"working"},{"id":"tp-other","bracket_type":"take_profit","account_id":"a","contract":"MESZ6","quantity":1,"side":"sell","order_type":"limit","limit_price":5490,"status":"working"}]}""")
        val orders = hyperpropWorkingOrders(rows, "a", listOf(spec))
        val position = hyperpropBindProtections(positions, orders).single()
        assertEquals(548000L, position.stopLoss)
        assertNull(position.takeProfit)
        assertEquals("sl1", position.stopLossOrderId)
        assertFalse(position.protectionEditable)
    }
}
