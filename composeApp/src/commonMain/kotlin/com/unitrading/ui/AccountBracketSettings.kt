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
import com.unitrading.app.riskAmount
import com.unitrading.broker.*
import kotlinx.coroutines.launch

@Composable
internal fun AccountBracketSettings(controller: AccountRiskController, broker: BrokerPlugin, account: AccountInfo, busy: Boolean) {
    val rules by controller.rules.collectAsState()
    val brackets = rules[broker.descriptor.id]?.get(account.id)?.brackets ?: AccountBrackets()
    val accountName = LocalAccountAliases.current.account(broker.descriptor.id, account)
    var editing by remember(broker, account.id) { mutableStateOf(false) }
    val title = "子账户订单风控"
    val amounts = listOfNotNull(
        brackets.loss?.let { "止损 ${money(it)} ${account.currency}" },
        brackets.profit?.let { "止盈 ${money(it)} ${account.currency}" },
    )
    val summary = when {
        controller.storageUnreadable -> "本机风控记录无法读取"
        brackets.sizingRisk != null -> "自动计算手数 · 风险 ${money(brackets.sizingRisk)} ${account.currency}"
        brackets.enabled -> amounts.joinToString(" · ")
        else -> "未启用"
    }
    SettingsRow(title, summary, AppIcons.Candlestick, "edit-account-brackets", { editing = true },
        enabled = !busy, navigates = true)
    if (!editing) return

    val scope = rememberCoroutineScope()
    var loss by remember { mutableStateOf(brackets.loss?.let { money(it) }.orEmpty()) }
    var profit by remember { mutableStateOf(brackets.profit?.let { money(it) }.orEmpty()) }
    var sizing by remember { mutableStateOf(brackets.sizingRisk != null) }
    var sizingAmount by remember { mutableStateOf(brackets.sizingRisk?.let { money(it) }.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lossValue = riskAmount(loss)
    val profitValue = riskAmount(profit)
    val sizingValue = riskAmount(sizingAmount)
    val valid = if (sizing) sizingAmount.isBlank() || sizingValue != null
        else (loss.isBlank() || lossValue != null) && (profit.isBlank() || profitValue != null)
    AlertDialog(
        onDismissRequest = { if (!saving) editing = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$accountName · ${account.currency}")
                SegmentedChoice(listOf(false, true), sizing, { if (it) "风险计算手数" else "金额止损止盈" },
                    { if (it) "account-mode-sizing" else "account-mode-brackets" }, { sizing = it }, Modifier.fillMaxWidth(), enabled = !saving)
                if (sizing) {
                    OutlinedTextField(sizingAmount, { sizingAmount = it }, Modifier.fillMaxWidth().testTag("account-sizing-risk"),
                        label = { Text("单笔风险金额 · ${account.currency}") }, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = sizingAmount.isNotBlank() && sizingValue == null)
                    Text("下单时填写止损价 SL，手数按合约步长向下取整。留空可关闭此设置。", style = MaterialTheme.typography.bodySmall)
                    Text("风险按毛亏损估算，未计手续费与成交滑点。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("按整笔订单的金额和手数计算止损止盈。止损向较小亏损取整，止盈向较大利润取整。")
                    OutlinedTextField(loss, { loss = it }, Modifier.fillMaxWidth().testTag("account-bracket-loss"),
                        label = { Text("风险金额 · ${account.currency}") }, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = loss.isNotBlank() && lossValue == null)
                    OutlinedTextField(profit, { profit = it }, Modifier.fillMaxWidth().testTag("account-bracket-profit"),
                        label = { Text("利润金额 · ${account.currency}") }, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = profit.isNotBlank() && profitValue == null)
                    Text("留空可关闭对应设置。金额按毛盈亏估算，未计手续费与成交滑点。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = TradingColors.Loss) }
            }
        },
        confirmButton = {
            TextButton({
                saving = true
                scope.launch {
                    error = controller.configureBrackets(broker, account.id,
                        if (sizing) AccountBrackets(sizingRisk = sizingValue) else AccountBrackets(lossValue, profitValue))
                    saving = false
                    if (error == null) editing = false
                }
            }, enabled = valid && !saving, modifier = Modifier.testTag("save-account-brackets")) { Text("应用") }
        },
        dismissButton = { TextButton({ editing = false }, enabled = !saving) { Text("取消") } },
    )
}
