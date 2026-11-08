package com.unitrading

import com.unitrading.app.ReleasePackage
import com.unitrading.app.UpdateFailure
import com.unitrading.app.UpdateInstall
import com.unitrading.app.UpdateProgress
import com.unitrading.app.UpdateTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Where one packaged program sits. */
data class UpdateLayout(
    /** The folder whose entries the package replaces; anything else in it stays. */
    val target: File,
    /** On the volume of [target], so the new program goes into place by renames. */
    val staging: File,
    /** The launcher the system opens to start the program. */
    val program: File,
) {
    /** Exactly the entries that go into [target]. */
    val payload get() = File(staging, "payload")
    val helper get() = File(staging, SelfUpdate.HelperFolder)
    val previous get() = File(staging, "previous")
    val job get() = File(helper, SelfUpdate.JobFile)
    val ready get() = File(staging, "update-ready")
    val result get() = File(staging, "update-result.json")
    val helperLauncher get() = File(helper, "${SelfUpdate.ProgramName}/${SelfUpdate.ProgramName}.exe")

    companion object {
        /** Only the packaged Windows launcher can be replaced; a source run has no `jpackage.app-path`. */
        fun locate(appPath: String?): UpdateLayout? {
            val launcher = File(appPath ?: return null).absoluteFile
            if (launcher.name != "${SelfUpdate.ProgramName}.exe") return null
            return launcher.parentFile?.let { UpdateLayout(it, File(it, "update-staging"), launcher) }
        }
    }
}

/**
 * A running program cannot replace itself: Windows holds its files open, and the jpackage launcher runs the app in a
 * kill-on-close job, so a child of the app dies with it and only the shell can start something that outlives it. The old
 * version therefore only downloads and unpacks, into two copies of the new program: `payload`, which goes into place, and
 * `helper`, which the system starts. The helper waits for the old version to exit, renames the payload into place, opens it
 * and exits; the new version removes the staging folder at its first start.
 *
 * The old version hands off to every later version, so the job file, its place and the launch rule in `main` stay as they are.
 * The package's SHA-256 comes from the same public repository as the package: it catches a damaged download only.
 */
object SelfUpdate {
    const val ProgramName = "UniTrading"
    const val HelperFolder = "helper"
    const val JobFile = "update-job.json"
    private const val ExitWaitSeconds = 180L
    private const val MoveWaitMillis = 60_000L

    /** The job of this launcher when it is a helper copy; `main` only asks for a launcher started without arguments. */
    fun pendingJob(launcher: File?): File? {
        val holder = generateSequence(launcher?.absoluteFile?.parentFile) { it.parentFile }.take(4).firstOrNull { it.name == HelperFolder } ?: return null
        return File(holder, JobFile).takeIf { it.isFile }
    }

