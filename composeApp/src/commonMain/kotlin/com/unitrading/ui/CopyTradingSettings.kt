package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class CopyFollowerInput(val fixed: Boolean = false, val quantity: String = "1", val maximum: String = "1")
private data class PendingCopyCleanup(val config: CopySavedConfiguration, val accounts: List<CopyAccountExposure>)

@Composable
internal fun CopyTradingDialog(runtime: TradingRuntime, broker: BrokerPlugin, leaderId: String, onDismiss: () -> Unit) {
    val recovery by runtime.copyLegacyRecovery.recovery.collectAsState()
    val scope = rememberCoroutineScope()
    val catalog by broker.catalog.collectAsState()
    val savedConfigurations by runtime.copyTradingConfiguration.configurations.collectAsState()
    val saved = savedConfigurations[broker.descriptor.id]
    var selectedLeader by remember(broker) { mutableStateOf(saved?.leaderId ?: leaderId) }
    var leaderMenu by remember { mutableStateOf(false) }
    val accounts = catalog.accounts.filter { it.id != selectedLeader && !it.readOnly }
    var selected by remember(broker) { mutableStateOf(saved?.followers?.map { it.accountId }?.toSet() ?: emptySet()) }
    val inputs = remember(broker) { mutableStateMapOf<String, CopyFollowerInput>().apply {
        saved?.followers?.forEach { follower -> put(follower.accountId, CopyFollowerInput(follower.fixedContracts != null,
            (follower.fixedContracts ?: follower.multiplier).toString(), follower.maxContracts.toString())) }
    } }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var cleanup by remember { mutableStateOf<PendingCopyCleanup?>(null) }
    var cleanupResults by remember { mutableStateOf<List<FlattenOutcome>?>(null) }
    var clearing by remember { mutableStateOf(false) }
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
    val valid = leader != null && !leader.readOnly && selected.isNotEmpty() &&
        followers.size == selected.size

    fun inspect(ids: List<String>): CopyStartPreparation {
        if (runtime.registry.plugins.none { it === broker }) return CopyStartPreparation.Blocked("券商连接已变化，请重新选择账户")
        val snapshots = runCatching { ids.associateWith { broker.openSession(it).state.value } }.getOrNull()
            ?: return CopyStartPreparation.Blocked("无法读取所选账户，请检查连接")
        return CopyStartPreparationInspector.inspect(ids, broker.catalog.value, broker.market.value, snapshots)
    }
    suspend fun save(config: CopySavedConfiguration) {
        if (runtime.copyTradingConfiguration.save(config)) {
            error = null; notice = "设置已保存；跟单功能仍在开发中。"
        } else {
            notice = null; error = "设置保存失败，请检查账户及本机存储。"
        }
    }
    fun cancelCleanup() { cleanup = null; onDismiss() }

    cleanup?.let { review ->
        AlertDialog(
            onDismissRequest = ::cancelCleanup,
            title = { Text("所选账户有持仓或挂单") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("是否撤销以下所选账户的全部挂单，并平掉全部持仓？未选择的子账户不受影响。")
                    review.accounts.forEach { row ->
                        ConfirmationAccount(broker, row.account, "持仓 ${row.positions} · 挂单 ${row.orders}")
                    }
                    Text("处理会使用账户在执行时的最新状态。跟单功能尚未完成，本次处理不会启动跟单。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    cleanup = null
                    clearing = true
                    scope.launch {
                        try {
                            val targets = review.accounts.map { FlattenTarget(broker, it.account) }
                            val results = runtime.accountRisk.flattenAccounts(runtime.registry, targets)
                            val check = inspect(review.accounts.map { it.account.id })
                            cleanupResults = if (check == CopyStartPreparation.Ready) results else results.map { result ->
                                if (result.confirmed) result.copy(confirmed = false, note = "处理已提交，但完整账户状态尚未核对，请检查券商。") else result
                            }
                            if (results.any { !it.confirmed }) {
                                notice = null; error = "部分账户未确认空仓且无挂单，请核对券商后再操作。"
                            } else when (check) {
                                CopyStartPreparation.Ready -> save(review.config)
                                is CopyStartPreparation.NeedsCleanup -> { notice = null; error = "所选账户仍有持仓或挂单，请重新核对。" }
                                is CopyStartPreparation.Blocked -> { notice = null; error = check.reason }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            notice = null; error = "账户处理结果无法确认，请到券商核对。"
                        } finally {
                            clearing = false
                        }
                    }
                }, modifier = Modifier.testTag("copy-cleanup-confirm")) { Text("是，撤单并平仓") }
            },
            dismissButton = { TextButton(::cancelCleanup,
                modifier = Modifier.testTag("copy-cleanup-cancel")) { Text("否，退出且不保存") } },
        )
    }
    cleanupResults?.let { results -> FlattenResults(results) { cleanupResults = null } }

    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text("同步并核对待确认订单") },
        text = { Text("请先在券商端逐一检查原连接所有主账户和跟随账户的订单、成交及持仓，并处理完毕。确认后会重新同步，只有全部空仓且无挂单才能清除此记录。停止不会撤销已发送订单。") },
        confirmButton = { TextButton({
            confirmRecovery = false
            scope.launch { error = runtime.copyLegacyRecovery.clearRecovery(broker) }
        }) { Text("已核对，重新同步") } },
        dismissButton = { TextButton({ confirmRecovery = false }) { Text("取消") } },
    )
    AlertDialog(
        onDismissRequest = { if (!clearing) onDismiss() },
        title = { Text("账户跟单") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已停止", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("跟单功能仍在开发，当前不能启动。正式实现将只跟随 UniTrading 发出的订单。")
                if (clearing) Text("正在处理所选账户，请等待券商确认。")
                if (runtime.copyLegacyRecovery.unreadable) Text("旧版待核对订单记录无法读取，请检查券商账户。", color = MaterialTheme.colorScheme.error)
                recovery?.let { record ->
                    Text("有未确认订单：${record.symbol}，账户 ${record.accountIds.joinToString()}。请核对券商端。", color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ confirmRecovery = true }) { Text("同步并核对") }
                }
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
                    Text("跟随范围：所有合约，各账户使用自己的手数设置。")
                    Text("选择跟随账户（只能在当前连接内选择）")
                    accounts.forEach { account ->
                        val input = inputs[account.id] ?: CopyFollowerInput()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(account.id in selected, { checked -> selected = if (checked) selected + account.id else selected - account.id },
                                modifier = Modifier.testTag("copy-follower-${account.id}"))
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
                        val config = CopySavedConfiguration(broker.descriptor.id, selectedLeader,
                            followers.map { CopySavedFollower(it.accountId, it.fixedContracts, it.multiplier, it.maxContracts) }, symbol = null)
                        clearing = true
                        scope.launch {
                            try {
                                when (val check = inspect(listOf(config.leaderId) + config.followers.map { it.accountId })) {
                                    CopyStartPreparation.Ready -> save(config)
                                    is CopyStartPreparation.NeedsCleanup -> { error = null; notice = null; cleanup = PendingCopyCleanup(config, check.accounts) }
                                    is CopyStartPreparation.Blocked -> { notice = null; error = check.reason }
                                }
                            } finally {
                                clearing = false
                            }
                        }
                    }, modifier = Modifier.testTag("copy-save-settings"), enabled = !clearing && valid && broker.descriptor.id.startsWith("tradovate-")) { Text("保存设置") }
                    Button({}, enabled = false) { Text("开发中，暂不可启动") }
            }
        },
        confirmButton = { TextButton(onDismiss, enabled = !clearing) { Text("关闭") } },
    )
}
