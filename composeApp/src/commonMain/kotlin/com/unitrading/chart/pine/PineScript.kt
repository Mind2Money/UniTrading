package com.unitrading.chart.pine

internal const val DEFAULT_PINE_SOURCE = """//@version=6
indicator("布林带", overlay=true)
length = 20
basis = ta.sma(close, length)
deviation = 2 * ta.stdev(close, length)
plot(basis, "中轨", color=color.orange)
plot(basis + deviation, "上轨", color=color.blue)
plot(basis - deviation, "下轨", color=color.blue)"""

internal const val MAX_PINE_BARS = 5_000
internal const val MAX_PINE_HISTORY = 5_000
internal const val PINE_BLUE = 0xFF2962FF.toInt()
internal const val PINE_WHITE = 0xFFFFFFFF.toInt()
private const val MAX_PINE_VARIABLES = 96
private const val MAX_PINE_VISUAL_CALLS = 24
internal const val MAX_PINE_TEXT = 512

internal class PineSignature(val required: Int, val parameters: List<String>, val unsupported: Set<String> = emptySet())
internal fun pineSignature(required: Int, vararg parameters: String) = PineSignature(required, parameters.toList())
private fun visual(required: Int, parameters: String, unsupported: String = "") =
    PineSignature(required, parameters.split(' '), unsupported.split(' ').filter(String::isNotEmpty).toSet())

internal enum class PineDisplay { ALL, PANE, NONE }

internal val pineConstants: Map<String, Any> = buildMap {
    mapOf(
        "aqua" to 0x00BCD4, "black" to 0x363A45, "blue" to 0x2962FF, "fuchsia" to 0xE040FB,
        "gray" to 0x787B86, "green" to 0x4CAF50, "lime" to 0x00E676, "maroon" to 0x880E4F,
        "navy" to 0x311B92, "olive" to 0x808000, "orange" to 0xFF9800, "purple" to 0x9C27B0,
        "red" to 0xF23645, "silver" to 0xB2B5BE, "teal" to 0x089981, "white" to 0xFFFFFF, "yellow" to 0xFDD835,
    ).forEach { (name, rgb) -> put("color.$name", PineColor(rgb or 0xFF000000.toInt())) }
    PineStroke.entries.forEach {
        val style = "style_${it.name.lowercase()}"
        put("line.$style", it); put("hline.$style", it)
    }
    PineExtend.entries.forEach { put("extend.${it.name.lowercase()}", it) }
    PineXloc.entries.forEach { put("xloc.${it.name.lowercase()}", it) }
    PineDisplay.entries.forEach { put("display.${it.name.lowercase()}", it) }
    put("yloc.price", PineLocation.ABSOLUTE)
    put("yloc.abovebar", PineLocation.ABOVE_BAR)
    put("yloc.belowbar", PineLocation.BELOW_BAR)
    PineLocation.entries.forEach { put("location.${it.name.lowercase().replace("_", "")}", it) }
    PinePlotStyle.entries.forEach {
        val style = if (it == PinePlotStyle.LINE_BREAK) "linebr" else it.name.lowercase()
        put("plot.style_$style", it)
    }
    put("math.pi", kotlin.math.PI)
    put("math.e", kotlin.math.E)
    put("math.phi", 1.618033988749895)
    put("math.rphi", 0.618033988749895)
    mapOf("tiny" to 8.0, "small" to 10.0, "normal" to 12.0, "large" to 16.0, "huge" to 20.0, "auto" to 12.0)
        .forEach { (name, value) -> put("size.$name", value) }
    mapOf(
        "circle" to PineShape.CIRCLE, "triangleup" to PineShape.TRIANGLE_UP, "triangledown" to PineShape.TRIANGLE_DOWN,
        "arrowup" to PineShape.ARROW_UP, "arrowdown" to PineShape.ARROW_DOWN, "cross" to PineShape.CROSS,
        "xcross" to PineShape.X_CROSS, "diamond" to PineShape.DIAMOND, "square" to PineShape.SQUARE,
        "labelup" to PineShape.LABEL_UP, "labeldown" to PineShape.LABEL_DOWN, "flag" to PineShape.FLAG,
    ).forEach { (name, shape) -> put("shape.$name", shape) }
    PineShape.entries.filterNot { it == PineShape.TEXT }.forEach {
        val style = if (it == PineShape.LABEL_UP || it == PineShape.LABEL_DOWN) it.name.lowercase() else it.name.lowercase().replace("_", "")
        put("label.style_$style", it)
    }
    put("label.style_none", PineShape.TEXT)
    put("format.mintick", "mintick")
    put("format.percent", "percent")
    put("format.volume", "volume")
}

