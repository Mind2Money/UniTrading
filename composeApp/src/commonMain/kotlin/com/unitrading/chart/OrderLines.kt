package com.unitrading.chart

import androidx.compose.ui.input.key.Key
import com.unitrading.app.TradingKeys
import com.unitrading.broker.*
import com.unitrading.ui.estimatedExitPnl
import com.unitrading.ui.label
import com.unitrading.ui.shortId

/** An order line the pointer can pick up; [price] is where it was when the drag started. */
internal sealed interface EditableLine {
    val price: Long
    data class Protection(val position: Position, val stop: Boolean, override val price: Long) : EditableLine
    data class Pending(val order: PendingOrder) : EditableLine { override val price get() = order.request.entryPrice!! }
    data class PendingProtection(val order: PendingOrder, val stop: Boolean, override val price: Long) : EditableLine
    data class Draft(val request: OrderRequest) : EditableLine { override val price get() = request.entryPrice!! }
    data class DraftProtection(val request: OrderRequest, val stop: Boolean, override val price: Long) : EditableLine
    /** A stop or target that changes on its own, by its id: see [PendingOrder.standaloneEditable]. */
    data class Standalone(val order: PendingOrder) : EditableLine { override val price get() = order.request.entryPrice!! }
}

/** A line being dragged, with the price scale frozen at the start so the line follows the pointer exactly. */
internal data class LineDrag(val line: EditableLine, val scale: PriceScale, val price: Long)

/** What a button on an order line does. The screen sends each one through the same checks and confirmation as the trade list. */
sealed interface LineAction {
    data class ClosePosition(val position: Position) : LineAction
    data class CancelOrder(val order: PendingOrder) : LineAction
    data class RemoveProtection(val position: Position, val stop: Boolean) : LineAction
    data class RemovePendingProtection(val order: PendingOrder, val stop: Boolean) : LineAction
    data class RemoveDraftProtection(val stop: Boolean) : LineAction
    data object CloseDraft : LineAction
}

internal enum class LineTone { BUY, SELL, STOP, TARGET, NEUTRAL }

/** The boxes of a line's tag, left to right. Cells of one [group] sit joined; a gap separates the groups. */
internal enum class CellKind(val group: Int) { TITLE(0), RESULT(0), ADD_TARGET(1), ADD_STOP(2), VALUE(3), CLOSE(3) }

/**
 * One box of a line's tag. [hint] names what it does, for the tooltip and, with an [action], the context menu and screen
 * readers. A [drag] cell does nothing when pressed: dragging from it pulls out that new line.
 */
internal data class LineCell(val kind: CellKind, val text: String = "", val action: LineAction? = null, val hint: String = "", val drag: EditableLine? = null)

/**
 * One horizontal line on the plot and its tag. [edit] is set when dragging it does something, [pnl] is the estimated
 * result of a stop loss or take profit at [price], or the position's floating result at the current quote ([gross] when
 * fees are not in it), [moving] marks the line under the pointer and [preview] one that is not at the broker yet.
 */
internal data class OrderLine(
    val price: Long,
    val cells: List<LineCell>,
    val tone: LineTone,
    val dashed: Boolean = true,
    val alpha: Float = 1f,
    val edit: EditableLine? = null,
    val pnl: Long? = null,
    val gross: Boolean = false,
    val moving: Boolean = false,
    val preview: Boolean = false,
)

internal fun OrderRequest.withProtection(stop: Boolean, price: Long) = if (stop) copy(stopLoss = price) else copy(takeProfit = price)

/** "毛 -12.50 USD": the estimate is gross when fees are not counted in it. */
internal fun resultLabel(pnl: Long?, gross: Boolean) = "${if (gross) "毛 " else ""}${pnl?.let { money(it, true) } ?: "—"} USD"

