package com.unitrading.android

import android.Manifest
import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import android.content.res.Configuration
import androidx.compose.ui.graphics.luminance
import com.unitrading.ui.themePalette
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.unitrading.app.LicenseController
import com.unitrading.app.TradingRuntime
import com.unitrading.app.SnapshotStore
import com.unitrading.ui.LicenseGate
import com.unitrading.ui.TradingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** Asked the first time the live P&L notice has an account to show but POST_NOTIFICATIONS is missing (Android 13+). */
    private val permissionPrompt: () -> Unit = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    private var model: TradingRuntimeModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val model = ViewModelProvider(this)[TradingRuntimeModel::class.java].also { this.model = it }
        model.notifier.requestPermission = permissionPrompt
        val login = AndroidTradovateLogin(this)
        setContent {
            val theme by model.runtime.theme.settings.collectAsState()
            val palette = themePalette(theme, resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            SideEffect {
                val barStyle = if (palette.Shell.luminance() > .45f) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    else SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            LicenseGate(model.license, model.runtime.theme) { TradingScreen(model.runtime, login) }
        }
    }

    override fun onDestroy() {
        model?.notifier?.let { if (it.requestPermission === permissionPrompt) it.requestPermission = null }
        super.onDestroy()
    }
}

class TradingRuntimeModel(application: Application) : AndroidViewModel(application) {
    val runtime = TradingRuntime(AndroidSnapshotStore(File(application.filesDir, "demo-v1")), credentialStore = AndroidCredentialStore(application))
    /** Held here so the activation survives configuration changes; the gate keeps the trading UI closed until it is active. */
    val license = LicenseController(AndroidCredentialStore(application, AndroidCredentialStore.LICENSE_FILE, AndroidCredentialStore.LICENSE_KEY_ALIAS))
    /** Live P&L notice; runs for the lifetime of the task and is cleared when the model is dropped. */
    val notifier = LivePnlNotifier(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init { scope.launch { runtime.livePnl.run(notifier) } }
    override fun onCleared() {
        scope.cancel()
        runtime.registry.plugins.forEach { it.disconnect() }
        super.onCleared()
    }
}

private class AndroidSnapshotStore(private val directory: File) : SnapshotStore {
    override fun read(key: String): String? {
        val file = File(directory, "$key.json")
        val atomic = AtomicFile(file)
        return if (file.exists() || File(file.path + ".bak").exists()) atomic.openRead().bufferedReader().use { it.readText() } else null
    }
    override fun write(key: String, value: String) {
        check(directory.exists() || directory.mkdirs()) { "无法创建数据目录" }
        val file = AtomicFile(File(directory, "$key.json"))
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
