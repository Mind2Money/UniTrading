package com.unitrading.chart.pine

import com.unitrading.broker.Candle

private const val MAX_PINE_MARKS = 10_000
private const val MAX_PINE_OFFSET = 500

private data class VisualRef(val id: Int, val level: Boolean, val onPrice: Boolean)

/** One bounded output buffer per plotting call site, separate from series and from persistent drawing objects. */
internal class PineVisuals(private val overlay: Boolean, private val candles: List<Candle>, private val priceFactor: Double) {
    private val plots = mutableMapOf<Int, PinePlot>()
    private val levels = mutableMapOf<Int, PineLevel>()
    private val fills = mutableMapOf<Int, PineFill>()
    private val marks = mutableListOf<PineMark>()
    private val backgrounds = arrayOfNulls<Int>(candles.size)
    private val priceBackgrounds = if (overlay) backgrounds else arrayOfNulls<Int>(candles.size)
    private val barColors = arrayOfNulls<Int>(candles.size)

    fun result(title: String, drawings: List<PineDrawing>) = PineResult(title, overlay, plots.values.toList(), levels.values.toList(),
        fills.values.toList(), marks.sortedBy { it.index }, drawings, backgrounds, barColors, priceBackgrounds)

    fun call(name: String, id: Int, args: PineArgs, bar: Int): Any? = when (name) {
        "plot" -> plot(id, args, bar)
        "hline" -> hline(id, args)
        "fill" -> fill(id, args, bar)
        "plotshape", "plotchar" -> mark(name, args, bar)
        "bgcolor", "barcolor" -> tint(name, args, bar)
        else -> args.error("不支持的绘图函数 $name")
    }

    private fun plot(id: Int, args: PineArgs, bar: Int): VisualRef {
        val item = plots.getOrPut(id) {
            PinePlot(id, args.text("title", "Plot"), DoubleArray(candles.size) { Double.NaN }, arrayOfNulls(candles.size),
                args.enum("style", PinePlotStyle.LINE), args.integer("linewidth", 1, 1, 20).toFloat(), args.offset(),
                args.number("histbase", 0.0), args.onPrice(), args.displayed())
        }
        if (args.showsBar(bar)) {
            item.values[bar] = args.number("series")
            item.colors[bar] = args.color("color", PINE_BLUE)
        }
        return VisualRef(id, false, item.onPrice)
    }

    private fun hline(id: Int, args: PineArgs): VisualRef {
        val item = levels.getOrPut(id) {
            PineLevel(id, args.text("title", "水平线"), args.number("price"), args.color("color", 0xFF787B86.toInt()),
                args.enum("linestyle", PineStroke.SOLID), args.integer("linewidth", 1, 1, 20).toFloat(), overlay, args.displayed())
        }
        return VisualRef(id, true, item.onPrice)
    }

    private fun fill(id: Int, args: PineArgs, bar: Int) {
        val first = args["plot1"] as? VisualRef ?: args.error("fill 需要 plot 或 hline 返回的引用")
        val second = args["plot2"] as? VisualRef ?: args.error("fill 需要 plot 或 hline 返回的引用")
        if (first.level != second.level || first.onPrice != second.onPrice) args.error("fill 的两个引用必须同类型且位于同一窗格")
        val item = fills.getOrPut(id) { PineFill(first.id, second.id, arrayOfNulls(candles.size), first.onPrice) }
        if (args.showsBar(bar)) item.colors[bar] = args.color("color")
    }

    private fun mark(name: String, args: PineArgs, bar: Int) {
        if (!args.displayed() || !args.showsBar(bar)) return
        val target = bar + args.offset()
        val onPrice = args.onPrice()
        val location = args.enum("location", PineLocation.ABOVE_BAR)
        val value = if (location == PineLocation.ABSOLUTE) args.number("series").also { if (!it.isFinite()) return } else {
            if (!pineTruth(args["series"], args.line)) return
            val candle = candles.getOrNull(target)
            when (location) {
                PineLocation.ABOVE_BAR -> candle?.let { it.high / priceFactor } ?: Double.NaN
                PineLocation.BELOW_BAR -> candle?.let { it.low / priceFactor } ?: Double.NaN
                else -> Double.NaN
            }
        }
        val color = args.color("color", PINE_BLUE)
        val isChar = name == "plotchar"
        val shape = if (isChar) PineShape.TEXT else args.enum("style", PineShape.X_CROSS)
        val text = if (isChar) args.text("char", "★").take(2) + args.text("text").let { if (it.isEmpty()) "" else "\n$it" } else args.text("text")
        val textColor = args.color("textcolor", if (isChar) color else PINE_WHITE)
        if (color == null && (textColor == null || text.isEmpty())) return
        if (marks.size >= MAX_PINE_MARKS) args.error("图形标记超过 $MAX_PINE_MARKS 个，请使用 show_last 或减少标记")
        marks += PineMark(target, value, location, shape, color, text.take(MAX_PINE_TEXT), textColor,
            args.integer("size", 12, 1, 100).toFloat(), onPrice)
    }

    private fun tint(name: String, args: PineArgs, bar: Int) {
        if (!args.displayed() || !args.showsBar(bar)) return
        val target = bar + args.offset()
        if (target !in candles.indices) return
        val color = args.color("color") ?: return
        val buffer = when {
            name == "barcolor" -> barColors
            args.onPrice() -> priceBackgrounds
            else -> backgrounds
        }
        buffer[target] = color
    }

    private fun PineArgs.onPrice() = overlay || bool("force_overlay")
    private fun PineArgs.offset() = integer("offset", 0, -MAX_PINE_OFFSET, MAX_PINE_OFFSET)
    private fun PineArgs.showsBar(bar: Int) = bar >= candles.size - integer("show_last", candles.size, 1, 100_000)
    private fun PineArgs.displayed() = enum("display", PineDisplay.ALL) != PineDisplay.NONE
}
