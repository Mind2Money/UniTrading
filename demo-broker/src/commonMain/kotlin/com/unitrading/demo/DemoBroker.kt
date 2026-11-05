package com.unitrading.demo

import com.unitrading.broker.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A deterministic USD-only, hedging demo. The UI calls advance() once per real second;
 * no wall clock, background timer, network connection or offline catch-up is used here.
 *
 * Candles use bid ticks. History retains 48 hours at the normal 15-second cadence,
 * the latest 1,000 closed trades and 200 events per account. Idempotency records are
 * NEVER expired (including rejects); they grow with usage and survive export/restore.
 * Explicit reset clears them: retrying a pre-reset request can therefore execute again.
 */
class DemoBroker(
    config: DemoConfig,
    snapshotJson: String? = null,
    seedTrades: Boolean = true,
) : BrokerPlugin {
    private val settings = config.copy(
        instruments = config.instruments.toList(), accounts = config.accounts.toList(),
        initialBids = config.initialBids.toMap(), spreadTicks = config.spreadTicks.toMap(),
        volatilityTicks = config.volatilityTicks.toMap(),
    )
    override val descriptor = BrokerDescriptor(settings.id, settings.name, settings.description)
    override val capabilities = BrokerCapabilities()
    override val instruments: List<Instrument> = settings.instruments.toList()
    override val accounts: List<AccountInfo> = settings.accounts.toList()
    override val catalog: StateFlow<BrokerCatalog> = MutableStateFlow(BrokerCatalog(instruments, accounts))
    private val tradeEvents = MutableSharedFlow<TradeFill>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val tradeFills = tradeEvents.asSharedFlow()
    private val pendingTradeFills = mutableListOf<TradeFill>()
    private var soundEpoch = 0L
    private val bySymbol = instruments.associateBy { it.symbol }

    // One lock also serializes ticks with every account's requests. A committed, detached
    // snapshot makes non-suspending export/history/preview reads atomic without runBlocking.
    private val mutex = Mutex()
    private val committed = MutableStateFlow<StoredBroker?>(null)
    private val marketFlow = MutableStateFlow(MarketSnapshot())
    override val market: StateFlow<MarketSnapshot> = marketFlow.asStateFlow()
    private val accountFlows = accounts.associate { it.id to MutableStateFlow(AccountSnapshot(it.id, settings.startingBalanceMicros)) }
    private var now = settings.startTimeMs
    private var rng = settings.randomSeed
    private var nextId = 1L
    private var revision = 0L
    private var connected = true
    private val quotes = linkedMapOf<String, Quote>()
    private val ticks = linkedMapOf<String, MutableList<Quote>>()
    private val ledgers = linkedMapOf<String, Ledger>()

    init {
        validateConfig()
        if (snapshotJson == null) initialize(seedTrades) else {
            val saved = try {
                SNAPSHOT_JSON.decodeFromString<StoredBroker>(snapshotJson).also { validateRestore(it) }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid or incompatible DemoBroker snapshot: ${e.message}", e)
            }
            load(saved)
        }
        pendingTradeFills.clear()
        publish()
    }

    override fun openSession(accountId: String): AccountSession {
        val info = accounts.firstOrNull { it.id == accountId } ?: error("未知账户：$accountId")
        return object : AccountSession {
            override val account = info
            override val state: StateFlow<AccountSnapshot> = accountFlows.getValue(accountId).asStateFlow()
            override fun preview(request: OrderRequest): OrderPreview {
                val saved = committed.value!!
                return estimate(request, saved.accounts.first { it.state.accountId == accountId }.state, saved.market)
            }
            override fun previewOrderModification(orderId: String, entryPrice: Long) =
                previewPending(accountId, orderId) { it.copy(entryPrice = entryPrice) }
            override fun previewOrderProtection(orderId: String, stopLoss: Long?, takeProfit: Long?) =
                previewPending(accountId, orderId) { it.copy(stopLoss = stopLoss, takeProfit = takeProfit) }
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) =
                submit(accountId, requestId, Command(CommandKind.MODIFY_ORDER, entityId = orderId,
                    expectedVersion = expectedVersion, entryPrice = entryPrice))
            override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
                submit(accountId, requestId, Command(CommandKind.PROTECT_ORDER, entityId = orderId,
                    expectedVersion = expectedVersion, stopLoss = stopLoss, takeProfit = takeProfit))
            override suspend fun placeOrder(requestId: String, request: OrderRequest) =
                submit(accountId, requestId, Command(CommandKind.PLACE, order = request))
            override suspend fun closePosition(requestId: String, positionId: String) =
                submit(accountId, requestId, Command(CommandKind.CLOSE, entityId = positionId))
            override suspend fun cancelOrder(requestId: String, orderId: String) =
                submit(accountId, requestId, Command(CommandKind.CANCEL, entityId = orderId))
            override suspend fun modifyProtection(
                requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?,
            ) = submit(accountId, requestId, Command(CommandKind.PROTECT, entityId = positionId,
                expectedVersion = expectedVersion, stopLoss = stopLoss, takeProfit = takeProfit))
        }
    }

    private fun estimate(request: OrderRequest, account: AccountSnapshot, market: MarketSnapshot): OrderPreview = try {
        val terms = terms(request, account, market)
        val fees = checkedMultiply(terms.fee, 2)
        OrderPreview(terms.entry, terms.margin, fees,
            request.stopLoss?.let { checkedAdd(grossPnl(terms.instrument, request.side, request.lotsHundredths, terms.entry, it), -fees) },
            request.takeProfit?.let { checkedAdd(grossPnl(terms.instrument, request.side, request.lotsHundredths, terms.entry, it), -fees) },
            quoteRevision = market.revision)
    } catch (error: IllegalArgumentException) {
        OrderPreview(null, errors = listOf(error.message ?: "无效订单"), quoteRevision = market.revision)
    }

    /** Estimates a pending order as [change] would leave it, without counting the order itself against the account. */
    private fun previewPending(accountId: String, orderId: String, change: (OrderRequest) -> OrderRequest): OrderPreview {
        val saved = committed.value!!
        val state = saved.accounts.first { it.state.accountId == accountId }.state
        val order = state.orders.firstOrNull { it.id == orderId }
            ?: return OrderPreview(null, errors = listOf("挂单已成交、已撤销或不属于当前账户"))
        return estimate(change(order.request), state.copy(orders = state.orders.filterNot { it.id == orderId }), saved.market)
    }

    override fun history(symbol: String, timeframe: Timeframe): List<Candle> {
        require(symbol in bySymbol) { "未知品种：$symbol" }
        val width = timeframe.minutes * 60_000L
        val candles = mutableListOf<Candle>()
        for (tick in committed.value!!.ticks.getValue(symbol)) {
            val bucket = tick.timeMs / width * width
            val previous = candles.lastOrNull()
            if (previous == null || previous.timeMs != bucket) {
                candles.add(Candle(bucket, tick.bid, tick.bid, tick.bid, tick.bid))
            } else {
                candles[candles.lastIndex] = previous.copy(high = maxOf(previous.high, tick.bid),
                    low = minOf(previous.low, tick.bid), close = tick.bid)
            }
        }
        return candles.toList()
    }

    suspend fun advance(seconds: Int = 15) {
        require(seconds in 0..86_400) { "单次模拟推进须在 0..86400 秒" }
        transaction {
            var remaining = seconds
            while (remaining > 0) {
                val step = minOf(remaining, 15)
                generateTick(step * 1_000L)
                remaining -= step
            }
        }
    }

    /** Export the last complete transaction; this does not block a coroutine or mutate state. */
    fun exportState(): String = SNAPSHOT_JSON.encodeToString(committed.value!!)

    suspend fun reset(seedTrades: Boolean = true) = transaction {
        initialize(seedTrades)
        pendingTradeFills.clear()
        soundEpoch++
    }

    /** Deterministic feed injection. Equal timestamps are allowed; time cannot move backwards. */
    suspend fun setQuote(symbol: String, bid: Long, ask: Long, timeMs: Long) = transaction {
        require(timeMs >= now) { "报价时间不可倒退" }
        val instrument = bySymbol[symbol] ?: throw IllegalArgumentException("未知品种：$symbol")
        val quote = Quote(bid, ask, timeMs)
        validateQuote(instrument, quote)
        now = timeMs
        appendTick(symbol, quote)
        revision = checkedAdd(revision, 1)
        match(setOf(symbol))
    }

    /** Disconnect pauses fills, not the simulation clock. Reconnect needs a fresh quote. */
    suspend fun setConnected(value: Boolean) = transaction {
        if (connected != value) {
            connected = value
            revision = checkedAdd(revision, 1)
        }
    }

    private suspend fun transaction(action: () -> Unit) = mutex.withLock {
        val before = committed.value!!
        try {
            action()
            publish()
        } catch (e: Exception) {
            pendingTradeFills.clear()
            load(before)
            throw e
        }
    }

    private suspend fun submit(accountId: String, requestId: String, command: Command): TradeResult = mutex.withLock {
        val result = execute(accountId, requestId, command)
        publish()
        result
    }

    private fun execute(accountId: String, requestId: String, command: Command): TradeResult {
        val original = ledgers.getValue(accountId)
        original.commands[requestId]?.let {
            if (it.command == command) return it.result
            val conflict = TradeResult(false, "requestId 已用于另一指令")
            event(original, "拒绝：${conflict.message}")
            return conflict
        }
        var working = original.copy()
        val previousId = nextId
        val previousFills = pendingTradeFills.size
        val result = try {
            require(requestId.isNotBlank() && requestId.length <= 128) { "requestId 须为 1..128 个非空字符" }
            val outcome = when (command.kind) {
                CommandKind.PLACE -> place(working, command.order!!)
                CommandKind.CLOSE -> {
                    val position = working.positions.firstOrNull { it.id == command.entityId }
                        ?: throw IllegalArgumentException("仓位已关闭或不属于当前账户")
                    val quote = freshQuote(position.symbol, currentMarket())
                    val trade = close(working, position, closePrice(position.side, quote), "手动平仓")
                    TradeResult(true, "已平仓", trade.id)
                }
                CommandKind.CANCEL -> {
                    val order = working.orders.firstOrNull { it.id == command.entityId }
                        ?: throw IllegalArgumentException("挂单不存在或不属于当前账户")
                    working.orders.remove(order)
                    event(working, "撤单 ${order.id}")
                    TradeResult(true, "已撤单", order.id)
                }
                CommandKind.MODIFY_ORDER, CommandKind.PROTECT_ORDER -> {
                    val index = working.orders.indexOfFirst { it.id == command.entityId }
                    require(index >= 0) { "挂单已成交、已撤销或不属于当前账户" }
                    val order = working.orders[index]
                    require(order.version == command.expectedVersion) { "挂单版本已过期，请刷新后重试" }
                    val entry = command.kind == CommandKind.MODIFY_ORDER
                    val updated = if (entry) order.request.copy(entryPrice = requireNotNull(command.entryPrice))
                        else order.request.copy(stopLoss = command.stopLoss, takeProfit = command.takeProfit)
                    val available = accountSnapshot(working).copy(orders = working.orders.filterNot { it.id == order.id })
                    terms(updated, available, currentMarket())
                    working.orders[index] = order.copy(request = updated, version = checkedAdd(order.version, 1))
                    event(working, "${if (entry) "修改挂单入场价" else "修改挂单止损/止盈"} ${order.id}")
                    TradeResult(true, if (entry) "挂单入场价已更新" else "挂单止损/止盈已更新", order.id)
                }
                CommandKind.PROTECT -> {
                    val index = working.positions.indexOfFirst { it.id == command.entityId }
                    require(index >= 0) { "仓位已关闭或不属于当前账户" }
                    val position = working.positions[index]
                    require(position.version == command.expectedVersion) { "仓位版本已过期，请刷新后重试" }
                    val quote = freshQuote(position.symbol, currentMarket())
                    validateProtection(bySymbol.getValue(position.symbol), position.side,
                        closePrice(position.side, quote), command.stopLoss, command.takeProfit)
                    working.positions[index] = position.copy(stopLoss = command.stopLoss, takeProfit = command.takeProfit,
                        version = checkedAdd(position.version, 1))
                    event(working, "修改止损/止盈 ${position.id}")
                    TradeResult(true, "止损/止盈已更新", position.id)
                }
            }
            stopOut(working)
            accountSnapshot(working) // Check derived arithmetic before committing any mutation.
            outcome
        } catch (e: IllegalArgumentException) {
            working = original.copy()
            nextId = previousId
            while (pendingTradeFills.size > previousFills) pendingTradeFills.removeAt(pendingTradeFills.lastIndex)
            TradeResult(false, e.message ?: "无效交易请求")
        }
        if (!result.accepted) event(working, "拒绝：${result.message}")
        working.commands[requestId] = CommandRecord(command, result, now)
        ledgers[accountId] = working
        return result
    }

    private fun place(ledger: Ledger, request: OrderRequest): TradeResult {
        val terms = terms(request, accountSnapshot(ledger), currentMarket())
        return if (request.kind == OrderKind.MARKET) {
            val position = open(ledger, request, terms)
            TradeResult(true, "市价单已成交", position.id)
        } else {
            require(ledger.orders.size < MAX_ACTIVE) { "挂单数量达到演示上限" }
            val order = PendingOrder(id("order"), request, now)
            ledger.orders.add(order)
            event(ledger, "挂单 ${order.id} ${request.symbol} ${request.kind}（不预占保证金）")
            TradeResult(true, "挂单已提交，触发时重新校验保证金", order.id)
        }
    }

    private fun terms(request: OrderRequest, account: AccountSnapshot, market: MarketSnapshot, filling: Boolean = false): Terms {
        val instrument = bySymbol[request.symbol] ?: throw IllegalArgumentException("未知品种：${request.symbol}")
        validateVolume(instrument, request.lotsHundredths)
        val quote = freshQuote(request.symbol, market)
        val executable = entryPrice(request.side, quote)
        val distance = checkedMultiply(instrument.minStopTicks, instrument.tickSize)
        val pending = request.kind != OrderKind.MARKET
        if (!pending) require(request.entryPrice == null) { "市价单不可指定入场价" }
        val requested = if (pending) request.entryPrice ?: throw IllegalArgumentException("挂单必须指定入场价") else executable
        validatePrice(instrument, requested)
        if (pending && !filling) {
            val valid = when {
                request.side == Side.BUY && request.kind == OrderKind.LIMIT -> requested <= checkedAdd(quote.ask, -distance)
                request.side == Side.SELL && request.kind == OrderKind.LIMIT -> requested >= checkedAdd(quote.bid, distance)
                request.side == Side.BUY -> requested >= checkedAdd(quote.ask, distance)
                else -> requested <= checkedAdd(quote.bid, -distance)
            }
            require(valid) { "挂单方向或最小距离无效" }
            require(account.orders.size < MAX_ACTIVE) { "挂单数量达到演示上限" }
        }
        val entry = if (filling) executable else requested
        val close = closePrice(request.side, quote)
        // Pending protection is initially relative to the requested entry. At a trigger,
        // unhit protection must still satisfy the current minimum distance. Already crossed
        // protection is allowed so a gap fills and exits at real quotes in this same tick.
        validateProtection(instrument, request.side, if (pending && !filling) requested else close,
            request.stopLoss, request.takeProfit, allowCrossed = filling)
        val margin = requiredMargin(instrument, request.lotsHundredths, entry)
        val fee = commission(instrument, request.lotsHundredths)
        checkedMultiply(fee, 2)
        for (price in listOfNotNull(request.stopLoss, request.takeProfit)) {
            checkedAdd(grossPnl(instrument, request.side, request.lotsHundredths, entry, price), -checkedMultiply(fee, 2))
        }
        if (!pending || filling) {
            require(account.positions.size < MAX_ACTIVE) { "仓位数量达到演示上限" }
            val spreadPnl = grossPnl(instrument, request.side, request.lotsHundredths, entry, close)
            val projectedEquity = checkedAdd(checkedAdd(account.equityMicros, -fee), spreadPnl)
            val totalMargin = checkedAdd(account.marginMicros, margin)
            require(projectedEquity >= totalMargin) { "可用保证金不足（已计入点差与开仓手续费）" }
        }
        return Terms(instrument, entry, margin, fee)
    }

    private fun validateProtection(instrument: Instrument, side: Side, reference: Long,
        stopLoss: Long?, takeProfit: Long?, allowCrossed: Boolean = false,
    ) {
        val distance = checkedMultiply(instrument.minStopTicks, instrument.tickSize)
        stopLoss?.let {
            validatePrice(instrument, it)
            val crossed = if (side == Side.BUY) reference <= it else reference >= it
            val distant = if (side == Side.BUY) it <= checkedAdd(reference, -distance) else it >= checkedAdd(reference, distance)
            require(distant || (allowCrossed && crossed)) { "止损方向或最小距离无效" }
        }
        takeProfit?.let {
            validatePrice(instrument, it)
            val crossed = if (side == Side.BUY) reference >= it else reference <= it
            val distant = if (side == Side.BUY) it >= checkedAdd(reference, distance) else it <= checkedAdd(reference, -distance)
            require(distant || (allowCrossed && crossed)) { "止盈方向或最小距离无效" }
        }
        if (stopLoss != null && takeProfit != null) {
            require(if (side == Side.BUY) stopLoss < takeProfit else stopLoss > takeProfit) { "止损/止盈顺序无效" }
        }
    }

    private fun open(ledger: Ledger, request: OrderRequest, terms: Terms): Position {
        val balance = checkedAdd(ledger.balance, -terms.fee)
        val position = Position(id("position"), request.symbol, request.side, request.lotsHundredths, terms.entry, now,
            request.stopLoss, request.takeProfit, terms.margin, terms.fee)
        ledger.balance = balance
        ledger.positions.add(position)
        event(ledger, "成交 ${position.id} ${request.symbol} ${request.side}")
        pendingTradeFills.add(TradeFill(ledger.accountId, "entry:$soundEpoch:${position.id}", position.id, OrderRole.ENTRY))
        return position
    }

    private fun close(ledger: Ledger, position: Position, exit: Long, reason: String): ClosedTrade {
        val instrument = bySymbol.getValue(position.symbol)
        val gross = grossPnl(instrument, position.side, position.lotsHundredths, position.entryPrice, exit)
        val cash = checkedAdd(gross, -commission(instrument, position.lotsHundredths))
        val net = checkedAdd(cash, -position.entryFeeMicros)
        val balance = checkedAdd(ledger.balance, cash)
        val archived = if (ledger.trades.size == MAX_TRADES)
            checkedAdd(ledger.archivedNetPnl, requireNotNull(ledger.trades.first().netPnlMicros)) else ledger.archivedNetPnl
        val trade = ClosedTrade(id("trade"), position.id, position.symbol, position.side, position.lotsHundredths,
            position.entryPrice, exit, position.openedAtMs, now, net, reason, grossPnlMicros = gross, feesMicros = gross - net)
        ledger.balance = balance
        ledger.positions.removeAll { it.id == position.id }
        if (ledger.trades.size == MAX_TRADES) ledger.trades.removeAt(0)
        ledger.archivedNetPnl = archived
        ledger.trades.add(trade)
        event(ledger, "$reason ${position.id}")
        val role = when (reason) { "止损" -> OrderRole.STOP_LOSS; "止盈" -> OrderRole.TAKE_PROFIT; else -> null }
        if (role != null) pendingTradeFills.add(TradeFill(ledger.accountId, "$soundEpoch:${position.id}", trade.id, role))
        return trade
    }

    private fun match(updated: Set<String>) {
        if (!connected) return
        for (ledger in ledgers.values) {
            protect(ledger, updated)
            for (order in ledger.orders.toList()) {
                val request = order.request
                if (request.symbol !in updated) continue
                val quote = quotes.getValue(request.symbol)
                if (!fresh(quote)) continue
                val price = request.entryPrice!!
                val triggered = when {
                    request.side == Side.BUY && request.kind == OrderKind.LIMIT -> quote.ask <= price
                    request.side == Side.SELL && request.kind == OrderKind.LIMIT -> quote.bid >= price
                    request.side == Side.BUY -> quote.ask >= price
                    else -> quote.bid <= price
                }
                if (!triggered) continue
                ledger.orders.remove(order)
                try {
                    val terms = terms(request, accountSnapshot(ledger), currentMarket(), filling = true)
                    val position = open(ledger, request, terms)
                    event(ledger, "挂单 ${order.id} 触发为 ${position.id}")
                } catch (e: IllegalArgumentException) {
                    event(ledger, "挂单 ${order.id} 触发被拒绝：${e.message}")
                }
            }
            protect(ledger, updated) // Includes newly filled orders and gap-through protection.
            stopOut(ledger)
        }
    }

    private fun protect(ledger: Ledger, updated: Set<String>) {
        for (position in ledger.positions.toList()) {
            if (position.symbol !in updated) continue
            val quote = quotes.getValue(position.symbol)
            if (!fresh(quote)) continue
            val exit = closePrice(position.side, quote)
            val stop = position.stopLoss?.let { if (position.side == Side.BUY) exit <= it else exit >= it } == true
            val target = position.takeProfit?.let { if (position.side == Side.BUY) exit >= it else exit <= it } == true
            if (stop || target) close(ledger, position, exit, if (stop) "止损" else "止盈")
        }
    }

    private fun stopOut(ledger: Ledger) {
        if (!connected) return
        while (ledger.positions.isNotEmpty()) {
            val account = accountSnapshot(ledger)
            // Integer comparison is exactly equity * 2 <= margin, without multiplication overflow.
            if (account.marginMicros == 0L || account.equityMicros > account.marginMicros / 2) return
            val worst = ledger.positions.filter { fresh(quotes.getValue(it.symbol)) }
                .minWithOrNull(compareBy<Position> {
                    positionPnl(bySymbol.getValue(it.symbol), it, quotes.getValue(it.symbol))
                }.thenBy { it.openedAtMs }.thenBy { it.id }) ?: return
            close(ledger, worst, closePrice(worst.side, quotes.getValue(worst.symbol)), "强平（保证金比例 ≤ 50%）")
        }
    }

    private fun fresh(quote: Quote): Boolean = quote.timeMs <= now && now - quote.timeMs <= settings.staleAfterMs
    private fun freshQuote(symbol: String, market: MarketSnapshot): Quote {
        require(market.connected) { "行情已断开" }
        val quote = market.quotes[symbol] ?: throw IllegalArgumentException("暂无报价")
        require(quote.timeMs <= market.simulatedTimeMs && market.simulatedTimeMs - quote.timeMs <= settings.staleAfterMs) { "报价已过期" }
        return quote
    }

    private fun generateTick(deltaMs: Long) {
        now = checkedAdd(now, deltaMs)
        if (connected) {
            for (instrument in instruments) {
                val old = quotes.getValue(instrument.symbol)
                val volatility = settings.volatilityTicks.getValue(instrument.symbol)
                val movement = nextRandom(volatility * 2 + 1) - volatility
                val bid = maxOf(instrument.tickSize, checkedAdd(old.bid, checkedMultiply(movement.toLong(), instrument.tickSize)))
                val ask = checkedAdd(bid, checkedMultiply(settings.spreadTicks.getValue(instrument.symbol), instrument.tickSize))
                val quote = Quote(bid, ask, now)
                validateQuote(instrument, quote)
                appendTick(instrument.symbol, quote)
            }
            match(bySymbol.keys)
        }
        revision = checkedAdd(revision, 1)
    }

    private fun nextRandom(bound: Int): Int {
        // xorshift32: intentional bitwise wrap, identical on every Kotlin target.
        rng = rng xor (rng shl 13)
        rng = rng xor (rng ushr 17)
        rng = rng xor (rng shl 5)
        return (rng.toUInt() % bound.toUInt()).toInt()
    }

    private fun appendTick(symbol: String, quote: Quote) {
        quotes[symbol] = quote
        val history = ticks.getValue(symbol)
        history.add(quote)
        if (history.size > MAX_TICKS) history.removeAt(0)
    }

    private fun initialize(seedTrades: Boolean) {
        now = settings.startTimeMs
        rng = settings.randomSeed
        nextId = 1
        revision = 0
        connected = true
        quotes.clear()
        ticks.clear()
        ledgers.clear()
        for (account in accounts) ledgers[account.id] = Ledger(account.id, settings.startingBalanceMicros)
        for (instrument in instruments) {
            val bid = settings.initialBids.getValue(instrument.symbol)
            val quote = Quote(bid, checkedAdd(bid, checkedMultiply(settings.spreadTicks.getValue(instrument.symbol), instrument.tickSize)), now)
            quotes[instrument.symbol] = quote
            ticks[instrument.symbol] = mutableListOf(quote)
        }
        // Trades are actually executed amid this one ordered tick stream, never invented
        // from OHLC bars. seedTrades changes the ledger only, not the generated price path.
        val seedPositions = mutableMapOf<String, String>()
        for (step in 1..5_760) {
            generateTick(15_000)
            if (!seedTrades) continue
            if (step == 5_400 || step == 5_560 || step == 5_730) {
                for ((index, account) in accounts.withIndex()) {
                    val instrument = instruments[(index + if (step == 5_560) 1 else 0) % instruments.size]
                    val lots = maxOf(instrument.minLotsHundredths, 10 / instrument.lotStepHundredths * instrument.lotStepHundredths)
                    val side = if ((index + step / 10) % 2 == 0) Side.BUY else Side.SELL
                    val result = execute(account.id, "demo-seed-open-$step", Command(CommandKind.PLACE,
                        order = OrderRequest(instrument.symbol, side, OrderKind.MARKET, lots)))
                    if (result.accepted) seedPositions[account.id] = result.entityId!!
                    event(ledgers.getValue(account.id), "演示数据：通过模拟撮合生成的示例交易")
                }
            }
            if (step == 5_480 || step == 5_640) {
                for (account in accounts) seedPositions.remove(account.id)?.let {
                    execute(account.id, "demo-seed-close-$step", Command(CommandKind.CLOSE, entityId = it))
                }
            }
        }
        if (seedTrades) {
            for (account in accounts) {
                for (position in ledgers.getValue(account.id).positions.toList()) {
                    val spec = bySymbol.getValue(position.symbol)
                    val reference = closePrice(position.side, quotes.getValue(position.symbol))
                    val distance = checkedMultiply(maxOf(spec.minStopTicks * 6, settings.volatilityTicks.getValue(position.symbol) * 20L), spec.tickSize)
                    if (reference <= distance * 2) continue
                    execute(account.id, "demo-seed-protect-${position.id}", Command(CommandKind.PROTECT,
                        entityId = position.id, expectedVersion = position.version,
                        stopLoss = if (position.side == Side.BUY) reference - distance else reference + distance,
                        takeProfit = if (position.side == Side.BUY) reference + distance * 2 else reference - distance * 2))
                }
            }
        }
    }

    private fun currentMarket() = MarketSnapshot(quotes.toMap(), now, revision, connected)

    private fun accountSnapshot(ledger: Ledger, feed: Map<String, Quote> = quotes): AccountSnapshot {
        var margin = 0L
        var floating = 0L
        for (position in ledger.positions) {
            margin = checkedAdd(margin, position.marginMicros)
            floating = checkedAdd(floating, positionPnl(bySymbol.getValue(position.symbol), position, feed.getValue(position.symbol)))
        }
        val equity = checkedAdd(ledger.balance, floating)
        checkedAdd(equity, -margin) // AccountSnapshot.freeMarginMicros must also be representable.
        return AccountSnapshot(ledger.accountId, ledger.balance, equity, margin,
            ledger.positions.toList(), ledger.orders.toList(), ledger.trades.toList(), ledger.events.toList())
    }

    private fun publish() {
        val saved = StoredBroker(SCHEMA, settings.id, settings, currentMarket(), rng, nextId,
            ticks.mapValues { it.value.toList() }, ledgers.values.map {
                StoredAccount(accountSnapshot(it), it.archivedNetPnl, it.commands.toMap())
            })
        committed.value = saved
        marketFlow.value = saved.market
        for (account in saved.accounts) accountFlows.getValue(account.state.accountId).value = account.state
        // Notify only after the transaction commits, never during a rollback or snapshot restore.
        for (fill in pendingTradeFills) {
            val state = saved.accounts.first { it.state.accountId == fill.accountId }.state
            val committedFill = if (fill.role == OrderRole.ENTRY) {
                state.positions.any { it.id == fill.executionId } || state.trades.any { it.positionId == fill.executionId }
            } else state.trades.any { it.id == fill.executionId }
            if (committedFill) tradeEvents.tryEmit(fill)
        }
        pendingTradeFills.clear()
    }

    private fun load(saved: StoredBroker) {
        now = saved.market.simulatedTimeMs
        rng = saved.rng
        nextId = saved.nextId
        revision = saved.market.revision
        connected = saved.market.connected
        quotes.clear()
        quotes.putAll(saved.market.quotes)
        ticks.clear()
        for ((symbol, history) in saved.ticks) ticks[symbol] = history.toMutableList()
        ledgers.clear()
        for (account in saved.accounts) ledgers[account.state.accountId] = Ledger.from(account)
    }

    private fun event(ledger: Ledger, message: String) {
        ledger.events.add(ActivityEvent(now, message))
        if (ledger.events.size > MAX_EVENTS) ledger.events.removeAt(0)
    }

    private fun id(kind: String): String {
        val value = "${settings.id}:$kind:$nextId"
        nextId = checkedAdd(nextId, 1)
        return value
    }

    private fun validateConfig() {
        require(settings.id.isNotBlank() && ':' !in settings.id)
        require(accounts.isNotEmpty() && accounts.map { it.id }.distinct().size == accounts.size)
        require(accounts.all { it.id.isNotBlank() && it.currency == "USD" })
        require(instruments.isNotEmpty() && bySymbol.size == instruments.size)
        require(settings.startingBalanceMicros > 0 && settings.startTimeMs >= 0 && settings.staleAfterMs > 0)
        require(settings.randomSeed != 0)
        require(settings.initialBids.keys == bySymbol.keys && settings.spreadTicks.keys == bySymbol.keys && settings.volatilityTicks.keys == bySymbol.keys)
        for (instrument in instruments) {
            require(instrument.symbol.isNotBlank() && instrument.currency == "USD" && instrument.priceDecimals in 0..8)
            require(instrument.tickSize > 0 && instrument.tickValueMicrosPerLot > 0 && instrument.leverage > 0)
            require(instrument.lotStepHundredths > 0 && instrument.minLotsHundredths > 0 && instrument.maxLotsHundredths >= instrument.minLotsHundredths)
            require(instrument.minLotsHundredths % instrument.lotStepHundredths == 0L)
            require(instrument.minStopTicks > 0 && instrument.commissionMicrosPerLot >= 0)
            checkedMultiply(commission(instrument, instrument.maxLotsHundredths), 2)
            checkedMultiply(instrument.minStopTicks, instrument.tickSize)
            require(settings.spreadTicks.getValue(instrument.symbol) >= 0)
            require(settings.volatilityTicks.getValue(instrument.symbol) in 1..1_000_000)
            val bid = settings.initialBids.getValue(instrument.symbol)
            validateQuote(instrument, Quote(bid, checkedAdd(bid,
                checkedMultiply(settings.spreadTicks.getValue(instrument.symbol), instrument.tickSize)), settings.startTimeMs))
        }
    }

    private fun validateVolume(instrument: Instrument, lots: Long) {
        require(lots in instrument.minLotsHundredths..instrument.maxLotsHundredths && lots % instrument.lotStepHundredths == 0L) { "数量不符合最小值、最大值或步长" }
    }

    private fun validatePrice(instrument: Instrument, price: Long) {
        require(price > 0 && price % instrument.tickSize == 0L) { "价格必须为正数且符合最小变动单位" }
        // Bound each maximum-size notional so a full demo book has arithmetic headroom.
        val notional = checkedMultiply(checkedMultiply(price / instrument.tickSize, instrument.tickValueMicrosPerLot), instrument.maxLotsHundredths) / 100
        require(notional <= Long.MAX_VALUE / (MAX_ACTIVE * 4L)) { "价格超过演示范围" }
    }

    private fun validateQuote(instrument: Instrument, quote: Quote) {
        validatePrice(instrument, quote.bid)
        validatePrice(instrument, quote.ask)
        require(quote.ask >= quote.bid && quote.timeMs >= 0) { "Bid/Ask 或报价时间无效" }
    }

    private fun validateRestore(saved: StoredBroker) {
        require(saved.schema == SCHEMA) { "不兼容的快照版本" }
        require(saved.brokerId == settings.id && saved.configuration == settings) { "交易商身份或合约配置不匹配" }
        val time = saved.market.simulatedTimeMs
        require(time >= settings.startTimeMs && saved.market.revision >= 0 && saved.rng != 0 && saved.nextId > 0)
        require(saved.market.quotes.keys == bySymbol.keys && saved.ticks.keys == bySymbol.keys)
        for (instrument in instruments) {
            val history = saved.ticks.getValue(instrument.symbol)
            require(history.isNotEmpty() && history.size <= MAX_TICKS)
            var previousTime = settings.startTimeMs
            for (quote in history) {
                validateQuote(instrument, quote)
                require(quote.timeMs in previousTime..time) { "行情历史时间无序" }
                previousTime = quote.timeMs
            }
            require(history.last() == saved.market.quotes.getValue(instrument.symbol)) { "最新报价与历史不一致" }
        }
        require(saved.accounts.map { it.state.accountId } == accounts.map { it.id }) { "账户身份不匹配" }
        val entityIds = mutableSetOf<String>()
        val sequences = mutableMapOf<Long, String>()
        fun checkId(value: String, kind: String, unique: Boolean = true) {
            val prefix = "${settings.id}:$kind:"
            require(value.startsWith(prefix)) { "实体 ID 不属于当前交易商" }
            val sequence = value.removePrefix(prefix).toLongOrNull()
            require(sequence != null && sequence > 0 && sequence < saved.nextId && value == "$prefix$sequence") { "实体 ID 序列无效" }
            require(sequences[sequence] == null || sequences[sequence] == value) { "实体复用了 ID 序号" }
            sequences[sequence] = value
            if (unique) require(entityIds.add(value)) { "重复实体 ID" }
        }
        fun specification(symbol: String) = bySymbol[symbol] ?: throw IllegalArgumentException("快照包含未知品种")
        fun checkProtection(instrument: Instrument, side: Side, sl: Long?, tp: Long?) {
            sl?.let { validatePrice(instrument, it) }
            tp?.let { validatePrice(instrument, it) }
            if (sl != null && tp != null) require(if (side == Side.BUY) sl < tp else sl > tp)
        }
        for (account in saved.accounts) {
            val state = account.state
            require(state.positions.size <= MAX_ACTIVE && state.orders.size <= MAX_ACTIVE)
            require(state.trades.size <= MAX_TRADES && state.events.size <= MAX_EVENTS)
            require(account.archivedNetPnl == 0L || state.trades.size == MAX_TRADES) { "归档账本不完整" }
            var balance = checkedAdd(settings.startingBalanceMicros, account.archivedNetPnl)
            for (position in state.positions) {
                checkId(position.id, "position")
                val instrument = specification(position.symbol)
                validateVolume(instrument, position.lotsHundredths)
                validatePrice(instrument, position.entryPrice)
                require(position.openedAtMs in settings.startTimeMs..time && position.version > 0)
                require(position.marginMicros == requiredMargin(instrument, position.lotsHundredths, position.entryPrice))
                require(position.entryFeeMicros == commission(instrument, position.lotsHundredths))
                checkProtection(instrument, position.side, position.stopLoss, position.takeProfit)
                balance = checkedAdd(balance, -position.entryFeeMicros)
            }
            for (order in state.orders) {
                checkId(order.id, "order")
                val request = order.request
                val instrument = specification(request.symbol)
                require(request.kind != OrderKind.MARKET && order.createdAtMs in settings.startTimeMs..time && order.version > 0)
                validateVolume(instrument, request.lotsHundredths)
                val entry = requireNotNull(request.entryPrice)
                validatePrice(instrument, entry)
                validateProtection(instrument, request.side, entry, request.stopLoss, request.takeProfit)
                requiredMargin(instrument, request.lotsHundredths, entry)
            }
            var lastClose = settings.startTimeMs
            for (trade in state.trades) {
                checkId(trade.id, "trade")
                checkId(trade.positionId, "position")
                val instrument = specification(trade.symbol)
                validateVolume(instrument, trade.lotsHundredths)
                validatePrice(instrument, trade.entryPrice)
                validatePrice(instrument, trade.exitPrice)
                require(trade.openedAtMs in settings.startTimeMs..trade.closedAtMs && trade.closedAtMs in lastClose..time)
                lastClose = trade.closedAtMs
                val net = checkedAdd(grossPnl(instrument, trade.side, trade.lotsHundredths, trade.entryPrice, trade.exitPrice),
                    -checkedMultiply(commission(instrument, trade.lotsHundredths), 2))
                require(net == trade.netPnlMicros) { "成交净盈亏与费用不一致" }
                balance = checkedAdd(balance, requireNotNull(trade.netPnlMicros))
            }
            require(balance == state.balanceMicros) { "余额与账本不一致" }
            val recomputed = accountSnapshot(Ledger.from(account), saved.market.quotes)
            require(recomputed == state) { "净值或保证金与仓位不一致" }
            var lastEvent = settings.startTimeMs
            for (event in state.events) {
                require(event.timeMs in lastEvent..time)
                lastEvent = event.timeMs
            }
            for ((requestId, record) in account.commands) {
                require(record.timeMs in settings.startTimeMs..time)
                val command = record.command
                if (command.kind != CommandKind.MODIFY_ORDER) require(command.entryPrice == null)
                when (command.kind) {
                    CommandKind.PLACE -> require(command.order != null && command.entityId == null && command.expectedVersion == null && command.stopLoss == null && command.takeProfit == null)
                    CommandKind.CLOSE, CommandKind.CANCEL -> require(command.order == null && command.entityId != null && command.expectedVersion == null && command.stopLoss == null && command.takeProfit == null)
                    CommandKind.PROTECT, CommandKind.PROTECT_ORDER -> require(command.order == null && command.entityId != null && command.expectedVersion != null)
                    CommandKind.MODIFY_ORDER -> require(command.order == null && command.entityId != null && command.expectedVersion != null && command.entryPrice != null && command.stopLoss == null && command.takeProfit == null)
                }
                if (record.result.accepted) {
                    require(requestId.isNotBlank() && requestId.length <= 128)
                    val kind = when (command.kind) {
                        CommandKind.PLACE -> if (command.order!!.kind == OrderKind.MARKET) "position" else "order"
                        CommandKind.CLOSE -> "trade"
                        CommandKind.CANCEL, CommandKind.MODIFY_ORDER, CommandKind.PROTECT_ORDER -> "order"
                        CommandKind.PROTECT -> "position"
                    }
                    checkId(requireNotNull(record.result.entityId), kind, unique = false)
                    when (command.kind) {
                        CommandKind.CLOSE -> {
                            checkId(requireNotNull(command.entityId), "position", unique = false)
                            state.trades.firstOrNull { it.id == record.result.entityId }?.let {
                                require(it.positionId == command.entityId)
                            }
                        }
                        CommandKind.CANCEL, CommandKind.PROTECT, CommandKind.MODIFY_ORDER, CommandKind.PROTECT_ORDER -> require(record.result.entityId == command.entityId)
                        CommandKind.PLACE -> Unit
                    }
                    command.order?.let {
                        val instrument = specification(it.symbol)
                        validateVolume(instrument, it.lotsHundredths)
                        if (it.kind == OrderKind.MARKET) require(it.entryPrice == null)
                        else validatePrice(instrument, requireNotNull(it.entryPrice))
                        checkProtection(instrument, it.side, it.stopLoss, it.takeProfit)
                    }
                    if (command.kind in setOf(CommandKind.PROTECT, CommandKind.MODIFY_ORDER, CommandKind.PROTECT_ORDER)) require(command.expectedVersion!! > 0)
                    if (command.kind == CommandKind.MODIFY_ORDER) require(command.entryPrice!! > 0)
                } else require(record.result.entityId == null)
            }
        }
    }
}

