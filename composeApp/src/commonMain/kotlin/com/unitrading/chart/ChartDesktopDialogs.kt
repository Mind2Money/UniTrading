package com.unitrading.chart

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.ChartTemplate
import com.unitrading.app.ChartTemplateController
import com.unitrading.chart.drawing.ChartDrawings
import kotlinx.coroutines.launch

@Composable
internal fun ChartObjectsDialog(drawings: ChartDrawings, indicators: List<IndicatorConfig>, onIndicators: (List<IndicatorConfig>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("对象树") }, text = {
        Column(Modifier.widthIn(min = 300.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("绘图 · ${drawings.drawings.size}", style = MaterialTheme.typography.titleSmall)
            if (drawings.drawings.isEmpty()) Text("当前品种没有绘图", style = MaterialTheme.typography.bodySmall)
            drawings.drawings.asReversed().forEach { drawing ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { drawings.select(drawing.id); onDismiss() }, modifier = Modifier.weight(1f)) { Text(drawing.tool.label, maxLines = 1) }
                    TextButton(onClick = { drawings.update(drawing.copy(hidden = !drawing.hidden)) }) { Text(if (drawing.hidden) "显示" else "隐藏") }
                    TextButton(onClick = { drawings.update(drawing.copy(locked = !drawing.locked)) }) { Text(if (drawing.locked) "解锁" else "锁定") }
                    TextButton(onClick = { drawings.remove(drawing.id) }, enabled = !drawing.locked) { Text("删除") }
                }
            }
            HorizontalDivider()
            Text("指标 · ${indicators.size}", style = MaterialTheme.typography.titleSmall)
            indicators.forEach { indicator ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(indicator.label, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onIndicators(indicators.map { if (it.id == indicator.id) it.copy(visible = !it.visible) else it }) }) { Text(if (indicator.visible) "隐藏" else "显示") }
                    TextButton(onClick = { onIndicators(indicators.filterNot { it.id == indicator.id }) }) { Text("删除") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, modifier = Modifier.testTag("chart-object-tree"))
}

@Composable
internal fun ChartTemplatesDialog(controller: ChartTemplateController, indicators: List<IndicatorConfig>, axis: PriceAxisSettings,
    onApply: (ChartTemplate) -> Unit, onDismiss: () -> Unit) {
    val templates by controller.templates.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图表模板") }, text = {
        Column(Modifier.widthIn(min = 300.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("保存指标和价格轴设置，不包含订单、账户或绘图。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("chart-template-name"))
            TextButton(onClick = { scope.launch { busy = true; try { if (controller.save(ChartTemplate(name, indicators, axis))) name = "" } finally { busy = false } } },
                enabled = name.isNotBlank() && !busy, modifier = Modifier.testTag("chart-template-save")) { Text("保存或覆盖同名模板") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            templates.forEach { template ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(template.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onApply(template); onDismiss() }, enabled = !busy, modifier = Modifier.testTag("chart-template-apply-${template.name}")) { Text("应用") }
                    TextButton(onClick = { scope.launch { busy = true; try { controller.delete(template.name) } finally { busy = false } } }, enabled = !busy) { Text("删除") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, modifier = Modifier.testTag("chart-templates"))
}

@Composable
internal fun ChartSettingsDialog(axis: PriceAxisSettings, showHistory: Boolean, onAxis: (PriceAxisSettings) -> Unit,
    onHistory: (Boolean) -> Unit, onAuto: () -> Unit, onIndicators: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图表设置") }, text = {
        Column(Modifier.widthIn(min = 280.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            @Composable fun setting(label: String, checked: Boolean, change: (Boolean) -> Unit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(checked, change) }
            }
            setting("反转价格轴", axis.inverted) { onAxis(axis.copy(inverted = it)) }
            setting("价格轴在左侧", axis.left) { onAxis(axis.copy(left = it)) }
            setting("仅缩放价格图表", axis.chartOnly) { onAxis(axis.copy(chartOnly = it)) }
            setting("显示历史交易", showHistory, onHistory)
            TextButton(onClick = onAuto) { Text("自动适应屏幕") }
            if (onIndicators != null) TextButton(onClick = { onDismiss(); onIndicators() }) { Text("指标设置") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, modifier = Modifier.testTag("chart-settings-dialog"))
}
