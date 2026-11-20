package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkspaceAccountsTest {
    @Test fun windowsRestoreTheirAccountsPerLoginWithoutChangingChartSettings() = runTest {
        val store = MemorySnapshotStore().apply { write("chart-v1", """{"favoriteMinutes":[5,15]}""") }
        val accounts = WorkspaceAccounts(store)
        assertTrue(accounts.save("login-a", mapOf("c1" to "200", "c2" to "300")))
        assertTrue(accounts.save("login-b", mapOf("c1" to "500")))
        val restored = WorkspaceAccounts(store)
        assertEquals(mapOf("c1" to "200", "c2" to "300"), restored.selections.value["login-a"])
        assertEquals(mapOf("c1" to "500"), restored.selections.value["login-b"])
        assertEquals("""{"favoriteMinutes":[5,15]}""", store.read("chart-v1"))
    }

    @Test fun unreadableAccountSettingsAreNotOverwritten() = runTest {
        val store = MemorySnapshotStore().apply { write("workspace-accounts-v1", "invalid") }
        val accounts = WorkspaceAccounts(store)
        assertFalse(accounts.readable)
        assertNotNull(accounts.error.value)
        assertFalse(accounts.save("login", mapOf("c1" to "200")))
        assertEquals("invalid", store.read("workspace-accounts-v1"))
    }
}
