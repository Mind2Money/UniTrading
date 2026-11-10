package com.unitrading.android

import com.unitrading.tradovate.TradovateEnvironment
import com.unitrading.tradovate.TradovateSession
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IsolatedLoginPolicyTest {
    private val demo = TradovateEnvironment.DEMO
    private val now = 1_800_000_000_000L
    private fun payload(expiry: Long? = now + 60_000): String = Json.encodeToString(
        TradovateSession.serializer(), TradovateSession(demo, "synthetic-test-token-1234567890", null, 1L, "test", expiry)
    )
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("Expected rejection")
    }
    @Test fun acceptsValidSessionInRequestedEnvironment() {
        val result = decodeIsolatedSession(payload(), demo, now)
        assertEquals(demo, result.environment)
        assertEquals(1L, result.userId)
    }
    @Test fun rejectsCrossEnvironmentSession() {
        rejects { decodeIsolatedSession(payload(), TradovateEnvironment.LIVE, now) }
    }
    @Test fun rejectsExpiredSession() {
        rejects { decodeIsolatedSession(payload(now), demo, now) }
    }
    @Test fun rejectsMissingExpiry() {
        rejects { decodeIsolatedSession(payload(null), demo, now) }
    }
    @Test fun rejectsMissingAndOversizePayload() {
        rejects { decodeIsolatedSession(null, demo, now) }
        rejects { decodeIsolatedSession("x".repeat(131_073), demo, now) }
    }
    @Test fun rejectsInvalidTokenAndUser() {
        rejects { decodeIsolatedSession(payload().replace("synthetic-test-token-1234567890", "invalid token"), demo, now) }
        rejects { decodeIsolatedSession(payload().replace("\"userId\":1", "\"userId\":0"), demo, now) }
    }
}
