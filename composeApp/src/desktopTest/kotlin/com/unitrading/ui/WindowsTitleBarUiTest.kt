package com.unitrading.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.test.*
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowsTitleBarUiTest {
    @Test fun windowButtonsMinimizeMaximizeRestoreAndRequestClose() = runDesktopComposeUiTest(width = 1100, height = 700) {
        lateinit var frame: ComposeWindow
        runOnIdle { frame = ComposeWindow() }
        val scope = object : FrameWindowScope { override val window: ComposeWindow = frame }
        val state = WindowState()
        val closed = mutableStateOf(false)
        try {
            setContent { TradingTheme { with(scope) { WindowsTitleBar(DesktopTitleBarSlot(), state, "UniTrading · 交易终端") { closed.value = true } } } }
            onNodeWithTag("window-minimize").performClick()
            assertTrue(state.isMinimized)
            onNodeWithTag("window-maximize").performClick()
            assertEquals(WindowPlacement.Maximized, state.placement)
            onNodeWithTag("window-maximize").performClick()
            assertEquals(WindowPlacement.Floating, state.placement)
            onNodeWithTag("window-close").performClick()
            assertTrue(closed.value)
        } finally { runOnIdle { frame.dispose() } }
    }
}
