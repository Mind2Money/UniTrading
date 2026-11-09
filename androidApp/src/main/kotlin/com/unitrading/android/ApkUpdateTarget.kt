package com.unitrading.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.unitrading.app.ReleasePackage
import com.unitrading.app.UpdateFailure
import com.unitrading.app.UpdateInstall
import com.unitrading.app.UpdateProgress
import com.unitrading.app.UpdateTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android replaces an app only through its installer, after the user confirms there. The first update also asks the user,
 * in a system screen, to allow installs from this app. The system compares the update's signing certificate itself; checking
 * here first turns its unexplained "app not installed" into a reason the settings page can give.
 */
internal class ApkUpdateTarget(private val context: Context) : UpdateTarget {
    private val folder = File(context.cacheDir, "updates")
    private val signatureFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    override val install = UpdateInstall.SYSTEM_INSTALLER
    // CI names the APK after the version and the short commit; `-unsigned` builds never reach a release.
    override fun assetName(version: String) = Regex("^UniTrading-${Regex.escape(version)}-[0-9a-f]{7,40}${Regex.escape(APK_NAME_SUFFIX)}\\.apk$")
    override fun clear() = !folder.exists() || folder.deleteRecursively()
    override fun lastOutcome(): UpdateProgress = UpdateProgress.Idle
    private fun apk(release: ReleasePackage) = File(folder, release.name)

    override fun archive(release: ReleasePackage): File {
        if (!folder.isDirectory && !folder.mkdirs()) throw UpdateFailure("UPDATE_LOCATION", "The cache folder cannot be written")
        return apk(release)
    }

    override fun prepare(archive: File, release: ReleasePackage) {
        val manager = context.packageManager
        @Suppress("DEPRECATION") val incoming = manager.getPackageArchiveInfo(archive.path, signatureFlag) ?: throw UpdateFailure("UPDATE_PACKAGE", "The download is not a readable APK")
        @Suppress("DEPRECATION") val installed = manager.getPackageInfo(context.packageName, signatureFlag)
        if (incoming.packageName != context.packageName) throw UpdateFailure("UPDATE_PACKAGE", "The APK is another application")
        if (incoming.versionName != release.version.removePrefix("v")) throw UpdateFailure("UPDATE_PACKAGE", "The APK is version ${incoming.versionName}")
        if (PackageInfoCompat.getLongVersionCode(incoming) <= PackageInfoCompat.getLongVersionCode(installed)) throw UpdateFailure("UPDATE_PACKAGE", "The APK does not raise the version code")
        val signers = incoming.certificates()
        if (signers.isEmpty() || signers != installed.certificates()) throw UpdateFailure("UPDATE_SIGNATURE", "The APK is signed by another certificate")
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.certificates(): Set<String> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners else signatures).orEmpty().map { it.toCharsString() }.toSet()

    override suspend fun handOff(release: ReleasePackage) = withContext(Dispatchers.Main) {
        val address = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk(release))
        val request = Intent(Intent.ACTION_VIEW).setDataAndType(address, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(request) }
        catch (failure: ActivityNotFoundException) { throw UpdateFailure("UPDATE_INSTALLER", "No installer answers", failure) }
    }
}
