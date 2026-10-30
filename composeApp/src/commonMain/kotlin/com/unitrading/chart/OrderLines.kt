package com.unitrading.chart

import com.unitrading.broker.*
import com.unitrading.ui.estimatedExitPnl
import com.unitrading.ui.label

/** An order line the pointer can pick up; [price] is where it was when the drag started. */
internal sealed interface EditableLine {
    val price: Long
    data class Protection(val position: Position, val stop: Boolean, override val price: Long) : EditableLine
    data class Pending(val order: PendingOrder) : EditableLine { override val price get() = order.request.entryPrice!! }
    data class PendingProtection(val order: PendingOrder, val stop: Boolean, override val price: Long) : EditableLine
    data class Draft(val request: OrderRequest) : EditableLine { override val price get() = request.entryPrice!! }
    data class DraftProtection(val request: OrderRequest, val stop: Boolean, override val price: Long) : EditableLine
}

/** A line being dragged, with the price scale frozen at the start so the line follows the pointer exactly. */
internal data class LineDrag(val line: EditableLine, val scale: PriceScale, val price: Long)

internal enum class LineTone { POSITION, STOP, TARGET, ORDER }

/**
 * One horizontal line on the plot. [edit] is set when dragging it does something, [pnl] is the estimated result of a
 * stop loss or take profit at [price] ([gross] when fees are not in it), and [moving] marks the line under the pointer.
 */
internal data class OrderLine(
    val price: Long,
    val label: String,
    val tone: LineTone,
    val dashed: Boolean = true,
    val alpha: Float = 1f,
    val edit: EditableLine? = null,
    val pnl: Long? = null,
    val gross: Boolean = false,
    val moving: Boolean = false,
)

internal fun OrderRequest.withProtection(stop: Boolean, price: Long) = if (stop) copy(stopLoss = price) else copy(takeProfit = price)

internal fun protectionTitle(stop: Boolean) = if (stop) "SL 止损" else "TP 止盈"

/** "毛 -12.50 USD": the estimate is gross when fees are not counted in it. */
internal fun resultLabel(pnl: Long?, gross: Boolean) = "${if (gross) "毛 " else ""}${pnl?.let { money(it, true) } ?: "—"} USD"

internal fun protectionLabel(stop: Boolean, pnl: Long?, gross: Boolean) = "${protectionTitle(stop)} · ${resultLabel(pnl, gross)}"

/** The legend's live line during a drag: an entry shows the whole estimate, a stop loss or take profit its own result. */
internal fun dragReadout(instrument: Instrument, drag: LineDrag, estimate: OrderPreview?, line: OrderLine?): String {
    val price = instrument.formatPrice(drag.price)
    val errors = estimate?.errors.orEmpty().joinToString("；")
    val stop = when (val edit = drag.line) {
        is EditableLine.Protection -> edit.stop
        is EditableLine.PendingProtection -> edit.stop
        is EditableLine.DraftProtection -> edit.stop
        is EditableLine.Pending, is EditableLine.Draft -> null
    }
    if (stop != null) return "${if (stop) "止损" else "止盈"}预览 $price   ${resultLabel(line?.pnl, line?.gross == true)}   $errors".trimEnd()
    val e = estimate ?: return "入场预览 $price"
    return "入场预览 $price   SL ${e.stopLossPnlMicros?.let { money(it, true) } ?: "—"}   TP ${e.takeProfitPnlMicros?.let { money(it, true) } ?: "—"}   " +
        "保证金 ${if (e.marginKnown) money(e.marginMicros) + " USD" else "未知"}   $errors"
}

private fun state(moving: Boolean, editable: Boolean, action: String = "拖动") =
    if (moving) " · 预览" else if (editable) " · $action" else " · 仅显示"

/**
 * Every position, pending order and draft line the chart draws, in drawing order. Stop loss and take profit lines
 * always carry their estimated result, measured from the entry they belong to; while one is dragged it sits at the
 * dragged price. [draft] and [draftPreview] already include a draft drag, and [draftEntry] is where the draft enters
 * (its own price, or for a market order the side of the quote it buys from).
 */
