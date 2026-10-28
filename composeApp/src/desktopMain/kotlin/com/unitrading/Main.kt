package com.unitrading

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.unitrading.app.TradingRuntime
import com.unitrading.app.SnapshotStore
import com.unitrading.ui.TradingScreen
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "UniTrading", "demo-v1")

fun main() = application {
    val runtime = remember { TradingRuntime(DesktopSnapshotStore(), credentialStore = DesktopCredentialStore()) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    Window(onCloseRequest = {
        if (!closing) {
            closing = true
            scope.launch {
                if (runtime.startupError.value != null || runtime.save()) {
                    runtime.registry.plugins.forEach { it.disconnect() }
                    exitApplication()
                }
                else closing = false
            }
        }
    }, title = "UniTrading · 交易终端", state = rememberWindowState(width = 1440.dp, height = 940.dp)) {
        TradingScreen(runtime, remember { DesktopTradovateLogin() })
    }
}
