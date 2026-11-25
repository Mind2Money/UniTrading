package com.unitrading.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemePreset(val label: String) {
    DARK("深色"), LIGHT("浅色"), SYSTEM("跟随系统"),
    /** TradingView 黑色主题配色：纯黑页面、#0F0F0F 图表、#2962FF 强调、#089981 / #F23645 K 线。 */
    TRADINGV("TradingV");

    /** Whether the preset renders dark controls; [systemDark] resolves [SYSTEM]. */
    fun isDark(systemDark: Boolean): Boolean = when (this) { DARK, TRADINGV -> true; LIGHT -> false; SYSTEM -> systemDark }
}

enum class ThemeColor(val label: String) {
    PAGE("页面背景"), SURFACE("面板背景"), CHART("图表背景"), GRID("图表网格"),
    RISE("上涨颜色"), FALL("下跌颜色"), UP("上涨K线主体"), DOWN("下跌K线主体"),
    UP_BORDER("上涨K线边框"), DOWN_BORDER("下跌K线边框"), UP_WICK("上涨K线影线"), DOWN_WICK("下跌K线影线"), PROFIT("盈利数字"), LOSS("亏损数字"),
    ACCENT("主题强调色"), TEXT("主要文字"), MUTED("次要文字"), STOP("止损线"), TARGET("止盈线"),
}

@Serializable
data class ThemeSettings(val preset: ThemePreset = ThemePreset.DARK, val colors: Map<String, String> = emptyMap(),
    val independentCandleParts: Boolean = false) {
    fun validate() {
        require(colors.keys.all { name -> ThemeColor.entries.any { it.name == name } })
        require(colors.values.all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
    }

    /** Older themes used the candle body as the implicit border and wick; freeze those before separating the controls. */
    fun withIndependentCandleParts(): ThemeSettings {
        if (independentCandleParts) return this
        val next = colors.toMutableMap()
        for ((body, parts) in listOf("UP" to listOf("UP_BORDER", "UP_WICK"), "DOWN" to listOf("DOWN_BORDER", "DOWN_WICK"))) {
            val old = colors[body] ?: continue
            parts.forEach { part -> if (part !in next) next[part] = old }
        }
        colors["PROFIT"]?.let { if ("RISE" !in next) next["RISE"] = it }
        colors["LOSS"]?.let { if ("FALL" !in next) next["FALL"] = it }
        return copy(colors = next, independentCandleParts = true)
    }
}

class ThemeController(private val store: SnapshotStore) {
    val error = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(try {
        (store.read("theme-v1")?.let { Json.decodeFromString<ThemeSettings>(it).also(ThemeSettings::validate) } ?: ThemeSettings()).withIndependentCandleParts()
    } catch (_: Exception) {
        error.value = "主题设置无法读取，已使用默认主题。"
        ThemeSettings().withIndependentCandleParts()
    })

    suspend fun apply(value: ThemeSettings): Boolean {
        value.validate()
        val normalized = value.withIndependentCandleParts()
        return try {
            withContext(Dispatchers.Default) { store.write("theme-v1", Json.encodeToString(normalized)) }
            settings.value = normalized
            error.value = null
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "主题保存失败，请检查存储后重试。"; false }
    }
}
