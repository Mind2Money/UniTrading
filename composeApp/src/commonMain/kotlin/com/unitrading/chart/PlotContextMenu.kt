package com.unitrading.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Quote
import com.unitrading.broker.Side
import com.unitrading.ui.DividerLine
import com.unitrading.ui.TradingColors
import com.unitrading.ui.label
import kotlin.math.roundToInt

/** An entry order the plot's context menu offers at the clicked price. */
internal data class PlotOrder(val side: Side, val kind: OrderKind)

/**
 * A right click on the plot away from every order line and drawing: where the menu opens, the [price] under the pointer
 * on the tick grid, and the [orders] that could rest there when it opened. They stay as they were while the menu is
 * open, so a moving quote never swaps the item under the pointer for its opposite.
 */
internal data class PlotMenu(val at: Offset, val price: Long, val orders: List<PlotOrder>)

/**
 * The entry orders that can rest at [price], limits first. A limit buys under the ask and sells over the bid; a stop
 * buys over the ask and sells under the bid. So a price under the market offers a buy limit and a sell stop, one over
 * it a sell limit and a buy stop, and one inside the spread the two limits. Without a quote nothing is offered, as
 * the side of the market is unknown. The broker's own check still decides when the order is sent.
 */
internal fun plotOrders(price: Long, quote: Quote?, kinds: Set<OrderKind>): List<PlotOrder> {
    quote ?: return emptyList()
    return listOfNotNull(
        PlotOrder(Side.BUY, OrderKind.LIMIT).takeIf { price < quote.ask },
        PlotOrder(Side.SELL, OrderKind.LIMIT).takeIf { price > quote.bid },
        PlotOrder(Side.BUY, OrderKind.STOP).takeIf { price > quote.ask },
        PlotOrder(Side.SELL, OrderKind.STOP).takeIf { price < quote.bid },
    ).filter { it.kind in kinds }
}

/** "买入限价 1 张 @ 5505.00"; the quantity is left out while the order panel holds none that parses. */
internal fun plotOrderLabel(instrument: Instrument, order: PlotOrder, lotsHundredths: Long?, price: Long): String =
    "${order.side.label()}${order.kind.label()} " + (lotsHundredths?.let { "${instrument.formatQuantity(it)} ${instrument.quantityUnit} " } ?: "") +
        "@ ${instrument.formatPrice(price)}"

/**
 * The plot's own context menu: a horizontal line at the clicked price when the chart has drawings ([onLine]), and the
 * entry orders of [menu]. [lotsHundredths] is the quantity an order would carry, shown in its label.
 */
@Composable
internal fun PlotContextMenu(menu: PlotMenu, instrument: Instrument, lotsHundredths: Long?, onDismiss: () -> Unit, onLine: (() -> Unit)?, onOrder: (PlotOrder) -> Unit) {
    val colors = TradingColors
    Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }) {
        DropdownMenu(true, onDismiss, Modifier.widthIn(min = 200.dp)) {
            if (onLine != null) DropdownMenuItem(
                text = { Text("在 ${instrument.formatPrice(menu.price)} 画水平线") },
                onClick = { onDismiss(); onLine() },
                modifier = Modifier.testTag("plot-menu-horizontal-line"),
            )
            if (onLine != null && menu.orders.isNotEmpty()) DividerLine(Modifier.padding(vertical = 4.dp))
            menu.orders.forEach { order ->
                DropdownMenuItem(
                    text = { Text(plotOrderLabel(instrument, order, lotsHundredths, menu.price), color = if (order.side == Side.BUY) colors.UpText else colors.DownText) },
                    onClick = { onDismiss(); onOrder(order) },
                    modifier = Modifier.testTag("plot-menu-${order.side.name}-${order.kind.name}"),
                )
            }
        }
    }
}
