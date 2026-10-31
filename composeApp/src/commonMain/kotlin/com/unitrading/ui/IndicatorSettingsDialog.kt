package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.unitrading.app.IndicatorController
import com.unitrading.app.IndicatorSettings
import com.unitrading.broker.parseFixed
import com.unitrading.chart.*
import kotlinx.coroutines.launch
import kotlin.random.Random

internal data class ReferenceLevelDraft(val value: String = "", val color: String = DEFAULT_INDICATOR_GUIDE_COLOR) {
    fun parse(): Double? = parseFixed(value.trim(), 2)?.let { it / 100.0 }?.takeIf { it in 0.0..100.0 }
    fun colorValid(): Boolean = color.matches(Regex("#[0-9a-fA-F]{6}"))
}

/** Editable text form of one [IndicatorConfig]; it only becomes a config once every field parses and validates. */
internal data class IndicatorDraft(
    val id: String,
    val kind: IndicatorKind,
    val params: Map<String, String>,
    val colors: Map<String, String>,
    val visible: Boolean = true,
    val referenceLevels: List<ReferenceLevelDraft> = emptyList(),
) {
    fun parse(spec: IndicatorParam): Double? {
        val text = params[spec.key]?.trim() ?: return null
        val value = if (spec.integer) text.toIntOrNull()?.toDouble() else parseFixed(text, 2)?.let { it / 100.0 }
        return value?.takeIf { it >= spec.min && it <= spec.max }
    }
    fun colorValid(line: String): Boolean = colors[line]?.matches(Regex("#[0-9a-fA-F]{6}")) == true
    fun parseReferenceLevels(): List<Double>? {
        val values = referenceLevels.map { it.parse() ?: return null }
        return values.takeIf { it.distinct().size == it.size }?.sorted()
    }
    fun toConfig(): IndicatorConfig? {
        val values = kind.params.associate { it.key to (parse(it) ?: return null) }
        val levels = if (kind == IndicatorKind.RSI) parseReferenceLevels() ?: return null else null
        // Build keys from each row before sorting levels so colours stay with their values.
        val guideColors = if (kind == IndicatorKind.RSI) referenceLevels.associate {
            formatParam(it.parse() ?: return null) to it.color.uppercase()
        } else emptyMap()
        val config = IndicatorConfig(id, kind, values, colors.mapValues { it.value.uppercase() }, visible, levels, guideColors)
        return config.takeIf { runCatching { it.validate() }.isSuccess }
    }
    companion object {
        fun from(config: IndicatorConfig) = IndicatorDraft(
            config.id, config.kind,
            config.kind.params.associate { it.key to formatParam(config.param(it.key)) },
            config.kind.lines.withIndex().filterNot { it.value.histogram }.associate { (index, line) -> line.name to config.color(index) },
            config.visible,
            if (config.kind == IndicatorKind.RSI) config.guides.map { ReferenceLevelDraft(formatParam(it), config.guideColor(it)) } else emptyList(),
        )
        fun new(kind: IndicatorKind) = from(IndicatorConfig(Random.nextLong().toULong().toString(16), kind))
    }
}

