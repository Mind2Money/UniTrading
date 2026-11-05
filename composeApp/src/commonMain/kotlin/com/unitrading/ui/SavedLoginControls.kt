package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.SavedTradovateAccounts
import kotlinx.coroutines.launch

@Composable
internal fun SavedLoginControls(saved: SavedTradovateAccounts, grouped: Boolean = false, onForgotten: () -> Unit) {
    if (!saved.available) return
    val message by saved.message.collectAsState()
    val restoring by saved.restoring.collectAsState()
    val count by saved.savedCount.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val retry: () -> Unit = { scope.launch { busy = true; try { saved.retry() } finally { busy = false } } }
    if (grouped) {
        SettingsRow("恢复已保存登录", if (restoring || busy) "正在处理…" else "本机已保存 $count 个登录",
            AppIcons.Sync, "settings-restore-logins", retry, enabled = !restoring && !busy)
        message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted) }
        SettingsRow("清除已保存登录", "删除本机登录状态并断开连接", AppIcons.DeleteSweep, "forget-saved-logins",
            { confirm = true }, enabled = !restoring && !busy, destructive = true)
    } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("本机已保存 $count 个登录", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(retry, enabled = !restoring && !busy) { Text("重试恢复登录") }
            TextButton({ confirm = true }, enabled = !restoring && !busy, modifier = Modifier.testTag("forget-saved-logins"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除已保存登录") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清除已保存登录？") },
        text = { Text("清除后将断开本机已保存的 Tradovate / Rithmic 连接，并删除保存的登录状态（含已记住的 Rithmic 密码）。浏览器密码、服务器订单和仓位不受影响。") },
        confirmButton = { TextButton({
            confirm = false
            scope.launch { busy = true; try { if (saved.forgetAll()) onForgotten() } finally { busy = false } }
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认清除") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
