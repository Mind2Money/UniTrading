package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SettingsPageTest {
    @get:Rule val compose = createComposeRule()

    private fun render(width: Int = 400, height: Int = 840, light: Boolean = false, fontScale: Float = 1f): TradingRuntime {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        runtime.paused.value = true
        if (light) runBlocking { runtime.theme.apply(ThemeSettings(ThemePreset.LIGHT)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f, fontScale)) {
            Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
        } }
        return runtime
    }

    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onNodeWithTag("settings-page").captureToImage().toAwtImage(), "png", target)
    }

    @Test fun phoneGroupsKeepAccountSwitchingAndPreferenceDestinationsAccessible() {
        render()
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("broker-picker").assertDoesNotExist()
        capture("settings-phone-dark")
        compose.onNodeWithTag("settings-connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-connect-rithmic").assertDoesNotExist()
        compose.onNodeWithTag("settings-account").performClick()
        compose.onNodeWithTag("broker-picker").assertIsDisplayed()
        compose.onNodeWithTag("settings-account").performClick()
        compose.onNodeWithTag("broker-picker").assertDoesNotExist()
        compose.onNodeWithTag("open-theme").performScrollTo().performClick()
        compose.onNodeWithTag("theme-settings").assertExists()
        compose.onNodeWithTag("theme-cancel").performClick()
        compose.onNodeWithTag("settings-indicators").performScrollTo().performClick()
        compose.onNodeWithTag("indicator-settings").assertExists()
        compose.onNodeWithTag("indicators-cancel").performClick()
        compose.onNodeWithTag("open-activity").performScrollTo().performClick()
        compose.onNodeWithTag("activity-back").performClick()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
        compose.onNodeWithTag("mobile-navigation").assertIsDisplayed()
    }

    @Test fun narrowLightPageSupportsLargeTextAndKeepsResetBehindConfirmation() {
        val runtime = render(width = 360, height = 640, light = true, fontScale = 1.3f)
        compose.onNodeWithTag("nav-3").performClick()
        capture("settings-phone-light-large-text")
        compose.onNodeWithTag("settings-reset-demo").performScrollTo().assertIsDisplayed()
        capture("settings-phone-light-actions")
        compose.onNodeWithTag("settings-reset-demo").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(runtime.paused.value)
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
    }

    @Test fun desktopUsesTheSameGroupsAndCanCloseAfterOpeningAnEditor() {
        render(width = 1100, height = 900)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-account").assertIsDisplayed()
        capture("settings-desktop-dark")
        compose.onNodeWithTag("open-theme").performScrollTo().performClick()
        compose.onNodeWithTag("theme-settings").assertExists()
        compose.onNodeWithTag("theme-cancel").performClick()
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("settings-page").assertDoesNotExist()
        compose.onNodeWithTag("trading-chart").assertExists()
    }

    @Test fun skipConfirmationSwitchAppliesImmediatelyAndResetStillRequiresConfirmation() {
        val runtime = render()
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-skip-confirmation").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { runtime.tradingPreferences.preferences.value.skipConfirmation }
        compose.onNodeWithTag("settings-skip-confirmation").assertIsOn()
        capture("settings-skip-confirmation")
        compose.onNodeWithTag("settings-reset-demo").performScrollTo().performClick()
        compose.onNodeWithText("重置全部模拟数据？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("settings-skip-confirmation").performScrollTo().performClick()
        compose.waitUntil(5_000) { !runtime.tradingPreferences.preferences.value.skipConfirmation }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
    }

    @Test fun damagedSavedLoginsCanStillBeClearedOnlyAfterConfirmation() {
        var clears = 0
        var forgotten = false
        val store = object : CredentialStore {
            override suspend fun read() = "invalid saved login"
            override suspend fun write(value: String) = Unit
            override suspend fun clear() { clears++ }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), credentialStore = store)
        runBlocking { runtime.savedLogins.restore() }
        compose.setContent { TradingTheme {
            Column { SavedLoginControls(runtime.savedLogins, grouped = true) { forgotten = true } }
        } }
        compose.onNodeWithTag("settings-restore-logins").assertIsEnabled()
        compose.onNodeWithTag("forget-saved-logins").assertIsEnabled().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, clears)
        compose.onNodeWithTag("forget-saved-logins").performClick()
        compose.onNodeWithText("确认清除").performClick()
        compose.waitUntil(5_000) { forgotten }
        assertEquals(1, clears)
    }
}
