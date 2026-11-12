package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AccountRiskSettings(
    controller: AccountRiskController, plugin: BrokerPlugin, account: AccountInfo, busy: Boolean,
) {
    val rules by controller.rules.collectAsState()
    val unreadable by controller.storageUnreadable.collectAsState()
    val rule = rules[plugin.descriptor.id]?.get(account.id) ?: AccountRiskRule()
    val supported = plugin.capabilities.tradingDayPnl
    // A freeze ends with a broker trading day, which arrives with the account's P&L snapshots and not with a rule change.
    val frozen by produceState(controller.frozen(plugin, account.id), controller, plugin, account.id, rule.frozenUntil) {
        while (true) {
            value = controller.frozen(plugin, account.id)
            delay(1_000)
        }
    }
    val accountName = LocalAccountAliases.current.account(plugin.descriptor.id, account)
    var open by remember(plugin.descriptor.id, account.id) { mutableStateOf(false) }
    val detail = when {
        unreadable -> "本机风控记录无法读取"
        !supported -> PNL_LOCK_UNSUPPORTED + if (rule.limits != null) "；已保存的规则不生效，可在此停用" else ""
        frozen -> "规则已冻结，券商交易日 ${rule.frozenUntil} 起可修改"
        rule.limits != null -> listOfNotNull(
            rule.limits.loss?.let { "亏损上限 ${money(it)} ${account.currency}" },
            rule.limits.profit?.let { "盈利目标 ${money(it)} ${account.currency}" },
        ).joinToString(" · ")
        else -> "未启用"
    }
    // Without the broker's day P&L and trading day there is nothing to set up; a rule left by an older file can still be cleared.
    SettingsRow("子账户交易锁定", detail, AppIcons.Key, "edit-account-risk", { open = true },
        enabled = !busy && !unreadable && (supported || rule.limits != null), navigates = true)
    if (open) AccountRiskDialog(account, accountName, rule, frozen, supported, { open = false }) { limits, freeze ->
        controller.configure(plugin, account.id, limits, freeze)
    }
}

