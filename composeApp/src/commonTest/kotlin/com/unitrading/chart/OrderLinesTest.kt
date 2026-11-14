package com.unitrading.chart

import com.unitrading.broker.*
import kotlin.test.*

class OrderLinesTest {
    private val mes = Instrument("MESZ6", "Micro E-mini S&P", "期货", 6, 250_000, 1_250_000, minLotsHundredths = 100, lotStepHundredths = 100,
        quantityUnit = "张", quantityDecimals = 0, displayPriceDecimals = 2, estimatesIncludeFees = false)
    private val fx = Instrument("EURUSD", "EUR/USD", "外汇", 5, tickValueMicrosPerLot = 1_000_000, commissionMicrosPerLot = 3_000_000)
    private val all = OrderLineOptions(showPositions = true, showOrders = true, editPending = true, editPendingProtection = true, addProtection = true, removeProtection = true)
    private val scale = PriceScale(5_480_000_000.0, 5_520_000_000.0, 0f, 100f)

    /** A server-style pending entry whose stop and target live in separate bracket orders 702 and 701. */
    private val entry = PendingOrder("700", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, 5_500_000_000, 5_490_000_000, 5_510_000_000), 0,
        stopLossOrderId = "702", takeProfitOrderId = "701")
    private val brackets = listOf(
        PendingOrder("701", OrderRequest("MESZ6", Side.SELL, OrderKind.LIMIT, 100, 5_510_000_000), 0, role = OrderRole.TAKE_PROFIT, editable = false),
        PendingOrder("702", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 5_490_000_000), 0, role = OrderRole.STOP_LOSS, editable = false),
    )
    private val bare = Position("100:200", "MESZ6", Side.BUY, 100, 5_500_000_000, 0, marginMicros = 0, feesKnown = false)

    private fun pending(orders: List<PendingOrder>, drag: LineDrag? = null, options: OrderLineOptions = all) =
        orderLines(mes, emptyList(), null, orders, null, null, null, drag, options)
    private fun OrderLine.texts() = cells.filter { it.kind != CellKind.CLOSE }.map { it.text }
    private fun OrderLine.closeAction() = cells.singleOrNull { it.kind == CellKind.CLOSE }?.action
    private fun OrderLine.closeHint() = cells.single { it.kind == CellKind.CLOSE }.hint

    @Test fun draftStopAndTargetAreDraggableAndPricedEvenWhileTheEstimateIsInvalid() {
        val draft = OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 100, 108_500, 108_300, 108_900)
        val lines = orderLines(fx, emptyList(), null, emptyList(), draft, OrderPreview(null, errors = listOf("止损方向或最小距离无效")), 108_500, null, all)
        val stop = lines.single { it.edit is EditableLine.DraftProtection && (it.edit as EditableLine.DraftProtection).stop }
        val target = lines.single { it.edit is EditableLine.DraftProtection && !(it.edit as EditableLine.DraftProtection).stop }
        // 200 ticks at $1 a tick, less a $3 commission each way.
        assertEquals(-206_000_000, stop.pnl)
        assertEquals(listOf("SL", "-206.00 USD", "1.00"), stop.texts())
        assertEquals(LineAction.RemoveDraftProtection(true), stop.closeAction())
        assertEquals(394_000_000, target.pnl)
        assertTrue(lines.any { it.edit is EditableLine.Draft && it.price == 108_500L })
    }

    @Test fun aValidDraftEstimateIsShownAsTheBrokerGaveIt() {
        val draft = OrderRequest("MESZ6", Side.SELL, OrderKind.MARKET, 200, stopLoss = 5_510_000_000)
        val preview = OrderPreview(5_500_000_000, stopLossPnlMicros = -123_450_000)
        val lines = orderLines(mes, emptyList(), null, emptyList(), draft, preview, 5_500_000_000, null, all)
        assertEquals(listOf("SL", "毛 -123.45 USD", "2"), lines.single { it.edit is EditableLine.DraftProtection }.texts())
        val line = lines.single { it.price == 5_500_000_000L }
        assertNull(line.edit, "A market draft has no entry price to drag")
        assertEquals(listOf("卖出", "TP", "2", "市价"), line.texts(), "The tag reads as on TradingView, offering only the protection it lacks")
        assertEquals(LineAction.CloseDraft, line.closeAction())
        val target = line.cells.single { it.kind == CellKind.ADD_TARGET }
        assertEquals(EditableLine.DraftProtection(draft, false, 5_500_000_000), target.drag, "The button pulls out a line that starts at the entry")
        assertNull(target.action, "Pressing it alone adds nothing")
        assertTrue(line.preview, "A draft is not at the broker yet")
    }

    @Test fun pendingEntryBracketsShowTheirResultFromTheEntryAndReplaceTheBracketOrdersLines() {
        val lines = pending(listOf(entry) + brackets)
        assertEquals(3, lines.size, "Bracket orders are drawn once, as the entry's own lines")
        val stop = lines.single { it.price == 5_490_000_000L }
        val target = lines.single { it.price == 5_510_000_000L }
        assertEquals(listOf("SL", "毛 -50.00 USD", "1"), stop.texts())
        assertEquals(listOf("TP", "毛 +50.00 USD", "1"), target.texts())
        assertEquals(EditableLine.PendingProtection(entry, true, 5_490_000_000), stop.edit)
        assertEquals(LineAction.CancelOrder(brackets[1]), stop.closeAction(), "The stop is its own order at the broker")
        assertEquals("撤销止损 #702", stop.closeHint())
        assertEquals(EditableLine.Pending(entry), lines.single { it.price == 5_500_000_000L }.edit)
    }

    @Test fun lockedBracketsStayVisibleWithTheirResultButCannotBeDragged() {
        for ((orders, options) in listOf(listOf(entry.copy(protectionEditable = false)) to all, listOf(entry.copy(editable = false)) to all,
            listOf(entry) to all.copy(editPendingProtection = false))) {
            val stop = pending(orders, options = options).single { it.price == 5_490_000_000L }
            assertNull(stop.edit)
            assertEquals(listOf("SL", "毛 -50.00 USD", "1"), stop.texts())
        }
    }

    @Test fun aBracketWithoutItsPriceOnTheEntryKeepsItsOwnLine() {
        val lines = pending(listOf(entry.copy(request = entry.request.copy(stopLoss = null))) + brackets)
        val own = lines.single { it.tone == LineTone.STOP }
        assertEquals(listOf("SL", "1", "突破"), own.texts())
        assertEquals("撤销止损 #702", own.closeHint())
        assertTrue(lines.single { it.tone == LineTone.TARGET }.edit is EditableLine.PendingProtection, "The target is still drawn once, as the entry's own line")
    }

    @Test fun draggedBracketFollowsThePointerWithALiveResult() {
        val drag = LineDrag(EditableLine.PendingProtection(entry, true, 5_490_000_000), scale, 5_495_000_000)
        val lines = pending(listOf(entry) + brackets, drag)
        val moving = lines.single { it.moving }
        assertEquals(5_495_000_000, moving.price)
        assertEquals(-25_000_000, moving.pnl)
        assertEquals("毛 -25.00 USD", moving.cells.single { it.kind == CellKind.RESULT }.text)
        assertEquals("止损预览 5495.00   毛 -25.00 USD", dragReadout(mes, drag, OrderPreview(5_500_000_000), moving))
        assertEquals("止损预览 5495.00   毛 -25.00 USD   止损方向或最小距离无效",
            dragReadout(mes, drag, OrderPreview(null, errors = listOf("止损方向或最小距离无效")), moving))
    }

    @Test fun movingThePendingEntryMovesItsBracketResults() {
        val lines = pending(listOf(entry), LineDrag(EditableLine.Pending(entry), scale, 5_502_000_000))
        assertEquals(5_502_000_000, lines.single { it.moving }.price)
        // 12 points below the new entry: 48 ticks at $1.25.
        assertEquals(-60_000_000, lines.single { it.price == 5_490_000_000L }.pnl)
    }

    @Test fun positionProtectionAndItsOrdersCarryTheResult() {
        val position = bare.copy(stopLoss = 5_490_000_000, takeProfit = 5_510_000_000, stopLossOrderId = "702", takeProfitOrderId = "701")
        val bound = brackets.map { it.copy(positionId = position.id, editable = true) }
        val shown = orderLines(mes, listOf(position), position, bound, null, null, null, null, all)
        val line = shown.single { it.price == 5_490_000_000L }
        assertEquals(listOf("SL", "毛 -50.00 USD", "1"), line.texts())
        assertEquals(EditableLine.Protection(position, true, 5_490_000_000), line.edit)
        assertEquals(3, shown.size, "The position's stop and target each appear once")
        assertEquals(LineAction.CancelOrder(bound[1]), line.closeAction())
        // With the positions layer off the same stop is drawn from its order, still priced from the position's entry.
        val ordersOnly = orderLines(mes, listOf(position), position, bound, null, null, null, null, all.copy(showPositions = false))
        val stop = ordersOnly.single { it.price == 5_490_000_000L }
        assertEquals(listOf("SL", "毛 -50.00 USD", "1"), stop.texts())
        assertEquals(EditableLine.Protection(position, true, 5_490_000_000), stop.edit)
    }

    /** Rithmic's different-family legs: the position cannot move them as a pair, but each order can still move. */
    @Test fun aLockedPositionsStandaloneProtectionStaysDraggableWithoutDuplicateLines() {
        val position = bare.copy(stopLoss = 5_490_000_000, takeProfit = 5_510_000_000, stopLossOrderId = "702", takeProfitOrderId = "701", protectionEditable = false)
        val bound = brackets.map { it.copy(positionId = position.id, standaloneEditable = true) }
        for (options in listOf(all, all.copy(showOrders = false), all.copy(showPositions = false))) {
            val shown = orderLines(mes, listOf(position), position, bound, null, null, null, null, options)
            val protection = shown.filter { it.tone == LineTone.STOP || it.tone == LineTone.TARGET }
            assertEquals(2, protection.size, "Each broker order is drawn once, whichever layer represents it")
            assertEquals(bound.map { EditableLine.Standalone(it) }.toSet(), protection.map { it.edit }.toSet())
            assertEquals(setOf(LineAction.CancelOrder(bound[0]), LineAction.CancelOrder(bound[1])), protection.map { it.closeAction() }.toSet())
            assertEquals(listOf("SL", "毛 -50.00 USD", "1"), protection.single { it.tone == LineTone.STOP }.texts())
            assertEquals(listOf("TP", "毛 +50.00 USD", "1"), protection.single { it.tone == LineTone.TARGET }.texts())
            assertTrue(shown.flatMap { it.cells }.none { it.kind == CellKind.ADD_STOP || it.kind == CellKind.ADD_TARGET }, "A locked position does not gain protection-creation buttons")
        }
    }

    @Test fun draggingARepresentedStandaloneLegMovesOnlyItsLineAndEstimate() {
        val position = bare.copy(stopLoss = 5_490_000_000, takeProfit = 5_510_000_000, stopLossOrderId = "702", takeProfitOrderId = "701", protectionEditable = false)
        val bound = brackets.map { it.copy(positionId = position.id, standaloneEditable = true) }
        for (order in bound) {
            val edit = EditableLine.Standalone(order)
            val drag = LineDrag(edit, scale, edit.price - 1_000_000)
            val shown = orderLines(mes, listOf(position), position, bound, null, null, null, drag, all)
            assertEquals(3, shown.size, "The moving leg replaces its original line, not a second copy")
            val moving = shown.single { it.moving }
            assertEquals(edit, moving.edit)
            assertEquals(drag.price, moving.price)
            assertEquals(if (order.role == OrderRole.STOP_LOSS) -55_000_000L else 45_000_000L, moving.pnl)
            val sibling = bound.single { it.id != order.id }
            assertEquals(sibling.request.entryPrice, shown.single { it.edit == EditableLine.Standalone(sibling) }.price)
            assertEquals(position.entryPrice, shown.first().price)
            assertTrue(dragReadout(mes, drag, OrderPreview(drag.price), moving).startsWith("单独改价预览"))
        }
    }

    /** Tradovate's processing sibling, and Rithmic's stop-limit leg, must not become editable with the working leg. */
    @Test fun aLockedPositionsReadOnlyLegStaysReadOnlyBesideAStandaloneLeg() {
        val position = bare.copy(stopLoss = 5_490_000_000, takeProfit = 5_510_000_000, stopLossOrderId = "702", takeProfitOrderId = "701", protectionEditable = false)
        val bound = brackets.map { it.copy(positionId = position.id, standaloneEditable = it.role == OrderRole.TAKE_PROFIT) }
        val shown = orderLines(mes, listOf(position), position, bound, null, null, null, null, all)
        assertEquals(3, shown.size)
        val stop = shown.single { it.tone == LineTone.STOP }
        assertNull(stop.edit, "A leg the broker does not permit to change stays read-only")
        assertEquals(LineAction.CancelOrder(bound[1]), stop.closeAction())
        assertEquals(EditableLine.Standalone(bound[0]), shown.single { it.tone == LineTone.TARGET }.edit)
        val noOrders = orderLines(mes, listOf(position), position, emptyList(), null, null, null, null, all)
        assertTrue(noOrders.all { it.edit == null }, "No standalone route is invented without the broker's order")
    }

    @Test fun aUniqueTargetStaysDraggableBesideMultipleStopOrders() {
        val position = bare.copy(takeProfit = 5_510_000_000, takeProfitOrderId = "701", protectionEditable = false)
        val bound = (brackets + brackets[1].copy(id = "703", request = brackets[1].request.copy(entryPrice = 5_495_000_000)))
            .map { it.copy(positionId = position.id, standaloneEditable = true) }
        val shown = orderLines(mes, listOf(position), position, bound, null, null, null, null, all)
        assertEquals(4, shown.size, "The position, two individual stops and one represented target")
        assertEquals(bound.map { EditableLine.Standalone(it) }.toSet(), shown.mapNotNull { it.edit }.toSet())
        assertEquals(2, shown.count { it.tone == LineTone.STOP })
        assertEquals(1, shown.count { it.tone == LineTone.TARGET })
    }

    @Test fun aRepresentedStandaloneLegUsesItsOwnPriceAndQuantityInsteadOfTheWholePosition() {
        val position = bare.copy(lotsHundredths = 300, stopLoss = 5_490_000_000, stopLossOrderId = "702", protectionEditable = false)
        val stop = brackets[1].copy(positionId = position.id, standaloneEditable = true,
            request = brackets[1].request.copy(entryPrice = 5_491_000_000))
        for (options in listOf(all, all.copy(showPositions = false))) {
            val shown = orderLines(mes, listOf(position), position, listOf(stop), null, null, null, null, options)
            val line = shown.single { it.tone == LineTone.STOP }
            assertEquals(EditableLine.Standalone(stop), line.edit)
            assertEquals(5_491_000_000L, line.price)
            assertEquals(-45_000_000L, line.pnl, "The one-contract leg, not the three-contract position")
            assertEquals(listOf("SL", "毛 -45.00 USD", "1"), line.texts())
        }
    }

    @Test fun aRepresentedStandaloneLegCannotDragAMarketOrMissingPriceOrder() {
        val position = bare.copy(stopLoss = 5_490_000_000, stopLossOrderId = "702", protectionEditable = false)
        for (request in listOf(brackets[1].request.copy(kind = OrderKind.MARKET), brackets[1].request.copy(entryPrice = null))) {
            val order = brackets[1].copy(positionId = position.id, standaloneEditable = true, request = request)
            val stop = orderLines(mes, listOf(position), position, listOf(order), null, null, null, null, all).single { it.tone == LineTone.STOP }
            assertNull(stop.edit)
        }
    }

    @Test fun positionLabelsShowLiveGrossPnlUsingTheLiquidationSideOfTheQuote() {
        val buy = Position("buy", mes.symbol, Side.BUY, 200, 5_500_000_000, 0, marginMicros = 0, feesKnown = false)
        val sell = buy.copy(id = "sell", side = Side.SELL)
        fun lines(quote: Quote?) = orderLines(mes, listOf(buy, sell), buy, emptyList(), null, null, null, null, all, quote)
        val first = lines(Quote(5_501_000_000, 5_501_250_000, 0))
        assertEquals(10_000_000, first[0].pnl)
        assertEquals(-12_500_000, first[1].pnl)
        assertEquals("毛 +10.00 USD", first[0].cells.single { it.kind == CellKind.RESULT }.text)
        assertEquals("毛 -12.50 USD", first[1].cells.single { it.kind == CellKind.RESULT }.text)
        assertEquals(-10_000_000, lines(Quote(5_499_000_000, 5_499_250_000, 1))[0].pnl)
        assertNull(lines(null)[0].pnl)
        assertEquals("毛 — USD", lines(null)[0].cells.single { it.kind == CellKind.RESULT }.text)
    }

    @Test fun positionTagOffersItsCloseAndOnlyTheProtectionTheBrokerCanAdd() {
        val quote = Quote(5_501_000_000, 5_501_250_000, 0)
        fun line(position: Position, options: OrderLineOptions = all) = orderLines(mes, listOf(position), position, emptyList(), null, null, null, null, options, quote).first()
        assertEquals(listOf("买入", "毛 +5.00 USD", "TP", "SL", "1"), line(bare).texts())
        assertEquals(LineAction.ClosePosition(bare), line(bare).closeAction())
        assertEquals("平仓 买入 1 张 #200", line(bare).closeHint())
        assertEquals(EditableLine.Protection(bare, false, bare.entryPrice), line(bare).cells.single { it.kind == CellKind.ADD_TARGET }.drag)
        val halfDone = bare.copy(takeProfit = 5_510_000_000)
        assertEquals(listOf("买入", "毛 +5.00 USD", "SL", "1"), line(halfDone).texts())
        for ((position, options) in listOf(bare to all.copy(addProtection = false, addPositionProtection = false), bare.copy(protectionEditable = false) to all))
            assertEquals(listOf("买入", "毛 +5.00 USD", "1"), line(position, options).texts())
        // Tradovate and Rithmic: a position takes a stop and target after entry, a pending entry no brackets.
        val afterEntryOnly = all.copy(addProtection = false, addPositionProtection = true)
        assertEquals(listOf("买入", "毛 +5.00 USD", "TP", "SL", "1"), line(bare, afterEntryOnly).texts())
        val plain = entry.copy(request = entry.request.copy(stopLoss = null, takeProfit = null), stopLossOrderId = null, takeProfitOrderId = null)
        assertEquals(listOf("买入", "1", "限价"), pending(listOf(plain), options = afterEntryOnly).single().texts())
        assertEquals(LineTone.SELL, line(bare.copy(side = Side.SELL)).tone)
    }

    @Test fun protectionWithoutAnOrderIsRemovedOnlyWhereTheBrokerAllowsIt() {
        val position = bare.copy(stopLoss = 5_490_000_000)
        fun stop(options: OrderLineOptions) = orderLines(mes, listOf(position), position, emptyList(), null, null, null, null, options).single { it.price == 5_490_000_000L }
        assertEquals(LineAction.RemoveProtection(position, true), stop(all).closeAction())
        assertEquals("移除止损", stop(all).closeHint())
        assertNull(stop(all.copy(removeProtection = false)).closeAction())
        val locked = entry.copy(stopLossOrderId = null, takeProfitOrderId = null)
        val bracket = pending(listOf(locked)).single { it.price == 5_490_000_000L }
        assertEquals(LineAction.RemovePendingProtection(locked, true), bracket.closeAction())
        assertNull(pending(listOf(locked), options = all.copy(removeProtection = false)).single { it.price == 5_490_000_000L }.closeAction())
    }

    @Test fun pendingEntryTagReadsLeftToRightAsOnTradingView() {
        val plain = entry.copy(request = entry.request.copy(stopLoss = null, takeProfit = null), stopLossOrderId = null, takeProfitOrderId = null)
        val line = pending(listOf(plain)).single()
        assertEquals(listOf("买入", "TP", "SL", "1", "限价"), line.texts())
        assertEquals(LineTone.BUY, line.tone)
        assertEquals(LineAction.CancelOrder(plain), line.closeAction())
        assertEquals("撤单 买入限价 1 张 #700", line.closeHint())
        assertEquals(EditableLine.PendingProtection(plain, true, 5_500_000_000), line.cells.single { it.kind == CellKind.ADD_STOP }.drag)
        assertEquals(listOf("买入", "1", "限价"), pending(listOf(plain), options = all.copy(addProtection = false)).single().texts())
        assertEquals(listOf("买入", "1", "限价"), pending(listOf(plain.copy(editable = false))).single().texts())
    }

    @Test fun aNewProtectionFollowsThePointerOutOfItsButton() {
        val other = bare.copy(id = "100:300", side = Side.SELL)
        for (selected in listOf(bare, other)) {
            val drag = LineDrag(EditableLine.Protection(bare, true, bare.entryPrice), scale, 5_490_000_000)
            val moving = orderLines(mes, listOf(bare, other), selected, emptyList(), null, null, null, drag, all).single { it.moving }
            assertEquals(5_490_000_000, moving.price)
            assertEquals(-50_000_000, moving.pnl, "Measured from the position it was pulled out of, selected or not")
            assertEquals(LineTone.STOP, moving.tone)
        }
        val plain = entry.copy(request = entry.request.copy(stopLoss = null, takeProfit = null), stopLossOrderId = null, takeProfitOrderId = null)
        val drag = LineDrag(EditableLine.PendingProtection(plain, false, 5_500_000_000), scale, 5_510_000_000)
        val moving = pending(listOf(plain), drag).single { it.moving }
        assertEquals(5_510_000_000, moving.price)
        assertEquals(50_000_000, moving.pnl)
        assertEquals(LineTone.TARGET, moving.tone)
        assertTrue(pending(listOf(plain)).none { it.tone == LineTone.TARGET }, "Nothing is drawn until a drag starts")
    }

    @Test fun anOrderOfUnknownPurposeCanStillBeCancelledFromTheChart() {
        val unknown = PendingOrder("1080033", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 5_495_000_000), 0, role = OrderRole.UNRESOLVED, editable = false)
        val line = orderLines(mes, listOf(bare), bare, listOf(unknown), null, null, null, null, all).single { it.price == 5_495_000_000L }
        assertEquals(listOf("待确认", "1", "突破"), line.texts())
        assertNull(line.edit)
        assertEquals(LineTone.NEUTRAL, line.tone)
        assertEquals(LineAction.CancelOrder(unknown), line.closeAction())
    }

    @Test fun recognizedProtectionThatCannotBeEditedStillShowsItsResultAndCanBeCancelled() {
        val stop = brackets[1].copy(positionId = bare.id, editable = false)
        val line = orderLines(mes, listOf(bare), bare, listOf(stop), null, null, null, null, all).single { it.price == 5_490_000_000L }
        assertEquals(listOf("SL", "毛 -50.00 USD", "1"), line.texts())
        assertNull(line.edit)
        assertEquals(LineAction.CancelOrder(stop), line.closeAction())
    }

    @Test fun aPartialStopIsMeasuredOnItsOwnQuantity() {
        val position = bare.copy(lotsHundredths = 300)
        val stop = brackets[1].copy(positionId = position.id)
        val line = orderLines(mes, listOf(position), position, listOf(stop), null, null, null, null, all).single { it.price == 5_490_000_000L }
        // 40 ticks at $1.25 on one contract out of three.
        assertEquals(-50_000_000, line.pnl)
    }
}
