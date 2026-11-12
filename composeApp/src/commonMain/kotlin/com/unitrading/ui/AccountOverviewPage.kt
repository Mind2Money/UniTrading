package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.app.AccountAliasesController
import com.unitrading.app.overviewTotals
import com.unitrading.broker.*

/**
 * Two explicit levels: login connection, then the child used by the order ticket. Both show the user's aliases; with
 * [aliases] every row of the two menus also has a button that renames it.
 */
@Composable
internal fun AccountContextBar(
    registry: BrokerRegistry, plugin: BrokerPlugin, accountId: String,
    onBroker: (String) -> Unit, onAccount: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dense: Boolean = false,
    aliases: AccountAliasesController? = null,
) {
    val brokers by registry.state.collectAsState()
    val catalog by key(plugin) { plugin.catalog.collectAsState() }
    val names = LocalAccountAliases.current
    var loginMenu by remember(plugin) { mutableStateOf(false) }
    var childMenu by remember(plugin) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AliasEdit?>(null) }
    renaming?.let { AliasDialog(it) { renaming = null } }
    Row(modifier.heightIn(min = if (dense) 40.dp else 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton({ loginMenu = true }, Modifier.fillMaxWidth().testTag("quick-login-picker"), enabled = enabled,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    if (!dense) Text("登录账户", style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                    Text(names.login(plugin.descriptor), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(loginMenu && enabled, { loginMenu = false }) {
                brokers.forEach { broker ->
                    val descriptor = broker.descriptor
                    val label = names.login(descriptor)
                    DropdownMenuItem(text = { Column {
                        Text(label)
                        Text(listOfNotNull(descriptor.name.takeIf { it != label }, descriptor.environment.label).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                    } }, onClick = { loginMenu = false; onBroker(descriptor.id) },
                        modifier = Modifier.testTag("quick-login-${descriptor.id}"),
                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                            if (broker === plugin) Icon(AppIcons.Check, "当前登录")
                            if (aliases != null) RenameButton("修改 $label 的别名", "rename-login-${descriptor.id}") {
                                loginMenu = false
                                renaming = AliasEdit("登录账户别名", descriptor.name, aliases.aliases.value.logins[descriptor.id],
                                    { aliases.setLogin(descriptor.id, it) }, { aliases.error.value })
                            }
                        } })
                }
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(TradingColors.Border))
        Box(Modifier.weight(1f)) {
            TextButton({ childMenu = true }, Modifier.fillMaxWidth().testTag("quick-account-picker"), enabled = enabled && catalog.accounts.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    if (!dense) Text("交易子账户", style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                    Text(catalog.accounts.firstOrNull { it.id == accountId }?.let { names.account(plugin.descriptor.id, it) } ?: "选择子账户", maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(childMenu && enabled, { childMenu = false }) {
                catalog.accounts.forEach { child ->
                    val label = names.account(plugin.descriptor.id, child)
                    DropdownMenuItem(text = { Column {
                        Text(label)
                        Text(listOfNotNull(child.name.takeIf { it != label }, child.id, child.currency, "只读".takeIf { child.readOnly }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                    } }, onClick = { childMenu = false; onAccount(child.id) }, modifier = Modifier.testTag("quick-account-${child.id}"),
                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                            if (child.id == accountId) Icon(AppIcons.Check, "当前交易子账户")
                            if (aliases != null) RenameButton("修改 $label 的别名", "rename-account-${child.id}") {
                                childMenu = false
                                renaming = AliasEdit("交易子账户别名", child.name, aliases.aliases.value.accounts[plugin.descriptor.id]?.get(child.id),
                                    { aliases.setAccount(plugin.descriptor.id, child.id, it) }, { aliases.error.value })
                            }
                        } })
                }
            }
        }
    }
}

/**
 * The trading child's funds, drawdown and the net result of its closed [trades], and the way to its positions and
 * orders. [trades] is the list the analytics page counts (the broker's snapshot with the trades cached on this
 * device), so both pages show the same total.
 */
@Composable
internal fun AccountOverviewPage(
    plugin: BrokerPlugin, account: AccountInfo, snapshot: AccountSnapshot, trades: List<ClosedTrade>, market: MarketSnapshot,
    onOpenTrading: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false,
) {
    val totals = remember(snapshot, trades) { overviewTotals(snapshot.copy(trades = trades)) }
    val names = LocalAccountAliases.current
    val accountName = names.account(plugin.descriptor.id, account)
    val colors = TradingColors
    BoxWithConstraints(modifier.background(colors.Shell).testTag("account-overview")) {
        val wide = maxWidth >= 760.dp
        val inset = if (wide) 28.dp else 16.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = inset, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账户总览", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("${names.login(plugin.descriptor)} · $accountName", color = colors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnvironmentBadge(plugin.descriptor.environment)
                        Text(if (market.connected) "已连接 · 显示当前快照" else "连接已断开 · 显示最后快照",
                            color = if (market.connected) colors.Muted else colors.Caution, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item(key = "summary") {
                AccountSummary(snapshot, connected = market.connected, currency = account.currency)
            }
            // Every card has the summary's shape and inset, and a titled one its title in the style of "回撤与风险".
            item(key = "history") {
                Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已加载历史净盈亏", style = MaterialTheme.typography.titleMedium)
                        Text(totals.realizedMicros?.let { money(it, true) } ?: "—", Modifier.testTag("overview-realized"),
                            style = MaterialTheme.typography.titleSmall, color = colors.pnl(totals.realizedMicros), maxLines = 1)
                        // The total stays "—" while a trade's net result is unknown; the note says why.
                        if (totals.unknownClosed > 0) Text("${totals.unknownClosed} 笔历史净盈亏待确认，未计为零。", Modifier.testTag("overview-unknown-closed"),
                            style = MaterialTheme.typography.bodySmall, color = colors.Caution)
                    }
                }
            }
            item(key = "child") {
                Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(accountName, style = MaterialTheme.typography.titleMedium)
                            if (accountName != account.name) Text(account.name, color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                            Text("持仓 ${snapshot.positions.size} · 挂单 ${snapshot.orders.size} · ${account.currency}", color = colors.Muted,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onOpenTrading, enabled = !busy, modifier = Modifier.testTag("overview-open-${account.id}")) { Text("查看交易") }
                    }
                }
            }
        }
    }
}
