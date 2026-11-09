package com.unitrading.chart.drawing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.broker.parseFixed
import com.unitrading.chart.dateTime
import com.unitrading.ui.ActionIcon
import com.unitrading.ui.AppIcons
import com.unitrading.ui.ColorPicker
import com.unitrading.ui.DividerLine
import com.unitrading.ui.FormDialog
import com.unitrading.ui.SegmentedChoice
import com.unitrading.ui.TradingColors
import com.unitrading.ui.colorHex
import kotlin.math.roundToInt
import kotlin.time.Instant

private val HUES = listOf("#F23645", "#FF9800", "#FFEB3B", "#4CAF50", "#089981", "#00BCD4", "#2962FF", "#673AB7", "#9C27B0", "#E91E63")
private val GRAYS = listOf("#FFFFFF", "#DBDBDB", "#B8B8B8", "#9C9C9C", "#808080", "#636363", "#4A4A4A", "#333333", "#1F1F1F", "#000000")

/** Grays, the hues, and three tints and two shades of each hue. */
private val swatchRows: List<List<Color>> = buildList {
    add(GRAYS.map { colorOf(it) })
    add(HUES.map { colorOf(it) })
    listOf(.72f, .5f, .28f).forEach { amount -> add(HUES.map { lerp(colorOf(it), Color.White, amount) }) }
    listOf(.2f, .4f).forEach { amount -> add(HUES.map { lerp(colorOf(it), Color.Black, amount) }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchPalette(selected: String?, tag: String, onColor: (String) -> Unit, onCustom: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).width(228.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        swatchRows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { c, color ->
                    val hex = colorHex(color)
                    Box(Modifier.size(21.dp).clip(MaterialTheme.shapes.extraSmall).background(color)
                        .then(if (hex.equals(selected, true)) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall) else Modifier)
                        .clickable(role = Role.Button) { onColor(hex) }.testTag("$tag-$r-$c").semantics { contentDescription = hex })
                }
            }
        }
        TextButton(onCustom, Modifier.testTag("$tag-custom")) { Text("自定义颜色") }
    }
}

