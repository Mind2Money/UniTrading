package com.unitrading.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** The network and file half of an update, the same on the desktop and on Android. */
object ReleaseTransfer {
    private const val UserAgent = "UniTrading-update"
    private const val MaxMetadataBytes = 1 shl 20

    /** Release files redirect from GitHub to its own download hosts, and nowhere else. */
    fun allowedAddress(url: String): Boolean = runCatching {
        val address = URI(url)
        val host = address.host.orEmpty().lowercase()
        address.scheme == "https" && address.userInfo == null && (host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)

    private fun connect(url: String, accept: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        setRequestProperty("User-Agent", UserAgent)
        setRequestProperty("Accept", accept)
    }

    /** GitHub's JSON for the latest release, read anonymously. */
    fun latestRelease(): String {
        val connection = try { connect(LatestReleaseUrl, "application/vnd.github+json") } catch (cause: IOException) { throw UpdateFailure("UPDATE_UNAVAILABLE", "Cannot reach GitHub", cause) }
        try {
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = try { connection.responseCode } catch (cause: IOException) { throw UpdateFailure("UPDATE_UNAVAILABLE", "Cannot reach GitHub", cause) }
            when {
                status == 429 || status == 403 && (connection.getHeaderField("Retry-After") != null || connection.getHeaderField("X-RateLimit-Remaining") == "0") ->
                    throw UpdateFailure("UPDATE_RATE_LIMITED", "GitHub answered $status")
                status == 404 -> throw UpdateFailure("UPDATE_NOT_PUBLIC", "GitHub lists no release")
                status != 200 -> throw UpdateFailure("UPDATE_UNAVAILABLE", "GitHub answered $status")
            }
            return try {
                connection.inputStream.use { stream ->
                    // InputStream.readNBytes only exists from Android 13.
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(1 shl 13)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                        if (bytes.size() > MaxMetadataBytes) throw UpdateFailure("UPDATE_INVALID", "The release data is too large")
                    }
                    bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
                }
            } catch (cause: IOException) { throw UpdateFailure("UPDATE_UNAVAILABLE", "The release data did not arrive", cause) }
            catch (cause: CharacterCodingException) { throw UpdateFailure("UPDATE_INVALID", "The release data is not text", cause) }
        } finally { connection.disconnect() }
    }

    /** No credentials, no cookies, and each redirect checked before it is followed. */
    private fun openAddress(url: String): InputStream {
        var address = url
        repeat(6) {
            if (!allowedAddress(address)) throw UpdateFailure("UPDATE_DOWNLOAD", "Download address refused")
            val connection = connect(address, "application/octet-stream")
            val status = connection.responseCode
            if (status == 200) return connection.inputStream
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            if (status !in 300..399 || next == null) throw UpdateFailure("UPDATE_DOWNLOAD", "Download answered $status")
            address = URI(address).resolve(next).toString()
        }
        throw UpdateFailure("UPDATE_DOWNLOAD", "Too many download redirects")
    }

    /** The first of the package's addresses that answers. */
    fun open(release: ReleasePackage, attempt: (String) -> InputStream = ::openAddress): InputStream {
        var failure: Exception? = null
        for (url in release.urls) {
            try { return attempt(url) }
            catch (problem: UpdateFailure) { failure = problem }
            catch (problem: IOException) { failure = problem }
        }
        throw UpdateFailure("UPDATE_DOWNLOAD", "No download address answered", failure)
    }

    /** Stores the download; a wrong size or SHA-256 leaves nothing usable behind. */
    fun download(source: InputStream, file: File, release: ReleasePackage, alive: () -> Boolean, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        source.use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (!alive()) throw CancellationException("Update cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                received += read
                if (received > release.bytes) throw UpdateFailure("UPDATE_CORRUPT", "The download exceeds the published size")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                progress(received)
            }
        } }
        if (received != release.bytes) throw UpdateFailure("UPDATE_DOWNLOAD", "The download ended at $received of ${release.bytes} bytes")
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) throw UpdateFailure("UPDATE_CORRUPT", "The download does not match the published SHA-256")
    }
}