/** The legend's live line during a drag: an entry shows the whole estimate, a stop loss or take profit its own result. */
internal fun dragReadout(instrument: Instrument, drag: LineDrag, estimate: OrderPreview?, line: OrderLine?, sizedLots: Long? = null): String {
    val price = instrument.formatPrice(drag.price)
    val errors = estimate?.errors.orEmpty().joinToString("；")
    if (drag.line is EditableLine.Standalone) return "单独改价预览 $price   ${line?.pnl?.let { resultLabel(it, line.gross) } ?: ""}   $errors".replace(Regex(" {4,}"), "   ").trimEnd()
    val stop = when (val edit = drag.line) {
        is EditableLine.Protection -> edit.stop
        is EditableLine.PendingProtection -> edit.stop
        is EditableLine.DraftProtection -> edit.stop
        is EditableLine.Pending, is EditableLine.Draft, is EditableLine.Standalone -> null
    }
    if (stop != null) {
        val quantity = sizedLots?.let {
            "   自动手数 ${if (it > 0) "${instrument.formatQuantity(it)} ${instrument.quantityUnit}" else "—"}"
        }.orEmpty()
        return "${if (stop) "止损" else "止盈"}预览 $price$quantity   ${resultLabel(line?.pnl, line?.gross == true)}   $errors".trimEnd()
    }
    val e = estimate ?: return "入场预览 $price"
    return "入场预览 $price   SL ${e.stopLossPnlMicros?.let { money(it, true) } ?: "—"}   TP ${e.takeProfitPnlMicros?.let { money(it, true) } ?: "—"}   " +
        "保证金 ${if (e.marginKnown) money(e.marginMicros) + " USD" else "未知"}   $errors"
}

private fun sideTone(side: Side) = if (side == Side.BUY) LineTone.BUY else LineTone.SELL
private fun protectionName(stop: Boolean) = if (stop) "止损" else "止盈"
private fun protectionTitle(stop: Boolean) = LineCell(CellKind.TITLE, if (stop) "SL" else "TP")
private fun protectionTone(stop: Boolean) = if (stop) LineTone.STOP else LineTone.TARGET
private fun addButton(stop: Boolean, drag: EditableLine) =
    LineCell(if (stop) CellKind.ADD_STOP else CellKind.ADD_TARGET, if (stop) "SL" else "TP", hint = "拖动设置${protectionName(stop)}", drag = drag)

/** The target and stop buttons of an entry or position, in that order; each is offered until its protection exists. */
private fun addButtons(target: EditableLine?, stop: EditableLine?) = listOfNotNull(target?.let { addButton(false, it) }, stop?.let { addButton(true, it) })

/** Cells of a stop loss or take profit line: what it would earn or lose, its size, and the button that takes it away. */
private fun protectionCells(instrument: Instrument, stop: Boolean, pnl: Long?, gross: Boolean, quantity: Long, removal: LineAction?, hint: String) = listOfNotNull(
    protectionTitle(stop),
    LineCell(CellKind.RESULT, resultLabel(pnl, gross)),
    LineCell(CellKind.VALUE, if (quantity > 0) instrument.formatQuantity(quantity) else "—"),
    removal?.let { LineCell(CellKind.CLOSE, action = it, hint = hint) },
)

private fun orderTitle(order: PendingOrder) = when (order.role) {
    OrderRole.ENTRY -> LineCell(CellKind.TITLE, order.request.side.label())
    OrderRole.STOP_LOSS -> protectionTitle(true)
    OrderRole.TAKE_PROFIT -> protectionTitle(false)
    OrderRole.UNRESOLVED -> LineCell(CellKind.TITLE, "待确认")
}