/** A swatch that opens the palette; [opacity] adds a slider. */
@Composable
private fun ColorButton(label: String, hex: String?, tag: String, opacity: Float? = null, onOpacity: ((Float) -> Unit)? = null, onOpacityDone: (() -> Unit)? = null, onClear: (() -> Unit)? = null, onColor: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val color = hex?.let { colorOf(it) }
    Box {
        Box(Modifier.size(32.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { open = true }.testTag(tag).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(color ?: Color.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        DropdownMenu(open, { open = false }) {
            SwatchPalette(hex, tag, { onColor(it); open = false }, { custom = true })
            if (onOpacity != null && opacity != null) {
                Row(Modifier.padding(horizontal = 12.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("不透明度 ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(92.dp))
                    Slider(opacity, onOpacity, Modifier.weight(1f).height(28.dp).testTag("$tag-opacity"), valueRange = 0f..1f, onValueChangeFinished = onOpacityDone)
                }
            }
            if (onClear != null) TextButton({ onClear(); open = false }, Modifier.testTag("$tag-none").padding(horizontal = 8.dp)) { Text("不填充") }
        }
        if (custom) ColorPicker(label, color ?: TradingColors.Accent, { custom = false }) { value -> onColor(colorHex(value)); custom = false; open = false }
    }
}

@Composable
private fun MenuButton(label: String, tag: String, content: @Composable (close: () -> Unit) -> Unit, button: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { open = true }.testTag(tag).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { button() }
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

/** Quick edits of the selected drawing; it can be dragged away from what it covers. */
@Composable
internal fun DrawingFloatingBar(drawings: ChartDrawings, modifier: Modifier = Modifier) {
    val d = drawings.selected ?: return
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    /** The drawing as it was before an opacity drag, so the whole drag is one undo step. */
    var opacityStart by remember(d.id) { mutableStateOf<Drawing?>(null) }
    val locked = d.locked || drawings.lockDrawings
    val button = Modifier.size(32.dp)
    fun style(change: DrawingStyle.() -> DrawingStyle) = drawings.update(d.copy(style = d.style.change()))
    Surface(modifier.offset { offset }.testTag("drawing-bar"), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 16.dp, height = 36.dp).pointerInput(Unit) {
                detectDragGestures { change, amount -> change.consume(); offset = IntOffset(offset.x + amount.x.roundToInt(), offset.y + amount.y.roundToInt()) }
            }, contentAlignment = Alignment.Center) { Icon(AppIcons.DragIndicator, contentDescription = "拖动工具条", Modifier.size(14.dp), tint = TradingColors.Muted) }
            ColorButton("线条颜色", d.style.color, "drawing-bar-color") { hex -> style { copy(color = hex) } }
            if (d.tool.hasFill) ColorButton("填充颜色", d.style.fill, "drawing-bar-fill", d.style.fillAlpha, { value -> if (opacityStart == null) opacityStart = d; drawings.preview(d.copy(style = d.style.copy(fillAlpha = value))) }, {
                opacityStart?.let { start -> drawings.preview(start); drawings.update(start.copy(style = start.style.copy(fillAlpha = d.style.fillAlpha))) }
                opacityStart = null
            }, { style { copy(fill = null) } }) { hex -> style { copy(fill = hex) } }
            if (d.tool.hasText) ColorButton("文字颜色", d.style.textColor ?: d.style.color, "drawing-bar-text") { hex -> style { copy(textColor = hex) } }
            MenuButton("线宽", "drawing-bar-width", { close ->
                listOf(1f, 2f, 3f, 4f).forEach { width ->
                    DropdownMenuItem(text = { Text("${width.roundToInt()} px") }, onClick = { style { copy(width = width) }; close() }, modifier = Modifier.testTag("drawing-width-${width.roundToInt()}"))
                }
            }) { Text("${d.style.width.roundToInt()}px", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
            MenuButton("线型", "drawing-bar-dash", { close ->
                DrawingDash.entries.forEach { dash ->
                    DropdownMenuItem(text = { Text(dash.label) }, onClick = { style { copy(dash = dash) }; close() }, modifier = Modifier.testTag("drawing-dash-${dash.name}"))
                }
            }) { Text(d.style.dash.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
            if (d.tool.hasText) ActionIcon(AppIcons.Edit, "编辑文字", "drawing-bar-edit", button, iconSize = 18.dp) { drawings.openText(d.id) }
            ActionIcon(AppIcons.Settings, "设置", "drawing-bar-settings", button, iconSize = 18.dp) { drawings.openSettings(d.id) }
            ActionIcon(if (d.locked) DrawingIcons.Locked else DrawingIcons.Unlocked, if (d.locked) "解锁" else "锁定", "drawing-bar-lock", button, iconSize = 18.dp, enabled = !drawings.lockDrawings) { drawings.toggleLock(d.id) }
            ActionIcon(DrawingIcons.Clone, "克隆", "drawing-bar-clone", button, iconSize = 18.dp) { drawings.clone(d.id) }
            ActionIcon(AppIcons.Delete, "移除", "drawing-bar-remove", button, iconSize = 18.dp, enabled = !locked || drawings.removeLockedToo) { drawings.remove(d.id) }
        }
    }
}

@Composable
internal fun DrawingContextMenu(drawings: ChartDrawings) {
    val at = drawings.menuAt ?: return
    val d = drawings.selected ?: return
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(true, { drawings.closeMenu() }, Modifier.widthIn(min = 180.dp)) {
            @Composable fun item(label: String, tag: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.testTag("drawing-menu-$tag"))
            item("设置…", "settings") { drawings.openSettings(d.id) }
            if (d.tool.hasText) item("编辑文字…", "text") { drawings.openText(d.id) }
            item("克隆", "clone") { drawings.clone(d.id) }
            item("复制", "copy") { drawings.copySelected(); drawings.closeMenu() }
            DividerLine(Modifier.padding(vertical = 4.dp))
            item("置于顶层", "front") { drawings.moveOrder(d.id, drawings.drawings.size) }
            item("上移一层", "forward") { drawings.moveOrder(d.id, 1) }
            item("下移一层", "backward") { drawings.moveOrder(d.id, -1) }
            item("置于底层", "back") { drawings.moveOrder(d.id, -drawings.drawings.size) }
            DividerLine(Modifier.padding(vertical = 4.dp))
            item(if (d.locked) "解锁" else "锁定", "lock") { drawings.toggleLock(d.id) }
            item("隐藏", "hide") { drawings.hide(d.id) }
            DropdownMenuItem(text = { Text("移除", color = MaterialTheme.colorScheme.error) }, onClick = { drawings.remove(d.id) }, modifier = Modifier.testTag("drawing-menu-remove"))
        }
    }
}

/** Asks for a drawing's text, a post's link, or a table's cells. */
@Composable
internal fun DrawingTextDialog(drawings: ChartDrawings) {
    val id = drawings.textEditId ?: return
    val source = remember(id) { drawings.drawings.firstOrNull { it.id == id } } ?: return
    if (source.tool == DrawingTool.TABLE) { TableDialog(drawings, source); return }
    var text by remember(id) { mutableStateOf(source.text) }
    var link by remember(id) { mutableStateOf(source.link) }
    val needsLink = source.tool == DrawingTool.POST || source.tool == DrawingTool.IDEA
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = { drawings.closeText(true) }, title = { Text(source.tool.label) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsLink) OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().testTag("drawing-link"), label = { Text("链接") }, singleLine = true)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("drawing-text"), label = { Text(if (needsLink) "内容" else "文字") }, minLines = 3)
            if (needsLink && link.isNotBlank()) TextButton({ runCatching { uriHandler.openUri(link.trim()) } }, Modifier.testTag("drawing-open-link")) { Text("打开链接") }
        }
    }, confirmButton = {
        TextButton({ drawings.update(source.copy(text = text, link = link.trim())); drawings.closeText(false) }, Modifier.testTag("drawing-text-save"), enabled = text.isNotBlank() || link.isNotBlank()) { Text("确定") }
    }, dismissButton = { TextButton({ drawings.closeText(true) }, Modifier.testTag("drawing-text-cancel")) { Text("取消") } })
}

@Composable
private fun TableDialog(drawings: ChartDrawings, source: Drawing) {
    var rows by remember(source.id) { mutableStateOf(source.table) }
    FormDialog("表格", "确定", rows.isNotEmpty(), { drawings.update(source.copy(table = rows)); drawings.closeText(false) }, { drawings.closeText(false) }, "drawing-table-dialog", "drawing-table-cancel", "drawing-table-save") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, cell ->
                        OutlinedTextField(cell, { value -> rows = rows.mapIndexed { i, old -> if (i == r) old.mapIndexed { j, v -> if (j == c) value else v } else old } }, Modifier.weight(1f).testTag("table-cell-$r-$c"), singleLine = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val columns = rows.firstOrNull()?.size ?: 0
                TextButton({ rows = rows + listOf(List(columns) { "" }) }, Modifier.testTag("table-add-row"), enabled = rows.size < 20) { Text("添加行") }
                TextButton({ rows = rows.map { it + "" } }, Modifier.testTag("table-add-column"), enabled = columns < 10) { Text("添加列") }
                TextButton({ rows = rows.dropLast(1) }, Modifier.testTag("table-remove-row"), enabled = rows.size > 1) { Text("删除行") }
                TextButton({ rows = rows.map { it.dropLast(1) } }, Modifier.testTag("table-remove-column"), enabled = columns > 1) { Text("删除列") }
            }
        }
    }
}

