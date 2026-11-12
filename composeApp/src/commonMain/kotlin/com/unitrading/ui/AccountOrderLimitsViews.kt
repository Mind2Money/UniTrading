package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
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
    val catalog by broker.catalog.collectAsState()
    val unit = LotCapUnit.of(catalog.instruments)
    val limits = rules[broker.descriptor.id]?.get(account.id)?.orderLimits ?: AccountOrderLimits()
    val accountName = LocalAccountAliases.current.account(broker.descriptor.id, account)
    var editing by remember(broker, account.id) { mutableStateOf(false) }
    val summary = when {
        unreadable -> "本机风控记录无法读取"
        limits.maxLots == null && limits.blockedSymbols.isEmpty() -> "未设置限制"
        else -> listOfNotNull(
            limits.maxLots?.let { "最大 ${unit.format(it)} ${unit.label}" },
            limits.blockedSymbols.size.takeIf { it > 0 }?.let { "禁用 $it 个品种" },
        ).joinToString(" · ")
    }
    SettingsRow("手数与品种限制", summary, AppIcons.Rule, "edit-account-order-limits", { editing = true },
        enabled = !busy && !unreadable, navigates = true)
    if (editing) {
        val scope = rememberCoroutineScope()
        val type = MaterialTheme.typography
        var lots by remember { mutableStateOf(limits.maxLots?.let(unit::format).orEmpty()) }
        var symbols by remember { mutableStateOf(limits.blockedSymbols.sorted().joinToString(", ")) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val max = unit.parse(lots)
        val blocked = parseRestrictedSymbols(symbols)
        val valid = (lots.isBlank() || max != null) && blocked != null
        // On a hedging account every order opens a position of its own: both sides add up, and no order reduces one.
        val hedging = broker.capabilities.hedging
        FormDialog("手数与品种限制", "保存", valid && !saving, {
            blocked?.let { codes ->
                saving = true
                scope.launch {
                    error = controller.configureOrders(broker, account.id, AccountOrderLimits(max, codes))
                    saving = false
                    if (error == null) editing = false
                }
            }
        }, { editing = false }, "account-order-limits-editor", "cancel-account-order-limits", "save-account-order-limits", dismissEnabled = !saving) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$accountName · ${account.id}", style = type.titleSmall)
                Text(if (hedging) "限制只在本应用生效，仍可手动撤单和平仓。" else "限制只在本应用生效。平仓、撤单和减仓单不受限制。",
                    style = type.bodyMedium, color = TradingColors.Muted)
                OutlinedTextField(lots, { lots = it }, Modifier.fillMaxWidth().testTag("account-max-lots"),
                    label = { Text("最大持仓（${unit.label}，留空不限）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = if (unit.whole) KeyboardType.Number else KeyboardType.Decimal),
                    supportingText = { Text(if (hedging) "持仓、未成交开仓挂单和新单合计，多空不抵扣。"
                        else "按每个品种的持仓加未成交开仓挂单可能形成的最大持仓合计。止损、止盈等减仓单不占额度。") },
                    isError = lots.isNotBlank() && max == null)
                OutlinedTextField(symbols, { symbols = it }, Modifier.fillMaxWidth().testTag("account-blocked-symbols"),
                    label = { Text("禁止交易品种（留空不限）") }, enabled = !saving, maxLines = 4,
                    supportingText = { Text("用逗号或换行分隔。MES 禁止 MES 连续合约及各月份合约，MESZ6 只禁该合约。") },
                    isError = blocked == null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = type.bodySmall) }
            }
        }
    }
}

/** What a flatten is about to act on. The [skipped] accounts are listed with the reason and left alone. */
internal data class FlattenReview(
    val all: Boolean, val targets: List<FlattenTarget>, val skipped: List<Pair<FlattenTarget, FlattenSkip>> = emptyList(),
)

/**
 * One account in a confirmation or its result: alias, login and environment on the first line, then its id and what
 * applies to it in [detail]. [muted] is for an account the action leaves alone; the lines read as one item.
 */
@Composable
internal fun ConfirmationAccount(
    broker: BrokerPlugin, account: AccountInfo, detail: String?, detailColor: Color = TradingColors.Muted, muted: Boolean = false,
    titleColor: Color = if (muted) TradingColors.Muted else MaterialTheme.colorScheme.onSurface,
) {
    val names = LocalAccountAliases.current
    val descriptor = broker.descriptor
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${names.account(descriptor.id, account)} · ${names.login(descriptor)} · ${descriptor.environment.label}",
            style = MaterialTheme.typography.titleSmall, color = titleColor)
        Text(listOfNotNull(account.id, detail).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = detailColor)
    }
}

/** What a target of a flatten holds as the dialog shows it; null where its login cannot open the account. */
@Composable
private fun holdings(target: FlattenTarget): String? {
    val state = remember(target) { runCatching { target.broker.openSession(target.account.id).state }.getOrNull() } ?: return null
    val snapshot by state.collectAsState()
    return "持仓 ${snapshot.positions.size} · 挂单 ${snapshot.orders.size}"
}

@Composable
internal fun FlattenConfirmation(review: FlattenReview, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val type = MaterialTheme.typography
    val muted = TradingColors.Muted
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (review.all) "平仓全部子账户？" else "平仓此子账户？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (review.targets.isEmpty()) Text("没有可以处理的子账户。", style = type.bodySmall, color = muted)
                else Text("将处理以下 ${review.targets.size} 个子账户的全部持仓和未成交挂单，包括确认后在执行前进入券商快照的订单。",
                    style = type.bodySmall, color = muted)
                review.targets.forEach { ConfirmationAccount(it.broker, it.account, holdings(it)) }
                // Nothing can be sent for these, so they are neither targets nor failures afterwards.
                if (review.skipped.isNotEmpty()) Text("以下 ${review.skipped.size} 个子账户不处理：", style = type.bodySmall, color = muted)
                review.skipped.forEach { (target, reason) ->
                    ConfirmationAccount(target.broker, target.account, if (reason == FlattenSkip.READ_ONLY) "只读，跳过" else "未连接，跳过", muted = true)
                }
                if (review.targets.isNotEmpty()) Text("先撤会开仓或加仓的挂单；止损、止盈等减仓单保留到平仓确认后再撤。", style = type.bodySmall, color = muted)
            }
        },
        // The action is on the right, as in every confirmation of the app, closing one position included. It names
        // what it does in the error colour, and it is asked even with "跳过交易确认" on.
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("confirm-bulk-flatten"), enabled = review.targets.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("平仓") }
        },
        dismissButton = { TextButton(onCancel, Modifier.testTag("cancel-bulk-flatten")) { Text("取消") } },
    )
}

@Composable
internal fun FlattenResults(outcomes: List<FlattenOutcome>, onClose: () -> Unit) {
    val count = outcomes.count { it.confirmed }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("平仓结果") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已确认空仓且无挂单 $count / ${outcomes.size} 个子账户")
                outcomes.forEach { result ->
                    val (broker, child) = result.target
                    val message = result.note ?: (if (result.confirmed) FlattenStatus.FLAT else FlattenStatus.FAILED).text()
                    val error = MaterialTheme.colorScheme.error
                    if (result.confirmed) ConfirmationAccount(broker, child, message)
                    else ConfirmationAccount(broker, child, message, detailColor = error, titleColor = error)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("关闭") } },
    )
}
