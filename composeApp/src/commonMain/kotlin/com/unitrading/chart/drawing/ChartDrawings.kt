package com.unitrading.chart.drawing

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.unitrading.app.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
internal data class DrawingToolPrefs(
    val favorites: List<DrawingTool> = emptyList(),
    /** Tool each flyout's button shows, by group name. */
    val recent: Map<String, DrawingTool> = emptyMap(),
    val magnet: MagnetMode = MagnetMode.OFF,
    val magnetIndicators: Boolean = false,
    val keepDrawing: Boolean = false,
    val cursor: ChartCursor = ChartCursor.CROSS,
    val valuesTooltip: Boolean = false,
    val removeLocked: Boolean = false,
    val favoritesBarVisible: Boolean = true,
    /** The look a tool starts with after "设为默认". */
    /** A tool's look as saved before [toolDefaults] existed; read for a tool without one, never written again. */
    val defaults: Map<DrawingTool, DrawingStyle> = emptyMap(),
    val toolDefaults: Map<DrawingTool, DrawingDefaults> = emptyMap(),
)

/**
 * What a tool's settings dialog sets, saved as the tool's default so new drawings of the tool start with it: the look
 * (text formatting included), the levels, the settings kept as params, and the intervals the drawing shows on. A drawing's
 * own content stays its own: points, text, link, glyph, image, and params such as where a table sits.
 */
@Serializable
internal data class DrawingDefaults(
    val style: DrawingStyle,
    val levels: List<DrawingLevel> = emptyList(),
    val levels2: List<DrawingLevel> = emptyList(),
    val levels3: List<DrawingLevel> = emptyList(),
    val params: Map<String, Double> = emptyMap(),
    val hiddenIntervals: Set<IntervalBucket> = emptySet(),
) {
    fun applyTo(drawing: Drawing): Drawing = drawing.copy(style = style, levels = levels, levels2 = levels2, levels3 = levels3,
        params = drawing.params + params.filterKeys { it in settingParams(drawing.tool) }, hiddenIntervals = hiddenIntervals)

    companion object {
        fun of(drawing: Drawing) = DrawingDefaults(drawing.style, drawing.levels, drawing.levels2, drawing.levels3,
            drawing.params.filterKeys { it in settingParams(drawing.tool) }, drawing.hiddenIntervals)
    }
}

/** A pen stroke of the demonstration cursor; it fades and goes away by itself. */
internal class DemoStroke(val points: List<Offset>, val endedAtMs: Long?, val opacity: Float)

internal sealed interface DrawingHit {
    val drawing: Drawing
    data class Body(override val drawing: Drawing) : DrawingHit
    data class Grab(override val drawing: Drawing, val handle: Int) : DrawingHit
}

private const val STATE_KEY = "chart-drawings-v1"
private const val PREFS_KEY = "chart-drawing-tools-v1"
private const val IMAGE_KEY_PREFIX = "chart-drawing-image-"
internal const val MAX_DRAWINGS_PER_SYMBOL = 1000
private const val DOUBLE_CLICK_MS = 350L
private const val DEMO_FADE_MS = 1500L

private val json = Json { ignoreUnknownKeys = true }

/**
 * The drawings of every symbol and the toolbar's saved preferences, shared by every chart that is open. Writes go to the
 * snapshot store on their own coroutine, latest state wins.
 */
