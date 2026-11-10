package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.unitrading.app.WindowBounds

/**
 * Windows for UI tests. A test cannot open real ones, so each window is a layer over the test surface, under the tag
 * `fake-window-<id>`: the windows stand side by side in a strip [strip] high along its bottom edge, which the test leaves
 * free. Like a real window, a layer is a composition of its own under the place that opened it, so its content is
 * composed after the screen it belongs to.
 */
internal class FakePanelWindows(private val strip: Dp = 440.dp) : PanelWindowHost {
    class Open(val title: String, val bounds: WindowBounds?, val size: DpSize, val onBounds: (WindowBounds) -> Unit, val onClose: () -> Unit)

    val open = mutableStateMapOf<String, Open>()
    private val names = androidx.compose.runtime.mutableStateListOf<String>()

    @Composable
    override fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit) {
        val entry = Open(title, bounds, size, onBounds, onClose)
        SideEffect { open[id] = entry }
        DisposableEffect(id) { names += id; onDispose { names -= id; open.remove(id) } }
        // Equal shares of the strip, in the order of the windows' names. Read from [names], which changes only when a
        // window opens or closes: [open] is written on every pass, and reading it here would compose without end.
        val ids = (names + id).distinct().sorted()
        val index = ids.indexOf(id)
        val count = ids.size
        val height = with(LocalDensity.current) { strip.roundToPx() }
        val place = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(windowSize.width * index / count, windowSize.height - height)
        }
        // Not focusable: a press outside the window reaches the screen under it, as it does between real windows.
        Popup(place, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(Modifier.fillMaxWidth(1f / count).height(strip).testTag("fake-window-$id")) { content() }
        }
    }
}
