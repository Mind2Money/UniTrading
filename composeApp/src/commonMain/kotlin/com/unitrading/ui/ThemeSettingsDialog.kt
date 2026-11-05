package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import kotlinx.coroutines.launch

fun TradingPalette.color(role: ThemeColor): Color = when (role) {
    ThemeColor.PAGE -> Shell; ThemeColor.SURFACE -> Surface; ThemeColor.CHART -> Chart; ThemeColor.GRID -> Grid
    ThemeColor.UP -> Up; ThemeColor.DOWN -> Down; ThemeColor.PROFIT -> Profit; ThemeColor.LOSS -> Loss
    ThemeColor.ACCENT -> Accent; ThemeColor.TEXT -> Ink; ThemeColor.MUTED -> Muted; ThemeColor.STOP -> Stop; ThemeColor.TARGET -> Target
}
fun colorHex(color: Color) = "#" + (color.toArgb().toLong() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** Colours by what they paint, so a role is found by where it shows up. */
private val themeColorGroups = listOf(
    "界面" to listOf(ThemeColor.PAGE, ThemeColor.SURFACE, ThemeColor.TEXT, ThemeColor.MUTED, ThemeColor.ACCENT),
    "图表" to listOf(ThemeColor.CHART, ThemeColor.GRID, ThemeColor.UP, ThemeColor.DOWN, ThemeColor.STOP, ThemeColor.TARGET),
    "盈亏数字" to listOf(ThemeColor.PROFIT, ThemeColor.LOSS),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSettingsDialog(controller: ThemeController, onDismiss: () -> Unit) {
    val saved by controller.settings.collectAsState()
    var draft by remember { mutableStateOf(saved) }
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf<ThemeColor?>(null) }
    val valid = runCatching { draft.validate() }.isSuccess
    TradingTheme(draft.copy(colors = draft.colors.filterValues { it.matches(Regex("#[0-9a-fA-F]{6}")) })) {
        val colors = TradingColors
        val type = MaterialTheme.typography
        FormDialog("外观与主题", "保存", valid && !busy, {
            scope.launch { busy = true; try { if (controller.apply(draft)) onDismiss() } finally { busy = false } }
        }, onDismiss, "theme-settings", "theme-cancel", "theme-save", dismissEnabled = !busy) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("预览配色，保存后应用到所有页面。", style = type.bodyMedium, color = colors.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreset.entries.forEach { preset ->
                        FilterChip(draft.preset == preset, { draft = ThemeSettings(preset) }, label = { Text(preset.label) },
                            modifier = Modifier.testTag("theme-${preset.name}"))
                    }
                }
                Column(Modifier.fillMaxWidth().background(colors.Shell, MaterialTheme.shapes.medium).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(88.dp).background(colors.Chart, MaterialTheme.shapes.small)) {
                        for (i in 1..3) drawLine(colors.Grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
                        val body = 8.dp.toPx()
                        for (i in 1..7) {
                            val x = size.width * i / 8
                            val y = size.height * (if (i % 2 == 0) .45f else .3f)
                            val ink = if (i % 2 == 0) colors.Down else colors.Up
                            drawLine(ink, Offset(x, y - 8.dp.toPx()), Offset(x, y + 28.dp.toPx()), 1.5.dp.toPx())
                            drawRect(ink, Offset(x - body / 2, y), Size(body, 18.dp.toPx()))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("+128.50", color = colors.Profit, style = type.titleSmall); Text("−64.25", color = colors.Loss, style = type.titleSmall)
                    }
                }
                themeColorGroups.forEach { (title, roles) ->
                    Text(title, color = MaterialTheme.colorScheme.primary, style = type.labelLarge)
                    roles.forEach { role ->
                        ColorRow(role.label, draft.colors[role.name] ?: colorHex(colors.color(role)), colors.color(role), "theme-color-${role.name}", { editingColor = role },
                            { draft = draft.copy(colors = draft.colors + (role.name to it.uppercase())) })
                    }
                }
                TextButton({ draft = ThemeSettings(draft.preset) }, Modifier.testTag("theme-reset")) { Text("恢复此主题默认颜色") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = type.bodySmall) }
            }
        }
        editingColor?.let { role ->
            ColorPicker(role.label, colors.color(role), { editingColor = null }) { value ->
                draft = draft.copy(colors = draft.colors + (role.name to colorHex(value)))
                editingColor = null
            }
        }
    }
}

/** One colour role: swatch (opens the picker), name, and the hex value inline; an invalid value is flagged on its own row. */
@Composable
private fun ColorRow(label: String, value: String, swatch: Color, tag: String, onPick: () -> Unit, onValue: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val valid = value.matches(Regex("#[0-9A-Fa-f]{6}"))
    val style = MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onPick, Modifier.semantics { contentDescription = "选择${label}颜色" }) {
            Box(Modifier.size(28.dp).background(swatch, CircleShape).border(1.dp, scheme.outline, CircleShape))
        }
        Text(label, Modifier.weight(1f), style = style, maxLines = 1)
        BasicTextField(value, onValue, Modifier.width(96.dp).testTag(tag), singleLine = true, cursorBrush = SolidColor(scheme.primary),
            textStyle = TextStyle(color = if (valid) scheme.onSurface else scheme.error, textAlign = TextAlign.End).merge(MaterialTheme.typography.labelLarge),
            decorationBox = { inner ->
                Box(Modifier.background(scheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .border(1.dp, if (valid) scheme.outlineVariant else scheme.error, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp)) { inner() }
            })
    }
}

@Composable
internal fun ColorPicker(title: String, initial: Color, onDismiss: () -> Unit, onColor: (Color) -> Unit) {
    var red by remember { mutableStateOf(initial.red * 255) }
    var green by remember { mutableStateOf(initial.green * 255) }
    var blue by remember { mutableStateOf(initial.blue * 255) }
    val selected = Color(red.toInt(), green.toInt(), blue.toInt())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(selected, MaterialTheme.shapes.medium))
            Text(colorHex(selected), style = MaterialTheme.typography.labelLarge)
            listOf("红" to red, "绿" to green, "蓝" to blue).forEachIndexed { index, (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$label ${value.toInt()}", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                    Slider(value, { when (index) { 0 -> red = it; 1 -> green = it; else -> blue = it } }, Modifier.weight(1f), valueRange = 0f..255f)
                }
            }
        }
    }, confirmButton = { TextButton({ onColor(selected) }) { Text("使用颜色") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
