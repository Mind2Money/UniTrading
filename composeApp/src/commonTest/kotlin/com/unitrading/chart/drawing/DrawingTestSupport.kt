package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.ChartViewport
import com.unitrading.chart.PriceScale

internal val testInstrument = Instrument("TEST", "Test", "Test", 2, tickSize = 25, tickValueMicrosPerLot = 5_000_000)
internal const val HOUR = 3_600_000L
internal const val START = 1_700_000_000_000L

/** Bars around 40000 in steps of 100, volume 1000 each. */
internal fun testCandles(count: Int = 100): List<Candle> = List(count) { i ->
    val open = 40_000L + (i % 10) * 100
    val close = open + if (i % 2 == 0) 100 else -100
    Candle(START + i * HOUR, open, maxOf(open, close) + 50, minOf(open, close) - 50, close, 1000)
}

internal fun testContext(candles: List<Candle> = testCandles(), viewport: ChartViewport = ChartViewport(100.0, 0.0), timeframe: Timeframe = Timeframe(60), plot: Rect = Rect(0f, 0f, 1000f, 500f)) =
    DrawingContext(DrawingCoordinates(candles, HOUR, viewport, plot, plot.bottom, PriceScale(38_000.0, 42_000.0, plot.top, plot.bottom)), testInstrument, timeframe, 1f, Color.Black, Color.White, Color.Green, Color.Red,
        { text, size, _, _, _ -> Size(text.length * size * .6f, size * 1.2f) })

internal fun at(bar: Int, price: Long, ctx: DrawingContext = testContext()) = Offset(ctx.coords.xOfIndex(bar.toDouble()), ctx.coords.y(price.toDouble()))
