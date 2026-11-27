package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unitrading.app.HyperpropPersonalSession
import com.unitrading.app.HyperpropConnectionException
import com.unitrading.app.HyperpropPendingOrder
import com.unitrading.app.HyperpropChartBroker
import com.unitrading.app.TradingRuntime
import com.unitrading.app.hyperpropOrderBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Isolated personal-API order test. No credentials are persisted. */
@Composable
internal fun HyperpropConnectionDialog(runtime: TradingRuntime, onAttached: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<HyperpropPersonalSession?>(null) }
    var accountId by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var contract by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("market") }
    var pending by remember { mutableStateOf<List<HyperpropPendingOrder>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmOrder by remember { mutableStateOf(false) }
    var cancelOrder by remember { mutableStateOf<HyperpropPendingOrder?>(null) }
    var transferred by remember { mutableStateOf<HyperpropPersonalSession?>(null) }
    val connections by runtime.registry.state.collectAsState()
    val feeds = connections.filter { it.descriptor.id.startsWith("tradovate-") || it.descriptor.id.startsWith("rithmic-") }
    var selectedFeed by remember { mutableStateOf<String?>(null) }
    var feedMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(session) { val current = session; onDispose { if (current != transferred) current?.close() } }
    fun action(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: HyperpropConnectionException) { message = error.message }
            catch (_: Exception) { message = "请求未能确认结果。请先在 Hyperprop 核对账户及订单，切勿重复提交。" }
            finally { busy = false }
        }
    }
    val active = session
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Hyperprop 个人 API · 交易测试") },
        text = { Column(Modifier.widthIn(max = 520.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active == null) {
                Text("输入个人密钥连接自己的账户。密钥只在本次会话使用，不保存。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { key = it }, label = { Text("个人 API 密钥（hpp_）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { action { val connected = HyperpropPersonalSession.connect(key); session = connected; accountId = connected.check.accounts.firstOrNull()?.id ?: ""; key = "" } }, enabled = !busy && key.isNotBlank()) { Text("连接") }
            } else {
                Text(if (active.check.scope == "trade") "读写密钥 · 测试交易" else "只读密钥 · 无法下单或撤单", style = MaterialTheme.typography.bodySmall)
                Text("本次密钥授权了 ${active.check.accounts.size} 个账户；如缺少账户，请在 Hyperprop 检查个人密钥及账户授权。", style = MaterialTheme.typography.bodySmall)
                Text("连接到主图：选择已登录的 Tradovate / Rithmic 行情。只有服务器明确标识的模拟账户允许测试下单。", style = MaterialTheme.typography.bodySmall)
                Box {
                    val source = feeds.firstOrNull { it.descriptor.id == selectedFeed } ?: feeds.firstOrNull()
                    OutlinedButton({ feedMenu = true }, enabled = !busy && feeds.isNotEmpty()) { Text(source?.descriptor?.name ?: "请先登录行情来源") }
                    DropdownMenu(expanded = feedMenu, onDismissRequest = { feedMenu = false }) {
                        feeds.forEach { feed -> DropdownMenuItem(text = { Text(feed.descriptor.name) }, onClick = { selectedFeed = feed.descriptor.id; feedMenu = false }) }
                    }
                }
                Button(onClick = {
                    val source = feeds.firstOrNull { it.descriptor.id == selectedFeed } ?: feeds.firstOrNull() ?: return@Button
                    val broker = HyperpropChartBroker(source, active)
                    runtime.registry.register(broker)
                    transferred = active
                    onAttached(broker.descriptor.id)
                    onDismiss()
                }, enabled = !busy && feeds.isNotEmpty()) { Text("连接主图（模拟测试）") }
                Box {
                    OutlinedButton({ menuOpen = true }, enabled = !busy) { Text(active.check.accounts.firstOrNull { it.id == accountId }?.let { listOfNotNull(it.number, it.firm).joinToString(" · ") } ?: "选择账户") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        active.check.accounts.forEach { account -> DropdownMenuItem(text = { Text(listOfNotNull(account.number, account.firm).joinToString(" · ")) }, onClick = { accountId = account.id; pending = emptyList(); menuOpen = false }) }
                    }
                }
                active.check.accounts.firstOrNull { it.id == accountId }?.markets?.let { markets ->
                    Text("该账户市场：${markets.sorted().joinToString("、").ifEmpty { "无" }}", style = MaterialTheme.typography.bodySmall)
                }
                Text("独立测试窗口：手动输入到期合约；暂不关联图表行情、持仓或止盈止损。请使用自己的模拟账户。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                OutlinedTextField(contract, { contract = it.uppercase() }, label = { Text("到期合约，例如 MESZ6") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("buy" to "买入", "sell" to "卖出").forEach { (v, label) -> FilterChip(side == v, { side = v }, label = { Text(label) }, enabled = !busy) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("market" to "市价", "limit" to "限价", "stop" to "突破").forEach { (v, label) -> FilterChip(kind == v, { kind = v }, label = { Text(label) }, enabled = !busy) }
                }
                OutlinedTextField(quantity, { quantity = it }, label = { Text("数量（张）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (kind != "market") OutlinedTextField(price, { price = it }, label = { Text(if (kind == "limit") "限价" else "触发价") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    try {
                        require(active.check.accounts.firstOrNull { it.id == accountId }?.onlyPerpetualMarkets != true) { "当前账户没有期货市场权限，请选择 CME 期货账户" }
                        hyperpropOrderBody(accountId, contract, side, kind, quantity.toIntOrNull() ?: 0, price); confirmOrder = true
                    }
                    catch (e: IllegalArgumentException) { message = e.message ?: "请检查订单" }
                }, enabled = !busy && active.check.scope == "trade" && accountId.isNotBlank()) { Text("检查并提交订单") }
                HorizontalDivider()
                OutlinedButton({ action { pending = active.pending(accountId); message = "已刷新挂单" } }, enabled = !busy && accountId.isNotBlank()) { Text("刷新挂单") }
                Text("列表可能包含保护订单；撤销前请确认订单用途。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                pending.forEach { order ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${order.contract} · ${order.side} · ${order.type} · ${order.quantity} 张${order.filledQuantity?.takeIf { it != "0" }?.let { "（已成交 $it 张）" } ?: ""}${order.price?.let { " @ $it" } ?: ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ cancelOrder = order }, enabled = !busy && active.check.scope == "trade") { Text("撤单") }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onDismiss, enabled = !busy) { Text("关闭") } })
    if (confirmOrder && active != null) AlertDialog(onDismissRequest = { confirmOrder = false }, title = { Text("确认提交 Hyperprop 订单") },
        text = { Text("账户：${active.check.accounts.firstOrNull { it.id == accountId }?.number}\n合约：$contract\n方向：${if (side == "buy") "买入" else "卖出"}\n类型：$kind\n数量：$quantity 张${if (kind != "market") "\n价格：$price" else ""}") },
        confirmButton = { Button(onClick = { confirmOrder = false; action { val outcome = active.submit(accountId, contract, side, kind, quantity.toInt(), price); message = "${outcome.status} · 命令 ${outcome.id}" } }) { Text("确认提交") } },
        dismissButton = { TextButton({ confirmOrder = false }) { Text("返回") } })
    cancelOrder?.let { order -> AlertDialog(onDismissRequest = { cancelOrder = null }, title = { Text("确认撤单") }, text = { Text("撤销 ${order.contract} ${order.side} ${order.type} ${order.quantity} 张？") },
        confirmButton = { Button(onClick = { cancelOrder = null; if (active != null) action { val outcome = active.cancel(order); message = "${outcome.status} · 命令 ${outcome.id}"; pending = active.pending(accountId) } }) { Text("确认撤单") } },
        dismissButton = { TextButton({ cancelOrder = null }) { Text("返回") } }) }
}
