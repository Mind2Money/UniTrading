package com.unitrading.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.TradingRuntime
import com.unitrading.rithmic.*
import kotlinx.coroutines.*

/** Direct R | Protocol login: credentials go only to a `*.rithmic.com` gateway the user names. */
@Composable
internal fun RithmicConnectionDialog(
    runtime: TradingRuntime,
    onConnected: (String) -> Unit,
    onDismiss: () -> Unit,
    systemLookup: suspend (String) -> List<String> = { rithmicSystemNames(it) },
) {
    var environment by remember { mutableStateOf(RithmicEnvironment.TEST) }
    var gateway by remember { mutableStateOf(RithmicEnvironment.TEST.defaultGateway ?: "") }
    var systemName by remember { mutableStateOf(RithmicEnvironment.TEST.defaultSystemName) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    var systems by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val restoring by runtime.savedLogins.restoring.collectAsState()
    fun dismiss() { attempt?.cancel(); onDismiss() }
    fun choose(env: RithmicEnvironment) {
        if (busy || env == environment) return
        environment = env
        systemName = env.defaultSystemName
        gateway = env.defaultGateway ?: ""
        systems = emptyList()
        error = null
    }
    val credentials = remember(environment, gateway, systemName, user, password) {
        runCatching { RithmicCredentials(environment, gateway, systemName, user, password) }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("连接 Rithmic") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RithmicEnvironment.entries.forEach { env ->
                    FilterChip(environment == env, { choose(env) }, label = { Text(env.label) }, modifier = Modifier.testTag("rithmic-env-${env.wire}"))
                }
            }
            OutlinedTextField(gateway, { gateway = it }, label = { Text("网关地址 wss://…rithmic.com:443") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("rithmic-gateway"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(systemName, { systemName = it }, label = { Text("系统名称") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("rithmic-system"))
                TextButton({
                    scope.launch {
                        error = null
                        systems = try { withTimeout(20_000) { systemLookup(gateway) } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { error = "无法读取系统列表：${e.message ?: "请检查网关地址"}"; emptyList() }
                        if (systems.isEmpty() && error == null) error = "网关没有返回系统列表"
                    }
                }, enabled = !busy && gateway.isNotBlank()) { Text("查询") }
            }
            if (systems.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                systems.forEach { name -> FilterChip(systemName == name, { if (!busy) systemName = name }, label = { Text(name) }) }
            }
            OutlinedTextField(user, { user = it }, label = { Text("用户名（Rithmic 登录 ID）") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("rithmic-user"))
            OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("rithmic-password"))
            if (runtime.savedLogins.available) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(remember, { remember = it }, enabled = !busy, modifier = Modifier.testTag("rithmic-remember"))
                Text("在本机加密保存密码，下次启动自动恢复登录", fontSize = 12.sp)
            }
            Text("凭据只发送到你填写的 *.rithmic.com 网关。登录使用 R | Protocol API，连接后不会自动下单。", fontSize = 12.sp, color = TradingColors.Muted)
            when (environment) {
                RithmicEnvironment.TEST -> Text("Rithmic Test 是开发系统：行情可能缺失或延迟，账户为测试账户。", fontSize = 12.sp, color = TradingColors.Muted)
                RithmicEnvironment.PAPER -> Text("默认为芝加哥生产网关，可用“查询”查看该网关的系统列表（含各 prop firm 系统）；Rithmic 提供区域网关时可替换。应用未通过 Rithmic conformance 时登录可能被拒绝。", fontSize = 12.sp, color = TradingColors.Muted)
                RithmicEnvironment.LIVE -> Text("Live：后续确认的订单会发送到真实账户。", color = TradingColors.Loss, fontSize = 12.sp)
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, Modifier.testTag("rithmic-progress"), fontSize = 12.sp)
            }
            error?.let { Text(it, color = TradingColors.Loss, fontSize = 12.sp, modifier = Modifier.testTag("rithmic-error")) }
        } },
        confirmButton = { Button({
            val value = credentials.getOrNull() ?: run { error = credentials.exceptionOrNull()?.message ?: "请检查输入"; return@Button }
            busy = true; error = null; progress = "正在连接 Rithmic…"
            attempt = scope.launch {
                val report: (String) -> Unit = { message -> scope.launch { progress = message } }
                try {
                    val id = withTimeout(180_000) { runtime.savedLogins.signInRithmic(value, remember, report) }
                    onConnected(id)
                    onDismiss()
                } catch (_: TimeoutCancellationException) {
                    error = "连接超时：$progress。未发送交易指令。"
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) {
                    error = "$progress\n${e.message ?: "登录失败，请检查网关、系统名称和凭据"}"
                } finally { busy = false }
            }
        }, enabled = !busy && !restoring, modifier = Modifier.testTag("rithmic-connect")) { Text("登录") } },
        dismissButton = { TextButton(::dismiss, Modifier.testTag("cancel-rithmic-login")) { Text(if (busy) "取消连接" else "取消") } },
    )
}
