package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import com.unitrading.app.WindowBounds

/**
 * Windows for UI tests. A test cannot open real ones, so each open window is kept here with what the app gave it, and
 * the test shows their content beside the screen with [Windows], under the tag `fake-window-<id>`.
 */
internal class FakePanelWindows : PanelWindowHost {
    class Open(val title: String, val bounds: WindowBounds?, val size: DpSize, val onBounds: (WindowBounds) -> Unit, val onClose: () -> Unit,
        val locals: CompositionLocalContext, val content: @Composable () -> Unit)

    val open = mutableStateMapOf<String, Open>()

    @Composable
    override fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit) {
        // A real window passes the composition locals of where it was opened on to its content; so does this one.
        val entry = Open(title, bounds, size, onBounds, onClose, currentCompositionLocalContext, content)
        SideEffect { open[id] = entry }
        DisposableEffect(id) { onDispose { open.remove(id) } }
    }

    /** The content of every open window, side by side in equal shares of the row. */
    @Composable
    fun WindowRow(modifier: Modifier) {
        Row(modifier) {
            for (id in open.keys.sorted()) {
                val window = open[id]
                if (window != null) key(id) {
                    CompositionLocalProvider(window.locals) { Box(Modifier.weight(1f).fillMaxHeight().testTag("fake-window-$id")) { window.content() } }
                }
            }
        }
    }
}
