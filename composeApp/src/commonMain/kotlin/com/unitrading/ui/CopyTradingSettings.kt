package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.launch

private data class CopyFollowerInput(val fixed: Boolean = false, val quantity: String = "1", val maximum: String = "1")

@Composable
internal fun CopyTradingDialog(runtime: TradingRuntime, broker: BrokerPlugin, leaderId: String, onDismiss: () -> Unit) {
    val status by runtime.copyTrading.status.collectAsState()
    val recovery by runtime.copyTrading.recovery.collectAsState()
    val scope = rememberCoroutineScope()
    val catalog by broker.catalog.collectAsState()
    val savedConfigurations by runtime.copyTradingConfiguration.configurations.collectAsState()
    val saved = savedConfigurations[broker.descriptor.id]
    var selectedLeader by remember(broker) { mutableStateOf(saved?.leaderId ?: leaderId) }
    var leaderMenu by remember { mutableStateOf(false) }
    val accounts = catalog.accounts.filter { it.id != selectedLeader && !it.readOnly }
    var selected by remember(broker) { mutableStateOf(saved?.followers?.map { it.accountId }?.toSet() ?: emptySet()) }
    var symbol by remember(broker) { mutableStateOf(saved?.symbol.orEmpty()) }
    val inputs = remember(broker) { mutableStateMapOf<String, CopyFollowerInput>().apply {
        saved?.followers?.forEach { follower -> put(follower.accountId, CopyFollowerInput(follower.fixedContracts != null,
            (follower.fixedContracts ?: follower.multiplier).toString(), follower.maxContracts.toString())) }
    } }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    val leader = catalog.accounts.firstOrNull { it.id == selectedLeader }
    fun follower(accountId: String): CopyFollower? {
        val input = inputs[accountId] ?: CopyFollowerInput()
        val quantity = input.quantity.toIntOrNull() ?: return null
        val maximum = input.maximum.toIntOrNull() ?: return null
        if (maximum !in 1..1000 || quantity !in 1..(if (input.fixed) maximum else 100)) return null
        return CopyFollower(accountId, if (input.fixed) 1 else quantity, maximum, if (input.fixed) quantity else null)
    }
    val selectedFollowers = accounts.filter { it.id in selected }
    val followers = selectedFollowers.mapNotNull { follower(it.id) }
    val dated = catalog.instruments.firstOrNull { it.symbol == symbol.trim() && !it.isContinuous }
    val valid = leader != null && !leader.readOnly && selected.isNotEmpty() && dated != null &&
        followers.size == selected.size

    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text("同步并核对待确认订单") },
        text = { Text("请先在券商端逐一检查原连接所有主账户和跟随账户的订单、成交及持仓，并处理完毕。确认后会重新同步，只有全部空仓且无挂单才能清除此记录。停止不会撤销已发送订单。") },
        confirmButton = { TextButton({
            confirmRecovery = false
            scope.launch { error = runtime.copyTrading.clearRecovery(broker) }
        }) { Text("已核对，重新同步") } },
        dismissButton = { TextButton({ confirmRecovery = false }) { Text("取消") } },
    )
    if (confirm && valid) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("确认启动 Demo 跟单") },
        text = { Text("主账户：${leader?.name}\n跟随账户：${selectedFollowers.joinToString("、") { account -> val input = inputs[account.id] ?: CopyFollowerInput(); "${account.name}（${if (input.fixed) "固定 ${input.quantity} 张" else "${input.quantity} 倍"}，最多 ${input.maximum} 张）" }}\n合约：${dated?.symbol}\n\n跟单功能尚未完成，暂不能启动。") },
        confirmButton = { TextButton({
            val settings = CopyPlanSettings(selectedLeader, followers, dated!!.symbol)
            error = runtime.copyTrading.start(broker, settings)
            confirm = false
        }) { Text("确认启动") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账户跟单") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.message, color = if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("跟单功能仍在开发，当前不能启动。正式实现将只跟随 UniTrading 发出的订单。")
                recovery?.let { record ->
                    Text("有未确认订单：${record.symbol}，账户 ${record.accountIds.joinToString()}。请核对券商端。", color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ confirmRecovery = true }) { Text("同步并核对") }
                }
                if (status.running) {
                    Text("当前主账户：${status.settings?.leaderId.orEmpty()} · ${status.settings?.symbol.orEmpty()} · ${status.settings?.followers?.size ?: 0} 个跟随账户")
                    OutlinedButton({ runtime.copyTrading.stop() }) { Text("停止跟单") }
                } else {
                    Text("主账户")
                    Box {
                        OutlinedButton({ leaderMenu = true }) { Text(leader?.name ?: "选择主账户") }
                        DropdownMenu(expanded = leaderMenu, onDismissRequest = { leaderMenu = false }) {
                            catalog.accounts.filter { !it.readOnly }.forEach { account ->
                                DropdownMenuItem(text = { Text(account.name) }, onClick = {
                                    selectedLeader = account.id
                                    selected = selected - account.id
                                    leaderMenu = false
                                })
                            }
                        }
                    }
                    if (!broker.descriptor.id.startsWith("tradovate-")) Text("请先连接 Tradovate。", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(symbol, { symbol = it.trim().uppercase() }, label = { Text("到期合约，如 MESZ6") }, singleLine = true)
                    Text("选择跟随账户（只能在当前连接内选择）")
                    accounts.forEach { account ->
                        val input = inputs[account.id] ?: CopyFollowerInput()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(account.id in selected, { checked -> selected = if (checked) selected + account.id else selected - account.id })
                            Text(account.name, Modifier.padding(top = 12.dp))
                        }
                        if (account.id in selected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ inputs[account.id] = input.copy(fixed = !input.fixed) }) { Text(if (input.fixed) "固定张数" else "倍数") }
                                OutlinedTextField(input.quantity, { inputs[account.id] = input.copy(quantity = it.filter(Char::isDigit).take(4)) },
                                    Modifier.weight(1f), label = { Text(if (input.fixed) "固定张数" else "倍数") }, singleLine = true)
                                OutlinedTextField(input.maximum, { inputs[account.id] = input.copy(maximum = it.filter(Char::isDigit).take(4)) },
                                    Modifier.weight(1f), label = { Text("最大张数") }, singleLine = true)
                            }
                        }
                    }
                    if (accounts.isEmpty()) Text("当前连接没有其他可交易子账户")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val config = CopySavedConfiguration(broker.descriptor.id, selectedLeader,
                                followers.map { CopySavedFollower(it.accountId, it.fixedContracts, it.multiplier, it.maxContracts) }, dated?.symbol)
                            if (runtime.copyTradingConfiguration.save(config)) {
                                error = null; notice = "设置已保存；跟单功能仍在开发中。"
                            } else {
                                notice = null; error = "设置保存失败，请检查账户及本机存储。"
                            }
                        }
                    }, enabled = valid && broker.descriptor.id.startsWith("tradovate-")) { Text("保存设置") }
                    Button({ confirm = true }, enabled = false) { Text("开发中，暂不可启动") }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
    )
}
