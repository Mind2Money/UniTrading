package com.unitrading

import com.unitrading.tradovate.TradovateEnvironment
import com.unitrading.tradovate.TradovateHosts
import com.unitrading.tradovate.TradovateRemoteException
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopLoginHandoffTest {
    private val fixture = """
        const token = 'eyJhbGciOiJub25lIn0.' + Buffer.from(JSON.stringify({sub:42,exp:4102444800})).toString('base64url') + '.test-only-signature';
        console.log('STATUS WAITING_BROWSER');
        console.log('TOKEN ' + JSON.stringify({kind:'auth',environment:'demo',accessToken:token,userId:42}));
        console.log('STATUS SESSION_CAPTURED');
        console.log('STATUS PASSWORD_SAVE 1');
    """.trimIndent()

    private fun files(body: String): Pair<Path, Path> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("unitrading-login-pipe-")
        val executable = directory.resolve("fixture.cjs")
        Files.writeString(executable, body)
        return directory to executable
    }
    private fun remove(directory: Path) {
        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    @Test fun automaticDoneHandsOffSessionWithoutClickOrSeparateMdToken() : Unit = runBlocking {
        val (directory, executable) = files(fixture + "\nsetTimeout(() => { console.log('STATUS RETURNING'); console.log('DONE'); }, 20);")
        try {
            val phases = CopyOnWriteArrayList<String>()
            val login = DesktopTradovateLogin({ ProcessBuilder("node", executable.toString()).start() }, executable, directory.resolve("profile"))
            val session = withTimeout(5_000) { login.signIn(TradovateEnvironment.DEMO) { phases.add(it) } }
            assertEquals(42, session.userId)
            assertEquals(TradovateEnvironment.DEMO, session.environment)
            assertNull(session.mdAccessToken)
            assertTrue(phases.any { it.contains("网页登录成功") })
            assertTrue(phases.any { it.contains("1 秒后自动返回") })
            assertFalse(phases.any { it.contains(session.accessToken) })
        } finally { remove(directory) }
    }
    private fun routedLogin(apiHosts: String) = """
        const token = 'eyJhbGciOiJub25lIn0.' + Buffer.from(JSON.stringify({sub:42,exp:4102444800})).toString('base64url') + '.test-only-signature';
        console.log('TOKEN ' + JSON.stringify({kind:'auth',environment:'demo',accessToken:token,userId:42,apiHosts:$apiHosts}));
        console.log('DONE');
    """.trimIndent()
    @Test fun aRoutedLoginReachesTheAppWithItsHosts() : Unit = runBlocking {
        val (directory, executable) = files(routedLogin("{demo:'demo-api.org.ninjatrader.com',mdDemo:'md-demo-api.org.ninjatrader.com'}"))
        try {
            val login = DesktopTradovateLogin({ ProcessBuilder("node", executable.toString()).start() }, executable, directory.resolve("profile"))
            val session = withTimeout(5_000) { login.signIn(TradovateEnvironment.DEMO) }
            assertEquals(TradovateHosts("demo-api.org.ninjatrader.com", "md-demo-api.org.ninjatrader.com"), session.hosts)
        } finally { remove(directory) }
    }
    @Test fun aRefusedHostReachesTheLoginDialogWithItsReason() : Unit = runBlocking {
        val (directory, executable) = files(routedLogin("{demo:'demo-api.evil.example'}"))
        try {
            val login = DesktopTradovateLogin({ ProcessBuilder("node", executable.toString()).start() }, executable, directory.resolve("profile"))
            assertTrue(assertFailsWith<TradovateRemoteException> { withTimeout(5_000) { login.signIn(TradovateEnvironment.DEMO) } }.message!!.contains("apiHosts.demo"))
        } finally { remove(directory) }
    }
    @Test fun cancellationAfterTokenDoesNotCreateALoggedInSession() : Unit = runBlocking {
        val (directory, executable) = files(fixture + "\nconsole.log('ERROR login_cancelled'); process.exitCode = 2;")
        try {
            val login = DesktopTradovateLogin({ ProcessBuilder("node", executable.toString()).start() }, executable, directory.resolve("profile"))
            assertFailsWith<IllegalStateException> { login.signIn(TradovateEnvironment.DEMO) }
        } finally { remove(directory) }
    }
    @Test fun cancellingWaitTerminatesItsOwnChildProcess() : Unit = runBlocking {
        val (directory, executable) = files(fixture + "\nsetInterval(() => {}, 1000);")
        val child = AtomicReference<Process>()
        try {
            val login = DesktopTradovateLogin({ ProcessBuilder("node", executable.toString()).start().also(child::set) }, executable, directory.resolve("profile"))
            val pending = launch { login.signIn(TradovateEnvironment.DEMO) }
            withTimeout(5_000) { while (child.get() == null) delay(10) }
            pending.cancelAndJoin()
            assertTrue(child.get().waitFor(5, TimeUnit.SECONDS))
        } finally { child.get()?.destroyForcibly(); remove(directory) }
    }
}
