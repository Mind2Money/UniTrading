package com.unitrading.android

import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.unitrading.app.TradingRuntime
import com.unitrading.app.SnapshotStore
import com.unitrading.ui.TradingScreen
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val model = ViewModelProvider(this)[TradingRuntimeModel::class.java]
        val login = AndroidTradovateLogin(this)
        setContent { TradingScreen(model.runtime, login) }
    }
}

class TradingRuntimeModel(application: Application) : AndroidViewModel(application) {
    val runtime = TradingRuntime(AndroidSnapshotStore(File(application.filesDir, "demo-v1")))
    override fun onCleared() {
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
