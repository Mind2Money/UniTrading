package com.unitrading

import com.unitrading.app.SnapshotStore

/** Journal the original workspace files while preserving the existing crash-safe autosave behavior. */
internal class DesktopWorkspaceSessionStore(
    private val delegate: SnapshotStore,
    private val delete: (String) -> Unit,
) : SnapshotStore {
    private val originals = mutableMapOf<String, String?>()
    private var frozen = false

    private fun isWorkspace(key: String): Boolean = key == "chart-v1" || key == "desktop-layout-v1" ||
        key == "workspace-accounts-v1" || key == "indicators-v1" || key == "chart-views-v1" ||
        key.startsWith("chart-drawing") || key.matches(Regex("chart-[0-9a-f]+-.*"))

    @Synchronized override fun read(key: String): String? = delegate.read(key)

    @Synchronized override fun write(key: String, value: String) {
        if (isWorkspace(key)) {
            if (frozen) return
            if (key !in originals) originals[key] = delegate.read(key)
        }
        delegate.write(key, value)
    }

    @Synchronized fun hasChanges(): Boolean = originals.any { (key, original) -> delegate.read(key) != original }

    @Synchronized fun resumeWrites() { frozen = false }

    /** Freeze further autosaves before restoring, so delayed background writes cannot reapply discarded edits. */
    @Synchronized fun discard() {
        frozen = true
        try {
            originals.forEach { (key, value) -> if (value == null) delete(key) else delegate.write(key, value) }
        } catch (error: Exception) {
            frozen = false
            throw error
        }
    }
}
