package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import com.unitrading.broker.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ReplayRecordsTest {
    @Test fun recordsSurviveReloadAndUpdatesDoNotDuplicateSessions() {
        val store = MemorySnapshotStore()
        val records = ReplayRecords(store)
        val trade = ClosedTrade("t1", "p1", "MES", Side.BUY, 100, 100, 101, 1, 2, 50, "close")
        val first = ReplayRecord("id1", "MES", "USD", 50_000_000L, 3, listOf(trade))
        records.save(first)
        records.save(first.copy(savedAtMs = 4))
        records.save(ReplayRecord("id2", "MNQ", "USD", 10, 5, emptyList()))
        assertEquals(listOf(first.copy(savedAtMs = 4), ReplayRecord("id2", "MNQ", "USD", 10, 5, emptyList())), ReplayRecords(store).list())
    }

    @Test fun damagedStorageIsNotSilentlyOverwritten() {
        val store = MemorySnapshotStore().apply { write(ReplayRecords.KEY, "broken") }
        assertFails { ReplayRecords(store).save(ReplayRecord("id", "MES", "USD", 1, 2, emptyList())) }
        assertEquals("broken", store.read(ReplayRecords.KEY))
    }

    @Test fun discardingOneSessionPreservesOtherSessionsAndStoredEquity() {
        val store = MemorySnapshotStore()
        val records = ReplayRecords(store)
        val saved = ReplayRecord("keep", "MES", "USD", 50_000_000L, 3, emptyList(), status = "失败",
            equity = listOf(ReplayEquityPoint(2, 48_000_000L)))
        records.save(saved)
        records.save(saved.copy(id = "discard"))
        records.delete("discard")
        assertEquals(listOf(saved), ReplayRecords(store).list())
    }

    @Test fun importsLegacySessionsWithoutOverwritingTheOldVersion() {
        val legacy = """[{"id":"old","symbol":"MES","currency":"USD","initialMicros":50000000,"savedAtMs":1,"trades":[]}]"""
        val store = MemorySnapshotStore().apply { write(ReplayRecords.LEGACY_KEY, legacy) }
        val records = ReplayRecords(store)
        assertEquals("old", records.list().single().id)
        records.save(ReplayRecord("new", "MES", "USD", 50_000_000L, 2, emptyList()))
        assertEquals(listOf("old", "new"), records.list().map { it.id })
        assertEquals(legacy, store.read(ReplayRecords.LEGACY_KEY))
    }
    @Test fun mergedLegacySessionsStayDeleted() {
        val legacy = """[{"id":"old","symbol":"MES","currency":"USD","initialMicros":50000000,"savedAtMs":1,"trades":[]}]"""
        val store = MemorySnapshotStore().apply { write(ReplayRecords.LEGACY_KEY, legacy) }
        val records = ReplayRecords(store)
        records.save(ReplayRecord("new", "MES", "USD", 50_000_000L, 2, emptyList()))
        records.delete("old")
        assertEquals(listOf("new"), ReplayRecords(store).list().map { it.id })
    }

}
