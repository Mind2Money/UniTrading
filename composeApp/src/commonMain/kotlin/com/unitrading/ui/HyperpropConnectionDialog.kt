package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unitrading.app.HyperpropCheck
import com.unitrading.app.checkHyperpropPersonalKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Personal-key connection check; never stores the key or submits orders. */
@Composable
internal fun HyperpropConnectionDialog(onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HyperpropCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Hyperprop 个人 API · 连接测试") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("此测试版只验证个人密钥并读取账户，不支持 Hyperprop 下单、持仓或止盈止损。密钥仅在本次测试中使用，不会保存。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it; result = null; error = null }, label = { Text("个人 API 密钥（hpp_）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("行情仍来自你已连接的 Tradovate 或 Rithmic。账户余额以 Hyperprop 返回值为准；接口未返回的净值和浮动盈亏不会用 0 代替。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = TradingColors.Loss, style = MaterialTheme.typography.bodySmall) }
            result?.let { data ->
                Text("连接成功 · ${if (data.scope == "read") "只读密钥" else "读写密钥（本测试仍只读）"}")
                if (data.accounts.isEmpty()) Text("没有可访问的账户")
                data.accounts.forEach { account -> Text(listOfNotNull(account.number, account.firm, account.balance?.let { "余额 $it" }).joinToString(" · ")) }
            }
        } },
        confirmButton = { Button(onClick = {
            busy = true; error = null; result = null
            scope.launch {
                try { result = checkHyperpropPersonalKey(key); key = "" }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = "连接未完成，请检查密钥格式、网络及账户权限。" }
                finally { busy = false }
            }
        }, enabled = !busy && key.isNotBlank()) { Text("测试连接") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("关闭") } },
    )
}
