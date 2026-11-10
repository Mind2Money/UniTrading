package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlin.test.*

private fun message(build: ProtoWriter.() -> Unit): ProtoMessage = decodeProto(ProtoWriter().apply(build).toByteArray())

class RithmicMappingTest {
    @Test fun priceScaleFollowsTheTickSize() {
        assertEquals(2, PriceScale.forTick(0.25)?.decimals)
        assertEquals(6, PriceScale.forTick(0.015625)?.decimals)
        assertEquals(1, PriceScale.forTick(0.1)?.decimals)
        assertEquals(0, PriceScale.forTick(1.0)?.decimals)
        assertNull(PriceScale.forTick(0.0))
        assertNull(PriceScale.forTick(Double.NaN))
        val scale = PriceScale(2)
        assertEquals(550025, scale.units(5500.25))
        assertEquals(5500.25, scale.price(550025))
        assertNull(scale.units(Double.POSITIVE_INFINITY))
    }

    @Test fun decimalTextBecomesIntegerUnitsWithoutDoubles() {
        assertEquals(25_000_500_000, decimalUnits("25000.50", 6))
        assertEquals(-12_250_000, decimalUnits("-12.25", 6))
        assertEquals(1_000_001, decimalUnits("1.0000005", 6))
        assertEquals(0, decimalUnits("0", 6))
        assertNull(decimalUnits("1e5", 6))
        assertNull(decimalUnits("abc", 6))
        assertNull(decimalUnits(null, 6))
    }

    @Test fun referenceDataBecomesAUsdFuturesInstrument() {
        val spec = mapInstrument(message {
            int32(Rf.TEMPLATE_ID, Rt.RESPONSE_REFERENCE_DATA); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
            string(Rf.SYMBOL_NAME, "Micro E-mini S&P 500"); string(Rf.PRODUCT_CODE, "MES"); string(Rf.INSTRUMENT_TYPE, "Future")
            string(Rf.EXPIRATION_DATE, "20261218"); string(Rf.CURRENCY, "USD")
            double(Rf.MIN_QPRICE_CHANGE, 0.25); double(Rf.MIN_FPRICE_CHANGE, 25.0); double(Rf.FTOQ_PRICE, 0.01); double(Rf.SINGLE_POINT_VALUE, 5.0); string(Rf.IS_TRADABLE, "true")
        })
        assertNotNull(spec)
        assertEquals("CME", spec.exchange)
        assertEquals("MES", spec.productCode)
        assertEquals(25, spec.instrument.tickSize)
        assertEquals(2, spec.instrument.priceDecimals)
        assertEquals(1_250_000, spec.instrument.tickValueMicrosPerLot)
        assertEquals("张", spec.instrument.quantityUnit)
        assertEquals(100, spec.instrument.lotStepHundredths)
        assertFalse(spec.instrument.estimatesIncludeFees)
        assertEquals("5500.25", spec.instrument.formatPrice(550025))
        // ESZ6 as Rithmic Test actually sends it: q-tick 0.25, f-tick 25.0, 50 USD per point -> 12.50 USD per tick.
        val es = mapInstrument(message {
            string(Rf.SYMBOL, "ESZ6"); string(Rf.EXCHANGE, "CME"); string(Rf.SYMBOL_NAME, "E-Mini S&P 500")
            string(Rf.PRODUCT_CODE, "ES"); string(Rf.INSTRUMENT_TYPE, "Future"); string(Rf.CURRENCY, "USD")
            double(Rf.MIN_QPRICE_CHANGE, 0.25); double(Rf.MIN_FPRICE_CHANGE, 25.0); double(Rf.FTOQ_PRICE, 0.01); double(Rf.QTOF_PRICE, 100.0); double(Rf.SINGLE_POINT_VALUE, 50.0)
        })!!
        assertEquals(2, es.instrument.priceDecimals)
        assertEquals(25, es.instrument.tickSize)
        assertEquals(12_500_000, es.instrument.tickValueMicrosPerLot)
        assertEquals("7708.25", es.instrument.formatPrice(770825))
    }

