package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    val colors = TradingColors
    val theme by runtime.theme.settings.collectAsState()
    val indicators by runtime.indicators.settings.collectAsState()
    val notificationsAvailable by runtime.livePnl.available.collectAsState()
    val paused by runtime.paused.collectAsState()
    val localDemo = plugin.descriptor.environment == BrokerEnvironment.LOCAL_DEMO
    val account = plugin.accounts.firstOrNull { it.id == accountId }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(modifier.background(colors.Shell).testTag("settings-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设置", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("管理账户，调整你的交易界面", fontSize = 12.sp, color = colors.Muted)
            }
            if (onClose != null) TextButton(onClose, Modifier.testTag("close-settings")) { Text("完成") }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SettingsSection("账户与连接") {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { accountsExpanded = !accountsExpanded }
                    .semantics { stateDescription = if (accountsExpanded) "已展开账户列表" else "已收起账户列表" }
                    .testTag("settings-account").padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsIconTile(SettingsGlyph.Account, large = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(account?.name ?: accountId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${plugin.descriptor.name} · ${account?.currency.orEmpty()}", fontSize = 12.sp, color = colors.Muted)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val ready = market.connected && market.tradingReady
                            val status = if (localDemo) { if (paused) "模拟已暂停" else "模拟运行中" }
                                else if (!market.connected) "已断开" else if (ready) "已连接" else "交易未就绪"
                            Box(Modifier.size(6.dp).background(if (localDemo || ready) colors.Profit else colors.Loss, CircleShape))
                            Text("${plugin.descriptor.environment.label} · $status", fontSize = 11.sp, color = colors.Muted)
                        }
                    }
                    Icon(if (accountsExpanded) SettingsGlyph.Collapse.vector else SettingsGlyph.Chevron.vector,
                        contentDescription = null, tint = colors.Muted, modifier = Modifier.size(18.dp))
                }
                if (accountsExpanded) {
                    SettingsDivider()
                    BrokerAccountChooser(runtime.registry, plugin, accountId, onBroker, { onAccount(it); accountsExpanded = false })
                }
                if (!localDemo) {
                    SettingsDivider()
                    SettingsActionRow("同步账户", if (busy) "正在同步…" else market.statusMessage.ifBlank { "刷新服务器账户状态" },
                        SettingsGlyph.Sync, "settings-sync", onSync, enabled = plugin.capabilities.resynchronize && market.connected && !busy)
                }
                SettingsDivider()
                SettingsActionRow("连接 Tradovate", "添加或重新登录 Demo / Live 账户", SettingsGlyph.Add, "settings-connect", onConnect)
            }

            SettingsSection("外观与图表") {
                SettingsActionRow("外观与主题", "${theme.preset.label} · ${if (theme.colors.isEmpty()) "默认配色" else "自定义配色"}",
                    SettingsGlyph.Theme, if (onClose == null) "open-theme" else "settings-theme", onTheme) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(colors.Accent, colors.Up, colors.Down).forEach {
                            Box(Modifier.size(10.dp).background(it, CircleShape))
                        }
                    }
                }
                SettingsDivider()
                SettingsActionRow("图表指标", "${indicators.indicators.size} 个指标 · 参数与线条颜色", SettingsGlyph.Chart,
                    "settings-indicators", onIndicators)
            }

            if (notificationsAvailable) SettingsSection("通知") {
                LivePnlSwitch(runtime.livePnl, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            SettingsSection("记录") {
                SettingsActionRow("活动记录", "查看当前账户的交易与操作记录", SettingsGlyph.History, "open-activity", onActivity)
            }

            if (runtime.savedLogins.available || !localDemo) SettingsSection("登录与会话") {
                if (runtime.savedLogins.available) SavedLoginControls(runtime.savedLogins, grouped = true, onForgotten = onForgotten)
                if (!localDemo) {
                    if (runtime.savedLogins.available) SettingsDivider()
                    SettingsActionRow("断开 Tradovate", "断开当前连接，保留本机登录", SettingsGlyph.Disconnect,
                        "settings-disconnect", onDisconnect, destructive = true)
                }
            }

            if (localDemo) SettingsSection("本地模拟") {
                SettingsActionRow(if (paused) "继续模拟行情" else "暂停模拟行情", "模拟时间每秒推进 15 秒", SettingsGlyph.Chart,
                    "settings-pause-demo", { runtime.paused.value = !paused })
                SettingsDivider()
                SettingsActionRow("重置所有模拟账户", "清空本地模拟交易并恢复初始资金", SettingsGlyph.Reset,
                    "settings-reset-demo", onReset, destructive = true)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, color = TradingColors.Muted,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = TradingColors.Surface,
            border = BorderStroke(1.dp, TradingColors.Border.copy(alpha = .4f))) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp),
    color = TradingColors.Border.copy(alpha = .35f))

