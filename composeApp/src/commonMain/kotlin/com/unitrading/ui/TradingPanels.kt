package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
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
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("市场报价", modifier = Modifier.weight(1f))
            Text("${plugin.instruments.size} 个品种", color = TradingColors.Muted, fontSize = 10.sp)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("symbol-search"),
            placeholder = { Text("搜索品种", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(8.dp))
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
            Text("全部为本地生成的模拟行情", fontSize = 10.sp, color = TradingColors.Muted)
        }
    }
}

@Composable
internal fun InstrumentHeader(instrument: Instrument, quote: Quote?, compact: Boolean, onSide: (Side) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(instrument.symbol, fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.SemiBold)
            Text("${instrument.name}  /  ${instrument.category}", fontSize = 11.sp, color = TradingColors.Muted)
        }
        listOf(Side.SELL, Side.BUY).forEach { side ->
            val color = if (side == Side.BUY) TradingColors.Up else TradingColors.Down
            Surface(onClick = { onSide(side) }, shape = RoundedCornerShape(8.dp), color = color.copy(alpha = .09f), border = BorderStroke(1.dp, color.copy(alpha = .3f))) {
                Column(Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp)) {
                    Text(if (side == Side.BUY) "买入 Ask" else "卖出 Bid", fontSize = 10.sp, color = TradingColors.Muted)
                    Text(quote?.let { instrument.formatPrice(if (side == Side.BUY) it.ask else it.bid) } ?: "—", fontSize = if (compact) 12.sp else 16.sp, fontWeight = FontWeight.Medium)
                }
            }
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
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("新建订单", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Badge("模拟")
        }
        Text("${instrument.symbol} · ${instrument.currency} 结算", color = TradingColors.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.entries.forEach { side ->
                FilterChip(draft.side == side, { onDraft(draft.copy(side = side, stopLoss = "", takeProfit = "")) }, label = { Text(side.label()) }, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OrderKind.entries.filter { it in capabilities.orderKinds }.forEach { kind ->
                FilterChip(draft.kind == kind, {
                    onDraft(draft.copy(kind = kind, entry = if (kind == OrderKind.MARKET) "" else quote?.let {
                        val distance = maxOf(50L, instrument.minStopTicks * 4) * instrument.tickSize
                        val current = if (draft.side == Side.BUY) it.ask else it.bid
                        val above = (draft.side == Side.BUY) == (kind == OrderKind.STOP)
                        instrument.formatPrice(if (above) current + distance else maxOf(instrument.tickSize, current - distance))
                    } ?: "", stopLoss = "", takeProfit = ""))
                }, label = { Text(kind.label(), fontSize = 11.sp) })
            }
        }
        PriceInput("数量（手）", draft.quantity, { onDraft(draft.copy(quantity = it)) }, "order-quantity")
        Text("范围 ${formatFixed(instrument.minLotsHundredths, 2)}–${formatFixed(instrument.maxLotsHundredths, 2)} · 步长 ${formatFixed(instrument.lotStepHundredths, 2)}", color = TradingColors.Muted, fontSize = 10.sp)
        if (draft.kind != OrderKind.MARKET) PriceInput(if (draft.kind == OrderKind.LIMIT) "限价" else "突破触发价", draft.entry, { onDraft(draft.copy(entry = it)) }, "order-entry")
        PriceInput("止损 SL（可选）", draft.stopLoss, { onDraft(draft.copy(stopLoss = it)) }, "order-sl")
        PriceInput("止盈 TP（可选）", draft.takeProfit, { onDraft(draft.copy(takeProfit = it)) }, "order-tp")
        TextButton({ quote?.let { onDraft(draft.withReferenceProtection(instrument, it)) } }, enabled = quote != null, contentPadding = PaddingValues(0.dp)) {
            Text("设置参考 SL / TP", fontSize = 11.sp)
        }
        DividerLine()
        Text("下单前预估", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        EstimateRow("预计入场", preview?.entryPrice?.let(instrument::formatPrice) ?: "—")
        EstimateRow("止损净结果", preview?.stopLossPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        EstimateRow("止盈净结果", preview?.takeProfitPnlMicros?.let { "${money(it, true)} USD" } ?: "未设置")
        val risk = preview?.stopLossPnlMicros
        val reward = preview?.takeProfitPnlMicros
        val ratio = if (risk != null && reward != null && risk < 0 && reward > 0) formatFixed((reward.toDouble() / -risk * 100).toLong(), 2) else "—"
        EstimateRow("收益 / 风险", ratio)
        EstimateRow("预计保证金", preview?.let { "${money(it.marginMicros)} USD" } ?: "—")
        EstimateRow("双边手续费", preview?.let { "${money(it.feesMicros)} USD" } ?: "—")
        Text("按当前报价估算；成交和止损可能受后续报价及跳空影响。", fontSize = 10.sp, color = TradingColors.Muted)
        if (draft.kind != OrderKind.MARKET) Text("挂单不预占保证金，触发时重新校验资金与保护价。", color = TradingColors.Muted, fontSize = 10.sp)
        if (preview == null) Text("请输入有效数量与价格，不能超过品种的小数精度。", color = TradingColors.Loss, fontSize = 11.sp)
        preview?.errors?.forEach { Text(it, color = TradingColors.Loss, fontSize = 11.sp) }
        Button(onSubmit, Modifier.fillMaxWidth().height(46.dp).testTag("review-order"), enabled = preview?.valid == true && !busy) {
            Text(if (busy) "处理中…" else "检查并模拟${draft.side.label()}")
        }
        Text("订单只提交给当前模拟交易商。", color = TradingColors.Muted, fontSize = 10.sp)
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
        Text(value, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}

@Composable
internal fun AccountStrip(account: AccountSnapshot, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(TradingColors.Shell).horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        val level = if (account.marginMicros > 0) "${account.equityMicros.toDouble().div(account.marginMicros).times(100).toInt()}%" else "—"
        listOf("余额 USD" to money(account.balanceMicros), "净值 USD" to money(account.equityMicros), "浮动盈亏" to money(account.floatingPnlMicros, true),
            "已用保证金" to money(account.marginMicros), "可用保证金" to money(account.freeMarginMicros), "保证金比例" to level).forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = TradingColors.Muted, fontSize = 10.sp)
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
) {
    var tab by remember(plugin.descriptor.id, account.accountId) { mutableStateOf(0) }
    val instruments = plugin.instruments.associateBy { it.symbol }
    Column(modifier.background(TradingColors.Surface)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("持仓 ${account.positions.size}", "挂单 ${account.orders.size}", "历史 ${account.trades.size}", "活动").forEachIndexed { index, title ->
                TextButton({ tab = index }, Modifier.testTag("terminal-tab-$index")) {
                    Text(title, color = if (tab == index) TradingColors.Accent else TradingColors.Muted, fontSize = 12.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        DividerLine()
        LazyColumn(Modifier.weight(1f).testTag("trade-terminal")) {
            when (tab) {
                0 -> {
                    if (account.positions.isEmpty()) item { EmptyState("暂无持仓", "选择品种，在订单面板检查后提交模拟订单。") }
                    items(account.positions, key = { it.id }) { p ->
                        val instrument = instruments.getValue(p.symbol)
                        val q = market.quotes[p.symbol]
                        val pnl = q?.let { positionPnl(instrument, p, it) }
                        Column(Modifier.fillMaxWidth().background(if (selectedPosition == p.id) TradingColors.Raised.copy(alpha = .7f) else Color.Transparent).clickable { onSelectPosition(p) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(3.dp).height(26.dp).background(if (p.side == Side.BUY) TradingColors.Up else TradingColors.Down))
                                Column(Modifier.weight(1f)) {
                                    Text("${p.symbol}   ${p.side.label()}   ${formatFixed(p.lotsHundredths, 2)} 手", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("入场 ${instrument.formatPrice(p.entryPrice)}    #${p.id.takeLast(7)}", color = TradingColors.Muted, fontSize = 10.sp)
                                }
                                Text(pnl?.let { money(it, true) } ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (!compact) {
                                    Text("SL ${p.stopLoss?.let(instrument::formatPrice) ?: "—"}\nTP ${p.takeProfit?.let(instrument::formatPrice) ?: "—"}", fontSize = 10.sp, color = TradingColors.Muted)
                                    TextButton({ onProtect(p) }, enabled = !busy && plugin.capabilities.modifyProtection) { Text("保护价", fontSize = 11.sp) }
                                    OutlinedButton({ onClose(p) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("平仓", fontSize = 11.sp) }
                                }
                            }
                            if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("SL ${p.stopLoss?.let(instrument::formatPrice) ?: "—"} / TP ${p.takeProfit?.let(instrument::formatPrice) ?: "—"}", Modifier.weight(1f), fontSize = 10.sp, color = TradingColors.Muted)
                                TextButton({ onProtect(p) }, enabled = !busy && plugin.capabilities.modifyProtection) { Text("保护价", fontSize = 11.sp) }
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
                        val instrument = instruments.getValue(r.symbol)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.symbol}   ${r.side.label()}${r.kind.label()}   ${formatFixed(r.lotsHundredths, 2)} 手", fontSize = 12.sp)
                                Text("触发价 ${r.entryPrice?.let(instrument::formatPrice)} · 未占用保证金", color = TradingColors.Muted, fontSize = 10.sp)
                            }
                            TextButton({ onEditOrder(order) }, enabled = !busy && plugin.capabilities.modifyPendingOrders) { Text("入场价", fontSize = 11.sp) }
                            TextButton({ onCancel(order) }, enabled = !busy) { Text("撤单") }
                        }
                        DividerLine()
                    }
                }
                2 -> {
                    if (account.trades.isEmpty()) item { EmptyState("暂无已平仓交易", "平仓后可在这里和图表中查看成交记录。") }
                    items(account.trades.asReversed(), key = { it.id }) { t ->
                        val instrument = instruments.getValue(t.symbol)
                        Row(Modifier.fillMaxWidth().clickable { onSelectTrade(t) }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.symbol}   ${t.side.label()} ${formatFixed(t.lotsHundredths, 2)} 手", fontSize = 12.sp)
                                Text("${instrument.formatPrice(t.entryPrice)} → ${instrument.formatPrice(t.exitPrice)}", color = TradingColors.Muted, fontSize = 10.sp)
                                if (compact) Text("${dateTime(t.closedAtMs)} · ${t.reason}", color = TradingColors.Muted, fontSize = 10.sp)
                            }
                            if (!compact) Text("${dateTime(t.closedAtMs)}\n${t.reason}", color = TradingColors.Muted, fontSize = 10.sp)
                            Text("${money(t.netPnlMicros, true)} USD", fontSize = 12.sp)
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