/** Numeric or on/off settings of a tool beyond its style and levels. */
private class ParamSpec(val key: String, val label: String, val fallback: Double, val range: ClosedFloatingPointRange<Double>? = null, val toggle: Boolean = false)

private fun paramSpecs(tool: DrawingTool): List<ParamSpec> = when (tool) {
    DrawingTool.REGRESSION_TREND -> listOf(ParamSpec("upper", "上偏差", 2.0, .0..10.0), ParamSpec("lower", "下偏差", 2.0, .0..10.0))
    DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION -> listOf(ParamSpec("accountSize", "账户规模", 1000.0, 1.0..1e9), ParamSpec("riskPercent", "风险 %", 25.0, .01..100.0))
    DrawingTool.GHOST_FEED -> listOf(ParamSpec("averageHl", "平均振幅（跳）", 20.0, 1.0..10000.0), ParamSpec("variance", "波动", 50.0, .0..100.0))
    DrawingTool.FIXED_RANGE_VOLUME_PROFILE, DrawingTool.ANCHORED_VOLUME_PROFILE -> listOf(ParamSpec("rows", "行数", 24.0, 4.0..200.0), ParamSpec("valueArea", "价值区 %", 70.0, 10.0..100.0), ParamSpec("widthPercent", "宽度 %", 30.0, 5.0..100.0))
    DrawingTool.ANCHORED_VWAP -> listOf(ParamSpec("band1", "波段倍数 1", 1.0, .0..10.0), ParamSpec("band2", "波段倍数 2", .0, .0..10.0), ParamSpec("band3", "波段倍数 3", .0, .0..10.0))
    DrawingTool.BARS_PATTERN -> listOf(ParamSpec("mirrored", "水平镜像", .0, toggle = true), ParamSpec("flipped", "上下翻转", .0, toggle = true))
    DrawingTool.FIB_SPEED_ARCS -> listOf(ParamSpec("fullCircles", "完整圆", .0, toggle = true))
    DrawingTool.FIB_SPIRAL -> listOf(ParamSpec("counterclockwise", "逆时针", .0, toggle = true))
    DrawingTool.GANN_BOX -> listOf(ParamSpec("showFans", "显示角度线", .0, toggle = true))
    DrawingTool.POLYLINE -> listOf(ParamSpec("closed", "闭合", .0, toggle = true))
    else -> emptyList()
}

