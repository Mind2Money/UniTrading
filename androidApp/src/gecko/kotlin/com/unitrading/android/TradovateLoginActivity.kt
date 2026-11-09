package com.unitrading.android

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.View
import com.unitrading.tradovate.TradovateEnvironment
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

internal const val LOGIN_EXTENSION_ID = "tradovate-login@unitrading.invalid"
private const val NATIVE_APP = "unitrading"

internal fun loginExtensionFolder(environment: TradovateEnvironment) = "resource://android/assets/tradovate-login/${environment.wire}/"

/** GeckoView allows one runtime per process and cannot start again, so this one stays up after the window closes. */
internal object LoginBrowser {
    private var runtime: GeckoRuntime? = null
    fun runtime(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        context.applicationContext,
        GeckoRuntimeSettings.Builder().allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY).aboutConfigEnabled(false).remoteDebuggingEnabled(false).consoleOutput(false).build(),
    ).also { runtime = it }
}

class TradovateLoginActivity : TradovateLoginWindow() {
    private var browser: GeckoView? = null
    private var session: GeckoSession? = null

    override fun openBrowser(environment: TradovateEnvironment) {
        val runtime = LoginBrowser.runtime(this)
        // A context keeps its own cookies and site storage, like a WebView profile.
        val page = GeckoSession(GeckoSessionSettings.Builder().contextId("unitrading-tradovate-${environment.wire}").build())
        session = page
        page.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                if (!isOfficialLoginAddress(Uri.parse(request.uri))) { showBlockedAddress(); return GeckoResult.deny() }
                if (request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) return GeckoResult.allow()
                // No second window: an official link that asks for one opens here, as it does in Android WebView.
                session.loadUri(request.uri)
                return GeckoResult.deny()
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                showStatus(if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) "TLS 证书校验失败，已停止登录" else "登录页面加载失败，请检查网络后重试")
                return null
            }
        }
        page.progressDelegate = object : GeckoSession.ProgressDelegate {
            private var host: String? = null
            override fun onPageStart(session: GeckoSession, url: String) { host = Uri.parse(url).host }
            override fun onPageStop(session: GeckoSession, success: Boolean) { if (success) showPage(host) }
        }
        page.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) = showStatus("内置浏览器的页面进程已退出，请取消后重新登录")
            override fun onKill(session: GeckoSession) = showStatus("内置浏览器的页面进程已退出，请取消后重新登录")
        }
        page.promptDelegate = object : GeckoSession.PromptDelegate {
            // A page's <select> opens only through this; without it the login page's language list does nothing.
            override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val options = prompt.choices.flatMap { it.items?.asList() ?: listOf(it) }.filter { !it.separator && !it.disabled }
                if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE || options.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
                val choice = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                AlertDialog.Builder(this@TradovateLoginActivity)
                    .setItems(options.map { it.label }.toTypedArray()) { _, index -> choice.complete(prompt.confirm(options[index])) }
                    .setOnCancelListener { choice.complete(prompt.dismiss()) }
                    .show()
                return choice
            }
        }
        page.open(runtime)
        browser = GeckoView(this).apply { importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES; setSession(page) }.also(::addBrowser)
        showStatus("Tradovate ${environment.wire} · 正在启动内置浏览器…")
        val messages = object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val origin = runCatching { Uri.parse(sender.url).let { "${it.scheme}://${it.host}" } }.getOrNull()
                if (sender.session === page && sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT && message is String && origin == TRADOVATE_LOGIN_ORIGIN) receiveAuth(message, origin, sender.isTopLevel)
                return null
            }
        }
        // Must finish before the page loads: login.js has to be in place when the page's own scripts start.
        runtime.webExtensionController.ensureBuiltIn(loginExtensionFolder(environment), LOGIN_EXTENSION_ID).accept({ extension ->
            if (extension != null && session === page) {
                page.webExtensionController.setMessageDelegate(extension, messages, NATIVE_APP)
                page.loadUri(TRADOVATE_LOGIN_PAGE)
            }
        }, { showStatus("内置浏览器无法加载登录组件，请取消后重试") })
    }

    /** Gecko writes cookies to its profile on its own. */
    override fun saveBrowserState() = Unit

    override fun closeBrowser() {
        browser?.releaseSession()
        session?.close()
        browser = null
        session = null
    }
}
