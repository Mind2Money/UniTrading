package com.unitrading.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.unitrading.tradovate.*
import kotlinx.coroutines.*

/** Only an in-process deferred carries credentials; Intents and saved state never do. */
private object LoginTransfer {
    var pending: CompletableDeferred<TradovateSession>? = null
    var progress: ((String) -> Unit)? = null
    var window: TradovateLoginWindow? = null
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

internal const val TRADOVATE_LOGIN_ORIGIN = "https://trader.tradovate.com"
internal const val TRADOVATE_LOGIN_PAGE = "$TRADOVATE_LOGIN_ORIGIN/welcome"

/** Where the login window's main frame may go, whichever browser shows it. */
internal fun isOfficialLoginAddress(uri: Uri) =
    uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null && uri.host in setOf("trader.tradovate.com", "auth.tradovate.com", "login.tradovate.com")

/** The login window's frame and its hand-over to the app. Each build flavor's [TradovateLoginActivity] supplies the browser inside it. */
abstract class TradovateLoginWindow : ComponentActivity() {
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoReturn: Job? = null
    private var sessionCaptured = false
    private var delivered = false
    private var environment: TradovateEnvironment? = null
    private lateinit var accumulator: LoginAccumulator
    private lateinit var frame: LinearLayout
    private lateinit var status: TextView
    private lateinit var done: Button
    private var autofill: AutofillManager? = null

    /** Called once the frame is on screen; ends by calling [addBrowser], or [showStatus] when no browser can be shown. */
    protected abstract fun openBrowser(environment: TradovateEnvironment)
    /** Writes what the browser keeps, such as cookies, before the window closes. */
    protected abstract fun saveBrowserState()
    protected abstract fun closeBrowser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val environment = TradovateEnvironment.entries.firstOrNull { it.wire == intent.getStringExtra("environment") }
        if (environment == null || LoginTransfer.pending == null) { finish(); return }
        this.environment = environment
        LoginTransfer.window = this
        accumulator = LoginAccumulator(environment)
        frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(15, 27, 45)) }
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + gap, bars.top + gap, bars.right + gap, bars.bottom + gap)
            insets
        }
        status = TextView(this).apply { text = "Tradovate ${environment.wire} · 官方网页登录\n$TRADOVATE_LOGIN_ORIGIN"; setTextColor(Color.WHITE); textSize = 14f }
        val actions = LinearLayout(this)
        done = Button(this).apply { text = "立即返回"; isEnabled = false; setOnClickListener { completeLogin() } }
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { finish() } }
        actions.addView(done); actions.addView(cancel)
        frame.addView(status); frame.addView(actions)
        autofill = getSystemService(AutofillManager::class.java)
        val autofillEnabled = autofill?.isEnabled == true
        showNotice(if (autofillEnabled) "系统密码服务负责保存和自动填充。登录后可选择保存密码。" else "系统自动填充尚未启用，请到系统设置选择密码或自动填充服务。")
        if (!autofillEnabled) frame.addView(Button(this).apply { text = "系统自动填充设置"; setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) } })
        setContentView(frame)
        openBrowser(environment)
    }

    protected fun addBrowser(browser: View) = frame.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    protected fun showStatus(text: String) { status.text = text }
    protected fun showPage(host: String?) { if (!sessionCaptured) status.text = "Tradovate ${environment?.wire} · ${host ?: "官方登录"}" }
    protected fun showBlockedAddress() = showStatus("已阻止离开官方登录域名。请在 Tradovate 官方页面完成登录。")
    protected fun showNotice(text: String) = frame.addView(TextView(this).apply { this.text = text; setTextColor(Color.LTGRAY); textSize = 12f })

    /** A message from the login script. The browser has already confirmed that it came from the official origin. */
    protected fun receiveAuth(message: String, origin: String, mainFrame: Boolean) {
        if (!accumulator.accept(message, origin, mainFrame)) return
        done.isEnabled = true
        sessionCaptured = true
        if (autoReturn?.isActive == true || delivered) return
        status.text = "网页登录已成功，正在自动返回交易终端…"
        LoginTransfer.progress?.invoke(status.text.toString())
        runCatching { autofill?.commit() }
        autoReturn = loginScope.launch {
            val started = SystemClock.elapsedRealtime()
            delay(1_500)
            while (!hasWindowFocus() && SystemClock.elapsedRealtime() - started < 60_000) {
                val seconds = ((60_000 - (SystemClock.elapsedRealtime() - started)) / 1000).coerceAtLeast(0)
                status.text = "登录成功，等待系统提示保存密码，最多再等 ${seconds} 秒"
                LoginTransfer.progress?.invoke(status.text.toString())
                delay(250)
            }
            LoginTransfer.progress?.invoke("已收到登录信息，正在自动返回…")
            completeLogin()
        }
    }

    private fun completeLogin() {
        if (delivered || isFinishing) return
        try {
            val session = accumulator.result()
            saveBrowserState()
            delivered = LoginTransfer.pending?.complete(session) == true
            finish()
        } catch (refused: TradovateRemoteException) { status.text = refused.message }
        catch (_: Exception) { status.text = "登录会话不完整或已过期，请重新登录" }
    }

    override fun onDestroy() {
        loginScope.cancel()
        if (LoginTransfer.window === this) LoginTransfer.window = null
        closeBrowser()
        if (!isChangingConfigurations) LoginTransfer.pending?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("已取消登录")) }
        super.onDestroy()
    }
}
