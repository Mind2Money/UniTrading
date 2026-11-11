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
    val rule = rules[plugin.descriptor.id]?.get(account.id) ?: AccountRiskRule()
    val today by produceState(controller.today(), controller) {
        while (true) {
            delay(1_000)
            value = controller.today()
        }
    }
    val accountName = LocalAccountAliases.current.account(plugin.descriptor.id, account)
    var open by remember(plugin.descriptor.id, account.id) { mutableStateOf(false) }
    val detail = when {
        controller.storageUnreadable -> "本机风控记录无法读取"
        rule.frozen(today) -> "规则已冻结至 ${rule.frozenUntil} 00:00（芝加哥）"
        rule.limits != null -> listOfNotNull(
            rule.limits.loss?.let { "亏损上限 ${money(it)} ${account.currency}" },
            rule.limits.profit?.let { "盈利目标 ${money(it)} ${account.currency}" },
        ).joinToString(" · ")
        else -> "未启用"
    }
    SettingsRow("子账户交易锁定", detail, AppIcons.Key, "edit-account-risk", { open = true },
        enabled = !busy, navigates = true)
    if (open) AccountRiskDialog(account, accountName, rule, rule.frozen(today), controller.storageUnreadable, { open = false }) { limits, freeze ->
        controller.configure(plugin, account.id, limits, freeze)
    }
}

private fun thresholdText(value: Long?) = value?.let {
    "${it / 1_000_000}.${(it / 10_000 % 100).toString().padStart(2, '0')}"
}.orEmpty()

@Composable
private fun AccountRiskDialog(
    account: AccountInfo, accountName: String, rule: AccountRiskRule, frozen: Boolean, unreadable: Boolean,
    onDismiss: () -> Unit, onSave: suspend (RiskLimits?, RiskFreeze?) -> String?,
) {
    val scope = rememberCoroutineScope()
    var loss by remember { mutableStateOf(thresholdText(rule.limits?.loss)) }
    var profit by remember { mutableStateOf(thresholdText(rule.limits?.profit)) }
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
    val editable = !frozen && !unreadable && !saving
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
                if (freezeOnSave) append("规则将在芝加哥时间${if (freeze == RiskFreeze.DAY) "次日" else "下周一"}零点前无法修改或关闭。")
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
                Text("使用券商当日总盈亏。触发后停止本应用开仓和改单，可手动撤单和平仓。", style = MaterialTheme.typography.bodySmall)
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
                Text(if (action == RiskAction.LOCK) "保留券商已有挂单和持仓。" else "先撤可能开仓的挂单，再请求平仓并核对结果。",
                    style = MaterialTheme.typography.bodySmall)
                Text("设置冻结（芝加哥时间）", style = MaterialTheme.typography.labelLarge)
                Text("冻结前必须设置亏损上限。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(freeze == RiskFreeze.DAY, { freeze = RiskFreeze.DAY }, label = { Text("当日") }, enabled = editable)
                    FilterChip(freeze == RiskFreeze.WEEK, { freeze = RiskFreeze.WEEK }, label = { Text("当周") }, enabled = editable)
                }
                if (frozen) Text("冻结至 ${rule.frozenUntil} 00:00，期间无法修改或停用。")
                else TextButton({ requestSave(true) }, enabled = editable && limits?.loss != null,
                    modifier = Modifier.testTag("freeze-risk-settings")) { Text("锁定亏损 / 盈利设置") }
                Text("仅在应用运行且连接有效时监控。其他客户端仍可下单。", style = MaterialTheme.typography.bodySmall)
                if (unreadable) Text("风控设置无法读取。", color = TradingColors.Loss)
                error?.let { Text(it, color = TradingColors.Loss) }
                if (rule.limits != null && editable) TextButton({ save(null, null) }) { Text("停用规则") }
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

@Composable
internal fun AccountRiskNotice(message: String?, trigger: RiskTrigger?, currency: String, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(modifier.fillMaxWidth().testTag("account-risk-notice"), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.titleSmall)
            if (trigger != null) Text("触发盈亏 ${money(trigger.pnl, true)} $currency", style = MaterialTheme.typography.bodySmall)
            trigger?.flattenStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
