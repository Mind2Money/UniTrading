package com.unitrading.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

class HyperpropPersonalProbeTest {
    private val session = """{"session_type":"personal_api_key","scope":"trade","account_ids":["a"]}"""
    @Test fun displaysAuthorizedAccountAndKeepsMissingBalanceUnknown() {
        val parsed = parseHyperpropCheck(session, """{"success":true,"data":[{"id":"a","account_number":"A-1","organization":{"name":"Firm"},"current_balance":null}]}""")
        assertEquals("trade", parsed.scope)
        assertEquals("A-1", parsed.accounts.single().number)
        assertEquals("Firm", parsed.accounts.single().firm)
        assertNull(parsed.accounts.single().balance)
    }
    @Test fun rejectsAccountsOutsideTheSession() {
        assertFails { parseHyperpropCheck(session, """{"success":true,"data":[{"id":"b"}]}""") }
    }
    @Test fun rejectsInvalidSessionAndFailedAccountResponses() {
        assertFails { parseHyperpropCheck("""{"session_type":"partner","scope":"trade","account_ids":[]}""", """{"success":true,"data":[]}""") }
        assertFails { parseHyperpropCheck(session, """{"success":false,"data":[]}""") }
    }
    @Test fun buildsDatedOrderWithoutRoundingPriceOrAcceptingContinuousSymbols() {
        val order = hyperpropOrderBody("a", "MESZ6", "buy", "limit", 2, "5485.25")
        assertEquals("5485.25", order["price"]?.jsonPrimitive?.content)
        assertEquals(2, order["quantity"]?.jsonPrimitive?.int)
        assertFails { hyperpropOrderBody("a", "@MES", "buy", "limit", 1, "5485.25") }
        assertFails { hyperpropOrderBody("a", "MESZ6", "buy", "stop", 0, "5485.25") }
    }
    @Test fun pendingOrdersAreFilteredToTheSelectedAccountAndWorkingStates() {
        val text = """{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","side":"buy","order_type":"limit","quantity":1,"limit_price":5485.25,"status":"pending"},{"id":"2","account_id":"b","contract":"MESZ6","status":"pending"},{"id":"3","account_id":"a","contract":"MESZ6","status":"filled"}]}"""
        val pending = parseHyperpropPending(text, "a")
        assertEquals(listOf("1"), pending.map { it.id })
        assertEquals("5485.25", pending.single().price)
    }
}
