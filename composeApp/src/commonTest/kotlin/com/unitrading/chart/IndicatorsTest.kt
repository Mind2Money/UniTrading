package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

class IndicatorsTest {
    private val es = Instrument("ESZ6", "E-Mini S&P 500", "期货 · 202612", 6, 250_000, 12_500_000, displayPriceDecimals = 2)
    private val nan = Double.NaN

    private fun assertSeries(expected: List<Double>, actual: DoubleArray, tolerance: Double = 1e-6) {
        assertEquals(expected.size, actual.size, "series length")
        expected.forEachIndexed { i, e ->
            if (e.isNaN()) assertTrue(actual[i].isNaN(), "index $i should be NaN but was ${actual[i]}")
            else assertTrue(abs(actual[i] - e) <= tolerance, "index $i expected $e but was ${actual[i]}")
        }
    }
    private fun closes(vararg values: Double) = values.map { Candle(0, it.toLong(), it.toLong(), it.toLong(), it.toLong()) }

    @Test fun movingAveragesSeedFromTheFirstFullWindow() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        assertSeries(listOf(nan, nan, 2.0, 3.0, 4.0, 5.0), sma(values, 3))
        assertSeries(listOf(nan, nan, 2.0, 3.0, 4.0, 5.0), ema(values, 3))
        assertSeries(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), ema(values, 1))
        assertSeries(listOf(nan, nan, nan, nan, nan, nan), sma(values, 7))
        // A gap restarts the window instead of mixing values across it.
        assertSeries(listOf(nan, 1.5, nan, nan, 4.5), sma(doubleArrayOf(1.0, 2.0, nan, 4.0, 5.0), 2))
        assertSeries(listOf(nan, 1.5, nan, nan, 4.5), ema(doubleArrayOf(1.0, 2.0, nan, 4.0, 5.0), 2))
    }

    @Test fun bollingerBandsUsePopulationDeviation() {
        val bands = bollinger(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3, 2.0)
        val deviation = sqrt(2.0 / 3)
        assertSeries(listOf(nan, nan, 2.0, 3.0, 4.0), bands[0])
        assertSeries(listOf(nan, nan, 2 + 2 * deviation, 3 + 2 * deviation, 4 + 2 * deviation), bands[1])
        assertSeries(listOf(nan, nan, 2 - 2 * deviation, 3 - 2 * deviation, 4 - 2 * deviation), bands[2])
    }

    @Test fun rsiFollowsWilderSmoothing() {
        assertSeries(listOf(nan, nan, 50.0, 100.0 * 1.25 / 1.5, 50.0), rsi(doubleArrayOf(10.0, 11.0, 10.0, 12.0, 11.0), 2))
        assertSeries(listOf(nan, nan, 100.0, 100.0), rsi(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 2))
        assertSeries(listOf(nan, nan, 0.0, 0.0), rsi(doubleArrayOf(4.0, 3.0, 2.0, 1.0), 2))
        assertSeries(listOf(nan, nan, 50.0, 50.0), rsi(doubleArrayOf(5.0, 5.0, 5.0, 5.0), 2))
        assertSeries(listOf(nan, nan), rsi(doubleArrayOf(5.0, 6.0), 2))
    }

    @Test fun macdOfALinearSeriesIsConstant() {
        val lines = macd(DoubleArray(50) { it.toDouble() }, 12, 26, 9)
        val (dif, dea, bar) = lines
        assertTrue(dif[24].isNaN() && dea[32].isNaN() && bar[32].isNaN())
        for (i in 25 until 50) assertEquals(7.0, dif[i], 1e-9)
        for (i in 33 until 50) { assertEquals(7.0, dea[i], 1e-9); assertEquals(0.0, bar[i], 1e-9) }
        val falling = macd(DoubleArray(50) { 100.0 - it }, 12, 26, 9)
        assertEquals(-7.0, falling[0][49], 1e-9)
    }

    @Test fun kdjStartsFromFiftyAndStaysBounded() {
        val rising = (0 until 6).map { i -> Candle(i.toLong(), i.toLong(), i + 10L, i.toLong(), i + 10L) }
        val (k, d, j) = kdj(rising, 3, 3, 3)
        assertTrue(k[1].isNaN() && d[1].isNaN() && j[1].isNaN())
        assertEquals(200.0 / 3, k[2], 1e-9)
        assertEquals((100.0 + 200.0 / 3) / 3, d[2], 1e-9)
        assertEquals(3 * k[2] - 2 * d[2], j[2], 1e-9)
        assertEquals((2 * 200.0 / 3 + 100) / 3, k[3], 1e-9)
        for (i in 2 until 6) assertTrue(k[i] in 0.0..100.0 && d[i] in 0.0..100.0)
        val flat = kdj(closes(5.0, 5.0, 5.0, 5.0), 2, 3, 3)
        assertSeries(listOf(nan, 50.0, 50.0, 50.0), flat[0])
        assertSeries(listOf(nan, 50.0, 50.0, 50.0), flat[2])
    }

    @Test fun atrSeedsWithTheMeanTrueRange() {
        val candles = listOf(Candle(0, 9, 10, 8, 9), Candle(1, 10, 12, 9, 11), Candle(2, 10, 11, 7, 8))
        assertSeries(listOf(nan, 2.5, 3.25), atr(candles, 2))
        assertSeries(listOf(2.0, 3.0, 4.0), atr(candles, 1))
        assertSeries(listOf(nan, nan, nan), atr(candles, 4))
        assertSeries(emptyList(), atr(emptyList(), 3))
    }

    @Test fun thousandBarPeriodsAreAcceptedAndComputeOverLongHistory() {
        assertTrue(MAX_INDICATOR_PERIOD >= 1000)
        for (kind in IndicatorKind.entries) for (spec in kind.params.filter { it.integer }) assertEquals(MAX_INDICATOR_PERIOD.toDouble(), spec.max, "${kind.label} ${spec.key}")
        val candles = List(2 * MAX_INDICATOR_PERIOD) { i -> val c = 7_700_000_000L + (i % 37) * 250_000L; Candle(i * 300_000L, c, c + 500_000, c - 500_000, c) }
        val period = MAX_INDICATOR_PERIOD.toDouble()
        val ready = MAX_INDICATOR_PERIOD until candles.size
        for (kind in listOf(IndicatorKind.MA, IndicatorKind.EXPMA, IndicatorKind.BOLL, IndicatorKind.RSI, IndicatorKind.ATR)) {
            for (s in computeIndicator(IndicatorConfig("x", kind, mapOf("period" to period)), candles)) {
                assertTrue(s.values[MAX_INDICATOR_PERIOD - 2].isNaN(), "${kind.label} ${s.line.name} before warm-up")
                assertTrue(ready.all { s.values[it].isFinite() }, "${kind.label} ${s.line.name} after warm-up")
            }
        }
        val kdj = computeIndicator(IndicatorConfig("y", IndicatorKind.KDJ, mapOf("period" to period, "k" to period, "d" to period)), candles)
        assertTrue(kdj.all { s -> s.values[MAX_INDICATOR_PERIOD - 2].isNaN() && ready.all { s.values[it].isFinite() } })
        val macd = computeIndicator(IndicatorConfig("z", IndicatorKind.MACD, mapOf("fast" to period - 1, "slow" to period, "signal" to period)), candles)
        assertTrue(macd.all { s -> s.values.last().isFinite() })
    }

    @Test fun configsValidateParametersColoursAndLabels() {
        val boll = IndicatorConfig("a", IndicatorKind.BOLL, mapOf("period" to 20.0, "width" to 2.5))
        boll.validate()
        assertEquals("BOLL(20,2.5)", boll.label)
        assertEquals("MACD(12,26,9)", IndicatorConfig("b", IndicatorKind.MACD).label)
        assertEquals("EXPMA(30)", IndicatorConfig("c", IndicatorKind.EXPMA, mapOf("period" to 30.0)).label)
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("d", IndicatorKind.RSI, mapOf("period" to 0.0)).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("e", IndicatorKind.RSI, mapOf("period" to 2.5)).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("f", IndicatorKind.RSI, mapOf("period" to 1001.0)).validate() }
        IndicatorConfig("l", IndicatorKind.EXPMA, mapOf("period" to 1000.0)).validate()
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("g", IndicatorKind.MACD, mapOf("fast" to 30.0)).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("h", IndicatorKind.MA, mapOf("width" to 1.0)).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("i", IndicatorKind.MA, colors = mapOf("MA" to "red")).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig("j", IndicatorKind.MACD, colors = mapOf("MACD" to "#FFFFFF")).validate() }
        assertFailsWith<IllegalArgumentException> { IndicatorConfig(" ", IndicatorKind.MA).validate() }
        assertEquals("#F5B942", IndicatorConfig("k", IndicatorKind.MA).color(0))
        assertEquals("#123456", IndicatorConfig("k", IndicatorKind.MA, colors = mapOf("MA" to "#123456")).color(0))
        assertEquals("2", formatParam(2.0))
        assertEquals("0.1", formatParam(0.1))
    }

    @Test fun rsiReferenceLevelsReplaceDefaultsWithoutChangingOtherIndicators() {
        val rsi = IndicatorConfig("rsi", IndicatorKind.RSI)
        assertEquals(listOf(30.0, 70.0), rsi.guides)
        val custom = rsi.copy(referenceLevels = listOf(0.0, 25.5, 50.0, 75.25, 100.0))
        custom.validate()
        assertEquals(custom.referenceLevels, custom.guides)
        val hidden = rsi.copy(referenceLevels = emptyList())
        hidden.validate()
        assertEquals(emptyList(), hidden.guides)
        assertEquals(listOf(30.0, 70.0), rsi.guides)
        assertEquals(listOf(20.0, 80.0), IndicatorConfig("kdj", IndicatorKind.KDJ).guides)
        assertEquals(listOf(0.0), IndicatorConfig("macd", IndicatorKind.MACD).guides)
        for (levels in listOf(listOf(-0.01), listOf(100.01), listOf(nan), listOf(Double.POSITIVE_INFINITY), listOf(30.0, 30.0))) {
            assertFailsWith<IllegalArgumentException> { rsi.copy(referenceLevels = levels).validate() }
        }
        assertFailsWith<IllegalArgumentException> { custom.copy(kind = IndicatorKind.MA).validate() }
    }

    @Test fun computedSeriesPaneRangesAndFormatting() {
        val candles = (0 until 40).map { i -> Candle(i * 60_000L, 100_000_000L + i, 105_000_000L + i, 95_000_000L + i, 102_000_000L + i) }
        val boll = computeIndicator(IndicatorConfig("x", IndicatorKind.BOLL), candles)
        assertEquals(listOf("中轨", "上轨", "下轨"), boll.map { it.line.name })
        assertTrue(boll.all { it.values[18].isNaN() && !it.values[39].isNaN() })
        assertTrue(boll[1].values[39] >= boll[0].values[39] && boll[0].values[39] >= boll[2].values[39])
        val macd = computeIndicator(IndicatorConfig("y", IndicatorKind.MACD), candles)
        assertTrue(macd[2].line.histogram && !macd[0].line.histogram)
        assertEquals(indicatorPalette[1], macd[1].color)

        val rsi = computeIndicator(IndicatorConfig("z", IndicatorKind.RSI), candles)
        val rsiRange = paneRange(IndicatorKind.RSI, rsi, 0..39)
        assertEquals(-6.0, rsiRange.start, 1e-9)
        assertEquals(106.0, rsiRange.endInclusive, 1e-9)
        val macdRange = paneRange(IndicatorKind.MACD, macd, 0..39)
        assertEquals(-macdRange.start, macdRange.endInclusive, 1e-9)
        assertEquals(-1.0..1.0, paneRange(IndicatorKind.ATR, computeIndicator(IndicatorConfig("w", IndicatorKind.ATR), candles), IntRange.EMPTY))
        val atr = computeIndicator(IndicatorConfig("v", IndicatorKind.ATR), candles)
        val atrRange = paneRange(IndicatorKind.ATR, atr, 20..39)
        assertTrue(atrRange.start < atr[0].values[39] && atrRange.endInclusive > atr[0].values[39])

        assertEquals("55.56", formatIndicatorValue(IndicatorKind.RSI, 55.555, es))
        assertEquals("—", formatIndicatorValue(IndicatorKind.MA, nan, es))
        assertEquals(es.formatPrice(7_773_750_000), formatIndicatorValue(IndicatorKind.MA, 7_773_750_000.0, es))
        assertEquals("-3.50", formatIndicatorValue(IndicatorKind.MACD, -3_500_000.0, es))
    }
}
