package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unitrading.app.TradingRuntime

/** Logo and heading shared by the two gates in front of the trading UI. */
@Composable
internal fun GateHeader(title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLogo(64.dp, Modifier.padding(bottom = 16.dp))
        Text("UniTrading", style = MaterialTheme.typography.titleMedium, color = TradingColors.Muted)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = TradingColors.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LoginWelcome(runtime: TradingRuntime, onLogin: () -> Unit, onTheme: () -> Unit, onLoginRithmic: (() -> Unit)? = null) {
    val restoring by runtime.savedLogins.restoring.collectAsState()
    val message by runtime.savedLogins.message.collectAsState()
    val savedCount by runtime.savedLogins.savedCount.collectAsState()
    Surface(Modifier.fillMaxSize(), color = TradingColors.Shell) {
        Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 400.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GateHeader(if (restoring) "正在恢复已保存的登录" else "登录交易账户",
                    if (onLoginRithmic != null) "连接 Tradovate 或 Rithmic 后查看账户、行情和图表。" else "连接 Tradovate 后查看账户、行情和图表。")
                Spacer(Modifier.height(12.dp))
                if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, Modifier.testTag("saved-login-status"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                Button(onLogin, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-login")) { Text("登录 Tradovate") }
                if (onLoginRithmic != null) OutlinedButton(onLoginRithmic, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-login-rithmic")) { Text("登录 Rithmic") }
                if (savedCount > 0 || message != null) SavedLoginControls(runtime.savedLogins) {}
                TextButton(onTheme, Modifier.testTag("open-theme")) {
                    Icon(AppIcons.Palette, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("外观与主题")
                }
            }
        }
    }
}
