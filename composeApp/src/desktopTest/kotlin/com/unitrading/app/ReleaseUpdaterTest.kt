package com.unitrading.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** The shared update flow, with the platform and the network replaced. */
class ReleaseUpdaterTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(bytes: ByteArray) = ReleasePackage("v1.4.0", "p.zip", listOf("https://api.github.com/x"), bytes.size.toLong(), sha256(bytes))
    private val body = "package".toByteArray()
    private val latest = """{"tag_name":"v1.4.0","html_url":"https://github.com/Mind2Money/UniTrading/releases/tag/v1.4.0","draft":false,"prerelease":false,"assets":[]}"""

    private class FakeTarget(private val folder: File, override val install: UpdateInstall = UpdateInstall.RESTART, private val prepared: (File) -> Unit = {}) : UpdateTarget {
        var clears = 0
        var handOffs = 0
        var handOff: () -> Unit = {}
        override fun assetName(version: String) = Regex("^p\\.zip$")
        override fun archive(release: ReleasePackage) = File(folder, release.name)
        override fun prepare(archive: File, release: ReleasePackage) = prepared(archive)
        override fun clear(): Boolean { clears++; return true }
        override suspend fun handOff(release: ReleasePackage) { handOffs++; handOff() }
        override fun lastOutcome(): UpdateProgress = UpdateProgress.Idle
    }

    private fun withFolder(body: (File) -> Unit) { val root = Files.createTempDirectory("unitrading-updater-test-").toFile(); try { body(root) } finally { root.deleteRecursively() } }
    private fun settled(updates: ReleaseUpdater, done: (UpdateProgress) -> Boolean) = runBlocking { withTimeout(20_000) { updates.progress.first(done) } }
    private fun checked(updates: ReleaseUpdater) = runBlocking { withTimeout(20_000) { updates.check.first { it != UpdateCheck.Checking && it != UpdateCheck.Idle } } }
    private fun updater(target: UpdateTarget, version: String = "1.3.1", blocked: () -> UpdateBlock? = { null }, latest: () -> String = { this.latest }, open: (ReleasePackage) -> InputStream = { ByteArrayInputStream(body) }) =
        ReleaseUpdater(version, target, blocked, latest, open)

    @Test fun aCheckComparesTheLatestReleaseWithThisVersion() = withFolder { root ->
        val target = FakeTarget(root)
        val available = updater(target).also { it.checkNow() }
        assertEquals("v1.4.0", assertIs<UpdateCheck.Available>(checked(available)).release.version)
        assertEquals(UpdateCheck.Current("v1.4.0"), checked(updater(target, version = "1.4.0").also { it.checkNow() }))
        assertEquals(UpdateCheck.Ahead("v1.4.0"), checked(updater(target, version = "1.5.0").also { it.checkNow() }))
    }

    @Test fun aRunThatCannotReplaceItselfStillFindsNewReleases() {
        val updates = ReleaseUpdater("1.3.1", CheckOnlyUpdateTarget, { null }, { latest })
        assertEquals(UpdateInstall.MANUAL, updates.install)
        assertNull(updates.checkedAtMs.value)
        updates.checkNow()
        val found = assertIs<UpdateCheck.Available>(checked(updates))
        assertEquals("v1.4.0", found.release.version)
        assertNull(found.release.packageFile, "It has no file of its own, so the page offers the download instead")
        assertNotNull(updates.checkedAtMs.value)
        updates.close()
    }

    @Test fun aFailedCheckKeepsItsReason() = withFolder { root ->
        val target = FakeTarget(root)
        assertEquals(UpdateCheck.Failed("UPDATE_RATE_LIMITED"), checked(updater(target, latest = { throw UpdateFailure("UPDATE_RATE_LIMITED", "limited") }).also { it.checkNow() }))
        assertEquals(UpdateCheck.Failed("UPDATE_INVALID"), checked(updater(target, latest = { "garbage" }).also { it.checkNow() }))
        assertEquals(UpdateCheck.Failed("UPDATE_UNAVAILABLE"), checked(updater(target, latest = { throw IllegalStateException("boom") }).also { it.checkNow() }))
        assertEquals(UpdateCheck.Failed("UPDATE_INVALID"), checked(updater(target, version = "dev").also { it.checkNow() }), "A version that cannot be compared")
    }

    @Test fun oneRestartPressRunsThroughToTheHandOff() = withFolder { root ->
        val target = FakeTarget(root)
        val updates = updater(target)
        updates.start(release(body))
        settled(updates) { it is UpdateProgress.Restarting }
        val deadline = System.currentTimeMillis() + 10_000
        while (target.handOffs == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, target.handOffs)
        assertContentEquals(body, File(root, "p.zip").readBytes())
    }

    @Test fun anInstallerPlatformWaitsReadySoACancelledInstallCanBeReopened() = withFolder { root ->
        val target = FakeTarget(root, UpdateInstall.SYSTEM_INSTALLER)
        val second = CompletableDeferred<Unit>()
        val updates = updater(target)
        target.handOff = { if (target.handOffs == 2) second.complete(Unit) }
        updates.start(release(body))
        val deadline = System.currentTimeMillis() + 10_000
        while (target.handOffs == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(UpdateProgress.Ready("v1.4.0"), updates.progress.value)
        updates.finish()
        runBlocking { withTimeout(20_000) { second.await() } }
        assertEquals(UpdateProgress.Ready("v1.4.0"), updates.progress.value)
    }

    @Test fun openTradesRefuseTheStartAndHoldAFinishedDownload() = withFolder { root ->
        val refused = FakeTarget(root)
        val before = updater(refused, blocked = { UpdateBlock.OPEN_TRADES }, open = { fail("Nothing is downloaded") })
        before.start(release(body))
        assertEquals(UpdateProgress.Failed("UPDATE_TRADING_ACTIVE", "v1.4.0"), before.progress.value)

        // A position opened during the download: nothing happens until the user asks with the account flat again.
        var checks = 0
        var block: UpdateBlock? = null
        val target = FakeTarget(root)
        val updates = updater(target, blocked = { if (++checks > 1) block else null })
        block = UpdateBlock.OPEN_TRADES
        updates.start(release(body))
        settled(updates) { it is UpdateProgress.Ready }
        Thread.sleep(50)
        assertEquals(0, target.handOffs)
        updates.finish()
        assertIs<UpdateProgress.Ready>(updates.progress.value)
        assertEquals(0, target.handOffs)
        block = null
        updates.finish()
        settled(updates) { it is UpdateProgress.Restarting }
        val deadline = System.currentTimeMillis() + 10_000
        while (target.handOffs == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, target.handOffs)
    }

    @Test fun aBadDownloadCleansUpAndReports() = withFolder { root ->
        val target = FakeTarget(root)
        val corrupt = updater(target)
        val before = target.clears
        corrupt.start(release(body).copy(sha256 = "0".repeat(64)))
        assertEquals(UpdateProgress.Failed("UPDATE_CORRUPT", "v1.4.0"), settled(corrupt) { it is UpdateProgress.Failed })
        assertTrue(target.clears > before)
        val lost = updater(target, open = { throw IOException("reset") })
        lost.start(release(body))
        assertEquals(UpdateProgress.Failed("UPDATE_DOWNLOAD", "v1.4.0"), settled(lost) { it is UpdateProgress.Failed })
    }

    @Test fun aPlatformRefusalAndAFailedHandOffAreReported() = withFolder { root ->
        val rejected = updater(FakeTarget(root) { throw UpdateFailure("UPDATE_SIGNATURE", "other key") })
        rejected.start(release(body))
        assertEquals(UpdateProgress.Failed("UPDATE_SIGNATURE", "v1.4.0"), settled(rejected) { it is UpdateProgress.Failed })
        val stuck = FakeTarget(root).also { it.handOff = { throw UpdateFailure("UPDATE_HANDOFF", "no helper") } }
        val updates = updater(stuck)
        updates.start(release(body))
        assertEquals(UpdateProgress.Failed("UPDATE_HANDOFF", "v1.4.0"), settled(updates) { it is UpdateProgress.Failed })
    }

    @Test fun cancellingADownloadAllowsAnotherAttempt() = withFolder { root ->
        val target = FakeTarget(root)
        val reading = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val slow = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reading.complete(Unit); runBlocking { proceed.await() }; return 1 }
        }
        val updates = updater(target, open = { slow })
        updates.start(release(body))
        runBlocking { withTimeout(20_000) { reading.await() } }
        updates.cancel()
        proceed.complete(Unit)
        assertEquals(UpdateProgress.Idle, updates.progress.value)
    }

    @Test fun onlyGitHubAddressesAreFollowed() {
        assertTrue(ReleaseTransfer.allowedAddress("https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/1"))
        assertTrue(ReleaseTransfer.allowedAddress("https://github.com/Mind2Money/UniTrading/releases/download/v1.4.0/a.zip"))
        assertTrue(ReleaseTransfer.allowedAddress("https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=2"))
        assertFalse(ReleaseTransfer.allowedAddress("http://github.com/a.zip"))
        assertFalse(ReleaseTransfer.allowedAddress("https://github.com.example.com/a.zip"))
        assertFalse(ReleaseTransfer.allowedAddress("https://githubusercontent.com.example.com/a.zip"))
        assertFalse(ReleaseTransfer.allowedAddress("https://evil.test/a.zip"))
        assertFalse(ReleaseTransfer.allowedAddress("https://user:key@github.com/a.zip"))
        assertFalse(ReleaseTransfer.allowedAddress("not a url"))
    }

    @Test fun theNextAddressIsTriedWhenOneFails() {
        val tried = ArrayList<String>()
        val release = release(body).copy(urls = listOf("first", "second", "third"))
        val stream = ReleaseTransfer.open(release) { url ->
            tried += url
            if (url == "first") throw UpdateFailure("UPDATE_DOWNLOAD", "504")
            if (url == "second") throw IOException("reset")
            ByteArrayInputStream(body)
        }
        assertContentEquals(body, stream.readBytes())
        assertEquals(listOf("first", "second", "third"), tried)
        assertEquals("UPDATE_DOWNLOAD", assertFailsWith<UpdateFailure> { ReleaseTransfer.open(release) { throw IOException("down") } }.code)
    }

    @Test fun aDownloadMustMatchThePublishedFileExactly() = withFolder { root ->
        val file = File(root, "p.zip")
        val seen = ArrayList<Long>()
        ReleaseTransfer.download(ByteArrayInputStream(body), file, release(body), { true }, seen::add)
        assertContentEquals(body, file.readBytes())
        assertEquals(body.size.toLong(), seen.last())
        assertEquals("UPDATE_DOWNLOAD", assertFailsWith<UpdateFailure> { ReleaseTransfer.download(ByteArrayInputStream(body.copyOf(3)), file, release(body), { true }) {} }.code)
        assertEquals("UPDATE_CORRUPT", assertFailsWith<UpdateFailure> { ReleaseTransfer.download(ByteArrayInputStream(body + 1), file, release(body), { true }) {} }.code)
        assertEquals("UPDATE_CORRUPT", assertFailsWith<UpdateFailure> { ReleaseTransfer.download(ByteArrayInputStream("Package".toByteArray()), file, release(body), { true }) {} }.code)
    }
}
