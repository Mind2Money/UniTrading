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
    var progress: ((String) -> Unit)? = null
    var window: TradovateLoginActivity? = null
}
class AndroidTradovateLogin(private val activity: Activity) : TradovateLogin {
    override suspend fun signIn(environment: TradovateEnvironment, onProgress: (String) -> Unit): TradovateSession = withContext(Dispatchers.Main) {
        check(LoginTransfer.pending == null) { "已有登录窗口" }
        val result = CompletableDeferred<TradovateSession>()
        LoginTransfer.pending = result
        LoginTransfer.progress = onProgress
        onProgress("请在官方网页中登录，成功后将自动返回")
        try {
            activity.startActivity(Intent(activity, TradovateLoginActivity::class.java).putExtra("environment", environment.wire))
            result.await()
        } finally {
            if (LoginTransfer.pending === result) {
                LoginTransfer.pending = null
                LoginTransfer.progress = null
                LoginTransfer.window?.finish()
            }
        }
    }
}

class TradovateLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoReturn: Job? = null
    private var sessionCaptured = false
    private var delivered = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val environment = TradovateEnvironment.entries.firstOrNull { it.wire == intent.getStringExtra("environment") }
        if (environment == null || LoginTransfer.pending == null) { finish(); return }
        LoginTransfer.window = this
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
        val done = Button(this).apply { text = "立即返回"; isEnabled = false }
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { finish() } }
        actions.addView(done); actions.addView(cancel)
        layout.addView(status); layout.addView(actions)
        val autofill = getSystemService(AutofillManager::class.java)
        val hint = TextView(this).apply { text = if (autofill?.isEnabled == true) "系统密码服务负责保存和自动填充。登录后可选择保存密码。" else "系统自动填充尚未启用，请到系统设置选择密码或自动填充服务。"; setTextColor(Color.LTGRAY); textSize = 12f }
        layout.addView(hint)
        if (autofill?.isEnabled != true) layout.addView(Button(this).apply { text = "系统自动填充设置"; setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) } })
        setContentView(layout)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            status.text = "请先更新 Android System WebView。当前版本缺少登录所需的安全消息、页面启动脚本或独立浏览器配置支持。"
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
                if (!sessionCaptured) status.text = "Tradovate ${environment.wire} · ${host ?: "官方登录"}"
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); status.text = "TLS 证书校验失败，已停止登录"
            }
        }
        fun completeLogin() {
            if (delivered || isFinishing) return
            try {
                val session = accumulator.result()
                WebViewCompat.getProfile(web).cookieManager.flush()
                delivered = LoginTransfer.pending?.complete(session) == true
                finish()
            } catch (refused: TradovateRemoteException) { status.text = refused.message }
            catch (_: Exception) { status.text = "登录会话不完整或已过期，请重新登录" }
        }
        WebViewCompat.addWebMessageListener(web, "UniTradingAuth", setOf("https://trader.tradovate.com")) { view, message, sourceOrigin, isMainFrame, _ ->
            val currentOrigin = runCatching { Uri.parse(view.url).let { "${it.scheme}://${it.host}" } }.getOrNull()
            if (currentOrigin != "https://trader.tradovate.com") return@addWebMessageListener
            if (accumulator.accept(message.data ?: "", sourceOrigin.toString(), isMainFrame)) {
                done.isEnabled = true
                sessionCaptured = true
                if (autoReturn?.isActive != true && !delivered) {
                    status.text = "网页登录已成功，正在自动返回交易终端…"
                    LoginTransfer.progress?.invoke(status.text.toString())
                    runCatching { autofill?.commit() }
                    autoReturn = loginScope.launch {
                        val started = android.os.SystemClock.elapsedRealtime()
                        delay(1_500)
                        while (!hasWindowFocus() && android.os.SystemClock.elapsedRealtime() - started < 60_000) {
                            val seconds = ((60_000 - (android.os.SystemClock.elapsedRealtime() - started)) / 1000).coerceAtLeast(0)
                            status.text = "登录成功，等待系统提示保存密码，最多再等 ${seconds} 秒"
                            LoginTransfer.progress?.invoke(status.text.toString())
                            delay(250)
                        }
                        LoginTransfer.progress?.invoke("已收到登录信息，正在自动返回…")
                        completeLogin()
                    }
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(web, TradovateLoginScript.forEnvironment(environment), setOf("https://trader.tradovate.com"))
        done.setOnClickListener { completeLogin() }
        layout.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadUrl("https://trader.tradovate.com/welcome")
    }
    override fun onDestroy() {
        loginScope.cancel()
        if (LoginTransfer.window === this) LoginTransfer.window = null
        browser?.let { it.stopLoading(); it.destroy() }
        browser = null
        if (!isChangingConfigurations) LoginTransfer.pending?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("已取消登录")) }
        super.onDestroy()
    }
}
