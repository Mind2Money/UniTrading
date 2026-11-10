package com.unitrading.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView

/** Configure each fallback process before content providers or WebKit can initialize. */
class TradingApplication : Application() {
    var loginDirectoryReady = false
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT < 28) return
        val suffix = when (getProcessName()) {
            "$packageName:tradovate_demo" -> "tradovate-demo-compat"
            "$packageName:tradovate_live" -> "tradovate-live-compat"
            else -> return
        }
        loginDirectoryReady = runCatching { WebView.setDataDirectorySuffix(suffix) }.isSuccess
    }
}
