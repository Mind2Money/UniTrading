package com.unitrading.chart.pine

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import kotlin.math.abs
import kotlin.math.pow

internal fun pinePriceFactor(instrument: Instrument): Double = 10.0.pow(instrument.priceDecimals)
internal fun pineReplaySample(candles: List<Candle>): List<Candle> =
    if (candles.size <= MAX_PINE_BARS) candles else candles.subList(candles.size - MAX_PINE_BARS, candles.size)

/** Snapshot replay makes the changing last bar deterministic: no speculative object or variable survives a replay. */
internal fun runPine(
    script: PineScript, candles: List<Candle>, instrument: Instrument, timeframe: Timeframe, checkCancelled: () -> Unit = {},
): PineResult = PineRuntime(script, pineReplaySample(candles), instrument, timeframe, checkCancelled).run()

private class PineRuntime(
    private val script: PineScript,
    private val candles: List<Candle>,
    private val instrument: Instrument,
    private val timeframe: Timeframe,
    private val checkCancelled: () -> Unit,
) {
    private class Scope(val parent: Scope?) {
        val variables = mutableMapOf<String, Series>()
        private var entry = 0

        fun enter(loopVariable: Series?) { entry++; loopVariable?.let(::define) }
        fun define(series: Series) { series.scopeEntry = entry }
        fun variable(name: String): Series? = variables[name]?.takeIf { it.scopeEntry == entry } ?: parent?.variable(name)
    }

    private class Series(size: Int, val carry: Boolean) {
        private val values = arrayOfNulls<Any?>(size)
        var scopeEntry = -1
        var last = -1
            private set

        fun get(index: Int): Any? = if (index < 0) null else values[if (carry && last >= 0) minOf(index, last) else index]
        fun set(bar: Int, value: Any?, charge: (Int) -> Unit) {
            if (carry && last >= 0 && bar > last + 1) {
                charge(bar - last - 1)
                values.fill(values[last], last + 1, bar)
            }
            values[bar] = value
            last = bar
        }
    }

    private val factor = pinePriceFactor(instrument)
    private val scope = Scope(null)
    private val scopes = mutableMapOf<Int, Scope>()
    private val history = mutableMapOf<Int, Series>()
    private val functions = PineFunctions()
    private val objects = PineObjects(script.lineLimit, script.labelLimit, script.boxLimit)
    private val visuals = PineVisuals(script.overlay, candles, factor)
    private var bar = 0
    private var totalWork = 0
    private var barWork = 0
    private var untilCancellationCheck = 512
    private var activeLine = 1
    private val charge: (Int) -> Unit = { cost ->
        totalWork += cost
        barWork += cost
        untilCancellationCheck -= cost
        if (totalWork > 1_500_000 || barWork > 25_000) throw PineException("脚本计算量超过限制", activeLine)
        if (untilCancellationCheck <= 0) {
            checkCancelled()
            untilCancellationCheck = 512
        }
    }

    fun run(): PineResult {
        checkCancelled()
        for (index in candles.indices) {
            bar = index
            barWork = 0
            block(script.program.statements, scope)
        }
        checkCancelled()
        return visuals.result(script.title, objects.snapshot())
    }

    private fun block(statements: List<PineStmt>, scope: Scope, loopVariable: Series? = null) {
        scope.enter(loopVariable)
        for (statement in statements) {
            activeLine = statement.line
            charge(1)
            when (statement) {
                is PineStmt.Declare -> {
                    val series = scope.variables.getOrPut(statement.name) { Series(candles.size, statement.persistent) }
                    // Locals become visible only after their declaration in this execution of the block.
                    if (!statement.persistent || series.last == -1) series.set(bar, eval(statement.initializer, scope), charge)
                    scope.define(series)
                }
                is PineStmt.Assign -> {
                    val series = scope.variable(statement.name) ?: throw PineException("未声明变量 ${statement.name}", statement.line)
                    val value = eval(statement.value, scope)
                    val next = if (statement.operator == ":=") value else binary(series.get(bar), statement.operator.removeSuffix("="), value, statement.line, charge)
                    series.set(bar, next, charge)
                }
                is PineStmt.Evaluate -> eval(statement.expression, scope)
                is PineStmt.If -> {
                    val yes = pineTruth(eval(statement.condition, scope), statement.line)
                    val branch = if (yes) statement.thenBranch else statement.elseBranch
                    if (branch.isNotEmpty()) block(branch, scopes.getOrPut(statement.line * 2 + if (yes) 0 else 1) { Scope(scope) })
                }
                is PineStmt.For -> loop(statement, scope)
            }
        }
    }

    private fun loop(statement: PineStmt.For, parent: Scope) {
        val from = pineNumber(eval(statement.from, parent), statement.line)
        val to = pineNumber(eval(statement.to, parent), statement.line)
        val step = statement.step?.let { pineNumber(eval(it, parent), statement.line) } ?: 1.0
        if (!from.isFinite() || !to.isFinite()) return
        if (!step.isFinite() || step <= 0.0 || step % 1.0 != 0.0 || from % 1.0 != 0.0 || to % 1.0 != 0.0 ||
            maxOf(abs(from), abs(to), step) > 1e12) {
            throw PineException("for 需要有效整数起止值与正步长", statement.line)
        }
        val scope = scopes.getOrPut(statement.line * 2) { Scope(parent) }
        val variable = scope.variables.getOrPut(statement.variable) { Series(candles.size, false) }
        var index = from
        while (if (to >= from) index <= to else index >= to) {
            activeLine = statement.line
            charge(1)
            variable.set(bar, index, charge)
            block(statement.body, scope, variable)
            index += if (to >= from) step else -step
        }
    }

    private fun eval(expr: PineExpr, scope: Scope): Any? {
        activeLine = expr.line
        charge(1)
        return when (expr) {
            is PineExpr.Literal -> expr.value
            is PineExpr.ColorLiteral -> {
                val hex = expr.hex.removePrefix("#")
                val value = hex.toLong(16).toInt()
                PineColor(if (hex.length == 8) (value ushr 8) or (value shl 24) else value or 0xFF000000.toInt())
            }
            is PineExpr.Name -> name(expr, scope, bar)
            is PineExpr.Unary -> {
                val value = eval(expr.value, scope)
                when (expr.operator) {
                    "not" -> !pineTruth(value, expr.line)
                    "-" -> -pineNumber(value, expr.line)
                    "+" -> pineNumber(value, expr.line)
                    else -> throw PineException("不支持的运算符 ${expr.operator}", expr.line)
                }
            }
            is PineExpr.Binary -> {
                val left = eval(expr.left, scope)
                when (expr.operator) {
                    "and" -> pineTruth(left, expr.line) && pineTruth(eval(expr.right, scope), expr.line)
                    "or" -> pineTruth(left, expr.line) || pineTruth(eval(expr.right, scope), expr.line)
                    else -> binary(left, expr.operator, eval(expr.right, scope), expr.line, charge)
                }
            }
            is PineExpr.Choose -> if (pineTruth(eval(expr.condition, scope), expr.line)) eval(expr.yes, scope) else eval(expr.no, scope)
            is PineExpr.History -> history(expr, scope)
            is PineExpr.Call -> call(expr, scope)
        }
    }

    private fun history(expr: PineExpr.History, scope: Scope): Any? {
        val value = expr.value
        val series = if (value is PineExpr.Name) null else history.getOrPut(expr.id) { Series(candles.size, false) }.also {
            it.set(bar, eval(value, scope), charge)
        }
        val offset = pineNumber(eval(expr.offset, scope), expr.line)
        if (offset.isNaN()) return null
        if (!offset.isFinite() || offset < 0 || offset % 1.0 != 0.0 || offset > MAX_PINE_HISTORY) {
            throw PineException("历史偏移必须是 0–$MAX_PINE_HISTORY 的整数", expr.line)
        }
        val index = bar - offset.toInt()
        if (index < 0) return null
        return if (value is PineExpr.Name) name(value, scope, index) else series?.get(index)
    }

    private fun name(expr: PineExpr.Name, scope: Scope, index: Int): Any? {
        val path = expr.path
        if (path in pineMarketNames) return market(path, index)
        pineConstants[path]?.let { return it }
        if (path == "na") return null
        return scope.variable(path)?.get(index)
    }

    private fun market(name: String, index: Int): Any? {
        val candle = candles[index]
        return when (name) {
            "open" -> candle.open / factor
            "high" -> candle.high / factor
            "low" -> candle.low / factor
            "close" -> candle.close / factor
            "volume" -> candle.volume?.toDouble() ?: Double.NaN
            "time" -> candle.timeMs.toDouble()
            "time_close" -> candle.timeMs.toDouble() + timeframe.millis
            "hl2" -> (candle.high.toDouble() + candle.low) / (factor * 2)
            "hlc3" -> (candle.high.toDouble() + candle.low + candle.close) / (factor * 3)
            "ohlc4" -> (candle.open.toDouble() + candle.high + candle.low + candle.close) / (factor * 4)
            "hlcc4" -> (candle.high.toDouble() + candle.low + candle.close * 2.0) / (factor * 4)
            "bar_index" -> index.toDouble()
            "last_bar_index" -> candles.lastIndex.toDouble()
            "last_bar_time" -> candles.last().timeMs.toDouble()
            "syminfo.ticker", "syminfo.tickerid" -> instrument.symbol.take(MAX_PINE_TEXT)
            "syminfo.mintick" -> instrument.tickSize / factor
            "syminfo.currency" -> instrument.currency.take(MAX_PINE_TEXT)
            "timeframe.period" -> when {
                timeframe.isDaily -> "${timeframe.seconds / Timeframe.DAY}D"
                timeframe.isSeconds -> "${timeframe.seconds}S"
                else -> (timeframe.seconds / Timeframe.MINUTE).toString()
            }
            "timeframe.multiplier" -> (if (timeframe.isDaily) timeframe.seconds / Timeframe.DAY else if (timeframe.isSeconds) timeframe.seconds else timeframe.seconds / Timeframe.MINUTE).toDouble()
            "timeframe.isseconds" -> timeframe.isSeconds
            "timeframe.isminutes" -> !timeframe.isDaily && !timeframe.isSeconds
            "timeframe.isintraday" -> timeframe.seconds < Timeframe.DAY
            "timeframe.isdaily" -> timeframe.isDaily
            "barstate.isfirst" -> index == 0
            "barstate.islast", "barstate.islastconfirmedhistory" -> index == candles.lastIndex
            "barstate.isnew", "barstate.ishistory", "barstate.isconfirmed" -> true
            "barstate.isrealtime" -> false
            "ta.tr" -> trueRange(index)
            else -> null
        }
    }

    private fun trueRange(index: Int): Double {
        if (index == 0) return Double.NaN
        val candle = candles[index]
        val previous = candles[index - 1].close.toDouble()
        return maxOf(candle.high.toDouble() - candle.low, abs(candle.high - previous), abs(candle.low - previous)) / factor
    }

    private fun call(call: PineExpr.Call, scope: Scope): Any? {
        if (call.name == "indicator") return null
        val names = script.arguments.getValue(call.id)
        val values = buildMap { call.arguments.forEachIndexed { index, arg -> put(names[index], eval(arg.value, scope)) } }
        activeLine = call.line
        val args = PineArgs(call.line, values)
        val candle = candles[bar]
        val value = when (call.name) {
            in pineInputSignatures -> defaultInput(call.name, args)
            in pineFunctionSignatures -> functions.call(call.name, call.id, args, bar, trueRange(bar), candle.high / factor, candle.low / factor, charge)
            in pineObjectSignatures -> objects.call(call.name, args, script.overlay)
            else -> visuals.call(call.name, call.id, args, bar)
        }
        return when (value) {
            is Double -> value.finiteOrNaN()
            is String -> value.take(MAX_PINE_TEXT)
            else -> value
        }
    }

    private fun defaultInput(name: String, args: PineArgs): Any? {
        val kind = name.removePrefix("input.")
        return when (kind) {
            "bool" -> args.bool("defval")
            "string" -> args.text("defval")
            "color" -> args.color("defval")?.let(::PineColor)
            "int", "float", "source" -> {
                val value = args.number("defval")
                if (kind == "int" && value.isFinite() && value % 1.0 != 0.0) args.error("input.int 需要整数默认值")
                if ((args.has("minval") && value < args.number("minval")) || (args.has("maxval") && value > args.number("maxval"))) {
                    args.error("输入默认值超出 minval 或 maxval")
                }
                value
            }
            else -> args["defval"].also {
                if (!pineNa(it) && it !is Double && it !is String && it !is Boolean && it !is PineColor) args.error("input 默认值类型不支持")
            }
        }
    }
}

