package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AccountAliasUiTest {
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val account = plugin.accounts.first()

    private fun ComposeUiTest.render() =
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) } } }

    private fun ComposeUiTest.rename(picker: String, row: String, alias: String) {
        onNodeWithTag(picker).performClick()
        onNodeWithTag(row).performClick()
        onNodeWithTag("alias-field").performTextReplacement(alias)
        onNodeWithTag("alias-save").performClick()
    }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("../artifacts/$name.png"); file.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun anAccountAliasShowsFirstWithTheBrokersNameUnderItAndResets() = runDesktopComposeUiTest(width = 400, height = 800) {
        render()
        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("rename-account-${account.id}").performClick()
        onNodeWithTag("alias-field").performTextReplacement("日内 50K")
        capture("account-alias-dialog")
        onNodeWithTag("alias-save").performClick()
        onNodeWithTag("quick-account-picker").assertTextContains("日内 50K")
        assertEquals("日内 50K", AccountAliasesController(store).aliases.value.account(plugin.descriptor.id, account), "saved on this device")

        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("quick-account-${account.id}").assertTextContains("日内 50K").assertTextContains(account.name, substring = true)
        capture("account-alias-picker")
        onNodeWithTag("quick-account-${account.id}").performClick()
        onNodeWithTag("nav-4").performClick()
        onNodeWithText("${plugin.descriptor.name} · 日内 50K").assertExists()

        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("rename-account-${account.id}").performClick()
        onNodeWithTag("alias-reset").performClick()
        onNodeWithTag("quick-account-picker").assertTextContains(account.name)
        onNodeWithText("${plugin.descriptor.name} · ${account.name}").assertExists()
    }

    @Test fun aLoginAliasNamesTheLoginInThePickerAndInSettings() = runDesktopComposeUiTest(width = 400, height = 800) {
        render()
        rename("quick-login-picker", "rename-login-${plugin.descriptor.id}", "练习号")
        onNodeWithTag("quick-login-picker").assertTextContains("练习号")
        onNodeWithTag("quick-login-picker").performClick()
        onNodeWithTag("quick-login-${plugin.descriptor.id}").assertTextContains("练习号").assertTextContains(plugin.descriptor.name, substring = true)
        onNodeWithTag("quick-login-${plugin.descriptor.id}").performClick()
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("settings-account").assertTextContains("练习号 · ${account.currency}")
    }

    @Test fun anAliasThatCannotBeSavedKeepsTheDialogOpenWithTheReason() = runDesktopComposeUiTest(width = 400, height = 800) {
        val failing = object : SnapshotStore by store {
            override fun write(key: String, value: String) { if (key == AccountAliasesController.KEY) error("disk full") else store.write(key, value) }
        }
        val local = TradingRuntime(failing, includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(local) } } }
        rename("quick-account-picker", "rename-account-${account.id}", "日内")
        onNodeWithTag("alias-field").assertExists()
        onNodeWithText("账户别名保存失败，别名未变更。").assertExists()
        onNodeWithTag("alias-cancel").performClick()
        onNodeWithTag("quick-account-picker").assertTextContains(account.name)
    }
}
