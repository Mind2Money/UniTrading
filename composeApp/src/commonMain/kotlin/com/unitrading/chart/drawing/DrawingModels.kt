package com.unitrading.chart.drawing

import kotlinx.serialization.Serializable

/** Open-ended tools finish on a double click, Enter, or a click back on their first point. */
internal const val OPEN_ANCHORS = -1
/** Freehand tools take one continuous stroke. */
internal const val FREEHAND = 0

/**
 * Every drawing the left toolbar offers, in its menu order, named as on TradingView's Chinese chart. [anchors] counts the
 * points the user places; a few tools add computed points when placement ends (see [completeAnchors]).
 */
@Serializable
enum class DrawingTool(val label: String, val anchors: Int = 2, val shortcut: String? = null) {
    TREND_LINE("趋势线", 2, "Alt + T"),
    RAY("射线"),
    INFO_LINE("信息线"),
    EXTENDED_LINE("延长线"),
    TREND_ANGLE("趋势角度"),
    HORIZONTAL_LINE("水平线", 1, "Alt + H"),
    HORIZONTAL_RAY("水平射线", 1, "Alt + J"),
    VERTICAL_LINE("垂直线", 1, "Alt + V"),
    CROSS_LINE("横线", 1, "Alt + C"),
    PARALLEL_CHANNEL("平行通道", 3),
    REGRESSION_TREND("回归趋势"),
    FLAT_TOP_BOTTOM("平顶/平底", 3),
    DISJOINT_CHANNEL("不相交通道", 3),
    PITCHFORK("分叉线", 3),
    SCHIFF_PITCHFORK("希夫干草叉", 3),
    MODIFIED_SCHIFF_PITCHFORK("改良希夫干草叉", 3),
    INSIDE_PITCHFORK("内部分叉线", 3),

    FIB_RETRACEMENT("斐波那契回撤", 2, "Alt + F"),
    FIB_EXTENSION("斐波那契趋势扩展", 3),
    FIB_CHANNEL("斐波那契通道", 3),
    FIB_TIME_ZONE("斐波那契时区"),
    FIB_SPEED_FAN("斐波那契速度阻力扇"),
    FIB_TREND_TIME("斐波那契趋势时间", 3),
    FIB_CIRCLES("斐波那契圈"),
    FIB_SPIRAL("斐波那契螺旋"),
    FIB_SPEED_ARCS("斐波那契速度阻力弧"),
    FIB_WEDGE("斐波那契楔形", 3),
    PITCHFAN("倾斜扇形", 3),
    GANN_BOX("江恩箱"),
    GANN_SQUARE_FIXED("固定江恩正方"),
    GANN_SQUARE("江恩正方"),
    GANN_FAN("江恩扇"),

    XABCD("XABCD形态", 5),
    CYPHER("赛福形态", 5),
    HEAD_SHOULDERS("头肩形态", 7),
    ABCD("ABCD形态", 4),
    TRIANGLE_PATTERN("三角形态", 4),
    THREE_DRIVES("三驱形态", 7),
    ELLIOTT_IMPULSE("艾略特脉冲波（1·2·3·4·5）", 6),
    ELLIOTT_CORRECTION("艾略特修正浪（A·B·C）", 4),
    ELLIOTT_TRIANGLE("艾略特三角波（A·B·C·D·E）", 6),
    ELLIOTT_DOUBLE("艾略特双重组合浪（W·X·Y）", 4),
    ELLIOTT_TRIPLE("艾略特三重组合浪（W·X·Y·X·Z）", 6),
    CYCLIC_LINES("循环线"),
    TIME_CYCLES("时间循环"),
    SINE_LINE("正弦线"),

    LONG_POSITION("多头仓位", 1),
    SHORT_POSITION("空头仓位", 1),
    FORECAST("仓位预测"),
    BARS_PATTERN("K线形态"),
    GHOST_FEED("模拟K线", OPEN_ANCHORS),
    PROJECTION("扇形", 3),
    ANCHORED_VWAP("锚定VWAP", 1),
    FIXED_RANGE_VOLUME_PROFILE("固定范围成交量分布图"),
    ANCHORED_VOLUME_PROFILE("锚定成交量分布图", 1),
    PRICE_RANGE("价格范围"),
    DATE_RANGE("日期范围"),
    DATE_PRICE_RANGE("日期和价格范围"),

