package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The first cell is the existing main chart; remaining cells retain their own symbol and interval. */
internal enum class ChartSplitLayout(val label: String, val count: Int) {
    SINGLE("单图", 1),
    TWO_COLUMNS("双图 · 左右", 2),
    TWO_ROWS("双图 · 上下", 2),
    THREE_LEFT("三图 · 左大右二", 3),
    THREE_TOP("三图 · 上大下二", 3),
    FOUR_GRID("四图 · 田字", 4),
    THREE_RIGHT("三图 · 左二右大", 3),
}

/** One draggable divider, shared by mouse and touch. Each side keeps at least 20% of the available space. */
@Composable
private fun ChartSplitPane(
    horizontal: Boolean, id: String, ratios: MutableMap<String, Float>, modifier: Modifier = Modifier,
    first: @Composable () -> Unit, second: @Composable () -> Unit,
) {
    var ratio by remember(id) { mutableFloatStateOf(ratios[id] ?: .5f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val sizePx = (if (horizontal) constraints.maxWidth else constraints.maxHeight).toFloat()
        val usable = (sizePx - with(density) { 8.dp.toPx() }).coerceAtLeast(1f)
        val divider = Modifier.testTag("chart-divider-$id")
            .background(MaterialTheme.colorScheme.surface)
            .draggable(rememberDraggableState { delta -> ratio = (ratio + delta / usable).coerceIn(.2f, .8f); ratios[id] = ratio },
                if (horizontal) Orientation.Horizontal else Orientation.Vertical)
            .semantics { contentDescription = "拖动调整图表大小" }
        if (horizontal) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(ratio).fillMaxHeight()) { first() }
            Box(divider.width(8.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            }
            Box(Modifier.weight(1f - ratio).fillMaxHeight()) { second() }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(ratio).fillMaxWidth()) { first() }
            Box(divider.height(8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
            }
            Box(Modifier.weight(1f - ratio).fillMaxWidth()) { second() }
        }
    }
}

@Composable
private fun LayoutIcon(layout: ChartSplitLayout, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.4.dp.toPx()
        drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        fun vertical(x: Float, y1: Float = 0f, y2: Float = 1f) =
            drawLine(color, Offset(size.width * x, size.height * y1), Offset(size.width * x, size.height * y2), stroke)
        fun horizontal(y: Float, x1: Float = 0f, x2: Float = 1f) =
            drawLine(color, Offset(size.width * x1, size.height * y), Offset(size.width * x2, size.height * y), stroke)
        when (layout) {
            ChartSplitLayout.SINGLE -> Unit
            ChartSplitLayout.TWO_COLUMNS -> vertical(.5f)
            ChartSplitLayout.TWO_ROWS -> horizontal(.5f)
            ChartSplitLayout.THREE_LEFT -> { vertical(.5f); horizontal(.5f, .5f, 1f) }
            ChartSplitLayout.THREE_TOP -> { horizontal(.5f); vertical(.5f, .5f, 1f) }
            ChartSplitLayout.FOUR_GRID -> { vertical(.5f); horizontal(.5f) }
            ChartSplitLayout.THREE_RIGHT -> { vertical(.5f); horizontal(.5f, 0f, .5f) }
        }
    }
}

@Composable
internal fun ChartLayoutMenu(layout: ChartSplitLayout, onLayout: (ChartSplitLayout) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("chart-layout-menu")) {
            LayoutIcon(layout, colors.primary)
            Spacer(Modifier.width(6.dp))
            Text("布局")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChartSplitLayout.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) },
                    onClick = { expanded = false; onLayout(option) },
                    leadingIcon = { LayoutIcon(option, if (option == layout) colors.primary else colors.onSurfaceVariant) },
                    trailingIcon = { if (option == layout) Text("✓", color = colors.primary) },
                    modifier = Modifier.testTag("chart-layout-${option.name}"))
            }
        }
    }
}

@Composable
internal fun ChartSplitWorkspace(
    layout: ChartSplitLayout,
    activeChart: String,
    chart: @Composable (String, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ratios = remember { mutableMapOf<String, Float>() }
    Column(modifier.testTag("split-workspace")) {
        @Composable fun cell(id: String, cellModifier: Modifier) {
            Box(cellModifier.border(1.dp,
                if (id == activeChart && layout.count > 1) colors.primary else colors.outlineVariant).clipToBounds()
                .testTag("split-cell-$id")) {
                chart(id, Modifier.fillMaxSize())
            }
        }
        val content = Modifier.weight(1f).fillMaxWidth()
        @Composable fun chartCell(id: String) { cell(id, Modifier.fillMaxSize()) }
        when (layout) {
            ChartSplitLayout.SINGLE -> cell("main", content)
            ChartSplitLayout.TWO_COLUMNS -> ChartSplitPane(true, "two-columns", ratios, content, { chartCell("main") }, { chartCell("split1") })
            ChartSplitLayout.TWO_ROWS -> ChartSplitPane(false, "two-rows", ratios, content, { chartCell("main") }, { chartCell("split1") })
            ChartSplitLayout.THREE_LEFT -> ChartSplitPane(true, "three-left", ratios, content, { chartCell("main") }, {
                ChartSplitPane(false, "three-left-inner", ratios, Modifier.fillMaxSize(), { chartCell("split1") }, { chartCell("split2") })
            })
            ChartSplitLayout.THREE_TOP -> ChartSplitPane(false, "three-top", ratios, content, { chartCell("main") }, {
                ChartSplitPane(true, "three-top-inner", ratios, Modifier.fillMaxSize(), { chartCell("split1") }, { chartCell("split2") })
            })
            ChartSplitLayout.FOUR_GRID -> ChartSplitPane(true, "four", ratios, content, {
                ChartSplitPane(false, "four-left", ratios, Modifier.fillMaxSize(), { chartCell("main") }, { chartCell("split2") })
            }, {
                ChartSplitPane(false, "four-right", ratios, Modifier.fillMaxSize(), { chartCell("split1") }, { chartCell("split3") })
            })
            ChartSplitLayout.THREE_RIGHT -> ChartSplitPane(true, "three-right", ratios, content, {
                ChartSplitPane(false, "three-right-inner", ratios, Modifier.fillMaxSize(), { chartCell("main") }, { chartCell("split1") })
            }, { chartCell("split2") })
        }
    }
}
