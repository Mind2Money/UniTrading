package com.unitrading.chart.pine

private const val MAX_OBJECTS_PER_KIND = 500
private const val MAX_TIME_MS = 9_007_199_254_740_991.0 // Largest exactly representable safe integer.

internal val pineObjectSignatures: Map<String, PineSignature> = buildMap {
    // Keep the numeric overloads' positional slots; unsupported ones are rejected by the binder.
    put("line.new", pineSignature(4, "x1", "y1", "x2", "y2", "xloc", "extend", "color", "style", "width", "force_overlay"))
    put("label.new", PineSignature(2,
        "x y text xloc yloc color style textcolor size textalign tooltip text_font_family force_overlay text_formatting".split(' '),
        setOf("textalign", "tooltip", "text_font_family", "text_formatting")))
    put("box.new", PineSignature(4,
        "left top right bottom border_color border_width border_style extend xloc bgcolor text text_size text_color text_halign text_valign text_wrap text_font_family force_overlay text_formatting".split(' '),
        setOf("text_size", "text_halign", "text_valign", "text_wrap", "text_font_family", "text_formatting")))

    fun member(namespace: String, name: String, vararg parameters: String) {
        put("$namespace.$name", pineSignature(1 + parameters.size, "id", *parameters))
    }
    for (namespace in listOf("line", "label", "box")) {
        member(namespace, "copy")
        member(namespace, "delete")
    }
    for (coordinate in listOf("x1", "y1", "x2", "y2")) {
        member("line", "get_$coordinate")
        member("line", "set_$coordinate", coordinate.take(1))
    }
    for (property in listOf("extend", "color", "style", "width")) member("line", "set_$property", property)
    member("line", "set_xy1", "x", "y")
    member("line", "set_xy2", "x", "y")
    member("line", "set_xloc", "x1", "x2", "xloc")
    member("line", "get_price", "x")

    for (property in listOf("x", "y", "text")) member("label", "get_$property")
    for (property in listOf("x", "y", "yloc", "text", "color", "style", "textcolor", "size")) {
        member("label", "set_$property", property)
    }
    member("label", "set_xy", "x", "y")
    member("label", "set_xloc", "x", "xloc")

    for (coordinate in listOf("left", "top", "right", "bottom")) {
        member("box", "get_$coordinate")
        member("box", "set_$coordinate", coordinate)
    }
    member("box", "set_lefttop", "left", "top")
    member("box", "set_rightbottom", "right", "bottom")
    member("box", "set_xloc", "left", "right", "xloc")
    member("box", "set_extend", "extend")
    member("box", "set_border_color", "color")
    member("box", "set_border_style", "style")
    member("box", "set_border_width", "width")
    member("box", "set_bgcolor", "color")
    member("box", "set_text", "text")
    member("box", "set_text_color", "text_color")
}

internal class PineObjects(lineLimit: Int, labelLimit: Int, boxLimit: Int) {
    private enum class Kind(val namespace: String) { LINE("line"), LABEL("label"), BOX("box") }

    // History carries only the handle, not the drawing. Identity also isolates replay instances.
    private class Ref(val id: Long, val kind: Kind)

    private val limits = listOf(lineLimit, labelLimit, boxLimit).map { it.coerceIn(0, MAX_OBJECTS_PER_KIND) }
    private val objects = List(Kind.entries.size) { linkedMapOf<Ref, PineDrawing>() }
    private var nextId = 0L

    fun call(name: String, args: PineArgs, overlay: Boolean): Any? {
        if (name !in pineObjectSignatures) args.error("不支持的绘图对象函数 $name")
        val namespace = name.substringBefore('.')
        val kind = Kind.entries.first { it.namespace == namespace }
        val operation = name.substringAfter('.')
        if (operation == "new") return add(kind, create(kind, args, args.bool("force_overlay") || overlay))

        val id = args["id"]
        if (pineNa(id)) return null
        if (id !is Ref || id.kind != kind) args.error("参数 id 需要 ${kind.namespace} 对象")
        val current = objects[kind.ordinal]
        val drawing = current[id] ?: return null
        return when (operation) {
            "delete" -> { current.remove(id); null }
            "copy" -> add(kind, drawing) // Drawings are immutable; setters replace the value for one ID only.
            else -> {
                val result = when (drawing) {
                    is PineDrawing.Line -> line(operation, args, drawing)
                    is PineDrawing.Label -> label(operation, args, drawing)
                    is PineDrawing.Box -> box(operation, args, drawing)
                }
                if (result is PineDrawing) {
                    current[id] = result
                    null
                } else result
            }
        }
    }

