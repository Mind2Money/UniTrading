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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The panels the desktop workspace can fold away. */
enum class DesktopPanel { MARKET, ORDER, TERMINAL }

/** Every part of the desktop workspace; each can leave the main window for a window of its own. */
@Serializable
enum class WorkspacePanel(val title: String) {
    CHART("图表"), MARKET("行情"), ORDER("下单"), TERMINAL("交易"), ORDER_FLOW("订单流")
}

/** Where a window of the app sits on the desktop and how large it is, in dp. */
@Serializable
data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    val valid: Boolean get() = listOf(x, y, width, height).all { it.isFinite() } && x in -20_000f..20_000f && y in -20_000f..20_000f &&
        width in 120f..10_000f && height in 120f..10_000f
}

/** Preferred panel dimensions in dp, and which panels are open. Window constraints affect display, never overwrite these preferences. */
@Serializable
data class DesktopLayoutPreferences(
    val sidebarWidth: Float = 320f,
    val orderWidth: Float = 296f,
    val terminalHeight: Float = 220f,
    val marketOpen: Boolean = true,
    val orderOpen: Boolean = true,
    val terminalOpen: Boolean = true,
    val orderFlowFraction: Float = .5f,
    /** Panels shown in a window of their own instead of in the main window. */
    val detached: Set<WorkspacePanel> = emptySet(),
    /** Last bounds of each window other than the main one, by its key: a panel's name, or a chart window's id. */
    val windows: Map<String, WindowBounds> = emptyMap(),
) {
    fun validate() {
        require(listOf(sidebarWidth, orderWidth, terminalHeight).all { it.isFinite() && it in 1f..4000f }) {
            "面板尺寸超出范围"
        }
        require(orderFlowFraction.isFinite() && orderFlowFraction > 0f && orderFlowFraction < 1f) {
            "订单流面板比例超出范围"
        }
        require(windows.size <= MAX_WINDOWS && windows.all { (key, bounds) -> key.isNotBlank() && key.length <= 64 && bounds.valid }) { "窗口位置超出范围" }
    }
    fun withOpen(panel: DesktopPanel, open: Boolean) = when (panel) {
        DesktopPanel.MARKET -> copy(marketOpen = open)
        DesktopPanel.ORDER -> copy(orderOpen = open)
        DesktopPanel.TERMINAL -> copy(terminalOpen = open)
    }

    companion object { const val MAX_WINDOWS = 64 }
}

/**
 * Reads a saved layout. Fields and panels the app no longer has are dropped rather than failing the whole layout:
 * layouts saved before 2026-11-11 hold the removed accounts panel's height and may list it among the detached panels.
 */
internal fun decodeDesktopLayout(text: String): DesktopLayoutPreferences {
    val json = Json { ignoreUnknownKeys = true }
    val saved = json.parseToJsonElement(text).jsonObject
    val panels = WorkspacePanel.entries.map { it.name }.toSet()
    val detached = saved["detached"]?.jsonArray?.filter { it.jsonPrimitive.content in panels }
    return json.decodeFromJsonElement(DesktopLayoutPreferences.serializer(),
        if (detached == null) saved else JsonObject(saved + ("detached" to JsonArray(detached))))
}

class DesktopLayoutController(private val store: SnapshotStore) {
    private val saveMutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow(try {
        store.read("desktop-layout-v1")?.let { decodeDesktopLayout(it).also(DesktopLayoutPreferences::validate) }
            ?: DesktopLayoutPreferences()
    } catch (_: Exception) {
        error.value = "桌面布局无法读取，已使用默认尺寸。"
        DesktopLayoutPreferences()
    })
    val preferences = state.asStateFlow()

    fun update(value: DesktopLayoutPreferences) { value.validate(); state.value = value }
    fun reset() = update(DesktopLayoutPreferences())
    fun setOpen(panel: DesktopPanel, open: Boolean) = update(state.value.withOpen(panel, open))
    /** Moves [panel] into a window of its own, or back into the main window. */
    fun setDetached(panel: WorkspacePanel, detached: Boolean) =
        update(state.value.let { it.copy(detached = if (detached) it.detached + panel else it.detached - panel) })
    /** Remembers where the window [key] sits; null forgets it, as when a chart window is closed for good. */
    fun setWindowBounds(key: String, bounds: WindowBounds?) {
        val current = state.value
        if (bounds == null) { if (key in current.windows) update(current.copy(windows = current.windows - key)); return }
        if (!bounds.valid || current.windows[key] == bounds || key !in current.windows && current.windows.size >= DesktopLayoutPreferences.MAX_WINDOWS) return
        update(current.copy(windows = current.windows + (key to bounds)))
    }

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