@Stable
class DrawingLibrary(internal val store: SnapshotStore, saveDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    internal val scope = CoroutineScope(SupervisorJob() + saveDispatcher)
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val prefSaves = Channel<Unit>(Channel.CONFLATED)
    /** One list per symbol. Each chart reads and replaces the list of the symbol it shows, so two charts of one symbol show the same drawings. */
    internal val lists = mutableStateMapOf<String, List<Drawing>>()
    internal var prefs by mutableStateOf(load<DrawingToolPrefs>(PREFS_KEY) ?: DrawingToolPrefs()); private set
    var notice by mutableStateOf<String?>(null); internal set
    internal val images = mutableStateMapOf<String, ImageBitmap>()
    private val histories = mutableMapOf<String, DrawingHistory>()
    /** The undo history of [symbol]'s drawings, kept while the app runs. */
    internal fun history(symbol: String) = histories.getOrPut(symbol) { DrawingHistory() }

    init {
        // A file that cannot be read is kept aside, so saving the empty state does not destroy it.
        try {
            store.read(STATE_KEY)?.let { lists.putAll(json.decodeFromString<Map<String, List<Drawing>>>(it)) }
        } catch (_: Exception) {
            store.read(STATE_KEY)?.let { runCatching { store.write("$STATE_KEY-unreadable", it) } }
            notice = "图表绘图无法读取，原文件已另存。"
        }
        scope.launch { for (ignored in saves) runCatching { store.write(STATE_KEY, json.encodeToString(lists.toMap().filterValues { it.isNotEmpty() })) } }
        scope.launch { for (ignored in prefSaves) runCatching { store.write(PREFS_KEY, json.encodeToString(prefs)) } }
    }

    private inline fun <reified T> load(key: String): T? = runCatching { store.read(key)?.let { json.decodeFromString<T>(it) } }.getOrNull()
    internal fun save() { saves.trySend(Unit) }
    internal fun updatePrefs(change: (DrawingToolPrefs) -> DrawingToolPrefs) { prefs = change(prefs); prefSaves.trySend(Unit) }
}

/**
 * The steps taken on one symbol's drawings, newest last. Every chart of the symbol works on the one list, so they share
 * its history too: undoing in one chart takes back the last step on the symbol, whichever chart made it, and never the
 * drawings another chart added since.
 */
internal class DrawingHistory {
    private val undone = ArrayDeque<List<Drawing>>()
    private val redone = ArrayDeque<List<Drawing>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** A step from [before] to a new list. */
    fun record(before: List<Drawing>) {
        undone.addLast(before)
        if (undone.size > 100) undone.removeFirst()
        redone.clear()
        refresh()
    }
    /** The list before the last step, or null when there is none; [current] can be brought back with [redo]. */
    fun undo(current: List<Drawing>): List<Drawing>? = undone.removeLastOrNull()?.also { redone.addLast(current); refresh() }
    fun redo(current: List<Drawing>): List<Drawing>? = redone.removeLastOrNull()?.also { undone.addLast(current); refresh() }
    private fun refresh() { canUndo = undone.isNotEmpty(); canRedo = redone.isNotEmpty() }
}

/**
 * One chart's drawings and its toolbar's state: the tool armed, the selection and the placement under way. A chart
 * [bind]s the symbol it shows; time anchors are absolute, so one list serves every interval. The lists and their undo
 * history live in the [DrawingLibrary], which every open chart shares.
 */
@Stable
class ChartDrawings internal constructor(private val library: DrawingLibrary) {
    /** A chart with a library of its own, for an app that shows one chart. */
    constructor(store: SnapshotStore, saveDispatcher: CoroutineDispatcher = Dispatchers.Default) : this(DrawingLibrary(store, saveDispatcher))

    private val store get() = library.store
    private val saveScope get() = library.scope
    private val prefs get() = library.prefs
    var notice: String?
        get() = library.notice
        private set(value) { library.notice = value }

    var symbol by mutableStateOf(""); private set
    var drawings: List<Drawing>
        get() = library.lists[symbol].orEmpty()
        private set(value) { library.lists[symbol] = value }

    // Toolbar state --------------------------------------------------------------------------------------------------
    var tool by mutableStateOf<DrawingTool?>(null); private set
    var cursor by mutableStateOf(prefs.cursor); private set
    var zooming by mutableStateOf(false); private set
    var iconGlyph by mutableStateOf("😀")
    var magnet: MagnetMode
        get() = prefs.magnet
        set(value) { updatePrefs { it.copy(magnet = value) } }
    var magnetIndicators: Boolean
        get() = prefs.magnetIndicators
        set(value) { updatePrefs { it.copy(magnetIndicators = value) } }
    var keepDrawing: Boolean
        get() = prefs.keepDrawing
        set(value) { updatePrefs { it.copy(keepDrawing = value) } }
    var valuesTooltip: Boolean
        get() = prefs.valuesTooltip
        set(value) { updatePrefs { it.copy(valuesTooltip = value) } }
    var removeLockedToo: Boolean
        get() = prefs.removeLocked
        set(value) { updatePrefs { it.copy(removeLocked = value) } }
    var favoritesBarVisible: Boolean
        get() = prefs.favoritesBarVisible
        set(value) { updatePrefs { it.copy(favoritesBarVisible = value) } }
    val favorites: List<DrawingTool> get() = prefs.favorites
    var lockDrawings by mutableStateOf(false)
    var lockIndicators by mutableStateOf(false)
    var hideDrawings by mutableStateOf(false)
    var hideIndicators by mutableStateOf(false)
    var hideTrading by mutableStateOf(false)
    /** Whether a phone shows the toolbar; wider layouts always do. */
    var toolbarOpen by mutableStateOf(false)

