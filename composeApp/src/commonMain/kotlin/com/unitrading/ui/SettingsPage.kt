package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.unitrading.app.AppUpdates
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
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

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
                    headlineContent = { Text(account?.name ?: accountId) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${plugin.descriptor.name} · ${account?.currency.orEmpty()}")
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
                if (!localDemo) SettingsRow("断开 ${plugin.descriptor.name}", "断开当前连接，保留本机登录", AppIcons.Logout, "settings-disconnect", onDisconnect, destructive = true)
            }

            SettingsSection("交易") {
                ListItem(
                    headlineContent = { Text("跳过交易确认") },
                    supportingContent = { Text("所有账户：下单、平仓、撤单直接提交；图表拖动改单松手生效。") },
                    leadingContent = { Icon(AppIcons.Bolt, contentDescription = null) },
                    trailingContent = { Switch(tradingPreferences.skipConfirmation, onCheckedChange = null, enabled = !savingTradingPreferences) },
                    modifier = Modifier.toggleable(tradingPreferences.skipConfirmation, enabled = !savingTradingPreferences, role = Role.Switch,
                        onValueChange = { enabled -> preferenceScope.launch { runtime.tradingPreferences.setSkipConfirmation(enabled) } })
                        .testTag("settings-skip-confirmation"),
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
                SavedLoginControls(runtime.savedLogins, grouped = true, onForgotten = onForgotten)
            }

            if (localDemo) SettingsSection("本地模拟") {
                SettingsRow(if (paused) "继续模拟行情" else "暂停模拟行情", "模拟时间每秒推进 15 秒", if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                    "settings-pause-demo", { runtime.paused.value = !paused })
                SettingsRow("重置所有模拟账户", "清空本地模拟交易并恢复初始资金", AppIcons.DeleteSweep, "settings-reset-demo", onReset, destructive = true)
            }

            if (updates != null) SettingsSection("关于与更新") { AppUpdateRows(updates, runtime.registry, busy) }
        }
    }
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
