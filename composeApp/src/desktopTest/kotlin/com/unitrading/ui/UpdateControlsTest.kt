package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.demo.DemoBroker
import com.unitrading.demo.DemoConfigs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class UpdateControlsTest {
    @get:Rule val compose = createComposeRule()

    private class FakeUpdates(override val install: UpdateInstall = UpdateInstall.RESTART) : AppUpdates {
        override val currentVersion = "1.3.1"
        override val check = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
        override val progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
        var checks = 0
        var cancels = 0
        var finishes = 0
        val started = ArrayList<ReleasePackage>()
        override fun checkNow() { checks++ }
        override fun start(release: ReleasePackage) { started += release }
        override fun cancel() { cancels++ }
        override fun finish() { finishes++ }
    }

    private val file = ReleasePackage("v1.4.0", "UniTrading-1.4.0-windows-x64.zip", listOf("https://api.github.com/x"), 73_108_933, "a".repeat(64))
    private val page = "https://github.com/Mind2Money/UniTrading/releases/tag/v1.4.0"
    private val available = UpdateCheck.Available(AppRelease("v1.4.0", page, file))
    private val opened = ArrayList<String>()
    private val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    private val demo = DemoBroker(DemoConfigs.all.first())

    private fun registry(vararg accounts: AccountSnapshot, ready: Boolean = true) = BrokerRegistry(listOf(object : BrokerPlugin by demo {
        override val descriptor = BrokerDescriptor("server", "Server", "test", simulated = false, environment = BrokerEnvironment.REMOTE_DEMO)
        override val accounts = accounts.map { AccountInfo(it.accountId, it.accountId) }
        override val market = MutableStateFlow(MarketSnapshot(connected = ready, tradingReady = ready))
        override fun openSession(accountId: String): AccountSession {
            val base = demo.openSession(demo.accounts.first().id)
            return object : AccountSession by base { override val state = MutableStateFlow(accounts.first { it.accountId == accountId }) }
        }
    }))

    private fun show(updates: AppUpdates, registry: BrokerRegistry = BrokerRegistry(emptyList()), busy: Boolean = false) {
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides links) { TradingTheme { Column { AppUpdateRows(updates, registry, busy) } } } }
    }

    @Test fun nothingHappensUntilTheCheckIsPressed() {
        val updates = FakeUpdates()
        show(updates)
        compose.onNodeWithTag("update-check").assertTextContains("当前版本 1.3.1 · 尚未检查", substring = true)
        assertEquals(0, updates.checks)
        compose.onNodeWithTag("update-install").assertDoesNotExist()
        compose.onNodeWithTag("update-release-page").assertDoesNotExist()
        compose.onNodeWithTag("update-check").performClick()
        assertEquals(1, updates.checks)
    }

    @Test fun aCheckInProgressCannotBeRepeated() {
        val updates = FakeUpdates().also { it.check.value = UpdateCheck.Checking }
        show(updates)
        compose.onNodeWithTag("update-check").assertIsNotEnabled()
    }

    @Test fun anAvailableReleaseCanBeInstalledAndItsPageOpened() {
        val updates = FakeUpdates().also { it.check.value = available }
        show(updates)
        compose.onNodeWithTag("update-check").assertTextContains("发现新版本 v1.4.0", substring = true)
        compose.onNodeWithTag("update-install").assertTextContains("更新到 v1.4.0", substring = true).assertTextContains("下载 69.7 MB，校验后替换程序并重启", substring = true)
        compose.onNodeWithTag("update-install").performClick()
        assertEquals(listOf(file), updates.started)
        compose.onNodeWithTag("update-release-page").performClick()
        assertEquals(listOf(page), opened)
    }

    @Test fun androidSaysTheSystemInstallsIt() {
        val updates = FakeUpdates(UpdateInstall.SYSTEM_INSTALLER).also { it.check.value = available }
        show(updates)
        compose.onNodeWithTag("update-install").assertTextContains("校验后由系统安装", substring = true)
        updates.progress.value = UpdateProgress.Ready("v1.4.0")
        compose.onNodeWithTag("update-finish").assertTextContains("安装 v1.4.0", substring = true).performClick()
        assertEquals(1, updates.finishes)
    }

    @Test fun aReleaseWithoutAVerifiableFileOnlyOffersItsPage() {
        val updates = FakeUpdates().also { it.check.value = UpdateCheck.Available(AppRelease("v1.4.0", page, null)) }
        show(updates)
        compose.onNodeWithTag("update-install").assertDoesNotExist()
        compose.onNodeWithTag("update-blocked").assertTextContains("手动下载", substring = true)
        compose.onNodeWithTag("update-release-page").assertIsEnabled()
    }

    @Test fun openTradesDisableTheUpdateAndSayWhy() {
        val updates = FakeUpdates().also { it.check.value = available }
        val position = Position("p", "MES", Side.BUY, 100, 500, 0, marginMicros = 0)
        show(updates, registry(AccountSnapshot("a", 1, positions = listOf(position))))
        compose.onNodeWithTag("update-install").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), updates.started)
        compose.onNodeWithTag("update-blocked").assertTextContains("有持仓或挂单", substring = true)
        compose.onNodeWithTag("update-release-page").assertIsEnabled()
    }

    @Test fun anUnconfirmedAccountDisablesTheUpdateAndSaysWhy() {
        val updates = FakeUpdates().also { it.check.value = available }
        show(updates, registry(AccountSnapshot("a", 1), ready = false))
        compose.onNodeWithTag("update-install").assertIsNotEnabled()
        compose.onNodeWithTag("update-blocked").assertTextContains("无法确认", substring = true)
    }

    @Test fun anOrderInFlightDisablesTheUpdate() {
        val updates = FakeUpdates().also { it.check.value = available }
        show(updates, busy = true)
        compose.onNodeWithTag("update-install").assertIsNotEnabled()
    }

    @Test fun aRunningDownloadShowsProgressAndCanBeCancelled() {
        val updates = FakeUpdates().also { it.check.value = available; it.progress.value = UpdateProgress.Downloading("v1.4.0", 36_554_466, 73_108_933) }
        show(updates)
        compose.onNodeWithTag("update-install").assertDoesNotExist()
        compose.onNodeWithTag("update-check").assertIsNotEnabled()
        compose.onNodeWithTag("update-progress").assertExists()
        compose.onNodeWithText("34.8 MB / 69.7 MB").assertExists()
        compose.onNodeWithTag("update-cancel").performClick()
        assertEquals(1, updates.cancels)
    }

    @Test fun laterStagesHaveNoCancelAndAHeldUpdateWaitsForTheAccount() {
        val updates = FakeUpdates().also { it.check.value = available; it.progress.value = UpdateProgress.Preparing("v1.4.0") }
        val position = Position("p", "MES", Side.BUY, 100, 500, 0, marginMicros = 0)
        show(updates, registry(AccountSnapshot("a", 1, positions = listOf(position))))
        compose.onNodeWithText("正在校验 v1.4.0").assertExists()
        compose.onNodeWithTag("update-cancel").assertDoesNotExist()
        compose.onNodeWithTag("update-blocked").assertDoesNotExist()
        updates.progress.value = UpdateProgress.Ready("v1.4.0")
        compose.onNodeWithTag("update-finish").assertTextContains("重启并更新到 v1.4.0", substring = true).assertIsNotEnabled()
        compose.onNodeWithTag("update-blocked").assertExists()
        updates.progress.value = UpdateProgress.Restarting("v1.4.0")
        compose.onNodeWithText("正在重启并更新到 v1.4.0").assertExists()
    }

    @Test fun aFailureIsWordedAndCanBeRetried() {
        val updates = FakeUpdates().also { it.check.value = available; it.progress.value = UpdateProgress.Failed("UPDATE_CORRUPT", "v1.4.0") }
        show(updates)
        compose.onNodeWithTag("update-failure").assertTextContains("SHA-256", substring = true)
        compose.onNodeWithTag("update-install").performClick()
        assertEquals(listOf(file), updates.started)
    }

    @Test fun theFinishedUpdateIsReported() {
        val updates = FakeUpdates().also { it.progress.value = UpdateProgress.Installed("v1.4.0") }
        show(updates)
        compose.onNodeWithTag("update-installed").assertTextContains("已更新到 v1.4.0", substring = true)
    }

    @Test fun failedChecksSayWhy() {
        val updates = FakeUpdates()
        show(updates)
        for ((code, text) in listOf("UPDATE_RATE_LIMITED" to "限流", "UPDATE_NOT_PUBLIC" to "未找到", "UPDATE_INVALID" to "无法核实", "UPDATE_UNAVAILABLE" to "暂时无法检查")) {
            updates.check.value = UpdateCheck.Failed(code)
            compose.onNodeWithTag("update-check").assertTextContains(text, substring = true)
        }
        updates.check.value = UpdateCheck.Current("v1.3.1")
        compose.onNodeWithTag("update-check").assertTextContains("已是最新版本", substring = true)
        updates.check.value = UpdateCheck.Ahead("v1.3.0")
        compose.onNodeWithTag("update-check").assertTextContains("高于最新正式版 v1.3.0", substring = true)
    }

    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onNodeWithTag("settings-page").captureToImage().toAwtImage(), "png", target)
    }

    @Test fun theSettingsPageHasTheSectionOnlyWherePlatformsProvideIt() {
        val updates = FakeUpdates().also { it.check.value = available; it.progress.value = UpdateProgress.Failed("UPDATE_DOWNLOAD", "v1.4.0") }
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        runtime.paused.value = true
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 840.dp)) { TradingScreen(runtime, updates = updates) }
        } }
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("update-check").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("update-install").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("update-privacy").performScrollTo().assertIsDisplayed()
        capture("settings-update-phone")
    }

    @Test fun platformsWithoutAnUpdaterShowNoSection() {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        runtime.paused.value = true
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 840.dp)) { TradingScreen(runtime) }
        } }
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-account").assertExists()
        compose.onNodeWithTag("update-check").assertDoesNotExist()
    }
}