    private fun updatePrefs(change: (DrawingToolPrefs) -> DrawingToolPrefs) = library.updatePrefs(change)

    internal fun recentTool(group: ToolGroup): DrawingTool = prefs.recent[group.name]?.takeIf { it in group.tools } ?: group.sections.first().tools.first()
    fun toggleFavorite(value: DrawingTool) = updatePrefs { p -> p.copy(favorites = if (value in p.favorites) p.favorites - value else p.favorites + value) }
    fun isFavorite(value: DrawingTool) = value in prefs.favorites

    fun chooseCursor(value: ChartCursor) {
        cancelPlacement()
        zooming = false
        cursor = value
        updatePrefs { it.copy(cursor = value) }
    }

    /** Arms [value]; choosing the armed tool again disarms it. */
    fun chooseTool(value: DrawingTool, glyph: String? = null) {
        val same = tool == value && (glyph == null || glyph == iconGlyph)
        cancelPlacement()
        zooming = false
        if (glyph != null) iconGlyph = glyph
        if (same) return
        tool = value
        ToolGroup.entries.firstOrNull { value in it.tools }?.let { group -> updatePrefs { it.copy(recent = it.recent + (group.name to value)) } }
    }

    fun toggleZoom() { cancelPlacement(); zooming = !zooming }
    fun cancelTool() { cancelPlacement() }
    /** Selected drawing, if any. */
    val selected: Drawing? get() = drawings.firstOrNull { it.id == selectedId }

    // Placement and selection state ----------------------------------------------------------------------------------
    var selectedId by mutableStateOf<String?>(null); private set
    internal var hoveredId by mutableStateOf<String?>(null); private set
    internal var draft by mutableStateOf<Drawing?>(null); private set
    /** The result of the last measurement, shown until the next click. */
    internal var measured by mutableStateOf<Drawing?>(null); private set
    internal var zoomBox by mutableStateOf<Rect?>(null); private set
    internal var demoStrokes by mutableStateOf<List<DemoStroke>>(emptyList()); private set
    var menuAt by mutableStateOf<Offset?>(null); private set
    /** Drawing whose text is being asked for. */
    var textEditId by mutableStateOf<String?>(null); private set
    var settingsId by mutableStateOf<String?>(null); private set
    var pendingImageId by mutableStateOf<String?>(null); private set
    internal var tooltipAt by mutableStateOf<Offset?>(null); private set
    internal val images get() = library.images

    private var placed = 0
    private var scenes: List<DrawingScene> = emptyList()
    private var dragOriginal: Drawing? = null
    private var dragHandle: Int? = null
    private var dragBefore: List<Drawing>? = null
    private var dragStart = Offset.Zero
    private var dragMoved = false
    private var dragCloned = false
    private var downAt = Offset.Zero
    private var freehand = false
    /** A touch on an unselected drawing: selects it if the finger lifts without moving, otherwise the chart pans. */
    private var touchCandidate: String? = null
    private var lastClickMs = 0L
    private var lastClickAt = Offset.Zero
    private var lastClickDrawing: String? = null
    private var demoPoints: MutableList<Offset>? = null
    private var clipboard: Drawing? = null
    private val history get() = library.history(symbol)
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    private fun cancelPlacement() {
        draft = null
        placed = 0
        freehand = false
        tool = null
        zoomBox = null
    }

    fun bind(value: String) {
        if (value == symbol) return
        cancelPlacement()
        symbol = value
        selectedId = null
        hoveredId = null
        measured = null
        menuAt = null
        loadImages(drawings)
    }

    /** Records [next] as the symbol's list; `before` goes on the undo stack. */
    private fun commit(next: List<Drawing>, before: List<Drawing> = drawings) {
        history.record(before)
        drawings = next
        persist()
    }

    private fun persist() { library.save() }

