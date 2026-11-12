package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.unitrading.app.AccountBrackets
import com.unitrading.app.AppUpdates
import com.unitrading.app.OrderPanelLayout
import com.unitrading.app.TradingKeys
import com.unitrading.app.TradingPreferences
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*

/** Shared settings destination; account details are expanded only when switching accounts. */
@Composable
internal fun SettingsPage(
    runtime: TradingRuntime,
    plugin: BrokerPlugin,
    accountId: String,
    market: MarketSnapshot,
    busy: Boolean,
    onBroker: (String) -> Unit,
    onAccount: (String) -> Unit,
    onTheme: () -> Unit,
    onIndicators: () -> Unit,
    onActivity: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForgotten: () -> Unit,
    onSync: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onConnectRithmic: (() -> Unit)? = null,
    barActions: @Composable RowScope.() -> Unit = {},
    updates: AppUpdates? = null,
) {
    val colors = TradingColors
    val scheme = MaterialTheme.colorScheme
    val theme by runtime.theme.settings.collectAsState()
    val tradingPreferences by runtime.tradingPreferences.preferences.collectAsState()
    val savingTradingPreferences by runtime.tradingPreferences.saving.collectAsState()
    val tradingPreferencesError by runtime.tradingPreferences.error.collectAsState()
    val preferenceScope = rememberCoroutineScope()
    val indicators by runtime.indicators.settings.collectAsState()
    val notificationsAvailable by runtime.livePnl.available.collectAsState()
    val paused by runtime.paused.collectAsState()
    val localDemo = plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO
    val account = plugin.accounts.firstOrNull { it.id == accountId }
    val names = LocalAccountAliases.current
    val loginName = names.login(plugin.descriptor)
    val accountName = account?.let { names.account(plugin.descriptor.id, it) }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var chooseOrderLayout by remember { mutableStateOf(false) }

    Column(modifier.background(colors.Surface).testTag("settings-page")) {
        PageTopBar("设置", actions = {
            barActions()
            if (onClose != null) ActionIcon(AppIcons.Close, "关闭设置", "close-settings", onClick = onClose)
        })
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsSection("账户与连接") {
                val ready = market.connected && market.tradingReady
                val status = if (localDemo) { if (paused) "模拟已暂停" else "模拟运行中" }
                    else if (!market.connected) "已断开" else if (ready) "已连接" else "交易未就绪"
                ListItem(
                    headlineContent = { Text(accountName ?: accountId) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listOfNotNull(account?.name?.takeIf { it != accountName }, loginName, account?.currency).joinToString(" · "))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusDot(if (localDemo || ready) colors.Profit else colors.Loss)
                                Text("${plugin.descriptor.environment.label} · $status")
                            }
                        }
                    },
                    leadingContent = { Icon(AppIcons.AccountCircle, contentDescription = null, Modifier.size(32.dp), tint = scheme.primary) },
                    trailingContent = { Icon(if (accountsExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { accountsExpanded = !accountsExpanded }
                        .semantics { stateDescription = if (accountsExpanded) "已展开账户列表" else "已收起账户列表" }.testTag("settings-account"),
                    colors = settingsRowColors(),
                )
                if (accountsExpanded) BrokerAccountChooser(runtime.registry, plugin, accountId, onBroker, { onAccount(it); accountsExpanded = false })
                if (!localDemo) SettingsRow("同步账户", if (busy) "正在同步…" else market.statusMessage.ifBlank { "刷新服务器账户状态" }, AppIcons.Sync, "settings-sync",
                    onSync, enabled = plugin.capabilities.resynchronize && market.connected && !busy)
                SettingsRow("连接 Tradovate", "添加或重新登录 Demo / Live 账户", AppIcons.Add, "settings-connect", onConnect)
                if (onConnectRithmic != null) SettingsRow("连接 Rithmic", "登录 Test / Paper / Live 系统", AppIcons.Add, "settings-connect-rithmic", onConnectRithmic)
                if (!localDemo) SettingsRow("断开 $loginName", "断开当前连接，保留本机登录", AppIcons.Logout, "settings-disconnect", onDisconnect, destructive = true)
            }

            SettingsSection("交易") {
                SettingsRow("下单界面", "手机和紧凑窗口 · ${tradingPreferences.orderPanelLayout.label}", AppIcons.ViewQuilt, "settings-order-layout",
                    { chooseOrderLayout = true }, enabled = !busy, navigates = true)
                // An unreadable rule file pauses every account of every login, so the way out shows whichever account is selected.
                AccountRiskRecovery(runtime.accountRisk, busy)
                if (account != null && !account.readOnly) {
                    AccountBracketSettings(runtime.accountRisk, plugin, account, busy)
                    AccountRiskSettings(runtime.accountRisk, plugin, account, busy)
                    AccountOrderLimitSettings(runtime.accountRisk, plugin, account, busy)
                }
                ListItem(
                    headlineContent = { Text("跳过交易确认") },
                    supportingContent = { Text("所有账户：下单、平仓、撤单直接提交；图表拖动改单松手生效；开启键盘下单时按键即成交。") },
                    leadingContent = { Icon(AppIcons.Bolt, contentDescription = null) },
                    trailingContent = { Switch(tradingPreferences.skipConfirmation, onCheckedChange = null, enabled = !savingTradingPreferences) },
                    modifier = Modifier.toggleable(tradingPreferences.skipConfirmation, enabled = !savingTradingPreferences, role = Role.Switch,
                        onValueChange = { enabled -> preferenceScope.launch { runtime.tradingPreferences.setSkipConfirmation(enabled) } })
                        .testTag("settings-skip-confirmation"),
                    colors = settingsRowColors(),
                )
                // What a key does depends on the selected sub-account: its cash amounts add protection, and its sizing takes the keys' orders away.
                val riskRules by runtime.accountRisk.rules.collectAsState()
                val brackets = riskRules[plugin.descriptor.id]?.get(accountId)?.brackets ?: AccountBrackets()
                ListItem(
                    headlineContent = { Text("键盘下单") },
                    supportingContent = { Text(keyboardTradingSummary(tradingPreferences, brackets), Modifier.testTag("settings-keyboard-trading-summary")) },
                    leadingContent = { Icon(AppIcons.Keyboard, contentDescription = null) },
                    trailingContent = { Switch(tradingPreferences.keyboardTrading, onCheckedChange = null, enabled = !savingTradingPreferences) },
                    modifier = Modifier.toggleable(tradingPreferences.keyboardTrading, enabled = !savingTradingPreferences, role = Role.Switch,
                        onValueChange = { enabled -> preferenceScope.launch { runtime.tradingPreferences.setKeyboardTrading(enabled) } })
                        .testTag("settings-keyboard-trading"),
                    colors = settingsRowColors(),
                )
                // Only one pair of keys trades, so the other pair keeps what it does elsewhere on the chart.
                if (tradingPreferences.keyboardTrading) SegmentedChoice(TradingKeys.entries, tradingPreferences.tradingKeys,
                    { "${it.buy} 买入 / ${it.sell} 卖出" }, { "settings-trading-keys-${it.name}" },
                    { keys -> preferenceScope.launch { runtime.tradingPreferences.setTradingKeys(keys) } },
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), enabled = !savingTradingPreferences)
                ListItem(
                    headlineContent = { Text("成交音效") },
                    // The clips are a joke feature, and the row says so.
                    supportingContent = { Text("搞笑功能：入场、止盈、止损成交时各播放一段搞笑音效，不想听可以关闭。") },
                    leadingContent = { Icon(AppIcons.VolumeUp, contentDescription = null) },
                    trailingContent = { Switch(tradingPreferences.tradeSounds, onCheckedChange = null, enabled = !savingTradingPreferences) },
                    modifier = Modifier.toggleable(tradingPreferences.tradeSounds, enabled = !savingTradingPreferences, role = Role.Switch,
                        onValueChange = { enabled -> preferenceScope.launch { runtime.tradingPreferences.setTradeSounds(enabled) } })
                        .testTag("settings-trade-sounds"),
                    colors = settingsRowColors(),
                )
                tradingPreferencesError?.let {
                    Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("trading-preferences-error"),
                        color = colors.Loss, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsSection("外观与图表") {
                SettingsRow("外观与主题", "${theme.preset.label} · ${if (theme.colors.isEmpty()) "默认配色" else "自定义配色"}",
                    AppIcons.Palette, "open-theme", onTheme, navigates = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(colors.Accent, colors.Up, colors.Down).forEach { Box(Modifier.size(12.dp).background(it, CircleShape)) }
                    }
                }
                SettingsRow("图表指标", "${indicators.indicators.size} 个指标 · 参数与线条颜色", AppIcons.Tune, "settings-indicators", onIndicators, navigates = true)
            }

            if (notificationsAvailable) SettingsSection("通知") { LivePnlSwitch(runtime.livePnl) }

            SettingsSection("记录") {
                SettingsRow("活动记录", "查看当前账户的交易与操作记录", AppIcons.History, "open-activity", onActivity, navigates = true)
            }

            if (runtime.savedLogins.available) SettingsSection("已保存的登录") {
                SavedLoginControls(runtime, grouped = true, onForgotten = onForgotten)
            }

            if (localDemo) SettingsSection("本地模拟") {
                SettingsRow(if (paused) "继续模拟行情" else "暂停模拟行情", "模拟时间每秒推进 15 秒", if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                    "settings-pause-demo", { runtime.paused.value = !paused })
                SettingsRow("重置所有模拟账户", "清空本地模拟交易并恢复初始资金", AppIcons.DeleteSweep, "settings-reset-demo", onReset, destructive = true)
            }

            if (updates != null) SettingsSection("关于与更新") { AppUpdateRows(updates, runtime.registry, busy) }
        }
    }
    if (chooseOrderLayout) AlertDialog(
        onDismissRequest = { if (!savingTradingPreferences) chooseOrderLayout = false },
        title = { Text("下单界面") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("适用于手机和紧凑窗口。", Modifier.padding(bottom = 8.dp))
                // Three choices that each need a line of explanation: a list, where segmented buttons would cut the text.
                OrderPanelLayout.entries.forEach { layout ->
                    val selected = layout == tradingPreferences.orderPanelLayout
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
                        .selectable(selected, enabled = !savingTradingPreferences, role = Role.RadioButton) {
                            preferenceScope.launch {
                                if (runtime.tradingPreferences.setOrderPanelLayout(layout)) chooseOrderLayout = false
                            }
                        }.testTag("settings-order-layout-${layout.name}").padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected, onClick = null, enabled = !savingTradingPreferences)
                        Column(Modifier.weight(1f)) {
                            Text(layout.label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                            Text(layout.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                tradingPreferencesError?.let { Text(it, color = colors.Loss) }
            }
        },
        confirmButton = { TextButton({ chooseOrderLayout = false }, Modifier.testTag("settings-order-layout-close"), enabled = !savingTradingPreferences) { Text("关闭") } },
    )
}

/**
 * What the keyboard trading switch does on the selected sub-account, with the chosen keys and whether a key press
 * still asks before it trades. Under [brackets] that size orders from a stop a key trades nothing: it opens the order
 * panel on its side, where the stop is filled in. Cash amounts add their protection to what a key sends.
 */
internal fun keyboardTradingSummary(preferences: TradingPreferences, brackets: AccountBrackets = AccountBrackets()): String {
    val keys = preferences.tradingKeys
    if (brackets.sizingRisk != null) return "点一下图表后，按 ${keys.buy} 或 ${keys.sell} 打开下单栏并选好买入或卖出，不会直接下单：" +
        "当前子账户按风险计算手数，要先填写止损价。在输入框里打字不会触发。"
    return "点一下图表后，按 ${keys.buy} 市价买入、按 ${keys.sell} 市价卖出；在输入框里打字不会下单。数量取下单面板，" +
        (if (brackets.enabled) "开仓单按当前子账户的金额设置自动附加止损止盈。" else "不带止损止盈。") +
        if (preferences.skipConfirmation) "已开启“跳过交易确认”，按键即成交。" else "按键后先弹确认框，Enter 确认，Esc 取消。"
}

@Composable
private fun settingsRowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 16.dp).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(content = content)
        }
    }
}

/** One settings entry. [navigates] marks rows that open another page, the only ones with a chevron. */
@Composable
internal fun SettingsRow(
    title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, destructive: Boolean = false, navigates: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (!enabled) scheme.onSurface.copy(alpha = .38f) else if (destructive) scheme.error else scheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(title, color = if (!enabled) scheme.onSurface.copy(alpha = .38f) else if (destructive) scheme.error else scheme.onSurface) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        trailingContent = if (trailing == null && !navigates) null else { {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trailing?.invoke()
                if (navigates) Icon(AppIcons.ChevronRight, contentDescription = null)
            }
        } },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        colors = settingsRowColors(),
    )
}
