package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import kotlin.test.*

class ChartOverlayTest {
    private val es = Instrument("ESZ6", "E-Mini S&P 500", "期货 · 202612", 6, 250_000, 12_500_000, displayPriceDecimals = 2)

    @Test fun barChangeUsesPreviousCloseAndFallsBackToOpen() {
        val previous = Candle(0, 7_770_000_000, 7_775_000_000, 7_769_000_000, 7_773_750_000)
        val bar = Candle(3_600_000, 7_773_750_000, 7_776_500_000, 7_768_500_000, 7_770_750_000)
        val change = barChange(bar, previous)
        assertEquals(-3_000_000, change.diff)
        assertEquals("-3.00 (-0.04%)", change.label(es))
        assertEquals("+0.00 (+0.00%)", barChange(bar.copy(close = bar.open), null).label(es))
        assertEquals("+2.50 (+0.03%)", barChange(bar.copy(close = 7_776_250_000), null).label(es))
        assertEquals(0, barChange(Candle(0, 0, 0, 0, 0), null).basisPoints)
    }

    @Test fun volumeFormatting() {
        assertEquals("—", formatVolume(null))
        assertEquals("—", formatVolume(-1))
        assertEquals("850", formatVolume(850))
        assertEquals("1.00K", formatVolume(1_000))
        assertEquals("34.84K", formatVolume(34_840))
        assertEquals("10.00K", formatVolume(9_995))
        assertEquals("1.23M", formatVolume(1_234_567))
    }

    @Test fun countdownOnlyInsideTheOpenBar() {
        val open = 57_600_000L // 16:00 UTC
        assertEquals("32:26", barCountdown(open, 3_600_000, open + 27 * 60_000 + 34_000))
        assertEquals("1:00:00", barCountdown(0, 7_200_000, 3_600_000))
        assertEquals("00:00", barCountdown(0, 60_000, 60_000))
        assertNull(barCountdown(open, 3_600_000, open + 3_600_001))
        assertNull(barCountdown(open, 3_600_000, open - 1))
        assertNull(barCountdown(open, 0, open))
    }

    @Test fun axisLabelsSwitchFromClockToDateForDailyBars() {
        val midnight = 1_798_588_800_000L // 2026-12-30 00:00 UTC
        assertEquals("00:00", axisTimeLabel(midnight, Timeframe.M5))
        assertEquals("01:00", axisTimeLabel(midnight + 3_600_000, Timeframe.ofMinutes(240)))
        assertEquals("12-30", axisTimeLabel(midnight, Timeframe.ofMinutes(1440)))
        assertEquals("12-30", axisTimeLabel(midnight + 3_600_000, Timeframe.ofMinutes(2 * 1440)))
        assertEquals("12-30", axisTimeLabel(midnight + 3_600_000, Timeframe.ofMinutes(1500)), "any interval of a day or longer shows the date")
    }

    @Test fun secondIntervalsLabelTheAxisToTheSecond() {
        val midnight = 1_798_588_800_000L // 2026-12-30 00:00 UTC
        assertEquals("00:00:05", axisTimeLabel(midnight + 5_000, Timeframe.ofSeconds(5)))
        assertEquals("01:02:30", axisTimeLabel(midnight + 3_750_000, Timeframe.ofSeconds(30)))
        assertEquals("01:02", axisTimeLabel(midnight + 3_750_000, Timeframe.M1), "minute intervals keep the short label")
    }
}
