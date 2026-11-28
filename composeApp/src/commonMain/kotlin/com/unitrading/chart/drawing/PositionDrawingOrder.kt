package com.unitrading.chart.drawing

import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.Quote
import com.unitrading.broker.Side
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Builds only a reviewable order, never submits it. Quantity follows the drawing's displayed risk formula. */
internal fun positionDrawingOrder(drawing: Drawing, spec: Instrument, quote: Quote): OrderRequest {
    require(drawing.tool == DrawingTool.LONG_POSITION || drawing.tool == DrawingTool.SHORT_POSITION) { "该绘图不是多头或空头仓位" }
    require(drawing.points.size >= 4 && spec.tickSize > 0 && spec.tickValueMicrosPerLot > 0 && spec.lotStepHundredths > 0) { "绘图或合约数据不完整" }
    fun price(index: Int): Long {
        val value = drawing.points[index].price
        require(value.isFinite() && value > 0 && value < Long.MAX_VALUE.toDouble()) { "绘图价格无效" }
        val units = value.roundToLong()
        require(abs(value - units) < 1e-6 && units % spec.tickSize == 0L) { "绘图价格不符合合约最小变动单位，请先调整绘图" }
        return units
    }
    val entry = price(0); val target = price(2); val stop = price(3)
    val side = if (drawing.tool == DrawingTool.LONG_POSITION) Side.BUY else Side.SELL
    require(if (side == Side.BUY) stop < entry && target > entry else stop > entry && target < entry) { "止盈止损须位于入场价的正确两侧" }
    require(quote.ask > 0 && quote.bid > 0 && quote.ask >= quote.bid) { "当前报价不可用，请等待有效行情" }
    val risk = drawing.param("accountSize", 1000.0) * drawing.param("riskPercent", 25.0) / 100.0
    val perLot = abs(entry - stop).toDouble() / spec.tickSize * (spec.tickValueMicrosPerLot / 1_000_000.0)
    require(risk.isFinite() && risk > 0 && perLot.isFinite() && perLot > 0) { "绘图风险金额或每跳价值无效" }
    val stepped = floor(risk / perLot * 100.0 / spec.lotStepHundredths) * spec.lotStepHundredths
    require(stepped.isFinite() && stepped >= spec.minLotsHundredths && stepped <= spec.maxLotsHundredths) { "绘图计算手数超出合约允许范围，请调整风险或止损距离" }
    val kind = if (side == Side.BUY && entry > quote.ask || side == Side.SELL && entry < quote.bid) OrderKind.STOP else OrderKind.LIMIT
    val symbol = requireNotNull(spec.executionSymbol) { "合约尚未解析，暂不能创建挂单" }
    return OrderRequest(symbol, side, kind, stepped.toLong(), entry, stop, target, sourceSymbol = spec.symbol.takeIf { spec.isContinuous }, fromPositionDrawing = true)
}
