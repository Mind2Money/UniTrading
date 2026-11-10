package com.unitrading.android

import android.app.Activity
import android.content.Intent
import android.os.*
import com.unitrading.tradovate.TradovateEnvironment
import com.unitrading.tradovate.TradovateSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.util.UUID

/** Only Binder messages carry credentials. Launch Intents contain a callback and request ID only. */
internal object LoginIpc {
    const val HELLO = 1
    const val READY = 2
    const val SESSION = 3
    const val CANCEL = 4
    const val CLOSE = 5
    const val REQUEST = "loginRequest"
    const val CALLBACK = "loginCallback"
    const val PAYLOAD = "sessionPayload"
    fun message(kind: Int, request: String) = Message.obtain().apply {
        what = kind
        data = Bundle().apply { putString(REQUEST, request) }
    }
}

/** Main-process endpoint, scoped to one invocation and one environment. */
internal class IsolatedLoginBridge(
    private val environment: TradovateEnvironment,
    private val result: CompletableDeferred<TradovateSession>,
) {
    private val request = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper()) { msg -> receive(msg); true }
    private val receiver = Messenger(handler)
    private var peer: Messenger? = null
    private var closed = false
    private val death = IBinder.DeathRecipient {
        handler.post { fail("登录进程已退出，请重新登录") }
    }
    private val startupTimeout = Runnable { fail("兼容登录窗口启动超时，请返回后重试") }

    fun launch(activity: Activity) {
        val target = if (environment == TradovateEnvironment.DEMO)
            TradovateDemoLoginActivity::class.java else TradovateLiveLoginActivity::class.java
        handler.postDelayed(startupTimeout, 30_000)
        activity.startActivity(Intent(activity, target)
            .putExtra("environment", environment.wire)
            .putExtra(LoginIpc.REQUEST, request)
            .putExtra(LoginIpc.CALLBACK, receiver))
    }

    private fun receive(msg: Message) {
        if (msg.sendingUid != Process.myUid() || msg.data.getString(LoginIpc.REQUEST) != request) return
        if (closed || result.isCompleted) {
            if (msg.what == LoginIpc.HELLO) runCatching {
                msg.replyTo?.send(LoginIpc.message(LoginIpc.CLOSE, request))
            }
            return
        }
        when (msg.what) {
            LoginIpc.HELLO -> {
                val candidate = msg.replyTo ?: return
                peer?.let { runCatching { it.binder.unlinkToDeath(death, 0) } }
                peer = candidate
                if (runCatching { candidate.binder.linkToDeath(death, 0) }.isFailure) {
                    fail("无法连接兼容登录窗口"); return
                }
                handler.removeCallbacks(startupTimeout)
                if (runCatching { candidate.send(LoginIpc.message(LoginIpc.READY, request)) }.isFailure)
                    fail("无法启动兼容登录窗口")
            }
            LoginIpc.SESSION -> {
                if (peer == null || msg.replyTo?.binder != peer?.binder) return
                val session = runCatching {
                    decodeIsolatedSession(msg.data.getString(LoginIpc.PAYLOAD), environment, System.currentTimeMillis())
                }.getOrNull()
                if (session == null) fail("登录会话无效或已过期，请重试") else result.complete(session)
            }
            LoginIpc.CANCEL -> {
                if (peer != null && msg.replyTo?.binder == peer?.binder) fail("已取消登录")
            }
        }
    }

    private fun fail(message: String) {
        if (!closed && !result.isCompleted) result.completeExceptionally(IllegalStateException(message))
    }

    fun close() {
        closed = true
        handler.removeCallbacks(startupTimeout)
        peer?.let {
            runCatching { it.send(LoginIpc.message(LoginIpc.CLOSE, request)) }
            runCatching { it.binder.unlinkToDeath(death, 0) }
        }
        peer = null
    }
}

/** Login-process endpoint. No tokens in saved state, Intent extras, logs or files. */
internal class IsolatedLoginClient(
    private val activity: Activity,
    private val start: () -> Unit,
) {
    private val request = activity.intent.getStringExtra(LoginIpc.REQUEST).orEmpty()
    @Suppress("DEPRECATION")
    private val parent = activity.intent.getParcelableExtra<Messenger>(LoginIpc.CALLBACK)
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.sendingUid == Process.myUid() && msg.data.getString(LoginIpc.REQUEST) == request) {
            when (msg.what) {
                LoginIpc.READY -> if (!started && !activity.isFinishing) { started = true; start() }
                LoginIpc.CLOSE -> activity.finish()
            }
        }
        true
    }
    private val receiver = Messenger(handler)
    private var started = false
    private val death = IBinder.DeathRecipient { handler.post { activity.finish() } }

    fun connect(): Boolean {
        val target = parent ?: return false
        if (request.length != 36) return false
        return runCatching {
            target.binder.linkToDeath(death, 0)
            target.send(LoginIpc.message(LoginIpc.HELLO, request).apply { replyTo = receiver })
        }.isSuccess
    }

    fun deliver(session: TradovateSession): Boolean = runCatching {
        check(started)
        val message = LoginIpc.message(LoginIpc.SESSION, request).apply {
            replyTo = receiver
            data.putString(LoginIpc.PAYLOAD, Json.encodeToString(TradovateSession.serializer(), session))
        }
        requireNotNull(parent).send(message)
    }.isSuccess

    fun close(cancelled: Boolean) {
        if (cancelled) runCatching {
            parent?.send(LoginIpc.message(LoginIpc.CANCEL, request).apply { replyTo = receiver })
        }
        runCatching { parent?.binder?.unlinkToDeath(death, 0) }
        handler.removeCallbacksAndMessages(null)
    }
}

class TradovateDemoLoginActivity : TradovateLoginActivity() {
    override val isolatedEnvironment = TradovateEnvironment.DEMO
}

class TradovateLiveLoginActivity : TradovateLoginActivity() {
    override val isolatedEnvironment = TradovateEnvironment.LIVE
}
