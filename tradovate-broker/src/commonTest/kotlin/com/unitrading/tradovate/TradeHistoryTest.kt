package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.serialization.json.*
import kotlin.test.*

class TradeHistoryTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val spec = Instrument("MES", "MES", "Futures", 6, 250000, 1250000)
    private fun book() = EntityBook().apply {
        apply("currency", obj("""{"id":1,"name":"USD"}"""))
        apply("currency", obj("""{"id":2,"name":"EUR"}"""))
        apply("order", obj("""{"id":10,"accountId":100}"""))
        apply("order", obj("""{"id":20,"accountId":100}"""))
        apply("fill", obj("""{"id":1,"orderId":10,"contractId":200,"action":"Buy","qty":3,"price":5500,"active":true,"timestamp":"2026-10-28T10:00:00Z"}"""))
        apply("fill", obj("""{"id":2,"orderId":20,"contractId":200,"action":"Sell","qty":3,"price":5501,"active":true,"timestamp":"2026-10-28T11:00:00Z"}"""))
        for (id in 1..3) apply("fillPair", obj("""{"id":$id,"positionId":90,"buyFillId":1,"sellFillId":2,"qty":1,"buyPrice":5500,"sellPrice":5501,"active":true}"""))
    }
    @Test fun serverPartialPairsAllocateEveryFeeMicroExactlyOnce() {
        val trades = pairedTrades(book(), 100, mapOf(200L to spec), mapOf(1L to 1_000_001L, 2L to 2_000_002L))
        assertEquals(3, trades.size)
        assertEquals(15_000_000L, trades.sumOf { it.grossPnlMicros!! })
        assertEquals(3_000_003L, trades.sumOf { it.feesMicros!! })
        assertEquals(11_999_997L, trades.sumOf { it.netPnlMicros!! })
        assertTrue(trades.all { it.side == Side.BUY && it.directionKnown })
    }
    @Test fun missingFeesNeverBecomeZeroOrFabricatedNetProfit() {
        val trades = pairedTrades(book(), 100, mapOf(200L to spec), mapOf(1L to 0L))
        assertEquals(3, trades.size)
        assertTrue(trades.all { it.netPnlMicros == null && it.feesMicros == null && it.grossPnlMicros == 5_000_000L })
        assertNull(usdFillFees(emptyList(), book()))
        assertNull(usdFillFees(listOf(obj("""{"commission":1,"commissionCurrencyId":2}""")), book()))
        assertEquals(0, usdFillFees(listOf(obj("""{"commission":0}""")), book()))
        assertEquals(350_000, usdFillFees(listOf(obj("""{"commission":0.5,"commissionCurrencyId":1,"exchangeFee":-0.15,"exchangeCurrencyId":1}""")), book()))
    }
    @Test fun foreignInactiveAndOverallocatedPairsAreRejected() {
        val b = book()
        assertTrue(pairedTrades(b, 101, mapOf(200L to spec), emptyMap()).isEmpty())
        b.apply("fillPair", obj("""{"id":4,"positionId":90,"buyFillId":1,"sellFillId":2,"qty":1,"buyPrice":5500,"sellPrice":5501,"active":true}"""))
        assertTrue(pairedTrades(b, 100, mapOf(200L to spec), emptyMap()).isEmpty())
        b.apply("fillPair", obj("""{"id":4,"active":false}"""))
        assertEquals(3, pairedTrades(b, 100, mapOf(200L to spec), emptyMap()).size)
        b.apply("order", obj("""{"id":20,"accountId":101}"""))
        assertTrue(pairedTrades(b, 100, mapOf(200L to spec), emptyMap()).isEmpty())
    }
    @Test fun shortAndSameTimestampPairsPreserveWhatServerEvidenceActuallyProves() {
        val b = book()
        b.apply("fill", obj("""{"id":1,"timestamp":"2026-10-28T12:00:00Z"}"""))
        assertTrue(pairedTrades(b, 100, mapOf(200L to spec), emptyMap()).all { it.side == Side.SELL })
        b.apply("fill", obj("""{"id":2,"timestamp":"2026-10-28T12:00:00Z"}"""))
        assertTrue(pairedTrades(b, 100, mapOf(200L to spec), emptyMap()).all { !it.directionKnown && it.grossPnlMicros == 5_000_000L })
    }
}