    fun undo() {
        drawings = history.undo(drawings) ?: return
        if (drawings.none { it.id == selectedId }) selectedId = null
        persist()
    }

    fun redo() {
        drawings = history.redo(drawings) ?: return
        persist()
    }

    private fun newId() = Random.nextLong().toULong().toString(16)

    private fun replace(value: Drawing) { drawings = drawings.map { if (it.id == value.id) value else it } }

    // Edits from menus and panels -----------------------------------------------------------------------------------
    fun update(value: Drawing) { if (drawings.any { it.id == value.id }) commit(drawings.map { if (it.id == value.id) value else it }) }
    /** Shows [value] on the chart without recording it, while a settings dialog is open. */
    internal fun preview(value: Drawing) = replace(value)
    fun select(id: String?) { selectedId = id; menuAt = null }
    fun remove(id: String) {
        drawings.firstOrNull { it.id == id }?.imageId?.let(::dropImage)
        if (selectedId == id) selectedId = null
        if (hoveredId == id) hoveredId = null
        menuAt = null
        commit(drawings.filterNot { it.id == id })
    }
    /** Delete key: a locked drawing stays unless the "always remove locked" option is on. */
    fun removeSelected() { selected?.takeIf { !it.locked || removeLockedToo }?.let { remove(it.id) } }
    fun clone(id: String) {
        val source = drawings.firstOrNull { it.id == id } ?: return
        val copy = source.copy(id = newId())
        commit(drawings + copy)
        selectedId = copy.id
        menuAt = null
    }
    fun copySelected() { clipboard = selected }
    fun paste() { clipboard?.let { val copy = it.copy(id = newId()); commit(drawings + copy); selectedId = copy.id } }
    /**
     * Draws a horizontal line through [point] in one step, as the plot's context menu does, and selects it. Hidden drawings
     * come back, because the new line would not show among them.
     */
    fun addHorizontalLine(point: DrawingPoint) {
        menuAt = null
        if (drawings.size >= MAX_DRAWINGS_PER_SYMBOL) { notice = "每个品种最多 $MAX_DRAWINGS_PER_SYMBOL 个绘图。"; return }
        val line = defaultsFor(DrawingTool.HORIZONTAL_LINE, newDrawing(DrawingTool.HORIZONTAL_LINE, newId(), listOf(point)))
        hideDrawings = false
        commit(drawings + line)
        selectedId = line.id
    }
    fun toggleLock(id: String) { drawings.firstOrNull { it.id == id }?.let { update(it.copy(locked = !it.locked)) }; menuAt = null }
    fun hide(id: String) { drawings.firstOrNull { it.id == id }?.let { update(it.copy(hidden = true)) }; if (selectedId == id) selectedId = null; menuAt = null }
    /** Moves a drawing in the paint order; a positive [delta] brings it towards the front. */
    fun moveOrder(id: String, delta: Int) {
        val index = drawings.indexOfFirst { it.id == id }
        if (index < 0) return
        val list = drawings.toMutableList()
        val item = list.removeAt(index)
        list.add((index + delta).coerceIn(0, list.size), item)
        commit(list); menuAt = null
    }
    fun openSettings(id: String) { menuAt = null; settingsId = id }
    fun closeSettings() { settingsId = null }
    fun openText(id: String) { menuAt = null; textEditId = id }
    fun closeText(cancelled: Boolean) {
        val id = textEditId
        textEditId = null
        // A note dismissed before it got any text was never wanted.
        if (cancelled && id != null) drawings.firstOrNull { it.id == id }?.takeIf { it.text.isBlank() && it.link.isBlank() && it.tool.promptsForText }?.let { remove(it.id) }
    }
    fun closeMenu() { menuAt = null }
    fun clearNotice() { notice = null }
    /** Saves everything [value]'s settings dialog sets as the default of its tool: new drawings of the tool start with it. */
    fun setAsDefault(value: Drawing) = updatePrefs { it.copy(toolDefaults = it.toolDefaults + (value.tool to DrawingDefaults.of(value)), defaults = it.defaults - value.tool) }
    /** Forgets the saved default of [tool]: new drawings of it start with the built-in settings again. */
    fun clearDefault(tool: DrawingTool) = updatePrefs { it.copy(toolDefaults = it.toolDefaults - tool, defaults = it.defaults - tool) }
    fun hasDefault(tool: DrawingTool): Boolean = tool in prefs.toolDefaults || tool in prefs.defaults
    /** [value] with the built-in settings of its tool, its own content kept. */
    fun builtInSettings(value: Drawing): Drawing =
        DrawingDefaults.of(newDrawing(value.tool, value.id, value.points, value.glyph)).applyTo(value)

