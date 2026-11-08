package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    val viewport = rememberReorderViewport()
    val settings = drafts.toSettings()
    val colors = TradingColors
    fun update(index: Int, value: IndicatorDraft) { drafts = drafts.mapIndexed { i, d -> if (i == index) value else d } }
    FormDialog("图表指标", "保存", settings != null && !busy, {
        scope.launch { busy = true; try { if (settings != null && controller.apply(settings)) onDismiss() } finally { busy = false } }
    }, onDismiss, "indicator-settings", "indicators-cancel", "indicators-save", dismissEnabled = !busy) {
        Column(Modifier.weight(1f).then(viewport.scrolling).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存后对所有品种和周期生效。", style = MaterialTheme.typography.bodyMedium, color = colors.Muted)
            Text("添加指标", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatorKind.entries.forEach { kind ->
                    AssistChip({ drafts = drafts + IndicatorDraft.new(kind) }, { Text(kind.label) }, Modifier.testTag("add-indicator-${kind.name}"),
                        enabled = drafts.size < IndicatorSettings.MAX_INDICATORS,
                        leadingIcon = { Icon(AppIcons.Add, contentDescription = kind.title, Modifier.size(AssistChipDefaults.IconSize)) })
                }
            }
            if (drafts.isEmpty()) EmptyState("尚未添加指标", "点击上方指标，可添加多个并分别设置参数和线条颜色。", icon = AppIcons.Tune)
            else ReorderableColumn(viewport, drafts, { it.id }, { it.kind.label }, { drafts = it }, spacing = 12.dp, heldShape = MaterialTheme.shapes.medium) { card ->
                val index = card.index
                IndicatorEditor(index, card.value, card.grip,
                    onChange = { update(index, it) },
                    onRemove = { if (!card.dragging) drafts = drafts.filterIndexed { i, _ -> i != index } },
                    onPickColor = { line -> editingColor = index to line },
                    onPickReferenceColor = { row -> editingReferenceColor = index to row })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
    grip: Modifier,
    onChange: (IndicatorDraft) -> Unit,
    onRemove: () -> Unit,
    onPickColor: (String) -> Unit,
    onPickReferenceColor: (Int) -> Unit,
) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val config = draft.toConfig()
    Surface(Modifier.fillMaxWidth().testTag("indicator-$index"), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHighest) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The icon lines up with the fields below; its touch target overhangs the card padding.
                Row(Modifier.weight(1f).offset(x = (-12).dp).then(grip), verticalAlignment = Alignment.CenterVertically) {
                    ReorderGrip("indicator-drag-$index")
                    Column(Modifier.weight(1f)) {
                        Text(config?.label ?: draft.kind.label, style = type.titleSmall)
                        Text(draft.kind.title + if (draft.kind.pane == IndicatorPane.OVERLAY) " · 叠加在 K 线上" else " · 独立副图", style = type.bodySmall, color = colors.Muted)
                    }
                }
                ActionIcon(AppIcons.Delete, "移除 ${draft.kind.label}", "remove-indicator-$index", onClick = onRemove)
            }
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("显示", Modifier.weight(1f), style = type.bodyMedium)
                Switch(draft.visible, { onChange(draft.copy(visible = it)) }, Modifier.testTag("indicator-visible-$index").semantics { contentDescription = "显示${draft.kind.label}" })
            }
            FlowRow(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.kind.params.forEach { spec ->
                    OutlinedTextField(draft.params[spec.key] ?: "", { onChange(draft.copy(params = draft.params + (spec.key to it))) },
                        Modifier.width(140.dp).testTag("indicator-param-$index-${spec.key}"), label = { Text(spec.label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (spec.integer) KeyboardType.Number else KeyboardType.Decimal),
                        isError = draft.parse(spec) == null, supportingText = { Text("${formatParam(spec.min)}–${formatParam(spec.max)}") })
                }
            }
            FlowRow(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.kind.lines.filterNot { it.histogram }.forEach { line ->
                    ColorField("${line.name}颜色", draft.colors[line.name] ?: "", draft.colorValid(line.name), "选择${draft.kind.label}${line.name}颜色",
                        "indicator-color-$index-${line.name}", Modifier.width(156.dp), { onPickColor(line.name) },
                        { onChange(draft.copy(colors = draft.colors + (line.name to it.uppercase()))) })
                }
            }
            if (draft.kind == IndicatorKind.RSI) {
                Text("水平参考线", color = scheme.primary, style = type.labelLarge)
                Text("0–100，最多两位小数，不可重复；移除全部参考线即可关闭。", style = type.bodySmall, color = colors.Muted)
                val values = draft.referenceLevels.map { it.parse() }
                draft.referenceLevels.forEachIndexed { rowIndex, row ->
                    fun updateRow(value: ReferenceLevelDraft) {
                        onChange(draft.copy(referenceLevels = draft.referenceLevels.mapIndexed { i, level -> if (i == rowIndex) value else level }))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.value, { updateRow(row.copy(value = it)) }, Modifier.weight(1f).testTag("indicator-reference-value-$index-$rowIndex"),
                            label = { Text("参考值 ${rowIndex + 1}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = values[rowIndex] == null || values.count { it == values[rowIndex] } > 1)
                        ColorField("颜色", row.color, row.colorValid(), "选择参考线 ${rowIndex + 1} 颜色", "indicator-reference-color-$index-$rowIndex", Modifier.weight(1f),
                            { onPickReferenceColor(rowIndex) }, { updateRow(row.copy(color = it.uppercase())) }, pickerTag = "pick-indicator-reference-color-$index-$rowIndex")
                        ActionIcon(AppIcons.Delete, "移除参考线 ${rowIndex + 1}", "remove-indicator-reference-$index-$rowIndex") {
                            onChange(draft.copy(referenceLevels = draft.referenceLevels.filterIndexed { i, _ -> i != rowIndex }))
                        }
                    }
                }
                if (draft.referenceLevels.isEmpty()) Text("参考线已关闭。", style = type.bodySmall, color = colors.Muted)
                TextButton({ onChange(draft.copy(referenceLevels = draft.referenceLevels + ReferenceLevelDraft())) }, Modifier.testTag("add-indicator-reference-$index")) { Text("添加参考线") }
            }
            if (draft.kind == IndicatorKind.MACD) Text("MACD 柱 = (DIF − DEA) × 2，按涨跌颜色绘制。", style = type.bodySmall, color = colors.Muted)
            if (config == null) Text("参数、颜色或参考值无效，请修改后保存。", style = type.bodySmall, color = scheme.error)
        }
    }
}

/** Hex colour with a swatch that opens the picker. */
@Composable
private fun ColorField(label: String, value: String, valid: Boolean, pickerLabel: String, tag: String, modifier: Modifier, onPick: () -> Unit, onValue: (String) -> Unit, pickerTag: String? = null) {
    OutlinedTextField(value, onValue, modifier.testTag(tag), label = { Text(label) }, singleLine = true, isError = !valid,
        leadingIcon = {
            IconButton(onPick, (if (pickerTag != null) Modifier.testTag(pickerTag) else Modifier).semantics { contentDescription = pickerLabel }) {
                Box(Modifier.size(22.dp).background(parseHexColor(value) ?: TradingColors.Muted, CircleShape))
            }
        })
}
