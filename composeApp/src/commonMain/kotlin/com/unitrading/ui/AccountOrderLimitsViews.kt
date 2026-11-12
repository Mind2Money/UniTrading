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
import kotlinx.coroutines.launch

@Composable
internal fun AccountOrderLimitSettings(
    controller: AccountRiskController, broker: BrokerPlugin, account: AccountInfo, busy: Boolean,
) {
    val rules by controller.rules.collectAsState()
    val unreadable by controller.storageUnreadable.collectAsState()
    val limits = rules[broker.descriptor.id]?.get(account.id)?.orderLimits ?: AccountOrderLimits()
    val accountName = LocalAccountAliases.current.account(broker.descriptor.id, account)
    var editing by remember(broker, account.id) { mutableStateOf(false) }
    val summary = when {
        unreadable -> "本机风控记录无法读取"
        limits.maxLots == null && limits.blockedSymbols.isEmpty() -> "未设置限制"
        else -> listOfNotNull(
            limits.maxLots?.let { "最大 ${formatFixed(it, 2)} 手" },
            limits.blockedSymbols.size.takeIf { it > 0 }?.let { "禁用 $it 个品种" },
        ).joinToString(" · ")
    }
    SettingsRow("子账户手数与品种限制", summary, AppIcons.Tune, "edit-account-order-limits", { editing = true },
        enabled = !busy && !unreadable, navigates = true)
    if (editing) {
        val scope = rememberCoroutineScope()
        var lots by remember { mutableStateOf(limits.maxLots?.let { formatFixed(it, 2) }.orEmpty()) }
        var symbols by remember { mutableStateOf(limits.blockedSymbols.sorted().joinToString(", ")) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val max = parseFixed(lots, 2)?.takeIf { it in 1..999_999_999L }
        val blocked = parseRestrictedSymbols(symbols)
        val valid = (lots.isBlank() || max != null) && blocked != null
        // On a hedging account every order opens a position of its own: both sides add up, and no order reduces one.
        val hedging = broker.capabilities.hedging
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text("子账户手数与品种限制") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$accountName · ${account.id}")
                    OutlinedTextField(lots, { lots = it }, Modifier.fillMaxWidth().testTag("account-max-lots"),
                        label = { Text("最大手数（留空不限）") }, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text(if (hedging) "持仓、未成交开仓挂单和新单合计，多空不抵扣。"
                            else "按每个品种的持仓加未成交开仓挂单可能形成的最大持仓合计。止损、止盈等减仓单不占额度。") },
                        isError = lots.isNotBlank() && max == null)
                    OutlinedTextField(symbols, { symbols = it }, Modifier.fillMaxWidth().testTag("account-blocked-symbols"),
                        label = { Text("禁止交易品种（留空不限）") }, enabled = !saving, maxLines = 4,
                        supportingText = { Text("用逗号或换行分隔。MES 禁止 MES 连续合约及各月份合约，MESZ6 只禁该合约。") },
                        isError = blocked == null)
                    Text(if (hedging) "限制只在本应用生效，仍可手动撤单和平仓。" else "限制只在本应用生效。平仓、撤单和减仓单不受限制。",
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = TradingColors.Loss) }
                }
            },
            confirmButton = {
                TextButton({
                    val codes = blocked ?: return@TextButton
                    saving = true
                    scope.launch {
                        error = controller.configureOrders(broker, account.id, AccountOrderLimits(max, codes))
                        saving = false
                        if (error == null) editing = false
                    }
                }, enabled = valid && !saving, modifier = Modifier.testTag("save-account-order-limits")) { Text("保存") }
            },
            dismissButton = { TextButton({ editing = false }, enabled = !saving) { Text("取消") } },
        )
    }
}

/** What a flatten is about to act on. The [skipped] accounts are listed with the reason and left alone. */
internal data class FlattenReview(
    val all: Boolean, val targets: List<FlattenTarget>, val skipped: List<Pair<FlattenTarget, FlattenSkip>> = emptyList(),
)

@Composable
internal fun FlattenConfirmation(review: FlattenReview, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val aliases = LocalAccountAliases.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (review.all) "确认平仓全部子账户？" else "确认一键平仓？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fun name(target: FlattenTarget) = "${aliases.login(target.broker.descriptor)} · ${target.broker.descriptor.environment.label}\n" +
                    "${aliases.account(target.broker.descriptor.id, target.account)} · ${target.account.id}"
                if (review.targets.isEmpty()) Text("没有可以处理的子账户。")
                else {
                    Text("将处理以下 ${review.targets.size} 个子账户的全部持仓和未成交挂单，包括确认后在执行前进入券商快照的订单。")
                    Text("先撤会开仓或加仓的挂单；止损、止盈等减仓单保留到平仓确认后再撤。")
                }
                review.targets.forEach { Text(name(it), style = MaterialTheme.typography.bodySmall) }
                // Nothing can be sent for these, so they are neither targets nor failures afterwards.
                if (review.skipped.isNotEmpty()) Text("以下 ${review.skipped.size} 个子账户不处理：")
                review.skipped.forEach { (target, reason) ->
                    Text("${name(target)}\n${if (reason == FlattenSkip.READ_ONLY) "只读，跳过" else "未连接，跳过"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        // Intentionally reverse the normal close dialog: the safe action is on the right.
        dismissButton = {
            TextButton(onConfirm, Modifier.testTag("confirm-bulk-flatten"), enabled = review.targets.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确定平仓") }
        },
        confirmButton = { Button(onCancel, Modifier.testTag("cancel-bulk-flatten")) { Text("取消") } },
    )
}

@Composable
internal fun FlattenResults(outcomes: List<FlattenOutcome>, onClose: () -> Unit) {
    val aliases = LocalAccountAliases.current
    val count = outcomes.count { it.confirmed }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("撤单和平仓结果") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已确认空仓且无挂单 $count / ${outcomes.size} 个子账户")
                outcomes.forEach { result ->
                    val (broker, child) = result.target
                    val message = result.note ?: (if (result.confirmed) FlattenStatus.FLAT else FlattenStatus.FAILED).text()
                    Text("${aliases.login(broker.descriptor)} · ${aliases.account(broker.descriptor.id, child)}（${child.id}）\n$message",
                        color = if (result.confirmed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("关闭") } },
    )
}
