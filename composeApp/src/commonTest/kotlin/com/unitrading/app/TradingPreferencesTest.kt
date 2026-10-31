package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TradingPreferencesTest {
    @Test fun confirmationDefaultsOnAndSettingSurvivesRestart() = runTest {
        val store = MemorySnapshotStore()
        val controller = TradingPreferencesController(store)
        assertFalse(controller.preferences.value.skipConfirmation)
        assertTrue(controller.setSkipConfirmation(true))
        assertTrue(TradingPreferencesController(store).preferences.value.skipConfirmation)
        assertTrue(controller.setSkipConfirmation(false))
        assertFalse(TradingPreferencesController(store).preferences.value.skipConfirmation)
    }

    @Test fun damagedSettingsRestoreConfirmation() {
        val store = MemorySnapshotStore().apply { write(TradingPreferencesController.KEY, "broken") }
        val controller = TradingPreferencesController(store)
        assertFalse(controller.preferences.value.skipConfirmation)
        assertNotNull(controller.error.value)
    }

    @Test fun failedSaveDoesNotSilentlyChangeTheSwitch() = runTest {
        val controller = TradingPreferencesController(object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("disk full") }
        })
        assertFalse(controller.setSkipConfirmation(true))
        assertFalse(controller.preferences.value.skipConfirmation)
        assertFalse(controller.saving.value)
        assertNotNull(controller.error.value)
    }
}
