package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.TradingRuntime
import com.unitrading.tradovate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TradovateConnectionDialog(runtime: TradingRuntime, login: TradovateLogin?, onConnected: (String) -> Unit, onDismiss: () -> Unit) {
    var environment by remember { mutableStateOf(TradovateEnvironment.DEMO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("连接 Tradovate") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradovateEnvironment.entries.forEach { env ->
                    FilterChip(environment == env, { if (!busy) environment = env }, label = { Text(env.wire) })
                }
            }
            Text("在客户端弹出的 Tradovate 官方网页中登录，完成后返回。密码保存和自动填充由浏览器/系统密码服务处理。", fontSize = 12.sp)
            Text("连接后只同步账户和行情，不会自动下单。网页登录成功不等于拥有 API 或行情权限。", fontSize = 12.sp, color = TradingColors.Muted)
            if (environment == TradovateEnvironment.LIVE) Text("Live：后续确认的订单会发送到真实账户。", color = TradingColors.Loss, fontSize = 12.sp)
            if (login == null) Text("此平台尚未配置官方网页登录组件。", color = TradingColors.Loss)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("等待网页登录或同步服务器账户…", fontSize = 12.sp) }
            error?.let { Text(it, color = TradingColors.Loss, fontSize = 12.sp) }
        } },
        confirmButton = { Button({
            busy = true; error = null
            scope.launch {
                var broker: TradovateBroker? = null
                try {
                    val session = login!!.signIn(environment)
                    broker = TradovateBroker(session)
                    broker.connect()
                    runtime.registry.register(broker)
                    onConnected(broker.descriptor.id)
                    onDismiss()
                } catch (cancel: CancellationException) { broker?.disconnect(); throw cancel }
                catch (e: Exception) { broker?.disconnect(); error = if (e is TradovateRemoteException) e.message else "登录未完成或账户同步失败，请重试并检查官方网页提示。" }
                finally { busy = false }
            }
        }, enabled = !busy && login != null) { Text("打开官方登录页") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}
