package com.unitrading.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.ui.TradingPalette
import com.unitrading.ui.contentOn
import com.unitrading.ui.pnl
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun TradingPalette.lineColor(tone: LineTone): Color = when (tone) {
    LineTone.BUY -> Up
    LineTone.SELL -> Down
    LineTone.STOP -> Stop
    LineTone.TARGET -> Target
    LineTone.NEUTRAL -> Muted
}

/** What every tag on the chart shares. [text] returns the measured text of a cell. */
internal class OrderTagStyle(val colors: TradingPalette, val corner: Float, val icon: Float, val close: Painter, val text: (String) -> TextLayoutResult)

/**
 * One line's tag in TradingView's layout: a filled title, then outlined boxes for the result, the protection buttons,
 * the quantity and type, and the close button. A tag pushed off its line by a neighbour keeps a leader back to [lineEnd].
 */
internal fun DrawScope.drawOrderTag(tag: PlacedTag, style: OrderTagStyle, lineEnd: Float, hovered: PlacedCell?) {
    val colors = style.colors
    val color = colors.lineColor(tag.line.tone)
    if (abs(tag.bounds.center.y - tag.lineY) > 1f) drawLine(color.copy(alpha = .65f), Offset(tag.bounds.right, tag.bounds.center.y), Offset(lineEnd, tag.lineY), 1f)
    // A draft is not at the broker yet: only its title stays bright, as on TradingView's order preview.
    val strength = if (tag.line.preview) .6f else 1f
    tag.cells.groupBy { it.cell.kind.group }.values.forEach { group ->
        val box = Rect(group.first().bounds.left, group.first().bounds.top, group.last().bounds.right, group.last().bounds.bottom)
        val edge = when (group.first().cell.kind) { CellKind.ADD_TARGET -> colors.Target; CellKind.ADD_STOP -> colors.Stop; else -> color }
        val shape = Path().apply { addRoundRect(RoundRect(box, CornerRadius(style.corner))) }
        clipPath(shape) {
            drawRect(colors.Surface, box.topLeft, box.size)
            group.forEach { placed ->
                val kind = placed.cell.kind
                val fill = when {
                    kind == CellKind.TITLE -> color
                    kind == CellKind.VALUE || kind == CellKind.CLOSE -> Color.Transparent
                    else -> edge.copy(alpha = .14f)
                }
                drawRect(fill, placed.bounds.topLeft, placed.bounds.size)
                if (placed.cell.action != null && placed.bounds == hovered?.bounds)
                    drawRect((if (kind == CellKind.CLOSE) colors.Loss else colors.Ink).copy(alpha = .28f), placed.bounds.topLeft, placed.bounds.size)
            }
            group.zipWithNext().filter { (left, _) -> left.cell.kind != CellKind.TITLE }.forEach { (left, _) ->
                drawLine(edge.copy(alpha = .4f * strength), Offset(left.bounds.right, box.top), Offset(left.bounds.right, box.bottom), 1f)
            }
        }
        drawPath(shape, edge.copy(alpha = .8f * strength), style = Stroke(1f))
        group.forEach { placed ->
            val kind = placed.cell.kind
            val ink = when (kind) {
                CellKind.TITLE -> contentOn(color)
                CellKind.RESULT -> colors.pnl(tag.line.pnl)
                CellKind.ADD_TARGET, CellKind.ADD_STOP -> edge
                CellKind.VALUE, CellKind.CLOSE -> colors.Ink
            }.let { if (kind == CellKind.TITLE) it else it.copy(alpha = strength) }
            if (kind == CellKind.CLOSE) {
                translate(placed.bounds.center.x - style.icon / 2, placed.bounds.center.y - style.icon / 2) {
                    with(style.close) { draw(Size(style.icon, style.icon), colorFilter = ColorFilter.tint(ink)) }
                }
            } else {
                val text = style.text(placed.cell.text)
                drawText(text, color = ink, topLeft = Offset(placed.bounds.center.x - text.size.width / 2f, placed.bounds.center.y - text.size.height / 2f))
            }
        }
    }
}

/** A right click on an order line, and the buttons of that line, offered by name. */
internal data class LineMenu(val at: Offset, val cells: List<LineCell>)

@Composable
internal fun LineContextMenu(menu: LineMenu, onDismiss: () -> Unit, onAction: (LineAction) -> Unit) {
    Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }) {
        DropdownMenu(true, onDismiss, Modifier.widthIn(min = 160.dp)) {
            menu.cells.forEachIndexed { index, cell ->
                DropdownMenuItem(
                    text = { Text(cell.hint, color = if (cell.kind == CellKind.CLOSE) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    onClick = { onDismiss(); cell.action?.let(onAction) },
                    modifier = Modifier.testTag("line-menu-$index"),
                )
            }
        }
    }
}
