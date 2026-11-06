package com.unitrading.rithmic

import kotlinx.coroutines.channels.Channel

internal const val FAKE_GATEWAY = "wss://rituz00100.rithmic.com:443"
internal const val FAKE_TIME = 1_762_300_000_000L

/** In-memory R | Protocol server: every plant is a socket; replies follow the real template ids and part/terminal rules. */
internal class FakeRithmicServer : RithmicWire {
    val sockets = mutableListOf<FakeSocket>()
    val requests = mutableListOf<ProtoMessage>()
    var systemNames = listOf("Rithmic Test", "Rithmic Paper Trading")
    var accounts = listOf("ACC1")
    var loginRejection: String? = null
    enum class OrderReply { ACCEPT, ACCEPT_WITHOUT_BASKET, REJECT, SILENT }
    var orderReply = OrderReply.ACCEPT
    var maxOrderQuantity: Long? = 10
    var quote: Pair<Double, Double>? = 5500.0 to 5500.25
    var bars = 3
    var closed = false
    private var baskets = 0
    /** Snapshot order notifications pushed after a show-orders request; each lambda fills one 352 frame. */
    val openOrders = mutableListOf<ProtoWriter.() -> Unit>()
    /** Instrument PnL snapshots pushed after a PnL snapshot request. */
    val positions = mutableListOf<ProtoWriter.() -> Unit>()
    val bracketTargets = mutableMapOf<String, Pair<Long, Long>>()   // basket → (ticks, quantity)
    val bracketStops = mutableMapOf<String, Pair<Long, Long>>()
    val knownSymbols = mutableMapOf("MESZ6" to "CME", "MESH7" to "CME")
    /** Rithmic Test answers the front-month request with rp_code 7 "no data". */
    var frontMonthNoData = false
    /** Listings a product search returns ahead of [knownSymbols], as symbol to exchange. */
    var foreignListings = emptyMap<String, String>()
    /** Exchanges this login has no entitlement for. */
    var deniedExchanges = emptySet<String>()
    /** Rithmic Paper Trading lists a bare product row as a front-month alias, and replays it across rolls. */
    var frontMonthAlias = false
    var aliasBars = 5
    var tradeRoutesListed = true
    var fillHistoryListed = true
    /** Products answering the product-code request, by exchange. */
    var productCodes = emptyMap<String, List<String>>()
    /** Per-product order limits answering the product RMS request. */
    var productMaxOrder = emptyMap<String, Int>()
    var productCommission = emptyMap<String, Double>()

    override suspend fun socket(url: String): RithmicWireSocket {
        require(url == FAKE_GATEWAY) { "unexpected gateway $url" }
        return FakeSocket().also { sockets += it }
    }
    override fun close() { closed = true }

    fun plant(infra: Int): FakeSocket = sockets.last { it.infra == infra }
    fun requests(template: Int) = requests.filter { it.templateId == template }

    inner class FakeSocket : RithmicWireSocket {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        var infra = 0
        var closedByClient = false
        val sent = mutableListOf<ProtoMessage>()
        override suspend fun send(bytes: ByteArray) { for (message in unframeMessages(bytes)) { sent += message; handle(this, message) } }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { closedByClient = true; inbound.close() }
        fun push(build: ProtoWriter.() -> Unit) { inbound.trySend(frameMessage(ProtoWriter().apply(build).toByteArray())) }
        /** Simulates the server dropping the connection. */
        fun drop() { inbound.close() }
    }

    private fun productOf(symbol: String) = if (symbol == "MES" || symbol == "MES2") "MES" else symbol.dropLast(2)

