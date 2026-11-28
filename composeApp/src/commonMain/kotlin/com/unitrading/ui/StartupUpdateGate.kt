package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface StartupCheck {
    data object Checking : StartupCheck
    data object Enter : StartupCheck
    data object Unavailable : StartupCheck
    data class Offer(val release: AppRelease) : StartupCheck
}

/** Composed only after activation. Do not create the trading UI until the startup decision is made. */
@Composable
fun StartupUpdateGate(updates: AppUpdates, content: @Composable () -> Unit) {
    var stage by remember(updates) { mutableStateOf<StartupCheck>(StartupCheck.Checking) }
    var attempt by remember(updates) { mutableIntStateOf(0) }
    var updating by remember(updates) { mutableStateOf(false) }
    val progress by updates.progress.collectAsState()
    val links = LocalUriHandler.current

    LaunchedEffect(updates, attempt) {
        stage = StartupCheck.Checking
        updates.checkNow()
        val result = withTimeoutOrNull(15_000) {
            updates.check.first { it != UpdateCheck.Idle && it != UpdateCheck.Checking }
        }
        stage = when (result) {
            is UpdateCheck.Available -> StartupCheck.Offer(result.release)
            is UpdateCheck.Current, is UpdateCheck.Ahead -> StartupCheck.Enter
            else -> StartupCheck.Unavailable
        }
    }

    val current = stage
    if (current == StartupCheck.Enter) {
        content()
        return
    }
    Surface(Modifier.fillMaxSize().testTag("startup-update-gate"), color = TradingColors.Shell) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (current == StartupCheck.Checking) Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("正在检查更新")
                LinearProgressIndicator(Modifier.widthIn(max = 320.dp).fillMaxWidth())
            }
        }
    }
    when (current) {
        StartupCheck.Unavailable -> AlertDialog(onDismissRequest = { stage = StartupCheck.Enter },
            title = { Text("无法检查更新") }, text = { Text("暂时无法获取最新版本，请检查网络后重试，或先进入软件。") },
            confirmButton = { TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("startup-update-retry-check")) { Text("重试") } },
            dismissButton = { TextButton(onClick = { stage = StartupCheck.Enter }, modifier = Modifier.testTag("startup-update-continue")) { Text("进入软件") } })
        is StartupCheck.Offer -> {
            val release = current.release
            val file = release.packageFile
            val active = updating && (progress is UpdateProgress.Downloading || progress is UpdateProgress.Preparing || progress is UpdateProgress.Restarting)
            AlertDialog(onDismissRequest = { if (!active) stage = StartupCheck.Enter },
                title = { Text(if (updating) "正在更新" else "发现新版本 ${release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (val value = if (updating) progress else UpdateProgress.Idle) {
                            is UpdateProgress.Downloading -> {
                                Text("正在下载 ${value.version} · ${value.received * 100 / value.total.coerceAtLeast(1)}%")
                                LinearProgressIndicator({ value.received.toFloat() / value.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                            }
                            is UpdateProgress.Preparing -> { Text("正在校验更新文件…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            is UpdateProgress.Restarting -> { Text("正在准备重启并更新…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            is UpdateProgress.Ready -> Text("更新已下载，但账户可能有持仓、挂单或状态未确认。请核对后在设置中完成更新。")
                            is UpdateProgress.Failed -> Text("更新未完成：${failureText(value.code)}")
                            else -> Text(if (file == null || updates.install == UpdateInstall.MANUAL)
                                "请前往发布页下载新版本。" else "当前版本 ${updates.currentVersion}。是否现在更新？")
                        }
                    }
                },
                confirmButton = {
                    if (!active) TextButton(onClick = {
                        when {
                            updating && progress is UpdateProgress.Ready -> updates.finish()
                            file == null || updates.install == UpdateInstall.MANUAL -> { runCatching { links.openUri(release.pageUrl) }; stage = StartupCheck.Enter }
                            else -> { updating = true; updates.start(file) }
                        }
                    }, modifier = Modifier.testTag("startup-update-install")) {
                        Text(if (file == null || updates.install == UpdateInstall.MANUAL) "前往下载" else if (updating && progress is UpdateProgress.Ready) "继续更新" else if (updating) "重试更新" else "更新")
                    }
                },
                dismissButton = {
                    if (!active || progress is UpdateProgress.Downloading) TextButton(onClick = {
                        if (progress is UpdateProgress.Downloading) updates.cancel()
                        stage = StartupCheck.Enter
                    }, modifier = Modifier.testTag("startup-update-cancel")) { Text(if (active) "取消下载并进入" else "取消") }
                })
        }
        else -> Unit
    }
}
