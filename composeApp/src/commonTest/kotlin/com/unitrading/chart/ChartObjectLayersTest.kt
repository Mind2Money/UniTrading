package com.unitrading.chart

import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.SnapshotStore
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ChartObjectLayersTest {
    @Test fun successiveFrontAndBackMovesRespectActualOrderAndPersist() = runBlocking {
        val store = MemorySnapshotStore()
        val layers = ChartObjectLayers(store)
        val a = drawingLayerKey("same")
        val b = indicatorLayerKey("same")
        assertTrue(layers.move("MES", a, listOf("same"), listOf("same"), true))
        assertTrue(layers.move("MES", b, listOf("same"), listOf("same"), true))
        assertTrue(layers.forSymbol("MES").getValue(b) > layers.forSymbol("MES").getValue(a))
        assertTrue(layers.forSymbol("MES").getValue(a) > 0)
        assertTrue(layers.move("MES", a, listOf("same"), listOf("same"), false))
        assertTrue(layers.move("MES", b, listOf("same"), listOf("same"), false))
        val ranks = layers.forSymbol("MES")
        assertTrue(ranks.getValue(b) < ranks.getValue(a))
        assertTrue(ranks.getValue(a) < 0)
        assertEquals(ranks, ChartObjectLayers(store).forSymbol("MES"))
        assertTrue(layers.forSymbol("NQ").isEmpty())
    }

    @Test fun hiddenAndRestoredObjectsKeepTheirLayerAndRenumberingCannotOverflow() = runBlocking {
        val store = MemorySnapshotStore()
        store.write("chart-object-layers-v1", "{\"MES\":{\"drawing:a\":9223372036854775807}}")
        val layers = ChartObjectLayers(store)
        assertTrue(layers.move("MES", drawingLayerKey("b"), emptyList(), listOf("a", "b"), true))
        assertTrue(layers.forSymbol("MES").getValue(drawingLayerKey("b")) > layers.forSymbol("MES").getValue(drawingLayerKey("a")))
        assertTrue(layers.move("MES", drawingLayerKey("a"), emptyList(), listOf("a", "b"), false))
        assertTrue(layers.forSymbol("MES").getValue(drawingLayerKey("a")) < 0)
    }

    @Test fun failedWriteDoesNotPretendTheOrderWasSaved() = runBlocking {
        val store = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("disk error") }
        }
        val layers = ChartObjectLayers(store)
        assertFalse(layers.move("MES", drawingLayerKey("a"), emptyList(), listOf("a"), true))
        assertTrue(layers.forSymbol("MES").isEmpty())
        assertNotNull(layers.error)
    }
}