internal val pineMarketNames = setOf(
    "open", "high", "low", "close", "volume", "time", "time_close", "hl2", "hlc3", "ohlc4", "hlcc4", "bar_index", "last_bar_index",
    "last_bar_time", "syminfo.ticker", "syminfo.tickerid", "syminfo.mintick", "syminfo.currency",
    "timeframe.period", "timeframe.multiplier", "timeframe.isseconds", "timeframe.isminutes", "timeframe.isintraday", "timeframe.isdaily",
    "barstate.isfirst", "barstate.islast", "barstate.isnew", "barstate.ishistory", "barstate.isrealtime", "barstate.isconfirmed",
    "barstate.islastconfirmedhistory", "ta.tr",
)

private val indicatorSignature = visual(1,
    "title shorttitle overlay format precision scale max_bars_back timeframe timeframe_gaps explicit_plot_zorder max_lines_count max_labels_count max_boxes_count",
    "format precision scale timeframe timeframe_gaps explicit_plot_zorder")

private val visualSignatures = mapOf(
    "plot" to visual(1, "series title color linewidth style trackprice histbase offset join editable show_last display format precision force_overlay",
        "trackprice join format precision"),
    "hline" to visual(1, "price title color linestyle linewidth editable display"),
    "fill" to visual(3, "plot1 plot2 color title editable show_last fillgaps", "fillgaps"),
    "plotshape" to visual(1, "series title style location color offset text textcolor editable size show_last display format precision force_overlay",
        "format precision"),
    "plotchar" to visual(1, "series title char location color offset text textcolor editable size show_last display format precision force_overlay",
        "format precision"),
    "bgcolor" to visual(1, "color offset editable show_last title force_overlay"),
    "barcolor" to visual(1, "color offset editable show_last title display"),
)

internal val pineInputSignatures = mapOf(
    "input" to pineSignature(1, "defval", "title", "tooltip", "inline", "group"),
    "input.int" to pineSignature(1, "defval", "title", "minval", "maxval", "step", "tooltip", "inline", "group", "confirm", "display", "active"),
    "input.float" to pineSignature(1, "defval", "title", "minval", "maxval", "step", "tooltip", "inline", "group", "confirm", "display", "active"),
    "input.bool" to pineSignature(1, "defval", "title", "tooltip", "inline", "group", "confirm", "display", "active"),
    "input.string" to visual(1, "defval title options tooltip inline group confirm display active", "options"),
    "input.color" to pineSignature(1, "defval", "title", "tooltip", "inline", "group", "confirm", "display", "active"),
    "input.source" to pineSignature(1, "defval", "title", "tooltip", "inline", "group", "display", "active"),
)

internal data class PineScript(
    val program: PineProgram,
    val title: String,
    val overlay: Boolean,
    val lineLimit: Int,
    val labelLimit: Int,
    val boxLimit: Int,
    val arguments: Map<Int, List<String>>,
)

internal fun pineScriptError(source: String): String? = try {
    compilePine(source)
    null
} catch (error: PineException) {
    error.message
}

internal fun compilePine(source: String): PineScript = PineCompiler(parsePine(source)).compile()

private class PineCompiler(private val program: PineProgram) {
    private val arguments = mutableMapOf<Int, List<String>>()
    private var variables = 0
    private var visuals = 0
    private lateinit var indicator: PineExpr.Call

