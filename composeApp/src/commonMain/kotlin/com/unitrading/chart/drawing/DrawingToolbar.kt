package com.unitrading.chart.drawing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.ui.AppIcons
import com.unitrading.ui.DividerLine
import com.unitrading.ui.TradingColors
import kotlin.math.roundToInt

internal val DrawingToolbarWidth = 46.dp
private val ButtonHeight = 38.dp
private val MenuItemHeight = 34.dp

/** One button of the toolbar; [menu] adds the arrow that opens its flyout. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButton(icon: ImageVector, label: String, tag: String, active: Boolean, onClick: () -> Unit, menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(ButtonHeight)) {
        TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text(label) } }, rememberTooltipState(), focusable = false,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
            Box(Modifier.size(32.dp).clip(MaterialTheme.shapes.small).background(if (active) scheme.secondaryContainer else Color.Transparent)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = menu?.let { { open = true } }).testTag(tag)
                .semantics { contentDescription = label; selected = active }, contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = if (active) scheme.primary else scheme.onSurfaceVariant)
            }
        }
        if (menu != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 1.dp).size(width = 9.dp, height = 28.dp).clip(MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button) { open = true }.testTag("$tag-menu").semantics { contentDescription = "$label 选项" }, contentAlignment = Alignment.Center) {
                Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.size(11.dp), tint = scheme.onSurfaceVariant.copy(alpha = .75f))
            }
            DropdownMenu(open, { open = false }, offset = DpOffset(DrawingToolbarWidth, -ButtonHeight)) { menu { open = false } }
        }
    }
}

@Composable
private fun MenuHeader(title: String) {
    Text(title, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = TradingColors.Muted)
}

@Composable
private fun MenuRow(icon: ImageVector?, label: String, tag: String, selected: Boolean = false, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick, leadingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingIcon = trailing, modifier = Modifier.height(MenuItemHeight).testTag(tag),
        contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
    )
}

@Composable
private fun CheckRow(label: String, tag: String, checked: Boolean, onToggle: () -> Unit) {
    MenuRow(null, label, tag, checked, trailing = { if (checked) Icon(AppIcons.Check, contentDescription = "已启用", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }, onClick = onToggle)
}

@Composable
private fun ToolRow(drawings: ChartDrawings, tool: DrawingTool, close: () -> Unit) {
    val favorite = drawings.isFavorite(tool)
    MenuRow(DrawingIcons.of(tool), tool.label, "drawing-tool-${tool.name}", drawings.tool == tool, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tool.shortcut?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted) }
            Box(Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button) { drawings.toggleFavorite(tool) }.testTag("drawing-favorite-${tool.name}")
                .semantics { contentDescription = if (favorite) "取消收藏 ${tool.label}" else "收藏 ${tool.label}" }, contentAlignment = Alignment.Center) {
                Icon(if (favorite) AppIcons.Star else AppIcons.StarBorder, contentDescription = null, Modifier.size(16.dp), tint = if (favorite) TradingColors.Accent else TradingColors.Muted.copy(alpha = .6f))
            }
        }
    }) { drawings.chooseTool(tool); close() }
}

@Composable
private fun cursorIcon(cursor: ChartCursor): ImageVector = when (cursor) {
    ChartCursor.CROSS -> DrawingIcons.Cross
    ChartCursor.DOT -> DrawingIcons.Dot
    ChartCursor.ARROW -> DrawingIcons.ArrowCursor
    ChartCursor.DEMONSTRATION -> DrawingIcons.Demonstration
    ChartCursor.ERASER -> DrawingIcons.Eraser
}

private val EMOJI = ("😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥😮🤐😯😪😫🥱😴😌😛😜😝🤤😒😓😔😕🙃🤑😲🙁😖😞😟😤😢😭😦😧😨😩🤯😬😰😱🥵🥶😳🤪😵😡😠🤬😷🤒🤕🤢🤮🤧😇🥳🥺🤠🤡🤥🤫🤭🧐🤓😈👿👹👺💀👻👽🤖" +
    "👍👎👌✌🤞🤟🤘👈👉👆👇✋👏🙌🙏💪🔥💯💥💫⭐🌟✨⚡💧🌈🌙❄🚀🌕🌑💰💵💎📈📉📊🏆🎯🔔⚠❗❓✅❌⛔🔴🟢🟡🔵⚪⚫").codePointsAsStrings()
private val STICKERS = "🚀🌕💎🙌🐂🐻🐳🦈🐋💰🏦📈📉🔥🎯🏆💣🧨🎉🎊👑⚔🛡🪙🍀🌋🌊🤑🧠🦍".codePointsAsStrings()
private val SYMBOLS = "★☆♥♦♣♠●○■□▲△▼▽◆◇✓✗✚✖➜➤⇧⇩⇦⇨↑↓←→↗↘↙↖∞≈≠±%$€¥£".codePointsAsStrings()

/** Splits a string into whole code points, so surrogate pairs and their following variation selectors stay together. */
private fun String.codePointsAsStrings(): List<String> = buildList {
    var i = 0
    while (i < this@codePointsAsStrings.length) {
        val end = if (this@codePointsAsStrings[i].isHighSurrogate() && i + 1 < this@codePointsAsStrings.length) i + 2 else i + 1
        add(this@codePointsAsStrings.substring(i, end))
        i = end
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(drawings: ChartDrawings, close: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("表情", "贴纸", "符号")
    Column(Modifier.width(320.dp).padding(horizontal = 8.dp)) {
        TabRow(tab, containerColor = Color.Transparent) {
            labels.forEachIndexed { index, label -> Tab(tab == index, { tab = index }, text = { Text(label) }, modifier = Modifier.testTag("icon-tab-$index")) }
        }
        val glyphs = when (tab) { 0 -> EMOJI; 1 -> STICKERS; else -> SYMBOLS }
        FlowRow(Modifier.padding(top = 8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            glyphs.forEachIndexed { index, glyph ->
                Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button) { drawings.chooseTool(DrawingTool.ICON, glyph); close() }
                    .testTag("icon-$tab-$index"), contentAlignment = Alignment.Center) {
                    Text(glyph, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/**
 * The drawing tools down the left of the chart, in TradingView's order: cursor, the tool flyouts, icons, measure and zoom,
 * then magnet, keep drawing, lock, hide and remove. [onRemoveIndicators] clears the chart's indicators.
 */
@Composable
internal fun DrawingToolbar(drawings: ChartDrawings, indicatorCount: Int, onRemoveIndicators: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TradingColors
    Column(modifier.width(DrawingToolbarWidth).fillMaxHeight().background(colors.Surface).verticalScroll(rememberScrollState()).testTag("drawing-toolbar").padding(vertical = 4.dp)) {
        ToolbarButton(cursorIcon(drawings.cursor), drawings.cursor.label, "drawing-cursor", drawings.tool == null && !drawings.zooming, { drawings.cancelTool(); if (drawings.zooming) drawings.toggleZoom() }) { close ->
            ChartCursor.entries.forEach { cursor ->
                MenuRow(cursorIcon(cursor), cursor.label, "drawing-cursor-${cursor.name}", drawings.cursor == cursor) { drawings.chooseCursor(cursor); close() }
            }
            DividerLine(Modifier.padding(vertical = 4.dp))
            CheckRow("长按时显示数值工具提示", "drawing-values-tooltip", drawings.valuesTooltip) { drawings.valuesTooltip = !drawings.valuesTooltip }
        }
        ToolGroup.entries.forEach { group ->
            val recent = drawings.recentTool(group)
            ToolbarButton(DrawingIcons.of(recent), recent.label, "drawing-group-${group.name}", drawings.tool?.let { it in group.tools } == true, { drawings.chooseTool(recent) }) { close ->
                group.sections.forEach { section ->
                    MenuHeader(section.title)
                    section.tools.forEach { tool -> ToolRow(drawings, tool, close) }
                }
            }
        }
        ToolbarButton(DrawingIcons.Smile, "图标", "drawing-group-ICON", drawings.tool == DrawingTool.ICON, { drawings.chooseTool(DrawingTool.ICON) }) { close -> IconPicker(drawings, close) }
        DividerLine(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        ToolbarButton(DrawingIcons.Measure, "测量", "drawing-measure", drawings.tool == DrawingTool.MEASURE, { drawings.chooseMeasure() })
        ToolbarButton(AppIcons.ZoomIn, "放大", "drawing-zoom", drawings.zooming, { drawings.toggleZoom() })
        DividerLine(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        val magnetOn = drawings.magnet != MagnetMode.OFF
        ToolbarButton(DrawingIcons.Magnet, "磁铁模式会将放置在价格K线附近的绘图吸附到最近的OHLC值。", "drawing-magnet", magnetOn, { drawings.magnet = if (magnetOn) MagnetMode.OFF else MagnetMode.WEAK }) { close ->
            listOf(MagnetMode.WEAK, MagnetMode.STRONG).forEach { mode ->
                CheckRow(mode.label, "drawing-magnet-${mode.name}", drawings.magnet == mode) { drawings.magnet = if (drawings.magnet == mode) MagnetMode.OFF else mode; close() }
            }
            DividerLine(Modifier.padding(vertical = 4.dp))
            CheckRow("吸附到指标", "drawing-magnet-indicators", drawings.magnetIndicators) { drawings.magnetIndicators = !drawings.magnetIndicators }
        }
        ToolbarButton(DrawingIcons.KeepDrawing, "继续绘图", "drawing-keep", drawings.keepDrawing, { drawings.keepDrawing = !drawings.keepDrawing })
        val lockAll = drawings.lockDrawings && drawings.lockIndicators
        ToolbarButton(if (lockAll) DrawingIcons.Locked else DrawingIcons.Unlocked, "锁定绘图和指标", "drawing-lock", lockAll, { val next = !lockAll; drawings.lockDrawings = next; drawings.lockIndicators = next }) { close ->
            CheckRow("锁定绘图", "drawing-lock-drawings", drawings.lockDrawings) { drawings.lockDrawings = !drawings.lockDrawings; close() }
            CheckRow("锁定指标", "drawing-lock-indicators", drawings.lockIndicators) { drawings.lockIndicators = !drawings.lockIndicators; close() }
            CheckRow("锁定所有", "drawing-lock-all", lockAll) { val next = !lockAll; drawings.lockDrawings = next; drawings.lockIndicators = next; close() }
        }
        val hideAll = drawings.hideDrawings && drawings.hideIndicators && drawings.hideTrading
        val anyHidden = drawings.hideDrawings || drawings.hideIndicators || drawings.hideTrading
        ToolbarButton(if (anyHidden) AppIcons.VisibilityOff else AppIcons.Visibility, "隐藏所有图形", "drawing-hide", anyHidden, { drawings.hideDrawings = !drawings.hideDrawings }) { close ->
            CheckRow("隐藏绘图", "drawing-hide-drawings", drawings.hideDrawings) { drawings.hideDrawings = !drawings.hideDrawings; close() }
            CheckRow("隐藏指标", "drawing-hide-indicators", drawings.hideIndicators) { drawings.hideIndicators = !drawings.hideIndicators; close() }
            CheckRow("隐藏仓位和订单", "drawing-hide-trading", drawings.hideTrading) { drawings.hideTrading = !drawings.hideTrading; close() }
            CheckRow("隐藏所有", "drawing-hide-all", hideAll) { val next = !hideAll; drawings.hideDrawings = next; drawings.hideIndicators = next; drawings.hideTrading = next; close() }
            if (drawings.hiddenCount > 0) MenuRow(null, "显示 ${drawings.hiddenCount} 个已隐藏的绘图", "drawing-show-hidden") { drawings.showHidden(); close() }
        }
        DividerLine(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        val count = drawings.drawingCount()
        ToolbarButton(AppIcons.Delete, "移除对象", "drawing-remove", false, { drawings.removeAllDrawings() }) { close ->
            MenuRow(null, "移除 $count 个绘图", "drawing-remove-drawings") { drawings.removeAllDrawings(); close() }
            MenuRow(null, "移除 $indicatorCount 个指标", "drawing-remove-indicators") { onRemoveIndicators(); close() }
            MenuRow(null, "移除 $count 个绘图和 $indicatorCount 个指标", "drawing-remove-all") { drawings.removeAllDrawings(); onRemoveIndicators(); close() }
            DividerLine(Modifier.padding(vertical = 4.dp))
            CheckRow("始终删除锁定的绘图", "drawing-remove-locked", drawings.removeLockedToo) { drawings.removeLockedToo = !drawings.removeLockedToo }
        }
        DividerLine(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        ToolbarButton(AppIcons.Undo, "撤销", "drawing-undo", false, { if (drawings.canUndo) drawings.undo() })
        ToolbarButton(AppIcons.Redo, "重做", "drawing-redo", false, { if (drawings.canRedo) drawings.redo() })
    }
}

/** The starred tools in a small bar that can be dragged out of the way. */
@Composable
internal fun FavoriteDrawingsBar(drawings: ChartDrawings, modifier: Modifier = Modifier) {
    if (drawings.favorites.isEmpty() || !drawings.favoritesBarVisible) return
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    Surface(modifier.offset { offset }.testTag("drawing-favorites"), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 16.dp, height = 36.dp).pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    offset = IntOffset(offset.x + amount.x.roundToInt(), offset.y + amount.y.roundToInt())
                }
            }, contentAlignment = Alignment.Center) { Icon(AppIcons.DragIndicator, contentDescription = "拖动收藏栏", Modifier.size(14.dp), tint = TradingColors.Muted) }
            drawings.favorites.forEach { tool ->
                Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(if (drawings.tool == tool) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(role = Role.Button) { drawings.chooseTool(tool) }.testTag("favorite-${tool.name}").semantics { contentDescription = tool.label }, contentAlignment = Alignment.Center) {
                    Icon(DrawingIcons.of(tool), contentDescription = null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
