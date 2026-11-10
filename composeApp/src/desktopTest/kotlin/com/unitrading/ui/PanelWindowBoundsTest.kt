package com.unitrading.ui

import com.unitrading.app.WindowBounds
import java.awt.Rectangle
import kotlin.test.*

/** Saved window bounds are restored only where the window's title strip can still be reached. */
class PanelWindowBoundsTest {
    private val primary = Rectangle(0, 0, 1920, 1080)
    private val secondary = Rectangle(1920, 0, 2560, 1440)

    @Test fun aWindowOnAnAttachedScreenIsRestoredAndOneOnAMissingScreenIsNot() {
        val onSecond = WindowBounds(2100f, 200f, 900f, 640f)
        assertTrue(reachable(onSecond, listOf(primary, secondary)))
        assertFalse(reachable(onSecond, listOf(primary)), "the second display is gone")
        assertTrue(reachable(WindowBounds(100f, 100f, 800f, 600f), listOf(primary)))
        assertFalse(reachable(WindowBounds(100f, 100f, 800f, 600f), emptyList()))
    }

    @Test fun enoughOfTheTitleStripHasToShowForTheWindowToBeDraggedBack() {
        assertTrue(reachable(WindowBounds(1800f, 500f, 900f, 640f), listOf(primary)), "120 dp of the title bar remain on screen")
        assertFalse(reachable(WindowBounds(1900f, 500f, 900f, 640f), listOf(primary)), "only 20 dp remain")
        assertFalse(reachable(WindowBounds(100f, -600f, 800f, 640f), listOf(primary)), "the title bar is above the screen")
        assertFalse(reachable(WindowBounds(100f, 1075f, 800f, 640f), listOf(primary)), "the title bar is below the screen")
        assertTrue(reachable(WindowBounds(-700f, 100f, 800f, 600f), listOf(primary)), "100 dp show at the left edge")
    }
}