    fun drawingCount(): Int = drawings.size
    /** Removes the symbol's drawings; locked ones only with the "always remove locked" option. */
    fun removeAllDrawings() {
        val kept = if (removeLockedToo) emptyList() else drawings.filter { it.locked }
        selectedId = null
        drawings.filter { it !in kept }.mapNotNull { it.imageId }.forEach(::dropImage)
        commit(kept)
    }

    private fun dropImage(id: String) { images.remove(id); saveScope.launch { runCatching { store.write(IMAGE_KEY_PREFIX + id, "") } } }

    val hiddenCount: Int get() = drawings.count { it.hidden }
    fun showHidden() { commit(drawings.map { it.copy(hidden = false) }) }

    /** Stores a picked picture for the IMAGE drawing waiting for one; [encoded] is the file's bytes. */
    fun finishImage(drawingId: String, encoded: ByteArray?) {
        pendingImageId = null
        if (encoded == null) { remove(drawingId); return }
        val bitmap = runCatching { decodeDrawingImage(encoded) }.getOrNull()
        if (bitmap == null) { remove(drawingId); return }
        val imageId = newId()
        images[imageId] = bitmap
        val text = encodeDrawingImage(encoded)
        saveScope.launch { runCatching { store.write(IMAGE_KEY_PREFIX + imageId, text) } }
        drawings.firstOrNull { it.id == drawingId }?.let { d ->
            val width = 200.0
            val height = width * bitmap.height / bitmap.width.coerceAtLeast(1)
            update(d.copy(imageId = imageId, params = d.params + ("width" to width) + ("height" to height)))
        }
    }

    private fun loadImages(list: List<Drawing>) {
        list.mapNotNull { it.imageId }.filterNot { it in images }.forEach { id ->
            saveScope.launch {
                val bitmap = runCatching { store.read(IMAGE_KEY_PREFIX + id)?.takeIf { it.isNotEmpty() }?.let(::decodeStoredImage) }.getOrNull()
                if (bitmap != null) kotlinx.coroutines.withContext(Dispatchers.Main) { images[id] = bitmap }
            }
        }
    }

    // Frame ----------------------------------------------------------------------------------------------------------

    /** What the chart paints this frame; hit tests use the same scenes. */
    internal fun frame(ctx: DrawingContext): DrawingFrame {
        val bucket = IntervalBucket.of(ctx.timeframe)
        val list = if (hideDrawings) emptyList() else drawings.filter { !it.hidden && bucket !in it.hiddenIntervals }
        val built = list.map { buildScene(it, ctx, expanded = it.id == hoveredId || it.id == selectedId) }
        scenes = built
        val transient = draft ?: measured
        return DrawingFrame(built, selectedId, hoveredId, images, transient?.let { buildScene(it, ctx, expanded = true) }, zoomBox, demoStrokes)
    }

    internal fun hitAt(point: Offset, tolerance: Float, handleTolerance: Float): DrawingHit? {
        listOfNotNull(selectedId, hoveredId).distinct().forEach { id ->
            val scene = scenes.firstOrNull { it.drawing.id == id } ?: return@forEach
            scene.handleAt(point, handleTolerance)?.let { return DrawingHit.Grab(scene.drawing, it.id) }
        }
        return scenes.asReversed().firstOrNull { it.hits(point, tolerance) }?.let { DrawingHit.Body(it.drawing) }
    }

    private fun anchorAt(point: Offset, ctx: DrawingContext, info: PointerInfo, indicatorValues: List<Double>, snapToBar: Boolean = true): DrawingPoint {
        val raw = ctx.coords.pointAt(point, snapToBar)
        var mode = magnet
        if (info.ctrl) mode = if (mode == MagnetMode.OFF) MagnetMode.STRONG else MagnetMode.OFF
        val snapped = ctx.coords.magnetize(raw, mode, 16f * ctx.density, if (magnetIndicators) indicatorValues else emptyList())
        return if (snapped === raw) raw.copy(price = ctx.snapToTick(raw.price)) else snapped
    }

