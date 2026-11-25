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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.app.IndicatorController
import com.unitrading.app.IndicatorSettings
import com.unitrading.broker.parseFixed
import com.unitrading.chart.*
import com.unitrading.chart.pine.DEFAULT_PINE_SOURCE
import com.unitrading.chart.pine.MAX_PINE_SOURCE_LENGTH
import com.unitrading.chart.pine.pineScriptError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val lineStyles: Map<String, IndicatorLineStyle> = emptyMap(),
    val pineSource: String? = null,
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
        val config = IndicatorConfig(id, kind, values, colors.mapValues { it.value.uppercase() }, visible, levels, guideColors, lineStyles, pineSource = pineSource)
        return config.takeIf { runCatching { it.validate() }.isSuccess }
    }
    companion object {
        fun from(config: IndicatorConfig) = IndicatorDraft(
            config.id, config.kind,
            config.kind.params.associate { it.key to formatParam(config.param(it.key)) },
            config.kind.lines.withIndex().filterNot { it.value.histogram }.associate { (index, line) -> line.name to config.color(index) },
            config.visible,
            if (config.kind == IndicatorKind.RSI) config.guides.map { ReferenceLevelDraft(formatParam(it), config.guideColor(it)) } else emptyList(),
            config.lineStyles,
            pineSource = config.pineSource,
        )
        fun new(kind: IndicatorKind) = from(IndicatorConfig(Random.nextLong().toULong().toString(16), kind,
            pineSource = DEFAULT_PINE_SOURCE.takeIf { kind == IndicatorKind.PINE }))
    }
}

internal fun List<IndicatorDraft>.toSettings(): IndicatorSettings? {
    val configs = map { it.toConfig() ?: return null }
    return IndicatorSettings(configs).takeIf { runCatching { it.validate() }.isSuccess }
}

private enum class IndicatorSavePhase { CHECKING_PINE, SAVING }

