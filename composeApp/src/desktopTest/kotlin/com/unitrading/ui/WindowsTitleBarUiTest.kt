package com.unitrading.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import org.junit.Test
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
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

    @Test fun titleBarRespondsToNativeMouse() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val events = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val slot = DesktopTitleBarSlot()
        val tabs = listOf(WorkspaceTabData("main", "A", "", "", 0), WorkspaceTabData("c1", "B", "", "", 0))
        val owner = Any()
        lateinit var frame: ComposeWindow
        SwingUtilities.invokeAndWait {
            slot.publish(owner, DesktopTitleTabs(tabs, "main", true, false),
                { events += "select:$it" }, { events += "detach:$it" }, { events += "close:$it" },
                { events += "order:${it.joinToString(",")}" })
            frame = ComposeWindow()
            val scope = object : FrameWindowScope { override val window: ComposeWindow = frame }
            frame.isUndecorated = true
            frame.setBounds(60, 60, 900, 500)
            frame.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    TradingTheme { with(scope) { WindowsTitleBar(slot, WindowState(), "") { } } }
                    SideEffect { ready.countDown() }
                }
            }
            frame.isVisible = true
            frame.toFront()
            frame.requestFocus()
        }
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS), "Native title row did not become ready")
            val robot = Robot().apply { autoDelay = 30 }
            robot.delay(500)
            val origin = frame.locationOnScreen
            // With a blank window title the first 150 dp tab begins at x=42; the second follows after 4 dp.
            val first = origin.x + 42
            val second = first + 154
            val y = origin.y + 20
            // Scan the second tab with real OS clicks to exercise its label and both icon hit targets.
            for (x in second + 5..second + 115 step 4) {
                robot.mouseMove(x, y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle()
            }
            assertTrue(events.contains("select:c1"), "The native window did not select its tab")
            assertTrue(events.contains("detach:c1"), "The native detach button did not receive a click")
            assertTrue(events.contains("close:c1"), "The native close button did not receive a click")
            robot.mouseMove(second + 12, y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseMove(second - 60, y)
            robot.delay(80)
            robot.mouseMove(first + 10, y)
            robot.delay(80)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            assertTrue(events.contains("order:c1,main"), "The native window did not reorder the tabs")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
