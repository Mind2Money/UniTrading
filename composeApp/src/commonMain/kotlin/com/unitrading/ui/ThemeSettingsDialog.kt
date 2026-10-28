package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.unitrading.app.*
import kotlinx.coroutines.launch

fun TradingPalette.color(role: ThemeColor): Color = when (role) {
    ThemeColor.PAGE -> Shell; ThemeColor.SURFACE -> Surface; ThemeColor.CHART -> Chart; ThemeColor.GRID -> Grid
    ThemeColor.UP -> Up; ThemeColor.DOWN -> Down; ThemeColor.PROFIT -> Profit; ThemeColor.LOSS -> Loss
    ThemeColor.ACCENT -> Accent; ThemeColor.TEXT -> Ink; ThemeColor.MUTED -> Muted; ThemeColor.STOP -> Stop; ThemeColor.TARGET -> Target
}
fun colorHex(color: Color) = "#" + (color.toArgb().toLong() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

@Composable
internal fun ThemeSettingsDialog(controller: ThemeController, onDismiss: () -> Unit) {
    val saved by controller.settings.collectAsState()
    var draft by remember { mutableStateOf(saved) }
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf<ThemeColor?>(null) }
    val valid = runCatching { draft.validate() }.isSuccess
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        TradingTheme(draft.copy(colors = draft.colors.filterValues { it.matches(Regex("#[0-9a-fA-F]{6}")) })) {
            val colors = TradingColors
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 560.dp).fillMaxHeight(.92f).imePadding().testTag("theme-settings")) {
                Column(Modifier.padding(20.dp)) {
                    Text("外观与主题", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("预览配色，保存后应用到所有页面。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemePreset.entries.forEach { preset ->
                                FilterChip(draft.preset == preset, { draft = ThemeSettings(preset) },
                                    label = { Text(when (preset) { ThemePreset.DARK -> "深色"; ThemePreset.LIGHT -> "浅色"; ThemePreset.SYSTEM -> "跟随系统" }) },
                                    modifier = Modifier.testTag("theme-${preset.name}"))
                            }
                        }
                        Column(Modifier.fillMaxWidth().background(colors.Shell).padding(12.dp)) {
                            Canvas(Modifier.fillMaxWidth().height(80.dp).background(colors.Chart)) {
                                for (i in 1..3) drawLine(colors.Grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
                                for (i in 1..7) {
                                    val x = size.width * i / 8
                                    val y = size.height * (if (i % 2 == 0) .45f else .3f)
                                    val ink = if (i % 2 == 0) colors.Down else colors.Up
                                    drawLine(ink, Offset(x, y - 12), Offset(x, y + 34), 2f)
                                    drawRect(ink, Offset(x - 5, y), Size(10f, 22f))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("+128.50", color = colors.Profit); Text("−64.25", color = colors.Loss)
                            }
                        }
                        ThemeColor.entries.forEach { role ->
                            val value = draft.colors[role.name] ?: colorHex(colors.color(role))
                            OutlinedTextField(value, { draft = draft.copy(colors = draft.colors + (role.name to it.uppercase())) },
                                label = { Text(role.label) }, singleLine = true, isError = !value.matches(Regex("#[0-9A-Fa-f]{6}")),
                                supportingText = { Text("#RRGGBB") },
                                leadingIcon = { IconButton({ editingColor = role }, Modifier.semantics { contentDescription = "选择${role.label}颜色" }) { Box(Modifier.size(24.dp).background(colors.color(role), CircleShape)) } },
                                modifier = Modifier.fillMaxWidth().testTag("theme-color-${role.name}"))
                        }
                        TextButton({ draft = ThemeSettings(draft.preset) }, Modifier.testTag("theme-reset")) { Text("恢复此主题默认颜色") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onDismiss, enabled = !busy) { Text("取消") }
                        Button({ scope.launch { busy = true; try { if (controller.apply(draft)) onDismiss() } finally { busy = false } } },
                            enabled = valid && !busy, modifier = Modifier.testTag("theme-save")) { Text("保存主题") }
                    }
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
}

@Composable
private fun ColorPicker(title: String, initial: Color, onDismiss: () -> Unit, onColor: (Color) -> Unit) {
    var red by remember { mutableStateOf(initial.red * 255) }
    var green by remember { mutableStateOf(initial.green * 255) }
    var blue by remember { mutableStateOf(initial.blue * 255) }
    val selected = Color(red.toInt(), green.toInt(), blue.toInt())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(selected, MaterialTheme.shapes.medium))
            Text(colorHex(selected))
            listOf("红" to red, "绿" to green, "蓝" to blue).forEachIndexed { index, (label, value) ->
                Text("$label  ${value.toInt()}")
                Slider(value, { when (index) { 0 -> red = it; 1 -> green = it; else -> blue = it } }, valueRange = 0f..255f, steps = 254)
            }
        }
    }, confirmButton = { TextButton({ onColor(selected) }) { Text("使用颜色") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
