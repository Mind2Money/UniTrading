package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The chart's indicator stack, applied to every instrument and timeframe; saved under `indicators-v1`. */
@Serializable
data class IndicatorSettings(val indicators: List<IndicatorConfig> = emptyList()) {
    fun validate() {
        require(indicators.size <= MAX_INDICATORS) { "最多 $MAX_INDICATORS 个指标" }
        require(indicators.map { it.id }.distinct().size == indicators.size) { "指标 ID 重复" }
        indicators.forEach(IndicatorConfig::validate)
    }
    companion object { const val MAX_INDICATORS = 12 }
}

class IndicatorController(private val store: SnapshotStore) {
    private val mutex = Mutex()
    private val history = ArrayDeque<IndicatorSettings>()
    val canUndo = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(try {
        store.read("indicators-v1")?.let { Json.decodeFromString<IndicatorSettings>(it).also(IndicatorSettings::validate) } ?: IndicatorSettings()
    } catch (_: Exception) {
        error.value = "指标设置无法读取，已清空图表指标。"
        IndicatorSettings()
    })

    suspend fun apply(value: IndicatorSettings): Boolean = mutex.withLock {
        value.validate()
        if (value == settings.value) return@withLock true
        val before = settings.value
        try {
            withContext(Dispatchers.Default) { store.write("indicators-v1", Json.encodeToString(value)) }
            settings.value = value
            history.addLast(before)
            if (history.size > 100) history.removeFirst()
            canUndo.value = true
            error.value = null
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "指标保存失败，请检查存储后重试。"; false }
    }

    suspend fun undo(): Boolean = mutex.withLock {
        val previous = history.lastOrNull() ?: return@withLock false
        try {
            withContext(Dispatchers.Default) { store.write("indicators-v1", Json.encodeToString(previous)) }
            settings.value = previous
            history.removeLast()
            canUndo.value = history.isNotEmpty()
            error.value = null
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "指标恢复失败，请检查存储后重试。"; false }
    }
}