    BRUSH("笔刷", FREEHAND),
    HIGHLIGHTER("荧光笔", FREEHAND),
    ARROW_MARKER("箭头标记"),
    ARROW("箭头"),
    ARROW_UP("向上箭头", 1),
    ARROW_DOWN("向下箭头", 1),
    RECTANGLE("矩形", 2, "Alt + Shift + R"),
    ROTATED_RECTANGLE("旋转矩形", 3),
    PATH("路径", OPEN_ANCHORS),
    CIRCLE("圆"),
    ELLIPSE("椭圆", 3),
    POLYLINE("折线", OPEN_ANCHORS),
    TRIANGLE("三角形", 3),
    ARC("弧形", 3),
    CURVE("曲线"),
    DOUBLE_CURVE("双曲线"),

    TEXT("文字", 1),
    NOTE("笔记", 1),
    PRICE_NOTE("价格笔记"),
    PIN("置顶", 1),
    TABLE("表格", 1),
    CALLOUT("标注"),
    COMMENT("评论", 1),
    PRICE_LABEL("价格标签", 1),
    SIGNPOST("指示牌", 1),
    FLAG("旗标", 1),
    IMAGE("图片", 1),
    POST("帖子", 1),
    IDEA("观点", 1),

    ICON("图标", 1),
    MEASURE("测量"),
}

internal class ToolSection(val title: String, val tools: List<DrawingTool>)

/** The flyouts of the toolbar; a group's button shows the tool last used from it. */
internal enum class ToolGroup(val label: String, val sections: List<ToolSection>) {
    TREND("趋势工具", listOf(
        ToolSection("线条", listOf(DrawingTool.TREND_LINE, DrawingTool.RAY, DrawingTool.INFO_LINE, DrawingTool.EXTENDED_LINE, DrawingTool.TREND_ANGLE,
            DrawingTool.HORIZONTAL_LINE, DrawingTool.HORIZONTAL_RAY, DrawingTool.VERTICAL_LINE, DrawingTool.CROSS_LINE)),
        ToolSection("通道", listOf(DrawingTool.PARALLEL_CHANNEL, DrawingTool.REGRESSION_TREND, DrawingTool.FLAT_TOP_BOTTOM, DrawingTool.DISJOINT_CHANNEL)),
        ToolSection("干草叉", listOf(DrawingTool.PITCHFORK, DrawingTool.SCHIFF_PITCHFORK, DrawingTool.MODIFIED_SCHIFF_PITCHFORK, DrawingTool.INSIDE_PITCHFORK)),
    )),
    FIB("江恩和斐波那契工具", listOf(
        ToolSection("斐波那契", listOf(DrawingTool.FIB_RETRACEMENT, DrawingTool.FIB_EXTENSION, DrawingTool.FIB_CHANNEL, DrawingTool.FIB_TIME_ZONE, DrawingTool.FIB_SPEED_FAN,
            DrawingTool.FIB_TREND_TIME, DrawingTool.FIB_CIRCLES, DrawingTool.FIB_SPIRAL, DrawingTool.FIB_SPEED_ARCS, DrawingTool.FIB_WEDGE, DrawingTool.PITCHFAN)),
        ToolSection("江恩", listOf(DrawingTool.GANN_BOX, DrawingTool.GANN_SQUARE_FIXED, DrawingTool.GANN_SQUARE, DrawingTool.GANN_FAN)),
    )),
    PATTERN("形态", listOf(
        ToolSection("图表形态", listOf(DrawingTool.XABCD, DrawingTool.CYPHER, DrawingTool.HEAD_SHOULDERS, DrawingTool.ABCD, DrawingTool.TRIANGLE_PATTERN, DrawingTool.THREE_DRIVES)),
        ToolSection("艾略特波浪", listOf(DrawingTool.ELLIOTT_IMPULSE, DrawingTool.ELLIOTT_CORRECTION, DrawingTool.ELLIOTT_TRIANGLE, DrawingTool.ELLIOTT_DOUBLE, DrawingTool.ELLIOTT_TRIPLE)),
        ToolSection("周期", listOf(DrawingTool.CYCLIC_LINES, DrawingTool.TIME_CYCLES, DrawingTool.SINE_LINE)),
    )),
    FORECAST("预测和测量工具", listOf(
        ToolSection("预测", listOf(DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION, DrawingTool.FORECAST, DrawingTool.BARS_PATTERN, DrawingTool.GHOST_FEED, DrawingTool.PROJECTION)),
        ToolSection("成交量", listOf(DrawingTool.ANCHORED_VWAP, DrawingTool.FIXED_RANGE_VOLUME_PROFILE, DrawingTool.ANCHORED_VOLUME_PROFILE)),
        ToolSection("测量", listOf(DrawingTool.PRICE_RANGE, DrawingTool.DATE_RANGE, DrawingTool.DATE_PRICE_RANGE)),
    )),
    SHAPE("几何形状", listOf(
        ToolSection("画笔", listOf(DrawingTool.BRUSH, DrawingTool.HIGHLIGHTER)),
        ToolSection("箭头", listOf(DrawingTool.ARROW_MARKER, DrawingTool.ARROW, DrawingTool.ARROW_UP, DrawingTool.ARROW_DOWN)),
        ToolSection("形状", listOf(DrawingTool.RECTANGLE, DrawingTool.ROTATED_RECTANGLE, DrawingTool.PATH, DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.POLYLINE,
            DrawingTool.TRIANGLE, DrawingTool.ARC, DrawingTool.CURVE, DrawingTool.DOUBLE_CURVE)),
    )),
    ANNOTATION("注释工具", listOf(
        ToolSection("文本和注释", listOf(DrawingTool.TEXT, DrawingTool.NOTE, DrawingTool.PRICE_NOTE, DrawingTool.PIN, DrawingTool.TABLE, DrawingTool.CALLOUT, DrawingTool.COMMENT,
            DrawingTool.PRICE_LABEL, DrawingTool.SIGNPOST, DrawingTool.FLAG)),
        ToolSection("内容", listOf(DrawingTool.IMAGE, DrawingTool.POST, DrawingTool.IDEA)),
    ));

