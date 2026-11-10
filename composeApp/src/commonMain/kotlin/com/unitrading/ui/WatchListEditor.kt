package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val RowHeight = 48.dp

/** The watch list while editing. One drag is one write. [firstRow] is the row to open at. */
@Composable
internal fun WatchListEditor(symbols: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier, firstRow: Int = 0) {
    val viewport = rememberReorderViewport(firstRow * with(LocalDensity.current) { RowHeight.roundToPx() })
    // The saved list reaches this composable a moment after a change; until then the rows keep the new order.
    var pending by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(symbols) { pending = null }
    val rows = pending ?: symbols
    fun change(next: List<String>) { pending = next; onChange(next) }
    ReorderableColumn(viewport, rows, { it }, { it }, { change(it) }, modifier.then(viewport.scrolling)) { row ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowHeight)
                .background(if (row.held) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).heightIn(min = RowHeight).testTag("market-row-${row.value}").then(row.grip), verticalAlignment = Alignment.CenterVertically) {
                ReorderGrip("market-drag-${row.value}")
                Text(row.value, color = TradingColors.Ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Rows move immediately after removal. A tooltip popup can intercept the next pointer event.
            IconButton({ change(rows - row.value) }, Modifier.testTag("market-remove-${row.value}"), enabled = !row.dragging) {
                Icon(AppIcons.RemoveCircle, "移除 ${row.value}", tint = TradingColors.Loss)
            }
        }
    }
}
