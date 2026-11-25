package com.unitrading.chart.pine

internal const val MAX_PINE_SOURCE_LENGTH = 32_768

internal class PineException(message: String, val line: Int) : IllegalArgumentException("第 $line 行：$message")

internal fun parsePine(source: String): PineProgram {
    if (source.length > MAX_PINE_SOURCE_LENGTH) {
        throw PineException("脚本长度不能超过 $MAX_PINE_SOURCE_LENGTH 个字符", 1)
    }
    return PineParser(PineLexer(source).lex()).parse()
}

internal data class PineProgram(val statements: List<PineStmt>)

internal data class PineArg(val name: String?, val value: PineExpr)

internal sealed interface PineStmt {
    val line: Int

    data class Declare(
        val name: String,
        val initializer: PineExpr,
        val persistent: Boolean,
        override val line: Int,
    ) : PineStmt

    data class Assign(val name: String, val operator: String, val value: PineExpr, override val line: Int) : PineStmt
    data class Evaluate(val expression: PineExpr, override val line: Int) : PineStmt

    data class If(
        val condition: PineExpr,
        val thenBranch: List<PineStmt>,
        val elseBranch: List<PineStmt>,
        override val line: Int,
    ) : PineStmt

    data class For(
        val variable: String,
        val from: PineExpr,
        val to: PineExpr,
        val step: PineExpr?,
        val body: List<PineStmt>,
        override val line: Int,
    ) : PineStmt
}

internal sealed interface PineExpr {
    val line: Int

    data class Literal(val value: Any?, override val line: Int) : PineExpr
    data class ColorLiteral(val hex: String, override val line: Int) : PineExpr
    data class Name(val path: String, override val line: Int) : PineExpr
    data class Unary(val operator: String, val value: PineExpr, override val line: Int) : PineExpr
    data class Binary(val left: PineExpr, val operator: String, val right: PineExpr, override val line: Int) : PineExpr
    data class Choose(val condition: PineExpr, val yes: PineExpr, val no: PineExpr, override val line: Int) : PineExpr
    /** [id] identifies this history buffer within its program. */
    data class History(val value: PineExpr, val offset: PineExpr, override val line: Int, val id: Int) : PineExpr

    data class Call(val name: String, val arguments: List<PineArg>, val id: Int, override val line: Int) : PineExpr
}

private const val MAX_TOKENS = 8_192
private const val MAX_IDENTIFIER_LENGTH = 128
private const val MAX_NUMBER_LENGTH = 128
private const val MAX_PATH_LENGTH = 256
private const val MAX_DELIMITER_DEPTH = 32
private const val MAX_BLOCK_DEPTH = 32
private const val MAX_EXPRESSION_RECURSION = 64
private const val MAX_EXPRESSION_DEPTH = 64
private const val INDENT_WIDTH = 4

private enum class Kind { WORD, NUMBER, STRING, COLOR, SYMBOL, NEWLINE, INDENT, DEDENT, EOF }

private data class Token(val kind: Kind, val text: String, val line: Int) {
    fun isSyntax(value: String): Boolean = (kind == Kind.WORD || kind == Kind.SYMBOL) && text == value
    fun fail(message: String): Nothing = throw PineException(message, line)
}

private fun Char.isDigitAscii(): Boolean = this in '0'..'9'
private fun Char.isWordStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_'
private fun Char.isWordPart(): Boolean = isWordStart() || isDigitAscii()
private fun Char.isLineBreak(): Boolean = this == '\r' || this == '\n'

private class PineLexer(private val source: String) {
    private val tokens = mutableListOf<Token>()
    private val delimiters = mutableListOf<Token>()
    private var position = if (source.startsWith('\uFEFF')) 1 else 0
    private var line = 1
    private var blockLevel = 0

    private val current: Char? get() = source.getOrNull(position)
    private val next: Char? get() = source.getOrNull(position + 1)
    private val atComment: Boolean get() = current == '/' && next == '/'