    fun snapshot(): List<PineDrawing> = objects.asSequence().flatMap { it.asSequence() }
        .filter { it.value.visible() }.sortedBy { it.key.id }.map { it.value }.toList()

    private fun add(kind: Kind, drawing: PineDrawing): Ref {
        val id = Ref(++nextId, kind)
        val limit = limits[kind.ordinal]
        if (limit > 0) {
            val current = objects[kind.ordinal]
            if (current.size == limit) current.remove(current.keys.first())
            current[id] = drawing
        }
        return id
    }

    private fun create(kind: Kind, args: PineArgs, onPrice: Boolean): PineDrawing = with(args) {
        when (kind) {
            Kind.LINE -> PineDrawing.Line(
                number("x1"), number("y1"), number("x2"), number("y2"), byTime(),
                enum("extend", PineExtend.NONE), color("color", PINE_BLUE), enum("style", PineStroke.SOLID),
                integer("width", 1, 1, 100).toFloat(), onPrice,
            )
            Kind.LABEL -> PineDrawing.Label(
                number("x"), number("y"), byTime(), labelLocation(), enum("style", PineShape.LABEL_DOWN),
                color("color", PINE_BLUE), text("text").take(MAX_PINE_TEXT), color("textcolor", PINE_WHITE), labelSize(), onPrice,
            )
            Kind.BOX -> PineDrawing.Box(
                number("left"), number("top"), number("right"), number("bottom"), byTime(),
                enum("extend", PineExtend.NONE), color("border_color", PINE_BLUE), enum("border_style", PineStroke.SOLID),
                integer("border_width", 1, 0, 100).toFloat(), color("bgcolor", PINE_BLUE), text("text").take(MAX_PINE_TEXT),
                color("text_color", 0xFF363A45.toInt()), onPrice,
            )
        }
    }

    private fun line(op: String, args: PineArgs, d: PineDrawing.Line): Any = with(args) {
        when (op) {
            "get_x1" -> d.x1
            "get_y1" -> d.y1
            "get_x2" -> d.x2
            "get_y2" -> d.y2
            "get_price" -> linePrice(d)
            "set_x1" -> d.copy(x1 = number("x"))
            "set_y1" -> d.copy(y1 = number("y"))
            "set_x2" -> d.copy(x2 = number("x"))
            "set_y2" -> d.copy(y2 = number("y"))
            "set_xy1" -> d.copy(x1 = number("x"), y1 = number("y"))
            "set_xy2" -> d.copy(x2 = number("x"), y2 = number("y"))
            "set_xloc" -> d.copy(x1 = number("x1"), x2 = number("x2"), byTime = byTime())
            "set_extend" -> d.copy(extend = enum("extend", PineExtend.NONE))
            "set_color" -> d.copy(color = color("color"))
            "set_style" -> d.copy(pattern = enum("style", PineStroke.SOLID))
            "set_width" -> d.copy(width = integer("width", 1, 1, 100).toFloat())
            else -> error("不支持的绘图对象函数 line.$op")
        }
    }