    val tools: List<DrawingTool> get() = sections.flatMap { it.tools }
}

/** The pointer's look over the chart; the eraser and the fading demonstration pen are cursors too, as on TradingView. */
@Serializable
enum class ChartCursor(val label: String) { CROSS("十字线"), DOT("圆点"), ARROW("箭头"), DEMONSTRATION("荧光笔"), ERASER("橡皮擦") }

/** Weak snaps only when the pointer is near a bar's open, high, low or close; strong always takes the nearest of them. */
@Serializable
enum class MagnetMode(val label: String) { OFF("关闭"), WEAK("弱磁铁"), STRONG("强磁铁") }

@Serializable
enum class DrawingDash(val label: String) { SOLID("实线"), DOTTED("点线"), DASHED("虚线") }

/** Interval families a drawing can be hidden on. */
@Serializable
enum class IntervalBucket(val label: String) {
    MINUTES("分钟"), HOURS("小时"), DAYS("日线及以上");
    companion object { fun of(minutes: Int) = when { minutes < 60 -> MINUTES; minutes < 1440 -> HOURS; else -> DAYS } }
}

/** Absolute bar time and price in the instrument's integer price units; freehand strokes keep fractions of both. */
@Serializable
data class DrawingPoint(val timeMs: Long, val price: Double)

/** One level of a Fibonacci or Gann tool; [value2] is the second ratio of a Gann fan line or arc. */
@Serializable
data class DrawingLevel(val value: Double, val color: String, val visible: Boolean = true, val value2: Double? = null)

@Serializable
data class DrawingStyle(
    val color: String = "#2962FF",
    val width: Float = 2f,
    val dash: DrawingDash = DrawingDash.SOLID,
    val fill: String? = null,
    val fillAlpha: Float = .2f,
    val textColor: String? = null,
    val fontSize: Int = 14,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val extendLeft: Boolean = false,
    val extendRight: Boolean = false,
    val leftArrow: Boolean = false,
    val rightArrow: Boolean = false,
    /** Level ratios and pattern letters. */
    val showLabels: Boolean = true,
    /** Price beside a level, or the axis tag of a horizontal line. */
    val showPrices: Boolean = false,
    val showStats: Boolean = false,
    val showMiddle: Boolean = false,
    val reverse: Boolean = false,
)

@Serializable
data class Drawing(
    val id: String,
    val tool: DrawingTool,
    val points: List<DrawingPoint>,
    val style: DrawingStyle = DrawingStyle(),
    val levels: List<DrawingLevel> = emptyList(),
    val levels2: List<DrawingLevel> = emptyList(),
    val levels3: List<DrawingLevel> = emptyList(),
    val text: String = "",
    /** Tool settings that are single numbers: rows of a volume profile, account size of a position, and so on. */
    val params: Map<String, Double> = emptyMap(),
    val table: List<List<String>> = emptyList(),
    /** Emoji or symbol of an icon or signpost. */
    val glyph: String? = null,
    val imageId: String? = null,
    val link: String = "",
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val hiddenIntervals: Set<IntervalBucket> = emptySet(),
) {
    fun param(key: String, fallback: Double = 0.0): Double = params[key] ?: fallback
}

