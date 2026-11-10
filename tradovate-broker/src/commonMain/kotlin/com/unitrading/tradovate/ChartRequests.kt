package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `md/getChart` description: minute bars for intraday intervals, daily bars when the interval is a whole number of days. */
internal fun chartDescription(timeframe: Timeframe): JsonObject = buildJsonObject {
    val wholeDays = timeframe.seconds == 0 && timeframe.minutes % 1440 == 0
    put("underlyingType", if (timeframe.seconds > 0) "Tick" else if (wholeDays) "DailyBar" else "MinuteBar")
    put("elementSize", if (timeframe.seconds > 0) timeframe.seconds else if (wholeDays) timeframe.minutes / 1440 else timeframe.minutes)
    put("elementSizeUnit", if (timeframe.seconds > 0) "Seconds" else "UnderlyingUnits")
    put("withHistogram", false)
}
