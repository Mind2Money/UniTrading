package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.TradingRuntime
import com.unitrading.tradovate.*
import kotlinx.coroutines.*

@Composable
internal fun TradovateConnectionDialog(runtime: TradingRuntime, login: TradovateLogin?, onConnected: (String) -> Unit, onDismiss: () -> Unit) {
    var environment by remember { mutableStateOf(TradovateEnvironment.DEMO) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("请打开官方登录页") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun dismiss() { attempt?.cancel(); onDismiss() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("连接 Tradovate") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradovateEnvironment.entries.forEach { env ->
                    FilterChip(environment == env, { if (!busy) environment = env }, label = { Text(env.wire) })
                }
            }
            Text("在官方网页中登录，成功后自动返回。首次最多保留 60 秒供浏览器保存密码；已记住的登录会更快交接。", fontSize = 12.sp)
            Text("认证统一使用 Tradovate 认证服务，账户与行情连接保持你选择的 Demo/Live 环境；不会自动下单。", fontSize = 12.sp, color = TradingColors.Muted)
            if (environment == TradovateEnvironment.LIVE) Text("Live：后续确认的订单会发送到真实账户。", color = TradingColors.Loss, fontSize = 12.sp)
            if (login == null) Text("此平台尚未配置官方网页登录组件。", color = TradingColors.Loss)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, Modifier.testTag("login-progress"), fontSize = 12.sp)
            }
            error?.let { Text(it, color = TradingColors.Loss, fontSize = 12.sp) }
        } },
        confirmButton = { Button({
            busy = true; error = null; progress = "正在打开官方登录窗口…"
            attempt = scope.launch {
                var broker: TradovateBroker? = null
                val report: (String) -> Unit = { message -> scope.launch { progress = message } }
                try {
                    val session = withTimeout(600_000) { login!!.signIn(environment, report) }
                    progress = "网页登录已完成，正在连接服务器账户…"
                    broker = TradovateBroker(session)
                    withTimeout(90_000) { broker.connect(report) }
                    runtime.registry.register(broker)
                    onConnected(broker.descriptor.id)
                    onDismiss()
                } catch (_: TimeoutCancellationException) {
                    broker?.disconnect()
                    error = "阶段超时：$progress。没有发送交易指令。"
                } catch (cancel: CancellationException) { broker?.disconnect(); throw cancel }
                catch (e: Exception) {
                    broker?.disconnect()
                    error = if (e is TradovateRemoteException) "$progress\n${e.message}" else "$progress\n登录交接未完成，请关闭旧登录窗口后重试。"
                } finally { busy = false }
            }
        }, enabled = !busy && login != null) { Text("打开官方登录页") } },
        dismissButton = { TextButton(::dismiss, Modifier.testTag("cancel-login")) { Text(if (busy) "取消连接" else "取消") } },
    )
}
