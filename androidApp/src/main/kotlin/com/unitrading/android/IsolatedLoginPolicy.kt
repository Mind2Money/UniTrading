package com.unitrading.android

import com.unitrading.tradovate.TradovateEnvironment
import com.unitrading.tradovate.TradovateSession
import kotlinx.serialization.json.Json

/** Revalidate the login process's result before it enters the main-process session store. */
internal fun decodeIsolatedSession(encoded: String?, environment: TradovateEnvironment, nowMs: Long): TradovateSession {
    requireNotNull(encoded)
    require(encoded.length <= 131_072)
    return Json.decodeFromString(TradovateSession.serializer(), encoded).also {
        require(it.environment == environment)
        require((it.expiresAtMs ?: 0) > nowMs)
    }
}
