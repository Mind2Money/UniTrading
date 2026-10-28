package com.unitrading.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.unitrading.tradovate.*
import kotlinx.coroutines.*

/** Only an in-process deferred carries credentials; Intents and saved state never do. */
private object LoginTransfer {
    var pending: CompletableDeferred<TradovateSession>? = null
}
class AndroidTradovateLogin(private val activity: Activity) : TradovateLogin {
    override suspend fun signIn(environment: TradovateEnvironment): TradovateSession = withContext(Dispatchers.Main) {
        check(LoginTransfer.pending == null) { "已有登录窗口" }
        val result = CompletableDeferred<TradovateSession>()
        LoginTransfer.pending = result
        try {
            activity.startActivity(Intent(activity, TradovateLoginActivity::class.java).putExtra("environment", environment.wire))
            result.await()
        } finally { if (LoginTransfer.pending === result) LoginTransfer.pending = null }
    }
}

class TradovateLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val environment = TradovateEnvironment.entries.firstOrNull { it.wire == intent.getStringExtra("environment") }
        if (environment == null || LoginTransfer.pending == null) { finish(); return }
        val accumulator = LoginAccumulator(environment)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(15, 27, 45)) }
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + gap, bars.top + gap, bars.right + gap, bars.bottom + gap)
            insets
        }
        val status = TextView(this).apply { text = "Tradovate ${environment.wire} · 官方网页登录\nhttps://trader.tradovate.com"; setTextColor(Color.WHITE); textSize = 14f }
        val actions = LinearLayout(this)
        val done = Button(this).apply { text = "完成登录"; isEnabled = false }
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { finish() } }
        actions.addView(done); actions.addView(cancel)
        layout.addView(status); layout.addView(actions)
        val autofill = getSystemService(AutofillManager::class.java)
        val hint = TextView(this).apply { text = if (autofill?.isEnabled == true) "保存与自动填充由系统密码服务提供；请在登录后选择保存。" else "未启用系统自动填充服务。请在系统设置中选择密码/自动填充服务。"; setTextColor(Color.LTGRAY); textSize = 12f }
        layout.addView(hint)
        if (autofill?.isEnabled != true) layout.addView(Button(this).apply { text = "系统自动填充设置"; setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) } })
        setContentView(layout)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            status.text = "请先更新 Android System WebView：需要安全消息桥、文档起始脚本与独立浏览器 profile。"
            return
        }
        val web = WebView(this)
        browser = web
        WebViewCompat.setProfile(web, "unitrading-tradovate-${environment.wire}")
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.safeBrowsingEnabled = true
        web.settings.setSupportMultipleWindows(false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val allowed = uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null && uri.host in setOf("trader.tradovate.com", "auth.tradovate.com", "login.tradovate.com")
                if (!allowed) status.text = "已阻止离开官方登录域名。请在 Tradovate 官方页面完成登录。"
                return !allowed
            }
            override fun onPageFinished(view: WebView, url: String) {
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                status.text = "Tradovate ${environment.wire} · ${host ?: "官方登录"}"
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); status.text = "TLS 证书校验失败，已停止登录"
            }
        }
        WebViewCompat.addWebMessageListener(web, "UniTradingAuth", setOf("https://trader.tradovate.com")) { view, message, sourceOrigin, isMainFrame, _ ->
            val currentOrigin = runCatching { Uri.parse(view.url).let { "${it.scheme}://${it.host}" } }.getOrNull()
            if (currentOrigin != "https://trader.tradovate.com") return@addWebMessageListener
            if (accumulator.accept(message.data ?: "", sourceOrigin.toString(), isMainFrame)) {
                done.isEnabled = true
                status.text = "已收到本次登录会话。可先完成保存密码提示，再点击完成登录。"
            }
        }
        WebViewCompat.addDocumentStartJavaScript(web, TradovateLoginScript.forEnvironment(environment), setOf("https://trader.tradovate.com"))
        done.setOnClickListener {
            try {
                val session = accumulator.result()
                autofill?.commit()
                WebViewCompat.getProfile(web).cookieManager.flush()
                LoginTransfer.pending?.complete(session)
                finish()
            } catch (_: Exception) { status.text = "登录会话不完整或已过期，请重新登录" }
        }
        layout.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadUrl("https://trader.tradovate.com/welcome")
    }
    override fun onDestroy() {
        browser?.let { it.stopLoading(); it.destroy() }
        browser = null
        if (!isChangingConfigurations) LoginTransfer.pending?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("已取消登录")) }
        super.onDestroy()
    }
}
