package com.unitrading.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.unitrading.app.IndicatorController
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.pine.DEFAULT_PINE_SOURCE
import com.unitrading.chart.pine.MAX_PINE_SOURCE_LENGTH
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class PineIndicatorSettingsUiTest {
    private val store = MemorySnapshotStore()
    private val controller = IndicatorController(store)
    private val edited = """//@version=5
indicator("Custom", overlay=false)
plot(close, "Close")"""

    private fun ComposeUiTest.render() {
        setContent {
            TradingTheme {
                var open by remember { mutableStateOf(false) }
                TextButton({ open = true }, Modifier.testTag("open-indicators")) { Text("图表指标") }
                if (open) IndicatorSettingsDialog(controller) { open = false }
            }
        }
        openIndicators()
    }

    private fun ComposeUiTest.openIndicators() = onNodeWithTag("open-indicators").performClick()
    private fun ComposeUiTest.addPine() = onNodeWithTag("add-indicator-PINE").performScrollTo().performClick()
    private fun ComposeUiTest.source(index: Int) = onNodeWithTag("indicator-pine-source-$index").performScrollTo()
    private fun ComposeUiTest.visibility(index: Int) = onNodeWithTag("indicator-visible-$index").performScrollTo()

    @Test fun pineScriptsCanBeEditedReorderedHiddenAndReopened() = runDesktopComposeUiTest(width = 360, height = 480) {
        render()
        addPine()
        source(0).assertTextContains(DEFAULT_PINE_SOURCE)
        addPine()
        source(1).performTextReplacement(edited)
        visibility(1).performClick().assertIsOff()

        // Use the same accessibility reorder action as the built-in indicators.
        onNodeWithTag("indicator-drag-1", useUnmergedTree = true).performScrollTo()
        val grip = onNode(hasAnyDescendant(hasTestTag("indicator-drag-1")) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
        val moveUp = grip.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        runOnIdle { moveUp.action() }
        source(0).assertTextContains(edited)
        source(1).assertTextContains(DEFAULT_PINE_SOURCE)

        onNodeWithTag("indicators-save").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("indicator-settings").fetchSemanticsNodes().isEmpty() }
        val saved = IndicatorController(store).settings.value.indicators
        assertEquals(listOf(IndicatorKind.PINE, IndicatorKind.PINE), saved.map { it.kind })
        assertEquals(listOf(edited, DEFAULT_PINE_SOURCE), saved.map { it.pineSource })
        assertFalse(saved.first().visible)
        openIndicators()
        source(0).assertTextContains(edited)
        visibility(0).assertIsOff()
        visibility(1).assertIsOn()

        source(0).performTextReplacement(DEFAULT_PINE_SOURCE)
        onNodeWithTag("remove-indicator-1").performScrollTo().performClick()
        onNodeWithTag("indicators-cancel").performClick()
        assertEquals(saved, controller.settings.value.indicators)
        assertEquals(saved, IndicatorController(store).settings.value.indicators)
    }

    @Test fun syntaxAndUnsupportedApiErrorsIdentifyTheHiddenScriptWithoutSaving() = runDesktopComposeUiTest(width = 360, height = 360) {
        render()
        addPine()
        addPine()
        visibility(1).performClick().assertIsOff()

        fun reject(line: String, expectedText: String) {
            source(1).performTextReplacement("//@version=6\nindicator(\"Broken\")\n$line")
            onNodeWithTag("indicators-save").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("indicator-pine-error").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("indicator-pine-error").assertIsDisplayed().assertTextContains("Pine 2 · 第 3 行：", substring = true)
                .assertTextContains(expectedText, substring = true)
            onNodeWithTag("indicators-save").assertIsNotEnabled()
            assertEquals(emptyList(), controller.settings.value.indicators)
            assertNull(store.read("indicators-v1"))
        }
        reject("plot(close", "未闭合")
        reject("plot(request.security(syminfo.tickerid, \"D\", close))", "request.security")
        onNodeWithTag("indicators-cancel").assertIsEnabled().performClick()
        onNodeWithTag("indicator-settings").assertDoesNotExist()
    }

    @Test fun anOversizedPasteLeavesTheSourceIntactAndBlankSourceCannotBeSaved() = runDesktopComposeUiTest(width = 360, height = 480) {
        render()
        addPine()
        source(0).performTextReplacement("x".repeat(MAX_PINE_SOURCE_LENGTH + 1))
        source(0).assertTextContains(DEFAULT_PINE_SOURCE)
        onNodeWithText("超长输入未应用", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        source(0).performTextReplacement("")
        onNodeWithTag("indicators-save").assertIsNotEnabled()
        onNodeWithText("请输入 Pine 源码。", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
