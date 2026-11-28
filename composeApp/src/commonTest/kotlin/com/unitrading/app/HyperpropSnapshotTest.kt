package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class HyperpropSnapshotTest {
    private val spec = Instrument("MESZ6", "MES", "futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000)
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun acceptsPositionsEnvelopeAndRejectsMissingPositions() {
        val response = obj("""{"success":true,"data":{"account_versions":{},"positions":[{"positionId":"p","account_id":"a","contract":"MESZ6","quantity":1,"avg_entry_price":5485.20}]}}""")
        assertEquals("p", hyperpropPositions(response, "a", listOf(spec)).single().id)
        assertEquals(548520L, hyperpropPositions(response, "a", listOf(spec)).single().entryPrice)
        assertTrue(hyperpropPositions(obj("""{"success":true,"data":{"account_versions":{},"positions":[]}}"""), "a", listOf(spec)).isEmpty())
        assertFails { hyperpropPositions(obj("""{"success":true,"data":{"account_versions":{}}}"""), "a", listOf(spec)) }
    }
    @Test fun mapsShortAndFractionalFillWithoutInventingProtection() {
        val positions = hyperpropPositions(obj("""{"success":true,"data":[{"positionId":"p-rest","account_id":"a","contract":"MESZ6","quantity":-2,"avg_entry_price":5485.25,"state_version":7}]}"""), "a", listOf(spec))
        assertEquals(Side.SELL, positions.single().side); assertEquals(548525L, positions.single().entryPrice)
        assertFalse(positions.single().protectionEditable); assertEquals(7L, positions.single().version); assertEquals("p-rest", positions.single().id)
        val orders = hyperpropWorkingOrders(obj("""{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","quantity":3,"filled_quantity":1,"side":"buy","order_type":"limit","limit_price":5485.25,"status":"partially_filled"}]}"""), "a", listOf(spec))
        assertEquals(100L, orders.single().filledLotsHundredths); assertEquals(OrderRole.UNRESOLVED, orders.single().role)
        assertFalse(orders.single().editable)
    }
    @Test fun unknownOrUnavailableDataDoesNotAppearFlat() {
        assertFails { hyperpropPositions(obj("""{"success":false,"data":null}"""), "a", listOf(spec)) }
        assertFails { hyperpropPositions(obj("""{"success":true,"data":[{"account_id":"a","contract":"NQZ6","quantity":1,"avg_entry_price":2}]}"""), "a", listOf(spec)) }
        assertFails { hyperpropWorkingOrders(obj("""{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","quantity":1,"side":"buy","order_type":"limit","limit_price":5485.26,"status":"working"}]}"""), "a", listOf(spec)) }
    }
    @Test fun withheldEquityAndMissingBalanceStayUnknown() {
        val snapshot = hyperpropAccountFigures(obj("""{"success":true,"data":[{"id":"a","current_balance":50000,"equity":null,"daily_pnl":-1.25}]}"""), "a", 123)
        assertEquals(50_000_000_000L, snapshot.balanceMicros)
        assertTrue(snapshot.balanceKnown); assertFalse(snapshot.equityKnown)
        assertEquals(-1_250_000L, snapshot.dailyPnl?.totalMicros)
        assertNull(snapshot.dailyPnl?.tradingDay)
        assertFails { hyperpropAccountFigures(obj("""{"success":true,"data":[]}"""), "a", 123) }
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
    @Test fun explicitPositionAndVersionAreRequiredForEditableProtection() {
        val positions = hyperpropPositions(obj("""{"success":true,"data":[{"position_id":"p1","state_version":7,"account_id":"a","contract":"MESZ6","quantity":1,"avg_entry_price":5485.25}]}"""), "a", listOf(spec))
        val orders = hyperpropWorkingOrders(obj("""{"success":true,"data":[{"id":"sl1","position_id":"p1","bracket_type":"stop_loss","updated_at":"2026-11-26T10:00:00Z","account_id":"a","contract":"MESZ6","quantity":1,"side":"sell","order_type":"stop","stop_price":5480,"status":"working"}]}"""), "a", listOf(spec))
        assertTrue(hyperpropBindProtections(positions, orders).single().protectionEditable)
        assertFalse(hyperpropBindProtections(positions, orders + orders).single().protectionEditable)
    }
}