private fun level(value: Double, color: String, visible: Boolean = true, value2: Double? = null) = DrawingLevel(value, color, visible, value2)

/** Default levels, read from TradingView's own defaults so a new drawing starts the same way. */
private val FIB_LEVELS = listOf(
    level(0.0, "#808080"), level(.236, "#F23645"), level(.382, "#FF9800"), level(.5, "#4CAF50"), level(.618, "#089981"), level(.786, "#00BCD4"), level(1.0, "#808080"),
    level(1.618, "#2962FF"), level(2.618, "#F23645"), level(3.618, "#9C27B0"), level(4.236, "#E91E63"),
    level(1.272, "#FF9800", false), level(1.414, "#F23645", false), level(2.0, "#089981", false), level(2.272, "#FF9800", false), level(2.414, "#4CAF50", false),
    level(3.0, "#00BCD4", false), level(3.272, "#808080", false), level(3.414, "#2962FF", false), level(4.0, "#F23645", false), level(4.272, "#9C27B0", false),
    level(4.414, "#E91E63", false), level(4.618, "#FF9800", false), level(4.764, "#089981", false),
)
private val FORK_LEVELS = listOf(
    level(.25, "#FFB74D", false), level(.382, "#81C784", false), level(.5, "#089981"), level(.618, "#089981", false), level(.75, "#00BCD4", false),
    level(1.0, "#2962FF"), level(1.5, "#9C27B0", false), level(1.75, "#E91E63", false), level(2.0, "#F77C80", false),
)
private val FAN_LEVELS = listOf(level(0.0, "#808080"), level(.25, "#FF9800"), level(.382, "#00BCD4"), level(.5, "#4CAF50"), level(.618, "#089981"), level(.75, "#2962FF"), level(1.0, "#808080"))
private val CIRCLE_LEVELS = listOf(
    level(.236, "#F23645"), level(.382, "#FF9800"), level(.5, "#089981"), level(.618, "#4CAF50"), level(.786, "#00BCD4"), level(1.0, "#808080"),
    level(1.618, "#2962FF"), level(2.618, "#E91E63"), level(3.618, "#2962FF"), level(4.236, "#E91E63"), level(4.618, "#F23645"),
)
private val WEDGE_LEVELS = listOf(
    level(.236, "#F23645"), level(.382, "#FF9800"), level(.5, "#4CAF50"), level(.618, "#089981"), level(.786, "#00BCD4"), level(1.0, "#808080"),
    level(1.618, "#2962FF", false), level(2.618, "#F23645", false), level(3.618, "#673AB7", false), level(4.236, "#E91E63", false), level(4.618, "#E91E63", false),
)
private val TIME_ZONE_LEVELS = listOf(0.0 to "#808080").plus(listOf(1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 21.0, 34.0, 55.0, 89.0).map { it to "#2962FF" }).map { (v, c) -> level(v, c) }
private val TREND_TIME_LEVELS = listOf(
    level(0.0, "#808080"), level(.382, "#F23645"), level(.5, "#81C784", false), level(.618, "#4CAF50"), level(1.0, "#089981"), level(1.382, "#00BCD4"),
    level(1.618, "#808080"), level(2.0, "#2962FF"), level(2.382, "#E91E63"), level(2.618, "#9C27B0"), level(3.0, "#673AB7"),
)
private val GANN_FAN_LEVELS = listOf(
    level(8.0, "#FF9800", value2 = 1.0), level(4.0, "#089981", value2 = 1.0), level(3.0, "#4CAF50", value2 = 1.0), level(2.0, "#089981", value2 = 1.0), level(1.0, "#00BCD4", value2 = 1.0),
    level(1.0, "#2962FF", value2 = 2.0), level(1.0, "#9C27B0", value2 = 3.0), level(1.0, "#E91E63", value2 = 4.0), level(1.0, "#F23645", value2 = 8.0),
)
private val CHANNEL_LEVELS = listOf(level(-.25, "#2962FF", false), level(0.0, "#2962FF"), level(.25, "#2962FF", false), level(.5, "#2962FF"), level(.75, "#2962FF", false), level(1.0, "#2962FF"), level(1.25, "#2962FF", false))
private val SQUARE_GRID = listOf("#808080", "#FF9800", "#00BCD4", "#4CAF50", "#089981", "#808080").mapIndexed { i, c -> level(i.toDouble(), c) }
private val SQUARE_FANS = listOf(
    level(8.0, "#B39DDB", false, 1.0), level(5.0, "#F23645", false, 1.0), level(4.0, "#808080", false, 1.0), level(3.0, "#FF9800", false, 1.0), level(2.0, "#00BCD4", true, 1.0), level(1.0, "#4CAF50", true, 1.0),
    level(1.0, "#089981", true, 2.0), level(1.0, "#089981", false, 3.0), level(1.0, "#2962FF", false, 4.0), level(1.0, "#9575CD", false, 5.0), level(1.0, "#B39DDB", false, 8.0),
)
private val SQUARE_ARCS = listOf(
    level(1.0, "#FF9800", true, 0.0), level(1.0, "#FF9800", true, 1.0), level(1.5, "#FF9800", true, 0.0), level(2.0, "#00BCD4", true, 0.0), level(2.0, "#00BCD4", true, 1.0), level(3.0, "#4CAF50", true, 0.0),
    level(3.0, "#4CAF50", true, 1.0), level(4.0, "#089981", true, 0.0), level(4.0, "#089981", true, 1.0), level(5.0, "#2962FF", true, 0.0), level(5.0, "#2962FF", true, 1.0),
)