    private fun defaultsFor(value: DrawingTool, base: Drawing): Drawing =
        prefs.toolDefaults[value]?.applyTo(base) ?: prefs.defaults[value]?.let { base.copy(style = it) } ?: base

    private fun startDraft(value: DrawingTool, first: DrawingPoint, ctx: DrawingContext) {
        val points = if (value.anchors == 1 || value.anchors == FREEHAND) listOf(first) else listOf(first, first)
        var fresh = defaultsFor(value, newDrawing(value, newId(), points, if (value == DrawingTool.ICON) iconGlyph else null))
        if (value == DrawingTool.TABLE) {
            val plot = ctx.coords.plot
            val x = ctx.coords.x(first.timeMs)
            val y = ctx.coords.y(first.price)
            fresh = fresh.copy(params = fresh.params + ("screenX" to ((x - plot.left) / plot.width).toDouble()) + ("screenY" to ((y - plot.top) / plot.height).toDouble()))
        }
        draft = fresh
        placed = 1
    }

    private fun finishDraft(ctx: DrawingContext, trimRubberBand: Boolean) {
        var finished = draft ?: return
        if (trimRubberBand) finished = finished.copy(points = finished.points.take(placed))
        finished = completeAnchors(finished, ctx)
        draft = null
        placed = 0
        freehand = false
        if (finished.tool == DrawingTool.MEASURE) { measured = finished; tool = null; return }
        if (drawings.size >= MAX_DRAWINGS_PER_SYMBOL) { tool = null; notice = "每个品种最多 $MAX_DRAWINGS_PER_SYMBOL 个绘图。"; return }
        commit(drawings + finished)
        selectedId = finished.id
        if (!keepDrawing) tool = null
        when {
            finished.tool.promptsForText -> textEditId = finished.id
            finished.tool == DrawingTool.IMAGE -> pendingImageId = finished.id
        }
    }

    private fun updateRubberBand(point: DrawingPoint) {
        val current = draft ?: return
        if (placed < current.points.size) draft = current.copy(points = current.points.toMutableList().also { it[placed] = point })
    }

    /** Press on the chart. Returns whether the drawing layer took it, in which case the chart must not pan or select. */
    internal fun pointerDown(info: PointerInfo, ctx: DrawingContext, indicatorValues: List<Double>, orderLineAt: (Offset) -> Boolean): Boolean {
        val point = info.position
        if (!ctx.coords.plot.contains(point)) return false
        tooltipAt = null
        measured?.let { measured = null }
        if (zooming) { zoomBox = Rect(point, point); downAt = point; return true }
        val armed = tool
        if (armed != null) {
            val anchor = anchorAt(point, ctx, info, indicatorValues, snapToBar = armed.anchors != FREEHAND)
            downAt = point
            val current = draft
            when {
                armed.anchors == FREEHAND -> { startDraft(armed, anchor, ctx); freehand = true }
                current == null -> {
                    startDraft(armed, anchor, ctx)
                    if (armed.anchors == 1) finishDraft(ctx, trimRubberBand = false)
                }
                armed.anchors == OPEN_ANCHORS -> {
                    val doubleClick = info.uptimeMs - lastClickMs < DOUBLE_CLICK_MS && distance(point, lastClickAt) < 8f * ctx.density
                    val onFirst = armed == DrawingTool.POLYLINE && placed >= 3 && distance(point, ctx.coords.offset(current.points.first())) < 10f * ctx.density
                    if (onFirst) { draft = current.copy(params = current.params + ("closed" to 1.0)); finishDraft(ctx, trimRubberBand = true) }
                    else if (doubleClick && placed >= 2) finishDraft(ctx, trimRubberBand = true)
                    else if (!doubleClick) {
                        draft = current.copy(points = current.points.take(placed) + anchor + anchor)
                        placed++
                    }
                }
                else -> {
                    draft = current.copy(points = current.points.take(placed) + anchor + anchor)
                    placed++
                    if (placed == armed.anchors) finishDraft(ctx, trimRubberBand = true)
                }
            }
            lastClickMs = info.uptimeMs
            lastClickAt = point
            return true
        }
        if (cursor == ChartCursor.DEMONSTRATION) { demoPoints = mutableListOf(point); return true }
        val hit = hitAt(point, (if (info.touch) 14f else 6f) * ctx.density, (if (info.touch) 18f else 9f) * ctx.density)
        if (cursor == ChartCursor.ERASER) {
            if (hit != null && !(hit.drawing.locked || lockDrawings)) remove(hit.drawing.id)
            return hit != null
        }
        if (info.shift && !info.touch) {
            selectedId = null
            tool = DrawingTool.MEASURE
            startDraft(DrawingTool.MEASURE, anchorAt(point, ctx, info, indicatorValues), ctx)
            downAt = point
            return true
        }
        if (hit == null) { selectedId = null; lastClickDrawing = null; return false }
        // Order lines stay reachable through a drawing lying on top of them; only a selected drawing's handles win over them.
        if (hit is DrawingHit.Body && orderLineAt(point)) return false
        if (info.touch && hit is DrawingHit.Body && hit.drawing.id != selectedId) { touchCandidate = hit.drawing.id; downAt = point; return false }
        val doubleClick = info.uptimeMs - lastClickMs < DOUBLE_CLICK_MS && lastClickDrawing == hit.drawing.id
        lastClickMs = info.uptimeMs
        lastClickDrawing = hit.drawing.id
        selectedId = hit.drawing.id
        if (doubleClick) {
            if (hit.drawing.tool.promptsForText || hit.drawing.tool == DrawingTool.TABLE) openText(hit.drawing.id) else openSettings(hit.drawing.id)
            return true
        }
        if (hit.drawing.locked || lockDrawings) return false
        var source = hit.drawing
        dragBefore = drawings
        dragCloned = info.ctrl
        if (info.ctrl) {
            source = source.copy(id = newId())
            drawings = drawings + source
            selectedId = source.id
        }
        dragOriginal = source
        dragHandle = (hit as? DrawingHit.Grab)?.handle
        dragStart = point
        dragMoved = false
        return true
    }

