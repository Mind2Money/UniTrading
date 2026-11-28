package com.unitrading.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

class HyperpropPersonalProbeTest {
    private val session = """{"session_type":"personal_api_key","scope":"trade","account_ids":["a"]}"""
    @Test fun recognizesOnlyConfirmedHyperpropPracticeEvaluations() {
        val data = """{"success":true,"data":[{"id":"a","account_number":"PRACTICE-HDL-1","account_type":"evaluation","organization":{"name":"Hyperprop Practice"},"markets":["HDL"]}]}"""
        val account = parseHyperpropCheck(session, data).accounts.single()
        assertTrue(account.isPractice)
        assertTrue(account.onlyPerpetualMarkets)
        assertFalse(account.copy(firm = "Another Firm").isPractice)
        assertFalse(account.copy(number = "LIVE-1").isPractice)
        assertFalse(account.copy(markets = setOf("CME")).onlyPerpetualMarkets)
    }
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
    @Test fun connectsUsingTheOfficialWrappedSessionResponse() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests.add(path)
            when (path) {
                "/trade/api-keys/session" -> respond(
                    """{"success":true,"data":{"access_token":"fake-token","session_type":"personal_api_key","scope":"trade","account_ids":["a"]}}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/trade/accounts" -> {
                    assertEquals("Bearer fake-token", request.headers[HttpHeaders.Authorization])
                    respond("""{"success":true,"data":[{"id":"a","account_number":"PRACTICE-1"}]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected endpoint")
            }
        }
        val connection = HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        try {
            assertEquals("PRACTICE-1", connection.check.accounts.single().number)
            assertEquals(listOf("/trade/api-keys/session", "/trade/accounts"), requests)
        } finally { connection.close() }
    }
    @Test fun rejectsFailedOrMalformedWrappedSession() {
        assertFails { parseHyperpropSession("""{"success":false,"data":{"access_token":"fake"}}""") }
        assertFails { parseHyperpropSession("""{"success":true,"data":null}""") }
    }
    @Test fun authenticationFailureDisplaysStatusWithoutEchoingCredentials() = runTest {
        val engine = MockEngine { respond("""{"error":"sensitive"}""", HttpStatusCode.Unauthorized) }
        val error = assertFailsWith<HyperpropConnectionException> {
            HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        }
        assertEquals(true, error.message?.contains("401"))
        assertEquals(false, error.message?.contains("sensitive"))
    }
    @Test fun explainsMarketPermissionFailureFromCommandStatus() = runTest {
        val engine = MockEngine { request ->
            val payload = when (request.url.encodedPath) {
                "/trade/api-keys/session" -> """{"success":true,"data":{"access_token":"fake-token","session_type":"personal_api_key","scope":"trade","account_ids":["a"]}}"""
                "/trade/accounts" -> """{"success":true,"data":[{"id":"a","account_number":"PRACTICE-1"}]}"""
                "/trade/order" -> """{"success":true,"data":{"accepted":true,"command_id":"01234567-89ab-cdef-0123-456789abcdef"}}"""
                "/trade/commands/01234567-89ab-cdef-0123-456789abcdef" -> """{"success":true,"data":{"status":"failed","error":"Account does not have access to the CME market."}}"""
                else -> error("Unexpected endpoint")
            }
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connection = HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        try {
            val outcome = connection.submit("a", "MNQZ6", "buy", "limit", 1, "26700")
            assertEquals(false, outcome.succeeded)
            assertTrue(outcome.status.contains("CME"))
        } finally { connection.close() }
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
    @Test fun partiallyFilledOrdersRemainAvailableForCancellation() {
        val text = """{"success":true,"data":[{"id":"1","account_id":"a","contract":"MESZ6","side":"buy","order_type":"limit","quantity":3,"filled_quantity":1,"limit_price":5485.25,"status":"partially_filled"}]}"""
        assertEquals(listOf("1"), parseHyperpropPending(text, "a").map { it.id })
    }
}