/** What differs per platform: which file of a release is ours, where it goes and how it gets installed. */
interface UpdateTarget {
    val install: UpdateInstall
    /** Matches this platform's file name among the assets of the release of [version]. */
    fun assetName(version: String): Regex
    /** An empty location for the download, ready to be written. */
    fun archive(release: ReleasePackage): File
    /** The platform's own checks of the verified download; throws [UpdateFailure]. */
    fun prepare(archive: File, release: ReleasePackage)
    /** True once nothing is left behind by an earlier attempt. */
    fun clear(): Boolean
    /** Hands over to the installer. On a [UpdateInstall.RESTART] platform this ends with the application exiting. */
    suspend fun handOff(release: ReleasePackage)
    /** What the previous run's update ended as. */
    fun lastOutcome(): UpdateProgress
}

/** State of one update, kept here so it survives leaving the settings page. */
class ReleaseUpdater(
    override val currentVersion: String,
    private val target: UpdateTarget,
    /** Consulted before the download and again before the last step: a position opened meanwhile holds the update. */
    private val blocked: () -> UpdateBlock?,
    private val latest: () -> String = ReleaseTransfer::latestRelease,
    private val open: (ReleasePackage) -> InputStream = ReleaseTransfer::open,
) : AppUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkState = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    private val state = MutableStateFlow(target.lastOutcome())
    private var work: Job? = null
    private var prepared: ReleasePackage? = null
    override val install get() = target.install
    override val check = checkState.asStateFlow()
    override val progress = state.asStateFlow()

    // The update helper may still be on its way out when the new version starts.
    init { work = scope.launch { repeat(15) { if (runCatching { target.clear() }.getOrDefault(false)) return@launch; delay(2000) } } }

    override fun checkNow() {
        if (checkState.value == UpdateCheck.Checking) return
        checkState.value = UpdateCheck.Checking
        scope.launch {
            checkState.value = try {
                val release = parseLatestRelease(latest(), target::assetName)
                val order = compareVersions(release.version, currentVersion)
                if (order > 0) UpdateCheck.Available(release) else if (order < 0) UpdateCheck.Ahead(release.version) else UpdateCheck.Current(release.version)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: UpdateFailure) { UpdateCheck.Failed(failure.code) }
            catch (_: Exception) { UpdateCheck.Failed("UPDATE_UNAVAILABLE") }
        }
    }

    override fun start(release: ReleasePackage) {
        val current = state.value
        if (current is UpdateProgress.Downloading || current is UpdateProgress.Preparing || current is UpdateProgress.Restarting) return
        if (blocked() != null) { state.value = UpdateProgress.Failed("UPDATE_TRADING_ACTIVE", release.version); return }
        val previous = work
        prepared = null
        state.value = UpdateProgress.Downloading(release.version, 0, release.bytes)
        work = scope.launch {
            previous?.cancelAndJoin()
            try {
                prepare(release)
                prepared = release
                state.value = UpdateProgress.Ready(release.version)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { target.clear() } }
                state.value = UpdateProgress.Idle
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) { runCatching { target.clear() } }
                state.value = UpdateProgress.Failed((failure as? UpdateFailure)?.code ?: "UPDATE_DOWNLOAD", release.version)
                return@launch
            }
            handOff(release)
        }
    }

    private suspend fun prepare(release: ReleasePackage) {
        if (!target.clear()) throw UpdateFailure("UPDATE_FILES_BUSY", "An earlier update is still in use")
        val archive = target.archive(release)
        val job = currentCoroutineContext().job
        var percent = -1L
        ReleaseTransfer.download(open(release), archive, release, { job.isActive }) { received ->
            val now = received * 100 / release.bytes
            if (now != percent) { percent = now; state.value = UpdateProgress.Downloading(release.version, received, release.bytes) }
        }
        state.value = UpdateProgress.Preparing(release.version)
        target.prepare(archive, release)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun handOff(release: ReleasePackage) {
        if (blocked() != null) { state.value = UpdateProgress.Ready(release.version); return }
        if (target.install == UpdateInstall.RESTART) state.value = UpdateProgress.Restarting(release.version)
        try { target.handOff(release) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.value = UpdateProgress.Failed((failure as? UpdateFailure)?.code ?: "UPDATE_HANDOFF", release.version) }
    }

    override fun cancel() {
        if (state.value !is UpdateProgress.Downloading) return
        work?.cancel()
        state.value = UpdateProgress.Idle
    }

    override fun finish() {
        val ready = state.value as? UpdateProgress.Ready ?: return
        val release = prepared ?: return
        if (blocked() != null) return
        if (target.install == UpdateInstall.RESTART && !state.compareAndSet(ready, UpdateProgress.Restarting(ready.version))) return
        work = scope.launch { handOff(release) }
    }

    /** Stops anything still running; for owners that end before the process does. */
    fun close() = scope.cancel()
}
