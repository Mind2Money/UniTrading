package com.unitrading.chart.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PineParserTest {
    @Test
    fun parsesPastedSourceWithCommentsNamedArgumentsAndLiterals() {
        val source = listOf(
            "\uFEFF// clipboard text",
            "//@version=6",
            "indicator(",
            "    title = \"示例 // \\\"引号\\\"\", // comment after argument",
            "  // comment only inside call",
            "",
            "overlay = true",
            ")",
            "string text = 'line\\n\\t\\'//\\\\'",
            "float number = +.5e+2",
            "number += 1.",
            "var line trend = na",
            "plot(series = na(close) ? #aAbBcC : #00112280)",
        ).joinToString("\r\n")

        val statements = parsePine(source).statements
        assertEquals(6, statements.size)
        val indicator = assertIs<PineExpr.Call>(assertIs<PineStmt.Evaluate>(statements[0]).expression)
        assertEquals("indicator", indicator.name)
        assertEquals(3, indicator.line)
        assertEquals(0, indicator.id)
        assertEquals(
            listOf(PineArg("title", PineExpr.Literal("示例 // \"引号\"", 4)), PineArg("overlay", PineExpr.Literal(true, 7))),
            indicator.arguments,
        )
        assertEquals(PineStmt.Declare("text", PineExpr.Literal("line\n\t'//\\", 9), false, 9), statements[1])
        assertEquals(
            PineStmt.Declare("number", PineExpr.Unary("+", PineExpr.Literal(50.0, 10), 10), false, 10),
            statements[2],
        )
        assertEquals(PineStmt.Assign("number", "+=", PineExpr.Literal(1.0, 11), 11), statements[3])
        assertEquals(PineStmt.Declare("trend", PineExpr.Name("na", 12), true, 12), statements[4])
        val plot = assertIs<PineExpr.Call>(assertIs<PineStmt.Evaluate>(statements[5]).expression)
        assertEquals("plot", plot.name)
        assertEquals("series", plot.arguments.single().name)
        val choice = assertIs<PineExpr.Choose>(plot.arguments.single().value)
        assertEquals(PineExpr.Call("na", listOf(PineArg(null, PineExpr.Name("close", 13))), 2, 13), choice.condition)
        assertEquals(PineExpr.ColorLiteral("#aAbBcC", 13), choice.yes)
        assertEquals(PineExpr.ColorLiteral("#00112280", 13), choice.no)
    }

    @Test
    fun usesPinePrecedenceAndRightNestedTernaries() {
        val stmt = parsePine("value = not close[1] > -open + 2 * high != false and a or b ? low : c ? 1 : 2")
            .statements.single()
        fun name(value: String) = PineExpr.Name(value, 1)
        fun literal(value: Any?) = PineExpr.Literal(value, 1)
        fun binary(left: PineExpr, op: String, right: PineExpr) = PineExpr.Binary(left, op, right, 1)
        val comparison = binary(
            PineExpr.Unary("not", PineExpr.History(name("close"), literal(1.0), 1, 0), 1),
            ">",
            binary(PineExpr.Unary("-", name("open"), 1), "+", binary(literal(2.0), "*", name("high"))),
        )
        val choice = PineExpr.Choose(
            binary(binary(binary(comparison, "!=", literal(false)), "and", name("a")), "or", name("b")),
            name("low"),
            PineExpr.Choose(name("c"), literal(1.0), literal(2.0), 1),
            1,
        )
        assertEquals(PineStmt.Declare("value", choice, false, 1), stmt)

        val grouped = assertIs<PineStmt.Declare>(parsePine("x = (1 - 2 - 3) / (4 + 5)").statements.single())
        assertEquals(
            binary(binary(binary(literal(1.0), "-", literal(2.0)), "-", literal(3.0)), "/", binary(literal(4.0), "+", literal(5.0))),
            grouped.initializer,
        )
    }

    @Test
    fun parsesBlocksAcrossBlankLinesAndAlignedElseIf() {
        val source = listOf(
            "//@version=5",
            "var float acc = na",
            "if barstate.isfirst",
            " // A comment at a non-block indentation",
            "  ",
            "\tacc := 0",
            "    for i = 1 to 5 by 2",
            "        acc += close[",
            " i // offset expression on the next line",
            "]",
            "else if close[1] > open",
            "    acc -= 1",
            "else",
            "    for j = 3 to 1",
            "        acc %= 2",
            "plot(acc)",
        ).joinToString("\n")

        val statements = parsePine(source).statements
        val accumulator = assertIs<PineStmt.Declare>(statements[0])
        assertTrue(accumulator.persistent)
        assertEquals(PineExpr.Name("na", 2), accumulator.initializer)
        val first = assertIs<PineStmt.If>(statements[1])
        assertEquals(3, first.line)
        assertEquals(PineExpr.Name("barstate.isfirst", 3), first.condition)
        assertEquals(PineStmt.Assign("acc", ":=", PineExpr.Literal(0.0, 6), 6), first.thenBranch[0])
        val loop = assertIs<PineStmt.For>(first.thenBranch[1])
        assertEquals(7, loop.line)
        assertEquals("i", loop.variable)
        assertEquals(PineExpr.Literal(1.0, 7), loop.from)
        assertEquals(PineExpr.Literal(5.0, 7), loop.to)
        assertEquals(PineExpr.Literal(2.0, 7), loop.step)
        assertEquals(
            PineStmt.Assign("acc", "+=", PineExpr.History(PineExpr.Name("close", 8), PineExpr.Name("i", 9), 8, 0), 8),
            loop.body.single(),
        )
        val second = assertIs<PineStmt.If>(first.elseBranch.single())
        assertEquals(11, second.line)
        assertEquals(PineStmt.Assign("acc", "-=", PineExpr.Literal(1.0, 12), 12), second.thenBranch.single())
        val descending = assertIs<PineStmt.For>(second.elseBranch.single())
        assertEquals("j", descending.variable)
        assertNull(descending.step)
        assertEquals(PineStmt.Assign("acc", "%=", PineExpr.Literal(2.0, 15), 15), descending.body.single())
        assertEquals(16, statements[2].line)
        assertEquals(3, statements.size)
    }

    @Test
    fun allocatesIndependentSmallIdsForCallsAndHistoryOnEachParse() {
        val source = "out = my.namespace.f(open[1], other(high)[2], (low + close)[offset[0]])"
        repeat(2) {
            val outer = assertIs<PineExpr.Call>(assertIs<PineStmt.Declare>(parsePine(source).statements.single()).initializer)
            assertEquals("my.namespace.f", outer.name)
            assertEquals(0, outer.id)
            val histories = outer.arguments.map { assertIs<PineExpr.History>(it.value) }
            assertEquals(listOf(0, 1, 2), histories.map { it.id })
            assertEquals(1, assertIs<PineExpr.Call>(histories[1].value).id)
            assertEquals(3, assertIs<PineExpr.History>(histories[2].offset).id)
        }
    }

    @Test
    fun doesNotRequireIndicatorOrResolveDeclarationsAndCallSignatures() {
        assertTrue(parsePine("// just a comment\r \t\r").statements.isEmpty())
        val statements = parsePine(
            "strategy(\"if\", \"not\", \"=\", \"[\", \"#00ff00\")\r" +
                "// a version annotation may be after the declaration\r//@version=5\r" +
                "custom := custom.function(missing, unknown = 0)\r",
        ).statements
        val strategy = assertIs<PineExpr.Call>(assertIs<PineStmt.Evaluate>(statements.first()).expression)
        assertEquals("strategy", strategy.name)
        assertEquals(listOf("if", "not", "=", "[", "#00ff00"), strategy.arguments.map { assertIs<PineExpr.Literal>(it.value).value })
        val assignment = assertIs<PineStmt.Assign>(statements.last())
        assertEquals(4, assignment.line)
        assertEquals("custom.function", assertIs<PineExpr.Call>(assignment.value).name)
        for (type in listOf("int", "float", "bool", "string", "color", "line", "label", "box")) {
            val declaration = assertIs<PineStmt.Declare>(parsePine("$type x = na").statements.single())
            assertEquals("x", declaration.name)
            assertFalse(declaration.persistent)
        }
    }

    @Test
    fun reportsUnsupportedConstructsAndMalformedLiteralsAtTheirSourceLine() {
        assertRejected("// license\r\n//@version=4\r\nplot(close)", "仅支持 Pine v5 或 v6", line = 2)
        assertRejected("//@version ???", "版本指令应写", line = 1)
        assertRejected("plot(close)\nvarip int a = 1", "varip", line = 2)
        assertRejected("table t = na", "类型标注 'table'", line = 1)
        assertRejected("if true\n  x = 1", "每层须缩进四个空格", line = 2)
        assertRejected("if true\n    // a comment is not a body", "缺少缩进代码块", line = 1)
        assertRejected("plot(1)\nelse\n    plot(2)", "else 没有匹配", line = 2)
        assertRejected("x = #1234567", "#RRGGBB", line = 1)
        assertRejected("x = 1e+", "指数缺少数字", line = 1)
        assertRejected("x = 'unterminated", "字符串未闭合", line = 1)
        assertRejected("x = if close > open\n    1", "作为表达式", line = 1)
        assertRejected("before = 1\nx = (\n  1 + 2", "未闭合", line = 2)
        assertRejected("x = (1]", "没有匹配的开括号", line = 1)
        assertRejected("x = close[1][2]", "连续使用两次历史引用", line = 1)
        assertRejected("c.value := 1", "赋值目标须为简单变量名", line = 1)
        assertRejected("f(x) => x", "函数定义", line = 1)
        assertRejected("for i in values\n    f(i)", "仅支持 for", line = 1)
    }

    @Test
    fun boundsSourceTokensAndIdentifierLength() {
        val withinLimit = "//" + "x".repeat(MAX_PINE_SOURCE_LENGTH - 2)
        assertTrue(parsePine(withinLimit).statements.isEmpty())
        assertRejected("${withinLimit}x", "脚本长度", line = 1)
        assertRejected("x = 1\n".repeat(3_000), "脚本记号数")
        assertRejected("plot(close)\n" + "x".repeat(129) + " = 1", "标识符长度", line = 2)
        assertRejected("x = " + "a.".repeat(128) + "z", "名称路径长度", line = 1)
        assertRejected("x = " + "9".repeat(129), "数字字面量长度", line = 1)
    }

    @Test
    fun boundsParserRecursionAndAstDepthIndependently() {
        assertRejected("x = " + "(".repeat(256) + "0" + ")".repeat(256), "括号嵌套")
        assertRejected("x = " + "-".repeat(256) + "1", "递归嵌套")
        assertRejected("x = " + "true ? 1 : ".repeat(256) + "0", "递归嵌套")
        assertRejected("x = " + List(256) { "1" }.joinToString(" + "), "表达式树深度")
        val nested = buildString {
            repeat(64) { append("\t".repeat(it)).append("if true\n") }
            append("\t".repeat(64)).append("f()")
        }
        assertRejected(nested, "缩进层数")
        assertRejected("if true\n    f()\n" + "else if true\n    f()\n".repeat(64), "控制语句嵌套")
    }

    private fun assertRejected(source: String, reason: String, line: Int? = null) {
        val error = assertFailsWith<PineException>(source.take(80)) { parsePine(source) }
        if (line != null) assertEquals(line, error.line)
        assertTrue(error.message.orEmpty().startsWith("第 ${error.line} 行："), error.message)
        assertTrue(error.message.orEmpty().contains(reason), error.message)
    }
}