    private fun handle(socket: FakeSocket, m: ProtoMessage) {
        requests += m
        val id = m.userMsg()
        fun reply(template: Int, terminal: Boolean = true, codes: List<String> = listOf("0"), build: ProtoWriter.() -> Unit = {}) = socket.push {
            int32(Rf.TEMPLATE_ID, template)
            if (id != null) string(Rf.USER_MSG, id)
            if (terminal) strings(Rf.RP_CODE, codes) else string(Rf.RQ_HANDLER_RP_CODE, "0")
            build()
        }
        fun parts(template: Int, rows: List<ProtoWriter.() -> Unit>) {
            rows.forEach { row -> reply(template, terminal = false, build = row) }
            reply(template)
        }
        when (m.templateId) {
            Rt.REQUEST_SYSTEM_INFO -> reply(Rt.RESPONSE_SYSTEM_INFO) { strings(Rf.SYSTEM_NAME, systemNames) }
            Rt.REQUEST_LOGIN -> {
                socket.infra = m.int(Rf.INFRA_TYPE) ?: 0
                val rejection = loginRejection
                if (rejection != null) reply(Rt.RESPONSE_LOGIN, codes = listOf("7", rejection))
                else reply(Rt.RESPONSE_LOGIN) { double(Rf.HEARTBEAT_INTERVAL, 30.0); string(Rf.FCM_ID, "FCM1"); string(Rf.IB_ID, "IB1") }
            }
            Rt.REQUEST_HEARTBEAT -> socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_HEARTBEAT); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt()) }
            Rt.REQUEST_LOGOUT -> reply(Rt.RESPONSE_LOGOUT)
            Rt.REQUEST_LOGIN_INFO -> reply(Rt.RESPONSE_LOGIN_INFO) { string(Rf.FCM_ID, "FCM1"); string(Rf.IB_ID, "IB1"); int32(Rf.USER_TYPE, RithmicEnums.USER_TYPE_TRADER) }
            Rt.REQUEST_ACCOUNT_LIST -> accounts.forEachIndexed { index, account ->
                reply(Rt.RESPONSE_ACCOUNT_LIST, terminal = index == accounts.lastIndex) { string(Rf.ACCOUNT_ID, account); string(Rf.ACCOUNT_NAME, "Account $account"); string(Rf.ACCOUNT_CURRENCY, "USD") }
            }
            Rt.REQUEST_TRADE_ROUTES -> reply(Rt.RESPONSE_TRADE_ROUTES) { if (tradeRoutesListed) { string(Rf.EXCHANGE, "CME"); string(Rf.TRADE_ROUTE, "simulator"); string(Rf.ROUTE_STATUS, "UP"); bool(Rf.IS_DEFAULT_ROUTE, true) } }
            Rt.REQUEST_SUBSCRIBE_ORDER_UPDATES -> reply(Rt.RESPONSE_SUBSCRIBE_ORDER_UPDATES)
            Rt.REQUEST_SUBSCRIBE_BRACKET_UPDATES -> reply(Rt.RESPONSE_SUBSCRIBE_BRACKET_UPDATES)
            Rt.REQUEST_SHOW_ORDERS -> {
                openOrders.forEach { order -> socket.push { int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); bool(Rf.IS_SNAPSHOT, true); string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); order() } }
                reply(Rt.RESPONSE_SHOW_ORDERS)
            }
            Rt.REQUEST_SHOW_BRACKETS -> parts(Rt.RESPONSE_SHOW_BRACKETS, bracketTargets.map { (basket, level) -> { string(Rf.BASKET_ID, basket); string(Rf.TARGET_TICKS, level.first.toString()); string(Rf.TARGET_QUANTITY, level.second.toString()) } })
            Rt.REQUEST_SHOW_BRACKET_STOPS -> parts(Rt.RESPONSE_SHOW_BRACKET_STOPS, bracketStops.map { (basket, level) -> { string(Rf.BASKET_ID, basket); string(Rf.STOP_TICKS, level.first.toString()); string(Rf.STOP_QUANTITY, level.second.toString()) } })
            Rt.REQUEST_ACCOUNT_RMS_INFO -> reply(Rt.RESPONSE_ACCOUNT_RMS_INFO) { string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); maxOrderQuantity?.let { int32(Rf.MAX_ORDER_QUANTITY, it.toInt()) } }
            Rt.REQUEST_SHOW_FILL_HISTORY -> parts(Rt.RESPONSE_SHOW_FILL_HISTORY, if (!fillHistoryListed) emptyList() else listOf {
                string(Rf.FILL_ID, "H1"); string(Rf.BASKET_ID, "H-B1"); string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
                string(Rf.TRANSACTION_TYPE, "B"); int64(Rf.FILL_SIZE, 1); double(Rf.FILL_PRICE, 5480.0); int32(Rf.SSBOE, (FAKE_TIME / 1_000 - 3_600).toInt())
            })
            Rt.REQUEST_PNL_SNAPSHOT -> {
                positions.forEach { position -> socket.push { int32(Rf.TEMPLATE_ID, Rt.INSTRUMENT_PNL_UPDATE); bool(Rf.IS_SNAPSHOT, true); string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); position() } }
                socket.push {
                    int32(Rf.TEMPLATE_ID, Rt.ACCOUNT_PNL_UPDATE); bool(Rf.IS_SNAPSHOT, true); string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: "")
                    string(Rf.ACCOUNT_BALANCE, "25000"); string(Rf.CASH_ON_HAND, "25000"); string(Rf.OPEN_POSITION_PNL, "0"); string(Rf.DAY_PNL, "12.5"); string(Rf.DAY_CLOSED_PNL, "12.5")
                    int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
                }
                reply(Rt.RESPONSE_PNL_SNAPSHOT)
            }
            Rt.REQUEST_PNL_UPDATES -> reply(Rt.RESPONSE_PNL_UPDATES)
            Rt.REQUEST_REFERENCE_DATA -> {
                val symbol = m.str(Rf.SYMBOL) ?: ""
                val exchange = knownSymbols[symbol]
                if (m.str(Rf.EXCHANGE) in deniedExchanges) reply(Rt.RESPONSE_REFERENCE_DATA, codes = listOf("13", "permission denied"))
                else if (exchange == null || exchange != m.str(Rf.EXCHANGE)) reply(Rt.RESPONSE_REFERENCE_DATA, codes = listOf("7", "no data"))
                else reply(Rt.RESPONSE_REFERENCE_DATA) {
                    val product = symbol.dropLast(2)
                    string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); string(Rf.SYMBOL_NAME, if (product == "MES") "Micro E-mini S&P 500" else "$product future"); string(Rf.PRODUCT_CODE, product)
                    string(Rf.INSTRUMENT_TYPE, "Future"); string(Rf.CURRENCY, "USD")
                    double(Rf.MIN_QPRICE_CHANGE, 0.25); double(Rf.MIN_FPRICE_CHANGE, 25.0); double(Rf.FTOQ_PRICE, 0.01); double(Rf.SINGLE_POINT_VALUE, 5.0)
                    string(Rf.EXPIRATION_DATE, if (symbol == "MESZ6") "20261218" else "20270319"); string(Rf.IS_TRADABLE, "true")
                }
            }
            Rt.REQUEST_SEARCH_SYMBOLS -> {
                val alias = if (frontMonthAlias) mapOf("MES" to "CME") else emptyMap()
                val wanted = m.str(Rf.EXCHANGE)
                val product = m.str(Rf.PRODUCT_CODE)
                val rows = (foreignListings + alias + knownSymbols).filter { (symbol, exchange) -> (wanted == null || exchange == wanted) && (product == null || productOf(symbol) == product) }
                parts(Rt.RESPONSE_SEARCH_SYMBOLS, rows.map { (symbol, exchange) -> {
                    string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); string(Rf.PRODUCT_CODE, productOf(symbol)); string(Rf.INSTRUMENT_TYPE, "Future")
                    string(Rf.EXPIRATION_DATE, if (symbol == "MESZ6" || symbol == "MES") "20261218" else "20270319")
                } })
            }
            Rt.REQUEST_FRONT_MONTH -> when {
                m.str(Rf.EXCHANGE) in deniedExchanges -> reply(Rt.RESPONSE_FRONT_MONTH, codes = listOf("13", "permission denied"))
                frontMonthNoData -> reply(Rt.RESPONSE_FRONT_MONTH, codes = listOf("7", "no data"))
                else -> reply(Rt.RESPONSE_FRONT_MONTH) { string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, "CME"); string(Rf.TRADING_SYMBOL, "${m.str(Rf.SYMBOL)}Z6"); string(Rf.TRADING_EXCHANGE, "CME"); bool(Rf.IS_FRONT_MONTH, true) }
            }
            Rt.REQUEST_PRODUCT_CODES -> parts(Rt.RESPONSE_PRODUCT_CODES, productCodes[m.str(Rf.EXCHANGE)].orEmpty().map { code -> { string(Rf.EXCHANGE, m.str(Rf.EXCHANGE) ?: ""); string(Rf.PRODUCT_CODE, code) } })
            Rt.REQUEST_PRODUCT_RMS_INFO -> parts(Rt.RESPONSE_PRODUCT_RMS_INFO, productMaxOrder.map { (code, limit) -> { string(Rf.PRODUCT_CODE, code); int32(Rf.MAX_ORDER_QUANTITY, limit); productCommission[code]?.let { double(Rf.COMMISSION_FILL_RATE, it) } } })
            Rt.REQUEST_MARKET_DATA_UPDATE -> {
                reply(Rt.RESPONSE_MARKET_DATA_UPDATE)
                val (bid, ask) = quote ?: return
                if (m.int(Rf.REQUEST) == RithmicEnums.REQUEST_SUBSCRIBE) socket.push {
                    int32(Rf.TEMPLATE_ID, Rt.BEST_BID_OFFER); string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, "CME")
                    uint32(Rf.PRESENCE_BITS, 3); double(Rf.BID_PRICE, bid); int32(Rf.BID_SIZE, 10); double(Rf.ASK_PRICE, ask); int32(Rf.ASK_SIZE, 12)
                    int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt()); int32(Rf.USECS, 250_000)
                }
            }
            Rt.REQUEST_TIME_BAR_UPDATE -> reply(Rt.RESPONSE_TIME_BAR_UPDATE)
            Rt.REQUEST_TIME_BAR_REPLAY -> {
                val symbol = m.str(Rf.SYMBOL) ?: ""
                val alias = frontMonthAlias && symbol == "MES"
                // The answer the real server gives for an expired month, and on a system without an alias.
                if (!alias && symbol !in knownSymbols) { reply(Rt.RESPONSE_TIME_BAR_REPLAY, codes = listOf("7", "reference data not available")); return }
                val period = (m.int(Rf.BAR_PERIOD) ?: 1) * 60L
                val lastClose = FAKE_TIME / 1_000 / period * period
                parts(Rt.RESPONSE_TIME_BAR_REPLAY, ((if (alias) aliasBars else bars) downTo 1).map { back -> {
                    string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, "CME"); int32(Rf.BAR_TYPE, RithmicEnums.BAR_MINUTE); string(Rf.BAR_PERIOD, (period / 60).toString())
                    int32(Rf.BAR_MARKER, (lastClose - (back - 1) * period).toInt()); int64(Rf.BAR_VOLUME, 100L * back)
                    double(Rf.OPEN_PRICE, 5499.0); double(Rf.HIGH_PRICE, 5501.0); double(Rf.LOW_PRICE, 5498.5); double(Rf.CLOSE_PRICE, 5500.0)
                } })
            }
            Rt.REQUEST_NEW_ORDER, Rt.REQUEST_BRACKET_ORDER -> {
                val response = if (m.templateId == Rt.REQUEST_NEW_ORDER) Rt.RESPONSE_NEW_ORDER else Rt.RESPONSE_BRACKET_ORDER
                when (orderReply) {
                    OrderReply.SILENT -> {}
                    OrderReply.ACCEPT_WITHOUT_BASKET -> {
                        socket.push {
                            int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, "B${++baskets}"); string(Rf.USER_TAG, m.str(Rf.USER_TAG) ?: "")
                            string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, m.str(Rf.EXCHANGE) ?: "")
                            string(Rf.STATUS, "open"); int32(Rf.TRANSACTION_TYPE, m.int(Rf.TRANSACTION_TYPE) ?: 0); int32(Rf.QUANTITY, m.int(Rf.QUANTITY) ?: 0); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
                        }
                        reply(response)
                    }
                    OrderReply.REJECT -> reply(response, codes = listOf("9", "RMS: quantity exceeds limit"))
                    OrderReply.ACCEPT -> {
                        // As on the real server: the basket id on a part of its own, then a terminal part without it.
                        val basket = "B${++baskets}"
                        reply(response, terminal = false) { string(Rf.BASKET_ID, basket); string(Rf.USER_TAG, m.str(Rf.USER_TAG) ?: "") }
                        reply(response)
                        socket.push {
                            int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, basket); string(Rf.USER_TAG, m.str(Rf.USER_TAG) ?: "")
                            string(Rf.ACCOUNT_ID, m.str(Rf.ACCOUNT_ID) ?: ""); string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, m.str(Rf.EXCHANGE) ?: "")
                            string(Rf.STATUS, "open"); int32(Rf.NOTIFY_TYPE, 13); int32(Rf.TRANSACTION_TYPE, m.int(Rf.TRANSACTION_TYPE) ?: 0); int32(Rf.QUANTITY, m.int(Rf.QUANTITY) ?: 0)
                            int32(Rf.PRICE_TYPE, m.int(Rf.PRICE_TYPE) ?: 0); m.double(Rf.PRICE)?.let { double(Rf.PRICE, it) }; m.double(Rf.TRIGGER_PRICE)?.let { double(Rf.TRIGGER_PRICE, it) }
                            int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
                        }
                    }
                }
            }
            Rt.REQUEST_MODIFY_ORDER -> reply(Rt.RESPONSE_MODIFY_ORDER) { string(Rf.BASKET_ID, m.str(Rf.BASKET_ID) ?: "") }
            Rt.REQUEST_CANCEL_ORDER -> reply(Rt.RESPONSE_CANCEL_ORDER) { string(Rf.BASKET_ID, m.str(Rf.BASKET_ID) ?: "") }
            Rt.REQUEST_UPDATE_STOP_LEVEL -> reply(Rt.RESPONSE_UPDATE_STOP_LEVEL)
            Rt.REQUEST_UPDATE_TARGET_LEVEL -> reply(Rt.RESPONSE_UPDATE_TARGET_LEVEL)
            Rt.REQUEST_EXIT_POSITION -> reply(Rt.RESPONSE_EXIT_POSITION) { string(Rf.SYMBOL, m.str(Rf.SYMBOL) ?: ""); string(Rf.EXCHANGE, m.str(Rf.EXCHANGE) ?: "") }
            else -> reply(Rt.REJECT, codes = listOf("5", "unsupported template ${m.templateId}"))
        }
    }

    /** Pushes an order notification on the order plant, as the server does after a modify, cancel or fill. */
    fun notifyOrder(basket: String, status: String, build: ProtoWriter.() -> Unit = {}) = plant(RithmicEnums.INFRA_ORDER).push {
        int32(Rf.TEMPLATE_ID, Rt.RITHMIC_ORDER_NOTIFICATION); string(Rf.BASKET_ID, basket); string(Rf.STATUS, status); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt()); build()
    }

    fun fill(basket: String, fillId: String, side: Int, size: Int, price: Double, originalBasket: String? = null, priceType: Int = RithmicEnums.PRICE_MARKET, origPriceType: Int = 0) = plant(RithmicEnums.INFRA_ORDER).push {
        int32(Rf.TEMPLATE_ID, Rt.EXCHANGE_ORDER_NOTIFICATION); int32(Rf.NOTIFY_TYPE, RithmicEnums.NOTIFY_FILL); string(Rf.BASKET_ID, basket); string(Rf.ACCOUNT_ID, "ACC1")
        string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME"); string(Rf.STATUS, "complete"); string(Rf.COMPLETION_REASON, "F")
        int32(Rf.TRANSACTION_TYPE, side); int32(Rf.QUANTITY, size); int32(Rf.PRICE_TYPE, priceType); originalBasket?.let { string(Rf.ORIGINAL_BASKET_ID, it) }
        if (origPriceType != 0) int32(Rf.ORIG_PRICE_TYPE, origPriceType)
        string(Rf.FILL_ID, fillId); int32(Rf.FILL_SIZE, size); double(Rf.FILL_PRICE, price); int32(Rf.TOTAL_FILL_SIZE, size); double(Rf.AVG_FILL_PRICE, price)
        int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
    }

    fun position(net: Int, avg: Double, pnl: String = "0") = plant(RithmicEnums.INFRA_PNL).push {
        int32(Rf.TEMPLATE_ID, Rt.INSTRUMENT_PNL_UPDATE); string(Rf.ACCOUNT_ID, "ACC1"); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME")
        int32(Rf.NET_QUANTITY, net); if (net == 0) { int32(Rf.FILL_BUY_QTY, 1); int32(Rf.FILL_SELL_QTY, 1) }
        double(Rf.AVG_OPEN_FILL_PRICE, avg); string(Rf.OPEN_POSITION_PNL, pnl); int32(Rf.SSBOE, (FAKE_TIME / 1_000).toInt())
    }
}

