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
    val restoring by runtime.savedLogins.restoring.collectAsState()
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
            Text("在官方网页登录后自动返回。首次登录最多等待 60 秒，供浏览器保存密码；已保存过登录信息时会更快返回。", fontSize = 12.sp)
            Text("登录由 Tradovate 认证服务处理。账户和行情使用你选择的 Demo/Live 环境，连接后不会自动下单。", fontSize = 12.sp, color = TradingColors.Muted)
            if (runtime.savedLogins.available) Text("应用会在本机加密保存登录状态，下次启动时恢复仍有效的登录。可在账户页清除。", fontSize = 12.sp)
            if (environment == TradovateEnvironment.LIVE) Text("Live：后续确认的订单会发送到真实账户。", color = TradingColors.Loss, fontSize = 12.sp)
            if (login == null) Text("当前平台尚未配置官方网页登录组件。", color = TradingColors.Loss)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, Modifier.testTag("login-progress"), fontSize = 12.sp)
            }
            error?.let { Text(it, color = TradingColors.Loss, fontSize = 12.sp) }
        } },
        confirmButton = { Button({
            busy = true; error = null; progress = "正在打开官方登录窗口…"
            attempt = scope.launch {
                val report: (String) -> Unit = { message -> scope.launch { progress = message } }
                try {
                    val session = withTimeout(600_000) { login!!.signIn(environment, report) }
                    progress = "网页登录已完成，正在连接服务器账户…"
                    val id = runtime.savedLogins.signIn(session, report)
                    onConnected(id)
                    onDismiss()
                } catch (_: TimeoutCancellationException) {
                    error = "连接超时：$progress。未发送交易指令。"
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) {
                    error = if (e is TradovateRemoteException) "$progress\n${e.message}" else "$progress\n登录信息未能传回应用，请关闭旧登录窗口后重试。"
                } finally { busy = false }
            }
        }, enabled = !busy && !restoring && login != null) { Text("打开官方登录页") } },
        dismissButton = { TextButton(::dismiss, Modifier.testTag("cancel-login")) { Text(if (busy) "取消连接" else "取消") } },
    )
}
