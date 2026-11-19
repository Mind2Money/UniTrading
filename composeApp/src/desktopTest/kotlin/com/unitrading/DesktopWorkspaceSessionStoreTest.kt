package com.unitrading

import com.unitrading.app.SnapshotStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWorkspaceSessionStoreTest {
    @Test fun discardRestoresWorkspaceAndKeepsAccountData() {
        val values = mutableMapOf("chart-v1" to "old", "account" to "old-account")
        val delegate = object : SnapshotStore {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
        }
        val session = DesktopWorkspaceSessionStore(delegate) { values.remove(it) }
        assertFalse(session.hasChanges())
        session.write("chart-v1", "new")
        session.write("chart-6331-indicators-v1", "new-indicators")
        session.write("account", "new-account")
        assertTrue(session.hasChanges())
        session.discard()
        session.write("chart-v1", "delayed-background-save")
        assertEquals("old", values["chart-v1"])
        assertEquals(null, values["chart-6331-indicators-v1"])
        assertEquals("new-account", values["account"])
    }
}
