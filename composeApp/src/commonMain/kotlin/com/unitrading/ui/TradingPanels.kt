package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import com.unitrading.chart.dateTime
import com.unitrading.chart.resultLabel

@Composable
internal fun BrokerAccountChooser(
    registry: BrokerRegistry,
    plugin: BrokerPlugin,
    accountId: String,
    onBroker: (String) -> Unit,
    onAccount: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val type = MaterialTheme.typography
    val names = LocalAccountAliases.current
    Column(Modifier.padding(bottom = 8.dp)) {
        ListSubheader("交易商与账户")
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag("broker-picker"), shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp)) {
                Text(names.login(plugin.descriptor), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(AppIcons.ExpandMore, contentDescription = "选择交易商")
            }
            DropdownMenu(expanded, { expanded = false }) {
                registry.plugins.forEach { broker ->
                    DropdownMenuItem(text = { Text(names.login(broker.descriptor)) }, onClick = { expanded = false; onBroker(broker.descriptor.id) })
                }
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).selectableGroup()) {
            plugin.accounts.forEach { account ->
                val selected = account.id == accountId
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                    .selectable(selected, role = Role.RadioButton) { onAccount(account.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null)
                    Column(Modifier.weight(1f)) {
                        val label = names.account(plugin.descriptor.id, account)
                        Text(label, style = type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(account.name.takeIf { it != label }, account.id, account.currency).joinToString(" · "), color = TradingColors.Muted, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Desktop order column, and its dialog on windows too narrow to keep the column open. */
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
    capacity: QuantityCapacityState = QuantityCapacityState(instrument.maxLotsHundredths),
    onRefreshCapacity: (() -> Unit)? = null,
    skipConfirmation: Boolean = false,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "返回图表",
    closeTag: String = "close-order-dialog",
) {
    Column(modifier) {
        PanelHeader("新建订单", subtitle = "${instrument.symbol} · ${instrument.currency} 结算") {
            EnvironmentBadge(environment)
            if (onClose != null) ActionIcon(AppIcons.Close, closeLabel, closeTag, onClick = onClose) else Spacer(Modifier.width(12.dp))
        }
        OrderForm(instrument, quote, capabilities, draft, onDraft, preview, busy, onSubmit, Modifier.weight(1f), environment, capacity, onRefreshCapacity,
            skipConfirmation, showKinds = true, detailsOpen = true)
    }
}

@Composable
internal fun EstimateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = TradingColors.Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
            color = when { value.startsWith("+") -> TradingColors.Profit; value.startsWith("-") -> TradingColors.Loss; else -> TradingColors.Ink })
    }
}

/** The account's figures, each with its label; a value the broker has not reported stays "—". */
private class AccountFigures(account: AccountSnapshot, colors: TradingPalette) {
    val daily = account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—"
    val dailyColor = colors.pnl(account.dailyPnl?.totalMicros)
    val dailySource = account.dailyPnl?.let { daily ->
        "${daily.tradingDay?.let { "$it 交易日 · " } ?: ""}快照 ${clockLabel(daily.updatedAtMs, true)} UTC"
    } ?: "等待券商数据"
    private val level = if (account.marginKnown && account.equityKnown && account.marginMicros > 0)
        "${account.equityMicros.toDouble().div(account.marginMicros).times(100).toInt()}%" else "—"
    private val floatingKnown = account.equityKnown && account.balanceKnown
    val funds = listOf(
        Triple("余额 USD", if (account.balanceKnown) money(account.balanceMicros) else "—", Color.Unspecified),
        Triple(if (account.equityEstimated) "净值 USD（估算）" else "净值 USD", if (account.equityKnown) money(account.equityMicros) else "—", Color.Unspecified),
    )
    val others = listOf(
        Triple("浮动盈亏", if (floatingKnown) money(account.floatingPnlMicros, true) else "—", if (floatingKnown) colors.pnl(account.floatingPnlMicros) else Color.Unspecified),
        Triple("已用保证金", if (account.marginKnown) money(account.marginMicros) else "—", Color.Unspecified),
        Triple("可用保证金", if (account.equityKnown && account.marginKnown) money(account.freeMarginMicros) else "—", Color.Unspecified),
        Triple("保证金比例", level, Color.Unspecified),
    )
    val entries = funds + others
}

/** Desktop status bar: every account figure on one line, scrolling sideways only when the window is too narrow. */
@Composable
internal fun AccountStrip(account: AccountSnapshot, modifier: Modifier = Modifier, note: String? = null, connected: Boolean = true) {
    val figures = AccountFigures(account, TradingColors)
    Row(modifier.fillMaxWidth().background(TradingColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Metric("今日盈亏 USD（券商）", figures.daily, color = figures.dailyColor, valueTag = "daily-pnl-value")
            figures.funds.forEach { (label, value, color) -> Metric(label, value, color = color) }
            DrawdownStrip(account.drawdown, connected)
            figures.others.forEach { (label, value, color) -> Metric(label, value, color = color) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(figures.dailySource, Modifier.testTag("daily-pnl-source"), color = TradingColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            if (note != null) Text(note, color = TradingColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Phone account summary: all figures on one card, with nothing hidden behind sideways scrolling. */
@Composable
internal fun AccountSummary(account: AccountSnapshot, modifier: Modifier = Modifier, connected: Boolean = true) {
    val figures = AccountFigures(account, TradingColors)
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric("今日盈亏 USD（券商）", figures.daily, Modifier.weight(1f), figures.dailyColor, "daily-pnl-value")
                Text(figures.dailySource, Modifier.testTag("daily-pnl-source"), color = TradingColors.Muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            figures.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value, color) -> Metric(label, value, Modifier.weight(1f), color) }
                }
            }
            DrawdownSummary(account.drawdown, connected)
        }
    }
}

/** Where the trade list shows each working order: a stop or target on the position or unfilled entry it belongs to, anything else in [rows]. */
internal class TerminalOrders(val onPosition: Map<String, List<PendingOrder>>, val onEntry: Map<String, List<PendingOrder>>, val rows: List<PendingOrder>)

internal fun terminalOrders(account: AccountSnapshot): TerminalOrders {
    val exits = account.orders.filter { it.isProtection }.sortedBy { it.role }
    val onPosition = exits.filter { exit -> account.positions.any { it.id == exit.positionId } }
    val entries = account.orders.filter { it.role == OrderRole.ENTRY }
    // A bracket already protecting the filled part of its entry stays with the position.
    val onEntry = (exits - onPosition.toSet()).mapNotNull { exit -> entries.firstOrNull { exit.id in listOf(it.stopLossOrderId, it.takeProfitOrderId) }?.let { it.id to exit } }
    val placed = (onPosition + onEntry.map { it.second }).toSet()
    return TerminalOrders(onPosition.groupBy { it.positionId!! }, onEntry.groupBy({ it.first }, { it.second }), account.orders - placed)
}

@OptIn(ExperimentalLayoutApi::class)
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
    header: (@Composable () -> Unit)? = null,
) {
    var expandedTrades by remember(plugin.descriptor.id, account.accountId) { mutableStateOf(emptySet<String>()) }
    val instruments = plugin.instruments.associateBy { it.symbol }
    val listState = key(plugin.descriptor.id, account.accountId) { rememberLazyListState() }
    val colors = TradingColors
    val type = MaterialTheme.typography
    val missing = "合约规格暂不可用，目录恢复后才能操作。"
    val orderGroups = terminalOrders(account)
    Box(modifier.background(colors.Surface)) {
        LazyColumn(Modifier.fillMaxSize().testTag("trade-terminal"), state = listState) {
            if (header != null) item(key = "header", contentType = "header") { header() }
            item(key = "section-positions", contentType = "section") { TerminalSectionHeader("持仓", account.positions.size, "terminal-section-positions") }
            if (account.positions.isEmpty()) item(key = "empty-positions") { EmptyState("暂无持仓", "选择品种，在订单面板检查当前环境和账户后提交。", icon = AppIcons.Candlestick) }
            items(account.positions, key = { "position-${it.id}" }, contentType = { "position" }) { p ->
                val instrument = instruments[p.symbol] ?: run { EmptyState(p.symbol, missing); return@items }
                val q = market.quotes[p.symbol]
                val pnl = q?.let { positionPnl(instrument, p, it) }
                val protection = "SL ${p.stopLoss?.let(instrument::formatPrice) ?: "—"} · TP ${p.takeProfit?.let(instrument::formatPrice) ?: "—"}"
                val orders = orderGroups.onPosition[p.id].orEmpty()
                val actions: @Composable RowScope.() -> Unit = {
                    TextButton({ onProtect(p) }, Modifier.testTag("protect-position-${p.id}"), enabled = !busy && plugin.capabilities.modifyProtection && p.protectionEditable) { Text("止损/止盈") }
                    OutlinedButton({ onClose(p) }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("平仓") }
                }
                Column(Modifier.fillMaxWidth().testTag("position-${p.id}")
                    .background(if (selectedPosition == p.id) colors.Selection else Color.Transparent)
                    .clickable { onSelectPosition(p) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBadge(p.side.label(), if (p.side == Side.BUY) colors.UpText else colors.DownText)
                        Column(Modifier.weight(1f)) {
                            Text("${p.symbol} · ${instrument.formatQuantity(p.lotsHundredths)} ${instrument.quantityUnit}", style = type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("入场 ${instrument.formatPrice(p.entryPrice)} · #${shortId(p.id)}", color = colors.Muted, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!compact && orders.isEmpty()) Text(protection, color = colors.Muted, style = type.bodySmall)
                        Text(pnl?.let { money(it, true) } ?: "—", style = type.titleMedium, color = colors.pnl(pnl))
                        if (!compact) actions()
                    }
                    if (orders.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        orders.forEach { order ->
                            ProtectionChip(order, instrument, order.request.entryPrice?.let { estimatedExitPnl(instrument, p, order.request.lotsHundredths, it) }, !p.feesKnown, busy, onCancel, onEditOrder)
                        }
                    }
                    (listOfNotNull(p.protectionNote) + orders.mapNotNull { it.note }).distinct().forEach { Text(it, color = colors.Muted, style = type.bodySmall) }
                    if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (orders.isEmpty()) Text(protection, Modifier.weight(1f), color = colors.Muted, style = type.bodySmall) else Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
                DividerLine()
            }
            item(key = "section-orders", contentType = "section") { TerminalSectionHeader("挂单", orderGroups.rows.size, "terminal-section-orders") }
            if (orderGroups.rows.isEmpty()) item(key = "empty-orders") { EmptyState("暂无挂单", "选择限价或突破订单；资金会在触发时重新校验。", icon = AppIcons.ReceiptLong) }
            items(orderGroups.rows, key = { "order-${it.id}" }, contentType = { "order" }) { order ->
                val r = order.request
                val instrument = instruments[r.symbol] ?: run { EmptyState(r.symbol, missing); return@items }
                val brackets = orderGroups.onEntry[order.id].orEmpty()
                Column(Modifier.fillMaxWidth().testTag("pending-order-${order.id}").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.testTag("order-role-${order.id}")) {
                            StatusBadge(order.role.label(), when (order.role) {
                                OrderRole.STOP_LOSS -> colors.Stop
                                OrderRole.TAKE_PROFIT -> colors.Target
                                OrderRole.UNRESOLVED -> colors.Muted
                                OrderRole.ENTRY -> colors.Accent
                            })
                        }
                        Text("${r.symbol} · ${r.side.label()}${r.kind.label()} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}",
                            Modifier.weight(1f), style = type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${order.role.editLabel()} ${r.entryPrice?.let(instrument::formatPrice)}" +
                        (if (order.role == OrderRole.ENTRY && brackets.isEmpty() && (r.stopLoss != null || r.takeProfit != null))
                            " · SL ${r.stopLoss?.let(instrument::formatPrice) ?: "—"} · TP ${r.takeProfit?.let(instrument::formatPrice) ?: "—"}" else "") +
                        (if (plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO) " · 未占用保证金" else " · 服务器委托"), color = colors.Muted, style = type.bodySmall)
                    if (brackets.isNotEmpty()) FlowRow(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        brackets.forEach { bracket ->
                            val pnl = r.entryPrice?.let { entry -> bracket.request.entryPrice?.let { exit -> estimatedExitPnl(instrument, r.side, bracket.request.lotsHundredths, entry, exit) } }
                            ProtectionChip(bracket, instrument, pnl, !instrument.estimatesIncludeFees, busy, onCancel, onEditOrder)
                        }
                    }
                    if (order.filledLotsHundredths > 0) Text("已成交 ${instrument.formatQuantity(order.filledLotsHundredths)} ${instrument.quantityUnit}", color = colors.Muted, style = type.bodySmall)
                    (listOfNotNull(order.note) + brackets.mapNotNull { it.note }).distinct().forEach { Text(it, color = colors.Muted, style = type.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ onEditOrder(order) }, enabled = !busy && (order.standaloneEditable || order.editable &&
                            (if (order.isProtection) plugin.capabilities.modifyProtection else plugin.capabilities.modifyPendingOrders)),
                            modifier = Modifier.testTag("edit-order-${order.id}")) { Text(if (order.standaloneEditable) "单独改单" else order.role.editLabel()) }
                        if (order.role == OrderRole.ENTRY && plugin.capabilities.modifyPendingProtection &&
                            (r.stopLoss != null || r.takeProfit != null || plugin.capabilities.addProtection)) {
                            TextButton({ onProtectOrder(order) }, enabled = !busy && order.editable && order.protectionEditable, modifier = Modifier.testTag("protect-order-${order.id}")) { Text("止损/止盈") }
                        }
                        OutlinedButton({ onCancel(order) }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("撤单") }
                    }
                }
                DividerLine()
            }
            item(key = "section-history", contentType = "section") { TerminalSectionHeader("历史", account.trades.size, "terminal-section-history") }
            if (account.trades.isEmpty()) item(key = "empty-history") { EmptyState("暂无已配对交易", "完成配对后可在这里展开入场和出场明细。", icon = AppIcons.History) }
            items(account.trades.asReversed(), key = { "trade-${it.id}" }, contentType = { "trade" }) { t ->
                val instrument = instruments[t.symbol] ?: run { EmptyState(t.symbol, missing); return@items }
                val expanded = t.id in expandedTrades
                Row(Modifier.fillMaxWidth().testTag("paired-trade-${t.id}")
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .clickable(onClickLabel = if (expanded) "收起成交明细" else "展开成交明细") {
                        expandedTrades = if (expanded) expandedTrades - t.id else expandedTrades + t.id
                    }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.directionKnown) StatusBadge(t.side.label(), if (t.side == Side.BUY) colors.UpText else colors.DownText) else StatusBadge("买卖配对", colors.Muted)
                            Text("${t.symbol} · ${instrument.formatQuantity(t.lotsHundredths)} ${instrument.quantityUnit}", style = type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${instrument.formatPrice(t.entryPrice)} ${if (t.directionKnown) "→" else "/"} ${instrument.formatPrice(t.exitPrice)}", color = colors.Muted, style = type.bodySmall)
                        if (compact) Text("${dateTime(t.closedAtMs)} · ${t.reason}", color = colors.Muted, style = type.bodySmall)
                    }
                    if (!compact) Text("${dateTime(t.closedAtMs)}\n${t.reason}", color = colors.Muted, style = type.bodySmall)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(t.netPnlMicros?.let { "${money(it, true)} USD" } ?: t.grossPnlMicros?.let { "毛 ${money(it, true)} USD" } ?: "—", style = type.titleSmall,
                            color = colors.pnl(t.netPnlMicros ?: t.grossPnlMicros))
                        if (t.netPnlMicros == null) Text("费用未知，净盈亏待确认", color = colors.Muted, style = type.bodySmall)
                        if (!t.directionKnown) Text("同时间买卖配对", color = colors.Muted, style = type.bodySmall)
                    }
                    Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = colors.Muted)
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().testTag("paired-trade-details-${t.id}").background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val exitSide = if (t.side == Side.BUY) Side.SELL else Side.BUY
                        PairedTradeLeg(if (t.directionKnown) "入场 · ${t.side.label()}" else t.side.label(),
                            instrument, t.lotsHundredths, t.entryPrice, t.openedAtMs)
                        PairedTradeLeg(if (t.directionKnown) "出场 · ${exitSide.label()}" else exitSide.label(),
                            instrument, t.lotsHundredths, t.exitPrice, t.closedAtMs)
                        if (!t.directionKnown) Text("买卖成交时间相同，入场和出场顺序未知", color = colors.Muted, style = type.bodySmall)
                        TextButton({ onSelectTrade(t) }, Modifier.testTag("locate-trade-${t.id}")) { Text("定位图表") }
                    }
                }
                DividerLine()
            }
        }
    }
}

