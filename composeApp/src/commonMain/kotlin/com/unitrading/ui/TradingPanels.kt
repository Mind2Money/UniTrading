package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.chart.dateTime

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
    capacity: QuantityCapacityState = QuantityCapacityState(instrument.maxLotsHundredths),
    onRefreshCapacity: (() -> Unit)? = null,
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
            if (instrument.isContinuous) Text("图表 / 指标 ${instrument.symbol} · 下单 / 报价 ${instrument.executionLabel}", color = TradingColors.Accent, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.entries.forEach { side ->
                FilterChip(draft.side == side, { onDraft(draft.withSide(side, instrument, quote)) }, label = { Text(side.label()) }, modifier = Modifier.weight(1f))
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
        OrderQuantitySlider(instrument, draft, onDraft, enabled = !busy, capacity = capacity, onRefresh = onRefreshCapacity)
        if (draft.kind != OrderKind.MARKET) PriceInput(if (draft.kind == OrderKind.LIMIT) "限价" else "突破触发价", draft.entry, { onDraft(draft.copy(entry = it)) }, "order-entry")
        OrderProtectionControl(true, instrument, quote, draft, onDraft, !busy)
        OrderProtectionControl(false, instrument, quote, draft, onDraft, !busy)
        TextButton({ quote?.let { onDraft(draft.withReferenceProtection(instrument, it)) } }, enabled = quote != null, contentPadding = PaddingValues(0.dp)) {
            Text("设置参考 SL / TP", fontSize = 11.sp)
        }
        DividerLine()
        Text("下单前预估", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        EstimateRow("预计入场", preview?.entryPrice?.let(instrument::formatPrice) ?: "—")
        EstimateRow(if (instrument.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", preview?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        EstimateRow(if (instrument.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", preview?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        val risk = preview?.stopLossPnlMicros
        val reward = preview?.takeProfitPnlMicros
        val ratio = if (risk != null && reward != null && risk < 0 && reward > 0) formatFixed((reward.toDouble() / -risk * 100).toLong(), 2) else "—"
        EstimateRow("收益 / 风险", ratio)
        EstimateRow("预计保证金", preview?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} USD" } ?: "—")
        EstimateRow("双边手续费", preview?.takeIf { it.feesKnown }?.let { "${money(it.feesMicros)} USD" } ?: "—")
        Text("按当前报价估算。后续报价变化或跳空可能影响成交和止损执行。", fontSize = 10.sp, color = TradingColors.Muted)
        if (draft.kind != OrderKind.MARKET) Text("挂单不预占保证金，触发时重新校验资金与保护价。", color = TradingColors.Muted, fontSize = 10.sp)
        if (preview == null) Text("请输入有效的数量和价格，小数位数须符合该品种的精度。", color = TradingColors.Loss, fontSize = 11.sp)
        preview?.warnings?.forEach { Text(it, color = TradingColors.Muted, fontSize = 10.sp) }
        preview?.errors?.forEach { Text(it, color = TradingColors.Loss, fontSize = 11.sp) }
        Button(onSubmit, Modifier.fillMaxWidth().height(46.dp).testTag("review-order"), enabled = preview?.valid == true && !busy) {
            Text(if (busy) "处理中…" else if (environment == BrokerEnvironment.LOCAL_DEMO) "检查并模拟${draft.side.label()}" else "检查并${draft.side.label()}")
        }
        Text(if (environment == BrokerEnvironment.LOCAL_DEMO) "订单只提交给当前模拟交易商。" else "订单将发送至当前 Tradovate ${environment.label} 账户。", color = TradingColors.Muted, fontSize = 10.sp)
    }
}

@Composable
internal fun PriceInput(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().testTag(tag), enabled = enabled, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
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
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("今日盈亏 USD（券商）", color = TradingColors.Muted, fontSize = 10.sp)
            Text(account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—", Modifier.testTag("daily-pnl-value"),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TradingColors.pnl(account.dailyPnl?.totalMicros))
            Text(account.dailyPnl?.let { daily ->
                "${daily.tradingDay?.let { "$it 交易日 · " } ?: ""}快照 ${clockLabel(daily.updatedAtMs, true)} UTC"
            } ?: "等待券商数据", Modifier.testTag("daily-pnl-source"), color = TradingColors.Muted, fontSize = 10.sp)
        }
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
    onProtectOrder: (PendingOrder) -> Unit = {},
) {
    var expandedTrades by remember(plugin.descriptor.id, account.accountId) { mutableStateOf(emptySet<String>()) }
    val instruments = plugin.instruments.associateBy { it.symbol }
    val listState = key(plugin.descriptor.id, account.accountId) { rememberLazyListState() }
    Box(modifier.background(TradingColors.Surface)) {
        LazyColumn(Modifier.fillMaxSize().testTag("trade-terminal"), state = listState) {
            item(key = "section-positions", contentType = "section") {
                TerminalSectionHeader("持仓 ${account.positions.size}", "terminal-section-positions")
            }
            if (account.positions.isEmpty()) item(key = "empty-positions") { EmptyState("暂无持仓", "选择品种，在订单面板检查当前环境和账户后提交。") }
            items(account.positions, key = { "position-${it.id}" }, contentType = { "position" }) { p ->
                val instrument = instruments[p.symbol] ?: run { EmptyState(p.symbol, "合约规格暂不可用，目录恢复后才能操作。"); return@items }
                val q = market.quotes[p.symbol]
                val pnl = q?.let { positionPnl(instrument, p, it) }
                Column(Modifier.fillMaxWidth().testTag("position-${p.id}").background(if (selectedPosition == p.id) TradingColors.Raised.copy(alpha = .7f) else Color.Transparent).clickable { onSelectPosition(p) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
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
            item(key = "section-orders", contentType = "section") {
                TerminalSectionHeader("挂单 ${account.orders.size}", "terminal-section-orders")
            }
            if (account.orders.isEmpty()) item(key = "empty-orders") { EmptyState("暂无挂单", "选择限价或突破订单；资金会在触发时重新校验。") }
            items(account.orders, key = { "order-${it.id}" }, contentType = { "order" }) { order ->
                val r = order.request
                val instrument = instruments[r.symbol] ?: run { EmptyState(r.symbol, "合约规格暂不可用，目录恢复后才能操作。"); return@items }
                fun bracketsEditable(entry: PendingOrder) = plugin.capabilities.modifyPendingProtection && entry.editable && entry.protectionEditable
                // A bracket of an unfilled entry is repriced through that entry.
                val entry = if (order.isProtection && order.positionId == null)
                    account.orders.firstOrNull { it.role == OrderRole.ENTRY && order.id in listOf(it.stopLossOrderId, it.takeProfitOrderId) } else null
                Column(Modifier.fillMaxWidth().testTag("pending-order-${order.id}").padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(r.symbol, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Box(Modifier.testTag("order-role-${order.id}")) {
                            Badge(order.role.label(), when (order.role) {
                                OrderRole.STOP_LOSS -> TradingColors.Stop
                                OrderRole.TAKE_PROFIT -> TradingColors.Target
                                OrderRole.UNRESOLVED -> TradingColors.Muted
                                OrderRole.ENTRY -> TradingColors.Accent
                            })
                        }
                    }
                    Text("${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}", fontSize = 12.sp)
                    Column {
                        Text("${order.role.editLabel()} ${r.entryPrice?.let(instrument::formatPrice)}" +
                            (if (order.role == OrderRole.ENTRY && (r.stopLoss != null || r.takeProfit != null))
                                " · SL ${r.stopLoss?.let(instrument::formatPrice) ?: "—"} / TP ${r.takeProfit?.let(instrument::formatPrice) ?: "—"}" else "") +
                            (if (plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO) " · 未占用保证金" else " · 服务器委托"), color = TradingColors.Muted, fontSize = 10.sp)
                        if (order.filledLotsHundredths > 0) Text("已成交 ${instrument.formatQuantity(order.filledLotsHundredths)} ${instrument.quantityUnit}", color = TradingColors.Muted, fontSize = 10.sp)
                        order.note?.let { Text(it, color = TradingColors.Muted, fontSize = 10.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
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
                }
                DividerLine()
            }
            item(key = "section-history", contentType = "section") {
                TerminalSectionHeader("历史 ${account.trades.size}", "terminal-section-history")
            }
            if (account.trades.isEmpty()) item(key = "empty-history") { EmptyState("暂无已配对交易", "完成配对后可在这里展开入场和出场明细。") }
            items(account.trades.asReversed(), key = { "trade-${it.id}" }, contentType = { "trade" }) { t ->
                val instrument = instruments[t.symbol] ?: run { EmptyState(t.symbol, "合约规格暂不可用，目录恢复后才能操作。"); return@items }
                val expanded = t.id in expandedTrades
                Row(Modifier.fillMaxWidth().testTag("paired-trade-${t.id}")
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .clickable(onClickLabel = if (expanded) "收起成交明细" else "展开成交明细") {
                        expandedTrades = if (expanded) expandedTrades - t.id else expandedTrades + t.id
                    }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "▾" else "▸", color = TradingColors.Muted, fontSize = 12.sp)
                    Column(Modifier.weight(1f)) {
                        Text("${t.symbol}   ${if (t.directionKnown) t.side.label() else "买卖配对"} ${instrument.formatQuantity(t.lotsHundredths)} ${instrument.quantityUnit}", fontSize = 12.sp)
                        Text("${instrument.formatPrice(t.entryPrice)} ${if (t.directionKnown) "→" else "/"} ${instrument.formatPrice(t.exitPrice)}", color = TradingColors.Muted, fontSize = 10.sp)
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
                if (expanded) {
                    Column(Modifier.fillMaxWidth().testTag("paired-trade-details-${t.id}")
                        .background(TradingColors.Raised.copy(alpha = .5f))
                        .padding(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val exitSide = if (t.side == Side.BUY) Side.SELL else Side.BUY
                        PairedTradeLeg(if (t.directionKnown) "入场 · ${t.side.label()}" else t.side.label(),
                            instrument, t.lotsHundredths, t.entryPrice, t.openedAtMs)
                        PairedTradeLeg(if (t.directionKnown) "出场 · ${exitSide.label()}" else exitSide.label(),
                            instrument, t.lotsHundredths, t.exitPrice, t.closedAtMs)
                        if (!t.directionKnown) Text("买卖成交时间相同，入场和出场顺序未知", color = TradingColors.Muted, fontSize = 10.sp)
                        TextButton({ onSelectTrade(t) }, Modifier.testTag("locate-trade-${t.id}"), contentPadding = PaddingValues(0.dp)) {
                            Text("定位图表", fontSize = 11.sp)
                        }
                    }
                }
                DividerLine()
            }
        }
    }
}

@Composable
private fun TerminalSectionHeader(title: String, tag: String) {
    Column(Modifier.fillMaxWidth().background(TradingColors.Raised).testTag(tag).semantics { heading() }) {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        DividerLine()
    }
}

@Composable
private fun PairedTradeLeg(label: String, instrument: Instrument, quantity: Long, price: Long, timeMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$label   ${instrument.formatQuantity(quantity)} ${instrument.quantityUnit}   ${instrument.formatPrice(price)}", fontSize = 12.sp)
        Text("${dateTime(timeMs)} UTC", color = TradingColors.Muted, fontSize = 10.sp)
    }
}
