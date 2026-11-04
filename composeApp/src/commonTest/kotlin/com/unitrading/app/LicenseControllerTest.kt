package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LicenseControllerTest {
    private val keyA = testLicenseKey("key-a", "unit-test-key-A1")
    private var now = 1_800_000_000_000L
    private fun controller(store: CredentialStore?, keys: List<LicenseKey> = listOf(keyA)) =
        LicenseController(store, OfflineLicenseVerifier(keys), clock = { now })
    private suspend fun loaded(store: CredentialStore?, keys: List<LicenseKey> = listOf(keyA)) = controller(store, keys).also { it.load() }
    private val LicenseController.status get() = state.value.status
    private val LicenseController.message get() = state.value.message

    @Test fun keyIsVerifiedOnceAndOnlyTheActivationIsStored() = runTest {
        val store = TestLicenseStore()
        val license = loaded(store)
        assertEquals(LicenseStatus.REQUIRED, license.status)
        assertNull(license.message)
        // Surrounding whitespace from a paste is ignored; the key itself is compared exactly.
        assertTrue(license.activate("  unit-test-key-A1\n"))
        assertEquals(LicenseStatus.ACTIVE, license.status)
        assertNull(license.state.value.notice)
        val saved = assertNotNull(store.value)
        assertFalse(saved.contains("unit-test-key-A1"))
        assertEquals("key-a", Json.parseToJsonElement(saved).jsonObject["activation"]!!.jsonObject["keyId"]!!.jsonPrimitive.content)
        assertEquals(LicenseStatus.ACTIVE, loaded(store).status)
    }

    @Test fun wrongKeysLockWithDoublingDelaysThatSurviveRestarts() = runTest {
        val store = TestLicenseStore()
        val license = loaded(store)
        repeat(4) { attempt ->
            assertFalse(license.activate("wrong-key-$attempt"))
            assertEquals("授权码无效，还可尝试 ${4 - attempt} 次。", license.message)
            assertEquals(0, license.state.value.lockedUntilMs)
        }
        assertFalse(license.activate("Unit-Test-Key-A1"), "keys are case-sensitive")
        assertEquals("授权码无效，尝试次数过多。", license.message)
        assertEquals(now + 30_000, license.state.value.lockedUntilMs)
        // While locked even the right key is not checked.
        assertFalse(license.activate("unit-test-key-A1"))
        assertEquals(LicenseStatus.REQUIRED, license.status)
        val restarted = loaded(store)
        assertEquals(now + 30_000, restarted.state.value.lockedUntilMs)
        now += 30_000
        assertFalse(restarted.activate("wrong-key-again"))
        assertEquals(now + 60_000, restarted.state.value.lockedUntilMs)
        now += 60_000
        assertTrue(restarted.activate("unit-test-key-A1"))
        val record = Json.parseToJsonElement(store.value!!).jsonObject
        assertEquals(0, record["failedAttempts"]?.jsonPrimitive?.int ?: 0)
        assertEquals(LicenseStatus.ACTIVE, loaded(store).status)
    }

    @Test fun lockoutPolicyDoublesUpToItsCap() {
        assertEquals(listOf(0L, 0, 0, 0, 0, 30_000, 60_000, 120_000, 240_000, 480_000, 900_000, 900_000, 900_000),
            (0..12).map(LockoutPolicy()::lockMs))
    }

    @Test fun movingTheClockBackCannotProlongALock() = runTest {
        val store = TestLicenseStore()
        val license = loaded(store)
        repeat(5) { license.activate("wrong-key") }
        now -= 24 * 3_600_000L
        assertEquals(now + 15 * 60_000, loaded(store).state.value.lockedUntilMs)
    }

    @Test fun retiredKeysAndUnreadableRecordsAskForAKeyAgain() = runTest {
        val store = TestLicenseStore()
        assertTrue(loaded(store).activate("unit-test-key-A1"))
        val keyB = testLicenseKey("key-b", "unit-test-key-B2")
        val rotated = loaded(store, listOf(keyB))
        assertEquals(LicenseStatus.REQUIRED, rotated.status)
        assertEquals("授权码已更换，请输入新的授权码。", rotated.message)
        assertFalse(rotated.activate("unit-test-key-A1"))
        assertTrue(rotated.activate("unit-test-key-B2"))
        assertEquals(LicenseStatus.ACTIVE, loaded(store, listOf(keyA, keyB)).status)

        for (damaged in listOf("{not json", """{"version":2,"activation":{"keyId":"key-a","activatedAtMs":1}}""")) {
            store.value = damaged
            val license = loaded(store)
            assertEquals(LicenseStatus.REQUIRED, license.status)
            assertEquals("无法读取本机授权记录，请重新输入授权码。", license.message)
            assertTrue(license.activate("unit-test-key-A1"))
            assertEquals(LicenseStatus.ACTIVE, loaded(store).status)
        }
    }

    @Test fun storageFailuresNeitherUnlockNorBlockAValidKey() = runTest {
        val store = TestLicenseStore().apply { failRead = true }
        val license = loaded(store)
        assertEquals(LicenseStatus.REQUIRED, license.status)
        assertEquals("无法读取本机授权记录，请重新输入授权码。", license.message)
        store.failRead = false
        store.failWrite = true
        assertTrue(license.activate("unit-test-key-A1"))
        assertEquals(LicenseStatus.ACTIVE, license.status)
        assertNotNull(license.state.value.notice)
        license.dismissNotice()
        assertNull(license.state.value.notice)
        assertNull(store.value)
        // Without a store the activation lasts for this process only, and says so.
        val memoryOnly = loaded(null)
        assertEquals(LicenseStatus.REQUIRED, memoryOnly.status)
        assertTrue(memoryOnly.activate("unit-test-key-A1"))
        assertNotNull(memoryOnly.state.value.notice)
    }

    @Test fun blankKeysAndVerifierFaultsAreNotCountedAsFailures() = runTest {
        val store = TestLicenseStore()
        val unavailable = object : LicenseVerifier {
            override suspend fun verify(key: String): String? = error("offline")
            override fun accepts(id: String) = false
        }
        val license = LicenseController(store, unavailable, clock = { now }).also { it.load() }
        repeat(10) { assertFalse(license.activate("any-key")) }
        assertEquals("暂时无法验证授权码，请重试。", license.message)
        assertEquals(0, license.state.value.lockedUntilMs)
        assertFalse(license.activate("   "))
        assertEquals("请输入授权码。", license.message)
        assertNull(store.value)
    }

    @Test fun everyAcceptedKeyIsCheckedAndOverlongInputIsRefused() = runTest {
        val keyB = testLicenseKey("key-b", "unit-test-key-B2")
        val verifier = OfflineLicenseVerifier(listOf(keyA, keyB))
        assertEquals("key-a", verifier.verify("unit-test-key-A1"))
        assertEquals("key-b", verifier.verify("unit-test-key-B2"))
        assertNull(verifier.verify("unit-test-key-C3"))
        assertNull(verifier.verify("x".repeat(257)))
        assertTrue(verifier.accepts("key-b"))
        assertFalse(verifier.accepts("key-c"))
    }

    @Test fun productionKeysAreWellFormed() {
        assertTrue(ProductionLicenseKeys.isNotEmpty())
        assertEquals(ProductionLicenseKeys.size, ProductionLicenseKeys.map { it.id }.distinct().size)
        assertFailsWith<IllegalArgumentException> { LicenseKey("short-salt", salt = "00", iterations = 1, verifier = "00".repeat(32)) }
    }
}
