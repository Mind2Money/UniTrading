package com.unitrading.app

import com.unitrading.broker.AccountInfo
import com.unitrading.broker.BrokerDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AccountAliasesTest {
    private val login = BrokerDescriptor("tradovate-demo-1", "Tradovate Demo", "test")
    private val otherLogin = BrokerDescriptor("tradovate-demo-2", "Tradovate Demo", "test")
    private val account = AccountInfo("42", "TAKEPROFIT813235899")

    @Test fun brokerNamesShowUntilAnAliasIsSetAndAliasesSurviveARestart() = runTest {
        val store = MemorySnapshotStore()
        val controller = AccountAliasesController(store)
        assertEquals("Tradovate Demo", controller.aliases.value.login(login))
        assertEquals("TAKEPROFIT813235899", controller.aliases.value.account(login.id, account))
        assertTrue(controller.setLogin(login.id, "  主号 Demo  "))
        assertTrue(controller.setAccount(login.id, account.id, "TPT 50K"))
        val names = AccountAliasesController(store).aliases.value
        assertEquals("主号 Demo", names.login(login), "trimmed")
        assertEquals("Tradovate Demo", names.login(otherLogin), "an alias belongs to one login")
        assertEquals("TPT 50K", names.account(login.id, account))
        assertEquals(account.name, names.account(otherLogin.id, account), "an account id is only unique within its login")
    }

    @Test fun aBlankAliasRestoresTheBrokerNameAndLeavesNothingBehind() = runTest {
        val store = MemorySnapshotStore()
        val controller = AccountAliasesController(store)
        assertTrue(controller.setLogin(login.id, "主号"))
        assertTrue(controller.setAccount(login.id, account.id, "TPT 50K"))
        assertTrue(controller.setLogin(login.id, ""))
        assertTrue(controller.setAccount(login.id, account.id, "   "))
        assertEquals(AccountAliases(), AccountAliasesController(store).aliases.value)
    }

    @Test fun anAliasLongerThanTheLimitIsRefusedAndNothingChanges() = runTest {
        val controller = AccountAliasesController(MemorySnapshotStore())
        assertFalse(controller.setAccount(login.id, account.id, "x".repeat(AccountAliases.MAX_LENGTH + 1)))
        assertNotNull(controller.error.value)
        assertEquals(AccountAliases(), controller.aliases.value)
        assertTrue(controller.setAccount(login.id, account.id, "x".repeat(AccountAliases.MAX_LENGTH)))
        assertNull(controller.error.value)
    }

    @Test fun aFailedSaveKeepsTheAliasesAsTheyWere() = runTest {
        val store = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        val controller = AccountAliasesController(store)
        assertFalse(controller.setLogin(login.id, "主号"))
        assertEquals(AccountAliases(), controller.aliases.value)
        assertNotNull(controller.error.value)
    }

    @Test fun anUnreadableFileShowsTheBrokerNames() {
        val store = MemorySnapshotStore().apply { write(AccountAliasesController.KEY, "{not json") }
        val controller = AccountAliasesController(store)
        assertEquals(AccountAliases(), controller.aliases.value)
        assertNotNull(controller.error.value)
    }
}
