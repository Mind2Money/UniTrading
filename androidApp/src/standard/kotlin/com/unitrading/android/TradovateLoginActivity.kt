package com.unitrading.android

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.unitrading.tradovate.*

/**
 * What the login cannot run without. A separate profile is not listed: without one Demo and Live share the website's
 * cookies, as they do on macOS 13, and the app still keeps their sessions apart.
 */
internal fun missingLoginFeatures(supported: (String) -> Boolean = WebViewFeature::isFeatureSupported): List<String> = listOf(
    WebViewFeature.DOCUMENT_START_SCRIPT to "页面启动脚本",
    WebViewFeature.WEB_MESSAGE_LISTENER to "受限消息通道",
).filterNot { supported(it.first) }.map { it.second }

internal fun webViewTooOldMessage(missing: List<String>, webView: String) =
    "当前系统 WebView（$webView）缺少登录需要的${missing.joinToString("、")}。请更新 Android System WebView，或改用文件名以 -webview.apk 结尾的内置浏览器版安装包。"

private fun systemWebViewName(context: Context) =
    WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" } ?: "版本未知"

class TradovateLoginActivity : TradovateLoginWindow() {
    private var browser: WebView? = null
    private val separateProfiles get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override fun openBrowser(environment: TradovateEnvironment) {
        val missing = missingLoginFeatures()
        if (missing.isNotEmpty()) {
            showStatus(webViewTooOldMessage(missing, systemWebViewName(this)))
            return
        }
        val web = WebView(this)
        browser = web
        if (separateProfiles) WebViewCompat.setProfile(web, "unitrading-tradovate-${environment.wire}")
        else showNotice("当前系统 WebView（${systemWebViewName(this)}）不支持独立配置，Demo 和 Live 共用网站登录状态。")
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
                val allowed = isOfficialLoginAddress(request.url)
                if (!allowed) showBlockedAddress()
                return !allowed
            }
            override fun onPageFinished(view: WebView, url: String) = showPage(runCatching { Uri.parse(url).host }.getOrNull())
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); showStatus("TLS 证书校验失败，已停止登录")
            }
        }
        WebViewCompat.addWebMessageListener(web, "UniTradingAuth", setOf(TRADOVATE_LOGIN_ORIGIN)) { view, message, sourceOrigin, isMainFrame, _ ->
            val currentOrigin = runCatching { Uri.parse(view.url).let { "${it.scheme}://${it.host}" } }.getOrNull()
            if (currentOrigin == TRADOVATE_LOGIN_ORIGIN) receiveAuth(message.data ?: "", sourceOrigin.toString(), isMainFrame)
        }
        WebViewCompat.addDocumentStartJavaScript(web, TradovateLoginScript.forEnvironment(environment), setOf(TRADOVATE_LOGIN_ORIGIN))
        addBrowser(web)
        web.loadUrl(TRADOVATE_LOGIN_PAGE)
    }

    override fun saveBrowserState() {
        val web = browser ?: return
        (if (separateProfiles) WebViewCompat.getProfile(web).cookieManager else CookieManager.getInstance()).flush()
    }

    override fun closeBrowser() {
        browser?.let { it.stopLoading(); it.destroy() }
        browser = null
    }
}
