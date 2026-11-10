package com.unitrading.broker
import kotlin.test.*
import kotlinx.serialization.json.Json
class SecondsTimeframeTest {
    @Test fun canonicalSecondsAndLegacySerialization() {
        val s = Timeframe.seconds(15)
        assertEquals(15000L, s.durationMs); assertEquals("15s", s.key); assertEquals("15秒", s.label)
        assertEquals(Timeframe.M1, Timeframe.seconds(60))
        assertEquals(Timeframe.M5, Json.decodeFromString<Timeframe>("""{"minutes":5}"""))
        assertEquals(s, Json.decodeFromString<Timeframe>(Json.encodeToString(s)))
        assertFailsWith<IllegalArgumentException> { Timeframe(1, 15) }
        assertFailsWith<IllegalArgumentException> { Timeframe.seconds(0) }
        assertFailsWith<IllegalArgumentException> { Timeframe.seconds(61) }
    }
}
