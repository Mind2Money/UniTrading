package com.unitrading.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize
import com.unitrading.app.WindowBounds

/**
 * Shows part of the workspace in a window of its own. The desktop app opens a real window; tests put the content
 * beside the screen instead. [Window] stays in the composition for as long as the window is open. [id] names it among the open windows, [bounds] is where it
 * was last, [size] the size of a window opened for the first time, [onBounds] reports every move and resize once it has
 * settled, and [onClose] is the user closing the window.
 */
internal interface PanelWindowHost {
    @Composable
    fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit)
}

/** The windows of this platform, or null where an app has only one. */
internal expect val platformPanelWindowHost: PanelWindowHost?

internal val LocalPanelWindowHost = staticCompositionLocalOf { platformPanelWindowHost }
