package com.unitrading.app

import com.unitrading.broker.*
import com.unitrading.demo.DemoBroker
import com.unitrading.demo.DemoConfigs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class AppUpdatesTest {
    private val windows = { version: String -> Regex("^UniTrading-${Regex.escape(version)}-windows-x64\\.zip$") }
    private val android = { version: String -> Regex("^UniTrading-${Regex.escape(version)}-[0-9a-f]{7,40}\\.apk$") }
    private val sha = "0123456789abcdef".repeat(4)
    private val pages = "https://github.com/Mind2Money/UniTrading/releases"

    private fun asset(
        name: String = "UniTrading-1.4.0-windows-x64.zip", tag: String = "v1.4.0", digest: String? = "sha256:$sha", size: Long = 73_108_933, state: String = "uploaded",
        url: String = "https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/723525934", direct: String = "$pages/download/$tag/$name",
    ) = """{"url":"$url","name":"$name","state":"$state","size":$size,${digest?.let { "\"digest\":\"$it\"," }.orEmpty()}"browser_download_url":"$direct","download_count":12}"""

    private fun release(tag: String = "v1.4.0", assets: List<String> = listOf(asset()), page: String = "$pages/tag/$tag", draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","html_url":"$page","draft":$draft,"prerelease":$prerelease,"published_at":"2026-11-08T09:47:02Z","body":"notes","assets":[${assets.joinToString(",")}],"extra":{"ignored":true}}"""

    private fun assertInvalid(text: String) = assertEquals("UPDATE_INVALID", assertFailsWith<UpdateFailure> { parseLatestRelease(text, windows) }.code)

    @Test fun readsThisPlatformsFileOfARelease() {
        val apk = asset("UniTrading-1.4.0-79768fe.apk", url = "https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/9", size = 3_221_147)
        val text = release(assets = listOf(apk, asset(), asset("UniTrading-1.4.0-android-mapping.txt", size = 51_926_129)))
        val desktop = parseLatestRelease(text, windows)
        assertEquals("v1.4.0", desktop.version)
        assertEquals("$pages/tag/v1.4.0", desktop.pageUrl)
        assertEquals(ReleasePackage("v1.4.0", "UniTrading-1.4.0-windows-x64.zip",
            listOf("https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/723525934", "$pages/download/v1.4.0/UniTrading-1.4.0-windows-x64.zip"), 73_108_933, sha), desktop.packageFile)
        val phone = parseLatestRelease(text, android).packageFile
        assertEquals("UniTrading-1.4.0-79768fe.apk", phone?.name)
        assertEquals(3_221_147, phone?.bytes)
    }

    @Test fun anythingButThisRepositorysPublishedStableReleaseIsRefused() {
        assertInvalid("not json")
        assertInvalid("[]")
        assertInvalid(release(tag = "1.4.0"))
        assertInvalid(release(tag = "v1.4"))
        assertInvalid(release(tag = "v1.4.0-beta.1"))
        assertInvalid(release(page = "https://github.com/someone/UniTrading/releases/tag/v1.4.0"))
        assertInvalid(release(page = "$pages/tag/v9.9.9"))
        assertInvalid(release(draft = true))
        assertInvalid(release(prerelease = true))
        assertInvalid("""{"html_url":"$pages/tag/v1.4.0","draft":false,"prerelease":false,"assets":[]}""")
    }

    @Test fun aFileThatCannotBeVerifiedIsNotOffered() {
        fun offered(vararg assets: String) = parseLatestRelease(release(assets = assets.toList()), windows).packageFile
        assertNotNull(offered(asset()))
        assertNull(offered(), "No file for this platform")
        assertNull(offered(asset(name = "UniTrading-1.3.9-windows-x64.zip")), "Another version's file")
        assertNull(offered(asset(digest = null)), "GitHub reported no digest")
        assertNull(offered(asset(digest = "md5:abc")))
        assertNull(offered(asset(digest = "sha256:${sha.uppercase()}")))
        assertNull(offered(asset(digest = "sha256:abc")))
        assertNull(offered(asset(size = 0)))
        assertNull(offered(asset(size = 3L shl 30)))
        assertNull(offered(asset(state = "starter")), "Still uploading")
        assertNull(offered(asset(url = "https://example.com/repos/Mind2Money/UniTrading/releases/assets/1")))
        assertNull(offered(asset(url = "https://api.github.com/repos/Mind2Money/Other/releases/assets/1")))
        assertNull(offered(asset(url = "https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/1/../2")))
        assertNull(offered(asset(direct = "https://example.com/UniTrading-1.4.0-windows-x64.zip")))
        assertNull(offered(asset(), asset()), "Two candidates are ambiguous")
    }

    @Test fun versionsCompareAsNumbers() {
        assertTrue(compareVersions("v1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.3.1", "v1.3.2") < 0)
        assertEquals(0, compareVersions("v1.3.1", "1.3.1"))
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
        for (bad in listOf("", "1.3", "1.3.1-rc", "01.3.1", "v", "1.3.99999999999")) assertEquals("UPDATE_INVALID", assertFailsWith<UpdateFailure>(bad) { compareVersions(bad, "1.0.0") }.code)
    }

    private val demo = DemoBroker(DemoConfigs.all.first())
    private val position = Position("p", "MES", Side.BUY, 100, 500, 0, marginMicros = 0)
    private val order = PendingOrder("o", OrderRequest("MES", Side.BUY, OrderKind.LIMIT, 100, 500), 0)

    private fun plugin(environment: BrokerEnvironment = BrokerEnvironment.REMOTE_DEMO, ready: Boolean = true, unreadable: Boolean = false, vararg accounts: AccountSnapshot): BrokerPlugin = object : BrokerPlugin by demo {
        override val descriptor = BrokerDescriptor("b-${environment.name}-${accounts.size}-$ready", environment.label, "test", simulated = false, environment = environment)
        override val accounts = accounts.map { AccountInfo(it.accountId, it.accountId) }
        override val market = MutableStateFlow(MarketSnapshot(connected = ready, tradingReady = ready))
        override fun openSession(accountId: String): AccountSession {
            check(!unreadable) { "gone" }
            val base = demo.openSession(demo.accounts.first().id)
            return object : AccountSession by base { override val state = MutableStateFlow(accounts.first { it.accountId == accountId }) }
        }
    }

    private fun flat(id: String = "flat") = AccountSnapshot(id, 1)

    @Test fun openTradesOnAnyServerAccountHoldTheUpdate() {
        assertNull(BrokerRegistry(emptyList()).updateBlock())
        assertNull(BrokerRegistry(listOf(plugin(accounts = arrayOf(flat())))).updateBlock())
        assertEquals(UpdateBlock.OPEN_TRADES, BrokerRegistry(listOf(plugin(accounts = arrayOf(flat("a"), flat("b").copy(positions = listOf(position)))))).updateBlock(), "An account other than the first")
        assertEquals(UpdateBlock.OPEN_TRADES, BrokerRegistry(listOf(plugin(accounts = arrayOf(flat().copy(orders = listOf(order)))))).updateBlock())
        assertEquals(UpdateBlock.OPEN_TRADES, BrokerRegistry(listOf(plugin(accounts = arrayOf(flat())), plugin(BrokerEnvironment.RITHMIC_PAPER, accounts = arrayOf(flat().copy(positions = listOf(position)))))).updateBlock(), "A broker other than the first")
    }

    @Test fun anUnconfirmedAccountHoldsTheUpdateAndALocalDemoDoesNot() {
        assertEquals(UpdateBlock.ACCOUNT_UNCONFIRMED, BrokerRegistry(listOf(plugin(ready = false, accounts = arrayOf(flat())))).updateBlock())
        assertEquals(UpdateBlock.ACCOUNT_UNCONFIRMED, BrokerRegistry(listOf(plugin(unreadable = true, accounts = arrayOf(flat())))).updateBlock())
        assertEquals(UpdateBlock.OPEN_TRADES, BrokerRegistry(listOf(plugin(ready = false, accounts = arrayOf(flat().copy(positions = listOf(position)))))).updateBlock(), "Known trades outrank an unconfirmed state")
        assertNull(BrokerRegistry(listOf(plugin(BrokerEnvironment.LOCAL_DEMO, ready = false, accounts = arrayOf(flat().copy(positions = listOf(position)))))).updateBlock())
    }
}
