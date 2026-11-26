package com.unitrading.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Quote
import com.unitrading.broker.Side
import com.unitrading.ui.DividerLine
import com.unitrading.ui.AppIcons
import com.unitrading.ui.TradingColors
import com.unitrading.ui.label
import kotlin.math.roundToInt

/** An entry order the plot's context menu offers at the clicked price. */
internal data class PlotOrder(val side: Side, val kind: OrderKind)

/**
 * A right click on the plot away from every order line and drawing: where the menu opens, the [price] under the pointer
 * on the tick grid, and the [orders] offered when it opened. They stay as they were while the menu is open, so a moving
 * quote does not change the item under the pointer.
 */
internal data class PlotMenu(val at: Offset, val price: Long, val orders: List<PlotOrder>, val desktop: Boolean = false,
    val timeMs: Long? = null, val longPress: Boolean = false)

internal class PlotMenuActions(
    val copyPrice: () -> Unit,
    val pasteDrawing: (() -> Unit)?,
    val openOrder: (() -> Unit)?,
    val toggleTimeLock: (() -> Unit)?,
    val timeLocked: Boolean,
    val showTable: () -> Unit,
    val showObjects: (() -> Unit)?,
    val showTemplates: (() -> Unit)?,
    val removeIndicators: (() -> Unit)?,
    val indicatorCount: Int,
    val removeDrawings: (() -> Unit)?,
    val drawingCount: Int,
    val keyboardTrading: Boolean,
    val settings: (() -> Unit)?,
)

/**
 * With a quote, offer a market buy and sell first (no order price), then both limits at [price], even if one could
 * execute immediately. Buy stops still need a price strictly above the ask and sell stops strictly below the bid.
 * Without a quote nothing is offered. The broker still validates the chosen order when it is sent.
 */
internal fun plotOrders(price: Long, quote: Quote?, kinds: Set<OrderKind>): List<PlotOrder> {
    quote ?: return emptyList()
    return listOfNotNull(
        PlotOrder(Side.BUY, OrderKind.MARKET),
        PlotOrder(Side.SELL, OrderKind.MARKET),
        PlotOrder(Side.BUY, OrderKind.LIMIT),
        PlotOrder(Side.SELL, OrderKind.LIMIT),
        PlotOrder(Side.BUY, OrderKind.STOP).takeIf { price > quote.ask },
        PlotOrder(Side.SELL, OrderKind.STOP).takeIf { price < quote.bid },
    ).filter { it.kind in kinds }
}

/**
 * "买入限价 1 张 @ 5505.00", or "买入市价 1 张" for a market order, which has no price of its own; the quantity is left
 * out while the order panel holds none that parses. On an account that
 * sizes its orders from a stop ([sizing]) the item only opens the order panel, where the stop sets the quantity, and
 * says so. Otherwise it names the protection the account's cash amounts add ([attached]), which no other step shows
 * when orders go out without a confirmation.
 */
internal fun plotOrderLabel(instrument: Instrument, order: PlotOrder, lotsHundredths: Long?, price: Long,
    sizing: Boolean = false, attached: String? = null): String =
    listOfNotNull("${order.side.label()}${order.kind.label()}",
        lotsHundredths?.takeUnless { sizing }?.let { "${instrument.formatQuantity(it)} ${instrument.quantityUnit}" },
        if (order.kind == OrderKind.MARKET) null else "@ ${instrument.formatPrice(price)}").joinToString(" ") + when {
            sizing -> " · 打开下单栏填写 SL"
            attached != null -> " · 开仓自动附加 $attached"
            else -> ""
        }

@Composable
private fun PlotMenuIcon(icon: ImageVector, name: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, phone: Boolean = false) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(if (phone) 16.dp else 18.dp).testTag("plot-icon-$name"), tint = tint)
}

/**
 * A long press offers a view reset; the price-axis menu can still add a horizontal line. [lotsHundredths] is the
 * quantity an order would carry, shown in its label.
 */