private fun Double.finiteOrNaN() = takeIf { it.isFinite() } ?: Double.NaN

private fun binary(left: Any?, operator: String, right: Any?, line: Int, charge: (Int) -> Unit): Any? {
    if (operator == "==" || operator == "!=") {
        if (pineNa(left) || pineNa(right)) return false
        // Double comparison deliberately uses numeric equality (including +0.0 vs. -0.0).
        val equal = if (left is Double && right is Double) left.toDouble() == right.toDouble() else {
            if (left is String && right is String) charge(minOf(left.length, right.length) / 8)
            left == right
        }
        return if (operator == "==") equal else !equal
    }
    if (operator == "+" && left is String && right is String) {
        charge((left.length + right.length) / 8)
        if (left.length >= MAX_PINE_TEXT) return left
        return left + right.take(MAX_PINE_TEXT - left.length)
    }
    val a = pineNumber(left, line)
    val b = pineNumber(right, line)
    return when (operator) {
        "+" -> (a + b).finiteOrNaN()
        "-" -> (a - b).finiteOrNaN()
        "*" -> (a * b).finiteOrNaN()
        "/" -> (a / b).finiteOrNaN()
        "%" -> (a % b).finiteOrNaN()
        "<" -> a < b
        "<=" -> a <= b
        ">" -> a > b
        ">=" -> a >= b
        else -> throw PineException("不支持的运算符 $operator", line)
    }
}
