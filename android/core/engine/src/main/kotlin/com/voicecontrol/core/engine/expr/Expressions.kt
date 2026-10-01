package com.voicecontrol.core.engine.expr

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The flow expression language (conditions, computed values, question templates).
 *
 * Spec + shared test vectors: `docs/spec/expressions.json`. The backend validator and the dashboard
 * simulator implement the same grammar and are tested against the same vectors.
 *
 * Grammar:
 * ```
 * expr    := or
 * or      := and (("||" | "or") and)*
 * and     := not (("&&" | "and") not)*
 * not     := ("!" | "not") not | cmp
 * cmp     := add (("==" | "!=" | "<" | "<=" | ">" | ">=") add)?
 * add     := mul (("+" | "-") mul)*
 * mul     := unary (("*" | "/" | "%") unary)*
 * unary   := "-" unary | primary
 * primary := NUMBER | STRING | true | false | null | IDENT | IDENT "(" args ")" | "(" expr ")"
 * ```
 * Values are strings, numbers, booleans or null. Unknown variables are null. `+` adds when both sides
 * are numeric, otherwise concatenates. `==` compares numerically when possible, otherwise as trimmed,
 * case-insensitive text.
 */
object Expressions {

    class ExpressionException(message: String, val position: Int) : RuntimeException(message)

    sealed interface Node
    private data class Literal(val value: Any?) : Node
    private data class Variable(val name: String) : Node
    private data class Unary(val op: String, val operand: Node) : Node
    private data class Binary(val op: String, val left: Node, val right: Node) : Node
    private data class Call(val name: String, val args: List<Node>) : Node

    /** Function name → allowed argument counts (null = any). */
    val functions: Map<String, IntRange?> = mapOf(
        "upper" to 1..1, "lower" to 1..1, "trim" to 1..1, "len" to 1..1, "empty" to 1..1,
        "contains" to 2..2, "startsWith" to 2..2, "endsWith" to 2..2, "concat" to null,
        "digits" to 1..1, "left" to 2..2, "right" to 2..2, "round" to 1..1, "number" to 1..1,
        "if" to 3..3, "yes" to 1..1, "today" to 0..0, "year" to 0..0,
    )

    private val yesWords = setOf(
        "yes", "y", "yeah", "yep", "haan", "han", "ha", "haa", "ji", "hanji", "haanji", "ok", "okay", "sure", "true", "1",
        "हाँ", "हां", "हा", "जी", "होय", "ஆம்", "అవును", "হ্যাঁ", "হ্যা", "હા",
    )

    fun parse(source: String): Node = Parser(Lexer(source).tokens(), source).parseAll()

    /** Returns null when valid, otherwise a human-readable error. */
    fun validate(source: String): String? = try {
        parse(source)
        null
    } catch (e: ExpressionException) {
        e.message
    }

    /** Variable names referenced by an expression (for editor hints). */
    fun variables(source: String): Set<String> {
        val out = linkedSetOf<String>()
        fun walk(n: Node) {
            when (n) {
                is Variable -> out += n.name
                is Unary -> walk(n.operand)
                is Binary -> { walk(n.left); walk(n.right) }
                is Call -> n.args.forEach(::walk)
                is Literal -> Unit
            }
        }
        walk(parse(source))
        return out
    }

    fun evaluate(source: String, variables: (String) -> Any?, today: () -> LocalDate = { LocalDate.now() }): Any? =
        eval(parse(source), variables, today)

    fun evaluateBoolean(source: String, variables: (String) -> Any?, today: () -> LocalDate = { LocalDate.now() }): Boolean =
        truthy(evaluate(source, variables, today))

    fun evaluateText(source: String, variables: (String) -> Any?, today: () -> LocalDate = { LocalDate.now() }): String =
        text(evaluate(source, variables, today))

    /** Replaces `{name}` placeholders (or `{expression}`) in a question with values. */
    fun template(text: String, variables: (String) -> Any?, today: () -> LocalDate = { LocalDate.now() }): String =
        Regex("\\{([^{}]+)}").replace(text) { m ->
            runCatching { text(evaluate(m.groupValues[1], variables, today)) }.getOrElse { m.value }
        }

    // ---------------------------------------------------------------------------------------------
    // Value semantics

