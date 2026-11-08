package com.unitrading

import com.unitrading.app.ReleasePackage
import com.unitrading.app.ReleaseUpdater
import com.unitrading.app.UpdateBlock
import com.unitrading.app.UpdateFailure
import com.unitrading.app.UpdateProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** The Windows update on plain folders: no network, no launcher and nothing started. */
class SelfUpdateTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun release(bytes: ByteArray) = ReleasePackage("v1.4.0", "UniTrading-1.4.0-windows-x64.zip", listOf("https://api.github.com/repos/Mind2Money/UniTrading/releases/assets/1"), bytes.size.toLong(), sha256(bytes))
    private val program = zip("UniTrading/UniTrading.exe" to "new launcher", "UniTrading/app/x.jar" to "new jar", "UniTrading/runtime/lib" to "new runtime")
    private fun installed(root: File): UpdateLayout {
        val target = File(root, "UniTrading")
        File(target, "app").mkdirs(); File(target, "runtime").mkdirs(); File(target, "notes").mkdirs()
        File(target, "UniTrading.exe").writeText("old launcher"); File(target, "app/x.jar").writeText("old jar"); File(target, "app/old.jar").writeText("old only")
        File(target, "runtime/lib").writeText("old runtime"); File(target, "notes/mine.txt").writeText("user file")
        return UpdateLayout.locate(File(target, "UniTrading.exe").path)!!
    }
    private fun unpacked(root: File): UpdateLayout = installed(root).also { layout ->
        val archive = File(root, "package.zip").also { it.writeBytes(program) }
        Files.createDirectories(layout.staging.toPath())
        SelfUpdate.unpack(archive, layout)
    }
    private fun withFolder(body: (File) -> Unit) { val root = Files.createTempDirectory("unitrading-update-test-").toFile(); try { body(root) } finally { root.deleteRecursively() } }

    @Test fun onlyThePackagedLauncherHasALayout() {
        val layout = UpdateLayout.locate(File(File("Apps", "UniTrading"), "UniTrading.exe").path)
        assertNotNull(layout)
        assertEquals("update-staging", layout.staging.name)
        assertEquals(layout.target, layout.staging.parentFile)
        assertTrue(layout.helperLauncher.path.replace('\\', '/').endsWith("update-staging/helper/UniTrading/UniTrading.exe"))
        assertNull(UpdateLayout.locate(null), "A source run has no launcher to replace")
        assertNull(UpdateLayout.locate("/Applications/UniTrading.app/Contents/MacOS/UniTrading"))
    }

    @Test fun onlyAHelperCopyWithAJobTakesTheInstallPath() = withFolder { root ->
        val layout = installed(root)
        layout.helper.mkdirs()
        assertNull(SelfUpdate.pendingJob(layout.helperLauncher), "No job yet")
        layout.job.writeText("{}")
        assertEquals(layout.job.canonicalFile, SelfUpdate.pendingJob(layout.helperLauncher)?.canonicalFile)
        assertNull(SelfUpdate.pendingJob(layout.program), "The installed program never reads a job")
        assertNull(SelfUpdate.pendingJob(null))
    }

    @Test fun aPackageCannotWriteOutsideItsFolder() = withFolder { root ->
        val into = File(root, "payload")
        val archive = File(root, "bad.zip")
        for (name in listOf("UniTrading/../../escaped", "UniTrading/..\\..\\escaped", "other/file")) {
            archive.writeBytes(zip(name to "x"))
            assertEquals("UPDATE_PACKAGE", assertFailsWith<UpdateFailure> { SelfUpdate.unpackZip(archive, into) }.code, name)
        }
        assertFalse(File(root, "escaped").exists())
        archive.writeBytes(zip("UniTrading/big" to "x".repeat(100)))
        assertEquals("UPDATE_PACKAGE", assertFailsWith<UpdateFailure> { SelfUpdate.unpackZip(archive, into, limit = 10) }.code)
    }

    @Test fun anIncompletePackageIsRefused() = withFolder { root ->
        val layout = installed(root)
        val archive = File(root, "p.zip").also { it.writeBytes(zip("UniTrading/app/x.jar" to "x")) }
        Files.createDirectories(layout.staging.toPath())
        assertEquals("UPDATE_PACKAGE", assertFailsWith<UpdateFailure> { SelfUpdate.unpack(archive, layout) }.code)
    }

    @Test fun theProgramIsReplacedAndOtherFilesStay() = withFolder { root ->
        val layout = unpacked(root)
        assertEquals("new launcher", layout.helperLauncher.readText(), "The helper is its own copy")
        SelfUpdate.replace(layout)
        assertEquals("new launcher", File(layout.target, "UniTrading.exe").readText())
        assertEquals("new jar", File(layout.target, "app/x.jar").readText())
        assertFalse(File(layout.target, "app/old.jar").exists())
        assertEquals("user file", File(layout.target, "notes/mine.txt").readText())
        assertEquals("old jar", File(layout.previous, "app/x.jar").readText())
    }

    @Test fun aFailedReplacePutsEverythingBack() = withFolder { root ->
        val layout = unpacked(root)
        var moves = 0
        assertFailsWith<IOException> {
            SelfUpdate.replace(layout, moveWaitMillis = 0) { from, to ->
                if (++moves == 5) throw IOException("in use")
                Files.move(from.toPath(), to.toPath())
            }
        }
        assertEquals("old launcher", File(layout.target, "UniTrading.exe").readText())
        assertEquals("old jar", File(layout.target, "app/x.jar").readText())
        assertEquals("old runtime", File(layout.target, "runtime/lib").readText())
        assertEquals("new jar", File(layout.payload, "app/x.jar").readText(), "The payload is whole again for a later attempt")
    }

    @Test fun aFileHeldForAMomentIsRetried() = withFolder { root ->
        val layout = unpacked(root)
        var held = 3
        SelfUpdate.replace(layout, moveWaitMillis = 10_000) { from, to ->
            if (held-- > 0) throw IOException("held")
            Files.move(from.toPath(), to.toPath())
        }
        assertEquals("new jar", File(layout.target, "app/x.jar").readText())
    }

    @Test fun aHeldFolderLeavesTheLauncherInPlace() = withFolder { root ->
        val layout = unpacked(root)
        assertFailsWith<IOException> {
            SelfUpdate.replace(layout, moveWaitMillis = 0) { from, _ ->
                assertTrue(File(layout.target, "UniTrading.exe").exists() || from.name == "UniTrading.exe", "The launcher moves last")
                throw IOException("held")
            }
        }
        assertEquals("old launcher", File(layout.target, "UniTrading.exe").readText())
    }

    private fun exitedPid(): Long {
        val java = ProcessHandle.current().info().command().get()
        return ProcessBuilder(java, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().also { it.waitFor() }.pid()
    }

    @Test fun theHelperInstallsOnceTheOldVersionHasExited() = withFolder { root ->
        val layout = unpacked(root)
        val opened = ArrayList<File>()
        SelfUpdate.writeJob(layout, "v1.4.0", exitedPid())
        assertEquals(0, SelfUpdate.install(layout.job) { opened += it })
        assertTrue(layout.ready.exists())
        assertEquals(listOf(layout.program), opened)
        assertEquals("new jar", File(layout.target, "app/x.jar").readText())
        assertEquals(UpdateProgress.Installed("v1.4.0"), SelfUpdate.lastOutcome(layout))
    }

    @Test fun aHelperNeverReplacesARunningVersionOrOpensASecondOne() = withFolder { root ->
        val layout = unpacked(root)
        val opened = ArrayList<File>()
        SelfUpdate.writeJob(layout, "v1.4.0", ProcessHandle.current().pid())
        assertEquals(3, SelfUpdate.install(layout.job, exitWaitSeconds = 0) { opened += it })
        assertTrue(opened.isEmpty())
        assertEquals("old jar", File(layout.target, "app/x.jar").readText())
        assertEquals(UpdateProgress.Failed("UPDATE_STILL_RUNNING", "v1.4.0"), SelfUpdate.lastOutcome(layout))
    }

    @Test fun aCancelledJobLeavesEverythingAlone() = withFolder { root ->
        val layout = unpacked(root)
        SelfUpdate.cancelJob(layout)
        assertEquals(0, SelfUpdate.install(layout.job) { fail("A cancelled job opens nothing") })
        assertFalse(layout.ready.exists())
        assertEquals("old jar", File(layout.target, "app/x.jar").readText())
    }

    private fun target(layout: UpdateLayout, verified: (File, String) -> Unit = { _, _ -> }, started: (File) -> Unit = { layout.ready.createNewFile() }, handOff: Long = 5_000) =
        DesktopUpdateTarget(layout, verify = verified, startHelper = started, pid = 1, handOffMillis = handOff)

    private fun updater(target: DesktopUpdateTarget, bytes: ByteArray, blocked: () -> UpdateBlock? = { null }) =
        ReleaseUpdater("1.3.1", target, blocked, latest = { "" }, open = { ByteArrayInputStream(bytes) })

    private fun settled(updates: ReleaseUpdater, done: (UpdateProgress) -> Boolean) = runBlocking { withTimeout(20_000) { updates.progress.first(done) } }

    @Test fun oneClickRunsThroughToTheShutdownWhenNothingIsOpen() = withFolder { root ->
        val layout = installed(root)
        val exited = CompletableDeferred<Unit>()
        var checked = ""
        var job = ""
        val target = target(layout, verified = { launcher, version -> checked = "${launcher.name} $version" }, started = { job = layout.job.readText(); layout.ready.createNewFile() })
        target.exit = { exited.complete(Unit) }
        val updates = updater(target, program)
        updates.start(release(program))
        runBlocking { withTimeout(20_000) { exited.await() } }
        assertEquals(UpdateProgress.Restarting("v1.4.0"), updates.progress.value)
        assertEquals("UniTrading.exe v1.4.0", checked)
        val handed = Json.parseToJsonElement(job).jsonObject
        assertEquals(1, handed["pid"]!!.jsonPrimitive.int)
        assertEquals(layout.target.path, handed["target"]!!.jsonPrimitive.content)
        assertEquals("old jar", File(layout.target, "app/x.jar").readText(), "The old version never touches the program")
        assertFalse(File(layout.staging, release(program).name).exists(), "The zip is gone once unpacked")
    }

    @Test fun aHelperThatNeverStartsLeavesTheOldVersionRunning() = withFolder { root ->
        val layout = installed(root)
        var exits = 0
        val target = target(layout, started = {}, handOff = 200)
        target.exit = { exits++ }
        val updates = updater(target, program)
        updates.start(release(program))
        assertEquals(UpdateProgress.Failed("UPDATE_HANDOFF", "v1.4.0"), settled(updates) { it is UpdateProgress.Failed })
        assertEquals(0, exits)
        assertTrue(Json.parseToJsonElement(layout.job.readText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean, "A late helper must find the cancelled job")
    }

    @Test fun aPositionOpenedDuringTheDownloadLeavesTheProgramRunning() = withFolder { root ->
        val layout = installed(root)
        var exits = 0
        var checks = 0
        val target = target(layout)
        target.exit = { exits++ }
        val updates = updater(target, program, blocked = { if (++checks > 1) UpdateBlock.OPEN_TRADES else null })
        updates.start(release(program))
        settled(updates) { it is UpdateProgress.Ready }
        assertEquals(0, exits)
        assertFalse(layout.job.exists(), "No helper was started")
    }

    @Test fun aBadPackageCleansUpAndReports() = withFolder { root ->
        val layout = installed(root)
        val wrong = updater(target(layout, verified = { _, _ -> throw UpdateFailure("UPDATE_PACKAGE", "reported 1.0.0") }), program)
        wrong.start(release(program))
        assertEquals(UpdateProgress.Failed("UPDATE_PACKAGE", "v1.4.0"), settled(wrong) { it is UpdateProgress.Failed })
        assertFalse(layout.staging.exists())
        val garbled = "not a zip".toByteArray()
        val unreadable = updater(target(layout), garbled)
        unreadable.start(release(garbled))
        assertEquals(UpdateProgress.Failed("UPDATE_PACKAGE", "v1.4.0"), settled(unreadable) { it is UpdateProgress.Failed })
    }

    @Test fun theNextStartReportsTheResultAndRemovesTheFolder() = withFolder { root ->
        val layout = unpacked(root)
        layout.result.writeText("""{"version":"v1.4.0","ok":true}""")
        val updates = ReleaseUpdater("1.4.0", DesktopUpdateTarget(layout, startHelper = {}, pid = 1), blocked = { null }, latest = { "" })
        assertEquals(UpdateProgress.Installed("v1.4.0"), updates.progress.value)
        val deadline = System.currentTimeMillis() + 10_000
        while (layout.staging.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(layout.staging.exists())
        assertEquals("user file", File(layout.target, "notes/mine.txt").readText())
    }

    @Test fun aReadOnlyOldLauncherDoesNotKeepTheStagingFolder() = withFolder { root ->
        val layout = unpacked(root)
        val launcher = File(layout.previous, "UniTrading.exe")
        launcher.parentFile.mkdirs()
        launcher.writeText("old launcher")
        assertTrue(launcher.setReadOnly())
        assertTrue(SelfUpdate.remove(layout))
        assertFalse(layout.staging.exists())
    }

    @Test fun theDesktopPackageNameMatchesWhatCiPublishes() = withFolder { root ->
        val pattern = DesktopUpdateTarget(installed(root)).assetName("1.4.0")
        assertTrue(pattern.matches("UniTrading-1.4.0-windows-x64.zip"))
        assertFalse(pattern.matches("UniTrading-1.4.0-79768fe.apk"))
        assertFalse(pattern.matches("UniTrading-1x4x0-windows-x64.zip"))
    }
}