/** Open limit entry snapshot row for [FakeRithmicServer.openOrders]. */
internal fun limitEntry(basket: String, side: Int, quantity: Int, price: Double, originalBasket: String? = null, priceType: Int = RithmicEnums.PRICE_LIMIT): ProtoWriter.() -> Unit = {
    string(Rf.BASKET_ID, basket); string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME"); string(Rf.STATUS, "open")
    int32(Rf.TRANSACTION_TYPE, side); int32(Rf.QUANTITY, quantity); int32(Rf.PRICE_TYPE, priceType)
    if (priceType == RithmicEnums.PRICE_LIMIT) double(Rf.PRICE, price) else double(Rf.TRIGGER_PRICE, price)
    originalBasket?.let { string(Rf.ORIGINAL_BASKET_ID, it) }
    int32(Rf.SSBOE, (FAKE_TIME / 1_000 - 600).toInt())
}

internal fun positionRow(net: Int, avg: Double, pnl: String = "0"): ProtoWriter.() -> Unit = {
    string(Rf.SYMBOL, "MESZ6"); string(Rf.EXCHANGE, "CME"); int32(Rf.NET_QUANTITY, net); double(Rf.AVG_OPEN_FILL_PRICE, avg); string(Rf.OPEN_POSITION_PNL, pnl)
    int32(Rf.SSBOE, (FAKE_TIME / 1_000 - 900).toInt())
}