    fun number(v: Any?): Double? = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        else -> null
    }

    fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Int, is Long -> v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is Number -> v.toDouble() != 0.0
        else -> v.toString().trim().lowercase() !in setOf("", "false", "no", "0", "null")
    }

    private fun equal(a: Any?, b: Any?): Boolean {
        val na = number(a)
        val nb = number(b)
        if (na != null && nb != null) return na == nb
        return text(a).trim().equals(text(b).trim(), ignoreCase = true)
    }

    private fun compare(a: Any?, b: Any?): Int {
        val na = number(a)
        val nb = number(b)
        if (na != null && nb != null) return na.compareTo(nb)
        return text(a).trim().lowercase().compareTo(text(b).trim().lowercase())
    }

    private fun eval(n: Node, vars: (String) -> Any?, today: () -> LocalDate): Any? = when (n) {
        is Literal -> n.value
        is Variable -> vars(n.name)
        is Unary -> when (n.op) {
            "-" -> -(number(eval(n.operand, vars, today)) ?: 0.0)
            else -> !truthy(eval(n.operand, vars, today))
        }
        is Binary -> when (n.op) {
            "||" -> truthy(eval(n.left, vars, today)) || truthy(eval(n.right, vars, today))
            "&&" -> truthy(eval(n.left, vars, today)) && truthy(eval(n.right, vars, today))
            else -> {
                val l = eval(n.left, vars, today)
                val r = eval(n.right, vars, today)
                when (n.op) {
                    "==" -> equal(l, r)
                    "!=" -> !equal(l, r)
                    "<" -> compare(l, r) < 0
                    "<=" -> compare(l, r) <= 0
                    ">" -> compare(l, r) > 0
                    ">=" -> compare(l, r) >= 0
                    "+" -> {
                        val nl = number(l)
                        val nr = number(r)
                        if (nl != null && nr != null) nl + nr else text(l) + text(r)
                    }
                    "-" -> (number(l) ?: 0.0) - (number(r) ?: 0.0)
                    "*" -> (number(l) ?: 0.0) * (number(r) ?: 0.0)
                    "/" -> {
                        val d = number(r) ?: 0.0
                        if (d == 0.0) null else (number(l) ?: 0.0) / d
                    }
                    "%" -> {
                        val d = number(r) ?: 0.0
                        if (d == 0.0) null else (number(l) ?: 0.0) % d
                    }
                    else -> error("unknown operator ${n.op}")
                }
            }
        }
        is Call -> call(n, vars, today)
    }

    private fun call(c: Call, vars: (String) -> Any?, today: () -> LocalDate): Any? {
        if (c.name == "if") {
            return if (truthy(eval(c.args[0], vars, today))) eval(c.args[1], vars, today) else eval(c.args[2], vars, today)
        }
        val a = c.args.map { eval(it, vars, today) }
        fun s(i: Int) = text(a[i])
        fun n(i: Int) = number(a[i]) ?: 0.0
        return when (c.name) {
            "upper" -> s(0).uppercase()
            "lower" -> s(0).lowercase()
            "trim" -> s(0).trim()
            "len" -> s(0).length.toDouble()
            "empty" -> s(0).isBlank()
            "contains" -> s(0).contains(s(1), ignoreCase = true)
            "startsWith" -> s(0).startsWith(s(1), ignoreCase = true)
            "endsWith" -> s(0).endsWith(s(1), ignoreCase = true)
            "concat" -> a.joinToString("") { text(it) }
            "digits" -> s(0).filter { it in '0'..'9' }
            "left" -> s(0).take(n(1).toInt().coerceAtLeast(0))
            "right" -> s(0).takeLast(n(1).toInt().coerceAtLeast(0))
            "round" -> n(0).roundToLong().toDouble()
            "number" -> number(a[0])
            "yes" -> s(0).trim().lowercase() in yesWords
            "today" -> today().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            "year" -> today().year.toDouble()
            else -> throw ExpressionException("Unknown function ${c.name}", 0)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lexer + parser

    private data class Token(val kind: Kind, val text: String, val pos: Int)
    private enum class Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, END }

    private class Lexer(private val s: String) {
        fun tokens(): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                        out += Token(Kind.NUMBER, s.substring(start, i), start)
                    }
                    c == '\'' || c == '"' -> {
                        val start = i
                        val sb = StringBuilder()
                        i++
                        while (i < s.length && s[i] != c) {
                            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
                        }
                        if (i >= s.length) throw ExpressionException("Unterminated string", start)
                        i++
                        out += Token(Kind.STRING, sb.toString(), start)
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
                        out += Token(Kind.IDENT, s.substring(start, i), start)
                    }
                    c == '(' -> { out += Token(Kind.LPAREN, "(", i); i++ }
                    c == ')' -> { out += Token(Kind.RPAREN, ")", i); i++ }
                    c == ',' -> { out += Token(Kind.COMMA, ",", i); i++ }
                    else -> {
                        val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                        if (two in setOf("==", "!=", "<=", ">=", "&&", "||")) {
                            out += Token(Kind.OP, two, i); i += 2
                        } else if (c in "+-*/%<>!") {
                            out += Token(Kind.OP, c.toString(), i); i++
                        } else {
                            throw ExpressionException("Unexpected character '$c'", i)
                        }
                    }
                }
            }
            out += Token(Kind.END, "", s.length)
            return out
        }
    }

    private class Parser(private val t: List<Token>, private val source: String) {
        private var p = 0
        private fun peek() = t[p]
        private fun next() = t[p++]
        private fun isOp(vararg ops: String) = peek().let { (it.kind == Kind.OP && it.text in ops) || (it.kind == Kind.IDENT && it.text in ops) }

        fun parseAll(): Node {
            if (source.isBlank()) throw ExpressionException("Expression is empty", 0)
            val n = or()
            if (peek().kind != Kind.END) throw ExpressionException("Unexpected token '${peek().text}'", peek().pos)
            return n
        }

        private fun or(): Node {
            var n = and()
            while (isOp("||", "or")) { next(); n = Binary("||", n, and()) }
            return n
        }

        private fun and(): Node {
            var n = not()
            while (isOp("&&", "and")) { next(); n = Binary("&&", n, not()) }
            return n
        }

        private fun not(): Node {
            if (isOp("!", "not")) { next(); return Unary("!", not()) }
            return cmp()
        }

        private fun cmp(): Node {
            val n = add()
            if (isOp("==", "!=", "<", "<=", ">", ">=")) {
                val op = next().text
                return Binary(op, n, add())
            }
            return n
        }

        private fun add(): Node {
            var n = mul()
            while (isOp("+", "-")) { val op = next().text; n = Binary(op, n, mul()) }
            return n
        }

        private fun mul(): Node {
            var n = unary()
            while (isOp("*", "/", "%")) { val op = next().text; n = Binary(op, n, unary()) }
            return n
        }

        private fun unary(): Node {
            if (isOp("-")) { next(); return Unary("-", unary()) }
            return primary()
        }

        private fun primary(): Node {
            val tok = next()
            return when (tok.kind) {
                Kind.NUMBER -> Literal(tok.text.toDoubleOrNull() ?: throw ExpressionException("Bad number ${tok.text}", tok.pos))
                Kind.STRING -> Literal(tok.text)
                Kind.LPAREN -> {
                    val n = or()
                    if (next().kind != Kind.RPAREN) throw ExpressionException("Missing )", tok.pos)
                    n
                }
                Kind.IDENT -> when (tok.text) {
                    "true" -> Literal(true)
                    "false" -> Literal(false)
                    "null" -> Literal(null)
                    else -> if (peek().kind == Kind.LPAREN) call(tok) else Variable(tok.text)
                }
                Kind.END -> throw ExpressionException("Unexpected end of expression", tok.pos)
                else -> throw ExpressionException("Unexpected token '${tok.text}'", tok.pos)
            }
        }

        private fun call(name: Token): Node {
            next() // (
            val args = mutableListOf<Node>()
            if (peek().kind != Kind.RPAREN) {
                args += or()
                while (peek().kind == Kind.COMMA) { next(); args += or() }
            }
            if (next().kind != Kind.RPAREN) throw ExpressionException("Missing ) after arguments of ${name.text}", name.pos)
            if (name.text !in functions) throw ExpressionException("Unknown function ${name.text}", name.pos)
            val allowed = functions.getValue(name.text)
            if (allowed != null && args.size !in allowed) {
                throw ExpressionException("${name.text}() takes ${allowed.first}${if (allowed.last != allowed.first) "-${allowed.last}" else ""} argument(s)", name.pos)
            }
            return Call(name.text, args)
        }
    }
}
