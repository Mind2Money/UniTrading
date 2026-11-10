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
import com.unitrading.app.overviewTotals
import com.unitrading.broker.*

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

/** The trading child's equity and P&L, and the way to its positions and orders. */
@Composable
internal fun AccountOverviewPage(
    plugin: BrokerPlugin, account: AccountInfo, snapshot: AccountSnapshot, market: MarketSnapshot,
    onOpenTrading: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false,
) {
    val totals = remember(snapshot) { overviewTotals(snapshot) }
    val colors = TradingColors
    BoxWithConstraints(modifier.background(colors.Shell).testTag("account-overview")) {
        val wide = maxWidth >= 760.dp
        val inset = if (wide) 28.dp else 16.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = inset, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账户总览", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("${plugin.descriptor.name} · ${account.name}", color = colors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnvironmentBadge(plugin.descriptor.environment)
                        Text(if (market.connected) "已连接 · 显示当前快照" else "连接已断开 · 显示最后快照",
                            color = if (market.connected) colors.Muted else colors.Caution, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item(key = "summary") {
                Surface(shape = MaterialTheme.shapes.large, color = colors.Surface) {
                    Column(Modifier.padding(if (wide) 24.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("所选子账户 · ${account.currency}", style = MaterialTheme.typography.labelMedium, color = colors.Muted)
                        Text(totals.equityMicros?.let { money(it) } ?: "—", style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("overview-equity-${account.currency}"))
                        Text(if (snapshot.equityEstimated) "账户总净值 · 含估算" else "账户总净值", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                        DividerLine()
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OverviewMetric("账户浮动盈亏", totals.floatingMicros, Modifier.weight(1f))
                            OverviewMetric("已加载历史净盈亏", totals.realizedMicros, Modifier.weight(1f))
                        }
                        if (totals.unknownClosed > 0) Text("${totals.unknownClosed} 笔历史净盈亏待确认，未计为零。", style = MaterialTheme.typography.bodySmall, color = colors.Caution)
                    }
                }
            }
            item(key = "child") {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text("持仓 ${snapshot.positions.size} · 挂单 ${snapshot.orders.size} · ${account.currency}", color = colors.Muted)
                        }
                        TextButton(onOpenTrading, enabled = !busy, modifier = Modifier.testTag("overview-open-${account.id}")) { Text("查看交易") }
                    }
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