/** A fresh drawing of [tool] with TradingView's default look. */
internal fun newDrawing(tool: DrawingTool, id: String, points: List<DrawingPoint>, glyph: String? = null): Drawing {
    val fibTrend = DrawingStyle("#808080", dash = DrawingDash.DASHED, fill = "#808080", showPrices = true)
    val base = Drawing(id, tool, points, glyph = glyph)
    return when (tool) {
        DrawingTool.TREND_LINE -> base
        DrawingTool.RAY -> base.copy(style = DrawingStyle(extendRight = true))
        DrawingTool.INFO_LINE -> base.copy(style = DrawingStyle(showStats = true))
        DrawingTool.EXTENDED_LINE -> base.copy(style = DrawingStyle(extendLeft = true, extendRight = true))
        DrawingTool.TREND_ANGLE -> base.copy(style = DrawingStyle(fontSize = 12))
        DrawingTool.HORIZONTAL_LINE, DrawingTool.HORIZONTAL_RAY, DrawingTool.VERTICAL_LINE, DrawingTool.CROSS_LINE -> base.copy(style = DrawingStyle(showPrices = true, fontSize = 12))
        DrawingTool.PARALLEL_CHANNEL -> base.copy(style = DrawingStyle(fill = "#2962FF"), levels = CHANNEL_LEVELS)
        DrawingTool.REGRESSION_TREND -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = .3f, showLabels = true), params = mapOf("upper" to 2.0, "lower" to 2.0))
        DrawingTool.FLAT_TOP_BOTTOM -> base.copy(style = DrawingStyle("#FF9800", fill = "#FF9800"))
        DrawingTool.DISJOINT_CHANNEL -> base.copy(style = DrawingStyle("#089981", fill = "#089981"))
        DrawingTool.PITCHFORK, DrawingTool.SCHIFF_PITCHFORK, DrawingTool.MODIFIED_SCHIFF_PITCHFORK, DrawingTool.INSIDE_PITCHFORK ->
            base.copy(style = DrawingStyle("#F23645", fill = "#2962FF"), levels = FORK_LEVELS)
        DrawingTool.FIB_RETRACEMENT, DrawingTool.FIB_EXTENSION -> base.copy(style = fibTrend, levels = FIB_LEVELS)
        DrawingTool.FIB_CHANNEL -> base.copy(style = fibTrend.copy(dash = DrawingDash.SOLID), levels = FIB_LEVELS)
        DrawingTool.FIB_TIME_ZONE -> base.copy(style = DrawingStyle("#808080", width = 1f, dash = DrawingDash.DASHED), levels = TIME_ZONE_LEVELS)
        DrawingTool.FIB_SPEED_FAN -> base.copy(style = DrawingStyle("#153899", width = 1f, fill = "#808080"), levels = FAN_LEVELS, levels2 = FAN_LEVELS)
        DrawingTool.FIB_TREND_TIME -> base.copy(style = DrawingStyle("#808080", dash = DrawingDash.DASHED, fill = "#808080"), levels = TREND_TIME_LEVELS)
        DrawingTool.FIB_CIRCLES, DrawingTool.FIB_SPEED_ARCS -> base.copy(style = DrawingStyle("#808080", dash = DrawingDash.DASHED, fill = "#808080"), levels = CIRCLE_LEVELS)
        DrawingTool.FIB_SPIRAL -> base.copy(style = DrawingStyle("#00BCD4"))
        DrawingTool.FIB_WEDGE -> base.copy(style = DrawingStyle("#808080", fill = "#808080"), levels = WEDGE_LEVELS)
        DrawingTool.PITCHFAN -> base.copy(style = DrawingStyle("#F23645", fill = "#2962FF"), levels = FORK_LEVELS.map { if (it.value == .5) it.copy(color = "#00BCD4") else it })
        DrawingTool.GANN_BOX -> base.copy(style = DrawingStyle("#153899", fill = "#808080"), levels = FAN_LEVELS, levels2 = FAN_LEVELS)
        DrawingTool.GANN_SQUARE_FIXED, DrawingTool.GANN_SQUARE -> base.copy(style = DrawingStyle("#808080", fill = "#808080"), levels = SQUARE_GRID, levels2 = SQUARE_FANS, levels3 = SQUARE_ARCS)
        DrawingTool.GANN_FAN -> base.copy(style = DrawingStyle("#808080", fill = "#808080"), levels = GANN_FAN_LEVELS)
        DrawingTool.XABCD, DrawingTool.CYPHER -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = .15f, textColor = "#FFFFFF", fontSize = 12))
        DrawingTool.HEAD_SHOULDERS -> base.copy(style = DrawingStyle("#089981", fill = "#089981", fillAlpha = .15f, textColor = "#FFFFFF", fontSize = 12))
        DrawingTool.ABCD -> base.copy(style = DrawingStyle("#089981", textColor = "#FFFFFF", fontSize = 12))
        DrawingTool.TRIANGLE_PATTERN -> base.copy(style = DrawingStyle("#673AB7", fill = "#673AB7", fillAlpha = .15f, textColor = "#FFFFFF", fontSize = 12))
        DrawingTool.THREE_DRIVES -> base.copy(style = DrawingStyle("#673AB7", textColor = "#FFFFFF", fontSize = 12))
        DrawingTool.ELLIOTT_IMPULSE, DrawingTool.ELLIOTT_CORRECTION -> base.copy(style = DrawingStyle("#3D85C6"), params = mapOf("degree" to 4.0))
        DrawingTool.ELLIOTT_TRIANGLE -> base.copy(style = DrawingStyle("#FF9800"), params = mapOf("degree" to 4.0))
        DrawingTool.ELLIOTT_DOUBLE, DrawingTool.ELLIOTT_TRIPLE -> base.copy(style = DrawingStyle("#6AA84F"), params = mapOf("degree" to 4.0))
        DrawingTool.CYCLIC_LINES -> base.copy(style = DrawingStyle("#80CCDB"))
        DrawingTool.TIME_CYCLES -> base.copy(style = DrawingStyle("#159980", fill = "#6AA84F", fillAlpha = .5f))
        DrawingTool.SINE_LINE -> base.copy(style = DrawingStyle("#159980"))
        DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION -> base.copy(style = DrawingStyle("#808080", width = 1f, textColor = "#FFFFFF", fontSize = 12, showPrices = true),
            params = mapOf("accountSize" to 1000.0, "riskPercent" to 25.0))
        DrawingTool.FORECAST -> base.copy(style = DrawingStyle())
        DrawingTool.BARS_PATTERN -> base.copy(params = mapOf("mode" to 0.0, "mirrored" to 0.0, "flipped" to 0.0))
        DrawingTool.GHOST_FEED -> base.copy(params = mapOf("averageHl" to 20.0, "variance" to 50.0))
        DrawingTool.PROJECTION -> base.copy(style = DrawingStyle("#9C9C9C", fill = "#2962FF"), levels = listOf(level(1.0, "#808080")))
        DrawingTool.ANCHORED_VWAP -> base.copy(style = DrawingStyle("#1E88E5", width = 1f, fill = "#4CAF50", fillAlpha = .05f, showPrices = true), params = mapOf("band1" to 1.0, "band2" to 0.0, "band3" to 0.0))
        DrawingTool.FIXED_RANGE_VOLUME_PROFILE, DrawingTool.ANCHORED_VOLUME_PROFILE -> base.copy(style = DrawingStyle("#DBDBDB", fill = "#37A6EF", fillAlpha = .06f, showPrices = false),
            params = mapOf("rows" to 24.0, "valueArea" to 70.0, "widthPercent" to 30.0))
        DrawingTool.PRICE_RANGE, DrawingTool.DATE_RANGE, DrawingTool.DATE_PRICE_RANGE, DrawingTool.MEASURE -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = .15f, textColor = "#FFFFFF", fontSize = 12, showStats = true))
        DrawingTool.BRUSH -> base.copy(style = DrawingStyle("#00BCD4"))
        DrawingTool.HIGHLIGHTER -> base.copy(style = DrawingStyle("#F23645", width = 20f, fillAlpha = .2f))
        DrawingTool.ARROW_MARKER -> base.copy(style = DrawingStyle("#1E53E5", fontSize = 16, bold = true))
        DrawingTool.ARROW -> base.copy(style = DrawingStyle(rightArrow = true))
        DrawingTool.ARROW_UP -> base.copy(style = DrawingStyle("#089981"))
        DrawingTool.ARROW_DOWN -> base.copy(style = DrawingStyle("#CC2F3C"))
        DrawingTool.RECTANGLE -> base.copy(style = DrawingStyle("#9C27B0", fill = "#9C27B0"))
        DrawingTool.ROTATED_RECTANGLE -> base.copy(style = DrawingStyle("#4CAF50", fill = "#4CAF50"))
        DrawingTool.PATH -> base.copy(style = DrawingStyle(rightArrow = true))
        DrawingTool.CIRCLE -> base.copy(style = DrawingStyle("#FF9800", fill = "#FF9800"))
        DrawingTool.ELLIPSE -> base.copy(style = DrawingStyle("#F23645", fill = "#F23645"))
        DrawingTool.POLYLINE -> base.copy(style = DrawingStyle("#00BCD4", fill = "#00BCD4"))
        DrawingTool.TRIANGLE -> base.copy(style = DrawingStyle("#089981", fill = "#089981"))
        DrawingTool.ARC -> base.copy(style = DrawingStyle("#E91E63", fill = "#E91E63"))
        DrawingTool.CURVE -> base
        DrawingTool.DOUBLE_CURVE -> base.copy(style = DrawingStyle("#673AB7"))
        DrawingTool.TEXT -> base.copy(style = DrawingStyle())
        DrawingTool.NOTE -> base.copy(style = DrawingStyle(fill = "#222222", fillAlpha = 1f, textColor = "#DBDBDB"))
        DrawingTool.PRICE_NOTE -> base.copy(style = DrawingStyle(showPrices = true))
        DrawingTool.PIN -> base.copy(style = DrawingStyle(fill = "#222222", fillAlpha = 1f, textColor = "#DBDBDB"))
        DrawingTool.TABLE -> base.copy(style = DrawingStyle("#555555", width = 1f, fill = "#000000", fillAlpha = 1f, textColor = "#DBDBDB"),
            table = List(3) { List(3) { "" } }, params = mapOf("screenX" to .25, "screenY" to .25))
        DrawingTool.CALLOUT -> base.copy(style = DrawingStyle("#0097A7", fill = "#0097A7", fillAlpha = .7f, textColor = "#FFFFFF"))
        DrawingTool.COMMENT -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = 1f, textColor = "#FFFFFF", fontSize = 16))
        DrawingTool.PRICE_LABEL -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = 1f, textColor = "#FFFFFF", bold = true))
        DrawingTool.SIGNPOST -> base.copy(style = DrawingStyle(fill = "#2962FF", fillAlpha = 1f, textColor = "#FFFFFF", fontSize = 12), params = mapOf("position" to 27.0))
        DrawingTool.FLAG -> base.copy(style = DrawingStyle())
        DrawingTool.IMAGE -> base.copy(params = mapOf("width" to 200.0, "height" to 150.0))
        DrawingTool.POST, DrawingTool.IDEA -> base.copy(style = DrawingStyle(fill = "#222222", fillAlpha = 1f, textColor = "#DBDBDB", fontSize = 12), params = mapOf("position" to 27.0))
        DrawingTool.ICON -> base.copy(params = mapOf("size" to 72.0, "angle" to 0.0))
    }
}

