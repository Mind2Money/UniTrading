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

class DesktopTradovateLogin : TradovateLogin {
    override suspend fun signIn(environment: TradovateEnvironment): TradovateSession {
        require(System.getProperty("os.name").startsWith("Windows")) { "当前桌面网页登录组件仅支持 Windows" }
        val root = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "UniTrading", "browser")
        val executable = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "tradovate-login", "UniTrading.TradovateLogin.exe") },
            Path.of("native-auth", "build", "UniTrading.TradovateLogin.exe"),
            Path.of("..", "native-auth", "build", "UniTrading.TradovateLogin.exe"),
        ).firstOrNull { Files.isRegularFile(it) } ?: error("登录组件未构建，请运行 buildTradovateLoginHost")
        val script = withContext(Dispatchers.IO) {
            Files.createDirectories(root)
            Files.createTempFile(root, "login-bridge-", ".js").also { Files.writeString(it, TradovateLoginScript.forEnvironment(environment)) }
        }
        return suspendCancellableCoroutine { continuation ->
            val process = try {
                ProcessBuilder(executable.toAbsolutePath().toString(), "--profile", root.toAbsolutePath().toString(),
                    "--environment", environment.wire, "--script", script.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
                            if (line == "DONE") done = true
                        }
                    }
                    val code = process.waitFor()
                    if (!done || code != 0) error("官方网页登录未完成")
                    val result = accumulator.result()
                    if (continuation.isActive) continuation.resume(result)
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
