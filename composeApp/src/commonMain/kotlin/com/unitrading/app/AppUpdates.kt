package com.unitrading.app

import com.unitrading.broker.BrokerRegistry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** [code] is what the settings page words for the user; the message only reaches logs and tests. */
class UpdateFailure(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** One platform's file of a release: where it comes from, in order of preference, and what the download must match. */
data class ReleasePackage(val version: String, val name: String, val urls: List<String>, val bytes: Long, val sha256: String)

/** The newest published release. [packageFile] is null when it has no verifiable file for this platform. */
data class AppRelease(val version: String, val pageUrl: String, val packageFile: ReleasePackage?)

sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Current(val latest: String) : UpdateCheck
    /** A build newer than anything published, such as one made from source. */
    data class Ahead(val latest: String) : UpdateCheck
    data class Available(val release: AppRelease) : UpdateCheck
    data class Failed(val code: String) : UpdateCheck
}

sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data class Downloading(val version: String, val received: Long, val total: Long) : UpdateProgress
    data class Preparing(val version: String) : UpdateProgress
    /** Downloaded and checked. Waits here while the last step is held back, and on Android until the installer finishes. */
    data class Ready(val version: String) : UpdateProgress
    data class Restarting(val version: String) : UpdateProgress
    /** The previous run installed this version; reported once after the restart. */
    data class Installed(val version: String) : UpdateProgress
    data class Failed(val code: String, val version: String) : UpdateProgress
}

/** Windows replaces its own files and restarts; on Android the system installer takes over and the user confirms there. */
enum class UpdateInstall { RESTART, SYSTEM_INSTALLER }

interface AppUpdates {
    val currentVersion: String
    val install: UpdateInstall
    val check: StateFlow<UpdateCheck>
    val progress: StateFlow<UpdateProgress>
    /** Reads the public release metadata. Only ever called from an explicit press. */
    fun checkNow()
    /** Downloads, verifies and prepares [release], then installs it unless something now holds it back. */
    fun start(release: ReleasePackage)
    fun cancel()
    /** The last step of a [UpdateProgress.Ready] update. */
    fun finish()
}

enum class UpdateBlock { OPEN_TRADES, ACCOUNT_UNCONFIRMED }

/**
 * Why the program may not be replaced now, or null when nothing stands in the way. Positions and working orders stay on the
 * broker's servers, but a restart leaves the user unable to see or change them; a state that cannot be read counts the same.
 * Local demo accounts hold no real money and do not count.
 */
fun BrokerRegistry.updateBlock(): UpdateBlock? {
    var unconfirmed = false
    for (plugin in plugins) {
        if (!plugin.descriptor.environment.remote) continue
        if (!plugin.market.value.tradingReady) unconfirmed = true
        for (account in plugin.accounts) {
            val state = try { plugin.openSession(account.id).state.value } catch (_: Exception) { unconfirmed = true; continue }
            if (state.positions.isNotEmpty() || state.orders.isNotEmpty()) return UpdateBlock.OPEN_TRADES
        }
    }
    return if (unconfirmed) UpdateBlock.ACCOUNT_UNCONFIRMED else null
}

private const val Repository = "Mind2Money/UniTrading"
internal const val LatestReleaseUrl = "https://api.github.com/repos/$Repository/releases/latest"
private const val ReleasePages = "https://github.com/$Repository/releases"
private const val AssetApi = "https://api.github.com/repos/$Repository/releases/assets/"
private const val MaxPackageBytes = 2L shl 30
private val PlainVersion = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
private val Sha256Digest = Regex("^sha256:([0-9a-f]{64})$")

private fun versionParts(text: String): List<Int>? =
    PlainVersion.matchEntire(text)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

/** Negative when [left] is older than [right]. Throws for anything but `X.Y.Z`, with or without a leading `v`. */
internal fun compareVersions(left: String, right: String): Int {
    val a = versionParts(left) ?: throw invalid("Unreadable version $left")
    val b = versionParts(right) ?: throw invalid("Unreadable version $right")
    return (0..2).firstNotNullOfOrNull { i -> (a[i] - b[i]).takeIf { it != 0 } } ?: 0
}

private fun invalid(message: String) = UpdateFailure("UPDATE_INVALID", message)
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

/**
 * Reads GitHub's answer for the latest release. Every address the app would follow is rebuilt from the tag and compared, so
 * nothing outside this repository's releases is accepted from the response. GitHub computes the digest when the file is
 * uploaded; it catches a damaged download and proves nothing about who published the release.
 */
internal fun parseLatestRelease(text: String, assetName: (version: String) -> Regex): AppRelease {
    val root = try { Json.parseToJsonElement(text).jsonObject } catch (cause: Exception) { throw UpdateFailure("UPDATE_INVALID", "Unreadable release data", cause) }
    val tag = root.text("tag_name")?.takeIf { it.startsWith("v") && versionParts(it) != null } ?: throw invalid("Unexpected tag")
    if (root.flag("draft") != false || root.flag("prerelease") != false) throw invalid("Not a published stable release")
    val page = "$ReleasePages/tag/$tag"
    if (root.text("html_url") != page) throw invalid("Unexpected release page")
    val pattern = assetName(tag.removePrefix("v"))
    val packageFile = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { asset -> asset.text("name")?.let(pattern::matches) == true }
        .singleOrNull()?.let { asset -> releasePackage(asset, tag) }
    return AppRelease(tag, page, packageFile)
}

private fun releasePackage(asset: JsonObject, tag: String): ReleasePackage? {
    val name = asset.text("name") ?: return null
    val bytes = (asset["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..MaxPackageBytes } ?: return null
    val sha256 = asset.text("digest")?.let(Sha256Digest::matchEntire)?.groupValues?.get(1) ?: return null
    val api = asset.text("url")?.takeIf { url -> url.startsWith(AssetApi) && url.removePrefix(AssetApi).let { id -> id.isNotEmpty() && id.all(Char::isDigit) } } ?: return null
    val direct = asset.text("browser_download_url")?.takeIf { it == "$ReleasePages/download/$tag/$name" } ?: return null
    if (asset.text("state") != "uploaded") return null
    // The API route first: on 2026-11-08 the release-download address kept answering 504 while the API's redirect worked.
    return ReleasePackage(tag, name, listOf(api, direct), bytes, sha256)
}