    private fun label(op: String, args: PineArgs, d: PineDrawing.Label): Any = with(args) {
        when (op) {
            "get_x" -> d.x
            "get_y" -> d.y
            "get_text" -> d.text
            "set_x" -> d.copy(x = number("x"))
            "set_y" -> d.copy(y = number("y"))
            "set_xy" -> d.copy(x = number("x"), y = number("y"))
            "set_xloc" -> d.copy(x = number("x"), byTime = byTime())
            "set_yloc" -> d.copy(location = labelLocation())
            "set_text" -> d.copy(text = text("text").take(MAX_PINE_TEXT))
            "set_color" -> d.copy(color = color("color"))
            "set_style" -> d.copy(shape = enum("style", PineShape.LABEL_DOWN))
            "set_textcolor" -> d.copy(textColor = color("textcolor"))
            "set_size" -> d.copy(size = labelSize())
            else -> error("不支持的绘图对象函数 label.$op")
        }
    }

    private fun box(op: String, args: PineArgs, d: PineDrawing.Box): Any = with(args) {
        when (op) {
            "get_left" -> d.left
            "get_top" -> d.top
            "get_right" -> d.right
            "get_bottom" -> d.bottom
            "set_left" -> d.copy(left = number("left"))
            "set_top" -> d.copy(top = number("top"))
            "set_right" -> d.copy(right = number("right"))
            "set_bottom" -> d.copy(bottom = number("bottom"))
            "set_lefttop" -> d.copy(left = number("left"), top = number("top"))
            "set_rightbottom" -> d.copy(right = number("right"), bottom = number("bottom"))
            "set_xloc" -> d.copy(left = number("left"), right = number("right"), byTime = byTime())
            "set_extend" -> d.copy(extend = enum("extend", PineExtend.NONE))
            "set_border_color" -> d.copy(border = color("color"))
            "set_border_style" -> d.copy(borderPattern = enum("style", PineStroke.SOLID))
            "set_border_width" -> d.copy(borderWidth = integer("width", 1, 0, 100).toFloat())
            "set_bgcolor" -> d.copy(background = color("color"))
            "set_text" -> d.copy(text = text("text").take(MAX_PINE_TEXT))
            "set_text_color" -> d.copy(textColor = color("text_color"))
            else -> error("不支持的绘图对象函数 box.$op")
        }
    }
}

private fun PineArgs.byTime() = enum("xloc", PineXloc.BAR_INDEX) == PineXloc.BAR_TIME

private fun PineArgs.labelLocation(): PineLocation = when (val location = enum("yloc", PineLocation.ABSOLUTE)) {
    PineLocation.TOP, PineLocation.BOTTOM -> error("参数 yloc 需要 yloc.price、yloc.abovebar 或 yloc.belowbar")
    else -> location
}

private fun PineArgs.labelSize(): Float {
    val size = number("size", 12.0)
    if (size !in 0.0..100.0) error("参数 size 必须在 0–100 之间")
    return if (size == 0.0) 12f else size.toFloat()
}

private fun PineArgs.linePrice(line: PineDrawing.Line): Double {
    if (line.byTime) error("line.get_price 仅支持 xloc.bar_index")
    val x = number("x")
    if (!line.visible() || line.x1 == line.x2 || !x.validX(false)) return Double.NaN
    val t = (x - line.x1) / (line.x2 - line.x1)
    val y = when {
        line.y1 == line.y2 -> line.y1
        t in 0.0..1.0 -> line.y1 * (1.0 - t) + line.y2 * t
        else -> line.y1 + (line.y2 - line.y1) * t
    }
    return y.takeIf { it.isFinite() } ?: Double.NaN
}

// Keep coordinates representable without indexing or allocating a series by script-provided x.
private fun Double.validX(byTime: Boolean) = if (byTime) {
    this in -MAX_TIME_MS..MAX_TIME_MS
} else {
    this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
}

private fun PineDrawing.visible(): Boolean = when (this) {
    is PineDrawing.Line -> x1.validX(byTime) && x2.validX(byTime) && y1.isFinite() && y2.isFinite() && (x1 != x2 || y1 != y2)
    is PineDrawing.Box -> left.validX(byTime) && right.validX(byTime) && top.isFinite() && bottom.isFinite()
    // For bar-relative labels the renderer resolves the candle and does not need an explicit price.
    is PineDrawing.Label -> x.validX(byTime) && (location != PineLocation.ABSOLUTE || y.isFinite())
}
