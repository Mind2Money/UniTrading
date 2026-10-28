package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TradingRuntimeTest {
    @Test fun damagedStorageIsNotSilentlyOverwritten() = runTest {
        val storage = object : SnapshotStore {
            var writes = 0
            override fun read(key: String) = "not a snapshot"
            override fun write(key: String, value: String) { writes++ }
        }
        val runtime = TradingRuntime(storage, includeDemoBrokers = true)
        assertNotNull(runtime.startupError.value)
        assertFalse(runtime.save())
        assertEquals(0, storage.writes)
        runtime.resetAll()
        assertNull(runtime.startupError.value)
        assertEquals(2, storage.writes)
    }
    @Test fun diskFailurePausesAndRetainsMemoryState() = runTest {
        val storage = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("disk full") }
        }
        val runtime = TradingRuntime(storage, includeDemoBrokers = true)
        val before = runtime.brokers.first().exportState()
        assertFalse(runtime.save())
        assertTrue(runtime.paused.value)
        assertNotNull(runtime.storageError.value)
        assertEquals(before, runtime.brokers.first().exportState())
    }
    @Test fun allBrokersRoundTripThroughStorage() = runTest {
        val storage = MemorySnapshotStore()
        val runtime = TradingRuntime(storage, includeDemoBrokers = true)
        runtime.brokers.forEach { it.advance(15) }
        assertTrue(runtime.save())
        val restored = TradingRuntime(storage, includeDemoBrokers = true)
        assertNull(restored.startupError.value)
        runtime.brokers.zip(restored.brokers).forEach { (a, b) -> assertEquals(a.exportState(), b.exportState()) }
    }
}