private val BARS_MODES = listOf("K线", "开盘价", "最高价", "最低价", "收盘价", "中间价")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun LevelsEditor(title: String, levels: List<DrawingLevel>, tag: String, onChange: (List<DrawingLevel>) -> Unit) {
    if (levels.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    levels.forEachIndexed { index, level ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(level.visible, { value -> onChange(levels.mapIndexed { i, old -> if (i == index) old.copy(visible = value) else old }) }, Modifier.testTag("$tag-visible-$index"))
            var value by remember(tag, index) { mutableStateOf(formatRatio(level.value).replace('−', '-')) }
            OutlinedTextField(value, { text ->
                value = text
                text.toDoubleOrNull()?.let { parsed -> onChange(levels.mapIndexed { i, old -> if (i == index) old.copy(value = parsed) else old }) }
            }, Modifier.width(112.dp).testTag("$tag-value-$index"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = value.toDoubleOrNull() == null)
            level.value2?.let { Text("/ ${formatRatio(it)}", style = MaterialTheme.typography.bodySmall) }
            ColorButton("层级颜色", level.color, "$tag-color-$index") { hex -> onChange(levels.mapIndexed { i, old -> if (i == index) old.copy(color = hex) else old }) }
            ActionIcon(AppIcons.Delete, "移除层级", "$tag-remove-$index", Modifier.size(32.dp), iconSize = 18.dp) { onChange(levels.filterIndexed { i, _ -> i != index }) }
        }
    }
    TextButton({ onChange(levels + DrawingLevel(levels.maxOf { it.value } + .5, "#2962FF")) }, Modifier.testTag("$tag-add")) { Text("添加层级") }
}

private fun parseTime(text: String): Long? = runCatching { Instant.parse(text.trim().replace(' ', 'T') + ":00Z").toEpochMilliseconds() }.getOrNull()

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DrawingSettingsDialog(layer: DrawingLayer) {
    val drawings = layer.controller
    val id = drawings.settingsId ?: return
    val original = remember(id) { drawings.drawings.firstOrNull { it.id == id } } ?: return
    val instrument = layer.ctx?.instrument ?: return
    var d by remember(id) { mutableStateOf(original) }
    var tab by remember(id) { mutableStateOf(0) }
    var invalidCoordinates by remember(id) { mutableStateOf(false) }
    fun change(value: Drawing) { d = value; drawings.preview(value) }
    fun style(transform: DrawingStyle.() -> DrawingStyle) = change(d.copy(style = d.style.transform()))
    fun close() = drawings.closeSettings()
    val tabs = buildList { add("样式"); if (d.tool.hasText) add("文字"); if (d.tool.anchors != FREEHAND && !d.tool.screenAnchored) add("坐标"); add("可见性") }
    FormDialog("${d.tool.label} 设置", "确定", !invalidCoordinates, {
        drawings.preview(original)
        drawings.update(d)
        close()
    }, { drawings.preview(original); close() }, "drawing-settings", "drawing-settings-cancel", "drawing-settings-save") {
        PrimaryTabRow(tab) { tabs.forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }, modifier = Modifier.testTag("drawing-settings-tab-$index")) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tabs[tab]) {
                "样式" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("线条", Modifier.weight(1f))
                        ColorButton("线条颜色", d.style.color, "settings-color") { hex -> style { copy(color = hex) } }
                    }
                    SegmentedChoice(listOf(1f, 2f, 3f, 4f), d.style.width, { "${it.roundToInt()}px" }, { "settings-width-${it.roundToInt()}" }, { w -> style { copy(width = w) } }, Modifier.fillMaxWidth())
                    SegmentedChoice(DrawingDash.entries, d.style.dash, { it.label }, { "settings-dash-${it.name}" }, { v -> style { copy(dash = v) } }, Modifier.fillMaxWidth())
                    if (d.tool.hasFill) {
                        SwitchRow("填充", d.style.fill != null, "settings-fill-on") { on -> style { copy(fill = if (on) (fill ?: color) else null) } }
                        if (d.style.fill != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorButton("填充颜色", d.style.fill, "settings-fill") { hex -> style { copy(fill = hex) } }
                            Text("不透明度", style = MaterialTheme.typography.labelMedium)
                            Slider(d.style.fillAlpha, { v -> style { copy(fillAlpha = v) } }, Modifier.weight(1f).testTag("settings-fill-alpha"), valueRange = 0f..1f)
                        }
                    }
                    if (d.tool.hasExtend) {
                        SwitchRow("向左延伸", d.style.extendLeft, "settings-extend-left") { v -> style { copy(extendLeft = v) } }
                        SwitchRow("向右延伸", d.style.extendRight, "settings-extend-right") { v -> style { copy(extendRight = v) } }
                    }
                    if (d.tool in setOf(DrawingTool.TREND_LINE, DrawingTool.RAY, DrawingTool.EXTENDED_LINE, DrawingTool.INFO_LINE, DrawingTool.ARROW)) {
                        SwitchRow("显示统计信息", d.style.showStats, "settings-stats") { v -> style { copy(showStats = v) } }
                        SwitchRow("显示中点", d.style.showMiddle, "settings-middle") { v -> style { copy(showMiddle = v) } }
                        SwitchRow("显示价格标签", d.style.showPrices, "settings-prices") { v -> style { copy(showPrices = v) } }
                    }
                    if (d.tool in setOf(DrawingTool.TREND_LINE, DrawingTool.ARROW, DrawingTool.PATH, DrawingTool.RAY, DrawingTool.BRUSH)) {
                        SwitchRow("起点箭头", d.style.leftArrow, "settings-arrow-left") { v -> style { copy(leftArrow = v) } }
                        SwitchRow("终点箭头", d.style.rightArrow, "settings-arrow-right") { v -> style { copy(rightArrow = v) } }
                    }
                    if (d.tool == DrawingTool.RECTANGLE) SwitchRow("中线", d.style.showMiddle, "settings-middle") { v -> style { copy(showMiddle = v) } }
                    if (d.levels.isNotEmpty()) {
                        SwitchRow("显示层级比例", d.style.showLabels, "settings-labels") { v -> style { copy(showLabels = v) } }
                        SwitchRow("显示价格", d.style.showPrices, "settings-prices") { v -> style { copy(showPrices = v) } }
                        if (d.tool == DrawingTool.FIB_RETRACEMENT) SwitchRow("反转", d.style.reverse, "settings-reverse") { v -> style { copy(reverse = v) } }
                    }
                    LevelsEditor(if (d.tool == DrawingTool.GANN_SQUARE || d.tool == DrawingTool.GANN_SQUARE_FIXED) "网格" else "层级", d.levels, "settings-levels") { change(d.copy(levels = it)) }
                    LevelsEditor(if (d.tool == DrawingTool.GANN_SQUARE || d.tool == DrawingTool.GANN_SQUARE_FIXED) "角度线" else "时间层级", d.levels2, "settings-levels2") { change(d.copy(levels2 = it)) }
                    LevelsEditor("弧线", d.levels3, "settings-levels3") { change(d.copy(levels3 = it)) }
                    if (d.tool == DrawingTool.BARS_PATTERN) SegmentedChoice(BARS_MODES.indices.toList(), d.param("mode").toInt(), { BARS_MODES[it] }, { "settings-bars-mode-$it" }, { mode -> change(d.copy(params = d.params + ("mode" to mode.toDouble()))) }, Modifier.fillMaxWidth())
                    if (d.tool in setOf(DrawingTool.ELLIOTT_IMPULSE, DrawingTool.ELLIOTT_CORRECTION, DrawingTool.ELLIOTT_TRIANGLE, DrawingTool.ELLIOTT_DOUBLE, DrawingTool.ELLIOTT_TRIPLE)) {
                        Text("浪级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ElliottDegree.entries.forEachIndexed { index, degree ->
                                FilterChip(d.param("degree", 4.0).toInt() == index, { change(d.copy(params = d.params + ("degree" to index.toDouble()))) }, label = { Text(degree.label) }, modifier = Modifier.testTag("settings-degree-$index"))
                            }
                        }
                    }
                    paramSpecs(d.tool).forEach { spec ->
                        if (spec.toggle) SwitchRow(spec.label, d.param(spec.key, spec.fallback) != 0.0, "settings-param-${spec.key}") { on -> change(d.copy(params = d.params + (spec.key to if (on) 1.0 else 0.0))) }
                        else {
                            var text by remember(id, spec.key) { mutableStateOf(formatRatio(d.param(spec.key, spec.fallback), 4).replace('−', '-')) }
                            val parsed = text.toDoubleOrNull()?.takeIf { value -> spec.range == null || value in spec.range }
                            OutlinedTextField(text, { value ->
                                text = value
                                value.toDoubleOrNull()?.takeIf { v -> spec.range == null || v in spec.range }?.let { change(d.copy(params = d.params + (spec.key to it))) }
                            }, Modifier.fillMaxWidth().testTag("settings-param-${spec.key}"), label = { Text(spec.label) }, singleLine = true, isError = parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                    TextButton({ drawings.setAsDefault(d) }, Modifier.testTag("settings-make-default")) { Text("设为此工具的默认样式") }
                }
                "文字" -> {
                    OutlinedTextField(d.text, { change(d.copy(text = it)) }, Modifier.fillMaxWidth().testTag("settings-text"), label = { Text("文字") }, minLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("文字颜色", Modifier.weight(1f))
                        ColorButton("文字颜色", d.style.textColor ?: d.style.color, "settings-text-color") { hex -> style { copy(textColor = hex) } }
                    }
                    SegmentedChoice(listOf(10, 12, 14, 16, 20, 24, 28), d.style.fontSize, { it.toString() }, { "settings-font-$it" }, { size -> style { copy(fontSize = size) } }, Modifier.fillMaxWidth())
                    SwitchRow("粗体", d.style.bold, "settings-bold") { v -> style { copy(bold = v) } }
                    SwitchRow("斜体", d.style.italic, "settings-italic") { v -> style { copy(italic = v) } }
                    if (d.tool == DrawingTool.SIGNPOST) OutlinedTextField(d.glyph.orEmpty(), { change(d.copy(glyph = it.ifBlank { null })) }, Modifier.fillMaxWidth().testTag("settings-glyph"), label = { Text("表情") }, singleLine = true)
                    if (d.tool == DrawingTool.POST || d.tool == DrawingTool.IDEA) OutlinedTextField(d.link, { change(d.copy(link = it)) }, Modifier.fillMaxWidth().testTag("settings-link"), label = { Text("链接") }, singleLine = true)
                }
                "坐标" -> {
                    var invalid = false
                    d.points.take(24).forEachIndexed { index, point ->
                        var timeText by remember(id, index) { mutableStateOf(dateTime(point.timeMs)) }
                        var priceText by remember(id, index) { mutableStateOf(instrument.formatPrice(point.price.roundToInt().toLong().coerceAtLeast(0))) }
                        val time = parseTime(timeText)
                        val price = parseFixed(priceText.trim(), instrument.priceDecimals)
                        if (time == null || price == null) invalid = true
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(timeText, { text ->
                                timeText = text
                                parseTime(text)?.let { value -> change(d.copy(points = d.points.mapIndexed { i, old -> if (i == index) old.copy(timeMs = value) else old })) }
                            }, Modifier.weight(1.6f).testTag("settings-point-time-$index"), label = { Text("点 ${index + 1} 时间 UTC") }, singleLine = true, isError = time == null)
                            OutlinedTextField(priceText, { text ->
                                priceText = text
                                parseFixed(text.trim(), instrument.priceDecimals)?.let { value -> change(d.copy(points = d.points.mapIndexed { i, old -> if (i == index) old.copy(price = value.toDouble()) else old })) }
                            }, Modifier.weight(1f).testTag("settings-point-price-$index"), label = { Text("价格") }, singleLine = true, isError = price == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                    SideEffect { invalidCoordinates = invalid }
                    if (d.points.size > 24) Text("共 ${d.points.size} 个点，这里显示前 24 个。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                }
                "可见性" -> {
                    Text("在这些周期上显示", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    IntervalBucket.entries.forEach { bucket ->
                        SwitchRow(bucket.label, bucket !in d.hiddenIntervals, "settings-visible-${bucket.name}") { on -> change(d.copy(hiddenIntervals = if (on) d.hiddenIntervals - bucket else d.hiddenIntervals + bucket)) }
                    }
                }
            }
        }
    }
}

/** Everything of the drawing layer that sits over the plot: quick bar, menus, dialogs and the value tooltip. */
@Composable
internal fun BoxScope.DrawingChartOverlays(layer: DrawingLayer, compact: Boolean) {
    val drawings = layer.controller
    DrawingContextMenu(drawings)
    DrawingValuesTooltip(layer)
    // A phone's legend and toolbar leave no room at the top, so the bar sits above the navigation there.
    DrawingFloatingBar(drawings, if (compact) Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp) else Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 92.dp))
    FavoriteDrawingsBar(drawings, Modifier.align(Alignment.BottomEnd).padding(bottom = 38.dp, end = 92.dp))
    DrawingTextDialog(drawings)
    DrawingSettingsDialog(layer)
    // Phones have no room for the toolbar by default; this tab on the plot's edge brings it in and out.
    if (compact) Surface(Modifier.align(Alignment.CenterStart), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shadowElevation = 2.dp) {
        ActionIcon(DrawingIcons.of(DrawingTool.TREND_LINE), "画图工具", "chart-drawing-tools", Modifier.size(width = 32.dp, height = 44.dp), iconSize = 22.dp,
            tint = if (drawings.toolbarOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { drawings.toolbarOpen = !drawings.toolbarOpen }
    }
    drawings.notice?.let { notice ->
        Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp).clickable(role = Role.Button) { drawings.clearNotice() }.testTag("drawing-notice"),
            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Text(notice, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    val pending = drawings.pendingImageId
    val currentPending by rememberUpdatedState(pending)
    val picker = rememberDrawingImagePicker { bytes -> currentPending?.let { drawings.finishImage(it, bytes) } }
    LaunchedEffect(pending) { if (pending != null) picker() }
}