    /** Pointer movement with the button down or, for placement previews, up. Returns whether the chart must ignore it. */
    internal fun pointerMove(info: PointerInfo, ctx: DrawingContext, indicatorValues: List<Double>): Boolean {
        val point = info.position
        tooltipAt?.let { tooltipAt = null }
        if (zoomBox != null) { zoomBox = absoluteRect(downAt, point); return true }
        demoPoints?.let { it += point; demoStrokes = demoStrokes.filter { s -> s.endedAtMs != null } + DemoStroke(it.toList(), null, 1f); return true }
        val armed = tool
        val current = draft
        if (armed != null && current != null) {
            if (freehand) {
                val anchor = ctx.coords.pointAt(point, snapToBar = false)
                val last = ctx.coords.offset(current.points.last())
                if (distance(last, point) >= 2f * ctx.density) draft = current.copy(points = current.points + anchor)
                return true
            }
            updateRubberBand(anchorAt(point, ctx, info, indicatorValues))
            return info.pressed
        }
        val original = dragOriginal ?: return false
        if (!dragMoved && distance(point, dragStart) < 3f * ctx.density) return true
        dragMoved = true
        val handle = dragHandle
        if (handle != null) replace(moveHandle(original, handle, anchorAt(point, ctx, info, indicatorValues), ctx))
        else {
            val bars = ((point.x - dragStart.x) / ctx.coords.step).roundToInt().toDouble()
            val price = ctx.snapToTick(ctx.coords.priceAt(point.y) - ctx.coords.priceAt(dragStart.y))
            replace(translate(original, bars, price, point - dragStart, ctx))
        }
        return true
    }

    /** A multi-touch chart gesture takes over; roll back any uncommitted drawing drag. */
    internal fun cancelPointerGesture() {
        dragBefore?.let { drawings = it }
        dragOriginal = null; dragBefore = null; dragHandle = null; dragMoved = false; dragCloned = false
        touchCandidate = null
        cancelPlacement()
        zooming = false
        hideTooltip()
        closeMenu()
    }

