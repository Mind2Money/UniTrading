package com.unitrading

import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.unitrading.app.SnapshotStore
import com.unitrading.app.NativePlatformBridge
import com.unitrading.app.TradingRuntime
import com.unitrading.ui.TradingScreen
import platform.Foundation.NSUserDefaults

fun MainViewController(platform: NativePlatformBridge) = ComposeUIViewController {
    val store = remember { AppleSnapshotStore() }
    val runtime = remember { TradingRuntime(store, credentialStore = platform.credentials) }
    DisposableEffect(runtime) { onDispose { runtime.registry.plugins.forEach { it.disconnect() } } }
    LaunchedEffect(runtime) { runtime.livePnl.run(platform.livePnlPresenter) }
    TradingScreen(runtime, platform.login)
}

private class AppleSnapshotStore : SnapshotStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun read(key: String): String? = defaults.stringForKey("unitrading.demo.v1.$key")
    override fun write(key: String, value: String) { defaults.setObject(value, forKey = "unitrading.demo.v1.$key") }
}
