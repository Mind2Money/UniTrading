package com.unitrading.broker

import kotlin.test.*

class TimeframeTest {
    @Test fun labelsFollowMinuteHourDayUnits() {
        assertEquals("1分钟", Timeframe.M1.label)
        assertEquals("45分钟", Timeframe(45).label)
        assertEquals("90分钟", Timeframe(90).label)
        assertEquals("1小时", Timeframe.H1.label)
        assertEquals("4小时", Timeframe(240).label)
        assertEquals("1天", Timeframe(1440).label)
        assertEquals("3天", Timeframe(3 * 1440).label)
    }
    @Test fun presetsAreSortedDistinctAndContainTheDefaultFavorites() {
        assertEquals(Timeframe.presets.sortedBy { it.minutes }, Timeframe.presets)
        assertEquals(Timeframe.presets.distinct(), Timeframe.presets)
        assertTrue(Timeframe.defaultFavorites.all { it in Timeframe.presets })
        assertTrue(Timeframe.presets.any { it.minutes < 60 } && Timeframe.presets.any { it.minutes in 60 until 1440 } && Timeframe.presets.any { it.minutes >= 1440 })
    }
    @Test fun customIntervalsAreBoundedAndEqualByMinutes() {
        assertEquals(Timeframe(420), Timeframe.of(7, 60))
        assertEquals(Timeframe(2 * 1440), Timeframe.of(2, 1440))
        assertEquals(Timeframe.M5, Timeframe.of(5, 1))
        assertNull(Timeframe.of(0, 1))
        assertNull(Timeframe.of(-5, 60))
        assertNull(Timeframe.of(400, 1440))
        assertNull(Timeframe.of(Int.MAX_VALUE, 60))
        assertFailsWith<IllegalArgumentException> { Timeframe(0) }
        assertFailsWith<IllegalArgumentException> { Timeframe(Timeframe.MAX_MINUTES + 1) }
    }
}