    fun lex(): List<Token> {
        while (current != null) {
            var width = 0
            while (current == ' ' || current == '\t') {
                width += if (current == '\t') INDENT_WIDTH else 1
                position++
            }
            // Blank and comment-only lines do not alter the current block.
            if (atComment) {
                comment(standalone = true)
            } else if (current?.isLineBreak() == false) {
                if (delimiters.isEmpty()) indentation(width)
                codeLine()
                if (delimiters.isEmpty()) emit(Kind.NEWLINE)
            }
            if (current != null) {
                if (current == '\r' && next == '\n') position++
                position++
                line++
            }
        }
        delimiters.lastOrNull()?.let { it.fail("'${it.text}' 未闭合，缺少 '${closing(it.text)}'") }
        indentation(0)
        emit(Kind.EOF)
        return tokens
    }

    private fun indentation(width: Int) {
        if (width % INDENT_WIDTH != 0) {
            fail("代码块每层须缩进四个空格或一个制表符；多行表达式请使用括号")
        }
        val level = width / INDENT_WIDTH
        if (level > MAX_BLOCK_DEPTH) fail("缩进层数超过限制（$MAX_BLOCK_DEPTH）")
        if (level > blockLevel + 1) fail("代码块不能一次缩进多层")
        if (level > blockLevel) emit(Kind.INDENT)
        repeat((blockLevel - level).coerceAtLeast(0)) { emit(Kind.DEDENT) }
        blockLevel = level
    }

    private fun comment(standalone: Boolean) {
        val start = position
        while (current?.isLineBreak() == false) position++
        val prefix = "//@version"
        if (!standalone || !source.startsWith(prefix, start)) return
        val valueStart = start + prefix.length
        if (source.getOrNull(valueStart)?.isWordPart() == true) return
        val annotation = source.substring(valueStart, position).trimStart()
        if (!annotation.startsWith('=')) fail("版本指令应写为 //@version=5 或 //@version=6")
        val version = annotation.substring(1).substringBefore("//").trim()
        if (version != "5" && version != "6") fail("版本指令仅支持 Pine v5 或 v6")
    }

    private fun codeLine() {
        while (true) {
            val ch = current ?: return
            when {
                ch.isLineBreak() -> return
                ch == ' ' || ch == '\t' -> position++
                atComment -> {
                    comment(standalone = false)
                    return
                }
                ch.isWordStart() -> {
                    val start = position++
                    while (current?.isWordPart() == true) {
                        if (position - start >= MAX_IDENTIFIER_LENGTH) {
                            fail("标识符长度不能超过 $MAX_IDENTIFIER_LENGTH 个字符")
                        }
                        position++
                    }
                    emit(Kind.WORD, source.substring(start, position))
                }
                ch.isDigitAscii() || ch == '.' && next?.isDigitAscii() == true -> number()
                ch == '\'' || ch == '"' -> string(ch)
                ch == '#' -> color()
                else -> symbol(ch)
            }
        }
    }

    private fun number() {
        val start = position
        while (current?.isDigitAscii() == true) position++
        if (current == '.') {
            position++
            while (current?.isDigitAscii() == true) position++
        }
        if (current == 'e' || current == 'E') {
            position++
            if (current == '+' || current == '-') position++
            if (current?.isDigitAscii() != true) fail("数字的指数缺少数字")
            while (current?.isDigitAscii() == true) position++
        }
        if (current?.isWordStart() == true) fail("无效的数字字面量")
        if (position - start > MAX_NUMBER_LENGTH) fail("数字字面量长度不能超过 $MAX_NUMBER_LENGTH 个字符")
        emit(Kind.NUMBER, source.substring(start, position))
    }

    private fun string(quote: Char) {
        position++
        if (current == quote && next == quote) fail("暂不支持三引号字符串")
        val value = StringBuilder()
        while (true) {
            val ch = current ?: fail("字符串未闭合")
            if (ch.isLineBreak()) fail("字符串未闭合；请用 \\n 表示换行")
            position++
            when (ch) {
                quote -> {
                    emit(Kind.STRING, value.toString())
                    return
                }
                '\\' -> {
                    val escaped = current ?: fail("字符串未闭合")
                    if (escaped.isLineBreak()) fail("字符串未闭合；请用 \\n 表示换行")
                    position++
                    when (escaped) {
                        'n' -> value.append('\n')
                        't' -> value.append('\t')
                        '\\', '\'', '"' -> value.append(escaped)
                        else -> value.append('\\').append(escaped)
                    }
                }
                else -> value.append(ch)
            }
        }
    }

