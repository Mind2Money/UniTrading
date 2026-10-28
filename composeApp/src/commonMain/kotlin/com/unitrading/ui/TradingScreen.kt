package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.TradingChart
import com.unitrading.tradovate.TradovateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun UniTradingApp(store: SnapshotStore, login: TradovateLogin? = null) {
    val runtime = remember(store) { TradingRuntime(store) }
    TradingScreen(runtime, login)
}

@Composable
fun TradingScreen(runtime: TradingRuntime, login: TradovateLogin? = null) {
    TradingTheme {
        val registry = runtime.registry
        val plugins by registry.state.collectAsState()
        var brokerId by rememberSaveable { mutableStateOf(plugins.first().descriptor.id) }
        val plugin = plugins.firstOrNull { it.descriptor.id == brokerId } ?: plugins.first()
        val catalog by key(plugin) { plugin.catalog.collectAsState() }
        var showConnection by remember { mutableStateOf(false) }
        if (showConnection) TradovateConnectionDialog(runtime, login, { brokerId = it }, { showConnection = false })
        if (catalog.accounts.isEmpty() || catalog.instruments.isEmpty()) {
            val feed by key(plugin) { plugin.market.collectAsState() }
            val emptyScope = rememberCoroutineScope()
            var query by remember(plugin) { mutableStateOf("MES") }
            var searchError by remember(plugin) { mutableStateOf<String?>(null) }
            Surface(Modifier.fillMaxSize(), color = TradingColors.Shell) {
                Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("UniTrading · ${plugin.descriptor.name}", fontSize = 20.sp)
                    Text(feed.statusMessage, color = TradingColors.Muted)
                    Text("连接后由交易商提供账户和合约。空列表不会生成本地替代数据。", fontSize = 12.sp)
                    BrokerAccountChooser(registry, plugin, "", { brokerId = it }, {})
                    OutlinedTextField(query, { query = it }, label = { Text("搜索服务器合约，如 MES") }, singleLine = true)
                    Button({ emptyScope.launch { try { plugin.searchInstruments(query) } catch (_: Exception) { searchError = "合约搜索失败，请检查连接和权限" } } }, enabled = feed.connected) { Text("查询合约") }
                    searchError?.let { Text(it, color = TradingColors.Loss) }
                    TextButton({ showConnection = true }) { Text("连接 / 重新登录 Tradovate") }
                    TextButton({ plugin.disconnect(); brokerId = plugins.first().descriptor.id }) { Text("返回本地 Demo") }
                }
            }
            return@TradingTheme
        }
        var chosenAccountId by remember(plugin) { mutableStateOf(catalog.accounts.first().id) }
        val accountId = chosenAccountId.takeIf { id -> catalog.accounts.any { it.id == id } } ?: catalog.accounts.first().id
        val localDemo = plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO
        val session = remember(plugin, accountId) { plugin.openSession(accountId) }
        val account by key(session) { session.state.collectAsState() }
        val market by key(plugin) { plugin.market.collectAsState() }
        val paused by runtime.paused.collectAsState()
        val startupError by runtime.startupError.collectAsState()
        val storageError by runtime.storageError.collectAsState()
        val accountKey = "$brokerId/$accountId/${plugin.hashCode()}"
        var symbol by remember(plugin) { mutableStateOf(catalog.instruments.first().symbol) }
        val instrument = plugin.instruments.first { it.symbol == symbol }
        val quote = market.quotes[symbol]
        var timeframe by remember { mutableStateOf(Timeframe.M5) }
        var draft by remember(accountKey, symbol) { mutableStateOf(OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10")) }
        var showDraft by remember(accountKey, symbol) { mutableStateOf(false) }
        var selectedPositionId by remember(accountKey) { mutableStateOf<String?>(null) }
        var focusedTrade by remember(accountKey) { mutableStateOf<ClosedTrade?>(null) }
        var protection by remember(accountKey) { mutableStateOf<ProtectionEdit?>(null) }
        var pendingEdit by remember(accountKey) { mutableStateOf<PendingEntryEdit?>(null) }
        var confirmation by remember(accountKey, symbol) { mutableStateOf<Confirmation?>(null) }
        var mobileTab by remember { mutableStateOf(1) }
        var showAccounts by remember { mutableStateOf(false) }
        var showOrder by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val request = draft.request(instrument)
        val preview = remember(session, instrument, request, market.revision, account) { request?.let(session::preview) }
        val candles = remember(plugin, symbol, timeframe, market.revision) { plugin.history(symbol, timeframe) }
        val visiblePositions = account.positions.filter { it.symbol == symbol }
        val visibleTrades = account.trades.filter { it.symbol == symbol }
        val visibleOrders = account.orders.filter { it.request.symbol == symbol }

        LaunchedEffect(runtime) { runtime.run() }
        LaunchedEffect(plugin, symbol, timeframe) { plugin.watch(symbol, timeframe) }
        LaunchedEffect(accountKey) { showOrder = false; notice = null }

        fun chooseSymbol(value: String) {
            symbol = value
            focusedTrade = null
            selectedPositionId = null
            protection = null
            pendingEdit = null
            confirmation = null
            mobileTab = 1
        }
        fun chooseBroker(value: String) { if (!busy) brokerId = value }
        fun chooseAccount(value: String) { if (!busy) chosenAccountId = value }
        fun changeDraft(value: OrderDraft) { draft = value; showDraft = true }
        fun perform(action: suspend () -> TradeResult) {
            if (busy) return
            busy = true
            val origin = session.account.name
            scope.launch {
                try {
                    val result = action()
                    runtime.save()
                    notice = "$origin：${result.message}"
                    if (result.accepted) showDraft = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    notice = "操作失败：${error.message}"
                } finally { busy = false }
            }
        }
        fun editProtection(position: Position, sl: Long? = position.stopLoss, tp: Long? = position.takeProfit) {
            if (!plugin.capabilities.modifyProtection || !position.protectionEditable || busy) return
            val spec = plugin.instruments.firstOrNull { it.symbol == position.symbol }
                ?: run { notice = "品种或账户已切换，请重新选择仓位"; return }
            symbol = position.symbol
            selectedPositionId = position.id
            protection = ProtectionEdit(position, sl?.let(spec::formatPrice) ?: "", tp?.let(spec::formatPrice) ?: "")
        }

        fun editPending(order: PendingOrder, price: Long = order.request.entryPrice!!) {
            if (busy) return
            val current = account.orders.firstOrNull { it.id == order.id }
            if (current == null || current.version != order.version || current.role != order.role || !current.editable) {
                notice = "订单状态或保护关联已变化，请重新选择"
                return
            }
            if (current.isProtection) {
                val position = account.positions.firstOrNull { it.id == current.positionId && it.protectionEditable }
                if (position == null) { notice = "保护单尚未唯一关联到当前仓位"; return }
                pendingEdit = null
                editProtection(position, if (current.role == OrderRole.STOP_LOSS) price else position.stopLoss,
                    if (current.role == OrderRole.TAKE_PROFIT) price else position.takeProfit)
                return
            }
            if (current.role != OrderRole.ENTRY || !plugin.capabilities.modifyPendingOrders) return
            val spec = plugin.instruments.first { it.symbol == current.request.symbol }
            symbol = current.request.symbol
            protection = null
            showDraft = false
            pendingEdit = PendingEntryEdit(current, spec.formatPrice(price))
        }

        Surface(Modifier.fillMaxSize(), color = TradingColors.Shell) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val desktop = maxWidth >= 900.dp && maxHeight >= 600.dp
                val wide = desktop && maxWidth >= 1240.dp
                val compact = !desktop
                val compactShort = compact && maxHeight < 550.dp
                val showCompactQuotes = compactShort && maxWidth > 650.dp
                val terminalHeight = if (maxHeight > 830.dp) 235.dp else 190.dp
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(if (compactShort) 44.dp else 60.dp).padding(horizontal = if (compact) 14.dp else 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Canvas(Modifier.size(28.dp)) {
                            drawLine(TradingColors.Accent, Offset(size.width * .1f, size.height * .72f), Offset(size.width * .45f, size.height * .3f), 4f)
                            drawLine(TradingColors.Accent, Offset(size.width * .45f, size.height * .3f), Offset(size.width * .65f, size.height * .55f), 4f)
                            drawLine(TradingColors.Ink, Offset(size.width * .65f, size.height * .55f), Offset(size.width * .95f, size.height * .12f), 4f)
                        }
                        Column {
                            Text(if (compactShort) instrument.symbol else "UniTrading", fontSize = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.SemiBold)
                            if (compact) Text(session.account.name, fontSize = 10.sp, color = TradingColors.Muted, maxLines = 1)
                        }
                        Badge(if (localDemo) "本地模拟" else if (plugin.descriptor.environment == BrokerEnvironment.LIVE) "Live 实盘" else "远程 Demo")
                        Spacer(Modifier.weight(1f))
                        if (desktop) {
                            Text("${plugin.descriptor.name} / ${session.account.name} / ${plugin.descriptor.environment.label}", fontSize = 12.sp, color = TradingColors.Muted)
                            if (localDemo) TextButton({ runtime.paused.value = !paused }) { Text(if (paused) "继续行情" else "暂停行情", fontSize = 11.sp) }
                        }
                        if (showCompactQuotes) Text("Bid ${quote?.bid?.let(instrument::formatPrice) ?: "—"} / Ask ${quote?.ask?.let(instrument::formatPrice) ?: "—"}", fontSize = 11.sp, color = TradingColors.Muted)
                        TextButton({ showAccounts = true }, Modifier.testTag("open-accounts")) { Text(if (compact) "账户" else "切换账户", fontSize = 12.sp) }
                        if (desktop && !wide || compactShort) Button({ showOrder = true; showDraft = true }, Modifier.testTag("open-order")) { Text("下单") }
                    }
                    DividerLine()
                    if (!localDemo) Text(market.statusMessage, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = TradingColors.Muted, fontSize = 11.sp)
                    if (storageError != null) {
                        Row(Modifier.fillMaxWidth().background(TradingColors.Raised).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(storageError!!, Modifier.weight(1f), fontSize = 11.sp, color = TradingColors.Loss)
                            TextButton({ scope.launch { runtime.save() } }) { Text("重试保存") }
                        }
                    }
                    notice?.let { message ->
                        Row(Modifier.fillMaxWidth().background(TradingColors.Raised).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f).testTag("trade-notice"), fontSize = 11.sp)
                            TextButton({ notice = null }) { Text("关闭", fontSize = 10.sp) }
                        }
                    }
                    if (desktop) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.width(216.dp).fillMaxHeight().background(TradingColors.Shell)) {
                                BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount)
                                DividerLine()
                                MarketWatch(plugin, market, symbol, ::chooseSymbol, Modifier.weight(1f))
                            }
                            VerticalDivider(color = TradingColors.Border)
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    Column(Modifier.weight(1f).fillMaxHeight()) {
                                        InstrumentHeader(instrument, quote, false) { side -> changeDraft(draft.copy(side = side, stopLoss = "", takeProfit = "")); if (!wide) showOrder = true }
                                        TradingChart(accountKey, instrument, quote, candles, timeframe, { timeframe = it }, visiblePositions, visibleTrades,
                                            selectedPositionId, focusedTrade, request.takeIf { showDraft }, preview.takeIf { showDraft }, { p, sl, tp -> editProtection(p, sl, tp) },
                                            visibleOrders, plugin.capabilities.modifyPendingOrders, { order, price -> editPending(order, price) },
                                            { changeDraft(draft.copy(entry = instrument.formatPrice(it))) }, session::preview, session::previewOrderModification,
                                            Modifier.weight(1f), environment = plugin.descriptor.environment, executions = account.executions.filter { it.symbol == symbol })
                                    }
                                    if (wide) {
                                        VerticalDivider(color = TradingColors.Border)
                                        OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, { request?.let { confirmation = Confirmation.Place(it) } }, Modifier.width(296.dp).fillMaxHeight().background(TradingColors.Shell), environment = plugin.descriptor.environment)
                                    }
                                }
                                DividerLine()
                                TradeTerminal(plugin, account, market, selectedPositionId, false,
                                    onSelectPosition = { p -> symbol = p.symbol; selectedPositionId = p.id; focusedTrade = null },
                                    onSelectTrade = { t -> symbol = t.symbol; focusedTrade = t; selectedPositionId = null },
                                    onClose = { confirmation = Confirmation.Close(it) }, onProtect = { editProtection(it) }, onCancel = { confirmation = Confirmation.Cancel(it) }, onEditOrder = { editPending(it) }, busy = busy,
                                    modifier = Modifier.fillMaxWidth().height(terminalHeight))
                            }
                        }
                    } else {
                        when (mobileTab) {
                            0 -> MarketWatch(plugin, market, symbol, ::chooseSymbol, Modifier.weight(1f))
                            1 -> Column(Modifier.weight(1f)) {
                                if (!compactShort) InstrumentHeader(instrument, quote, true) { side -> changeDraft(draft.copy(side = side, stopLoss = "", takeProfit = "")); showOrder = true }
                                TradingChart(accountKey, instrument, quote, candles, timeframe, { timeframe = it }, visiblePositions, visibleTrades,
                                    selectedPositionId, focusedTrade, request.takeIf { showDraft }, preview.takeIf { showDraft }, { p, sl, tp -> editProtection(p, sl, tp) },
                                            visibleOrders, plugin.capabilities.modifyPendingOrders, { order, price -> editPending(order, price) },
                                            { changeDraft(draft.copy(entry = instrument.formatPrice(it))) }, session::preview, session::previewOrderModification,
                                            Modifier.weight(1f), environment = plugin.descriptor.environment, executions = account.executions.filter { it.symbol == symbol })
                                if (!compactShort) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("本品种 ${visiblePositions.size} 笔持仓", Modifier.weight(1f), color = TradingColors.Muted, fontSize = 11.sp)
                                    Button({ showOrder = true; showDraft = true }, Modifier.testTag("open-order")) { Text("新建订单") }
                                }
                            }
                            2 -> TradeTerminal(plugin, account, market, selectedPositionId, true,
                                onSelectPosition = { p -> symbol = p.symbol; selectedPositionId = p.id; focusedTrade = null; mobileTab = 1 },
                                onSelectTrade = { t -> symbol = t.symbol; focusedTrade = t; mobileTab = 1 },
                                onClose = { confirmation = Confirmation.Close(it) }, onProtect = { editProtection(it) }, onCancel = { confirmation = Confirmation.Cancel(it) }, onEditOrder = { editPending(it) }, busy = busy,
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount)
                                if (compactShort) AccountStrip(account)
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(plugin.descriptor.description, fontSize = 12.sp, color = TradingColors.Muted)
                                    if (localDemo) Text("模拟时钟每秒前进 15 秒。暂停或退出时，不补造离线成交。", fontSize = 12.sp, color = TradingColors.Muted)
                                    if (localDemo) OutlinedButton({ runtime.paused.value = !paused }) { Text(if (paused) "继续模拟行情" else "暂停模拟行情") }
                                    if (localDemo) TextButton({ confirmation = Confirmation.Reset }) { Text("重置所有模拟账户") }
                                    TextButton({ showConnection = true }) { Text("连接 Tradovate") }
                                    if (!localDemo) TextButton({ plugin.disconnect(); brokerId = plugins.first().descriptor.id }) { Text("断开 Tradovate") }
                                }
                            }
                        }
                    }
                    DividerLine()
                    if (!compactShort) AccountStrip(account)
                    if (compact) {
                        Row(Modifier.fillMaxWidth().background(TradingColors.Shell)) {
                            listOf("行情", "图表", "交易", "账户").forEachIndexed { index, title ->
                                TextButton({ mobileTab = index }, Modifier.weight(1f).height(48.dp).testTag("nav-$index")) {
                                    Text(title, color = if (mobileTab == index) TradingColors.Accent else TradingColors.Muted, fontWeight = if (mobileTab == index) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
                            Text(if (!localDemo) plugin.descriptor.environment.label else if (paused) "模拟已暂停" else "模拟行情运行中 · ×15", color = TradingColors.Muted, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (localDemo) "报价 ${clockLabel(market.simulatedTimeMs, true)} UTC   |   本地保存   |   无真实资金" else "${session.account.name}   |   服务器权威状态   |   ${plugin.descriptor.environment.label}", color = TradingColors.Muted, fontSize = 10.sp)
                        }
                    }
                }
                if (showOrder) {
                    Dialog(onDismissRequest = { if (!busy) showOrder = false }) {
                        Surface(shape = MaterialTheme.shapes.large, color = TradingColors.Surface) {
                            Column(Modifier.widthIn(max = 420.dp).fillMaxHeight(.94f).imePadding()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) { TextButton({ showOrder = false }) { Text("返回图表") } }
                                OrderTicket(instrument, quote, plugin.capabilities, draft, ::changeDraft, preview, busy, {
                                    request?.let { confirmation = Confirmation.Place(it); showOrder = false }
                                }, Modifier.weight(1f), environment = plugin.descriptor.environment)
                            }
                        }
                    }
                }
                if (showAccounts) AlertDialog(
                    onDismissRequest = { showAccounts = false },
                    title = { Text("交易商与账户") },
                    text = { Column { BrokerAccountChooser(registry, plugin, accountId, ::chooseBroker, ::chooseAccount); Text(if (localDemo) "当前账户为本地模拟。" else "${plugin.descriptor.environment.label} · 服务器账户 · 净额持仓", fontSize = 12.sp, color = TradingColors.Muted); TextButton({ showAccounts = false; showConnection = true }) { Text("连接 Tradovate") }; if (!localDemo) TextButton({ plugin.disconnect(); brokerId = plugins.first().descriptor.id; showAccounts = false }) { Text("断开 Tradovate") } } },
                    confirmButton = { TextButton({ showAccounts = false }) { Text("完成") } },
                    dismissButton = { if (localDemo) TextButton({ showAccounts = false; confirmation = Confirmation.Reset }) { Text("重置演示") } },
                )

                confirmation?.let { action ->
                    val title = when (action) { is Confirmation.Place -> if (localDemo) "确认模拟下单" else "确认 ${plugin.descriptor.environment.label} 下单"; is Confirmation.Close -> "确认平仓"; is Confirmation.Cancel -> "确认撤单"; Confirmation.Reset -> "重置全部模拟数据？" }
                    val livePreview = (action as? Confirmation.Place)?.let { session.preview(it.request) }
                    AlertDialog(
                        onDismissRequest = { if (!busy) confirmation = null },
                        title = { Text(title) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${plugin.descriptor.name} / ${session.account.name} / ${plugin.descriptor.environment.label}", color = TradingColors.Muted, fontSize = 12.sp)
                            when (action) {
                                is Confirmation.Place -> {
                                    val r = action.request
                                    Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}")
                                    EstimateRow("当前预估入场", livePreview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                                    EstimateRow("预计保证金 USD", livePreview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                                    Text("提交时按最新报价与账户状态再次校验，预估不是锁定成交价。", fontSize = 12.sp, color = TradingColors.Muted)
                                    livePreview?.errors?.forEach { Text(it, color = TradingColors.Loss, fontSize = 12.sp) }
                                }
                                is Confirmation.Close -> Text("平掉 ${action.position.symbol} 的 #${action.position.id} 全部 ${plugin.instruments.first { it.symbol == action.position.symbol }.formatQuantity(action.position.lotsHundredths)} ${plugin.instruments.first { it.symbol == action.position.symbol }.quantityUnit}，按当前可成交报价执行。")
                                is Confirmation.Cancel -> Text("撤销${action.order.role.label()} #${action.order.id}。" +
                                    if (action.order.isProtection) "这会移除该项保护，服务器可能联动关联订单。" else "若已被触发，交易商将拒绝撤单。")
                                Confirmation.Reset -> Text("将覆盖两个交易商的全部本地模拟余额、仓位、挂单和历史，并重新生成演示交易。此操作不能撤销。")
                            }
                        } },
                        confirmButton = { Button({
                            confirmation = null
                            val id = requestId()
                            when (action) {
                                is Confirmation.Place -> perform { session.placeOrder(id, action.request) }
                                is Confirmation.Close -> perform { session.closePosition(id, action.position.id) }
                                is Confirmation.Cancel -> perform { session.cancelOrder(id, action.order.id) }
                                Confirmation.Reset -> perform { runtime.resetAll(); TradeResult(true, "已重置全部本地模拟数据") }
                            }
                        }, enabled = !busy && (livePreview == null || livePreview.valid), modifier = Modifier.testTag("confirm-action")) { Text("确认") } },
                        dismissButton = { TextButton({ confirmation = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                pendingEdit?.let { edit ->
                    val order = edit.order
                    val spec = plugin.instruments.first { it.symbol == order.request.symbol }
                    val current = account.orders.firstOrNull { it.id == order.id }
                    val price = spec.parsePrice(edit.entry)
                    val estimate = price?.let { session.previewOrderModification(order.id, it) }
                    val validVersion = current != null && current.version == order.version && current.role == OrderRole.ENTRY && current.editable
                    AlertDialog(
                        modifier = Modifier.testTag("pending-entry-dialog"),
                        onDismissRequest = { if (!busy) pendingEdit = null },
                        title = { Text("修改挂单入场价") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${order.request.symbol} · ${order.request.side.label()} ${order.request.kind.name} · ${spec.formatQuantity(order.request.lotsHundredths)} ${spec.quantityUnit}", fontSize = 12.sp)
                            Text("原入场价 ${spec.formatPrice(order.request.entryPrice!!)}", color = TradingColors.Muted, fontSize = 11.sp)
                            PriceInput("新入场价", edit.entry, { pendingEdit = edit.copy(entry = it) }, "pending-entry")
                            EstimateRow("预计保证金", estimate?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} USD" } ?: "—")
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净结果" else "止损毛结果", estimate?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净结果" else "止盈毛结果", estimate?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
                            Text("仅修改入场价；原订单类型、数量、SL/TP 保持不变。确认后按最新报价重新校验。", color = TradingColors.Muted, fontSize = 11.sp)
                            if (!validVersion) Text("挂单已成交、已撤销或版本已更新，请取消后重新选择。", color = TradingColors.Loss, fontSize = 11.sp)
                            if (price == null) Text("请输入符合品种精度的有效价格。", color = TradingColors.Loss, fontSize = 11.sp)
                            estimate?.errors?.forEach { Text(it, color = TradingColors.Loss, fontSize = 11.sp) }
                        } },
                        confirmButton = { Button({
                            val newPrice = price ?: return@Button
                            pendingEdit = null
                            perform { session.modifyOrder(requestId(), order.id, order.version, newPrice) }
                        }, Modifier.testTag("confirm-pending-entry"), enabled = !busy && validVersion && estimate?.valid == true) { Text("确认改价") } },
                        dismissButton = { TextButton({ pendingEdit = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                protection?.let { edit ->
                    val p = edit.position
                    val spec = plugin.instruments.first { it.symbol == p.symbol }
                    val current = account.positions.firstOrNull { it.id == p.id }
                    val sl = if (edit.stopLoss.isBlank()) null else spec.parsePrice(edit.stopLoss)
                    val tp = if (edit.takeProfit.isBlank()) null else spec.parsePrice(edit.takeProfit)
                    val validInput = (edit.stopLoss.isBlank() || sl != null && sl > 0 && sl % spec.tickSize == 0L) && (edit.takeProfit.isBlank() || tp != null && tp > 0 && tp % spec.tickSize == 0L)
                    val validVersion = current?.version == p.version
                    fun estimate(price: Long?): String = price?.takeIf { it > 0 }?.let {
                        runCatching { "${money(grossPnl(spec, p.side, p.lotsHundredths, p.entryPrice, it) - p.entryFeeMicros - commission(spec, p.lotsHundredths), true)} USD" }.getOrDefault("超出范围")
                    } ?: "未设置"
                    AlertDialog(
                        modifier = Modifier.testTag("protection-dialog"),
                        onDismissRequest = { if (!busy) protection = null },
                        title = { Text("修改止损 / 止盈") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${p.symbol} · ${p.side.label()} ${spec.formatQuantity(p.lotsHundredths)} ${spec.quantityUnit} · #${p.id}", fontSize = 12.sp)
                            Text("原 SL ${p.stopLoss?.let(spec::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(spec::formatPrice) ?: "—"}", color = TradingColors.Muted, fontSize = 11.sp)
                            PriceInput("新止损 SL（留空移除）", edit.stopLoss, { protection = edit.copy(stopLoss = it) }, "protect-sl")
                            PriceInput("新止盈 TP（留空移除）", edit.takeProfit, { protection = edit.copy(takeProfit = it) }, "protect-tp")
                            EstimateRow(if (spec.estimatesIncludeFees) "止损净结果" else "止损毛结果", estimate(sl))
                            EstimateRow(if (spec.estimatesIncludeFees) "止盈净结果" else "止盈毛结果", estimate(tp))
                            Text("当前为预览，确认后交易商才会校验并更新。跳空时实际结果可能不同。", color = TradingColors.Muted, fontSize = 11.sp)
                            if (!validVersion) Text("仓位已关闭或保护价版本已更新，请取消后重新选择。", color = TradingColors.Loss, fontSize = 11.sp)
                            if (!validInput) Text("价格必须为正数并符合品种最小变动单位。", color = TradingColors.Loss, fontSize = 11.sp)
                        } },
                        confirmButton = { Button({
                            protection = null
                            perform { session.modifyProtection(requestId(), p.id, p.version, sl, tp) }
                        }, Modifier.testTag("confirm-protection"), enabled = validInput && validVersion && !busy) { Text("确认修改") } },
                        dismissButton = { TextButton({ protection = null }, enabled = !busy) { Text("取消") } },
                    )
                }
                if (startupError != null && confirmation !is Confirmation.Reset) AlertDialog(
                    onDismissRequest = {}, title = { Text("模拟存档无法读取") },
                    text = { Text(startupError!! + "\n请先备份原文件。继续重置会覆盖所有模拟账户。") },
                    confirmButton = { TextButton({ confirmation = Confirmation.Reset }) { Text("检查重置操作") } },
                )
            }
        }
    }
}