@Composable
internal fun SettingsActionRow(
    title: String, subtitle: String, glyph: SettingsGlyph, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, destructive: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    val colors = TradingColors
    val ink = if (!enabled) colors.Muted else if (destructive) colors.Loss else colors.Ink
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsIconTile(glyph, destructive = destructive)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = colors.Muted)
        }
        trailing?.invoke()
        Icon(SettingsGlyph.Chevron.vector, contentDescription = null, tint = colors.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsIconTile(glyph: SettingsGlyph, large: Boolean = false, destructive: Boolean = false) {
    val tint = if (destructive) TradingColors.Loss else TradingColors.Accent
    Box(Modifier.size(if (large) 44.dp else 32.dp).background(tint.copy(alpha = .1f), RoundedCornerShape(if (large) 14.dp else 10.dp)),
        contentAlignment = Alignment.Center) {
        Icon(glyph.vector, contentDescription = null, tint = tint, modifier = Modifier.size(if (large) 24.dp else 18.dp))
    }
}

internal enum class SettingsGlyph { Account, Add, Theme, Chart, History, Sync, Reset, Disconnect, Chevron, Collapse;
    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                when (this@SettingsGlyph) {
                    Account -> {
                        moveTo(16f, 7f); curveTo(16f, 12.3f, 8f, 12.3f, 8f, 7f); curveTo(8f, 1.7f, 16f, 1.7f, 16f, 7f)
                        moveTo(4f, 21f); lineTo(4f, 18f); curveTo(4f, 12f, 20f, 12f, 20f, 18f); lineTo(20f, 21f)
                    }
                    Add -> { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
                    Theme -> {
                        moveTo(12f, 3f); curveTo(0f, 3f, 0f, 21f, 12f, 21f); curveTo(17f, 21f, 11f, 15f, 16f, 15f)
                        curveTo(24f, 15f, 22f, 3f, 12f, 3f); close()
                        moveTo(7f, 9f); lineTo(7.1f, 9f); moveTo(11f, 6f); lineTo(11.1f, 6f); moveTo(16f, 8f); lineTo(16.1f, 8f)
                    }
                    Chart -> {
                        moveTo(4f, 3f); lineTo(4f, 20f); lineTo(21f, 20f)
                        moveTo(7f, 15f); lineTo(11f, 10f); lineTo(15f, 13f); lineTo(21f, 5f)
                    }
                    History -> {
                        moveTo(4f, 9f); curveTo(7f, -1f, 22f, 2f, 21f, 13f); curveTo(20f, 23f, 6f, 24f, 3f, 15f)
                        moveTo(3f, 4f); lineTo(3f, 10f); lineTo(9f, 10f)
                        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
                    }
                    Sync, Reset -> {
                        moveTo(4f, 9f); curveTo(7f, 1f, 16f, 1f, 20f, 8f); lineTo(20f, 3f)
                        moveTo(20f, 8f); lineTo(15f, 8f)
                        moveTo(20f, 15f); curveTo(17f, 23f, 8f, 23f, 4f, 16f); lineTo(4f, 21f)
                        moveTo(4f, 16f); lineTo(9f, 16f)
                    }
                    Disconnect -> {
                        moveTo(10f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(10f, 20f)
                        moveTo(10f, 12f); lineTo(21f, 12f); moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f)
                    }
                    Chevron -> { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
                    Collapse -> { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
                }
            }
        }.build()
    }
}
