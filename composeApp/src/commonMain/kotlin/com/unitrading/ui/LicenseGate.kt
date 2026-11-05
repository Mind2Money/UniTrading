package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.unitrading.app.LicenseController
import com.unitrading.app.LicenseState
import com.unitrading.app.LicenseStatus
import com.unitrading.app.ThemeController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App entry gate: [content] (and with it saved broker logins, market data and trading) is composed only after
 * [license] is active. Platform entry points wrap the trading screen in it.
 */
@Composable
fun LicenseGate(license: LicenseController, theme: ThemeController, content: @Composable () -> Unit) {
    val state by license.state.collectAsState()
    val settings by theme.settings.collectAsState()
    LaunchedEffect(license) { license.load() }
    if (state.status == LicenseStatus.ACTIVE) {
        content()
        state.notice?.let { notice ->
            TradingTheme(settings) {
                AlertDialog(onDismissRequest = license::dismissNotice, title = { Text("授权未保存") }, text = { Text(notice) },
                    confirmButton = { TextButton(license::dismissNotice, Modifier.testTag("license-notice-ok")) { Text("知道了") } })
            }
        }
    } else TradingTheme(settings) { LicenseScreen(license, state) }
}

@Composable
private fun LicenseScreen(license: LicenseController, state: LicenseState) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Not saveable: the key must not end up in saved instance state.
    var key by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(license.clock()) }
    LaunchedEffect(state.lockedUntilMs) {
        while (true) {
            now = license.clock()
            val remaining = state.lockedUntilMs - now
            if (remaining <= 0) break
            delay((remaining - 1) % 1_000 + 1)
        }
    }
    val lockedMs = (state.lockedUntilMs - now).coerceAtLeast(0)
    val checking = state.status == LicenseStatus.CHECKING
    val verifying = state.status == LicenseStatus.VERIFYING
    val canSubmit = key.isNotBlank() && !checking && !verifying && lockedMs == 0L
    val message = when {
        lockedMs > 0 -> "输错次数过多，请在 ${waitText(lockedMs)}后重试。"
        state.lockedUntilMs > 0 -> null
        else -> state.message
    }
    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        scope.launch { license.activate(key) }
    }
    // Focus once the form appears and again after a refused attempt, so the key can be corrected at once.
    LaunchedEffect(state.status) { if (state.status == LicenseStatus.REQUIRED) runCatching { focus.requestFocus() } }
    Surface(Modifier.fillMaxSize().testTag("license-gate"), color = TradingColors.Shell) {
        Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 400.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (checking) {
                    GateHeader("正在检查授权")
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("license-checking"))
                } else {
                    GateHeader("输入授权码", "验证通过后本机保持授权，重启应用无需再次输入。")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("license-key"),
                        enabled = !verifying, singleLine = true, label = { Text("授权码") }, isError = message != null,
                        supportingText = message?.let { { Text(it, Modifier.testTag("license-message")) } },
                        leadingIcon = { Icon(AppIcons.Key, contentDescription = null) },
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailingIcon = {
                            ActionIcon(if (revealed) AppIcons.VisibilityOff else AppIcons.Visibility, if (revealed) "隐藏授权码" else "显示授权码", "license-reveal") { revealed = !revealed }
                        })
                    Button(::submit, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("license-submit"), enabled = canSubmit) {
                        Text(if (verifying) "正在验证…" else "验证并进入")
                    }
                    if (verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun waitText(ms: Long): String {
    val seconds = (ms + 999) / 1_000
    return if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
}
