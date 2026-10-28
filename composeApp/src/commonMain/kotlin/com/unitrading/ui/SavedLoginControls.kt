package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.SavedTradovateAccounts
import kotlinx.coroutines.launch

@Composable
internal fun SavedLoginControls(saved: SavedTradovateAccounts, onForgotten: () -> Unit) {
    if (!saved.available) return
    val message by saved.message.collectAsState()
    val restoring by saved.restoring.collectAsState()
    val count by saved.savedCount.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("本机已保存 $count 个 Tradovate 登录", fontSize = 12.sp)
        message?.let { Text(it, fontSize = 12.sp) }
        TextButton({ scope.launch { busy = true; try { saved.retry() } finally { busy = false } } }, enabled = !restoring && !busy) { Text("重试恢复登录") }
        TextButton({ confirm = true }, enabled = !restoring && !busy, modifier = Modifier.testTag("forget-saved-logins")) { Text("清除已保存登录") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清除已保存登录？") },
        text = { Text("断开本机 Tradovate 连接并删除保存的会话。浏览器保存的密码不受影响，服务器订单和仓位保持不变。") },
        confirmButton = { TextButton({
            confirm = false
            scope.launch { busy = true; try { if (saved.forgetAll()) onForgotten() } finally { busy = false } }
        }) { Text("确认清除") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
