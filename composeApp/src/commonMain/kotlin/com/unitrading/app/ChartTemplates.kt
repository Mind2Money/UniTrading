package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.PriceAxisSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A local template stores indicator configuration and price-axis settings, never orders or account data. */
@Serializable
data class ChartTemplate(val name: String, val indicators: List<IndicatorConfig>, val axis: PriceAxisSettings) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 48) { "模板名称须为 1–48 个字符" }
        IndicatorSettings(indicators).validate()
    }
}

class ChartTemplateController(private val store: SnapshotStore) {
    private val mutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    val templates = MutableStateFlow(try {
        store.read("chart-templates-v1")?.let { Json.decodeFromString<List<ChartTemplate>>(it).also { list ->
            require(list.size <= 20 && list.map { it.name }.distinct().size == list.size)
            list.forEach(ChartTemplate::validate)
        } } ?: emptyList()
    } catch (_: Exception) { error.value = "图表模板无法读取"; emptyList() })

    suspend fun save(value: ChartTemplate): Boolean = mutex.withLock {
        val clean = value.copy(name = value.name.trim())
        try { clean.validate() } catch (e: IllegalArgumentException) { error.value = e.message; return@withLock false }
        val next = templates.value.filterNot { it.name == clean.name } + clean
        if (next.size > 20) { error.value = "最多保存 20 个模板"; return@withLock false }
        persist(next)
    }
    suspend fun delete(name: String): Boolean = mutex.withLock { persist(templates.value.filterNot { it.name == name }) }
    private suspend fun persist(next: List<ChartTemplate>): Boolean = try {
        withContext(Dispatchers.Default) { store.write("chart-templates-v1", Json.encodeToString(next)) }
        templates.value = next; error.value = null; true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { error.value = "图表模板保存失败"; false }
}
