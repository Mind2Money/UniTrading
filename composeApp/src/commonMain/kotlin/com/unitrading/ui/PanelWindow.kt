package com.unitrading.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    /** True when the global pointer was released over the main window's tab strip. */
    fun canDockAtPointer(): Boolean = false
    @Composable
    fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit)

    /** A chart window adds its tab to the title row, without changing ordinary detached panels. */
    @Composable
    fun ChartWindow(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit,
        onClose: () -> Unit, header: @Composable (Modifier) -> Unit, content: @Composable () -> Unit) {
        Window(id, title, bounds, size, onBounds, onClose) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("detached-title-toolbar-$id"),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(20.dp, Modifier.padding(start = 4.dp))
                    Text("UniTrading · 交易终端", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                    header(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** The windows of this platform, or null where an app has only one. */
internal expect val platformPanelWindowHost: PanelWindowHost?

internal val LocalPanelWindowHost = staticCompositionLocalOf { platformPanelWindowHost }