    /** Button released. Returns whether this ended something the layer had taken. */
    internal fun pointerUp(info: PointerInfo, ctx: DrawingContext, indicatorValues: List<Double>, onZoom: (Double, Double) -> Unit): Boolean {
        val point = info.position
        touchCandidate?.let { id -> touchCandidate = null; if (distance(point, downAt) < 12f * ctx.density) selectedId = id }
        zoomBox?.let { box ->
            zoomBox = null
            zooming = false
            if (box.width > 4f * ctx.density) onZoom(ctx.coords.indexAt(box.left), ctx.coords.indexAt(box.right))
            return true
        }
        demoPoints?.let { list ->
            demoPoints = null
            demoStrokes = demoStrokes.filter { it.endedAtMs != null } + DemoStroke(list.toList(), info.uptimeMs, 1f)
            return true
        }
        val armed = tool
        val current = draft
        if (armed != null && current != null) {
            if (freehand) { finishDraft(ctx, trimRubberBand = false); return true }
            // Press, drag, release draws the first two anchors in one motion.
            if (placed == 1 && armed.anchors == 2 && distance(point, downAt) > 6f * ctx.density) {
                updateRubberBand(anchorAt(point, ctx, info, indicatorValues))
                placed = 2
                finishDraft(ctx, trimRubberBand = true)
            }
            return true
        }
        if (dragOriginal == null) return false
        val before = dragBefore
        val moved = dragMoved
        val source = dragOriginal
        dragOriginal = null
        dragHandle = null
        dragBefore = null
        if (before != null) {
            if (moved) commit(drawings, before)
            // Ctrl + click without a drag leaves no copy behind.
            else if (dragCloned) { drawings = before; selectedId = before.lastOrNull { it.points == source?.points && it.tool == source.tool }?.id }
        }
        return true
    }

    /** Mouse over the chart with no button down: hover highlight and placement preview. */
    internal fun pointerHover(info: PointerInfo, ctx: DrawingContext, indicatorValues: List<Double>) {
        pointerMove(info, ctx, indicatorValues)
        if (tool != null || zooming) { hoveredId = null; return }
        val inside = ctx.coords.plot.contains(info.position)
        hoveredId = if (inside) hitAt(info.position, 6f * ctx.density, 9f * ctx.density)?.drawing?.id else null
    }

    internal fun pointerExit() { hoveredId = null }

    /** Right click or long press on a drawing opens its menu. */
    internal fun openMenuAt(point: Offset, ctx: DrawingContext, orderLineAt: (Offset) -> Boolean): Boolean {
        if (draft != null) { cancelPlacement(); return true }
        if (!ctx.coords.plot.contains(point)) return false
        val hit = hitAt(point, 8f * ctx.density, 12f * ctx.density) ?: return false
        if (orderLineAt(point)) return false
        selectedId = hit.drawing.id
        menuAt = point
        return true
    }

    internal fun showTooltip(point: Offset) { if (valuesTooltip && tool == null && !zooming) tooltipAt = point }
    internal fun hideTooltip() { tooltipAt = null }

    /** One animation frame of the demonstration pen's fade. */
    internal fun tickDemo(nowMs: Long) {
        val strokes = demoStrokes
        if (strokes.isEmpty()) return
        demoStrokes = strokes.mapNotNull { stroke ->
            val ended = stroke.endedAtMs ?: return@mapNotNull stroke
            val left = 1f - (nowMs - ended).toFloat() / DEMO_FADE_MS
            if (left <= 0f) null else DemoStroke(stroke.points, ended, left)
        }
    }

    /** Enter finishes an open-ended tool. */
    internal fun finishOpen(ctx: DrawingContext): Boolean {
        val armed = tool ?: return false
        if (armed.anchors != OPEN_ANCHORS || draft == null || placed < 2) return false
        finishDraft(ctx, trimRubberBand = true)
        return true
    }

    internal val placementActive: Boolean get() = draft != null
    internal fun chooseMeasure() { chooseTool(DrawingTool.MEASURE) }
    /** Esc: the current placement first, then the selection. */
    internal fun escape(): Boolean {
        if (zooming || zoomBox != null) { zooming = false; zoomBox = null; return true }
        if (tool != null || draft != null) { cancelPlacement(); return true }
        if (measured != null) { measured = null; return true }
        if (selectedId != null) { selectedId = null; return true }
        return false
    }

}

/** Where and how the pointer is, in one value. */
internal class PointerInfo(val position: Offset, val ctrl: Boolean, val shift: Boolean, val touch: Boolean, val pressed: Boolean, val uptimeMs: Long)