/**
 * A stop loss or take profit on the row it belongs to: price, size, the result if it fills ([pnl], [gross] without fees),
 * the button that changes it on its own where it cannot move with its position, and the button that cancels it.
 */
@Composable
private fun ProtectionChip(order: PendingOrder, instrument: Instrument, pnl: Long?, gross: Boolean, busy: Boolean, onCancel: (PendingOrder) -> Unit,
    onEdit: (PendingOrder) -> Unit) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    val r = order.request
    val stop = order.role == OrderRole.STOP_LOSS
    val filled = if (order.filledLotsHundredths > 0) " · 已成交 ${instrument.formatQuantity(order.filledLotsHundredths)}" else ""
    Surface(Modifier.testTag("pending-order-${order.id}"), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.testTag("order-role-${order.id}")) { StatusBadge(order.role.label(), if (stop) colors.Stop else colors.Target) }
            Text("${r.entryPrice?.let(instrument::formatPrice) ?: "—"} · ${instrument.formatQuantity(r.lotsHundredths)} ${instrument.quantityUnit}$filled", style = type.bodySmall, maxLines = 1)
            Text(resultLabel(pnl, gross), color = colors.pnl(pnl), style = type.bodySmall, maxLines = 1)
            if (order.standaloneEditable) ActionIcon(AppIcons.Edit, "单独修改${if (stop) "止损" else "止盈"} #${shortId(order.id)}", "edit-order-${order.id}",
                Modifier.size(32.dp), enabled = !busy, iconSize = 16.dp) { onEdit(order) }
            ActionIcon(AppIcons.Close, "撤销${if (stop) "止损" else "止盈"} #${shortId(order.id)}", "cancel-order-${order.id}", Modifier.size(32.dp), enabled = !busy,
                tint = MaterialTheme.colorScheme.error, iconSize = 16.dp) { onCancel(order) }
        }
    }
}

@Composable
private fun TerminalSectionHeader(title: String, count: Int, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag)) { ListSubheader(title, count = count) }
}

@Composable
private fun PairedTradeLeg(label: String, instrument: Instrument, quantity: Long, price: Long, timeMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label   ${instrument.formatQuantity(quantity)} ${instrument.quantityUnit}   ${instrument.formatPrice(price)}", style = MaterialTheme.typography.bodyMedium)
        Text("${dateTime(timeMs)} UTC", color = TradingColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
