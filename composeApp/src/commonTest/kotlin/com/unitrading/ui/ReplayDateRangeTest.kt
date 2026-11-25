package com.unitrading.ui

import com.unitrading.app.ChartTimeZone
import com.unitrading.broker.Timeframe
import com.unitrading.chart.parseChartTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplayDateRangeTest {
    @Test fun endDateIncludesTheWholeDayAndDailyBarsUseTradingDates() {
        val range = assertNotNull(replayDateRange("2026-01-01", "2026-11-24", ChartTimeZone.SHANGHAI))
        assertEquals(parseChartTime("2026-01-01 00:00", "Asia/Shanghai"), range.start)
        assertEquals(parseChartTime("2026-11-25 00:00", "Asia/Shanghai"), range.end)
        assertEquals(parseChartTime("2026-01-01 00:00", "UTC"), range.forFrame(Timeframe.D1).first)
        assertEquals(parseChartTime("2026-11-25 00:00", "UTC"), range.forFrame(Timeframe.D1).second)
    }

    @Test fun invalidOrReversedDatesAreRejected() {
        assertNull(replayDateRange("2026-02-30", "2026-03-01", ChartTimeZone.UTC))
        assertNull(replayDateRange("2026-12-01", "2026-11-01", ChartTimeZone.UTC))
    }
}
