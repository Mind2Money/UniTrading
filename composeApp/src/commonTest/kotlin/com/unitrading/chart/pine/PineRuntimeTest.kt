package com.unitrading.chart.pine

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PineRuntimeTest {
    private val instrument = Instrument("TEST", "Test", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1)
    private val candles = listOf(10.0, 11.0, 13.0, 12.0, 15.0).mapIndexed { i, value ->
        val close = (value * 100).toLong()
        Candle(1_700_000_000_000 + i * Timeframe.M1.millis, close - 25, close + 100, close - 100, close, 100L + i)
    }

    private fun run(body: String, overlay: Boolean = true, bars: List<Candle> = candles) =
        runPine(compilePine("//@version=6\nindicator(\"Test\", overlay=$overlay)\n${body.trimIndent()}"), bars, instrument, Timeframe.M1)

    @Test fun defaultScriptDrawsTwentyPeriodBollingerBandsOnThePriceChart() {
        val bars = List(21) { i ->
            val close = (i + 1) * 100L
            Candle(i * Timeframe.M1.millis, close, close, close, close)
        }
        val result = runPine(compilePine(DEFAULT_PINE_SOURCE), bars, instrument, Timeframe.M1)
        assertEquals("布林带", result.title)
        assertTrue(result.overlay)
        assertEquals(listOf("中轨", "上轨", "下轨"), result.plots.map { it.title })
        assertTrue(result.plots.all { it.onPrice && it.values.take(19).all(Double::isNaN) })
        val (middle, upper, lower) = result.plots
        val width = 2 * kotlin.math.sqrt(33.25) // Population variance of twenty consecutive integer prices.
        assertEquals(10.5, middle.values[19], 1e-10)
        assertEquals(10.5 + width, upper.values[19], 1e-10)
        assertEquals(10.5 - width, lower.values[19], 1e-10)
        assertEquals(11.5, middle.values[20], 1e-10)
    }

    @Test fun formulasUseActualPricesAndTrackHistoricalValues() {
        val result = run("""
            length = input.int(2, "Window", minval=1)
            plot(ta.sma(close, length), "SMA")
            plot(ta.ema(close, length)[1], "Last EMA")
            plot(close + syminfo.mintick)
            plot(ta.highest(length=2))
            plot(ta.lowest(close, 2))
        """)
        assertTrue(result.overlay)
        assertContentEquals(doubleArrayOf(Double.NaN, 10.5, 12.0, 12.5, 13.5), result.plots[0].values)
        assertTrue(result.plots[1].values.first().isNaN())
        assertEquals(10.0, result.plots[1].values[1])
        assertEquals(10.0 + 2.0 / 3, result.plots[1].values[2], 1e-10)
        assertEquals(15.25, result.plots[2].values.last())
        assertEquals(14.0, result.plots[3].values[3])
        assertEquals(12.0, result.plots[4].values[3])
    }

    @Test fun outputContainsDynamicColorsReferencesPaneRoutingAndOffsets() {
        val result = run("""
            p = plot(close, "Hidden boundary", display=display.none, color=#00FF0080)
            q = plot(open, "Other boundary", color=close > close[1] ? color.lime : color.red)
            fill(p, q, #FFFF0066)
            hline(11, linestyle=hline.style_dotted)
            plot(close, force_overlay=true, offset=2, show_last=1)
            bgcolor(#0A0B0CCC, force_overlay=true)
            bgcolor(color.new(color.yellow, 80))
            barcolor(bar_index == 1 ? #112233 : na)
            plotshape(bar_index == 2, style=shape.triangleup, location=location.belowbar, offset=1, force_overlay=true)
            plotchar(barstate.islast, char="X", color=color.aqua, location=location.top)
        """, overlay = false)
        assertFalse(result.overlay)
        val (p, q, forced) = result.plots
        assertFalse(p.displayed)
        assertEquals(0x8000FF00.toInt(), p.colors.last())
        assertEquals(0xFF00E676.toInt(), q.colors.last())
        assertEquals(0xFFF23645.toInt(), q.colors[3])
        val fill = result.fills.single()
        assertEquals(p.id, fill.first)
        assertEquals(q.id, fill.second)
        assertEquals(0x66FFFF00, fill.colors[2])
        assertEquals(PineStroke.DOTTED, result.levels.single().pattern)
        assertTrue(forced.onPrice)
        assertEquals(2, forced.offset)
        assertTrue(forced.values.dropLast(1).all(Double::isNaN))
        assertEquals(0xCC0A0B0C.toInt(), result.priceBackgrounds.last())
        assertEquals(0xFF112233.toInt(), result.barColors[1])
        assertNull(result.barColors[2])
        val (shape, char) = result.marks
        assertTrue(shape.onPrice)
        assertEquals(3, shape.index)
        assertEquals(11.0, shape.value)
        assertEquals("X", char.text)
        assertEquals(0xFF00BCD4.toInt(), char.textColor)
    }

    @Test fun objectsCanBeUpdatedDeletedAndReplayedOnTheChangingLastBar() {
        val script = compilePine("""
            //@version=5
            indicator("Objects", overlay=true, max_labels_count=2)
            var line guide = na
            if barstate.isfirst
                guide := line.new(bar_index, close, bar_index + 1, close, extend=extend.right, color=color.red)
            line.set_xy2(guide, bar_index, high)
            label.new(bar_index, high, str.tostring(bar_index), style=label.style_label_down)
            if barstate.islast
                label labelId = label.new(bar_index, low, "removed")
                label.delete(labelId)
                box.new(time[2], high, time, low, xloc=xloc.bar_time, text="range", bgcolor=#55AAAA55)
        """.trimIndent())
        val original = runPine(script, candles, instrument, Timeframe.M1)
        val updatedBars = candles.dropLast(1) + candles.last().copy(high = 1_800, close = 1_700)
        val updated = runPine(script, updatedBars, instrument, Timeframe.M1)
        val line = original.drawings.filterIsInstance<PineDrawing.Line>().single()
        assertEquals(10.0, line.y1)
        assertEquals(16.0, line.y2)
        assertEquals(18.0, updated.drawings.filterIsInstance<PineDrawing.Line>().single().y2)
        val labels = updated.drawings.filterIsInstance<PineDrawing.Label>()
        assertTrue(labels.isNotEmpty() && labels.size <= 2)
        assertTrue(labels.none { it.text == "removed" })
        val box = updated.drawings.filterIsInstance<PineDrawing.Box>().single()
        assertTrue(box.byTime)
        assertEquals(candles[2].timeMs.toDouble(), box.left)
        assertEquals(18.0, box.top)
    }

    @Test fun limitsAndUnsupportedApisFailWithTheSourceLine() {
        val invalid = listOf(
            "indicator(\"X\")\nstrategy.entry(\"buy\", strategy.long)" to 2,
            "strategy(\"X\")" to 1,
            "indicator(\"X\")\nplot(request.security(\"ES\", \"60\", close))" to 2,
            "indicator(\"X\")\nif true\n    plot(close)" to 3,
            "indicator(\"X\")\nplot(close, color=color.red, color=color.green)" to 2,
            "indicator(\"X\")\nplot(noSuchSeries)" to 2,
        )
        for ((source, line) in invalid) assertEquals(line, assertFailsWith<PineException>(source) { compilePine(source) }.line)
        assertNull(pineScriptError(DEFAULT_PINE_SOURCE))
        val limit = assertFailsWith<PineException> {
            run("""
                var total = 0
                for i = 1 to 100000000000
                    total += i
                plot(total)
            """)
        }
        assertTrue(limit.message.orEmpty().contains("计算量"))
        assertEquals(3, assertFailsWith<PineException> { run("plot(close[-1])") }.line)
    }

    @Test fun localDeclarationsSeeOuterValuesUntilDeclaredOnEveryBar() {
        val result = run("""
            x = 10
            y = 0
            if true
                y := x
                x = x + 1
                y += x
            for i = 1 to 2
                y += i
            plot(y)
        """)
        assertContentEquals(DoubleArray(candles.size) { 24.0 }, result.plots.single().values)
    }

    @Test fun historyIsCappedAndCancellationIsNotSwallowed() {
        val sample = List(MAX_PINE_BARS + 15) { index -> candles[0].copy(timeMs = index.toLong() * 60_000) }
        val result = run("plot(bar_index)\nplot(time)", bars = sample)
        assertEquals(MAX_PINE_BARS, result.plots[0].values.size)
        assertEquals(0.0, result.plots[0].values.first())
        assertEquals(sample[15].timeMs.toDouble(), result.plots[1].values.first())
        var checks = 0
        val error = assertFailsWith<kotlinx.coroutines.CancellationException> {
            runPine(compilePine(DEFAULT_PINE_SOURCE), sample, instrument, Timeframe.M1) {
                if (++checks > 1) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertEquals("cancelled", error.message)
    }
}