private const val SCHEMA = 1
private const val MAX_ACTIVE = 200
private const val MAX_TRADES = 1_000
private const val MAX_EVENTS = 200
private const val MAX_TICKS = 11_521
private val SNAPSHOT_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
private fun entryPrice(side: Side, quote: Quote) = if (side == Side.BUY) quote.ask else quote.bid
private fun closePrice(side: Side, quote: Quote) = if (side == Side.BUY) quote.bid else quote.ask
private data class Terms(val instrument: Instrument, val entry: Long, val margin: Long, val fee: Long)

@Serializable
private enum class CommandKind { PLACE, CLOSE, CANCEL, PROTECT, MODIFY_ORDER, PROTECT_ORDER }
@Serializable
private data class Command(val kind: CommandKind, val order: OrderRequest? = null, val entityId: String? = null,
    val expectedVersion: Long? = null, val stopLoss: Long? = null, val takeProfit: Long? = null, val entryPrice: Long? = null)
@Serializable
private data class CommandRecord(val command: Command, val result: TradeResult, val timeMs: Long)
@Serializable
private data class StoredAccount(val state: AccountSnapshot, val archivedNetPnl: Long, val commands: Map<String, CommandRecord>)
@Serializable
private data class StoredBroker(val schema: Int, val brokerId: String, val configuration: DemoConfig,
    val market: MarketSnapshot, val rng: Int, val nextId: Long, val ticks: Map<String, List<Quote>>, val accounts: List<StoredAccount>)

private class Ledger(val accountId: String, var balance: Long, var archivedNetPnl: Long = 0,
    val positions: MutableList<Position> = mutableListOf(), val orders: MutableList<PendingOrder> = mutableListOf(),
    val trades: MutableList<ClosedTrade> = mutableListOf(), val events: MutableList<ActivityEvent> = mutableListOf(),
    val commands: MutableMap<String, CommandRecord> = linkedMapOf(),
) {
    fun copy() = Ledger(accountId, balance, archivedNetPnl, positions.toMutableList(), orders.toMutableList(),
        trades.toMutableList(), events.toMutableList(), commands.toMutableMap())
    companion object {
        fun from(saved: StoredAccount) = Ledger(saved.state.accountId, saved.state.balanceMicros, saved.archivedNetPnl,
            saved.state.positions.toMutableList(), saved.state.orders.toMutableList(), saved.state.trades.toMutableList(),
            saved.state.events.toMutableList(), saved.commands.toMutableMap())
    }
}
