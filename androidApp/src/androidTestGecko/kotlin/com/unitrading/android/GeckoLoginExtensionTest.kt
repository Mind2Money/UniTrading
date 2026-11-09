package com.unitrading.android

import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.tradovate.TradovateEnvironment
import com.unitrading.tradovate.TradovateLoginScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mozilla.geckoview.GeckoResult

class GeckoLoginExtensionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> onMain(call: () -> GeckoResult<T>): T? {
        lateinit var result: GeckoResult<T>
        instrumentation.runOnMainSync { result = call() }
        return result.poll(60_000)
    }
    private fun install(environment: TradovateEnvironment) = onMain { LoginBrowser.runtime(context).webExtensionController.ensureBuiltIn(loginExtensionFolder(environment), LOGIN_EXTENSION_ID) }!!

    /** Fails on a shrunk build if the generated extension is left out of the APK, or if it stops matching the script the other platforms run. */
    @Test fun eachEnvironmentCarriesTheSharedLoginScript() {
        for (environment in TradovateEnvironment.entries) {
            assertEquals(TradovateLoginScript.forEnvironment(environment), context.assets.open("tradovate-login/${environment.wire}/login.js").bufferedReader().use { it.readText() })
        }
    }

    /** Two installed scripts would race for the page, and the first would capture the session under the wrong environment. */
    @Test fun openingAnEnvironmentReplacesTheOtherOnesScript() {
        val versions = listOf(TradovateEnvironment.DEMO, TradovateEnvironment.LIVE, TradovateEnvironment.DEMO).map { install(it).metaData.version }
        assertNotEquals(versions[0], versions[1])
        assertEquals(versions[0], versions[2])
        val installed = onMain { LoginBrowser.runtime(context).webExtensionController.list() }!!.filter { it.id == LOGIN_EXTENSION_ID }
        assertEquals(versions[2], installed.single().metaData.version)
    }
}
