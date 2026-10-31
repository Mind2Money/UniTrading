package com.unitrading.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TradingPreferences(val skipConfirmation: Boolean = false)

/** Device-wide trading interaction preferences, independent of account snapshots. */
class TradingPreferencesController(private val store: SnapshotStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
    private val current = MutableStateFlow(try {
        store.read(KEY)?.let { json.decodeFromString<TradingPreferences>(it) } ?: TradingPreferences()
    } catch (_: Exception) {
        error.value = "交易设置无法读取，已恢复交易确认。"
        TradingPreferences()
    })
    val preferences = current.asStateFlow()

    suspend fun setSkipConfirmation(enabled: Boolean): Boolean = mutex.withLock {
        saving.value = true
        try {
            val next = TradingPreferences(skipConfirmation = enabled)
            withContext(Dispatchers.Default) { store.write(KEY, json.encodeToString(next)) }
            current.value = next
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "交易设置保存失败，开关未变更。"; false }
        finally { saving.value = false }
    }

    companion object { const val KEY = "trading-preferences-v1" }
}