private fun cancelHint(instrument: Instrument, order: PendingOrder) =
    if (order.isProtection) "撤销${protectionName(order.role == OrderRole.STOP_LOSS)} #${shortId(order.id)}"
    else "撤单 ${order.request.side.label()}${order.request.kind.label()} ${instrument.formatQuantity(order.request.lotsHundredths)} ${instrument.quantityUnit} #${shortId(order.id)}"

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
    quote: Quote? = null,
): List<OrderLine> = buildList {
    val moving = drag?.line
    if (options.showPositions) {
        positions.forEach { p ->
            val pnl = quote?.let { positionPnl(instrument, p, it) }
            val canAdd = options.addPositionProtection && p.protectionEditable
            add(OrderLine(p.entryPrice, listOf(LineCell(CellKind.TITLE, p.side.label()), LineCell(CellKind.RESULT, resultLabel(pnl, true))) +
                addButtons(EditableLine.Protection(p, false, p.entryPrice).takeIf { canAdd && p.takeProfit == null }, EditableLine.Protection(p, true, p.entryPrice).takeIf { canAdd && p.stopLoss == null }) +
                LineCell(CellKind.VALUE, instrument.formatQuantity(p.lotsHundredths)) +
                LineCell(CellKind.CLOSE, action = LineAction.ClosePosition(p), hint = "平仓 ${p.side.label()} ${instrument.formatQuantity(p.lotsHundredths)} ${instrument.quantityUnit} #${shortId(p.id)}"),
                sideTone(p.side), dashed = false, alpha = if (p.id == selectedPosition?.id) .85f else .3f, pnl = pnl, gross = true))
        }
        // A position that is not selected draws only the new protection being dragged out of its tag.
        val dragging = (moving as? EditableLine.Protection)?.position?.id
        positions.filter { it.id == selectedPosition?.id || it.id == dragging }.forEach { p ->
            listOf(true to p.stopLoss, false to p.takeProfit).forEach { (stop, base) ->
                if (p.id != selectedPosition?.id && base != null) return@forEach
                val dragged = (moving as? EditableLine.Protection)?.takeIf { it.position.id == p.id && it.stop == stop } != null
                val price = (if (dragged) drag?.price else base) ?: return@forEach
                val pnl = estimatedExitPnl(instrument, p, price)
                val order = orders.firstOrNull { it.id == if (stop) p.stopLossOrderId else p.takeProfitOrderId }
                val removal = order?.let(LineAction::CancelOrder) ?: LineAction.RemoveProtection(p, stop).takeIf { options.removeProtection && p.protectionEditable }
                add(OrderLine(price, protectionCells(instrument, stop, pnl, !p.feesKnown, p.lotsHundredths, removal, order?.let { cancelHint(instrument, it) } ?: "移除${protectionName(stop)}"),
                    protectionTone(stop), edit = if (p.protectionEditable && base != null) EditableLine.Protection(p, stop, base) else null, pnl = pnl, gross = !p.feesKnown, moving = dragged))
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
                // The broker marks an order that changes on its own only where no other route moves it.
                order.standaloneEditable && request.kind != OrderKind.MARKET -> EditableLine.Standalone(order)
                !options.editPending || !order.editable -> null
                order.role == OrderRole.ENTRY -> EditableLine.Pending(order)
                order.isProtection && position?.protectionEditable == true -> EditableLine.Protection(position, order.role == OrderRole.STOP_LOSS, base)
                else -> null
            }
            val dragged = when (moving) {
                is EditableLine.Standalone -> moving.order.id == order.id
                is EditableLine.Pending -> moving.order.id == order.id
                is EditableLine.Protection -> order.id == if (moving.stop) moving.position.stopLossOrderId else moving.position.takeProfitOrderId
                else -> false
            }
            val price = if (dragged) drag!!.price else base
            // A protection order bound to a position is measured from that position's entry.
            val bound = position?.takeIf { order.isProtection }
            val pnl = bound?.let { estimatedExitPnl(instrument, it, request.lotsHundredths, price) }
            val bracketsOpen = order.role == OrderRole.ENTRY && options.editPendingProtection && options.addProtection && order.editable && order.protectionEditable
            add(OrderLine(price, buildList {
                add(orderTitle(order))
                if (bound != null) add(LineCell(CellKind.RESULT, resultLabel(pnl, !bound.feesKnown)))
                addAll(addButtons(EditableLine.PendingProtection(order, false, base).takeIf { bracketsOpen && request.takeProfit == null },
                    EditableLine.PendingProtection(order, true, base).takeIf { bracketsOpen && request.stopLoss == null }))
                add(LineCell(CellKind.VALUE, instrument.formatQuantity(request.lotsHundredths)))
                if (bound == null) add(LineCell(CellKind.VALUE, request.kind.label()))
                add(LineCell(CellKind.CLOSE, action = LineAction.CancelOrder(order), hint = cancelHint(instrument, order)))
            }, when (order.role) { OrderRole.STOP_LOSS -> LineTone.STOP; OrderRole.TAKE_PROFIT -> LineTone.TARGET; OrderRole.ENTRY -> sideTone(request.side); OrderRole.UNRESOLVED -> LineTone.NEUTRAL },
                edit = edit, pnl = pnl, gross = bound?.feesKnown == false, moving = dragged))
            if (order.role != OrderRole.ENTRY) return@forEach
            // The entry's own stop loss and take profit, measured from the entry (or from where its line is being dragged).
            val entry = if (dragged) price else base
            listOf(true to request.stopLoss, false to request.takeProfit).forEach { (stop, bracket) ->
                val draggedBracket = (moving as? EditableLine.PendingProtection)?.takeIf { it.order.id == order.id && it.stop == stop } != null
                val at = (if (draggedBracket) drag!!.price else bracket) ?: return@forEach
                val editable = options.editPendingProtection && order.editable && order.protectionEditable
                val bracketPnl = estimatedExitPnl(instrument, request.side, request.lotsHundredths, entry, at)
                val bracketOrder = orders.firstOrNull { it.id == if (stop) order.stopLossOrderId else order.takeProfitOrderId }
                val removal = bracketOrder?.let(LineAction::CancelOrder) ?: LineAction.RemovePendingProtection(order, stop).takeIf { options.removeProtection && editable }
                add(OrderLine(at, protectionCells(instrument, stop, bracketPnl, !instrument.estimatesIncludeFees, request.lotsHundredths, removal,
                    bracketOrder?.let { cancelHint(instrument, it) } ?: "移除${protectionName(stop)}"),
                    protectionTone(stop), alpha = .8f, edit = if (editable && bracket != null) EditableLine.PendingProtection(order, stop, bracket) else null,
                    pnl = bracketPnl, gross = !instrument.estimatesIncludeFees, moving = draggedBracket))
            }
        }
    }
    if (draft != null && draftEntry != null && draftEntry > 0) {
        val pending = draft.kind != OrderKind.MARKET && draft.entryPrice != null
        add(OrderLine(draftEntry, listOf(LineCell(CellKind.TITLE, draft.side.label())) +
            addButtons(EditableLine.DraftProtection(draft, false, draftEntry).takeIf { draft.takeProfit == null && !options.draftTargetManaged },
                EditableLine.DraftProtection(draft, true, draftEntry).takeIf { draft.stopLoss == null && !options.draftStopManaged }) +
            LineCell(CellKind.VALUE, if (draft.lotsHundredths > 0) instrument.formatQuantity(draft.lotsHundredths) else "—") + LineCell(CellKind.VALUE, draft.kind.label()) +
            LineCell(CellKind.CLOSE, action = LineAction.CloseDraft, hint = "关闭下单预览"),
            sideTone(draft.side), edit = if (pending) EditableLine.Draft(draft) else null, moving = moving is EditableLine.Draft, preview = true))
        listOf(true to draft.stopLoss, false to draft.takeProfit).forEach { (stop, price) ->
            price ?: return@forEach
            val dragged = (moving as? EditableLine.DraftProtection)?.stop == stop
            // The broker's estimate when it has one; otherwise the same arithmetic, so an invalid draft still shows its result.
            val pnl = (if (stop) draftPreview?.stopLossPnlMicros else draftPreview?.takeProfitPnlMicros)
                ?: estimatedExitPnl(instrument, draft.side, draft.lotsHundredths, draftEntry, price)
            val managed = if (stop) options.draftStopManaged else options.draftTargetManaged
            add(OrderLine(price, protectionCells(instrument, stop, pnl, !instrument.estimatesIncludeFees, draft.lotsHundredths,
                LineAction.RemoveDraftProtection(stop).takeUnless { managed }, "取消${protectionName(stop)}"),
                protectionTone(stop), alpha = .65f, edit = EditableLine.DraftProtection(draft, stop, price).takeUnless { managed },
                pnl = pnl, gross = !instrument.estimatesIncludeFees, moving = dragged, preview = true))
        }
    }
}

