package com.unitrading.broker

import kotlin.test.*

class TimeframeTest {
    @Test fun labelsFollowSecondMinuteHourDayUnits() {
        assertEquals("1秒", Timeframe.ofSeconds(1).label)
        assertEquals("30秒", Timeframe.ofSeconds(30).label)
        assertEquals("90秒", Timeframe.ofSeconds(90).label, "not a whole number of minutes, so it stays in seconds")
        assertEquals("1分钟", Timeframe.M1.label)
        assertEquals("45分钟", Timeframe.ofMinutes(45).label)
        assertEquals("90分钟", Timeframe.ofMinutes(90).label)
        assertEquals("1小时", Timeframe.H1.label)
        assertEquals("4小时", Timeframe.ofMinutes(240).label)
        assertEquals("1天", Timeframe.D1.label)
        assertEquals("3天", Timeframe.ofMinutes(3 * 1440).label)
    }
    @Test fun presetsAreSortedDistinctAndContainTheDefaultFavorites() {
        assertEquals(Timeframe.presets.sortedBy { it.seconds }, Timeframe.presets)
        assertEquals(Timeframe.presets.distinct(), Timeframe.presets)
        assertTrue(Timeframe.defaultFavorites.all { it in Timeframe.presets })
        assertTrue(Timeframe.presets.any { it.isSeconds } && Timeframe.presets.any { !it.isSeconds && it.seconds < Timeframe.HOUR } &&
            Timeframe.presets.any { it.seconds in Timeframe.HOUR until Timeframe.DAY } && Timeframe.presets.any { it.isDaily })
    }
    @Test fun customIntervalsAreBoundedAndEqualByLength() {
        assertEquals(Timeframe.ofMinutes(420), Timeframe.of(7, Timeframe.HOUR))
        assertEquals(Timeframe.ofMinutes(2 * 1440), Timeframe.of(2, Timeframe.DAY))
        assertEquals(Timeframe.M5, Timeframe.of(5, Timeframe.MINUTE))
        assertEquals(Timeframe.ofSeconds(45), Timeframe.of(45, 1))
        assertEquals(Timeframe.M5, Timeframe.ofSeconds(300), "five minutes however they were entered")
        assertNull(Timeframe.of(0, 1))
        assertNull(Timeframe.of(-5, Timeframe.HOUR))
        assertNull(Timeframe.of(400, Timeframe.DAY))
        assertNull(Timeframe.of(Int.MAX_VALUE, Timeframe.MINUTE))
        assertFailsWith<IllegalArgumentException> { Timeframe.ofMinutes(0) }
        assertFailsWith<IllegalArgumentException> { Timeframe.ofSeconds(0) }
        assertFailsWith<IllegalArgumentException> { Timeframe.ofMinutes(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { Timeframe.ofSeconds(Timeframe.MAX_SECONDS + 1) }
    }
    @Test fun spansAndKindsFollowTheLength() {
        assertEquals(5_000L, Timeframe.ofSeconds(5).millis)
        assertEquals(300_000L, Timeframe.M5.millis)
        assertTrue(Timeframe.ofSeconds(5).isSeconds && Timeframe.ofSeconds(90).isSeconds)
        assertFalse(Timeframe.M1.isSeconds || Timeframe.ofSeconds(120).isSeconds || Timeframe.D1.isSeconds)
        assertTrue(Timeframe.D1.isDaily && Timeframe.ofMinutes(3 * 1440).isDaily)
        assertFalse(Timeframe.H1.isDaily || Timeframe.ofMinutes(1500).isDaily, "25 hours are not whole days")
    }
    @Test fun wholeMinuteIntervalsKeepTheNameTheyHadBeforeSecondsExisted() {
        assertEquals("5", Timeframe.M5.id)
        assertEquals("240", Timeframe.ofMinutes(240).id)
        assertEquals("1440", Timeframe.D1.id)
        assertEquals("30s", Timeframe.ofSeconds(30).id)
        assertEquals("90s", Timeframe.ofSeconds(90).id)
        assertEquals(Timeframe.presets.size, Timeframe.presets.map { it.id }.distinct().size)
    }
}
