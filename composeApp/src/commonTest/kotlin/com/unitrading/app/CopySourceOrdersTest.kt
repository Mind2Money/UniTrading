package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopySourceOrdersTest {
    private fun intent(id: String) = CopySourceOrder("tradovate-demo-42", "leader", id)

    @Test fun recordsTheAppOrderBeforeAcceptingItsBrokerId() = runTest {
        val storage = MemorySnapshotStore()
        val source = CopySourceOrders(storage)
        assertTrue(source.begin(intent("request-1")))
        assertFalse(source.begin(intent("request-1")))
        assertTrue(source.accepted("request-1", "order-1"))
        val restored = CopySourceOrders(storage).read()!!
        assertEquals(CopySourceState.ACCEPTED, restored.single().state)
        assertEquals("order-1", restored.single().brokerOrderId)
        assertFalse(source.accepted("request-1", "order-2"))
    }

    @Test fun uncertainOrderIsNeverAutomaticallyAcceptedAgain() = runTest {
        val source = CopySourceOrders(MemorySnapshotStore())
        assertTrue(source.begin(intent("a")))
        assertTrue(source.uncertain("a"))
        assertFalse(source.accepted("a", "order-a"))
        assertEquals(CopySourceState.UNCERTAIN, source.read()!!.single().state)
    }

    @Test fun cannotBindOneBrokerOrderToTwoRequests() = runTest {
        val source = CopySourceOrders(MemorySnapshotStore())
        assertTrue(source.begin(intent("a")))
        assertTrue(source.begin(intent("b")))
        assertTrue(source.accepted("a", "order-1"))
        assertFalse(source.accepted("b", "order-1"))
    }

    @Test fun unreadableOrUnwritableJournalDoesNotReportSuccess() = runTest {
        val brokenRead = object : SnapshotStore {
            override fun read(key: String): String = "{invalid"
            override fun write(key: String, value: String) = error("must not overwrite")
        }
        val brokenWrite = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        val unreadable = CopySourceOrders(brokenRead)
        assertNull(unreadable.read())
        assertFalse(unreadable.begin(intent("a")))
        val unwritable = CopySourceOrders(brokenWrite)
        assertFalse(unwritable.begin(intent("b")))
        assertTrue(unwritable.read()!!.isEmpty())
    }
}
