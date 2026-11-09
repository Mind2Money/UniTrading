package com.unitrading.android

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.app.ReleasePackage
import com.unitrading.app.UpdateFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ApkUpdateTargetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = ApkUpdateTarget(context)
    private fun release(version: String, name: String = "UniTrading-${version.removePrefix("v")}-79768fe$APK_NAME_SUFFIX.apk") = ReleasePackage(version, name, listOf("https://api.github.com/x"), 1, "a".repeat(64))

    @Test fun onlyTheSignedApkOfTheReleaseIsPicked() {
        val pattern = target.assetName("1.4.0")
        assertTrue(pattern.matches("UniTrading-1.4.0-79768fe$APK_NAME_SUFFIX.apk"))
        assertFalse(pattern.matches("UniTrading-1.4.0-79768fe-unsigned.apk"))
        assertFalse(pattern.matches("UniTrading-1.4.0-79768fe${if (APK_NAME_SUFFIX.isEmpty()) "-webview" else ""}.apk"))
        assertFalse(pattern.matches("UniTrading-1.4.0-android-mapping.txt"))
        assertFalse(pattern.matches("UniTrading-1.4.0-windows-x64.zip"))
        assertFalse(pattern.matches("UniTrading-1.3.9-79768fe$APK_NAME_SUFFIX.apk"))
    }

    /** Fails on a shrunk build if R8 or resource shrinking removes the provider or its path list. */
    @Test fun theInstallerCanReadTheDownloadThroughTheProvider() {
        val apk = target.archive(release("v1.4.0"))
        try {
            apk.writeBytes(byteArrayOf(1, 2, 3))
            val address = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            assertEquals("content", address.scheme)
            assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(address)!!.use { it.readBytes() })
        } finally { assertTrue(target.clear()) }
    }

    @Test fun anythingButANewerBuildOfThisAppIsRefusedBeforeTheInstaller() {
        val own = File(context.applicationInfo.sourceDir)
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName!!
        val apk = target.archive(release("v$current"))
        try {
            own.copyTo(apk, overwrite = true)
            assertEquals("UPDATE_PACKAGE", assertThrows(UpdateFailure::class.java) { target.prepare(apk, release("v$current")) }.code)
            assertEquals("UPDATE_PACKAGE", assertThrows(UpdateFailure::class.java) { target.prepare(apk, release("v99.0.0")) }.code)
            apk.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals("UPDATE_PACKAGE", assertThrows(UpdateFailure::class.java) { target.prepare(apk, release("v$current")) }.code)
        } finally { assertTrue(target.clear()) }
        assertFalse(apk.exists())
    }
}
