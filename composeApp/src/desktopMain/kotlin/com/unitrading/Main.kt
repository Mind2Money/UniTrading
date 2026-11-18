package com.unitrading

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import com.unitrading.ui.DesktopTitleBarSlot
import com.unitrading.ui.LocalDesktopTitleBarSlot
import com.unitrading.ui.TradingTheme
import com.unitrading.ui.WindowsTitleBar
import com.unitrading.app.AnalyticsTradeCache
import com.unitrading.app.LicenseController
import com.unitrading.app.CheckOnlyUpdateTarget
import com.unitrading.app.ReleaseUpdater
import com.unitrading.rithmic.RithmicCredentials
import com.unitrading.app.updateBlock
import com.unitrading.app.TradingRuntime
import com.unitrading.app.SnapshotStore
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.ui.AppIcons
import com.unitrading.ui.LicenseGate
import com.unitrading.ui.TradingScreen
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

class DesktopSnapshotStore(private val directory: Path = defaultDataDirectory()) : SnapshotStore {
    override fun read(key: String): String? {
        val file = directory.resolve("$key.json")
        return if (Files.exists(file)) Files.readString(file) else null
    }
    override fun write(key: String, value: String) {
        Files.createDirectories(directory)
        val target = directory.resolve("$key.json")
        val temp = Files.createTempFile(directory, key, ".tmp")
        try {
            Files.writeString(temp, value)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

private fun defaultDataDirectory(): Path = (System.getProperty("unitrading.dataDir") ?: System.getenv("UNITRADING_DATA_DIR"))?.let(Path::of)
    ?: userDataRoot().resolve("UniTrading").resolve("demo-v1")

fun main(args: Array<String>) {
    // An update starts the copy it unpacked with this argument to check it, so it comes before anything else.
    if ("--version" in args) { println(System.getProperty("jpackage.app-version") ?: "source"); return }
    // The helper copy of a new version, opened by the version it replaces, puts the new version in place and exits.
    if (args.isEmpty()) SelfUpdate.pendingJob(System.getProperty("jpackage.app-path")?.let(::File))?.let { exitProcess(SelfUpdate.install(it)) }
    // Only HttpURLConnection reads this, and only the update uses it; the brokers' Ktor sockets keep their own route.
    System.setProperty("java.net.useSystemProxies", "true")
    desktopApplication(args)
}

private fun desktopApplication(args: Array<String>) = application {
    val localDemo = "--local-demo" in args
    val runtime = remember {
        if (localDemo) TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).also {
            // The demo keeps its state in memory, so its runtime trims nothing on disk; the trades normal runs cached
            // still leave the data folder after 30 days.
            AnalyticsTradeCache(DesktopSnapshotStore()).pruneInBackground()
        }
        else TradingRuntime(DesktopSnapshotStore(), credentialStore = DesktopCredentialStore())
    }
    val updateTarget = remember { if (localDemo) null else UpdateLayout.locate(System.getProperty("jpackage.app-path"))?.let { DesktopUpdateTarget(it) } }
    // A run that cannot replace itself (from source, --local-demo, the macOS package) still checks for new versions.
    val updates = remember {
        val packaged = System.getProperty("jpackage.app-version")
        ReleaseUpdater(packaged ?: RithmicCredentials.DEFAULT_APP_VERSION, updateTarget?.takeIf { packaged != null } ?: CheckOnlyUpdateTarget, blocked = { runtime.registry.updateBlock() })
    }
    // One activation per OS user, shared by both modes; the local demo is gated like the real terminal.
    val license = remember { LicenseController(DesktopCredentialStore(fileName = DesktopCredentialStore.LICENSE_FILE)) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    fun shutdown(saveWorkspace: Boolean = false) {
        if (!closing) {
            closing = true
            scope.launch {
                val saved = try { !saveWorkspace || runtime.desktopWorkspaceSaver?.invoke() == true }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { false }
                if (!saved) { exitError = "窗口布局或绘图保存失败，请重试。"; closing = false; return@launch }
                if (runtime.startupError.value != null || runtime.save()) {
                    runtime.registry.plugins.forEach { it.disconnect() }
                    exitApplication()
                } else { exitError = "账户数据保存失败，请重试。"; closing = false }
            }
        }
    }
    fun requestClose() {
        if (runtime.desktopWorkspaceCount.value > 1) { exitError = null; confirmExit = true }
        else shutdown()
    }
    LaunchedEffect(updateTarget) { updateTarget?.exit = { withContext(Dispatchers.Main) { requestClose() } } }
    val windowState = rememberWindowState(width = 1440.dp, height = 940.dp)
    val customTitle = remember { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || System.getProperty("unitrading.customTitleBar") == "true" }
    val titleSlot = remember { DesktopTitleBarSlot() }
    val windowTitle = if (localDemo) "UniTrading · 本地模拟 · 不连接真实账户" else "UniTrading · 交易终端"
    Window(onCloseRequest = ::requestClose, title = windowTitle, state = windowState, undecorated = customTitle,
        icon = rememberVectorPainter(AppIcons.LogoTile)) {
        val content: @Composable () -> Unit = {
            LicenseGate(license, runtime.theme) { TradingScreen(runtime, remember { if (localDemo) null else DesktopTradovateLogin() }, updates = updates) }
        }
        if (customTitle) {
            val theme by runtime.theme.settings.collectAsState()
            CompositionLocalProvider(LocalDesktopTitleBarSlot provides titleSlot) {
                Column(Modifier.fillMaxSize()) {
                    TradingTheme(theme) { WindowsTitleBar(titleSlot, windowState, windowTitle, ::requestClose) }
                    Box(Modifier.weight(1f).fillMaxSize()) { content() }
                }
            }
        } else content()
        if (confirmExit) AlertDialog(onDismissRequest = { if (!closing) confirmExit = false },
            title = { Text("保存窗口布局？") },
            text = { Text(exitError ?: "保存当前图表标签、分图和独立窗口，下次打开时恢复。") },
            confirmButton = { TextButton(onClick = { shutdown(saveWorkspace = true) }, enabled = !closing,
                modifier = Modifier.testTag("save-workspaces-and-exit")) { Text("保存并退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }, enabled = !closing,
                modifier = Modifier.testTag("cancel-workspace-exit")) { Text("取消") } })
    }
}
