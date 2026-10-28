package com.unitrading.app

import com.unitrading.broker.*
import com.unitrading.demo.DemoBroker
import com.unitrading.demo.DemoConfigs
import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SavedTradovateAccountsTest {
    @Test fun lastRemoteLoginCanBeForgottenWithoutADefaultBroker() = runTest {
        val store = Store()
        val brokers = BrokerRegistry(emptyList())
        val accounts = manager(brokers, store)
        accounts.signIn(session()) {}
        assertEquals(1, brokers.plugins.size)
        assertTrue(accounts.forgetAll())
        assertTrue(brokers.plugins.isEmpty())
        assertNull(store.value)
    }
    private class Store : CredentialStore {
        var value: String? = null
        var failWrite = false
        var failRead = false
        override suspend fun read(): String? { check(!failRead); return value }
        override suspend fun write(value: String) { check(!failWrite); this.value = value }
        override suspend fun clear() { check(!failWrite); value = null }
    }
    private val now = 1_800_000_000_000L
    private fun session(env: TradovateEnvironment = TradovateEnvironment.DEMO, user: Long = 42, expires: Long = now + 3_600_000, token: String = "fake-token-for-tests-only-12345") =
        TradovateSession(env, token, null, user, "test", expires)
    private fun registry() = BrokerRegistry(listOf(DemoBroker(DemoConfigs.all.first())))
    private fun plugin(session: TradovateSession): BrokerPlugin = object : BrokerPlugin by DemoBroker(DemoConfigs.all.first()) {
        override val descriptor = BrokerDescriptor(session.brokerId, "Saved test", "", true, BrokerEnvironment.REMOTE_DEMO)
    }
    private fun manager(registry: BrokerRegistry, store: Store) = SavedTradovateAccounts(registry, store, { now }) { s, _, _ -> plugin(s) }

    @Test fun restartRestoresAllUsersAndEnvironmentsAndSelectedAccount() = runTest {
        val store = Store()
        val first = manager(registry(), store)
        first.restore()
        val sessions = listOf(session(), session(TradovateEnvironment.LIVE), session(user = 43))
        sessions.forEach { first.signIn(it) {} }
        first.select(sessions[1].brokerId, "account-200")
        val restoredRegistry = registry()
        val restored = manager(restoredRegistry, store)
        restored.restore()
        assertEquals(sessions.map { it.brokerId }.toSet(), restoredRegistry.plugins.drop(1).map { it.descriptor.id }.toSet())
        assertEquals(sessions[1].brokerId, restored.restoredBrokerId.value)
        assertEquals("account-200", restored.selectedAccount(sessions[1].brokerId))
        assertEquals(3, restored.savedCount.value)
        restored.restore()
        assertEquals(4, restoredRegistry.plugins.size)
    }

    @Test fun expiryNeverOpensBrowserOrConnectsAndKeepsOtherSessions() = runTest {
        val store = Store()
        val first = manager(registry(), store)
        first.signIn(session(expires = now - 1)) {}
        first.signIn(session(TradovateEnvironment.LIVE)) {}
        val calls = mutableListOf<TradovateSession>()
        val restored = SavedTradovateAccounts(registry(), store, { now }) { s, _, _ -> calls += s; plugin(s) }
        restored.restore()
        assertEquals(listOf(TradovateEnvironment.LIVE), calls.map { it.environment })
        assertTrue(restored.message.value!!.contains("已过期"))
        assertEquals(2, restored.savedCount.value)
    }

    @Test fun networkFailureKeepsCredentialsAndRetryRestores() = runTest {
        val store = Store()
        manager(registry(), store).signIn(session()) {}
        var offline = true
        val restoredRegistry = registry()
        val restored = SavedTradovateAccounts(restoredRegistry, store, { now }) { s, _, _ ->
            if (offline) throw TradovateRemoteException(0, "offline")
            plugin(s)
        }
        val saved = store.value
        restored.restore()
        assertEquals(saved, store.value)
        assertTrue(restored.message.value!!.contains("重试"))
        offline = false
        restored.retry()
        assertEquals(2, restoredRegistry.plugins.size)
        assertNull(restored.message.value)
    }

    @Test fun renewalIsSavedAndStaleCallbackCannotUndoReplacementOrForget() = runTest {
        val store = Store()
        val callbacks = mutableListOf<suspend (TradovateSession) -> Unit>()
        val accounts = SavedTradovateAccounts(registry(), store, { now }) { s, callback, _ -> callbacks += callback; plugin(s) }
        accounts.signIn(session()) {}
        callbacks[0](session(token = "renewed-fake-token-1234567890"))
        assertTrue(store.value!!.contains("renewed-fake-token"))
        accounts.signIn(session(token = "replacement-token-1234567890")) {}
        callbacks[0](session())
        assertTrue(store.value!!.contains("replacement-token"))
        assertTrue(accounts.forgetAll())
        callbacks[1](session())
        assertNull(store.value)
        val reopened = registry()
        manager(reopened, store).restore()
        assertEquals(1, reopened.plugins.size)
    }

    @Test fun corruptedOrUnreadableStoreIsNotOverwrittenByAccountSelection() = runTest {
        val store = Store().apply { value = "corrupted" }
        val accounts = manager(registry(), store)
        accounts.restore()
        accounts.select("local")
        assertEquals("corrupted", store.value)
        assertTrue(accounts.message.value!!.contains("损坏"))
        store.failRead = true
        accounts.retry()
        accounts.select("local")
        assertEquals("corrupted", store.value)
        assertTrue(accounts.message.value!!.contains("无法读取"))
    }

    @Test fun diskFailureIsVisibleAndDoesNotUndoSuccessfulConnection() = runTest {
        val store = Store().apply { failWrite = true }
        val brokers = registry()
        val accounts = manager(brokers, store)
        accounts.signIn(session()) {}
        assertEquals(2, brokers.plugins.size)
        assertTrue(accounts.message.value!!.contains("保存失败"))
        assertFalse(accounts.forgetAll())
        assertEquals(2, brokers.plugins.size)
        store.failWrite = false
        accounts.retry()
        assertNotNull(store.value)
        assertNull(accounts.message.value)
    }

    @Test fun connectionTimeoutDoesNotPreventNextSavedLoginFromRestoring() = runTest {
        val store = Store()
        val initial = manager(registry(), store)
        initial.signIn(session()) {}
        initial.signIn(session(TradovateEnvironment.LIVE)) {}
        val brokers = registry()
        val accounts = SavedTradovateAccounts(brokers, store, { now }) { s, _, _ ->
            if (s.environment == TradovateEnvironment.DEMO) awaitCancellation()
            plugin(s)
        }
        accounts.restore()
        assertEquals(2, brokers.plugins.size)
        assertTrue(accounts.message.value!!.contains("超时"))
        assertFalse(accounts.restoring.value)
    }
}
