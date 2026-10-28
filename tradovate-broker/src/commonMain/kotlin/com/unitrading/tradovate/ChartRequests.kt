package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `md/getChart` description: minute bars for intraday intervals, daily bars when the interval is a whole number of days. */
internal fun chartDescription(timeframe: Timeframe): JsonObject = buildJsonObject {
    val wholeDays = timeframe.minutes % 1440 == 0
    put("underlyingType", if (wholeDays) "DailyBar" else "MinuteBar")
    put("elementSize", if (wholeDays) timeframe.minutes / 1440 else timeframe.minutes)
    put("elementSizeUnit", "UnderlyingUnits")
    put("withHistogram", false)
}
