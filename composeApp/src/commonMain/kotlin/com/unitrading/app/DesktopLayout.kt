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

/** The panels the desktop workspace can fold away. */
enum class DesktopPanel { MARKET, ORDER, TERMINAL }

/** Preferred panel dimensions in dp, and which panels are open. Window constraints affect display, never overwrite these preferences. */
@Serializable
data class DesktopLayoutPreferences(
    val sidebarWidth: Float = 320f,
    val orderWidth: Float = 296f,
    val accountsHeight: Float = 260f,
    val terminalHeight: Float = 220f,
    val marketOpen: Boolean = true,
    val orderOpen: Boolean = true,
    val terminalOpen: Boolean = true,
    val orderFlowFraction: Float = .5f,
) {
    fun validate() {
        require(listOf(sidebarWidth, orderWidth, accountsHeight, terminalHeight).all { it.isFinite() && it in 1f..4000f }) {
            "面板尺寸超出范围"
        }
        require(orderFlowFraction.isFinite() && orderFlowFraction > 0f && orderFlowFraction < 1f) {
            "订单流面板比例超出范围"
        }
    }
    fun withOpen(panel: DesktopPanel, open: Boolean) = when (panel) {
        DesktopPanel.MARKET -> copy(marketOpen = open)
        DesktopPanel.ORDER -> copy(orderOpen = open)
        DesktopPanel.TERMINAL -> copy(terminalOpen = open)
    }
}

class DesktopLayoutController(private val store: SnapshotStore) {
    private val saveMutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow(try {
        store.read("desktop-layout-v1")?.let { Json.decodeFromString<DesktopLayoutPreferences>(it).also(DesktopLayoutPreferences::validate) }
            ?: DesktopLayoutPreferences()
    } catch (_: Exception) {
        error.value = "桌面布局无法读取，已使用默认尺寸。"
        DesktopLayoutPreferences()
    })
    val preferences = state.asStateFlow()

    fun update(value: DesktopLayoutPreferences) { value.validate(); state.value = value }
    fun reset() = update(DesktopLayoutPreferences())
    fun setOpen(panel: DesktopPanel, open: Boolean) = update(state.value.withOpen(panel, open))

    /** Called after dragging finishes. Serialize writes, read the latest state, and never roll back newer UI changes. */
    suspend fun save(): Boolean = saveMutex.withLock {
        val value = state.value
        try {
            withContext(Dispatchers.Default) { store.write("desktop-layout-v1", Json.encodeToString(value)) }
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "桌面布局保存失败，当前尺寸仍有效。"; false }
    }
}