@Composable
internal fun PlotContextMenu(menu: PlotMenu, instrument: Instrument, lotsHundredths: Long?, onDismiss: () -> Unit,
    onReset: () -> Unit, onLine: (() -> Unit)?, onOrder: (PlotOrder) -> Unit,
    stopRequiredForSizing: Boolean = false, attached: String? = null, desktopActions: PlotMenuActions? = null,
    phone: Boolean = false) {
    val colors = TradingColors
    val extended = menu.desktop || phone
    val shortcut = menu.desktop && !phone
    val itemSize = if (phone) Modifier.height(44.dp) else Modifier
    val itemText = if (phone) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
    Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }) {
        DropdownMenu(true, onDismiss, if (phone) Modifier.widthIn(min = 200.dp, max = 280.dp).testTag("phone-plot-menu") else Modifier.widthIn(min = 200.dp)) {
            if (menu.longPress || (menu.desktop && desktopActions != null)) {
                DropdownMenuItem(text = { Text(if (menu.longPress) "重置视图" else "重置图表视图", style = itemText) },
                    leadingIcon = { PlotMenuIcon(AppIcons.RestartAlt, "reset", phone = phone) },
                    trailingIcon = if (shortcut) ({ Text("Alt + R", style = MaterialTheme.typography.labelSmall, color = colors.Muted) }) else null,
                    onClick = { onDismiss(); onReset() }, modifier = itemSize.testTag("plot-reset"))
                if (menu.desktop) DividerLine()
            }
            if (!menu.desktop && !menu.longPress && onLine != null) DropdownMenuItem(
                text = { Text("在 ${instrument.formatPrice(menu.price)} 画水平线") },
                onClick = { onDismiss(); onLine() },
                modifier = Modifier.testTag("plot-menu-horizontal-line"),
            )
            // The reset or line, the market orders and priced orders form separate groups.
            val groups = listOf(menu.orders.filter { it.kind == OrderKind.MARKET }, menu.orders.filter { it.kind != OrderKind.MARKET })
            var above = !menu.desktop && (menu.longPress || onLine != null)
            groups.filter { it.isNotEmpty() }.forEach { group ->
                if (above) DividerLine(Modifier.padding(vertical = 4.dp))
                above = true
                group.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(plotOrderLabel(instrument, order, lotsHundredths, menu.price, stopRequiredForSizing, attached),
                                color = if (order.side == Side.BUY) colors.UpText else colors.DownText, style = itemText, maxLines = if (phone) 2 else Int.MAX_VALUE)
                        },
                        leadingIcon = if (extended) ({ PlotMenuIcon(if (order.side == Side.BUY) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            "${order.side.name}-${order.kind.name}", if (order.side == Side.BUY) colors.UpText else colors.DownText, phone) }) else null,
                        trailingIcon = if (shortcut && desktopActions?.keyboardTrading == true && order.side == Side.SELL && order.kind == OrderKind.LIMIT) ({ Text("Alt + Shift + S") }) else null,
                        onClick = { onDismiss(); onOrder(order) },
                        modifier = itemSize.testTag("plot-menu-${order.side.name}-${order.kind.name}"),
                    )
                }
            }
            if (extended && desktopActions != null) {
                DropdownMenuItem(text = { Text("止损限价单（当前接口不支持）", style = itemText) }, enabled = false, onClick = {},
                    leadingIcon = { PlotMenuIcon(AppIcons.Lock, "stop-limit", colors.Muted.copy(alpha = .5f), phone) }, modifier = itemSize.testTag("plot-stop-limit-unavailable"))
                if (!phone) DropdownMenuItem(text = { Text("以 ${instrument.formatPrice(menu.price)} 打开下单面板") }, enabled = desktopActions.openOrder != null,
                    leadingIcon = { PlotMenuIcon(AppIcons.OrderEntry, "open-order") },
                    trailingIcon = { Text("Shift + T", style = MaterialTheme.typography.labelSmall, color = colors.Muted) },
                    onClick = { onDismiss(); desktopActions.openOrder?.invoke() }, modifier = Modifier.testTag("plot-open-order"))
                DividerLine()
                if (!phone) {
                    DropdownMenuItem(text = { Text("对象树", style = itemText) }, enabled = desktopActions.showObjects != null,
                        leadingIcon = { PlotMenuIcon(AppIcons.Layers, "objects") }, onClick = { onDismiss(); desktopActions.showObjects?.invoke() }, modifier = itemSize.testTag("plot-objects"))
                    DropdownMenuItem(text = { Text("图表模板", style = itemText) }, enabled = desktopActions.showTemplates != null,
                        leadingIcon = { PlotMenuIcon(AppIcons.ViewQuilt, "templates") }, onClick = { onDismiss(); desktopActions.showTemplates?.invoke() }, modifier = itemSize.testTag("plot-templates"))
                    DividerLine()
                }
                if (!phone) DropdownMenuItem(text = { Text("移除 ${desktopActions.indicatorCount} 个指标") }, enabled = desktopActions.removeIndicators != null && desktopActions.indicatorCount > 0,
                    leadingIcon = { PlotMenuIcon(AppIcons.Tune, "remove-indicators") },
                    onClick = { onDismiss(); desktopActions.removeIndicators?.invoke() }, modifier = Modifier.testTag("plot-remove-indicators"))
                DropdownMenuItem(text = { Text("移除 ${desktopActions.drawingCount} 个绘图", style = itemText) }, enabled = desktopActions.removeDrawings != null && desktopActions.drawingCount > 0,
                    leadingIcon = { PlotMenuIcon(AppIcons.Delete, "remove-drawings", phone = phone) },
                    onClick = { onDismiss(); desktopActions.removeDrawings?.invoke() }, modifier = itemSize.testTag("plot-remove-drawings"))
                desktopActions.settings?.takeUnless { phone }?.let { settings ->
                    DividerLine()
                    DropdownMenuItem(text = { Text("设置") }, leadingIcon = { PlotMenuIcon(AppIcons.Settings, "settings") },
                        onClick = { onDismiss(); settings() }, modifier = Modifier.testTag("plot-settings"))
                }

            }
        }
    }
}
