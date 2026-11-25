package com.unitrading

import com.unitrading.app.SnapshotStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopExitTest {
    private fun store(values: MutableMap<String, String>) = object : SnapshotStore {
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }

    @Test fun savingFlushesTheCurrentWorkspaceAndAccount() = runTest {
        val values = mutableMapOf("chart-v1" to "old", "account" to "old-account")
        val session = DesktopWorkspaceSessionStore(store(values)) { values.remove(it) }
        var flushes = 0
        val failure = prepareDesktopExit(true, { flushes++; session.write("chart-v1", "new"); true },
            session::discard, session::resumeWrites, { session.write("account", "new-account"); true })
        assertNull(failure)
        assertEquals(1, flushes)
        assertEquals("new", values["chart-v1"])
        assertEquals("new-account", values["account"])
    }

    @Test fun discardingRestoresWorkspaceButKeepsOtherDataAndBlocksDelayedWrites() = runTest {
        val values = mutableMapOf("chart-v1" to "old", "account" to "old-account")
        val session = DesktopWorkspaceSessionStore(store(values)) { values.remove(it) }
        session.write("chart-v1", "changed")
        var flushed = false
        val failure = prepareDesktopExit(false, { flushed = true; true }, session::discard, session::resumeWrites,
            { session.write("account", "new-account"); true })
        session.write("chart-v1", "delayed-write")
        assertNull(failure)
        assertEquals(false, flushed)
        assertEquals("old", values["chart-v1"])
        assertEquals("new-account", values["account"])
    }

    @Test fun failedSavingDoesNotExitAndDiscardFailureResumesWrites() = runTest {
        val values = mutableMapOf("chart-v1" to "old")
        val session = DesktopWorkspaceSessionStore(store(values)) { values.remove(it) }
        session.write("chart-v1", "changed")
        var accountSaveCalled = false
        val failedWorkspace = prepareDesktopExit(true, { false }, session::discard, session::resumeWrites,
            { accountSaveCalled = true; true })
        assertTrue(failedWorkspace!!.contains("保存失败"))
        assertEquals(false, accountSaveCalled)
        val failedAccount = prepareDesktopExit(false, null, session::discard, session::resumeWrites, { false })
        assertTrue(failedAccount!!.contains("账户数据保存失败"))
        session.write("chart-v1", "can-write-again")
        assertEquals("can-write-again", values["chart-v1"])
    }

    @Test fun missingWorkspaceBeforeLoginDoesNotBlockExit() = runTest {
        val values = mutableMapOf<String, String>()
        val session = DesktopWorkspaceSessionStore(store(values)) { values.remove(it) }
        assertNull(prepareDesktopExit(true, null, session::discard, session::resumeWrites, { true }))
    }
}
