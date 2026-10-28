package com.unitrading.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemePreset { DARK, LIGHT, SYSTEM }

enum class ThemeColor(val label: String) {
    PAGE("页面背景"), SURFACE("面板背景"), CHART("图表背景"), GRID("图表网格"),
    UP("上涨 K 线"), DOWN("下跌 K 线"), PROFIT("盈利数字"), LOSS("亏损数字"),
    ACCENT("主题强调色"), TEXT("主要文字"), MUTED("次要文字"), STOP("止损线"), TARGET("止盈线"),
}

@Serializable
data class ThemeSettings(val preset: ThemePreset = ThemePreset.DARK, val colors: Map<String, String> = emptyMap()) {
    fun validate() {
        require(colors.keys.all { name -> ThemeColor.entries.any { it.name == name } })
        require(colors.values.all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
    }
}

class ThemeController(private val store: SnapshotStore) {
    val error = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(try {
        store.read("theme-v1")?.let { Json.decodeFromString<ThemeSettings>(it).also(ThemeSettings::validate) } ?: ThemeSettings()
    } catch (_: Exception) {
        error.value = "主题设置无法读取，已使用默认主题。"
        ThemeSettings()
    })

    suspend fun apply(value: ThemeSettings): Boolean {
        value.validate()
        return try {
            withContext(Dispatchers.Default) { store.write("theme-v1", Json.encodeToString(value)) }
            settings.value = value
            error.value = null
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "主题保存失败，请检查存储后重试。"; false }
    }
}
