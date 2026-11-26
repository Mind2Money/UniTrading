package com.unitrading.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

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
}
