package com.unitrading.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import java.awt.Frame
import java.awt.MouseInfo
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
    override fun canDockAtPointer(): Boolean = runCatching {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return@runCatching false
        Frame.getFrames().firstOrNull { frame ->
            frame.isVisible && (frame.extendedState and Frame.ICONIFIED) == 0 &&
                (frame.title == "UniTrading · 交易终端" || frame.title == "UniTrading · 本地模拟 · 不连接真实账户")
        }?.let { main ->
            val bounds = main.bounds
            val top = bounds.y + main.insets.top
            pointer.x >= bounds.x && pointer.x < bounds.x + bounds.width && pointer.y >= top && pointer.y < top + 90
        } ?: false
    }.getOrDefault(false)

    @Composable
    override fun Window(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit, onClose: () -> Unit, content: @Composable () -> Unit) =
        WindowImpl(id, title, bounds, size, onBounds, onClose, null, content)

    @Composable
    override fun ChartWindow(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit,
        onClose: () -> Unit, header: @Composable (Modifier) -> Unit, content: @Composable () -> Unit) =
        WindowImpl(id, title, bounds, size, onBounds, onClose, header, content)

    @Composable
    private fun WindowImpl(id: String, title: String, bounds: WindowBounds?, size: DpSize, onBounds: (WindowBounds) -> Unit,
        onClose: () -> Unit, header: (@Composable (Modifier) -> Unit)?, content: @Composable () -> Unit) = key(id) {
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
        val customTitle = header != null && System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        Window(onCloseRequest = onClose, state = state, title = title, undecorated = customTitle, icon = rememberVectorPainter(AppIcons.LogoTile)) {
            if (header == null) content() else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .testTag("detached-title-toolbar-$id"), verticalAlignment = Alignment.CenterVertically) {
                    if (customTitle) {
                        WindowDraggableArea {
                            Row(Modifier.fillMaxHeight().padding(start = 7.dp, end = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppLogo(20.dp); Spacer(Modifier.width(6.dp))
                                Text("UniTrading · 交易终端", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                        header(Modifier.widthIn(max = 340.dp).fillMaxHeight())
                        WindowDraggableArea(Modifier.weight(1f).fillMaxHeight().testTag("detached-title-drag-area-$id")) { }
                        WindowButton("最小化", "detached-window-minimize-$id", "−") { state.isMinimized = true }
                        WindowButton(if (state.placement == WindowPlacement.Maximized) "还原" else "最大化", "detached-window-maximize-$id", "□") {
                            state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                        }
                        WindowButton("收回标签", "detached-window-close-$id", "×", onClose)
                    } else {
                        AppLogo(20.dp, Modifier.padding(start = 4.dp))
                        Text("UniTrading · 交易终端", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                        header(Modifier.weight(1f))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}