    /** The zip's one root folder falls away, so [into] holds what goes into the program folder. */
    fun unpackZip(archive: File, into: File, limit: Long = 4L shl 30) {
        val root = into.canonicalFile
        val prefix = "$ProgramName/"
        var total = 0L
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val inside = entry.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: throw UpdateFailure("UPDATE_PACKAGE", "Unexpected package entry ${entry.name}")
                if (inside.isEmpty()) continue
                val file = File(root, inside).canonicalFile
                if ('\\' in entry.name || !file.toPath().startsWith(root.toPath())) throw UpdateFailure("UPDATE_PACKAGE", "Unsafe package entry ${entry.name}")
                if (entry.isDirectory) { file.mkdirs(); continue }
                file.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw UpdateFailure("UPDATE_PACKAGE", "The package unpacks beyond its limit")
                        output.write(buffer, 0, read)
                    }
                } }
                if (entry.time > 0) file.setLastModified(entry.time)
            }
        }
    }

    /** Unpacks the package into the two copies of the new program. */
    fun unpack(archive: File, layout: UpdateLayout) {
        unpackZip(archive, layout.payload)
        val complete = File(layout.payload, "$ProgramName.exe").isFile && File(layout.payload, "app").isDirectory && File(layout.payload, "runtime").isDirectory
        if (!complete) throw UpdateFailure("UPDATE_PACKAGE", "The package lacks the program files")
        layout.payload.copyRecursively(File(layout.helper, ProgramName))
    }

    /** The launcher starts here, its bundled runtime works and it is the expected version. */
    fun verifyLauncher(launcher: File, version: String) {
        val answer = File.createTempFile("unitrading-update-", ".txt")
        try {
            val process = try { ProcessBuilder(launcher.path, "--version").redirectErrorStream(true).redirectOutput(answer).start() }
                catch (failure: IOException) { throw UpdateFailure("UPDATE_PACKAGE", "The new version does not start", failure) }
            process.outputStream.close()
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw UpdateFailure("UPDATE_PACKAGE", "The new version did not answer") }
            val output = answer.readText().trim()
            if (process.exitValue() != 0 || output.lines().last().trim() != version.removePrefix("v")) throw UpdateFailure("UPDATE_PACKAGE", "The new version reported ${output.take(120)}")
        } finally { answer.delete() }
    }

    fun writeJob(layout: UpdateLayout, version: String, pid: Long) = layout.job.writeText(buildJsonObject {
        put("version", version); put("pid", pid); put("target", layout.target.path); put("staging", layout.staging.path); put("program", layout.program.path)
    }.toString())

    /** A helper that starts late finds this and leaves; deleting the job instead would let it open as an empty app. */
    fun cancelJob(layout: UpdateLayout) { layout.job.writeText(buildJsonObject { put("cancelled", true) }.toString()) }

    /**
     * The shell opens [file], outside the job of this process. Its own child exits once the shell has it, and must get that
     * far: it dies with this process's job otherwise. The quotes keep a comma in a path from reading as an Explorer switch.
     */
    fun openDetached(file: File) {
        val process = ProcessBuilder(File(System.getenv("WINDIR") ?: "C:\\Windows", "explorer.exe").path, "\"${file.path}\"").start()
        process.waitFor(20, TimeUnit.SECONDS)
    }

    private fun move(from: File, to: File, deadline: Long, attempt: (File, File) -> Unit) {
        while (true) {
            try { attempt(from, to); return }
            // Antivirus or the old process's last handles hold a file for a moment after it exits.
            catch (failure: IOException) { if (System.currentTimeMillis() >= deadline) throw failure; Thread.sleep(250) }
        }
    }

    private fun rename(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }

    /** All of it or none of it: a failure puts back what already moved. */
    fun replace(layout: UpdateLayout, moveWaitMillis: Long = MoveWaitMillis, attempt: (File, File) -> Unit = ::rename) {
        // Folders first: they are what stays held, and until one moves the program is still whole.
        val names = layout.payload.listFiles()?.sortedBy { it.isFile }?.map { it.name }.orEmpty()
        if (names.isEmpty()) throw IOException("The unpacked program is missing")
        val deadline = System.currentTimeMillis() + moveWaitMillis
        val aside = ArrayList<String>()
        val placed = ArrayList<String>()
        layout.previous.mkdirs()
        try {
            for (name in names) if (File(layout.target, name).exists()) { move(File(layout.target, name), File(layout.previous, name), deadline, attempt); aside += name }
            for (name in names) { move(File(layout.payload, name), File(layout.target, name), deadline, attempt); placed += name }
        } catch (failure: IOException) {
            val undo = System.currentTimeMillis() + 30_000
            placed.asReversed().forEach { move(File(layout.target, it), File(layout.payload, it), undo, attempt) }
            aside.asReversed().forEach { move(File(layout.previous, it), File(layout.target, it), undo, attempt) }
            throw failure
        }
    }

    private fun writeResult(layout: UpdateLayout, version: String, code: String?) = runCatching {
        layout.result.writeText(buildJsonObject { put("version", version); put("ok", code == null); code?.let { put("code", it) } }.toString())
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    /** The helper copy's whole run: the only code here that changes the installed program. */
    fun install(jobFile: File, exitWaitSeconds: Long = ExitWaitSeconds, moveWaitMillis: Long = MoveWaitMillis, open: (File) -> Unit = ::openDetached): Int {
        val job = runCatching { Json.parseToJsonElement(jobFile.readText()).jsonObject }.getOrNull() ?: return 2
        if (job.flag("cancelled")) return 0
        val layout = UpdateLayout(File(job.text("target")), File(job.text("staging")), File(job.text("program")))
        val version = job.text("version")
        val pid = (job["pid"] as? JsonPrimitive)?.longOrNull ?: return 2
        runCatching { layout.ready.createNewFile() }.onFailure { return 2 }
        val running = ProcessHandle.of(pid).filter { it.isAlive }
        val exited = !running.isPresent || runCatching { running.get().onExit().get(exitWaitSeconds, TimeUnit.SECONDS) }.isSuccess
        // The old version carries on, so there is nothing to open; a later attempt starts over.
        if (!exited) { writeResult(layout, version, "UPDATE_STILL_RUNNING"); return 3 }
        val failure = runCatching { replace(layout, moveWaitMillis) }.exceptionOrNull()
        writeResult(layout, version, failure?.let { "UPDATE_FILES_BUSY" })
        runCatching { open(layout.program) }
        return if (failure == null) 0 else 1
    }

    fun lastOutcome(layout: UpdateLayout): UpdateProgress {
        val result = runCatching { Json.parseToJsonElement(layout.result.readText()).jsonObject }.getOrNull() ?: return UpdateProgress.Idle
        return if (result.flag("ok")) UpdateProgress.Installed(result.text("version")) else UpdateProgress.Failed(result.text("code"), result.text("version"))
    }

    fun remove(layout: UpdateLayout): Boolean {
        if (!layout.staging.exists()) return true
        // jpackage marks the launcher read-only, and Windows refuses to delete a read-only file.
        layout.staging.walkBottomUp().forEach { it.setWritable(true) }
        return layout.staging.deleteRecursively()
    }
}