internal fun List<IndicatorDraft>.toSettings(): IndicatorSettings? {
    val configs = map { it.toConfig() ?: return null }
    return IndicatorSettings(configs).takeIf { runCatching { it.validate() }.isSuccess }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IndicatorSettingsDialog(controller: IndicatorController, onDismiss: () -> Unit) {
    val saved by controller.settings.collectAsState()
    var drafts by remember { mutableStateOf(saved.indicators.map(IndicatorDraft::from)) }
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var editingReferenceColor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val settings = drafts.toSettings()
    val colors = TradingColors
    fun update(index: Int, value: IndicatorDraft) { drafts = drafts.mapIndexed { i, d -> if (i == index) value else d } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight(.92f).imePadding().testTag("indicator-settings")) {
            Column(Modifier.padding(20.dp)) {
                Text("图表指标", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("可添加多个指标，分别设置参数和线条颜色。保存后对所有品种和周期生效。", style = MaterialTheme.typography.bodySmall)
                    Text("添加指标", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IndicatorKind.entries.forEach { kind ->
                            OutlinedButton({ drafts = drafts + IndicatorDraft.new(kind) }, Modifier.testTag("add-indicator-${kind.name}"),
                                enabled = drafts.size < IndicatorSettings.MAX_INDICATORS, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                Text("${kind.label} · ${kind.title}", fontSize = 11.sp)
                            }
                        }
                    }
                    if (drafts.isEmpty()) Text("尚未添加指标。", fontSize = 12.sp, color = colors.Muted)
                    drafts.forEachIndexed { index, draft ->
                        IndicatorEditor(index, draft, drafts.size,
                            onChange = { update(index, it) },
                            onMove = { delta -> drafts = drafts.toMutableList().also { list -> list.add(index + delta, list.removeAt(index)) } },
                            onRemove = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                            onPickColor = { line -> editingColor = index to line },
                            onPickReferenceColor = { row -> editingReferenceColor = index to row })
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消") }
                    Button({ scope.launch { busy = true; try { if (settings != null && controller.apply(settings)) onDismiss() } finally { busy = false } } },
                        enabled = settings != null && !busy, modifier = Modifier.testTag("indicators-save")) { Text("保存指标") }
                }
            }
        }
        editingColor?.let { (index, line) ->
            val draft = drafts.getOrNull(index) ?: return@let
            ColorPicker("${draft.kind.label} ${line}颜色", parseHexColor(draft.colors[line]) ?: colors.Accent, { editingColor = null }) { value ->
                update(index, draft.copy(colors = draft.colors + (line to colorHex(value))))
                editingColor = null
            }
        }
        editingReferenceColor?.let { (index, rowIndex) ->
            val draft = drafts.getOrNull(index) ?: return@let
            val row = draft.referenceLevels.getOrNull(rowIndex) ?: return@let
            ColorPicker("RSI 参考线 ${rowIndex + 1} 颜色", parseHexColor(row.color) ?: parseHexColor(DEFAULT_INDICATOR_GUIDE_COLOR)!!,
                { editingReferenceColor = null }) { value ->
                update(index, draft.copy(referenceLevels = draft.referenceLevels.mapIndexed { i, level ->
                    if (i == rowIndex) level.copy(color = colorHex(value)) else level
                }))
                editingReferenceColor = null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorEditor(
    index: Int,
    draft: IndicatorDraft,
    total: Int,
    onChange: (IndicatorDraft) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onPickColor: (String) -> Unit,
    onPickReferenceColor: (Int) -> Unit,
) {
    val colors = TradingColors
    val config = draft.toConfig()
    Column(Modifier.fillMaxWidth().background(colors.Shell, MaterialTheme.shapes.medium).padding(12.dp).testTag("indicator-$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(config?.label ?: draft.kind.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(draft.kind.title + if (draft.kind.pane == IndicatorPane.OVERLAY) " · 叠加在 K 线上" else " · 独立副图", fontSize = 10.sp, color = colors.Muted)
            }
            Text("显示", fontSize = 11.sp, color = colors.Muted)
            Switch(draft.visible, { onChange(draft.copy(visible = it)) }, Modifier.testTag("indicator-visible-$index").semantics { contentDescription = "显示${draft.kind.label}" })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            draft.kind.params.forEach { spec ->
                OutlinedTextField(draft.params[spec.key] ?: "", { onChange(draft.copy(params = draft.params + (spec.key to it))) },
                    Modifier.width(150.dp).testTag("indicator-param-$index-${spec.key}"), label = { Text(spec.label, fontSize = 11.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (spec.integer) KeyboardType.Number else KeyboardType.Decimal),
                    isError = draft.parse(spec) == null, supportingText = { Text("${formatParam(spec.min)}–${formatParam(spec.max)}", fontSize = 10.sp) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            draft.kind.lines.filterNot { it.histogram }.forEach { line ->
                OutlinedTextField(draft.colors[line.name] ?: "", { onChange(draft.copy(colors = draft.colors + (line.name to it.uppercase()))) },
                    Modifier.width(170.dp).testTag("indicator-color-$index-${line.name}"), label = { Text("${line.name}颜色", fontSize = 11.sp) }, singleLine = true,
                    isError = !draft.colorValid(line.name), supportingText = { Text("#RRGGBB", fontSize = 10.sp) },
                    leadingIcon = {
                        IconButton({ onPickColor(line.name) }, Modifier.semantics { contentDescription = "选择${draft.kind.label}${line.name}颜色" }) {
                            Box(Modifier.size(22.dp).background(parseHexColor(draft.colors[line.name]) ?: colors.Muted, CircleShape))
                        }
                    })
            }
        }
        if (draft.kind == IndicatorKind.RSI) {
            Text("水平参考线", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("0–100，最多两位小数，不可重复；移除全部参考线即可关闭。", fontSize = 10.sp, color = colors.Muted)
            val values = draft.referenceLevels.map { it.parse() }
            draft.referenceLevels.forEachIndexed { rowIndex, row ->
                fun updateRow(value: ReferenceLevelDraft) {
                    onChange(draft.copy(referenceLevels = draft.referenceLevels.mapIndexed { i, level -> if (i == rowIndex) value else level }))
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.value, { updateRow(row.copy(value = it)) },
                            Modifier.weight(1f).testTag("indicator-reference-value-$index-$rowIndex"),
                            label = { Text("参考值 ${rowIndex + 1}", fontSize = 11.sp) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = values[rowIndex] == null || values.count { it == values[rowIndex] } > 1)
                        TextButton({ onChange(draft.copy(referenceLevels = draft.referenceLevels.filterIndexed { i, _ -> i != rowIndex })) },
                            Modifier.testTag("remove-indicator-reference-$index-$rowIndex").semantics { contentDescription = "移除参考线 ${rowIndex + 1}" },
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("移除", fontSize = 11.sp) }
                    }
                    OutlinedTextField(row.color, { updateRow(row.copy(color = it.uppercase())) },
                        Modifier.fillMaxWidth().testTag("indicator-reference-color-$index-$rowIndex"),
                        label = { Text("参考线 ${rowIndex + 1} 颜色", fontSize = 11.sp) }, singleLine = true,
                        isError = !row.colorValid(), supportingText = { Text("#RRGGBB", fontSize = 10.sp) },
                        leadingIcon = {
                            IconButton({ onPickReferenceColor(rowIndex) },
                                Modifier.testTag("pick-indicator-reference-color-$index-$rowIndex").semantics { contentDescription = "选择参考线 ${rowIndex + 1} 颜色" }) {
                                Box(Modifier.size(22.dp).background(parseHexColor(row.color) ?: colors.Muted, CircleShape))
                            }
                        })
                }
            }
            if (draft.referenceLevels.isEmpty()) Text("参考线已关闭。", fontSize = 11.sp, color = colors.Muted)
            TextButton({ onChange(draft.copy(referenceLevels = draft.referenceLevels + ReferenceLevelDraft())) },
                Modifier.testTag("add-indicator-reference-$index")) { Text("添加参考线", fontSize = 11.sp) }
        }
        if (draft.kind == IndicatorKind.MACD) Text("MACD 柱 = (DIF − DEA) × 2，按涨跌颜色绘制。", fontSize = 10.sp, color = colors.Muted)
        if (config == null) Text("参数、颜色或参考值无效，请修改后保存。", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ onMove(-1) }, enabled = index > 0) { Text("上移", fontSize = 11.sp) }
            TextButton({ onMove(1) }, enabled = index < total - 1) { Text("下移", fontSize = 11.sp) }
            TextButton(onRemove, Modifier.testTag("remove-indicator-$index")) { Text("移除", fontSize = 11.sp) }
        }
    }
}