    private fun color() {
        val start = position++
        while (current?.isWordPart() == true) position++
        val value = source.substring(start, position)
        if ((value.length != 7 && value.length != 9) || value.drop(1).any { it !in "0123456789abcdefABCDEF" }) {
            fail("颜色字面量须为 #RRGGBB 或 #RRGGBBAA")
        }
        emit(Kind.COLOR, value)
    }

    private fun symbol(ch: Char) {
        if (ch !in "()[],:?=.+-*/%<>!") fail("不支持字符 '$ch'")
        if (ch == '=' && next == '>') fail("暂不支持 '=>'（函数定义或 switch 分支）")
        if (ch == '/' && next == '*') fail("不支持块注释，请使用 // 注释")
        val start = position++
        if (current == '=' && ch in ":=+-*/%<>!") position++
        if (ch == '!' && position - start == 1) fail("逻辑取反请使用 not")
        val text = source.substring(start, position)
        when (text) {
            "(", "[" -> {
                if (delimiters.size >= MAX_DELIMITER_DEPTH) fail("括号嵌套超过限制（$MAX_DELIMITER_DEPTH）")
                delimiters.add(Token(Kind.SYMBOL, text, line))
            }
            ")", "]" -> {
                val opener = delimiters.lastOrNull()
                if (opener == null || closing(opener.text) != text) fail("'$text' 没有匹配的开括号")
                delimiters.removeAt(delimiters.lastIndex)
            }
        }
        emit(Kind.SYMBOL, text)
    }

    private fun closing(opener: String): String = if (opener == "(") ")" else "]"

    private fun emit(kind: Kind, text: String = "") {
        if (tokens.size >= MAX_TOKENS) fail("脚本记号数超过限制（$MAX_TOKENS）")
        tokens.add(Token(kind, text, line))
    }

    private fun fail(message: String): Nothing = throw PineException(message, line)
}

private val declarationTypes = setOf("int", "float", "bool", "string", "color", "line", "label", "box")
private val reassignmentOperators = setOf(":=", "+=", "-=", "*=", "/=", "%=")
private val unsupportedWords = setOf(
    "varip", "while", "switch", "break", "continue", "import", "export", "type", "enum", "method",
    "const", "simple", "series",
)
private val syntaxWords = setOf("if", "else", "for", "to", "by", "in", "var", "not", "and", "or", "true", "false")

private class PineParser(private val tokens: List<Token>) {
    private var position = 0
    private var nextCallId = 0
    private var nextHistoryId = 0
    private val current: Token get() = tokens[position]
    private val next: Token get() = tokens.getOrElse(position + 1) { tokens.last() }

    fun parse(): PineProgram {
        val result = statements(depth = 0)
        expect(Kind.EOF, "意外的代码块结束")
        return PineProgram(result)
    }

    private fun statements(depth: Int): List<PineStmt> = buildList {
        while (current.kind != Kind.EOF && current.kind != Kind.DEDENT) add(statement(depth))
    }

    private fun statement(depth: Int): PineStmt {
        val start = current
        return when {
            start.kind == Kind.INDENT -> start.fail("这里不应缩进；只有 if、else 和 for 后可开启代码块")
            match("if") -> conditional(start, depth)
            match("for") -> loop(start, depth)
            start.isSyntax("else") -> start.fail("else 没有匹配的 if")
            else -> simpleStatement(start)
        }
    }

    private fun simpleStatement(start: Token): PineStmt {
        val persistent = match("var")
        val typed = current.isIdentifier() && next.isIdentifier()
        val result = when {
            persistent || typed || current.isIdentifier() && next.isSyntax("=") -> {
                if (typed) {
                    val type = advance()
                    if (type.text !in declarationTypes) type.fail("不支持类型标注 '${type.text}'")
                }
                val name = identifier("变量名")
                expect("=", "变量声明须用 '=' 初始化")
                PineStmt.Declare(name, expression().node, persistent, start.line)
            }
            current.isIdentifier() && next.isReassignment() -> {
                val name = advance().text
                val operator = advance().text
                PineStmt.Assign(name, operator, expression().node, start.line)
            }
            else -> {
                val value = expression().node
                if (current.isSyntax("=") || current.isReassignment()) current.fail("赋值目标须为简单变量名")
                PineStmt.Evaluate(value, start.line)
            }
        }
        expect(Kind.NEWLINE, "预期语句结束；此处语法不受支持")
        return result
    }

