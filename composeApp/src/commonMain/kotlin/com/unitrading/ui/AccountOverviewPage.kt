package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.dateTime
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Collect every authorized child account, even when another child is selected for trading. */
@Composable
internal fun rememberOverviewAccounts(plugin: BrokerPlugin): List<OverviewAccount> {
    val catalog by key(plugin) { plugin.catalog.collectAsState() }
    return key(plugin, catalog.accounts) {
        val sessions = remember(plugin, catalog.accounts) { catalog.accounts.map { plugin.openSession(it.id) } }
        val snapshots = remember(sessions) {
            if (sessions.isEmpty()) flowOf(emptyList<AccountSnapshot>())
            else combine(sessions.map { it.state }) { it.toList() }
        }
        val values by snapshots.collectAsState(initial = sessions.map { it.state.value })
        catalog.accounts.zip(values).map { (info, snapshot) -> OverviewAccount(info, snapshot) }
    }
}

/** Two explicit levels: login connection, then the child used by the order ticket. */
@Composable
internal fun AccountContextBar(
    registry: BrokerRegistry, plugin: BrokerPlugin, accountId: String,
    onBroker: (String) -> Unit, onAccount: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dense: Boolean = false,
) {
    val brokers by registry.state.collectAsState()
    val catalog by key(plugin) { plugin.catalog.collectAsState() }
    var loginMenu by remember(plugin) { mutableStateOf(false) }
    var childMenu by remember(plugin) { mutableStateOf(false) }
    Row(modifier.heightIn(min = if (dense) 40.dp else 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton({ loginMenu = true }, Modifier.fillMaxWidth().testTag("quick-login-picker"), enabled = enabled,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    if (!dense) Text("登录账户", style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                    Text(plugin.descriptor.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(loginMenu && enabled, { loginMenu = false }) {
                brokers.forEach { broker ->
                    DropdownMenuItem(text = { Column {
                        Text(broker.descriptor.name)
                        Text(broker.descriptor.environment.label, style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                    } }, onClick = { loginMenu = false; onBroker(broker.descriptor.id) },
                        modifier = Modifier.testTag("quick-login-${broker.descriptor.id}"),
                        trailingIcon = { if (broker === plugin) Icon(AppIcons.Check, "当前登录") })
                }
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(TradingColors.Border))
        Box(Modifier.weight(1f)) {
            TextButton({ childMenu = true }, Modifier.fillMaxWidth().testTag("quick-account-picker"), enabled = enabled && catalog.accounts.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    if (!dense) Text("交易子账户", style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                    Text(catalog.accounts.firstOrNull { it.id == accountId }?.name ?: "选择子账户", maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(childMenu && enabled, { childMenu = false }) {
                catalog.accounts.forEach { child ->
                    DropdownMenuItem(text = { Column {
                        Text(child.name)
                        Text("${child.id} · ${child.currency}${if (child.readOnly) " · 只读" else ""}", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                    } }, onClick = { childMenu = false; onAccount(child.id) }, modifier = Modifier.testTag("quick-account-${child.id}"),
                        trailingIcon = { if (child.id == accountId) Icon(AppIcons.Check, "当前交易子账户") })
                }
            }
        }
    }
}

@Composable
internal fun AccountOverviewPage(
    plugin: BrokerPlugin, accounts: List<OverviewAccount>, market: MarketSnapshot, tradingAccountId: String,
    onOpenAccount: (String) -> Unit, modifier: Modifier = Modifier, busy: Boolean = false, showRecords: Boolean = true,
) = key(plugin) {
    var scopeId by remember(tradingAccountId) { mutableStateOf<String?>(tradingAccountId) }
    var filter by remember { mutableStateOf(OverviewFilter.ALL) }
    var query by remember { mutableStateOf("") }
    val effectiveScope = scopeId?.takeIf { id -> accounts.any { it.info.id == id } }
    val scoped = accounts.filter { effectiveScope == null || it.info.id == effectiveScope }
    val catalog by plugin.catalog.collectAsState()
    val allRows = remember(scoped, catalog.instruments, market) { overviewRows(scoped, catalog.instruments, market) }
    val searched = remember(allRows, query) { allRows.filter { it.matchesQuery(query) } }
    val rows = searched.filter { filter.matches(it) }
    val totals = remember(scoped) { overviewTotals(scoped) }
    val colors = TradingColors
    BoxWithConstraints(modifier.background(colors.Shell).testTag("account-overview")) {
        val wide = maxWidth >= 760.dp
        val inset = if (wide) 28.dp else 16.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = inset, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账户总览", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("${plugin.descriptor.name} · ${accounts.firstOrNull { it.info.id == tradingAccountId }?.info?.name ?: "当前子账户"}", color = colors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnvironmentBadge(plugin.descriptor.environment)
                        Text(if (market.connected) "已连接 · 显示当前快照" else "连接已断开 · 显示最后快照",
                            color = if (market.connected) colors.Muted else colors.Caution, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            items(totals, key = { "summary-${it.currency}" }) { total ->
                Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                    Column(Modifier.padding(if (wide) 24.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${if (effectiveScope == null) "全部子账户" else "所选子账户"} · ${total.currency}", style = MaterialTheme.typography.labelMedium, color = colors.Muted)
                        Text(total.equityMicros?.let { money(it) } ?: "—", style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("overview-equity-${total.currency}"))
                        Text(if (total.estimated) "账户总净值 · 含估算" else "账户总净值", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                        DividerLine()
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OverviewMetric("账户浮动盈亏", total.floatingMicros, Modifier.weight(1f))
                            OverviewMetric("已加载历史净盈亏", total.realizedMicros, Modifier.weight(1f))
                        }
                        if (total.unknownClosed > 0) Text("${total.unknownClosed} 笔历史净盈亏待确认，未计为零。", style = MaterialTheme.typography.bodySmall, color = colors.Caution)
                    }
                }
            }
            if (accounts.size > 1) item(key = "accounts") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("查看范围", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(effectiveScope == null, { scopeId = null }, label = { Text("全部子账户 · ${accounts.size}") }, modifier = Modifier.testTag("overview-scope-all"))
                        accounts.forEach { child ->
                            FilterChip(effectiveScope == child.info.id, { scopeId = child.info.id },
                                label = { Text(child.info.name, maxLines = 1) }, modifier = Modifier.testTag("overview-scope-${child.info.id}"))
                        }
                    }
                }
            }
            if (!showRecords) items(scoped, key = { "child-${it.info.id}" }) { child ->
                Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(child.info.name, style = MaterialTheme.typography.titleMedium)
                            Text("持仓 ${child.snapshot.positions.size} · 挂单 ${child.snapshot.orders.size} · ${child.info.currency}", color = colors.Muted)
                        }
                        TextButton({ onOpenAccount(child.info.id) }, enabled = !busy,
                            modifier = Modifier.testTag("overview-open-${child.info.id}")) { Text("查看交易") }
                    }
                }
            }
            if (showRecords) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("订单与持仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("overview-search"),
                        singleLine = true, shape = MaterialTheme.shapes.medium,
                        placeholder = { Text("搜索合约、子账户或订单号") }, leadingIcon = { Icon(AppIcons.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(AppIcons.Close, "清空搜索") } })
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewFilter.entries.forEach { item ->
                            FilterChip(filter == item, { filter = item }, label = { Text("${item.label} ${searched.count { item.matches(it) }}") },
                                modifier = Modifier.testTag("overview-filter-${item.name}"))
                        }
                    }
                    Text("${rows.size} 条记录 · 历史范围以券商已返回数据为准；持仓为报价估算毛盈亏，平仓以净盈亏分类。",
                        style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                }
            }
            if (wide && rows.isNotEmpty()) item(key = "columns") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("合约 / 子账户", Modifier.weight(1.15f), style = MaterialTheme.typography.labelMedium, color = colors.Muted)
                    Text("成交 / 订单详情", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = colors.Muted)
                    Text("状态 / 盈亏", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.Muted, textAlign = TextAlign.End)
                    Spacer(Modifier.width(92.dp))
                }
            }
            if (rows.isEmpty()) item(key = "empty") {
                Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppIcons.ReceiptLong, null, tint = colors.Muted)
                        Text(if (allRows.isEmpty()) "暂无已加载订单" else "没有符合条件的记录", style = MaterialTheme.typography.titleMedium)
                        Text(if (allRows.isEmpty()) "账户数据返回后会自动显示。" else "试试其他子账户、状态或搜索词。", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                        if (query.isNotEmpty() || filter != OverviewFilter.ALL || effectiveScope != null) TextButton({ query = ""; filter = OverviewFilter.ALL; scopeId = tradingAccountId }) { Text("重置筛选") }
                    }
                }
            }
            items(rows, key = { it.key }, contentType = { "overview-order" }) { row ->
                OverviewOrderRow(row, wide, row.account.id == tradingAccountId, busy) { onOpenAccount(row.account.id) }
            }
            item(key = "scope-note") {
                Text("查看范围仅筛选列表。点击「查看交易」后才会切换交易子账户。止盈 / 止损原因直接显示券商记录。",
                    style = MaterialTheme.typography.bodySmall, color = colors.Muted, modifier = Modifier.padding(vertical = 8.dp))
            }
            }
        }
    }
}