/** Tools whose text is asked for as soon as they are placed. */
internal val DrawingTool.promptsForText: Boolean get() = this in setOf(DrawingTool.TEXT, DrawingTool.NOTE, DrawingTool.PIN, DrawingTool.CALLOUT, DrawingTool.COMMENT, DrawingTool.POST, DrawingTool.IDEA, DrawingTool.PRICE_NOTE)

/** Tools with editable text in their settings. */
internal val DrawingTool.hasText: Boolean get() = promptsForText || this in setOf(
    DrawingTool.TREND_LINE, DrawingTool.RAY, DrawingTool.INFO_LINE, DrawingTool.EXTENDED_LINE, DrawingTool.ARROW, DrawingTool.HORIZONTAL_LINE, DrawingTool.HORIZONTAL_RAY, DrawingTool.VERTICAL_LINE,
    DrawingTool.PARALLEL_CHANNEL, DrawingTool.FLAT_TOP_BOTTOM, DrawingTool.DISJOINT_CHANNEL, DrawingTool.RECTANGLE, DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.ARROW_MARKER,
    DrawingTool.ARROW_UP, DrawingTool.ARROW_DOWN, DrawingTool.SIGNPOST)

/** Tools that can be filled; [DrawingStyle.fill] null turns the filling off. */
internal val DrawingTool.hasFill: Boolean get() = this in setOf(
    DrawingTool.PARALLEL_CHANNEL, DrawingTool.REGRESSION_TREND, DrawingTool.FLAT_TOP_BOTTOM, DrawingTool.DISJOINT_CHANNEL, DrawingTool.PITCHFORK, DrawingTool.SCHIFF_PITCHFORK, DrawingTool.MODIFIED_SCHIFF_PITCHFORK,
    DrawingTool.INSIDE_PITCHFORK, DrawingTool.FIB_RETRACEMENT, DrawingTool.FIB_EXTENSION, DrawingTool.FIB_CHANNEL, DrawingTool.FIB_SPEED_FAN, DrawingTool.FIB_TREND_TIME, DrawingTool.FIB_CIRCLES, DrawingTool.FIB_SPEED_ARCS,
    DrawingTool.FIB_WEDGE, DrawingTool.PITCHFAN, DrawingTool.GANN_BOX, DrawingTool.GANN_SQUARE_FIXED, DrawingTool.GANN_SQUARE, DrawingTool.GANN_FAN, DrawingTool.XABCD, DrawingTool.CYPHER, DrawingTool.HEAD_SHOULDERS,
    DrawingTool.TRIANGLE_PATTERN, DrawingTool.TIME_CYCLES, DrawingTool.PROJECTION, DrawingTool.ANCHORED_VWAP, DrawingTool.FIXED_RANGE_VOLUME_PROFILE, DrawingTool.ANCHORED_VOLUME_PROFILE, DrawingTool.PRICE_RANGE,
    DrawingTool.DATE_RANGE, DrawingTool.DATE_PRICE_RANGE, DrawingTool.BRUSH, DrawingTool.RECTANGLE, DrawingTool.ROTATED_RECTANGLE, DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.POLYLINE, DrawingTool.TRIANGLE,
    DrawingTool.ARC, DrawingTool.CURVE, DrawingTool.DOUBLE_CURVE, DrawingTool.NOTE, DrawingTool.PIN, DrawingTool.TABLE, DrawingTool.CALLOUT, DrawingTool.COMMENT, DrawingTool.PRICE_LABEL, DrawingTool.SIGNPOST,
    DrawingTool.POST, DrawingTool.IDEA, DrawingTool.TEXT)

