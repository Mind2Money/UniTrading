package com.unitrading.chart.drawing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line diagrams of the drawing tools on a 28-unit grid. Material Icons has no glyphs for pitchforks, Fibonacci fans or Elliott
 * waves, so these are the one exception to the icons-from-AppIcons rule; each one is a sketch of what the tool draws.
 */
internal object DrawingIcons {
    private fun dot(x: Float, y: Float, r: Float = 1.7f) = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun ring(x: Float, y: Float, r: Float) = dot(x, y, r)
    private fun zig(vararg xy: Int) = "M" + xy.toList().chunked(2).joinToString("L") { (x, y) -> "$x $y" }
    private fun dots(vararg xy: Int) = xy.toList().chunked(2).joinToString("") { (x, y) -> dot(x.toFloat(), y.toFloat()) }

    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 28.dp, 28.dp, 28f, 28f).apply {
        paths.forEach { path ->
            addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    private val cache = HashMap<String, ImageVector>()
    private fun cached(name: String, vararg paths: String) = cache.getOrPut(name) { icon(name, *paths) }

    val Cross get() = cached("cross", "M14 5V23M5 14H23")
    val Dot get() = cached("dot", ring(14f, 14f, 3f))
    val ArrowCursor get() = cached("arrowCursor", "M9 5L9 22L13.5 18L16.5 24L19 23L16 17L22 17Z")
    val Demonstration get() = cached("demo", "M6 22L8 17L18 7L21 10L11 20ZM16 9L19 12", "M14 23C17 21 19 25 23 22")
    val Eraser get() = cached("eraser", "M8 20L16 8L23 13L15 24H9ZM12 14L19 19")
    val Smile get() = cached("smile", ring(14f, 14f, 9f), dot(11f, 12f, .8f), dot(17f, 12f, .8f), "M10 17C12 20 16 20 18 17")
    val Measure get() = cached("measure", "M5 18L18 5L23 10L10 23ZM9 14L11 16M12 11L14 13M15 8L17 10")
    val Magnet get() = cached("magnet", "M7 6H11V15A3 3 0 0 0 17 15V6H21V15A7 7 0 0 1 7 15ZM7 10H11M17 10H21")
    val KeepDrawing get() = cached("keep", "M6 22L8 17L17 8L20 11L11 20ZM18 18V24M15 21H21")
    val Locked get() = cached("locked", "M8 13H20V23H8ZM10 13V9A4 4 0 0 1 18 9V13")
    val Unlocked get() = cached("unlocked", "M8 13H20V23H8ZM10 13V9A4 4 0 0 1 17.5 7")
    val Clone get() = cached("clone", "M9 9H23V23H9ZM5 19V5H19")

    fun of(tool: DrawingTool): ImageVector = when (tool) {
        DrawingTool.TREND_LINE -> cached("trend", "M6 22L22 6", dots(6, 22, 22, 6))
        DrawingTool.RAY -> cached("ray", "M6 22L25 3", dots(6, 22, 14, 14))
        DrawingTool.INFO_LINE -> cached("info", "M6 22L22 6", dots(6, 22, 22, 6), "M15 16H25V24H15Z")
        DrawingTool.EXTENDED_LINE -> cached("extended", "M3 25L25 3", dots(11, 17, 17, 11))
        DrawingTool.TREND_ANGLE -> cached("angle", "M6 22L22 8M6 22H25M14 22A8 8 0 0 0 12 16.8", dots(6, 22, 22, 8))
        DrawingTool.HORIZONTAL_LINE -> cached("hline", "M3 14H25", dots(14, 14))
        DrawingTool.HORIZONTAL_RAY -> cached("hray", "M10 14H26", dots(9, 14))
        DrawingTool.VERTICAL_LINE -> cached("vline", "M14 3V25", dots(14, 14))
        DrawingTool.CROSS_LINE -> cached("cline", "M3 14H25M14 3V25", dots(14, 14))
        DrawingTool.PARALLEL_CHANNEL -> cached("channel", "M4 17L19 5M9 24L24 12", dots(4, 17, 19, 5, 24, 12))
        DrawingTool.REGRESSION_TREND -> cached("regression", "M4 13L24 3M4 19L24 9M4 25L24 15", dots(4, 19, 24, 9))
        DrawingTool.FLAT_TOP_BOTTOM -> cached("flat", "M4 8H24M4 22L24 13", dots(4, 22, 24, 13, 14, 8))
        DrawingTool.DISJOINT_CHANNEL -> cached("disjoint", "M4 10L24 4M4 18L24 25", dots(4, 10, 24, 4, 4, 18))
        DrawingTool.PITCHFORK -> cached("fork", "M4 14H12M12 7V21M12 7H25M12 14H25M12 21H25", dots(4, 14, 12, 7, 12, 21))
        DrawingTool.SCHIFF_PITCHFORK -> cached("schiff", "M4 19L12 14M12 7V21M12 7H25M12 14H25M12 21H25", dots(4, 19, 12, 7, 12, 21))
        DrawingTool.MODIFIED_SCHIFF_PITCHFORK -> cached("modschiff", "M7 17L12 14M12 7V21M12 7H25M12 14H25M12 21H25", dots(7, 17, 12, 7, 12, 21))
        DrawingTool.INSIDE_PITCHFORK -> cached("insidefork", "M4 14H25M12 10H25M12 18H25M12 10V18", dots(4, 14, 12, 10, 12, 18))
        DrawingTool.FIB_RETRACEMENT -> cached("fib", "M3 6H25M3 11H25M3 16H25M3 22H25", dots(6, 22, 22, 6))
        DrawingTool.FIB_EXTENSION -> cached("fibext", "M3 5H25M3 10H25M3 15H25", zig(4, 23, 11, 15, 16, 22), dots(4, 23, 11, 15, 16, 22))
        DrawingTool.FIB_CHANNEL -> cached("fibch", "M3 12L17 3M3 18L25 6M6 25L25 12", dots(3, 12, 17, 3))
        DrawingTool.FIB_TIME_ZONE -> cached("fibtz", "M5 3V25M8 3V25M12 3V25M18 3V25M26 3V25")
        DrawingTool.FIB_SPEED_FAN -> cached("fibfan", "M4 24L25 5M4 24L25 12M4 24L25 19M4 24L11 3M4 24L18 3", dots(4, 24))
        DrawingTool.FIB_TREND_TIME -> cached("fibtime", "M14 3V25M18 3V25M24 3V25", zig(3, 22, 8, 10, 12, 18), dots(3, 22, 8, 10, 12, 18))
        DrawingTool.FIB_CIRCLES -> cached("fibcircles", ring(14f, 14f, 3f), ring(14f, 14f, 7f), ring(14f, 14f, 11f))
        DrawingTool.FIB_SPIRAL -> cached("spiral", "M14 14C14 12 17 12 17 15C17 18 12 19 10 16C8 12 12 7 17 8C23 9 25 16 22 21C18 26 9 26 5 21")
        DrawingTool.FIB_SPEED_ARCS -> cached("fibarcs", "M4 24A8 8 0 0 1 12 16M4 24A14 14 0 0 1 18 10M4 24A20 20 0 0 1 24 4", dots(4, 24))
        DrawingTool.FIB_WEDGE -> cached("wedge", "M4 24L25 12M4 24L14 3M13 19A10 10 0 0 0 9 14M20 15A18 18 0 0 0 12 8", dots(4, 24))
        DrawingTool.PITCHFAN -> cached("pitchfan", "M4 14L25 4M4 14H25M4 14L25 24M25 4V24", dots(4, 14))
        DrawingTool.GANN_BOX -> cached("gannbox", "M5 6H23V22H5ZM5 11H23M5 16H23M11 6V22M17 6V22")
        DrawingTool.GANN_SQUARE_FIXED -> cached("gannfixed", "M5 5H23V23H5ZM5 23L23 5M5 23A18 18 0 0 1 23 5")
        DrawingTool.GANN_SQUARE -> cached("ganns", "M5 5H23V23H5ZM5 23L23 5M5 23L23 14M5 23L14 5")
        DrawingTool.GANN_FAN -> cached("gannfan", "M4 24L24 4M4 24L25 14M4 24L14 3M4 24L26 20M4 24L8 3", dots(4, 24))
        DrawingTool.XABCD -> cached("xabcd", zig(4, 20, 9, 7, 14, 17, 19, 9, 24, 21), dots(4, 20, 9, 7, 14, 17, 19, 9, 24, 21))
        DrawingTool.CYPHER -> cached("cypher", zig(4, 22, 9, 8, 13, 15, 19, 5, 24, 18), dots(4, 22, 9, 8, 13, 15, 19, 5, 24, 18))
        DrawingTool.HEAD_SHOULDERS -> cached("hs", zig(3, 21, 8, 13, 11, 18, 14, 6, 17, 18, 20, 13, 25, 21), "M3 18H25")
        DrawingTool.ABCD -> cached("abcd", zig(5, 22, 11, 8, 17, 16, 23, 5), dots(5, 22, 11, 8, 17, 16, 23, 5))
        DrawingTool.TRIANGLE_PATTERN -> cached("tripat", zig(4, 6, 9, 21, 15, 10, 21, 18, 24, 14), "M4 6L24 14M9 21L24 14")
        DrawingTool.THREE_DRIVES -> cached("drives", zig(3, 24, 8, 16, 11, 20, 16, 11, 19, 15, 25, 5), dots(8, 16, 16, 11, 25, 5))
        DrawingTool.ELLIOTT_IMPULSE -> cached("eimpulse", zig(3, 24, 7, 15, 10, 19, 17, 6, 20, 11, 25, 3), dots(7, 15, 17, 6, 25, 3))
        DrawingTool.ELLIOTT_CORRECTION -> cached("ecorr", zig(4, 5, 11, 18, 16, 11, 24, 23), dots(11, 18, 16, 11, 24, 23))
        DrawingTool.ELLIOTT_TRIANGLE -> cached("etri", zig(3, 21, 8, 6, 12, 19, 17, 9, 21, 16, 25, 12), dots(8, 6, 12, 19, 17, 9, 21, 16))
        DrawingTool.ELLIOTT_DOUBLE -> cached("edouble", zig(3, 6, 10, 19, 15, 11, 25, 23), dots(10, 19, 15, 11, 25, 23))
        DrawingTool.ELLIOTT_TRIPLE -> cached("etriple", zig(3, 5, 8, 15, 11, 10, 17, 20, 20, 14, 25, 24), dots(8, 15, 11, 10, 17, 20, 20, 14, 25, 24))
        DrawingTool.CYCLIC_LINES -> cached("cyclic", "M4 3V25M11 3V25M18 3V25M25 3V25")
        DrawingTool.TIME_CYCLES -> cached("timecycles", "M3 20A5 5 0 0 1 13 20A5 5 0 0 1 23 20M3 20H26")
        DrawingTool.SINE_LINE -> cached("sine", "M2 14C6 2 10 2 14 14C18 26 22 26 26 14")
        DrawingTool.LONG_POSITION -> cached("long", "M5 5H23V15H5ZM5 15V23H23V15M14 12V7M11 10L14 7L17 10")
        DrawingTool.SHORT_POSITION -> cached("short", "M5 5V13H23V5ZM5 13H23V23H5V13M14 16V21M11 18L14 21L17 18")
        DrawingTool.FORECAST -> cached("forecast", "M5 21C10 21 14 12 22 7M22 7H16M22 7V13", dots(5, 21))
        DrawingTool.BARS_PATTERN -> cached("bars", "M7 4V24M5 9H9V19H5ZM14 2V20M12 6H16V15H12ZM21 8V26M19 12H23V22H19Z")
        DrawingTool.GHOST_FEED -> cached("ghost", "M6 7H10V19H6ZM8 4V7M8 19V24M13 11H17V21H13ZM15 8V11M15 21V25M20 5H24V14H20ZM22 3V5M22 14V19")
        DrawingTool.PROJECTION -> cached("projection", "M5 22L24 8M5 22H24M5 22L13 3M24 22A19 19 0 0 0 13 3", dots(5, 22))
        DrawingTool.ANCHORED_VWAP -> cached("vwap", "M5 22C10 15 12 18 16 13S22 8 25 7", dots(5, 22))
        DrawingTool.FIXED_RANGE_VOLUME_PROFILE -> cached("frvp", "M4 3V25M24 3V25M4 7H11M4 11H17M4 15H20M4 19H12")
        DrawingTool.ANCHORED_VOLUME_PROFILE -> cached("avp", "M24 3V25M24 7H17M24 11H11M24 15H8M24 19H16", dots(5, 23))
        DrawingTool.PRICE_RANGE -> cached("pricerange", "M5 5H23M5 23H23M14 7V21M10 10L14 6L18 10M10 18L14 22L18 18")
        DrawingTool.DATE_RANGE -> cached("daterange", "M5 5V23M23 5V23M7 14H21M10 10L6 14L10 18M18 10L22 14L18 18")
        DrawingTool.DATE_PRICE_RANGE -> cached("dprange", "M5 5H23V23H5ZM14 8V20M8 14H20M11 11L14 8L17 11M17 11M11 17L8 14M17 17L20 14")
        DrawingTool.BRUSH -> cached("brush", "M4 21C7 15 10 24 13 18S19 10 24 6")
        DrawingTool.HIGHLIGHTER -> cached("highlighter", "M6 22L9 15L18 6L23 11L14 20L7 23ZM16 8L21 13", "M4 25H17")
        DrawingTool.ARROW_MARKER -> cached("amarker", "M5 22L14 13L11 10H22V21L19 18L10 27Z")
        DrawingTool.ARROW -> cached("arrow", "M5 23L23 5M15 5H23V13")
        DrawingTool.ARROW_UP -> cached("aup", "M14 4L22 13H17V24H11V13H6Z")
        DrawingTool.ARROW_DOWN -> cached("adown", "M14 24L22 15H17V4H11V15H6Z")
        DrawingTool.RECTANGLE -> cached("rect", "M5 8H23V20H5Z", dots(5, 8, 23, 20))
        DrawingTool.ROTATED_RECTANGLE -> cached("rotrect", "M4 15L14 5L24 13L14 23Z")
        DrawingTool.PATH -> cached("path", "M4 21L10 9L17 18L24 6M19 6H24V11", dots(10, 9, 17, 18))
        DrawingTool.CIRCLE -> cached("circle", ring(14f, 14f, 9.5f), dots(14, 14))
        DrawingTool.ELLIPSE -> cached("ellipse", "M3 14A11 7 0 1 0 25 14A11 7 0 1 0 3 14")
        DrawingTool.POLYLINE -> cached("polyline", "M5 20L9 8L18 12L24 5L22 22Z", dots(5, 20, 9, 8, 18, 12, 24, 5, 22, 22))
        DrawingTool.TRIANGLE -> cached("triangle", "M14 5L24 23H4Z", dots(14, 5, 24, 23, 4, 23))
        DrawingTool.ARC -> cached("arc", "M4 21A11 11 0 0 1 24 21", dots(4, 21, 24, 21, 14, 11))
        DrawingTool.CURVE -> cached("curve", "M4 22C8 6 18 6 24 22", dots(4, 22, 24, 22))
        DrawingTool.DOUBLE_CURVE -> cached("dcurve", "M3 20C8 6 12 6 14 14S20 22 25 8", dots(3, 20, 25, 8))
        DrawingTool.TEXT -> cached("text", "M7 7H21M14 7V22M11 22H17")
        DrawingTool.NOTE -> cached("note", "M6 5H22V18L17 23H6ZM22 18H17V23M9 10H19M9 14H16")
        DrawingTool.PRICE_NOTE -> cached("pricenote", "M4 24L12 16M12 8H24V16H12ZM15 11H21M15 13H19", dots(4, 24))
        DrawingTool.PIN -> cached("pin", "M14 25V17M8 17H20M10 17L11 7H17L18 17M9 7H19")
        DrawingTool.TABLE -> cached("table", "M4 6H24V22H4ZM4 11H24M4 16.5H24M11 6V22M17.5 6V22")
        DrawingTool.CALLOUT -> cached("callout", "M4 5H24V17H13L7 24V17H4Z")
        DrawingTool.COMMENT -> cached("comment", "M14 4C20 4 25 8 25 13S20 22 14 22C12 22 11 22 9 21L4 24L6 19C4 17 3 15 3 13C3 8 8 4 14 4Z")
        DrawingTool.PRICE_LABEL -> cached("pricelabel", "M4 14L10 7H24V21H10ZM13 14H21", dots(9, 14))
        DrawingTool.SIGNPOST -> cached("signpost", "M14 3V25M9 25H19M7 6H22L25 9.5L22 13H7Z")
        DrawingTool.FLAG -> cached("flag", "M7 3V25M7 4L22 9L7 15")
        DrawingTool.IMAGE -> cached("image", "M4 6H24V22H4ZM4 19L10 13L15 18L19 14L24 19", dots(18, 10))
        DrawingTool.POST -> cached("post", "M5 6H23V19H12L7 24V19H5ZM9 10H19M9 14H16")
        DrawingTool.IDEA -> cached("idea", "M11 20H17V17C20 15 21 12 21 10A7 7 0 0 0 7 10C7 12 8 15 11 17ZM12 23H16")
        DrawingTool.ICON -> Smile
        DrawingTool.MEASURE -> Measure
    }
}
