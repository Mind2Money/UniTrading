package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `md/getChart` description: daily bars when the interval is a whole number of days, minute bars for whole minutes. A
 * second interval asks for the tick series grouped by seconds, as Tradovate's own web client does; the public request
 * reference does not list that unit.
 */
internal fun chartDescription(timeframe: Timeframe): JsonObject = buildJsonObject {
    when {
        timeframe.isDaily -> { put("underlyingType", "DailyBar"); put("elementSize", timeframe.seconds / Timeframe.DAY); put("elementSizeUnit", "UnderlyingUnits") }
        timeframe.isSeconds -> { put("underlyingType", "Tick"); put("elementSize", timeframe.seconds); put("elementSizeUnit", "Seconds") }
        else -> { put("underlyingType", "MinuteBar"); put("elementSize", timeframe.seconds / Timeframe.MINUTE); put("elementSizeUnit", "UnderlyingUnits") }
    }
    put("withHistogram", false)
}
