package com.unitrading.chart

import com.unitrading.broker.*
import kotlin.test.*

class OrderLinesTest {
    private val mes = Instrument("MESZ6", "Micro E-mini S&P", "期货", 6, 250_000, 1_250_000, minLotsHundredths = 100, lotStepHundredths = 100,
        quantityUnit = "张", quantityDecimals = 0, displayPriceDecimals = 2, estimatesIncludeFees = false)
    private val fx = Instrument("EURUSD", "EUR/USD", "外汇", 5, tickValueMicrosPerLot = 1_000_000, commissionMicrosPerLot = 3_000_000)
    private val all = OrderLineOptions(showPositions = true, showOrders = true, editPending = true, editPendingProtection = true)
    private val scale = PriceScale(5_480_000_000.0, 5_520_000_000.0, 0f, 100f)

    /** A server-style pending entry whose stop and target live in separate bracket orders 702 and 701. */
    private val entry = PendingOrder("700", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, 5_500_000_000, 5_490_000_000, 5_510_000_000), 0,
        stopLossOrderId = "702", takeProfitOrderId = "701")
    private val brackets = listOf(
        PendingOrder("701", OrderRequest("MESZ6", Side.SELL, OrderKind.LIMIT, 100, 5_510_000_000), 0, role = OrderRole.TAKE_PROFIT, editable = false),
        PendingOrder("702", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 5_490_000_000), 0, role = OrderRole.STOP_LOSS, editable = false),
    )

    private fun pending(orders: List<PendingOrder>, drag: LineDrag? = null, options: OrderLineOptions = all) =
        orderLines(mes, emptyList(), null, orders, null, null, null, drag, options)

    @Test fun draftStopAndTargetAreDraggableAndPricedEvenWhileTheEstimateIsInvalid() {
        val draft = OrderRequest("EURUSD", Side.BUY, OrderKind.LIMIT, 100, 108_500, 108_300, 108_900)
        val lines = orderLines(fx, emptyList(), null, emptyList(), draft, OrderPreview(null, errors = listOf("止损方向或最小距离无效")), 108_500, null, all)
        val stop = lines.single { it.edit is EditableLine.DraftProtection && (it.edit as EditableLine.DraftProtection).stop }
        val target = lines.single { it.edit is EditableLine.DraftProtection && !(it.edit as EditableLine.DraftProtection).stop }
        // 200 ticks at $1 a tick, less a $3 commission each way.
        assertEquals(-206_000_000, stop.pnl)
        assertEquals("草稿 SL 止损 · -206.00 USD · 拖动", stop.label)
        assertEquals(394_000_000, target.pnl)
        assertTrue(lines.any { it.edit is EditableLine.Draft && it.price == 108_500L })
    }

    @Test fun aValidDraftEstimateIsShownAsTheBrokerGaveIt() {
        val draft = OrderRequest("MESZ6", Side.SELL, OrderKind.MARKET, 200, stopLoss = 5_510_000_000)
        val preview = OrderPreview(5_500_000_000, stopLossPnlMicros = -123_450_000)
        val lines = orderLines(mes, emptyList(), null, emptyList(), draft, preview, 5_500_000_000, null, all)
        assertEquals("草稿 SL 止损 · 毛 -123.45 USD · 拖动", lines.single { it.edit is EditableLine.DraftProtection }.label)
        assertNull(lines.single { it.price == 5_500_000_000L }.edit, "A market draft has no entry price to drag")
    }

    @Test fun pendingEntryBracketsShowTheirResultFromTheEntryAndReplaceTheBracketOrdersLines() {
        val lines = pending(listOf(entry) + brackets)
        assertTrue(lines.none { "#701" in it.label || "#702" in it.label }, "Bracket orders are drawn once, as the entry's own lines")
        val stop = lines.single { it.price == 5_490_000_000L }
        val target = lines.single { it.price == 5_510_000_000L }
        assertEquals("挂单 #700 SL 止损 · 毛 -50.00 USD · 拖动", stop.label)
        assertEquals("挂单 #700 TP 止盈 · 毛 +50.00 USD · 拖动", target.label)
        assertEquals(EditableLine.PendingProtection(entry, true, 5_490_000_000), stop.edit)
        assertEquals(EditableLine.Pending(entry), lines.single { it.price == 5_500_000_000L }.edit)
    }

    @Test fun lockedBracketsStayVisibleWithTheirResultButCannotBeDragged() {
        for ((orders, options) in listOf(listOf(entry.copy(protectionEditable = false)) to all, listOf(entry.copy(editable = false)) to all,
            listOf(entry) to all.copy(editPendingProtection = false))) {
            val stop = pending(orders, options = options).single { it.price == 5_490_000_000L }
            assertNull(stop.edit)
            assertEquals("挂单 #700 SL 止损 · 毛 -50.00 USD · 仅显示", stop.label)
        }
    }

    @Test fun aBracketWithoutItsPriceOnTheEntryKeepsItsOwnLine() {
        val lines = pending(listOf(entry.copy(request = entry.request.copy(stopLoss = null))) + brackets)
        assertTrue(lines.any { "#702" in it.label })
        assertTrue(lines.none { "#701" in it.label })
    }

    @Test fun draggedBracketFollowsThePointerWithALiveResult() {
        val drag = LineDrag(EditableLine.PendingProtection(entry, true, 5_490_000_000), scale, 5_495_000_000)
        val lines = pending(listOf(entry) + brackets, drag)
        val moving = lines.single { it.moving }
        assertEquals(5_495_000_000, moving.price)
        assertEquals(-25_000_000, moving.pnl)
        assertTrue(moving.label.endsWith("· 预览"))
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
        val position = Position("100:200", "MESZ6", Side.BUY, 100, 5_500_000_000, 0, 5_490_000_000, 5_510_000_000, 0, feesKnown = false,
            stopLossOrderId = "702", takeProfitOrderId = "701")
        val bound = brackets.map { it.copy(positionId = position.id, editable = true) }
        val shown = orderLines(mes, listOf(position), position, bound, null, null, null, null, all)
        assertEquals("SL 止损 · 毛 -50.00 USD · 拖动", shown.single { it.price == 5_490_000_000L }.label)
        // With the positions layer off the same stop is drawn from its order, still priced from the position's entry.
        val ordersOnly = orderLines(mes, listOf(position), position, bound, null, null, null, null, all.copy(showPositions = false))
        val stop = ordersOnly.single { it.price == 5_490_000_000L }
        assertEquals("SL 止损 卖出 STOP  #702 · 毛 -50.00 USD · 拖动止损", stop.label)
        assertEquals(EditableLine.Protection(position, true, 5_490_000_000), stop.edit)
    }
}