internal fun orderLines(
    instrument: Instrument,
    positions: List<Position>,
    selectedPosition: Position?,
    orders: List<PendingOrder>,
    draft: OrderRequest?,
    draftPreview: OrderPreview?,
    draftEntry: Long?,
    drag: LineDrag?,
    options: OrderLineOptions,
): List<OrderLine> = buildList {
    val moving = drag?.line
    if (options.showPositions) {
        positions.forEach { p ->
            add(OrderLine(p.entryPrice, "${p.side.label()} ${instrument.formatQuantity(p.lotsHundredths)}  #${p.id.takeLast(5)}", LineTone.POSITION,
                dashed = false, alpha = if (p.id == selectedPosition?.id) .85f else .3f))
        }
        selectedPosition?.let { p ->
            listOf(true to p.stopLoss, false to p.takeProfit).forEach { (stop, base) ->
                val dragged = (moving as? EditableLine.Protection)?.takeIf { it.position.id == p.id && it.stop == stop } != null
                val price = (if (dragged) drag?.price else base) ?: return@forEach
                val pnl = estimatedExitPnl(instrument, p, price)
                add(OrderLine(price, protectionLabel(stop, pnl, !p.feesKnown) + state(dragged, p.protectionEditable), if (stop) LineTone.STOP else LineTone.TARGET,
                    edit = if (p.protectionEditable && base != null) EditableLine.Protection(p, stop, base) else null, pnl = pnl, gross = !p.feesKnown, moving = dragged))
            }
        }
    }
    if (options.showOrders) {
        val represented = (if (options.showPositions) setOfNotNull(selectedPosition?.stopLossOrderId, selectedPosition?.takeProfitOrderId) else emptySet()) +
            orders.filter { it.role == OrderRole.ENTRY }.flatMap { entry ->
                listOfNotNull(entry.stopLossOrderId.takeIf { entry.request.stopLoss != null }, entry.takeProfitOrderId.takeIf { entry.request.takeProfit != null })
            }
        orders.filterNot { it.id in represented }.forEach { order ->
            val request = order.request
            val base = request.entryPrice ?: return@forEach
            val position = positions.firstOrNull { it.id == order.positionId }
            val edit = when {
                !options.editPending || !order.editable -> null
                order.role == OrderRole.ENTRY -> EditableLine.Pending(order)
                order.isProtection && position?.protectionEditable == true -> EditableLine.Protection(position, order.role == OrderRole.STOP_LOSS, base)
                else -> null
            }
            val dragged = when (moving) {
                is EditableLine.Pending -> moving.order.id == order.id
                is EditableLine.Protection -> order.id == if (moving.stop) moving.position.stopLossOrderId else moving.position.takeProfitOrderId
                else -> false
            }
            val price = if (dragged) drag!!.price else base
            // A protection order bound to a position is measured from that position's entry.
            val bound = position?.takeIf { order.isProtection }
            val pnl = bound?.let { estimatedExitPnl(instrument, it, price) }
            val result = bound?.let { " · ${resultLabel(pnl, !it.feesKnown)}" } ?: ""
            val action = when (order.role) { OrderRole.STOP_LOSS -> "拖动止损"; OrderRole.TAKE_PROFIT -> "拖动止盈"; else -> "拖动入场" }
            add(OrderLine(price, "${order.role.label()} ${request.side.label()} ${request.kind.name}  #${order.id.substringAfterLast(':')}$result${state(dragged, edit != null, action)}",
                when (order.role) { OrderRole.STOP_LOSS -> LineTone.STOP; OrderRole.TAKE_PROFIT -> LineTone.TARGET; else -> LineTone.ORDER },
                edit = edit, pnl = pnl, gross = bound?.feesKnown == false, moving = dragged))
            if (order.role != OrderRole.ENTRY) return@forEach
            // The entry's own stop loss and take profit, measured from the entry (or from where its line is being dragged).
            val entry = if (dragged) price else base
            listOf(true to request.stopLoss, false to request.takeProfit).forEach { (stop, bracket) ->
                bracket ?: return@forEach
                val draggedBracket = (moving as? EditableLine.PendingProtection)?.takeIf { it.order.id == order.id && it.stop == stop } != null
                val at = if (draggedBracket) drag!!.price else bracket
                val editable = options.editPendingProtection && order.editable && order.protectionEditable
                val bracketPnl = estimatedExitPnl(instrument, request.side, request.lotsHundredths, entry, at)
                add(OrderLine(at, "挂单 #${order.id.substringAfterLast(':')} ${protectionLabel(stop, bracketPnl, !instrument.estimatesIncludeFees)}${state(draggedBracket, editable)}",
                    if (stop) LineTone.STOP else LineTone.TARGET, alpha = .8f, edit = if (editable) EditableLine.PendingProtection(order, stop, bracket) else null,
                    pnl = bracketPnl, gross = !instrument.estimatesIncludeFees, moving = draggedBracket))
            }
        }
    }
    if (draft != null && draftEntry != null && draftEntry > 0) {
        val pending = draft.kind != OrderKind.MARKET && draft.entryPrice != null
        add(OrderLine(draftEntry, "${draft.side.label()} ${draft.kind.name} 草稿 · ${instrument.formatQuantity(draft.lotsHundredths)} ${instrument.quantityUnit}${if (pending) " · 拖动入场" else ""}",
            LineTone.ORDER, edit = if (pending) EditableLine.Draft(draft) else null, moving = moving is EditableLine.Draft))
        listOf(true to draft.stopLoss, false to draft.takeProfit).forEach { (stop, price) ->
            price ?: return@forEach
            val dragged = (moving as? EditableLine.DraftProtection)?.stop == stop
            // The broker's estimate when it has one; otherwise the same arithmetic, so an invalid draft still shows its result.
            val pnl = (if (stop) draftPreview?.stopLossPnlMicros else draftPreview?.takeProfitPnlMicros)
                ?: estimatedExitPnl(instrument, draft.side, draft.lotsHundredths, draftEntry, price)
            add(OrderLine(price, "草稿 ${protectionLabel(stop, pnl, !instrument.estimatesIncludeFees)}${state(dragged, true)}", if (stop) LineTone.STOP else LineTone.TARGET,
                alpha = .65f, edit = EditableLine.DraftProtection(draft, stop, price), pnl = pnl, gross = !instrument.estimatesIncludeFees, moving = dragged))
        }
    }
}

/** Which layers the chart shows and which pending-order lines the broker lets the user drag. */
internal data class OrderLineOptions(
    val showPositions: Boolean,
    val showOrders: Boolean,
    val editPending: Boolean,
    val editPendingProtection: Boolean,
)

/**
 * What dragging an order line does. A drag only reports where the line was dropped: the draft takes the price at once,
 * while a pending order or position changes on the broker only after the screen's confirmation. The estimates feed the
 * readout during a drag. Grouped in one parameter for the same reason as [ChartChrome].
 */
class ChartOrderActions(
    val estimateDraft: (OrderRequest) -> OrderPreview,
    val onDraftEntry: (Long) -> Unit,
    val onDraftProtection: (stop: Boolean, price: Long) -> Unit,
    val editPending: Boolean,
    val estimatePending: (orderId: String, entryPrice: Long) -> OrderPreview,
    val onPendingEntry: (PendingOrder, Long) -> Unit,
    val editPendingProtection: Boolean,
    val estimatePendingProtection: (orderId: String, stopLoss: Long?, takeProfit: Long?) -> OrderPreview,
    val onPendingProtection: (PendingOrder, Long?, Long?) -> Unit,
    val onProtection: (Position, Long?, Long?) -> Unit,
)
