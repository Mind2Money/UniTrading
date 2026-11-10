package com.unitrading.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.unitrading.app.WindowBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

internal actual val platformPanelWindowHost: PanelWindowHost? = DesktopPanelWindows

/**
 * Bounds worth restoring: at least [VISIBLE] dp of the window's title strip must lie on a screen that is attached now, or
 * a window saved on a display that is gone would open where nobody can reach it.
 */
internal fun reachable(bounds: WindowBounds, screens: List<Rectangle>): Boolean {
    val strip = Rectangle(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), TITLE_STRIP)
    return screens.any { screen -> screen.intersection(strip).let { it.width >= VISIBLE && it.height >= TITLE_STRIP / 2 } }
}
private const val VISIBLE = 80
private const val TITLE_STRIP = 32

private object DesktopPanelWindows : PanelWindowHost {
    @Composable
    override fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit) = key(id) {
        // Read once: afterwards the window itself is the source of its bounds.
        val state = remember {
            val saved = bounds?.takeIf { it.valid && reachable(it, runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device -> device.defaultConfiguration.bounds } }.getOrDefault(emptyList())) }
            if (saved != null) WindowState(position = WindowPosition(saved.x.dp, saved.y.dp), size = DpSize(saved.width.dp, saved.height.dp))
            else WindowState(position = WindowPosition.PlatformDefault, size = size)
        }
        val report by rememberUpdatedState(onBounds)
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.position, state.size, state.placement) }.collectLatest { (position, windowSize, placement) ->
                // A maximized or minimized window keeps the bounds it will return to.
                if (placement != WindowPlacement.Floating || !position.isSpecified || !windowSize.isSpecified) return@collectLatest
                delay(400)
                report(WindowBounds(position.x.value, position.y.value, windowSize.width.value, windowSize.height.value))
            }
        }
        Window(onCloseRequest = onClose, state = state, title = title, icon = rememberVectorPainter(AppIcons.LogoTile)) { content() }
    }
}
