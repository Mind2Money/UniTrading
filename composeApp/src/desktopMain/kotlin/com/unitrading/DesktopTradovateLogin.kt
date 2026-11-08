package com.unitrading

import com.unitrading.tradovate.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopTradovateLogin(
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val executableOverride: Path? = null,
    private val profileOverride: Path? = null,
) : TradovateLogin {
    override suspend fun signIn(environment: TradovateEnvironment, onProgress: (String) -> Unit): TradovateSession {
        require(System.getProperty("os.name").startsWith("Windows")) { "当前桌面网页登录组件仅支持 Windows" }
        val root = profileOverride ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "UniTrading", "browser")
        val executable = listOfNotNull(
            executableOverride,
            System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "tradovate-login", "1.1.1", "UniTrading.TradovateLogin.exe") },
            Path.of("native-auth", "build", "1.1.1", "UniTrading.TradovateLogin.exe"),
            Path.of("..", "native-auth", "build", "1.1.1", "UniTrading.TradovateLogin.exe"),
        ).firstOrNull { Files.isRegularFile(it) } ?: error("登录组件未构建，请运行 buildTradovateLoginHost")
        val script = withContext(Dispatchers.IO) {
            Files.createDirectories(root)
            Files.createTempFile(root, "login-bridge-", ".js").also { Files.writeString(it, TradovateLoginScript.forEnvironment(environment)) }
        }
        onProgress("正在打开官方登录窗口…")
        return suspendCancellableCoroutine { continuation ->
            val process = try {
                launch(listOf(executable.toAbsolutePath().toString(), "--profile", root.toAbsolutePath().toString(),
                    "--environment", environment.wire, "--script", script.toAbsolutePath().toString()))
            } catch (_: Exception) {
                Files.deleteIfExists(script)
                continuation.resumeWithException(IllegalStateException("无法启动安全登录窗口"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { process.destroyForcibly() }
            thread(name = "tradovate-login-pipe", isDaemon = true) {
                try {
                    val accumulator = LoginAccumulator(environment)
                    var done = false
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("TOKEN ") && line.length <= 131_080) accumulator.accept(line.removePrefix("TOKEN "), "https://trader.tradovate.com", true)
                            when {
                                line == "STATUS WAITING_BROWSER" -> onProgress("请在官方网页中登录")
                                line == "STATUS SESSION_CAPTURED" -> onProgress("网页登录成功，已收到登录信息")
                                line.startsWith("STATUS PASSWORD_SAVE ") -> line.substringAfterLast(' ').toIntOrNull()?.takeIf { it in 0..60 }?.let {
                                    onProgress("登录成功，等待浏览器保存密码。${it} 秒后自动返回，也可点击“立即返回”")
                                }
                                line == "STATUS RETURNING" -> onProgress("登录成功，正在将账户信息传回应用…")
                            }
                            if (line == "DONE") done = true
                        }
                    }
                    val code = process.waitFor()
                    if (!done || code != 0) error("官方网页登录未完成")
                    val result = accumulator.result()
                    if (continuation.isActive) continuation.resume(result)
                } catch (refused: TradovateRemoteException) {
                    if (continuation.isActive) continuation.resumeWithException(refused)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("官方网页登录未完成或会话无效"))
                } finally {
                    process.destroy()
                    runCatching { Files.deleteIfExists(script) }
                }
            }
        }
    }
}
