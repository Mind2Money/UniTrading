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
import com.unitrading.app.AccountBrackets
import com.unitrading.app.AccountRiskController
import com.unitrading.app.LotCapUnit
import com.unitrading.app.riskAmount
import com.unitrading.broker.*
import kotlinx.coroutines.launch

@Composable
internal fun AccountBracketSettings(controller: AccountRiskController, broker: BrokerPlugin, account: AccountInfo, busy: Boolean) {
    val rules by controller.rules.collectAsState()
    val unreadable by controller.storageUnreadable.collectAsState()
    val brackets = rules[broker.descriptor.id]?.get(account.id)?.brackets ?: AccountBrackets()
    val accountName = LocalAccountAliases.current.account(broker.descriptor.id, account)
    var editing by remember(broker, account.id) { mutableStateOf(false) }
    val title = "订单风控"
    val amounts = listOfNotNull(
        brackets.loss?.let { "止损 ${money(it)} ${account.currency}" },
        brackets.profit?.let { "止盈 ${money(it)} ${account.currency}" },
    )
    // The largest sized order is counted in the login's unit, as the lot cap is: whole contracts for futures.
    val unit = LotCapUnit.of(broker.instruments)
    val summary = when {
        unreadable -> "本机风控记录无法读取"
        brackets.sizingRisk != null -> "自动计算手数 · 风险 ${money(brackets.sizingRisk)} ${account.currency} · " +
            (brackets.sizingMaxLots?.let { "单笔最多 ${unit.format(it)} ${unit.label}" } ?: "未填单笔最大数量，暂不能开仓")
        brackets.enabled -> amounts.joinToString(" · ")
        else -> "未启用"
    }
    SettingsRow(title, summary, AppIcons.Candlestick, "edit-account-brackets", { editing = true },
        enabled = !busy && !unreadable, navigates = true)
    if (!editing) return

    val scope = rememberCoroutineScope()
    val type = MaterialTheme.typography
    val muted = TradingColors.Muted
    var loss by remember { mutableStateOf(brackets.loss?.let { money(it) }.orEmpty()) }
    var profit by remember { mutableStateOf(brackets.profit?.let { money(it) }.orEmpty()) }
    var sizing by remember { mutableStateOf(brackets.sizingRisk != null) }
    var sizingAmount by remember { mutableStateOf(brackets.sizingRisk?.let { money(it) }.orEmpty()) }
    var sizingLots by remember { mutableStateOf(brackets.sizingMaxLots?.let(unit::format).orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lossValue = riskAmount(loss)
    val profitValue = riskAmount(profit)
    val sizingValue = riskAmount(sizingAmount)
    val sizingLotsValue = unit.parse(sizingLots)
    // A blank amount switches the sizing off; with one, the largest order has to be given too.
    val valid = if (sizing) sizingAmount.isBlank() || (sizingValue != null && sizingLotsValue != null)
        else (loss.isBlank() || lossValue != null) && (profit.isBlank() || profitValue != null)
    // On a hedging account every order opens a position of its own, so there is no reducing order to speak of.
    fun reductions(text: String) = if (broker.capabilities.hedging) "" else text
    FormDialog(title, "保存", valid && !saving, {
        saving = true
        scope.launch {
            error = controller.configureBrackets(broker, account.id,
                if (sizing) AccountBrackets(sizingRisk = sizingValue, sizingMaxLots = sizingLotsValue.takeIf { sizingValue != null })
                else AccountBrackets(lossValue, profitValue))
            saving = false
            if (error == null) editing = false
        }
    }, { editing = false }, "account-brackets-editor", "cancel-account-brackets", "save-account-brackets", dismissEnabled = !saving) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$accountName · ${account.id}", style = type.titleSmall)
            Text("下单时按金额设置止损止盈，或按风险金额计算手数。", style = type.bodyMedium, color = muted)
            SegmentedChoice(listOf(false, true), sizing, { if (it) "风险计算手数" else "金额止损止盈" },
                { if (it) "account-mode-sizing" else "account-mode-brackets" }, { sizing = it }, Modifier.fillMaxWidth(), enabled = !saving)
            if (sizing) {
                OutlinedTextField(sizingAmount, { sizingAmount = it }, Modifier.fillMaxWidth().testTag("account-sizing-risk"),
                    label = { Text("单笔风险金额（${account.currency}）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = sizingAmount.isNotBlank() && sizingValue == null)
                OutlinedTextField(sizingLots, { sizingLots = it }, Modifier.fillMaxWidth().testTag("account-sizing-max-lots"),
                    label = { Text("单笔最大数量（${unit.label}）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = if (unit.whole) KeyboardType.Number else KeyboardType.Decimal),
                    supportingText = { Text("必填。止损离入场价越近，算出的手数越大；自动手数不会超过这个数量，也不会超过子账户最大持仓和服务器的可下单上限。") },
                    isError = sizingAmount.isNotBlank() && sizingLotsValue == null)
                Text("下单时填写止损价 SL，手数按合约步长向下取整。风险金额留空可关闭此设置。", style = type.bodySmall, color = muted)
                Text(reductions("减仓单按输入的数量下单，不按风险计算。") + "风险按毛亏损估算，未计手续费与成交滑点。", style = type.bodySmall, color = muted)
            } else {
                Text("按整笔订单的金额和手数计算止损止盈。止损向较小亏损取整，止盈向较大利润取整。", style = type.bodySmall, color = muted)
                OutlinedTextField(loss, { loss = it }, Modifier.fillMaxWidth().testTag("account-bracket-loss"),
                    label = { Text("风险金额（${account.currency}）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = loss.isNotBlank() && lossValue == null)
                OutlinedTextField(profit, { profit = it }, Modifier.fillMaxWidth().testTag("account-bracket-profit"),
                    label = { Text("利润金额（${account.currency}）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = profit.isNotBlank() && profitValue == null)
                Text("留空可关闭对应设置。" + reductions("减仓单不附加止损止盈。") + "金额按毛盈亏估算，未计手续费与成交滑点。",
                    style = type.bodySmall, color = muted)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = type.bodySmall) }
        }
    }
}
