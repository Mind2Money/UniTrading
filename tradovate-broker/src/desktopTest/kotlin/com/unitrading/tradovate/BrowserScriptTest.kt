package com.unitrading.tradovate

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserScriptTest {
    @Test fun productionBridgePassesIsolatedJavaScriptTests() {
        val target = Path.of("../artifacts/tradovate-login-bridge.js").toAbsolutePath()
        Files.createDirectories(target.parent)
        Files.writeString(target, TradovateLoginScript.forEnvironment(TradovateEnvironment.DEMO))
        val process = ProcessBuilder("node", "../native-auth/test-bridge.cjs", target.toString()).redirectErrorStream(true).start()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Browser bridge tests timed out")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("LOGIN_BRIDGE_TEST PASS"))
    }
}