private data class PineSaveFailure(val id: String, val source: String?, val message: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IndicatorSettingsDialog(controller: IndicatorController, onDismiss: () -> Unit) {
    val saved by controller.settings.collectAsState()
    var drafts by remember { mutableStateOf(saved.indicators.map(IndicatorDraft::from)) }
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    var savePhase by remember { mutableStateOf<IndicatorSavePhase?>(null) }
    var pineFailure by remember { mutableStateOf<PineSaveFailure?>(null) }
    var editingColor by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var editingReferenceColor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val viewport = rememberReorderViewport()
    val settings = remember(drafts) { drafts.toSettings() }
    val colors = TradingColors
    val saving = savePhase == IndicatorSavePhase.SAVING

    fun changeDrafts(value: List<IndicatorDraft>) {
        if (savePhase == IndicatorSavePhase.SAVING) return
        pineFailure?.let { failure ->
            if (value.none { it.id == failure.id && it.pineSource == failure.source }) pineFailure = null
        }
        drafts = value
    }
    fun update(value: IndicatorDraft) = changeDrafts(drafts.map { if (it.id == value.id) value else it })
    fun save() {
        if (savePhase != null || pineFailure != null) return
        val snapshot = drafts
        val candidate = snapshot.toSettings() ?: return
        val hasPine = candidate.indicators.any { it.kind == IndicatorKind.PINE }
        savePhase = if (hasPine) IndicatorSavePhase.CHECKING_PINE else IndicatorSavePhase.SAVING
        scope.launch {
            try {
                if (hasPine) {
                    val failure = withContext(Dispatchers.Default) {
                        candidate.indicators.firstNotNullOfOrNull { config ->
                            ensureActive()
                            if (config.kind != IndicatorKind.PINE) return@firstNotNullOfOrNull null
                            val source = config.pineSource ?: DEFAULT_PINE_SOURCE
                            pineScriptError(source)?.let { PineSaveFailure(config.id, config.pineSource, it) }
                        }
                    }
                    // The form remains editable during compilation. Never commit an older draft.
                    if (drafts != snapshot) return@launch
                    if (failure != null) {
                        pineFailure = failure
                        return@launch
                    }
                    savePhase = IndicatorSavePhase.SAVING
                }
                if (controller.apply(candidate)) onDismiss()
            } finally {
                savePhase = null
            }
        }
    }

    FormDialog("图表指标", if (savePhase == IndicatorSavePhase.CHECKING_PINE) "校验中" else "保存",
        settings != null && savePhase == null && pineFailure == null, ::save, {
            if (savePhase != IndicatorSavePhase.SAVING) {
                scope.coroutineContext.cancelChildren()
                onDismiss()
            }
        }, "indicator-settings", "indicators-cancel", "indicators-save", dismissEnabled = !saving) {
        val failure = pineFailure
        val failedIndex = if (failure != null) drafts.indexOfFirst { it.id == failure.id } else -1
        // Keep the failed script and line visible even when the editor is off-screen or the IME is open.
        if (failure != null && failedIndex >= 0) {
            Text("Pine ${failedIndex + 1} · ${failure.message}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("indicator-pine-error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).then(viewport.scrolling).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置仅应用于当前图表，切换该图表的品种或周期后继续保留。", style = MaterialTheme.typography.bodyMedium, color = colors.Muted)
            Text("添加指标", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatorKind.entries.forEach { kind ->
                    AssistChip({ changeDrafts(drafts + IndicatorDraft.new(kind)) }, { Text(kind.label) }, Modifier.testTag("add-indicator-${kind.name}"),
                        enabled = !saving && drafts.size < IndicatorSettings.MAX_INDICATORS,
                        leadingIcon = { Icon(AppIcons.Add, contentDescription = kind.title, Modifier.size(AssistChipDefaults.IconSize)) })
                }
            }
            if (drafts.isEmpty()) EmptyState("尚未添加指标", "点击上方指标，可添加多个并分别设置参数、线条颜色、线宽和线型。", icon = AppIcons.Tune)
            else ReorderableColumn(viewport, drafts, { it.id }, { it.kind.label }, ::changeDrafts, spacing = 12.dp, heldShape = MaterialTheme.shapes.medium) { card ->
                val index = card.index
                IndicatorEditor(index, card.value, card.grip,
                    pineError = failure?.takeIf { it.id == card.value.id }?.message,
                    saving = saving,
                    onChange = ::update,
                    onRemove = { if (!card.dragging) changeDrafts(drafts.filterNot { it.id == card.value.id }) },
                    onPickColor = { line -> if (savePhase != IndicatorSavePhase.SAVING) editingColor = index to line },
                    onPickReferenceColor = { row -> if (savePhase != IndicatorSavePhase.SAVING) editingReferenceColor = index to row })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        editingColor?.let { (index, line) ->
            val draft = drafts.getOrNull(index) ?: return@let
            ColorPicker("${draft.kind.label} ${line}颜色", parseHexColor(draft.colors[line]) ?: colors.Accent, { editingColor = null }) { value ->
                update(draft.copy(colors = draft.colors + (line to colorHex(value))))
                editingColor = null
            }
        }
        editingReferenceColor?.let { (index, rowIndex) ->
            val draft = drafts.getOrNull(index) ?: return@let
            val row = draft.referenceLevels.getOrNull(rowIndex) ?: return@let
            ColorPicker("RSI 参考线 ${rowIndex + 1} 颜色", parseHexColor(row.color) ?: parseHexColor(DEFAULT_INDICATOR_GUIDE_COLOR)!!,
                { editingReferenceColor = null }) { value ->
                update(draft.copy(referenceLevels = draft.referenceLevels.mapIndexed { i, level ->
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
    pineError: String?,
    saving: Boolean,
    onChange: (IndicatorDraft) -> Unit,
    onRemove: () -> Unit,
    onPickColor: (String) -> Unit,
    onPickReferenceColor: (Int) -> Unit,
) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val isPine = draft.kind == IndicatorKind.PINE
    val config = if (isPine) null else remember(draft) { draft.toConfig() }
    Surface(Modifier.fillMaxWidth().testTag("indicator-$index"), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHighest) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The icon lines up with the fields below; its touch target overhangs the card padding.
                Row(Modifier.weight(1f).offset(x = (-12).dp).then(grip), verticalAlignment = Alignment.CenterVertically) {
                    ReorderGrip("indicator-drag-$index")
                    Column(Modifier.weight(1f)) {
                        Text(if (isPine) "Pine ${index + 1}" else config?.label ?: draft.kind.label, style = type.titleSmall)
                        if (!isPine) Text(draft.kind.title + if (draft.kind.pane == IndicatorPane.OVERLAY) " · 叠加在 K 线上" else " · 独立副图", style = type.bodySmall, color = colors.Muted)
                    }
                }
                ActionIcon(AppIcons.Delete, "移除 ${draft.kind.label}", "remove-indicator-$index", enabled = !saving, onClick = onRemove)
            }
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("显示", Modifier.weight(1f), style = type.bodyMedium)
                Switch(draft.visible, { onChange(draft.copy(visible = it)) },
                    Modifier.testTag("indicator-visible-$index").semantics { contentDescription = "显示${draft.kind.label}" }, enabled = !saving)
            }
            if (isPine) {
                PineSourceEditor(index, draft.pineSource ?: DEFAULT_PINE_SOURCE, pineError, saving) { onChange(draft.copy(pineSource = it)) }
                return@Column
            }
            FlowRow(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.kind.params.forEach { spec ->
                    OutlinedTextField(draft.params[spec.key] ?: "", { onChange(draft.copy(params = draft.params + (spec.key to it))) },
                        Modifier.width(140.dp).testTag("indicator-param-$index-${spec.key}"), label = { Text(spec.label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (spec.integer) KeyboardType.Number else KeyboardType.Decimal),
                        isError = draft.parse(spec) == null, supportingText = { Text("${formatParam(spec.min)}–${formatParam(spec.max)}") })
                }
            }
            draft.kind.lines.filterNot { it.histogram }.forEach { line ->
                val lineStyle = draft.lineStyles[line.name] ?: IndicatorLineStyle()
                Column(Modifier.fillMaxWidth().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorField("${line.name}颜色", draft.colors[line.name] ?: "", draft.colorValid(line.name), "选择${draft.kind.label}${line.name}颜色",
                        "indicator-color-$index-${line.name}", Modifier.fillMaxWidth(), { onPickColor(line.name) },
                        { onChange(draft.copy(colors = draft.colors + (line.name to it.uppercase()))) })
                    IndicatorStylePicker(index, line.name, lineStyle) { style ->
                        onChange(draft.copy(lineStyles = draft.lineStyles + (line.name to style)))
                    }
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
            if (draft.kind == IndicatorKind.VWAP) Text("仅显示最新交易时段：北京时间冬令时 07:00 至次日 05:59，夏令时 06:00 至次日 04:59，自动切换；休市一小时不计入。按已加载的日内 K 线及成交量近似计算，日线及以上不显示。", style = type.bodySmall, color = colors.Muted)
            if (draft.kind == IndicatorKind.VOLUME) Text("柱体颜色跟随 K 线涨跌；无成交量数据时留空。", style = type.bodySmall, color = colors.Muted)
            if (draft.kind == IndicatorKind.MACD) Text("MACD 柱 = (DIF − DEA) × 2，按涨跌颜色绘制。", style = type.bodySmall, color = colors.Muted)
            if (config == null) Text("参数、颜色或参考值无效，请修改后保存。", style = type.bodySmall, color = scheme.error)
        }
    }
}

@Composable
private fun PineSourceEditor(index: Int, source: String, error: String?, saving: Boolean, onChange: (String) -> Unit) {
    var overLimit by remember { mutableStateOf(false) }
    val message = when {
        overLimit -> "最多 $MAX_PINE_SOURCE_LENGTH 字符，超长输入未应用。"
        source.isBlank() -> "请输入 Pine 源码。"
        else -> error
    }
    Column(Modifier.fillMaxWidth().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("仅支持 Pine v5/v6 子集。indicator(…, overlay=true) 叠加在 K 线上；省略 overlay 或设为 false 时显示为独立副图。",
            style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
        OutlinedTextField(source, { value ->
            // Reject an oversized paste as a whole rather than silently saving truncated code.
            overLimit = value.length > MAX_PINE_SOURCE_LENGTH
            if (!overLimit) onChange(value)
        }, Modifier.fillMaxWidth().testTag("indicator-pine-source-$index"), enabled = !saving,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text("Pine 源码") }, minLines = 3, maxLines = 6, isError = message != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            supportingText = { Text(message ?: "${source.length} / $MAX_PINE_SOURCE_LENGTH 字符", Modifier.testTag("indicator-pine-support-$index")) })
    }
}

/** Choose each output line independently; histogram bars have no line style. */
@Composable
private fun IndicatorStylePicker(index: Int, line: String, style: IndicatorLineStyle, onChange: (IndicatorLineStyle) -> Unit) {
    var widthOpen by remember(index, line) { mutableStateOf(false) }
    var patternOpen by remember(index, line) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton({ widthOpen = true }, Modifier.fillMaxWidth().height(48.dp).testTag("indicator-width-$index-$line"),
                shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("线宽 ${if (style.width == 1.4f) "默认" else style.width.toInt().toString()}", maxLines = 1)
            }
            DropdownMenu(widthOpen, { widthOpen = false }) {
                listOf(1f, 1.4f, 2f, 3f, 4f, 5f).forEach { width ->
                    DropdownMenuItem(text = { Text(if (width == 1.4f) "默认" else "${width.toInt()}") },
                        onClick = { onChange(style.copy(width = width)); widthOpen = false },
                        modifier = Modifier.testTag("indicator-width-$index-$line-${if (width == 1.4f) "default" else width.toInt()}"))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            OutlinedButton({ patternOpen = true }, Modifier.fillMaxWidth().height(48.dp).testTag("indicator-pattern-$index-$line"),
                shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("线型 ", maxLines = 1)
                Text(style.pattern.label, maxLines = 1)
            }
            DropdownMenu(patternOpen, { patternOpen = false }) {
                IndicatorLinePattern.entries.forEach { pattern ->
                    DropdownMenuItem(text = { Text(pattern.label) }, onClick = { onChange(style.copy(pattern = pattern)); patternOpen = false },
                        modifier = Modifier.testTag("indicator-pattern-$index-$line-${pattern.name}"))
                }
            }
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