    @Test fun nonUsdOrIncompleteReferenceDataIsRejected() {
        val base: ProtoWriter.() -> Unit = { string(Rf.SYMBOL, "FGBLZ6"); string(Rf.EXCHANGE, "EUREX"); double(Rf.MIN_FPRICE_CHANGE, 0.01); double(Rf.SINGLE_POINT_VALUE, 1000.0) }
        assertNull(mapInstrument(message { base(); string(Rf.CURRENCY, "EUR") }))
        assertNull(mapInstrument(message { string(Rf.SYMBOL, "ESZ6"); string(Rf.EXCHANGE, "CME"); double(Rf.SINGLE_POINT_VALUE, 50.0) }))
        assertNull(mapInstrument(message { string(Rf.SYMBOL, "ESZ6"); string(Rf.EXCHANGE, "CME"); double(Rf.MIN_FPRICE_CHANGE, 0.25) }))
        assertNull(mapInstrument(message { base(); string(Rf.CURRENCY, "USD"); string(Rf.INSTRUMENT_TYPE, "Future Option") }))
        assertNotNull(mapInstrument(message { base(); string(Rf.CURRENCY, "USD") }))
    }

    @Test fun barMarkersBecomeOpenTimes() {
        val close = kotlin.time.Instant.parse("2026-11-05T12:05:00Z").toEpochMilliseconds()
        assertEquals(close - 5 * 60_000, barOpenTimeMs(close / 1_000, Timeframe.M5))
        assertEquals(kotlin.time.Instant.parse("2026-11-05T00:00:00Z").toEpochMilliseconds(), barOpenTimeMs(20261105, Timeframe.ofMinutes(1440)))
        assertNull(barOpenTimeMs(20261399, Timeframe.ofMinutes(1440)))
        assertNull(barOpenTimeMs(0, Timeframe.M1))
        assertEquals(20261105, yyyymmdd(close))
        val trade = kotlin.time.Instant.parse("2026-11-05T12:07:30Z").toEpochMilliseconds()
        assertEquals(close, barStart(trade, Timeframe.M5))
        assertEquals(kotlin.time.Instant.parse("2026-11-05T12:00:00Z").toEpochMilliseconds(), barStart(trade, Timeframe.M15))
    }

    @Test fun secondIntervalsAskForSecondBarsAndAreTimedLikeMinuteBars() {
        val s30 = Timeframe.ofSeconds(30)
        assertEquals(RithmicEnums.BAR_SECOND to 30, barType(s30) to barPeriod(s30))
        assertEquals(RithmicEnums.BAR_SECOND to 90, barType(Timeframe.ofSeconds(90)) to barPeriod(Timeframe.ofSeconds(90)))
        assertEquals(RithmicEnums.BAR_MINUTE to 5, barType(Timeframe.M5) to barPeriod(Timeframe.M5))
        assertEquals(RithmicEnums.BAR_MINUTE to 2, barType(Timeframe.ofSeconds(120)) to barPeriod(Timeframe.ofSeconds(120)), "two minutes however they were entered")
        assertEquals(RithmicEnums.BAR_MINUTE to 1500, barType(Timeframe.ofMinutes(1500)) to barPeriod(Timeframe.ofMinutes(1500)))
        assertEquals(RithmicEnums.BAR_DAILY to 3, barType(Timeframe.ofMinutes(3 * 1440)) to barPeriod(Timeframe.ofMinutes(3 * 1440)))
        val close = kotlin.time.Instant.parse("2026-11-05T12:05:30Z").toEpochMilliseconds()
        assertEquals(close - 30_000, barOpenTimeMs(close / 1_000, s30))
        val trade = kotlin.time.Instant.parse("2026-11-05T12:07:44Z").toEpochMilliseconds()
        assertEquals(kotlin.time.Instant.parse("2026-11-05T12:07:30Z").toEpochMilliseconds(), barStart(trade, s30))
        assertEquals(kotlin.time.Instant.parse("2026-11-05T12:07:44Z").toEpochMilliseconds(), barStart(trade + 250, Timeframe.ofSeconds(1)))
    }

