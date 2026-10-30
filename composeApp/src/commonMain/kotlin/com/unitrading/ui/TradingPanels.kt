package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.chart.dateTime
import kotlinx.coroutines.launch

@Composable
internal fun BrokerAccountChooser(
    registry: BrokerRegistry,
    plugin: BrokerPlugin,
    accountId: String,
    onBroker: (String) -> Unit,
    onAccount: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 14.dp)) {
        SectionTitle("交易商与账户", "接口插件 · 独立账户")
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag("broker-picker"), shape = RoundedCornerShape(8.dp)) {
                Text(plugin.descriptor.name, Modifier.weight(1f), textAlign = TextAlign.Start, fontSize = 12.sp)
                Text("⌄")
            }
            DropdownMenu(expanded, { expanded = false }) {
                registry.plugins.forEach { broker ->
                    DropdownMenuItem(text = { Text(broker.descriptor.name) }, onClick = { expanded = false; onBroker(broker.descriptor.id) })
                }
            }
        }
        plugin.accounts.forEach { account ->
            val selected = account.id == accountId
            Row(Modifier.fillMaxWidth().clickable { onAccount(account.id) }
                .background(if (selected) TradingColors.Raised else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (selected) TradingColors.Accent else TradingColors.Border, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                    Text("${account.id} · ${account.currency}", color = TradingColors.Muted, fontSize = 10.sp)
                }
                if (selected) Text("✓", color = TradingColors.Accent)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun MarketWatch(plugin: BrokerPlugin, market: MarketSnapshot, selected: String, onSymbol: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember(plugin.descriptor.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var searchError by remember(plugin) { mutableStateOf<String?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("市场报价", modifier = Modifier.weight(1f))
            Text("${plugin.instruments.size} 个品种", color = TradingColors.Muted, fontSize = 10.sp)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("symbol-search"),
            placeholder = { Text("搜索品种", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(8.dp))
        if (plugin.descriptor.environment != BrokerEnvironment.LOCAL_DEMO) {
            TextButton({ scope.launch { try { plugin.searchInstruments(query) ; searchError = null } catch (_: Exception) { searchError = "合约搜索失败，请检查连接与授权" } } }, enabled = query.isNotBlank() && market.connected) { Text("查询服务器合约", fontSize = 11.sp) }
            searchError?.let { Text(it, color = TradingColors.Loss, fontSize = 10.sp) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("交易品种", Modifier.weight(1f), fontSize = 10.sp, color = TradingColors.Muted)
            Text("Bid / Ask", fontSize = 10.sp, color = TradingColors.Muted)
        }
        val filtered = plugin.instruments.filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
        LazyColumn(Modifier.weight(1f)) {
            if (filtered.isEmpty()) item { EmptyState("未找到品种", "更换关键词或选择其他交易商。") }
            items(filtered, key = { it.symbol }) { instrument ->
                val quote = market.quotes[instrument.symbol]
                Row(Modifier.fillMaxWidth().testTag("symbol-${instrument.symbol}").clickable { onSymbol(instrument.symbol) }
                    .background(if (instrument.symbol == selected) TradingColors.Raised else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(instrument.symbol, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(instrument.name, fontSize = 10.sp, color = TradingColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(quote?.let { instrument.formatPrice(it.bid) } ?: "—", fontSize = 12.sp)
                        Text(quote?.let { instrument.formatPrice(it.ask) } ?: "—", fontSize = 10.sp, color = TradingColors.Muted)
                    }
                }
                DividerLine(Modifier.padding(horizontal = 14.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("${plugin.descriptor.name} 提供", fontSize = 10.sp, color = TradingColors.Muted)
            Text(if (plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO) "全部为本地生成的模拟行情" else "行情与标的由 Tradovate 提供", fontSize = 10.sp, color = TradingColors.Muted)
        }
    }
}

@Composable
internal fun OrderTicket(
    instrument: Instrument,
    quote: Quote?,
    capabilities: BrokerCapabilities,
    draft: OrderDraft,
    onDraft: (OrderDraft) -> Unit,
    preview: OrderPreview?,
    busy: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    environment: BrokerEnvironment = BrokerEnvironment.LOCAL_DEMO,
    showTitle: Boolean = true,
) {
    // Without the title the host (the phone order panel) names the order, account and environment itself.
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = if (showTitle) 18.dp else 8.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("新建订单", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Badge(if (environment == BrokerEnvironment.LOCAL_DEMO) "模拟" else if (environment == BrokerEnvironment.LIVE) "Live 实盘" else "Demo")
            }
            Text("${instrument.symbol} · ${instrument.currency} 结算", color = TradingColors.Muted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.entries.forEach { side ->
                FilterChip(draft.side == side, { onDraft(draft.withSide(side)) }, label = { Text(side.label()) }, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OrderKind.entries.filter { it in capabilities.orderKinds }.forEach { kind ->
                FilterChip(draft.kind == kind, {
                    onDraft(draft.withKind(instrument, quote, kind))
                }, label = { Text(kind.label(), fontSize = 11.sp) })
            }
        }
        PriceInput("数量（${instrument.quantityUnit}）", draft.quantity, { onDraft(draft.copy(quantity = it)) }, "order-quantity")
        Text("范围 ${instrument.formatQuantity(instrument.minLotsHundredths)}–${instrument.formatQuantity(instrument.maxLotsHundredths)} · 步长 ${instrument.formatQuantity(instrument.lotStepHundredths)}", color = TradingColors.Muted, fontSize = 10.sp)
        if (draft.kind != OrderKind.MARKET) PriceInput(if (draft.kind == OrderKind.LIMIT) "限价" else "突破触发价", draft.entry, { onDraft(draft.copy(entry = it)) }, "order-entry")
        PriceInput("止损 SL（可选）", draft.stopLoss, { onDraft(draft.copy(stopLoss = it)) }, "order-sl")
        PriceInput("止盈 TP（可选）", draft.takeProfit, { onDraft(draft.copy(takeProfit = it)) }, "order-tp")
        TextButton({ quote?.let { onDraft(draft.withReferenceProtection(instrument, it)) } }, enabled = quote != null, contentPadding = PaddingValues(0.dp)) {
            Text("设置参考 SL / TP", fontSize = 11.sp)
        }
        DividerLine()
        Text("下单前预估", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        EstimateRow("预计入场", preview?.entryPrice?.let(instrument::formatPrice) ?: "—")
        EstimateRow(if (instrument.estimatesIncludeFees) "止损净结果" else "止损毛结果", preview?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        EstimateRow(if (instrument.estimatesIncludeFees) "止盈净结果" else "止盈毛结果", preview?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        val risk = preview?.stopLossPnlMicros
        val reward = preview?.takeProfitPnlMicros
        val ratio = if (risk != null && reward != null && risk < 0 && reward > 0) formatFixed((reward.toDouble() / -risk * 100).toLong(), 2) else "—"
        EstimateRow("收益 / 风险", ratio)
        EstimateRow("预计保证金", preview?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} USD" } ?: "—")
        EstimateRow("双边手续费", preview?.takeIf { it.feesKnown }?.let { "${money(it.feesMicros)} USD" } ?: "—")
        Text("按当前报价估算；成交和止损可能受后续报价及跳空影响。", fontSize = 10.sp, color = TradingColors.Muted)
        if (draft.kind != OrderKind.MARKET) Text("挂单不预占保证金，触发时重新校验资金与保护价。", color = TradingColors.Muted, fontSize = 10.sp)
        if (preview == null) Text("请输入有效数量与价格，不能超过品种的小数精度。", color = TradingColors.Loss, fontSize = 11.sp)
        preview?.warnings?.forEach { Text(it, color = TradingColors.Muted, fontSize = 10.sp) }
        preview?.errors?.forEach { Text(it, color = TradingColors.Loss, fontSize = 11.sp) }
        Button(onSubmit, Modifier.fillMaxWidth().height(46.dp).testTag("review-order"), enabled = preview?.valid == true && !busy) {
            Text(if (busy) "处理中…" else if (environment == BrokerEnvironment.LOCAL_DEMO) "检查并模拟${draft.side.label()}" else "检查并${draft.side.label()}")
        }
        Text(if (environment == BrokerEnvironment.LOCAL_DEMO) "订单只提交给当前模拟交易商。" else "订单将发送至当前 Tradovate ${environment.label} 账户。", color = TradingColors.Muted, fontSize = 10.sp)
    }
}

@Composable
internal fun PriceInput(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().testTag(tag), label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(8.dp))
}

@Composable
internal fun EstimateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = TradingColors.Muted, fontSize = 11.sp)
        Text(value, fontSize = 11.sp, textAlign = TextAlign.End,
            color = when { value.startsWith("+") -> TradingColors.Profit; value.startsWith("-") -> TradingColors.Loss; else -> TradingColors.Ink })
    }
}

@Composable
internal fun AccountStrip(account: AccountSnapshot, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(TradingColors.Shell).horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        val level = if (account.marginKnown && account.equityKnown && account.marginMicros > 0) "${account.equityMicros.toDouble().div(account.marginMicros).times(100).toInt()}%" else "—"
        listOf("余额 USD" to if (account.balanceKnown) money(account.balanceMicros) else "—", (if (account.equityEstimated) "净值 USD（估算）" else "净值 USD") to if (account.equityKnown) money(account.equityMicros) else "—", "浮动盈亏" to if (account.equityKnown && account.balanceKnown) money(account.floatingPnlMicros, true) else "—",
            "已用保证金" to if (account.marginKnown) money(account.marginMicros) else "—", "可用保证金" to if (account.equityKnown && account.marginKnown) money(account.freeMarginMicros) else "—", "保证金比例" to level).forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = TradingColors.Muted, fontSize = 10.sp)
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (label == "浮动盈亏" && account.equityKnown && account.balanceKnown) TradingColors.pnl(account.floatingPnlMicros) else TradingColors.Ink)
            }
        }
    }
}

@Composable
internal fun TradeTerminal(
    plugin: BrokerPlugin,
    account: AccountSnapshot,
    market: MarketSnapshot,
    selectedPosition: String?,
    compact: Boolean,
    onSelectPosition: (Position) -> Unit,
    onSelectTrade: (ClosedTrade) -> Unit,
    onClose: (Position) -> Unit,
    onProtect: (Position) -> Unit,
    onCancel: (PendingOrder) -> Unit,
    onEditOrder: (PendingOrder) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onSelectExecution: (Execution) -> Unit = {},
    onProtectOrder: (PendingOrder) -> Unit = {},
) {
    var tab by remember(plugin.descriptor.id, account.accountId) { mutableStateOf(0) }
    val instruments = plugin.instruments.associateBy { it.symbol }
    Column(modifier.background(TradingColors.Surface)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("持仓 ${account.positions.size}", "挂单 ${account.orders.size}", "历史 ${if (account.executions.isEmpty()) account.trades.size else account.executions.size}", "活动").forEachIndexed { index, title ->
                TextButton({ tab = index }, Modifier.testTag("terminal-tab-$index")) {
                    Text(title, color = if (tab == index) TradingColors.Accent else TradingColors.Muted, fontSize = 12.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        DividerLine()
        LazyColumn(Modifier.weight(1f).testTag("trade-terminal")) {
            when (tab) {
                0 -> {
                    if (account.positions.isEmpty()) item { EmptyState("暂无持仓", "选择品种，在订单面板检查当前环境和账户后提交。") }
                    items(account.positions, key = { it.id }) { p ->
                        val instrument = instruments[p.symbol] ?: run { EmptyState(p.symbol, "合约规格暂不可用，待目录恢复后可操作。"); return@items }
                        val q = market.quotes[p.symbol]
                        val pnl = q?.let { positionPnl(instrument, p, it) }
                        Column(Modifier.fillMaxWidth().background(if (selectedPosition == p.id) TradingColors.Raised.copy(alpha = .7f) else Color.Transparent).clickable { onSelectPosition(p) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(3.dp).height(26.dp).background(if (p.side == Side.BUY) TradingColors.Up else TradingColors.Down))
                                Column(Modifier.weight(1f)) {
                                    Text("${p.symbol}   ${p.side.label()}   ${instrument.formatQuantity(p.lotsHundredths)} ${instrument.quantityUnit}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("入场 ${instrument.formatPrice(p.entryPrice)}    #${p.id.takeLast(7)}", color = TradingColors.Muted, fontSize = 10.sp)
                                }
                                Text(pnl?.let { money(it, true) } ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TradingColors.pnl(pnl))
                                if (!compact) {
                                    Text("SL ${p.stopLoss?.let(instrument::formatPrice) ?: "—"}\nTP ${p.takeProfit?.let(instrument::formatPrice) ?: "—"}", fontSize = 10.sp, color = TradingColors.Muted)
                                    TextButton({ onProtect(p) }, enabled = !busy && plugin.capabilities.modifyProtection && p.protectionEditable) { Text("保护价", fontSize = 11.sp) }
                                    OutlinedButton({ onClose(p) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("平仓", fontSize = 11.sp) }
                                }
                            }
                            p.protectionNote?.let { Text(it, color = TradingColors.Muted, fontSize = 10.sp) }
                            if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("SL ${p.stopLoss?.let(instrument::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(instrument::formatPrice) ?: "—"}", Modifier.weight(1f), fontSize = 10.sp, color = TradingColors.Muted)
                                TextButton({ onProtect(p) }, enabled = !busy && plugin.capabilities.modifyProtection && p.protectionEditable) { Text("保护价", fontSize = 11.sp) }
                                TextButton({ onClose(p) }, enabled = !busy) { Text("平仓", fontSize = 11.sp) }
                            }
                        }
                        DividerLine()
                    }
                }
                1 -> {
                    if (account.orders.isEmpty()) item { EmptyState("暂无挂单", "选择限价或突破订单；资金会在触发时重新校验。") }
                    items(account.orders, key = { it.id }) { order ->
                        val r = order.request
                        val instrument = instruments[r.symbol] ?: run { EmptyState(r.symbol, "合约规格暂不可用，待目录恢复后可操作。"); return@items }
                        fun bracketsEditable(entry: PendingOrder) = plugin.capabilities.modifyPendingProtection && entry.editable && entry.protectionEditable
                        // A bracket of an unfilled entry is repriced through that entry.
                        val entry = if (order.isProtection && order.positionId == null)
                            account.orders.firstOrNull { it.role == OrderRole.ENTRY && order.id in listOf(it.stopLossOrderId, it.takeProfitOrderId) } else null
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.symbol}   ${order.role.label()} · ${r.side.label()}${r.kind.label()}   ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}", fontSize = 12.sp)
                                Text("${order.role.editLabel()} ${r.entryPrice?.let(instrument::formatPrice)}" +
                                    (if (order.role == OrderRole.ENTRY && (r.stopLoss != null || r.takeProfit != null))
                                        " · SL ${r.stopLoss?.let(instrument::formatPrice) ?: "—"} / TP ${r.takeProfit?.let(instrument::formatPrice) ?: "—"}" else "") +
                                    (if (plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO) " · 未占用保证金" else " · 服务器委托"), color = TradingColors.Muted, fontSize = 10.sp)
                                if (order.filledLotsHundredths > 0) Text("已成交 ${instrument.formatQuantity(order.filledLotsHundredths)} ${instrument.quantityUnit}", color = TradingColors.Muted, fontSize = 10.sp)
                                order.note?.let { Text(it, color = TradingColors.Muted, fontSize = 10.sp) }
                            }
                            TextButton({ onEditOrder(order) }, enabled = !busy && (order.editable &&
                                (if (order.isProtection) plugin.capabilities.modifyProtection else plugin.capabilities.modifyPendingOrders) || entry?.let(::bracketsEditable) == true),
                                modifier = Modifier.testTag("edit-order-${order.id}")) { Text(order.role.editLabel(), fontSize = 11.sp) }
                            if (order.role == OrderRole.ENTRY && plugin.capabilities.modifyPendingProtection &&
                                (r.stopLoss != null || r.takeProfit != null || plugin.capabilities.addProtection)) {
                                TextButton({ onProtectOrder(order) }, enabled = !busy && bracketsEditable(order), modifier = Modifier.testTag("protect-order-${order.id}")) {
                                    Text("保护价", fontSize = 11.sp)
                                }
                            }
                            TextButton({ onCancel(order) }, enabled = !busy) { Text("撤单") }
                        }
                        DividerLine()
                    }
                }
                2 -> {
                    if (account.trades.isEmpty() && account.executions.isEmpty()) item { EmptyState("暂无已平仓交易", "平仓后可在这里和图表中查看成交记录。") }
                    if (account.executions.isNotEmpty()) item { Text("原始成交 · 点击定位图表", Modifier.padding(16.dp), fontSize = 12.sp, color = TradingColors.Muted) }
                    items(account.executions.asReversed(), key = { "fill-${it.id}" }) { fill ->
                        val spec = instruments[fill.symbol]
                        if (spec != null) {
                            Column(Modifier.fillMaxWidth().testTag("execution-${fill.id}").clickable { onSelectExecution(fill) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("${fill.symbol}   ${fill.side.label()} ${spec.formatQuantity(fill.lotsHundredths)} ${spec.quantityUnit}   ${spec.formatPrice(fill.price)}", fontSize = 12.sp)
                                Text("${dateTime(fill.timeMs)} · 成交 #${fill.id} / 订单 #${fill.orderId}", color = TradingColors.Muted, fontSize = 10.sp)
                                Text("原始成交；配对与费用以服务器记录为准", color = TradingColors.Muted, fontSize = 10.sp)
                            }
                            DividerLine()
                        }
                    }
                    if (account.executions.isNotEmpty() && account.trades.isNotEmpty()) item { Text("服务器已配对交易", Modifier.padding(16.dp), fontSize = 12.sp, color = TradingColors.Muted) }
                    items(account.trades.asReversed(), key = { it.id }) { t ->
                        val instrument = instruments[t.symbol] ?: run { EmptyState(t.symbol, "合约规格暂不可用，待目录恢复后可操作。"); return@items }
                        Row(Modifier.fillMaxWidth().clickable { onSelectTrade(t) }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.symbol}   ${if (t.directionKnown) t.side.label() else "买卖配对"} ${instrument.formatQuantity(t.lotsHundredths)} ${instrument.quantityUnit}", fontSize = 12.sp)
                                Text("${instrument.formatPrice(t.entryPrice)} → ${instrument.formatPrice(t.exitPrice)}", color = TradingColors.Muted, fontSize = 10.sp)
                                if (compact) Text("${dateTime(t.closedAtMs)} · ${t.reason}", color = TradingColors.Muted, fontSize = 10.sp)
                            }
                            if (!compact) Text("${dateTime(t.closedAtMs)}\n${t.reason}", color = TradingColors.Muted, fontSize = 10.sp)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(t.netPnlMicros?.let { "${money(it, true)} USD" } ?: t.grossPnlMicros?.let { "毛 ${money(it, true)} USD" } ?: "—", fontSize = 12.sp,
                                    color = TradingColors.pnl(t.netPnlMicros ?: t.grossPnlMicros))
                                if (t.netPnlMicros == null) Text("费用未知，净盈亏待确认", color = TradingColors.Muted, fontSize = 10.sp)
                                if (!t.directionKnown) Text("同时间买卖配对", color = TradingColors.Muted, fontSize = 10.sp)
                            }
                        }
                        DividerLine()
                    }
                }
                else -> {
                    if (account.events.isEmpty()) item { EmptyState("暂无活动", "交易指令、触发和拒绝原因会记录在此。") }
                    items(account.events.asReversed()) { event ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(clockLabel(event.timeMs, true), fontSize = 10.sp, color = TradingColors.Muted)
                            Text(event.message, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
