package com.unitrading.app

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Chart state saved per device: starred intervals shown inline on the chart toolbar, user-added custom intervals, and
 * whether the legend (with its indicator list) is folded. [legendCollapsed] stays null until the user first folds or
 * unfolds it, so each layout keeps its own default until then.
 */
@Serializable
data class ChartPreferences(
    val favoriteMinutes: List<Int> = Timeframe.defaultFavorites.map { it.minutes },
    val customMinutes: List<Int> = emptyList(),
    val legendCollapsed: Boolean? = null,
    val indicatorPaneFractions: Map<String, Float> = emptyMap(),
) {
    fun validate() {
        require((favoriteMinutes + customMinutes).all { it in 1..Timeframe.MAX_MINUTES }) { "周期超出范围" }
        require(favoriteMinutes.distinct() == favoriteMinutes && customMinutes.distinct() == customMinutes) { "周期重复" }
        require(favoriteMinutes.size <= MAX_FAVORITES) { "收藏周期最多 $MAX_FAVORITES 个" }
        require(indicatorPaneFractions.all { (id, fraction) -> id.isNotBlank() && fraction.isFinite() && fraction > 0f && fraction <= .75f }) { "指标窗口比例超出范围" }
    }
    val favorites: List<Timeframe> get() = favoriteMinutes.map(::Timeframe)
    val custom: List<Timeframe> get() = customMinutes.map(::Timeframe)

    companion object { const val MAX_FAVORITES = 12 }
}

class ChartPreferencesController(private val store: SnapshotStore) {
    val error = MutableStateFlow<String?>(null)
    val preferences = MutableStateFlow(try {
        store.read("chart-v1")?.let { Json.decodeFromString<ChartPreferences>(it).also(ChartPreferences::validate) } ?: ChartPreferences()
    } catch (_: Exception) {
        error.value = "图表设置无法读取，已使用默认周期和图例。"
        ChartPreferences()
    })

    suspend fun apply(value: ChartPreferences): Boolean {
        value.validate()
        return try {
            withContext(Dispatchers.Default) { store.write("chart-v1", Json.encodeToString(value)) }
            preferences.value = value
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "图表设置保存失败，请检查存储后重试。"; false }
    }

    suspend fun toggleFavorite(timeframe: Timeframe): Boolean {
        val current = preferences.value
        val next = if (timeframe.minutes in current.favoriteMinutes) current.favoriteMinutes - timeframe.minutes else current.favoriteMinutes + timeframe.minutes
        if (next.size > ChartPreferences.MAX_FAVORITES) { error.value = "收藏周期最多 ${ChartPreferences.MAX_FAVORITES} 个"; return false }
        return apply(current.copy(favoriteMinutes = next))
    }

    suspend fun addCustom(timeframe: Timeframe): Boolean {
        val current = preferences.value
        if (timeframe.minutes in current.customMinutes || timeframe in Timeframe.presets) return true
        return apply(current.copy(customMinutes = current.customMinutes + timeframe.minutes))
    }

    suspend fun setLegendCollapsed(collapsed: Boolean): Boolean =
        preferences.value.legendCollapsed == collapsed || apply(preferences.value.copy(legendCollapsed = collapsed))

    suspend fun setIndicatorPaneFractions(fractions: Map<String, Float>): Boolean =
        preferences.value.indicatorPaneFractions == fractions || apply(preferences.value.copy(indicatorPaneFractions = fractions))
}