/** Which layers the chart shows, which pending-order lines the broker lets the user drag, and which protections its buttons can add or take away. */
internal data class OrderLineOptions(
    val showPositions: Boolean,
    val showOrders: Boolean,
    val editPending: Boolean,
    val editPendingProtection: Boolean,
    val addProtection: Boolean = false,
    val removeProtection: Boolean = false,
    /** An open position's missing stop or target can be dragged out of its tag; see [BrokerCapabilities.addPositionProtection]. */
    val addPositionProtection: Boolean = addProtection,
    val draftStopManaged: Boolean = false,
    val draftTargetManaged: Boolean = false,
)

/**
 * What dragging an order line or pressing one of its buttons does. A drag only reports where the line was dropped: the
 * draft takes the price at once, while a pending order or position changes on the broker only after the screen's
 * confirmation. The estimates feed the readout during a drag. Grouped in one parameter for the same reason as [ChartChrome].
 *
 * The plot's context menu offers a market buy and sell and the limit and stop entries of [entryKinds] at the clicked
 * price, from a right click or a long press. [entryLots] is the quantity
 * they would carry, the order panel's, or null while that does not parse; [onEntryOrder] hands the pick to the screen,
 * which sends it through the order panel's checks and confirmation. No [entryKinds], no orders in the menu.
 *
 * [tradingKeys] are the keys that buy and sell at the market while the chart has the keyboard focus, or null while
 * keyboard trading is off. [onMarketOrder] hands the side to the screen, which builds the order as for the menu;
 * [instantOrders] tells the legend's hint whether a key sends its order without a confirmation.
 */
