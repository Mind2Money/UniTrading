package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.TradingRuntime

@Composable
internal fun LoginWelcome(runtime: TradingRuntime, onLogin: () -> Unit, onTheme: () -> Unit) {
    val restoring by runtime.savedLogins.restoring.collectAsState()
    val message by runtime.savedLogins.message.collectAsState()
    val savedCount by runtime.savedLogins.savedCount.collectAsState()
    Surface(Modifier.fillMaxSize(), color = TradingColors.Shell) {
        Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("UniTrading", style = MaterialTheme.typography.headlineLarge)
                Text(if (restoring) "正在恢复已保存的登录" else "登录交易账户", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Tradovate 后查看账户、行情和图表。", color = TradingColors.Muted)
                if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, Modifier.testTag("saved-login-status")) }
                Button(onLogin, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-login")) { Text("登录 Tradovate") }
                OutlinedButton(onTheme, Modifier.fillMaxWidth().testTag("open-theme")) { Text("外观与主题") }
                if (savedCount > 0 || message != null) SavedLoginControls(runtime.savedLogins) {}
            }
        }
    }
}