    private fun conditional(start: Token, depth: Int): PineStmt.If {
        checkControlDepth(start, depth)
        val condition = expression().node
        val thenBranch = block(start, depth + 1)
        val elseBranch = if (current.isSyntax("else")) {
            val otherwise = advance()
            if (current.isSyntax("if")) listOf(conditional(advance(), depth + 1)) else block(otherwise, depth + 1)
        } else {
            emptyList()
        }
        return PineStmt.If(condition, thenBranch, elseBranch, start.line)
    }

    private fun loop(start: Token, depth: Int): PineStmt.For {
        checkControlDepth(start, depth)
        val variable = identifier("循环变量名")
        expect("=", "仅支持 for i = 起点 to 终点 [by 步长]")
        val from = expression().node
        expect("to", "for 语句缺少 'to'")
        val to = expression().node
        val step = if (match("by")) expression().node else null
        return PineStmt.For(variable, from, to, step, block(start, depth + 1), start.line)
    }

    private fun checkControlDepth(start: Token, depth: Int) {
        if (depth >= MAX_BLOCK_DEPTH) start.fail("控制语句嵌套超过限制（$MAX_BLOCK_DEPTH）")
    }

    private fun block(header: Token, depth: Int): List<PineStmt> {
        expect(Kind.NEWLINE, "代码块须另起一行并缩进")
        if (current.kind != Kind.INDENT) {
            val at = if (current.kind == Kind.EOF || current.kind == Kind.DEDENT) header else current
            at.fail("'${header.text}' 后缺少缩进代码块")
        }
        advance()
        val result = statements(depth)
        expect(Kind.DEDENT, "预期代码块结束")
        return result
    }

    // The depth travels with each constructed node; left-associative loops can grow a deep AST
    // even if they use almost no parser recursion.
    private data class Expr(val node: PineExpr, val depth: Int = 1)

    private inline fun branch(at: Token, childDepth: Int, node: () -> PineExpr): Expr {
        if (childDepth >= MAX_EXPRESSION_DEPTH) at.fail("表达式树深度超过限制（$MAX_EXPRESSION_DEPTH）")
        return Expr(node(), childDepth + 1)
    }

    private fun expression(minPrecedence: Int = 1, depth: Int = 0): Expr {
        if (depth >= MAX_EXPRESSION_RECURSION) current.fail("表达式递归嵌套超过限制（$MAX_EXPRESSION_RECURSION）")
        val prefix = current
        var left = if (prefix.isSyntax("+") || prefix.isSyntax("-") || prefix.isSyntax("not")) {
            advance()
            val value = expression(minPrecedence = 8, depth = depth + 1)
            branch(prefix, value.depth) { PineExpr.Unary(prefix.text, value.node, prefix.line) }
        } else {
            postfix(depth)
        }
        while (true) {
            val operator = current
            val precedence = operator.precedence()
            if (precedence < minPrecedence) return left
            advance()
            left = if (operator.text == "?") {
                val yes = expression(depth = depth + 1)
                expect(":", "三元表达式缺少 ':'")
                val no = expression(depth = depth + 1)
                branch(operator, maxOf(left.depth, yes.depth, no.depth)) {
                    PineExpr.Choose(left.node, yes.node, no.node, operator.line)
                }
            } else {
                val right = expression(minPrecedence = precedence + 1, depth = depth + 1)
                branch(operator, maxOf(left.depth, right.depth)) {
                    PineExpr.Binary(left.node, operator.text, right.node, operator.line)
                }
            }
        }
    }

    private fun postfix(depth: Int): Expr {
        val value = primary(depth)
        if (!current.isSyntax("[")) return value
        val bracket = advance()
        if (value.node is PineExpr.History) bracket.fail("同一值不能连续使用两次历史引用 '[]'")
        val id = nextHistoryId++
        val offset = expression(depth = depth + 1)
        expect("]", "历史引用缺少 ']'")
        if (current.isSyntax("[")) current.fail("同一值不能连续使用两次历史引用 '[]'")
        return branch(bracket, maxOf(value.depth, offset.depth)) {
            PineExpr.History(value.node, offset.node, bracket.line, id)
        }
    }