@Composable
private fun AccountRiskDialog(
    account: AccountInfo, accountName: String, rule: AccountRiskRule, frozen: Boolean, supported: Boolean,
    onDismiss: () -> Unit, onSave: suspend (RiskLimits?, RiskFreeze?) -> String?,
) {
    val scope = rememberCoroutineScope()
    var loss by remember { mutableStateOf(rule.limits?.loss?.let { money(it) }.orEmpty()) }
    var profit by remember { mutableStateOf(rule.limits?.profit?.let { money(it) }.orEmpty()) }
    var action by remember { mutableStateOf(rule.limits?.action ?: RiskAction.LOCK) }
    var freeze by remember { mutableStateOf(RiskFreeze.DAY) }
    var confirm by remember { mutableStateOf(false) }
    var freezeOnSave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsedLoss = riskAmount(loss)
    val parsedProfit = riskAmount(profit)
    val limits = RiskLimits(parsedLoss, parsedProfit, action).takeIf {
        it.valid() && (loss.isBlank() || parsedLoss != null) && (profit.isBlank() || parsedProfit != null)
    }
    val editable = supported && !frozen && !saving
    fun save(value: RiskLimits?, period: RiskFreeze?) {
        saving = true
        error = null
        scope.launch {
            val message = onSave(value, period)
            saving = false
            if (message == null) onDismiss() else error = message
        }
    }
    fun requestSave(freezeSettings: Boolean) {
        val value = limits ?: return
        if (freezeSettings || value.action == RiskAction.FLATTEN) {
            freezeOnSave = freezeSettings
            confirm = true
        } else save(value, null)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirm = false },
            title = { Text(if (freezeOnSave) "确认锁定盈亏设置？" else "确认启用自动平仓？") },
            text = { Text(buildString {
                append("子账户 $accountName（${account.id}）。")
                if (freezeOnSave) append("规则在券商进入${if (freeze == RiskFreeze.DAY) "下一个交易日" else "下周一的交易日"}之前无法修改或关闭，调整本机时间不会提前解除。")
                if (action == RiskAction.FLATTEN) append("一旦券商当日总盈亏达到任一额度，应用会锁定开仓、撤销挂单并提交平仓，可能立即触发。")
            }) },
            confirmButton = { TextButton({
                confirm = false
                save(limits, freeze.takeIf { freezeOnSave })
            }, enabled = !saving && limits != null) { Text("确认启用") } },
            dismissButton = { TextButton({ confirm = false }) { Text("返回") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("子账户锁定规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("risk-editor"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("$accountName · ${account.id}", style = MaterialTheme.typography.titleSmall)
                if (!supported) Text("$PNL_LOCK_UNSUPPORTED。已保存的规则不生效，可以停用。", color = TradingColors.Loss)
                Text("使用券商当日总盈亏。触发后本应用暂停开仓、加仓和放宽止损；仍可平仓、撤单、收紧止损或挂减仓单。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(loss, { loss = it }, Modifier.fillMaxWidth().testTag("risk-loss"),
                    label = { Text("亏损上限（${account.currency}）") }, singleLine = true, enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = loss.isNotBlank() && parsedLoss == null)
                OutlinedTextField(profit, { profit = it }, Modifier.fillMaxWidth().testTag("risk-profit"),
                    label = { Text("盈利目标（${account.currency}）") }, singleLine = true, enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = profit.isNotBlank() && parsedProfit == null)
                Text("触发后", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(action == RiskAction.LOCK, { action = RiskAction.LOCK }, label = { Text("只锁定") }, enabled = editable)
                    FilterChip(action == RiskAction.FLATTEN, { action = RiskAction.FLATTEN }, label = { Text("平仓并锁定") }, enabled = editable)
                }
                Text(if (action == RiskAction.LOCK) "保留券商已有挂单和持仓。"
                    else "先撤会开仓或加仓的挂单，再请求平仓并核对结果；止损等减仓单保留到平仓确认后再撤。",
                    style = MaterialTheme.typography.bodySmall)
                Text("设置冻结（按券商交易日）", style = MaterialTheme.typography.labelLarge)
                Text("冻结前必须设置亏损上限，并且已收到券商的交易日数据。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(freeze == RiskFreeze.DAY, { freeze = RiskFreeze.DAY }, label = { Text("当日") }, enabled = editable)
                    FilterChip(freeze == RiskFreeze.WEEK, { freeze = RiskFreeze.WEEK }, label = { Text("当周") }, enabled = editable)
                }
                if (frozen) Text("已冻结：券商交易日到 ${rule.frozenUntil} 之前无法修改或停用。")
                else TextButton({ requestSave(true) }, enabled = editable && limits?.loss != null,
                    modifier = Modifier.testTag("freeze-risk-settings")) { Text("锁定亏损 / 盈利设置") }
                Text("仅在应用运行且连接有效时监控。其他客户端仍可下单。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = TradingColors.Loss) }
                if (rule.limits != null && !frozen && !saving) TextButton({ save(null, null) }, Modifier.testTag("disable-risk-settings")) { Text("停用规则") }
            }
        },
        confirmButton = {
            TextButton({ requestSave(false) }, enabled = editable && limits != null, modifier = Modifier.testTag("save-risk-settings")) {
                Text("保存规则")
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("关闭") } },
    )
}

/** Shown while the saved risk rules cannot be read: the way on is to start over with none, which the user has to confirm. */
@Composable
internal fun AccountRiskRecovery(controller: AccountRiskController, busy: Boolean) {
    val unreadable by controller.storageUnreadable.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (!unreadable) return
    SettingsRow("重置风控设置", if (failed) "重置失败，请检查本机存储后重试" else "本机风控记录无法读取，已暂停开仓和改单",
        AppIcons.DeleteSweep, "reset-account-risk", { confirm = true }, enabled = !busy && !resetting, destructive = true)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("重置风控设置？") },
        text = { Text("本机风控记录无法读取。重置会清除这台设备上所有子账户的交易锁定、手数与品种限制、金额止损止盈规则，之后需要重新设置。应用会尽量把读不出的原记录另存一份备查。") },
        confirmButton = {
            TextButton({
                confirm = false
                resetting = true
                scope.launch {
                    failed = !controller.resetUnreadable()
                    resetting = false
                }
            }, Modifier.testTag("confirm-reset-account-risk"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除并重置") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
    )
}

internal fun FlattenStatus.text() = when (this) {
    FlattenStatus.WAITING -> "等待执行撤单和平仓"
    FlattenStatus.IN_PROGRESS -> "平仓结果待核对，请检查券商持仓和挂单"
    FlattenStatus.FAILED -> "未确认全部撤单和平仓，请检查券商持仓和挂单"
    FlattenStatus.FLAT -> "券商已确认无持仓、无挂单"
    FlattenStatus.UNSAVED -> "锁定未能保存，请检查存储并手动核对持仓"
}

@Composable
internal fun AccountRiskNotice(message: String?, trigger: RiskTrigger?, currency: String, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(modifier.fillMaxWidth().testTag("account-risk-notice"), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.titleSmall)
            if (trigger != null) Text("触发盈亏 ${money(trigger.pnl, true)} $currency", style = MaterialTheme.typography.bodySmall)
            trigger?.flattenStatus?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