/** The old version's side of a Windows update. */
class DesktopUpdateTarget(
    private val layout: UpdateLayout,
    private val unpack: (File, UpdateLayout) -> Unit = SelfUpdate::unpack,
    private val verify: (File, String) -> Unit = SelfUpdate::verifyLauncher,
    private val startHelper: (File) -> Unit = SelfUpdate::openDetached,
    private val pid: Long = ProcessHandle.current().pid(),
    private val handOffMillis: Long = 45_000,
) : UpdateTarget {
    /** The application's deliberate shutdown, set once its window exists. */
    @Volatile var exit: suspend () -> Unit = {}

    override val install = UpdateInstall.RESTART
    override fun assetName(version: String) = Regex("^${SelfUpdate.ProgramName}-${Regex.escape(version)}-windows-x64\\.zip$")
    override fun clear() = SelfUpdate.remove(layout)
    override fun lastOutcome() = SelfUpdate.lastOutcome(layout)

    override fun archive(release: ReleasePackage): File {
        try { Files.createDirectories(layout.staging.toPath()) }
        catch (failure: IOException) { throw UpdateFailure("UPDATE_LOCATION", "The program folder cannot be written", failure) }
        return File(layout.staging, release.name)
    }

    override fun prepare(archive: File, release: ReleasePackage) {
        try { unpack(archive, layout); Files.delete(archive.toPath()) }
        catch (failure: ZipException) { throw UpdateFailure("UPDATE_PACKAGE", "The package cannot be read", failure) }
        catch (failure: IOException) { throw UpdateFailure("UPDATE_LOCATION", "The package cannot be unpacked here", failure) }
        verify(layout.helperLauncher, release.version)
    }

    override suspend fun handOff(release: ReleasePackage) {
        try {
            SelfUpdate.writeJob(layout, release.version, pid)
            startHelper(layout.helperLauncher)
            val deadline = System.currentTimeMillis() + handOffMillis
            while (!layout.ready.exists()) {
                if (System.currentTimeMillis() >= deadline) throw UpdateFailure("UPDATE_HANDOFF", "The update helper did not start")
                delay(100)
            }
        } catch (failure: Exception) {
            // The folder stays: a helper that is still starting must find the cancelled job. The next attempt clears it.
            if (failure !is kotlinx.coroutines.CancellationException) runCatching { SelfUpdate.cancelJob(layout) }
            throw failure
        }
        exit()
    }
}
