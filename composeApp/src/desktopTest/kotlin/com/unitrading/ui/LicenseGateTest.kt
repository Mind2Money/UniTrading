package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.test.assertEquals

class LicenseGateTest {
    @get:Rule val compose = createComposeRule()
    private val key = testLicenseKey("key-ui", "ui-test-key-123")
    private var now = 1_800_000_000_000L
    private fun license(store: CredentialStore) = LicenseController(store, OfflineLicenseVerifier(listOf(key)), clock = { now })

    /** Saved broker logins; reading them means the trading UI started restoring connections. */
    private class SavedLogins : CredentialStore {
        var reads = 0
        override suspend fun read(): String? { reads++; return null }
        override suspend fun write(value: String) {}
        override suspend fun clear() {}
    }

    /** The key as drawn, after the field's visual transformation. */
    private fun displayedKey(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("license-key").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first().layoutInput.text.text
    }

    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }

    @Test fun tradingAndSavedLoginsStayClosedUntilAValidKeyIsEntered() {
        val logins = SavedLogins()
        val runtime = TradingRuntime(MemorySnapshotStore(), credentialStore = logins)
        val store = TestLicenseStore()
        val license = license(store)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 740.dp)) { LicenseGate(license, runtime.theme) { TradingScreen(runtime) } }
        } }
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.REQUIRED }
        compose.onNodeWithTag("license-gate").assertIsDisplayed()
        compose.onNodeWithTag("welcome-login").assertDoesNotExist()
        compose.onNodeWithTag("license-submit").assertIsNotEnabled()
        capture("license-gate-phone")

        compose.onNodeWithTag("license-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("wrong-key-000")
        assertEquals("•".repeat(13), displayedKey())
        compose.onNodeWithTag("license-reveal").assertTextEquals("显示").performClick()
        assertEquals("wrong-key-000", displayedKey())
        compose.onNodeWithTag("license-reveal").assertTextEquals("隐藏").performClick()
        assertEquals("•".repeat(13), displayedKey())
        compose.onNodeWithTag("license-submit").performClick()
        compose.waitUntil(5_000) { license.state.value.message != null }
        compose.onNodeWithTag("license-message", useUnmergedTree = true).assertTextEquals("授权码无效，还可尝试 4 次。")
        capture("license-gate-wrong-key")
        assertEquals(0, logins.reads)

        compose.onNodeWithTag("license-key").performTextReplacement("ui-test-key-123")
        compose.onNodeWithTag("license-key").performImeAction()
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.ACTIVE }
        compose.onNodeWithTag("welcome-login").assertIsDisplayed()
        compose.onNodeWithTag("license-gate").assertDoesNotExist()
        compose.waitUntil(5_000) { logins.reads > 0 }
    }

    @Test fun activatedInstallationOpensWithoutTheGate() {
        val store = TestLicenseStore()
        runBlocking { license(store).activate("ui-test-key-123") }
        val license = license(store)
        val runtime = TradingRuntime(MemorySnapshotStore())
        compose.setContent { LicenseGate(license, runtime.theme) { Text("trading", Modifier.testTag("content")) } }
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.ACTIVE }
        compose.onNodeWithTag("content").assertIsDisplayed()
        compose.onNodeWithTag("license-key").assertDoesNotExist()
    }

    @Test fun lockedAttemptsDisableSubmitUntilTheWaitEnds() {
        val store = TestLicenseStore()
        runBlocking { license(store).apply { repeat(5) { activate("wrong-key-$it") } } }
        val license = license(store)
        val runtime = TradingRuntime(MemorySnapshotStore())
        compose.setContent { LicenseGate(license, runtime.theme) { Text("trading", Modifier.testTag("content")) } }
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.REQUIRED }
        compose.onNodeWithTag("license-key").performTextInput("ui-test-key-123")
        compose.onNodeWithTag("license-submit").assertIsNotEnabled()
        compose.onNodeWithTag("license-message", useUnmergedTree = true).assertTextEquals("输错次数过多，请在 30 秒后重试。")
        now += 30_000
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("license-message", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("license-submit").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.ACTIVE }
        compose.onNodeWithTag("content").assertIsDisplayed()
    }

    @Test fun unsavedActivationIsReportedOverTheApp() {
        val store = TestLicenseStore().apply { failWrite = true }
        val license = license(store)
        val runtime = TradingRuntime(MemorySnapshotStore())
        compose.setContent { LicenseGate(license, runtime.theme) { Text("trading", Modifier.testTag("content")) } }
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.REQUIRED }
        compose.onNodeWithTag("license-key").performTextInput("ui-test-key-123")
        compose.onNodeWithTag("license-submit").performClick()
        compose.waitUntil(5_000) { license.state.value.status == LicenseStatus.ACTIVE }
        compose.onNodeWithText("授权未保存").assertIsDisplayed()
        compose.onNodeWithTag("license-notice-ok").performClick()
        compose.onNodeWithText("授权未保存").assertDoesNotExist()
        compose.onNodeWithTag("content").assertIsDisplayed()
    }
}
