package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState

/** Windows' own frame is replaced so chart tabs can occupy the same title row. */
@Composable
internal fun WindowScope.WindowsTitleBar(slot: DesktopTitleBarSlot, state: WindowState, title: String, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp).background(colors.surface).testTag("desktop-title-toolbar"),
        verticalAlignment = Alignment.CenterVertically) {
        WindowDraggableArea {
            Row(Modifier.height(40.dp).padding(start = 7.dp, end = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                AppLogo(20.dp)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = colors.onSurface, maxLines = 1)
            }
        }
        val tabs = slot.tabs
        if (tabs == null) WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) { }
        else Box(Modifier.weight(1f).fillMaxHeight()) {
            WorkspaceTabStrip(tabs.tabs, tabs.selected, slot::select, slot::detach, slot::close, slot::reorder,
                Modifier.fillMaxWidth(), canDetach = tabs.canDetach, touchSized = tabs.touchSized, onAdd = slot::add, canAdd = tabs.canAdd)
        }
        WindowDraggableArea(Modifier.width(55.dp).fillMaxHeight()) { }
        WindowButton("最小化", "window-minimize", "−") { state.isMinimized = true }
        WindowButton(if (state.placement == WindowPlacement.Maximized) "还原" else "最大化", "window-maximize", "□") {
            state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
        }
        WindowButton("关闭", "window-close", "×", onClose)
    }
}

@Composable
private fun WindowButton(description: String, tag: String, mark: String, onClick: () -> Unit) {
    Box(Modifier.width(44.dp).fillMaxHeight().clickable(onClick = onClick).testTag(tag)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(mark, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
