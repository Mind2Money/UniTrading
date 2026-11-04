package com.unitrading.app

import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small Objective-C-friendly boundary; Swift owns WebKit and Security framework calls. */
interface NativeLoginPresenter {
    fun open(environment: String, script: String)
    fun close()
}
class NativeStorageResult(val value: String?, val error: String?)
interface NativeCredentialStorage {
    fun read(): NativeStorageResult
    fun write(value: String): NativeStorageResult
    fun clear(): NativeStorageResult
}
/**
 * Live Activity surface; Swift owns ActivityKit. [trend] is 1 profit, -1 loss, 0 flat or unknown. Named
 * dismiss, not clear: a second exported `clear` selector with another return type makes Kotlin/Native
 * rename NativeCredentialStorage.clear to clear_ in the Objective-C header.
 */
interface NativeLivePnlPresenter {
    fun show(title: String, pnl: String, detail: String, trend: Int, timeMs: Long)
    fun dismiss()
}

class NativePlatformBridge {
    var presenter: NativeLoginPresenter? = null
    var storage: NativeCredentialStorage? = null
    /** Keychain item holding the license activation record, separate from the saved logins in [storage]. */
    var licenseStorage: NativeCredentialStorage? = null
    var livePnl: NativeLivePnlPresenter? = null
    private var pending: CancellableContinuation<TradovateSession>? = null
    private var accumulator: LoginAccumulator? = null

    internal val login: TradovateLogin = object : TradovateLogin {
        override suspend fun signIn(environment: TradovateEnvironment, onProgress: (String) -> Unit): TradovateSession = withContext(Dispatchers.Main) {
            check(pending == null) { "已有登录窗口" }
            val host = checkNotNull(presenter) { "未配置原生登录窗口" }
            accumulator = LoginAccumulator(environment)
            onProgress("请在官方网页中登录")
            try {
                suspendCancellableCoroutine { continuation ->
                    pending = continuation
                    try { host.open(environment.wire, TradovateLoginScript.forEnvironment(environment)) }
                    catch (_: Exception) { continuation.resumeWithException(IllegalStateException("无法打开官方登录窗口")) }
                }
            } finally {
                pending = null
                accumulator = null
                withContext(NonCancellable + Dispatchers.Main) { host.close() }
            }
        }
    }

    fun accept(message: String, origin: String, isMainFrame: Boolean): Boolean =
        pending?.isActive == true && accumulator?.accept(message, origin, isMainFrame) == true

    fun finish(cancelled: Boolean) {
        val continuation = pending?.takeIf { it.isActive } ?: return
        pending = null
        if (cancelled) continuation.resumeWithException(IllegalStateException("已取消登录"))
        else {
            val result = runCatching { accumulator?.result() ?: error("missing session") }
            result.fold(continuation::resume) { continuation.resumeWithException(IllegalStateException("官方登录尚未完成或会话无效")) }
        }
    }

    internal val livePnlPresenter: LivePnlPresenter = object : LivePnlPresenter {
        override fun show(update: LivePnlUpdate) { livePnl?.show(update.title, update.pnlText, update.detail, update.trend, update.timeMs) }
        override fun clear() { livePnl?.dismiss() }
    }

    internal val credentials: CredentialStore = BridgedCredentialStore { storage }
    internal val licenseCredentials: CredentialStore = BridgedCredentialStore { licenseStorage }
}

private class BridgedCredentialStore(private val storage: () -> NativeCredentialStorage?) : CredentialStore {
    private fun NativeStorageResult.checked(): String? {
        check(error == null) { "系统凭据存储操作失败" }
        return value
    }
    override suspend fun read(): String? = withContext(Dispatchers.Default) { checkNotNull(storage()).read().checked() }
    override suspend fun write(value: String) { withContext(Dispatchers.Default) { checkNotNull(storage()).write(value).checked() } }
    override suspend fun clear() { withContext(Dispatchers.Default) { checkNotNull(storage()).clear().checked() } }
}
