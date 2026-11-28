package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyTradingConfigurationTest {
    private val config = CopySavedConfiguration("tradovate-demo-42", "leader", listOf(CopySavedFollower("follower", 2, maxContracts = 4)))
    @Test fun savesAndRestoresIndependentlyByLogin() = runTest {
        val store = MemorySnapshotStore()
        val controller = CopyTradingConfiguration(store)
        assertTrue(controller.save(config))
        assertTrue(controller.save(config.copy(brokerId = "tradovate-live-42", leaderId = "live-leader")))
        val loaded = CopyTradingConfiguration(store).configurations.value
        assertEquals(config, loaded[config.brokerId])
        assertEquals("live-leader", loaded["tradovate-live-42"]?.leaderId)
    }
    @Test fun refusesDuplicateAccountsAndInvalidSizing() = runTest {
        val controller = CopyTradingConfiguration(MemorySnapshotStore())
        assertFalse(controller.save(config.copy(followers = listOf(CopySavedFollower("leader")))))
        assertFalse(controller.save(config.copy(followers = listOf(CopySavedFollower("x"), CopySavedFollower("x")))))
        assertFalse(controller.save(config.copy(followers = listOf(CopySavedFollower("x", fixedContracts = 3, maxContracts = 2)))))
        assertFalse(controller.save(config.copy(brokerId = "other")))
        assertTrue(controller.configurations.value.isEmpty())
    }
    @Test fun unreadableOrFailedStorageIsNeverReportedAsSaved() = runTest {
        val broken = object : SnapshotStore {
            override fun read(key: String) = "not-json"
            override fun write(key: String, value: String) = error("should not overwrite")
        }
        assertFalse(CopyTradingConfiguration(broken).save(config))
        val failing = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        val controller = CopyTradingConfiguration(failing)
        assertFalse(controller.save(config))
        assertTrue(controller.configurations.value.isEmpty())
    }
}
