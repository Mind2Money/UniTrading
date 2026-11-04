package com.unitrading.app

import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativePlatformBridgeTest {
    private class Presenter : NativeLoginPresenter {
        var opened = ""
        var closed = false
        override fun open(environment: String, script: String) { opened = environment; check(script.contains("trader.tradovate.com")) }
        override fun close() { closed = true }
    }
    @Test fun nativeLoginRejectsForeignFramesAndCompletesOfficialSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bridge = NativePlatformBridge()
            val presenter = Presenter().also { bridge.presenter = it }
            val login = async { bridge.login.signIn(TradovateEnvironment.DEMO) }
            runCurrent()
            assertEquals("demo", presenter.opened)
            val payload = """{"kind":"auth","environment":"demo","accessToken":"synthetic-native-test-token-123456","userId":42,"expirationTime":"2100-01-01T00:00:00Z"}"""
            assertFalse(bridge.accept(payload, "https://untrusted.example", true))
            assertFalse(bridge.accept(payload, "https://trader.tradovate.com", false))
            assertTrue(bridge.accept(payload, "https://trader.tradovate.com", true))
            bridge.finish(false)
            assertEquals(42, login.await().userId)
            assertTrue(presenter.closed)
            assertFalse(bridge.accept(payload, "https://trader.tradovate.com", true))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun nativeWindowClosesWhenLoginCoroutineIsCancelled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bridge = NativePlatformBridge()
            val presenter = Presenter().also { bridge.presenter = it }
            val login = launch { bridge.login.signIn(TradovateEnvironment.DEMO) }
            runCurrent(); login.cancelAndJoin()
            assertTrue(presenter.closed)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun storageDistinguishesMissingItemFromPlatformFailure() = runTest {
        val bridge = NativePlatformBridge()
        var value: String? = null
        var fail = false
        bridge.storage = object : NativeCredentialStorage {
            override fun read() = NativeStorageResult(value, if (fail) "unavailable" else null)
            override fun write(value: String) = NativeStorageResult(null, "denied")
            override fun clear(): NativeStorageResult { value = null; return NativeStorageResult(null, null) }
        }
        assertNull(bridge.credentials.read())
        value = "synthetic"
        assertEquals("synthetic", bridge.credentials.read())
        fail = true
        assertFailsWith<IllegalStateException> { bridge.credentials.read() }
        assertFailsWith<IllegalStateException> { bridge.credentials.write("never persisted") }
        fail = false
        bridge.credentials.clear()
        assertNull(bridge.credentials.read())
    }
    @Test fun licenseRecordUsesItsOwnStorage() = runTest {
        class Item : NativeCredentialStorage {
            var value: String? = null
            override fun read() = NativeStorageResult(value, null)
            override fun write(value: String): NativeStorageResult { this.value = value; return NativeStorageResult(null, null) }
            override fun clear(): NativeStorageResult { value = null; return NativeStorageResult(null, null) }
        }
        val bridge = NativePlatformBridge()
        val logins = Item().also { bridge.storage = it }
        assertFailsWith<IllegalStateException> { bridge.licenseCredentials.read() }
        val license = Item().also { bridge.licenseStorage = it }
        bridge.licenseCredentials.write("synthetic-license-record")
        assertEquals("synthetic-license-record", license.value)
        assertNull(logins.value)
        assertNull(bridge.credentials.read())
        assertEquals("synthetic-license-record", bridge.licenseCredentials.read())
    }
}