    @Test fun orderStatusesSeparateWorkingFromFinished() {
        assertTrue(orderStatusActive("open", null))
        assertTrue(orderStatusActive("Modified, Trigger Pending", null))
        assertTrue(orderStatusActive("something new", null))
        assertFalse(orderStatusActive("complete", "F"))
        assertFalse(orderStatusActive("Cancelled", null))
        assertFalse(orderStatusActive("rejected", null))
        assertFalse(orderStatusActive("something new", "C"))
    }

    @Test fun notificationsMergeIntoThePreviousOrderState() {
        val first = parseOrderNotification(message {
            int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, "B1"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
            string(Rf.STATUS, "open"); int32(Rf.TRANSACTION_TYPE, RithmicEnums.BUY); int32(Rf.QUANTITY, 2); double(Rf.PRICE, 5490.0); int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_LIMIT)
            string(Rf.USER_TAG, "UT-abc"); int32(Rf.SSBOE, 1_762_300_000); int32(Rf.USECS, 500_000)
        }, null, 0)
        assertNotNull(first)
        assertEquals(1_762_300_000_500, first.timeMs)
        assertEquals(Side.BUY, first.side)
        assertFalse(first.isBracketChild)
        val fill = parseOrderNotification(message {
            int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); string(Rf.BASKET_ID, "B1"); string(Rf.STATUS, "complete"); string(Rf.COMPLETION_REASON, "F")
            int32(Rf.TOTAL_FILL_SIZE, 2); double(Rf.AVG_FILL_PRICE, 5489.75)
        }, first, 0)
        assertNotNull(fill)
        assertEquals("ACC1", fill.accountId)
        assertEquals("MESZ6", fill.symbol)
        assertEquals(2, fill.filled)
        assertEquals(5490.0, fill.price)
        assertEquals("UT-abc", fill.userTag)
        assertFalse(fill.active)
        assertNull(parseOrderNotification(message { int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.STATUS, "open") }, null, 0))
    }

    @Test fun fillsParseFromNotificationsAndFillHistoryAlike() {
        val live = parseFill(message {
            int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); string(Rf.FILL_ID, "F1"); string(Rf.BASKET_ID, "B1"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6")
            int32(Rf.TRANSACTION_TYPE, RithmicEnums.SELL); int32(Rf.FILL_SIZE, 1); double(Rf.FILL_PRICE, 5500.5); int32(Rf.SSBOE, 1_762_300_000)
        }, 7)
        assertEquals(RithmicFill("F1", "B1", "ACC1", "MESZ6", Side.SELL, 1, 5500.5, 1_762_300_000_000), live)
        val history = parseFill(message {
            int32(Rf.TEMPLATE_ID, Rt.RESPONSE_SHOW_FILL_HISTORY); string(Rf.FILL_ID, "F2"); string(Rf.BASKET_ID, "B2"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6")
            string(Rf.TRANSACTION_TYPE, "B"); int64(Rf.FILL_SIZE, 3); double(Rf.FILL_PRICE, 5490.0)
        }, 7)
        assertEquals(Side.BUY, history?.side)
        assertEquals(3, history?.quantity)
        assertEquals(7, history?.timeMs)
        assertNull(parseFill(message { string(Rf.FILL_ID, "F3"); string(Rf.BASKET_ID, "B"); string(Rf.ACCOUNT_ID, "A"); string(Rf.SYMBOL, "S"); int32(Rf.FILL_SIZE, 1); double(Rf.FILL_PRICE, 1.0) }, 0))
    }

    private val mes = mapInstrument(message {
        string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME"); string(Rf.SYMBOL_NAME, "Micro"); string(Rf.PRODUCT_CODE, "MES"); string(Rf.INSTRUMENT_TYPE, "Future"); string(Rf.CURRENCY, "USD")
        double(Rf.MIN_QPRICE_CHANGE, 0.25); double(Rf.SINGLE_POINT_VALUE, 5.0)
    })!!
    private fun fill(id: String, basket: String, side: Side, quantity: Long, price: Double, time: Long) = RithmicFill(id, basket, "ACC1", "MESZ6", side, quantity, price, time)
    private fun child(basket: String, entry: String, type: Int) = RithmicOrder(basket, "ACC1", "MESZ6", "CME", "complete", null, 1, null, null, RithmicEnums.PRICE_MARKET, 0,
        entry, null, null, 1, 0, null, null, 0, null, origPriceType = type)

    @Test fun pairsAPlainCloseAcrossPartialFills() {
        val trades = pairFills(listOf(fill("1", "A", Side.BUY, 3, 100.0, 10), fill("2", "B", Side.SELL, 1, 101.0, 20), fill("3", "C", Side.SELL, 2, 102.0, 30)), emptyMap(), mes, null, "p", 0)
        assertEquals(listOf(100L, 200L), trades.map { it.lotsHundredths })
        assertEquals(listOf(10100L, 10200L), trades.map { it.exitPrice })
        assertEquals(listOf("平仓", "平仓"), trades.map { it.reason })
        assertEquals(listOf(5_000_000L, 20_000_000L), trades.map { it.grossPnlMicros })
        assertEquals(listOf<Long?>(null, null), trades.map { it.netPnlMicros })
    }

    @Test fun aBracketChildClosesItsOwnEntryBeforeOlderLots() {
        val fills = listOf(fill("1", "OLD", Side.BUY, 1, 100.0, 10), fill("2", "NEW", Side.BUY, 1, 110.0, 20), fill("3", "T", Side.SELL, 1, 112.0, 30))
        val trade = pairFills(fills, mapOf("T" to child("T", "NEW", RithmicEnums.PRICE_LIMIT)), mes, null, "p", 1).single()
        assertEquals(11000, trade.entryPrice)
        assertEquals("止盈", trade.reason)
    }

    @Test fun aTriggeredStopChildIsLabelledAStop() {
        val fills = listOf(fill("1", "E", Side.SELL, 1, 100.0, 10), fill("2", "S", Side.BUY, 1, 103.0, 20))
        assertEquals("止损", pairFills(fills, mapOf("S" to child("S", "E", RithmicEnums.PRICE_STOP_MARKET)), mes, null, "p", 0).single().reason)
    }

    @Test fun aReplayThatMissesTheServersNetPositionPairsNothing() {
        // The sell closed a position opened before the fill window, so it must not read as a new short.
        val fills = listOf(fill("1", "A", Side.SELL, 1, 100.0, 10), fill("2", "B", Side.BUY, 1, 99.0, 20))
        assertEquals(1, pairFills(fills, emptyMap(), mes, null, "p", 0).size)
        assertTrue(pairFills(fills, emptyMap(), mes, null, "p", 2).isEmpty())
    }

    @Test fun nearestContractSkipsExpiredPlaceholderAndForeignRows() {
        fun row(symbol: String, product: String, expiration: String, type: String = "Future", exchange: String = "CME") = message {
            string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); string(Rf.PRODUCT_CODE, product); string(Rf.EXPIRATION_DATE, expiration); string(Rf.INSTRUMENT_TYPE, type)
        }
        val rows = listOf(row("ES", "ES", "20261218"), row("ESU6", "ES", "20260918"), row("0ESZ6", "0ES", "20261218"), row("ESH7", "ES", "20270319"), row("ESZ6", "ES", "20261218"),
            row("ESF3", "ES", "20991231"), row("ESZ6C", "ES", "20261120", type = "FUTURE_OPTION"), row("ESZ6", "ES", "20261218", exchange = "CBOT"))
        assertEquals("ESZ6" to "CME", nearestContract(rows, "ES", "CME", 20261105))
        assertEquals("ESH7" to "CME", nearestContract(rows, "ES", "CME", 20261219))
        assertNull(nearestContract(rows, "NQ", "CME", 20261105))
    }

    /** Dev kit 0.90 servers may send only the 8-byte `*_64` size fields; they win over the deprecated 4-byte ones when both appear. */
    @Test fun eightByteSizeFieldsAreReadAndPreferred() {
        val order = parseOrderNotification(message {
            int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, "B9"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
            string(Rf.STATUS, "open"); int32(Rf.TRANSACTION_TYPE, RithmicEnums.BUY); int64(Rf.QUANTITY_64, 3); int64(Rf.TOTAL_FILL_SIZE_64, 1); int64(Rf.TOTAL_UNFILLED_SIZE_64, 2)
        }, null, 0)
        assertEquals(3, order?.quantity); assertEquals(1, order?.filled); assertEquals(2, order?.unfilled)
        val both = parseOrderNotification(message {
            int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, "B9"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
            int32(Rf.QUANTITY, 1); int64(Rf.QUANTITY_64, 4)
        }, null, 0)
        assertEquals(4, both?.quantity)
        val fill = parseFill(message {
            int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); string(Rf.FILL_ID, "F9"); string(Rf.BASKET_ID, "B9"); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6")
            int32(Rf.TRANSACTION_TYPE, RithmicEnums.BUY); int64(Rf.FILL_SIZE_64, 2); double(Rf.FILL_PRICE, 5500.0)
        }, 1)
        assertEquals(2, fill?.quantity)
    }

    @Test fun positionsKeepTheirNetWhenAnUpdateCarriesNoQuantities() {
        val snapshot = parsePosition(message {
            int32(Rf.TEMPLATE_ID, Rt.INSTRUMENT_PNL_UPDATE); bool(Rf.IS_SNAPSHOT, true); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
            int32(Rf.NET_QUANTITY, -2); double(Rf.AVG_OPEN_FILL_PRICE, 5500.0); string(Rf.OPEN_POSITION_PNL, "-12.5")
        }, null, 0)
        assertEquals(-2, snapshot?.net)
        assertEquals(-12_500_000, snapshot?.openPnlMicros)
        val pnlOnly = parsePosition(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.OPEN_POSITION_PNL, "-25") }, snapshot, 0)
        assertEquals(-2, pnlOnly?.net)
        assertEquals(5500.0, pnlOnly?.avgPrice)
        assertEquals(-25_000_000, pnlOnly?.openPnlMicros)
        val flat = parsePosition(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); int32(Rf.FILL_BUY_QTY, 2); int32(Rf.FILL_SELL_QTY, 2) }, pnlOnly, 0)
        assertEquals(0, flat?.net)
        assertNull(flat?.avgPrice)
    }

    @Test fun accountMoneyFieldsStayUnknownUntilSent() {
        val first = parseFinancials(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.ACCOUNT_BALANCE, "25000"); string(Rf.DAY_PNL, "12.5") }, null, 1)
        assertEquals(25_000_000_000, first?.balanceMicros)
        assertNull(first?.cashMicros)
        assertEquals(12_500_000, first?.dayPnlMicros)
        val next = parseFinancials(message { string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.OPEN_POSITION_PNL, "-3.25") }, first, 2)
        assertEquals(25_000_000_000, next?.balanceMicros)
        assertEquals(-3_250_000, next?.openPnlMicros)
        assertEquals(2, next?.timeMs)
    }

    @Test fun credentialsValidateGatewayAndNeverExposeThePassword() {
        val credentials = RithmicCredentials(RithmicEnvironment.TEST, "wss://rituz00100.rithmic.com:443/", "Rithmic Test", "user@example.com", "secret-password")
        assertEquals("wss://rituz00100.rithmic.com:443", credentials.normalizedGateway)
        assertEquals("rithmic-test-rithmic-test-user-example-com", credentials.brokerId)
        assertFalse(credentials.toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { RithmicCredentials(RithmicEnvironment.LIVE, "wss://evil.example.com:443", "Rithmic 01", "u", "p") }
        assertFailsWith<IllegalArgumentException> { RithmicCredentials(RithmicEnvironment.LIVE, "ws://rprotocol.rithmic.com:443", "Rithmic 01", "u", "p") }
        assertFailsWith<IllegalArgumentException> { RithmicCredentials(RithmicEnvironment.LIVE, "wss://rithmic.com.attacker.net", "Rithmic 01", "u", "p") }
        assertFailsWith<IllegalArgumentException> { RithmicCredentials(RithmicEnvironment.TEST, "wss://rituz00100.rithmic.com", "Rithmic Test", "", "p") }
        assertEquals("wss://rprotocol.rithmic.com:443", validateRithmicGateway("wss://RProtocol.rithmic.com"))
    }
}