/** Tools whose line can run on past their anchors. */
internal val DrawingTool.hasExtend: Boolean get() = this in setOf(
    DrawingTool.TREND_LINE, DrawingTool.RAY, DrawingTool.INFO_LINE, DrawingTool.EXTENDED_LINE, DrawingTool.ARROW, DrawingTool.PARALLEL_CHANNEL, DrawingTool.REGRESSION_TREND, DrawingTool.FLAT_TOP_BOTTOM,
    DrawingTool.DISJOINT_CHANNEL, DrawingTool.PITCHFORK, DrawingTool.SCHIFF_PITCHFORK, DrawingTool.MODIFIED_SCHIFF_PITCHFORK, DrawingTool.INSIDE_PITCHFORK, DrawingTool.FIB_RETRACEMENT, DrawingTool.FIB_EXTENSION,
    DrawingTool.FIB_CHANNEL, DrawingTool.RECTANGLE, DrawingTool.CURVE, DrawingTool.DOUBLE_CURVE)

/** Drawn relative to the plot, so they stay put when the chart scrolls. */
internal val DrawingTool.screenAnchored: Boolean get() = this == DrawingTool.TABLE

/** Elliott wave degrees and how each one writes its labels; index stored in the drawing's `degree` parameter. */
internal enum class ElliottDegree(val label: String, val style: Int) {
    GRAND_SUPERCYCLE("超级大循环", 0), SUPERCYCLE("大循环", 1), CYCLE("循环", 2), PRIMARY("基本浪", 3), INTERMEDIATE("中级浪", 4),
    MINOR("次级浪", 5), MINUTE("细浪", 6), MINUETTE("微浪", 7), SUBMINUETTE("亚微浪", 8),
}