@Composable
private fun OverviewMetric(label: String, value: Long?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = TradingColors.Muted, style = MaterialTheme.typography.bodySmall)
        Text(value?.let { money(it, true) } ?: "—", color = TradingColors.pnl(value), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OverviewOrderRow(row: OverviewRow, wide: Boolean, selected: Boolean, busy: Boolean, onOpen: () -> Unit) {
    val colors = TradingColors
    val identity: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(row.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(row.account.name, style = MaterialTheme.typography.bodySmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Text("当前交易子账户", style = MaterialTheme.typography.labelSmall, color = colors.Accent)
        }
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${row.side?.label() ?: "方向未知"} · ${row.quantity} · ${row.price}", style = MaterialTheme.typography.bodySmall)
            Text(row.detail, color = colors.Muted, style = MaterialTheme.typography.bodySmall)
            Text("${dateTime(row.timeMs)} UTC · #${row.id}", color = colors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val result: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusBadge(row.status.label, if (row.status == OverviewStatus.UNKNOWN || row.kind == OverviewKind.ORDER) colors.Muted else colors.pnl(row.pnlMicros))
            if (row.kind != OverviewKind.ORDER) {
                Text(row.pnlMicros?.let { money(it, true) } ?: "—", color = colors.pnl(row.pnlMicros),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${if (row.gross) "毛盈亏 · " else "净盈亏 · "}${row.currency}", color = colors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface, modifier = Modifier.fillMaxWidth().testTag("overview-row-${row.key}")) {
        if (wide) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            identity(Modifier.weight(1.15f)); details(Modifier.weight(1.4f)); result(Modifier.weight(1f))
            TextButton(onOpen, Modifier.width(92.dp).testTag("overview-open-${row.key}"), enabled = !busy) { Text("查看交易") }
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { identity(Modifier.weight(1f)); result(Modifier.weight(1f)) }
            DividerLine()
            details(Modifier.fillMaxWidth())
            TextButton(onOpen, Modifier.align(Alignment.End).testTag("overview-open-${row.key}"), enabled = !busy) { Text("查看交易"); Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp)) }
        }
    }
}
