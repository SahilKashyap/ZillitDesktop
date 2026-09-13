package com.zillit.desktop.feature.dealmemo.domain.preview

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The money field's calculator (`utils/calcExpression.js`, `ui/CalcInput.jsx`):
 * `2*2` or `(100+20)*3` typed into an amount resolves on commit. A hand-written
 * parser over numbers, the four operators, unary signs and parentheses —
 * anything else, a division by zero or a non-finite result is no answer at all.
 */
object DealCalc {

    private val PLAIN_NUMBER = Regex("^-?\\d*\\.?\\d*$")
    private val ALLOWED_INPUT = Regex("^[\\d.+\\-*/()\\s]*$")
    private const val THOUSANDS = 3
    private const val CENTS = 100.0

    /** A keystroke the field accepts: digits, `.`, the operators, parentheses, spaces. */
    fun isAllowedInput(text: String): Boolean = ALLOWED_INPUT.matches(text)

    /** An expression rather than a plain number: `-5` is a number, `5-3` is not. */
    fun isExpression(text: String): Boolean {
        val s = text.trim()
        return s.isNotEmpty() && !PLAIN_NUMBER.matches(s)
    }

    /** `parseFloat(v) || 0` for a plain number; null for the empty states `""`, `-` and `.`. */
    fun plainValue(text: String): Double? {
        val s = text.trim()
        if (s.isEmpty() || s == "-" || s == ".") return null
        val value = s.toDoubleOrNull() ?: s.trimEnd('.').toDoubleOrNull() ?: 0.0
        return if (value.isNaN()) 0.0 else value
    }

    /** The expression's value, or null when it is malformed or not finite. */
    fun evaluate(text: String): Double? {
        val tokens = tokenize(text) ?: return null
        if (tokens.isEmpty()) return null
        return runCatching { Parser(tokens).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    /** `Math.round(n * 100) / 100`. */
    fun round2(value: Double): Double = (value * CENTS).roundToLong() / CENTS

    /** `groupAmount(n, 2)`: `1234.5` → `1,234.50`. */
    fun grouped(value: Double): String {
        val cents = abs(value * CENTS).roundToLong()
        val whole = (cents / CENTS.toLong()).toString()
        val fraction = (cents % CENTS.toLong()).toString().padStart(2, '0')
        val sign = if (value < 0 && cents != 0L) "-" else ""
        return "$sign${group(whole)}.$fraction"
    }

    /** Commas into the whole part of a typed number, the sign and any fraction kept as typed. */
    fun groupTyped(raw: String): String {
        val sign = if (raw.startsWith("-")) "-" else ""
        val body = raw.removePrefix(sign)
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val rest = if (dot < 0) "" else body.substring(dot)
        return sign + group(whole) + rest
    }

    private fun group(digits: String): String =
        digits.reversed().chunked(THOUSANDS).joinToString(",").reversed()

    private sealed interface Token {
        data class Num(val value: Double) : Token
        data class Op(val symbol: Char) : Token
    }

    private fun tokenize(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == ' ' || ch == '\t' -> i++
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    val number = text.substring(start, i)
                    if (number.count { it == '.' } > 1 || number == ".") return null
                    tokens += Token.Num(number.toDoubleOrNull() ?: return null)
                }
                ch in "+-*/()" -> {
                    tokens += Token.Op(ch)
                    i++
                }
                else -> return null
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var at = 0

        fun parse(): Double {
            val value = expression()
            check(at == tokens.size)
            return value
        }

        private fun peek(): Char? = (tokens.getOrNull(at) as? Token.Op)?.symbol

        private fun expression(): Double {
            var value = term()
            while (peek() == '+' || peek() == '-') {
                val op = peek()
                at++
                val rhs = term()
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun term(): Double {
            var value = factor()
            while (peek() == '*' || peek() == '/') {
                val op = peek()
                at++
                val rhs = factor()
                value = if (op == '*') {
                    value * rhs
                } else {
                    check(rhs != 0.0)
                    value / rhs
                }
            }
            return value
        }

        private fun factor(): Double {
            val op = peek()
            if (op == '+' || op == '-') {
                at++
                val inner = factor()
                return if (op == '-') -inner else inner
            }
            if (op == '(') {
                at++
                val value = expression()
                check(peek() == ')')
                at++
                return value
            }
            val token = tokens.getOrNull(at) as? Token.Num ?: error("number expected")
            at++
            return token.value
        }
    }
}
