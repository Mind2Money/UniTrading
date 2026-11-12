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
        assertFalse(controller.preferences.value.keyboardTrading)
        assertTrue(controller.preferences.value.tradeSounds)
        assertNotNull(controller.error.value)
    }

    @Test fun tradeSoundsStartOnAndSwitchingThemOffSurvivesRestart() = runTest {
        val store = MemorySnapshotStore().apply { write(TradingPreferencesController.KEY, """{"skipConfirmation":true,"keyboardTrading":true}""") }
        val controller = TradingPreferencesController(store)
        assertTrue(TradingPreferences().tradeSounds)
        assertTrue(controller.preferences.value.tradeSounds, "settings saved before the switch existed keep the sounds")
        assertNull(controller.error.value)
        assertTrue(controller.setTradeSounds(false))
        val expected = TradingPreferences(skipConfirmation = true, keyboardTrading = true, tradeSounds = false)
        assertEquals(expected, controller.preferences.value, "the other settings stay as they were")
        assertEquals(expected, TradingPreferencesController(store).preferences.value)
        assertTrue(controller.setTradeSounds(true))
        assertTrue(TradingPreferencesController(store).preferences.value.tradeSounds)
    }

    @Test fun orderPageStartsAsTheSheetAndTheChosenPageSurvivesRestart() = runTest {
        val store = MemorySnapshotStore().apply { write(TradingPreferencesController.KEY, """{"skipConfirmation":true,"tradeSounds":false}""") }
        val controller = TradingPreferencesController(store)
        assertEquals(OrderPanelLayout.SHEET, TradingPreferences().orderPanelLayout)
        assertEquals(OrderPanelLayout.SHEET, controller.preferences.value.orderPanelLayout, "settings saved before there was a choice open the sheet")
        assertNull(controller.error.value)
        assertEquals(listOf("上滑面板", "停靠下单栏", "独立弹窗"), OrderPanelLayout.entries.map { it.label })
        for (layout in listOf(OrderPanelLayout.DOCKED, OrderPanelLayout.POPUP, OrderPanelLayout.SHEET, OrderPanelLayout.POPUP)) {
            assertTrue(controller.setOrderPanelLayout(layout))
            val expected = TradingPreferences(skipConfirmation = true, tradeSounds = false, orderPanelLayout = layout)
            assertEquals(expected, controller.preferences.value, "the other settings stay as they were")
            assertEquals(expected, TradingPreferencesController(store).preferences.value)
        }
    }

    @Test fun aDockChosenBeforeTheSheetCameBackIsKept() {
        val store = MemorySnapshotStore().apply { write(TradingPreferencesController.KEY, """{"orderPanelLayout":"DOCKED"}""") }
        val controller = TradingPreferencesController(store)
        assertEquals(TradingPreferences(orderPanelLayout = OrderPanelLayout.DOCKED), controller.preferences.value)
        assertNull(controller.error.value)
    }

    @Test fun moreSettingsOfTheDockStartFoldedAndTheirStateSurvivesRestart() = runTest {
        val store = MemorySnapshotStore()
        val controller = TradingPreferencesController(store)
        assertFalse(controller.preferences.value.orderMoreSettings)
        assertTrue(controller.setOrderPanelLayout(OrderPanelLayout.DOCKED))
        assertTrue(controller.setOrderMoreSettings(true))
        val expected = TradingPreferences(orderPanelLayout = OrderPanelLayout.DOCKED, orderMoreSettings = true)
        assertEquals(expected, controller.preferences.value)
        assertEquals(expected, TradingPreferencesController(store).preferences.value)
        assertTrue(controller.setOrderMoreSettings(false))
        assertEquals(expected.copy(orderMoreSettings = false), TradingPreferencesController(store).preferences.value)
    }

    @Test fun keyboardTradingStartsOffWithTheLetterKeysAndEachSettingKeepsTheOthers() = runTest {
        val store = MemorySnapshotStore()
        val controller = TradingPreferencesController(store)
        assertEquals(TradingPreferences(skipConfirmation = false, keyboardTrading = false, tradingKeys = TradingKeys.LETTERS), controller.preferences.value)
        assertTrue(controller.setKeyboardTrading(true))
        assertTrue(controller.setTradingKeys(TradingKeys.ARROWS))
        assertTrue(controller.setSkipConfirmation(true))
        val expected = TradingPreferences(skipConfirmation = true, keyboardTrading = true, tradingKeys = TradingKeys.ARROWS)
        assertEquals(expected, controller.preferences.value, "changing one setting leaves the others")
        assertEquals(expected, TradingPreferencesController(store).preferences.value, "and all of them survive a restart")
        assertTrue(controller.setKeyboardTrading(false))
        assertEquals(expected.copy(keyboardTrading = false), TradingPreferencesController(store).preferences.value)
    }

    @Test fun settingsSavedBeforeKeyboardTradingExistedLoadWithItOff() {
        val store = MemorySnapshotStore().apply { write(TradingPreferencesController.KEY, """{"skipConfirmation":true}""") }
        val controller = TradingPreferencesController(store)
        assertEquals(TradingPreferences(skipConfirmation = true), controller.preferences.value)
        assertNull(controller.error.value)
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
        assertFalse(controller.setKeyboardTrading(true))
        assertFalse(controller.setTradingKeys(TradingKeys.ARROWS))
        assertFalse(controller.setTradeSounds(false))
        assertFalse(controller.setOrderPanelLayout(OrderPanelLayout.DOCKED))
        assertFalse(controller.setOrderMoreSettings(true))
        assertEquals(TradingPreferences(), controller.preferences.value, "keyboard trading never turns on without being saved")
    }
}
