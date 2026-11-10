package com.unitrading.app

import com.unitrading.broker.*
import com.unitrading.demo.DemoBroker
import com.unitrading.demo.DemoConfigs
import com.unitrading.rithmic.RithmicCredentials
import com.unitrading.rithmic.RithmicEnvironment
import com.unitrading.rithmic.RithmicRemoteException
import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
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
    private fun rithmic(user: String = "trader@example.com", environment: RithmicEnvironment = RithmicEnvironment.TEST) =
        RithmicCredentials(environment, "wss://rituz00100.rithmic.com:443", environment.defaultSystemName, user, "secret-password-1")
    private fun rithmicPlugin(credentials: RithmicCredentials): BrokerPlugin = object : BrokerPlugin by DemoBroker(DemoConfigs.all.first()) {
        override val descriptor = BrokerDescriptor(credentials.brokerId, "Rithmic test", "", true, BrokerEnvironment.RITHMIC_TEST)
    }
    private fun managerWithRithmic(registry: BrokerRegistry, store: Store, connect: suspend (RithmicCredentials) -> BrokerPlugin = ::rithmicPlugin) =
        SavedTradovateAccounts(registry, store, { now }, connect = { s, _, _ -> plugin(s) }, connectRithmic = { c, _ -> connect(c) })

    @Test fun tradovateOnlyPolicyDoesNotRestoreOrAcceptOtherLogins() = runTest {
        val store = Store()
        managerWithRithmic(registry(), store).signInRithmic(rithmic(), remember = true) {}
        var connected = false
        val reopened = registry()
        val manager = managerWithRithmic(reopened, store) { connected = true; rithmicPlugin(it) }.also { it.allowRithmic = false }
        manager.restore()
        assertFalse(connected)
        assertFalse(reopened.plugins.any { it.descriptor.id == rithmic().brokerId })
        assertFailsWith<IllegalStateException> { manager.signInRithmic(rithmic(), true) {} }
        assertFalse(connected)
    }

    @Test fun rithmicPasswordIsStoredOnlyWhenRememberIsChosenAndRestoresWithoutABrowser() = runTest {
        val store = Store()
        val brokers = registry()
        val accounts = managerWithRithmic(brokers, store)
        val credentials = rithmic()
        assertEquals(credentials.brokerId, accounts.signInRithmic(credentials, remember = false) {})
        assertTrue(brokers.plugins.any { it.descriptor.id == credentials.brokerId })
        assertEquals(0, accounts.savedCount.value)
        assertFalse(store.value.orEmpty().contains("secret-password-1"))
        accounts.signInRithmic(credentials, remember = true) {}
        assertEquals(1, accounts.savedCount.value)
        accounts.select(credentials.brokerId, "ACC1")
        val connected = mutableListOf<RithmicCredentials>()
        val reopened = registry()
        val restored = managerWithRithmic(reopened, store) { c -> connected += c; rithmicPlugin(c) }
        restored.restore()
        assertEquals(listOf(credentials.brokerId), connected.map { it.brokerId })
        assertEquals("secret-password-1", connected.single().password)
        assertEquals(credentials.brokerId, restored.restoredBrokerId.value)
        assertEquals("ACC1", restored.selectedAccount(credentials.brokerId))
        assertTrue(reopened.plugins.any { it.descriptor.id == credentials.brokerId })
        assertTrue(restored.forgetAll())
        assertFalse(reopened.plugins.any { it.descriptor.id == credentials.brokerId })
        assertNull(store.value)
    }

    @Test fun rithmicLoginFailureIsNotSavedAndARefusedRestoreKeepsOtherLogins() = runTest {
        val store = Store()
        val accounts = managerWithRithmic(registry(), store) { throw RithmicRemoteException("7", "bad password") }
        assertFailsWith<RithmicRemoteException> { accounts.signInRithmic(rithmic(), remember = true) {} }
        assertEquals(0, accounts.savedCount.value)
        val saved = managerWithRithmic(registry(), store)
        saved.signInRithmic(rithmic(), remember = true) {}
        saved.signIn(session()) {}
        val reopened = registry()
        val restored = managerWithRithmic(reopened, store) { throw RithmicRemoteException("7", "bad password") }
        restored.restore()
        assertTrue(restored.message.value!!.contains("bad password"))
        assertEquals(2, restored.savedCount.value)
        assertTrue(reopened.plugins.any { it.descriptor.id == session().brokerId })
        assertFalse(reopened.plugins.any { it.descriptor.id == rithmic().brokerId })
    }

    @Test fun aLoginsHostsSurviveARestart() = runTest {
        val hosts = TradovateHosts("demo-api.org.ninjatrader.com", "md-demo-api.org.ninjatrader.com")
        val store = Store()
        manager(registry(), store).signIn(TradovateSession(TradovateEnvironment.DEMO, "fake-token-for-tests-only-12345", null, 42, "test", now + 3_600_000, hosts = hosts)) {}
        val restored = mutableListOf<TradovateHosts>()
        SavedTradovateAccounts(registry(), store, { now }) { s, _, _ -> restored += s.hosts; plugin(s) }.restore()
        assertEquals(listOf(hosts), restored)
    }

    @Test fun aRedirectedRestoreAsksForALoginInsteadOfARetry() = runTest {
        val store = Store()
        manager(registry(), store).signIn(session()) {}
        val accounts = SavedTradovateAccounts(registry(), store, { now }) { _, _, _ -> throw TradovateRemoteException(307, "synthetic redirect") }
        accounts.restore()
        assertTrue(accounts.message.value!!.contains("服务器地址已变更"))
        assertEquals(1, accounts.savedCount.value)
    }

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

    @Test fun preferredLoginBecomesAvailableWhileAnotherLoginIsStillWaiting() = runTest {
        val store = Store()
        val initial = manager(registry(), store)
        initial.signIn(session()) {}
        val preferred = session(TradovateEnvironment.LIVE)
        initial.signIn(preferred) {}
        initial.select(preferred.brokerId, "account-200")
        val brokers = registry()
        val accounts = SavedTradovateAccounts(brokers, store, { now }) { s, _, _ ->
            if (s.environment == TradovateEnvironment.DEMO) awaitCancellation()
            delay(100)
            plugin(s)
        }
        val restore = launch { accounts.restore() }
        advanceTimeBy(101); runCurrent()
        assertTrue(accounts.restoring.value)
        assertFalse(restore.isCompleted)
        assertTrue(brokers.plugins.any { it.descriptor.id == preferred.brokerId })
        assertEquals(preferred.brokerId, accounts.restoredBrokerId.value)
        assertEquals("account-200", accounts.selectedAccount(preferred.brokerId))
        restore.join()
        assertTrue(accounts.message.value!!.contains("超时"))
    }

    @Test fun savedConnectionsUseBoundedConcurrencyAndKeepSuccessfulResults() = runTest {
        val store = Store()
        val initial = manager(registry(), store)
        (42L..46L).forEach { initial.signIn(session(user = it)) {} }
        initial.select(session(user = 46).brokerId)
        val brokers = registry()
        val started = mutableListOf<Long>()
        var active = 0
        var peak = 0
        val accounts = SavedTradovateAccounts(brokers, store, { now }) { s, _, _ ->
            started += s.userId
            active++
            peak = maxOf(peak, active)
            try {
                delay(1_000)
                if (s.userId == 43L) throw TradovateRemoteException(403, "synthetic rejection")
                plugin(s)
            } finally { active-- }
        }
        accounts.restore()
        assertEquals(46L, started.first())
        assertEquals(3, peak)
        assertEquals(0, active)
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(setOf(42L, 44L, 45L, 46L).map { session(user = it).brokerId }.toSet(),
            brokers.plugins.drop(1).map { it.descriptor.id }.toSet())
        assertTrue(accounts.message.value!!.contains("授权已失效"))
        assertEquals(5, accounts.savedCount.value)
    }

    @Test fun cancellingRestoreCancelsParallelConnectionsAndRetainsCredentialsForRetry() = runTest {
        val store = Store()
        val initial = manager(registry(), store)
        (42L..45L).forEach { initial.signIn(session(user = it)) {} }
        val saved = store.value
        var active = 0
        var suspended = true
        val brokers = registry()
        val accounts = SavedTradovateAccounts(brokers, store, { now }) { s, _, _ ->
            if (suspended) {
                active++
                try { awaitCancellation() } finally { active-- }
            }
            plugin(s)
        }
        val restore = launch { accounts.restore() }
        runCurrent()
        assertEquals(3, active)
        restore.cancelAndJoin()
        assertEquals(0, active)
        assertFalse(accounts.restoring.value)
        assertEquals(saved, store.value)
        suspended = false
        accounts.restore()
        assertEquals(5, brokers.plugins.size)
    }
}
