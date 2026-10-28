package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
    val error = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(try {
        store.read("indicators-v1")?.let { Json.decodeFromString<IndicatorSettings>(it).also(IndicatorSettings::validate) } ?: IndicatorSettings()
    } catch (_: Exception) {
        error.value = "指标设置无法读取，已清空图表指标。"
        IndicatorSettings()
    })

    suspend fun apply(value: IndicatorSettings): Boolean {
        value.validate()
        return try {
            withContext(Dispatchers.Default) { store.write("indicators-v1", Json.encodeToString(value)) }
            settings.value = value
            error.value = null
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "指标保存失败，请检查存储后重试。"; false }
    }
}
