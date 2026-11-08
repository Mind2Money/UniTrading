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
import com.unitrading.broker.BrokerRegistry
import kotlinx.coroutines.delay

/** Version, update check and one-press update. Nothing here checks or downloads until the user presses a row. */
@Composable
internal fun AppUpdateRows(updates: AppUpdates, registry: BrokerRegistry, busy: Boolean) {
    val check by updates.check.collectAsState()
    val progress by updates.progress.collectAsState()
    // Positions span every account of every broker with no one flow to watch; this runs only while settings are open.
    val block by produceState(registry.updateBlock(), registry) { while (true) { value = registry.updateBlock(); delay(1_000) } }
    val links = LocalUriHandler.current
    val release = (check as? UpdateCheck.Available)?.release
    val available = release?.packageFile
    val working = progress is UpdateProgress.Downloading || progress is UpdateProgress.Preparing || progress is UpdateProgress.Restarting
    val restarts = updates.install == UpdateInstall.RESTART
    val canProceed = block == null && !busy

    SettingsRow("检查更新", "当前版本 ${updates.currentVersion} · ${checkText(check)}", AppIcons.Sync, "update-check", updates::checkNow,
        enabled = check != UpdateCheck.Checking && !working)
    when (val current = progress) {
        is UpdateProgress.Downloading -> UpdateWorkRow("正在下载 ${current.version}", "${megabytes(current.received)} / ${megabytes(current.total)}",
            current.received.toFloat() / current.total, updates::cancel)
        is UpdateProgress.Preparing -> UpdateWorkRow("正在校验 ${current.version}", null, null, null)
        is UpdateProgress.Restarting -> UpdateWorkRow("正在重启并更新到 ${current.version}", null, null, null)
        is UpdateProgress.Ready -> SettingsRow(if (restarts) "重启并更新到 ${current.version}" else "安装 ${current.version}",
            if (restarts) "已下载并校验" else "已下载并校验，点击打开系统安装界面", AppIcons.Download, "update-finish", updates::finish, enabled = canProceed)
        else -> available?.let { file ->
            SettingsRow("更新到 ${file.version}", "下载 ${megabytes(file.bytes)}，校验后${if (restarts) "替换程序并重启" else "由系统安装"}", AppIcons.Download,
                "update-install", { updates.start(file) }, enabled = canProceed)
        }
    }
    val waiting = progress is UpdateProgress.Ready || (available != null && !working)
    val note = when {
        waiting -> block?.let(::blockText)
        release != null && available == null -> "这个版本没有可核实的安装包，请到发布页手动下载。"
        else -> null
    }
    note?.let { UpdateNote(it, "update-blocked", TradingColors.Muted) }
    when (val current = progress) {
        is UpdateProgress.Failed -> UpdateNote("更新到 ${current.version} 未完成：${failureText(current.code)}", "update-failure", TradingColors.Loss)
        is UpdateProgress.Installed -> UpdateNote("已更新到 ${current.version}。", "update-installed", TradingColors.Muted)
        else -> {}
    }
    release?.let {
        SettingsRow("发布页", "查看 ${it.version} 更新说明或手动下载", AppIcons.OpenInNew, "update-release-page", { runCatching { links.openUri(it.pageUrl) } })
    }
    UpdateNote("只在点击时读取 GitHub 上的公开发布信息，不发送账户数据；不会在后台检查或下载。", "update-privacy", TradingColors.Muted)
}

@Composable
private fun UpdateWorkRow(title: String, detail: String?, fraction: Float?, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("update-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TradingColors.Muted) }
            }
            onCancel?.let { TextButton(it, Modifier.testTag("update-cancel")) { Text("取消") } }
        }
        if (fraction != null) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun UpdateNote(text: String, tag: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag), style = MaterialTheme.typography.bodySmall, color = color)
}

private fun megabytes(bytes: Long): String {
    val tenths = bytes * 10 / (1L shl 20)
    return "${tenths / 10}.${tenths % 10} MB"
}

private fun checkText(check: UpdateCheck): String = when (check) {
    UpdateCheck.Idle -> "尚未检查"
    UpdateCheck.Checking -> "正在检查…"
    is UpdateCheck.Current -> "已是最新版本"
    is UpdateCheck.Ahead -> "高于最新正式版 ${check.latest}"
    is UpdateCheck.Available -> "发现新版本 ${check.release.version}"
    is UpdateCheck.Failed -> when (check.code) {
        "UPDATE_RATE_LIMITED" -> "GitHub 暂时限流，请稍后再试"
        "UPDATE_NOT_PUBLIC" -> "未找到已发布的版本"
        "UPDATE_INVALID" -> "更新信息无法核实，未使用返回内容"
        else -> "暂时无法检查，请确认网络后重试"
    }
}

private fun blockText(block: UpdateBlock): String = when (block) {
    UpdateBlock.OPEN_TRADES -> "账户有持仓或挂单时不能更新，请先平仓、撤单。"
    UpdateBlock.ACCOUNT_UNCONFIRMED -> "账户未连接或未同步完成，无法确认没有持仓和挂单，暂时不能更新。"
}

private fun failureText(code: String): String = when (code) {
    "UPDATE_DOWNLOAD" -> "下载失败，请检查网络和磁盘空间后重试。"
    "UPDATE_CORRUPT" -> "下载的文件大小或 SHA-256 与发布信息不符，已丢弃。"
    "UPDATE_PACKAGE" -> "新版本校验未通过，当前版本没有改动。"
    "UPDATE_SIGNATURE" -> "安装包的签名与当前应用不同，系统不允许覆盖安装，需要卸载后手动安装。"
    "UPDATE_LOCATION" -> "程序所在文件夹无法写入，或磁盘空间不足。"
    "UPDATE_HANDOFF" -> "更新程序没有启动，当前版本没有改动。"
    "UPDATE_INSTALLER" -> "无法打开系统安装界面。"
    "UPDATE_FILES_BUSY" -> "程序文件被占用，当前版本没有改动。关闭占用的程序后重试。"
    "UPDATE_STILL_RUNNING" -> "旧版本没有及时退出，当前版本没有改动。"
    "UPDATE_TRADING_ACTIVE" -> "账户有持仓或挂单，或状态无法确认，没有开始更新。"
    else -> "请稍后重试。"
}