    fun compile(): PineScript {
        val first = (program.statements.firstOrNull() as? PineStmt.Evaluate)?.expression as? PineExpr.Call
        if (first?.name != "indicator") {
            throw PineException("脚本必须以 indicator(…) 开始；不支持 strategy(…) 或下单", program.statements.firstOrNull()?.line ?: 1)
        }
        indicator = first
        block(program.statements, Scope(null))
        val values = arguments.getValue(first.id).zip(first.arguments.map { constant(it.value) }).toMap()
        val args = PineArgs(first.line, values)
        val title = args.text("title").trim()
        if (title.isEmpty()) args.error("指标名称不能为空")
        args.integer("max_bars_back", MAX_PINE_HISTORY, 0, MAX_PINE_HISTORY)
        return PineScript(program, args.text("shorttitle").trim().ifEmpty { title }.take(96), args.bool("overlay"),
            args.integer("max_lines_count", 50, 1, 500), args.integer("max_labels_count", 50, 1, 500),
            args.integer("max_boxes_count", 50, 1, 500), arguments.toMap())
    }

    private class Scope(val parent: Scope?) {
        val locals = mutableSetOf<String>()
        fun contains(name: String): Boolean = name in locals || parent?.contains(name) == true
    }

    private fun declare(name: String, line: Int, scope: Scope) {
        if (name in pineMarketNames || name in pineConstants || name == "na" || !scope.locals.add(name)) {
            throw PineException("不能重复或重新声明 $name", line)
        }
        countVariable(line)
    }

    private fun countVariable(line: Int) {
        if (++variables > MAX_PINE_VARIABLES) throw PineException("变量与历史表达式合计最多 $MAX_PINE_VARIABLES 个", line)
    }

    private fun block(statements: List<PineStmt>, scope: Scope) {
        statements.forEach { statement ->
            when (statement) {
                is PineStmt.Declare -> {
                    expression(statement.initializer, scope)
                    declare(statement.name, statement.line, scope)
                }
                is PineStmt.Assign -> {
                    if (!scope.contains(statement.name)) throw PineException("未声明变量 ${statement.name}", statement.line)
                    expression(statement.value, scope)
                }
                is PineStmt.Evaluate -> expression(statement.expression, scope)
                is PineStmt.If -> {
                    expression(statement.condition, scope)
                    block(statement.thenBranch, Scope(scope))
                    block(statement.elseBranch, Scope(scope))
                }
                is PineStmt.For -> {
                    expression(statement.from, scope)
                    expression(statement.to, scope)
                    statement.step?.let { expression(it, scope) }
                    val body = Scope(scope)
                    declare(statement.variable, statement.line, body)
                    block(statement.body, body)
                }
            }
        }
    }

    private fun expression(expr: PineExpr, scope: Scope) {
        when (expr) {
            is PineExpr.Literal -> if (expr.value is String && expr.value.length > MAX_PINE_TEXT) {
                throw PineException("文本最多 $MAX_PINE_TEXT 个字符", expr.line)
            }
            is PineExpr.ColorLiteral -> Unit
            is PineExpr.Name -> if (expr.path != "na" && expr.path !in pineConstants && expr.path !in pineMarketNames && !scope.contains(expr.path)) {
                throw PineException("不支持或未声明的名称 ${expr.path}", expr.line)
            }
            is PineExpr.Unary -> expression(expr.value, scope)
            is PineExpr.Binary -> { expression(expr.left, scope); expression(expr.right, scope) }
            is PineExpr.Choose -> { expression(expr.condition, scope); expression(expr.yes, scope); expression(expr.no, scope) }
            is PineExpr.History -> {
                if (expr.value !is PineExpr.Name) countVariable(expr.line)
                expression(expr.value, scope); expression(expr.offset, scope)
            }
            is PineExpr.Call -> {
                val signature = when (expr.name) {
                    "indicator" -> if (expr.id == indicator.id) indicatorSignature else throw PineException("只能声明一个 indicator", expr.line)
                    else -> visualSignatures[expr.name]?.also {
                        if (scope.parent != null) throw PineException("${expr.name} 必须在脚本顶层调用", expr.line)
                        if (++visuals > MAX_PINE_VISUAL_CALLS) throw PineException("绘图调用最多 $MAX_PINE_VISUAL_CALLS 个", expr.line)
                    } ?: pineInputSignatures[expr.name] ?: pineFunctionSignatures[expr.name] ?: pineObjectSignatures[expr.name]
                    ?: throw PineException("不支持的函数 ${expr.name}", expr.line)
                }
                arguments[expr.id] = bind(expr, signature)
                expr.arguments.forEach { expression(it.value, scope) }
            }
        }
    }