class ChartOrderActions(
    val estimateDraft: (OrderRequest) -> Pair<OrderRequest, OrderPreview>,
    val onDraftEntry: (Long) -> Unit,
    val onDraftProtection: (stop: Boolean, price: Long) -> Unit,
    val editPending: Boolean,
    val estimatePending: (orderId: String, entryPrice: Long) -> OrderPreview,
    val onPendingEntry: (PendingOrder, Long) -> Unit,
    val editPendingProtection: Boolean,
    val estimatePendingProtection: (orderId: String, stopLoss: Long?, takeProfit: Long?) -> OrderPreview,
    val onPendingProtection: (PendingOrder, Long?, Long?) -> Unit,
    val onProtection: (Position, Long?, Long?) -> Unit,
    val onLineAction: (LineAction) -> Unit = {},
    val addProtection: Boolean = false,
    val removeProtection: Boolean = false,
    val addPositionProtection: Boolean = addProtection,
    val entryKinds: Set<OrderKind> = emptySet(),
    val entryLots: Long? = null,
    /** The menu's pick; [price] is null for a market order. */
    val onEntryOrder: (side: Side, kind: OrderKind, price: Long?) -> Unit = { _, _, _ -> },
    val tradingKeys: TradingKeys? = null,
    val instantOrders: Boolean = false,
    val onMarketOrder: (Side) -> Unit = {},
    /** The broker's check of an order changed on its own to a price, its quantity kept; see [PendingOrder.standaloneEditable]. */
    val estimateStandalone: (PendingOrder, Long) -> OrderPreview = { _, _ -> OrderPreview(null) },
    val onStandalone: (PendingOrder, Long) -> Unit = { _, _ -> },
    val draftStopManaged: Boolean = false,
    val draftTargetManaged: Boolean = false,
    /** The account sizes its orders from a stop: the menu and the keys open the order panel on their side and send nothing. */
    val stopRequiredForSizing: Boolean = false,
    /** What the account's cash amounts add to an order that opens or adds, as the menu and the legend name it: "SL/TP", "SL" or "TP". */
    val attachedProtection: String? = null,
)

/** The side [key] trades under these trading keys; null for every other key, and for any key held with a modifier. */
internal fun TradingKeys.side(key: Key, modified: Boolean): Side? = if (modified) null else when (this) {
    TradingKeys.LETTERS -> when (key) { Key.W -> Side.BUY; Key.S -> Side.SELL; else -> null }
    TradingKeys.ARROWS -> when (key) { Key.DirectionUp -> Side.BUY; Key.DirectionDown -> Side.SELL; else -> null }
}

/**
 * What the legend says about the trading keys. With the chart focused the keys are live, so it names what each does, the
 * quantity an order would carry ([lots], null while the order panel holds none that parses) and whether it still asks.
 * Otherwise it says how to make them live. On an account that sizes its orders from a stop ([sizing]) a key trades
 * nothing: it opens the order panel on its side. [attached] is the protection the account's cash amounts add, if any.
 */
internal fun tradingKeysHint(keys: TradingKeys, live: Boolean, instrument: Instrument, lots: Long?, instant: Boolean,
    sizing: Boolean = false, attached: String? = null): String = when {
    !live -> "键盘下单：点一下图表后 ${keys.buy} / ${keys.sell} 生效"
    sizing -> "${keys.buy} 买入 · ${keys.sell} 卖出 · 打开下单栏填写 SL，不直接下单"
    else -> "${keys.buy} 市价买入 · ${keys.sell} 市价卖出 · " + (lots?.let { "${instrument.formatQuantity(it)} ${instrument.quantityUnit}" } ?: "数量无效") +
        (attached?.let { " · 开仓自动附加 $it" } ?: "") + if (instant) " · 按键即成交" else " · 按键后确认"
}
