package com.unitrading.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredWorkspaceAccounts(val logins: Map<String, Map<String, String>> = emptyMap()) {
    fun validate() {
        require(logins.all { (login, windows) -> login.isNotBlank() && windows.size <= ChartPreferences.MAX_EXTRA_CHARTS &&
            windows.all { (window, account) -> window.isNotBlank() && window.length <= 16 && account.isNotBlank() && account.length <= 128 }
        }) { "子窗口账户设置无效" }
    }
}

/** Separate storage keeps older versions able to read their existing chart-layout file. */
internal class WorkspaceAccounts(private val store: SnapshotStore) {
    private val mutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    val readable: Boolean
    val selections: MutableStateFlow<Map<String, Map<String, String>>>

    init {
        val loaded = try {
            store.read("workspace-accounts-v1")?.let { Json.decodeFromString<StoredWorkspaceAccounts>(it).also(StoredWorkspaceAccounts::validate) }
                ?: StoredWorkspaceAccounts()
        } catch (_: Exception) {
            error.value = "无法读取子窗口账户设置，请在每个子窗口重新选择子账户；原设置未覆盖。"
            null
        }
        readable = loaded != null
        selections = MutableStateFlow(loaded?.logins.orEmpty())
    }

    suspend fun save(login: String, windows: Map<String, String>): Boolean = mutex.withLock {
        if (!readable) return@withLock false
        val next = StoredWorkspaceAccounts(selections.value + (login to windows)).also(StoredWorkspaceAccounts::validate)
        if (next.logins == selections.value) return@withLock true
        withContext(NonCancellable) {
            try {
                withContext(Dispatchers.Default) { store.write("workspace-accounts-v1", Json.encodeToString(next)) }
                selections.value = next.logins
                error.value = null
                true
            } catch (_: Exception) {
                error.value = "子窗口账户设置保存失败，请检查存储后重试。"
                false
            }
        }
    }
}