    private fun bind(call: PineExpr.Call, signature: PineSignature): List<String> {
        val seen = mutableSetOf<String>()
        var named = false
        val names = call.arguments.mapIndexed { index, argument ->
            val name = argument.name?.let { name ->
                named = true
                when {
                    call.name == "fill" && name == "hline1" -> "plot1"
                    call.name == "fill" && name == "hline2" -> "plot2"
                    else -> name
                }
            } ?: run {
                if (named) throw PineException("位置参数不能放在命名参数之后", call.line)
                signature.parameters.getOrNull(index) ?: throw PineException("${call.name} 的参数过多", call.line)
            }
            if (name !in signature.parameters || name in signature.unsupported) throw PineException("${call.name} 不支持参数 $name", call.line)
            if (!seen.add(name)) throw PineException("参数 $name 重复", call.line)
            name
        }
        for (name in signature.parameters.take(signature.required)) {
            if (name !in seen && !(call.name in setOf("ta.highest", "ta.lowest") && name == "source" && "length" in seen)) {
                throw PineException("${call.name} 缺少参数 $name", call.line)
            }
        }
        return names
    }

    private fun constant(expr: PineExpr): Any? = when (expr) {
        is PineExpr.Literal -> expr.value
        is PineExpr.Unary -> when (expr.operator) {
            "-" -> -pineNumber(constant(expr.value), expr.line)
            "+" -> pineNumber(constant(expr.value), expr.line)
            "not" -> !pineTruth(constant(expr.value), expr.line)
            else -> throw PineException("indicator 参数必须是常量", expr.line)
        }
        else -> throw PineException("indicator 参数必须是常量", expr.line)
    }
}

internal class PineArgs(val line: Int, private val values: Map<String, Any?>) {
    fun has(name: String) = name in values
    operator fun get(name: String): Any? = values[name]
    fun number(name: String, default: Double = Double.NaN): Double = if (has(name)) pineNumber(this[name], line) else default
    fun bool(name: String, default: Boolean = false): Boolean = if (has(name)) pineTruth(this[name], line) else default
    fun text(name: String, default: String = ""): String = if (has(name)) {
        val value = this[name]
        if (pineNa(value)) "" else value as? String ?: error("参数 $name 需要文本")
    } else default
    fun color(name: String, default: Int? = null): Int? = if (has(name)) {
        val value = this[name]
        if (pineNa(value)) null else (value as? PineColor ?: error("参数 $name 需要颜色")).argb
    } else default
    inline fun <reified E : Enum<E>> enum(name: String, default: E): E =
        if (has(name)) this[name] as? E ?: error("参数 $name 的选项无效") else default
    fun integer(name: String, default: Int, min: Int, max: Int): Int {
        val value = number(name, default.toDouble())
        if (!value.isFinite() || value !in min.toDouble()..max.toDouble() || value != value.toInt().toDouble()) {
            error("参数 $name 必须是 $min–$max 的整数")
        }
        return value.toInt()
    }
    fun error(message: String): Nothing = throw PineException(message, line)
}

internal fun pineNa(value: Any?): Boolean = value == null || (value is Double && value.isNaN())
internal fun pineNumber(value: Any?, line: Int): Double = when (value) {
    null -> Double.NaN
    is Double -> value
    else -> throw PineException("需要数值", line)
}
internal fun pineTruth(value: Any?, line: Int): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Double -> value != 0.0 && !value.isNaN()
    else -> throw PineException("需要布尔值或数值", line)
}
