package com.unitrading.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class StartupUpdateGateTest {
    @get:Rule val compose = createComposeRule()
    private val file = ReleasePackage("v1.9.0", "UniTrading-1.9.0-windows-x64.zip", listOf("https://api.github.com/x"), 100, "a".repeat(64))
    private val release = AppRelease("v1.9.0", "https://github.com/Mind2Money/UniTrading/releases/tag/v1.9.0", file)
    private class FakeUpdates : AppUpdates {
        override val currentVersion = "1.8.3"
        override val install = UpdateInstall.RESTART
        override val check = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
        override val checkedAtMs = MutableStateFlow<Long?>(null)
        override val progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
        var checks = 0
        var started: ReleasePackage? = null
        var cancels = 0
        override fun checkNow() { checks++; check.value = UpdateCheck.Checking }
        override fun start(release: ReleasePackage) { started = release; progress.value = UpdateProgress.Downloading(release.version, 0, release.bytes) }
        override fun cancel() { cancels++; progress.value = UpdateProgress.Idle }
        override fun finish() {}
    }
    private fun show(updates: FakeUpdates) {
        compose.setContent { TradingTheme { StartupUpdateGate(updates) { Text("trading", Modifier.testTag("content")) } } }
        compose.waitUntil(5_000) { updates.checks == 1 }
    }
    @Test fun currentVersionEntersAndUnavailableCanRetryOrContinue() {
        val updates = FakeUpdates(); show(updates)
        compose.onNodeWithTag("content").assertDoesNotExist()
        updates.check.value = UpdateCheck.Failed("UPDATE_UNAVAILABLE")
        compose.onNodeWithTag("startup-update-retry-check").performClick()
        assertEquals(2, updates.checks)
        updates.check.value = UpdateCheck.Current("v1.8.3")
        compose.onNodeWithTag("content").assertIsDisplayed()
    }
    @Test fun cancellingNewVersionEntersWithoutDownloading() {
        val updates = FakeUpdates(); show(updates)
        updates.check.value = UpdateCheck.Available(release)
        compose.onNodeWithTag("startup-update-cancel").performClick()
        compose.onNodeWithTag("content").assertIsDisplayed()
        assertEquals(null, updates.started)
    }
    @Test fun updatingStartsTheVerifiedReleaseAndCanCancelDownload() {
        val updates = FakeUpdates(); show(updates)
        updates.check.value = UpdateCheck.Available(release)
        compose.onNodeWithTag("startup-update-install").performClick()
        assertEquals(file, updates.started)
        compose.onNodeWithTag("content").assertDoesNotExist()
        compose.onNodeWithTag("startup-update-cancel").performClick()
        assertEquals(1, updates.cancels)
        compose.onNodeWithTag("content").assertIsDisplayed()
    }
    @Test fun activationIsStillRequiredBeforeAnyUpdateCheck() {
        val updates = FakeUpdates()
        val license = LicenseController(null, OfflineLicenseVerifier(listOf(testLicenseKey("key-ui", "ui-test-key-123"))))
        val runtime = TradingRuntime(MemorySnapshotStore())
        compose.setContent { LicenseGate(license, runtime.theme) { TradingTheme { StartupUpdateGate(updates) { Text("trading", Modifier.testTag("content")) } } } }
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.REQUIRED }
        assertEquals(0, updates.checks)
        compose.onNodeWithTag("license-key").performTextInput("ui-test-key-123")
        compose.onNodeWithTag("license-submit").performClick()
        compose.waitUntil(5_000) { updates.checks == 1 }
        updates.check.value = UpdateCheck.Current("v1.8.3")
        compose.onNodeWithTag("content").assertIsDisplayed()
    }
}
