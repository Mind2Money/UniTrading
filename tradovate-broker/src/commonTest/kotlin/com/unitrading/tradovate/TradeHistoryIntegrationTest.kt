package com.unitrading.tradovate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TradeHistoryIntegrationTest {
    private fun array(value: String) = Json.parseToJsonElement(value).jsonArray
    @Test fun missingFillAndOrderDetailsAreFetchedAndActualFeesCompleteNetPnl() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("fillPairs" to array("""[{"id":1,"positionId":90,"buyFillId":1,"sellFillId":2,"qty":1,"buyPrice":5500,"sellPrice":5501,"active":true}]""")))
            historyItems["fill"] = array("""[
                {"id":1,"orderId":10,"contractId":200,"action":"Buy","qty":1,"price":5500,"active":true,"timestamp":"2026-10-28T10:00:00Z"},
                {"id":2,"orderId":20,"contractId":200,"action":"Sell","qty":1,"price":5501,"active":true,"timestamp":"2026-10-28T11:00:00Z"}]
            """)
            historyItems["order"] = array("""[{"id":10,"accountId":100,"contractId":200,"ordStatus":"Filled"},{"id":20,"accountId":100,"contractId":200,"ordStatus":"Filled"}]""")
            feeResponses[1] = array("""[{"id":991,"commission":0.5,"commissionCurrencyId":1}]""")
            feeResponses[2] = array("""[{"id":992,"commission":0.7,"commissionCurrencyId":1}]""")
        }
        val session = TradovateSession(TradovateEnvironment.DEMO, "synthetic-history-test-token-123456", null, 42, "test", 4_102_444_800_000)
        val broker = TradovateBroker(session, wire, backgroundScope)
        broker.connect()
        advanceTimeBy(15_000); runCurrent()
        val account = broker.openSession("100")
        assertEquals(2, account.state.value.executions.size)
        val trade = account.state.value.trades.single()
        assertEquals(5_000_000L, trade.grossPnlMicros)
        assertEquals(1_200_000L, trade.feesMicros)
        assertEquals(3_800_000L, trade.netPnlMicros)
        assertTrue(wire.requests.none { it.method == "POST" && it.path.startsWith("order/") })
        wire.feeResponses[2] = array("""[{"id":992,"commission":1,"commissionCurrencyId":1}]""")
        wire.account.entity("fillFee", Json.parseToJsonElement("""{"id":992,"commission":1,"commissionCurrencyId":1}""").jsonObject)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3_500_000L, account.state.value.trades.single().netPnlMicros)
        wire.account.entity("fillPair", Json.parseToJsonElement("""{"id":1,"active":false}""").jsonObject)
        runCurrent()
        assertTrue(account.state.value.trades.isEmpty())
    }
}
