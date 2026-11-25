package com.unitrading.chart.pine

// The interpreter produces only chart data. It has no references to Compose, orders or broker sessions.
internal data class PineResult(
    val title: String,
    val overlay: Boolean,
    val plots: List<PinePlot>,
    val levels: List<PineLevel>,
    val fills: List<PineFill>,
    val marks: List<PineMark>,
    val drawings: List<PineDrawing>,
    val backgrounds: Array<Int?>,
    val barColors: Array<Int?>,
    val priceBackgrounds: Array<Int?> = emptyArray(),
)

internal data class PinePlot(
    val id: Int, val title: String, val values: DoubleArray, val colors: Array<Int?>,
    val style: PinePlotStyle, val width: Float, val offset: Int, val baseline: Double, val onPrice: Boolean,
    val displayed: Boolean = true,
)
internal data class PineLevel(
    val id: Int, val title: String, val value: Double, val color: Int?, val pattern: PineStroke, val width: Float, val onPrice: Boolean,
    val displayed: Boolean = true,
)
internal data class PineFill(val first: Int, val second: Int, val colors: Array<Int?>, val onPrice: Boolean)
internal data class PineMark(
    val index: Int, val value: Double, val location: PineLocation, val shape: PineShape, val color: Int?,
    val text: String, val textColor: Int?, val size: Float, val onPrice: Boolean,
)
internal enum class PinePlotStyle { LINE, LINE_BREAK, HISTOGRAM, COLUMNS, CIRCLES, CROSS }
internal enum class PineStroke { SOLID, DASHED, DOTTED }
internal enum class PineExtend { NONE, LEFT, RIGHT, BOTH }
internal enum class PineLocation { ABOVE_BAR, BELOW_BAR, TOP, BOTTOM, ABSOLUTE }
internal enum class PineShape { CIRCLE, TRIANGLE_UP, TRIANGLE_DOWN, ARROW_UP, ARROW_DOWN, CROSS, X_CROSS, DIAMOND, SQUARE, LABEL_UP, LABEL_DOWN, FLAG, TEXT }
internal enum class PineXloc { BAR_INDEX, BAR_TIME }
internal data class PineColor(val argb: Int)

internal sealed interface PineDrawing {
    val onPrice: Boolean

    data class Line(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double, val byTime: Boolean,
        val extend: PineExtend, val color: Int?, val pattern: PineStroke, val width: Float, override val onPrice: Boolean,
    ) : PineDrawing

    data class Box(
        val left: Double, val top: Double, val right: Double, val bottom: Double, val byTime: Boolean,
        val extend: PineExtend, val border: Int?, val borderPattern: PineStroke, val borderWidth: Float,
        val background: Int?, val text: String, val textColor: Int?, override val onPrice: Boolean,
    ) : PineDrawing

    data class Label(
        val x: Double, val y: Double, val byTime: Boolean, val location: PineLocation, val shape: PineShape,
        val color: Int?, val text: String, val textColor: Int?, val size: Float, override val onPrice: Boolean,
    ) : PineDrawing
}