    private fun primary(depth: Int): Expr {
        val token = current
        val node = when (token.kind) {
            Kind.NUMBER -> {
                val number = token.text.toDoubleOrNull() ?: token.fail("无效的数字字面量")
                if (!number.isFinite()) token.fail("数字字面量超出范围")
                PineExpr.Literal(number, token.line)
            }
            Kind.STRING -> PineExpr.Literal(token.text, token.line)
            Kind.COLOR -> PineExpr.ColorLiteral(token.text, token.line)
            Kind.WORD -> when (token.text) {
                "true", "false" -> PineExpr.Literal(token.text == "true", token.line)
                "if", "for" -> token.fail("不支持 '${token.text}' 作为表达式，请使用独立语句")
                in unsupportedWords -> token.fail("暂不支持 '${token.text}' 语法")
                in syntaxWords -> token.fail("预期表达式，不能在这里使用 '${token.text}'")
                else -> return pathOrCall(depth)
            }
            Kind.SYMBOL -> when (token.text) {
                "(" -> {
                    advance()
                    val value = expression(depth = depth + 1)
                    expect(")", "分组表达式缺少 ')'")
                    return value
                }
                "[" -> token.fail("暂不支持元组声明或元组表达式")
                else -> token.fail("预期表达式，不能在这里使用 '${token.text}'")
            }
            else -> token.fail("预期表达式")
        }
        advance()
        return Expr(node)
    }

    private fun pathOrCall(depth: Int): Expr {
        val origin = advance()
        val path = buildString {
            append(origin.text)
            while (match(".")) {
                val member = current
                if (!member.isIdentifier()) member.fail("'.' 后须为名称")
                advance()
                if (length + 1 + member.text.length > MAX_PATH_LENGTH) {
                    member.fail("名称路径长度不能超过 $MAX_PATH_LENGTH 个字符")
                }
                append('.').append(member.text)
            }
        }
        if (!match("(")) return Expr(PineExpr.Name(path, origin.line))
        val id = nextCallId++
        val arguments = mutableListOf<PineArg>()
        var childDepth = 0
        var sawNamed = false
        if (!match(")")) {
            do {
                // Built-in signatures can use names that have other syntactic roles (e.g. plot(series = ...)).
                val name = if (current.kind == Kind.WORD && next.isSyntax("=")) {
                    val argumentName = advance().text
                    advance()
                    argumentName
                } else {
                    if (sawNamed) current.fail("位置参数须位于命名参数之前")
                    null
                }
                sawNamed = sawNamed || name != null
                val value = expression(depth = depth + 1)
                childDepth = maxOf(childDepth, value.depth)
                arguments.add(PineArg(name, value.node))
            } while (match(","))
            expect(")", "调用参数之间应有 ','，末尾应有 ')'")
        }
        return branch(origin, childDepth) { PineExpr.Call(path, arguments, id, origin.line) }
    }

    private fun Token.precedence(): Int {
        if (kind != Kind.WORD && kind != Kind.SYMBOL) return 0
        return when (text) {
            "?" -> 1
            "or" -> 2
            "and" -> 3
            "==", "!=" -> 4
            "<", ">", "<=", ">=" -> 5
            "+", "-" -> 6
            "*", "/", "%" -> 7
            else -> 0
        }
    }

    private fun Token.isIdentifier(): Boolean = kind == Kind.WORD && text !in syntaxWords && text !in unsupportedWords
    private fun Token.isReassignment(): Boolean = kind == Kind.SYMBOL && text in reassignmentOperators

    private fun identifier(description: String): String {
        if (!current.isIdentifier()) current.fail("预期$description")
        return advance().text
    }

    private fun match(text: String): Boolean {
        if (!current.isSyntax(text)) return false
        advance()
        return true
    }

    private fun expect(text: String, message: String) {
        if (!match(text)) current.fail(message)
    }

    private fun expect(kind: Kind, message: String): Token {
        if (current.kind != kind) current.fail(message)
        return advance()
    }

    private fun advance(): Token = tokens[position++]
}
