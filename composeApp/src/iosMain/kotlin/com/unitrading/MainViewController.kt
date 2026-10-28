package com.unitrading

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.unitrading.app.SnapshotStore
import com.unitrading.ui.UniTradingApp
import platform.Foundation.NSUserDefaults

fun MainViewController() = ComposeUIViewController {
    val store = remember { AppleSnapshotStore() }
    UniTradingApp(store)
}

private class AppleSnapshotStore : SnapshotStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun read(key: String): String? = defaults.stringForKey("unitrading.demo.v1.$key")
    override fun write(key: String, value: String) { defaults.setObject(value, forKey = "unitrading.demo.v1.$key") }
}
