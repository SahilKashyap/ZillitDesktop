package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A money field that doubles as a calculator — the web's `CalcInput`.
 *
 * A plain number commits as it is typed, so running totals follow the
 * keyboard. An arithmetic expression (`2*2`, `(100+20)*3`) is not committed
 * live: it resolves, to two places, when the field is left or Enter is
 * pressed, and an expression that does not resolve is dropped — the field
 * goes back to the last committed figure. While an expression is being typed
 * its answer shows beneath as `= 1,234.00`.
 *
 * [value] is the committed amount as plain text (`"1200"`, `"1200.5"`); blank
 * is "nothing entered". A committed figure reads grouped to two places
 * (`1,200.00`), and a blank one shows the [placeholder], so an untouched
 * field stays distinguishable from a typed zero. [onCommit] gets
 * the plain text back — never the grouped display — and `""` when the field
 * is cleared (the web's `emptyValue=""`).
 */
@Composable
fun ZillitCalcField(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = "0.00",
    errorText: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    // Null while not editing: the field then mirrors the committed value.
    var draft by remember { mutableStateOf<String?>(null) }
    val editing = draft
    val expression = editing?.takeIf(CalcExpression::isExpression)
    val shown = when {
        editing != null -> editing
        else -> CalcExpression.committedDisplay(value)
    }

    fun settle() {
        val text = draft ?: return
        if (CalcExpression.isExpression(text)) {
            CalcExpression.evaluate(text)?.let { onCommit(CalcExpression.plain(CalcExpression.round2(it))) }
        }
        draft = null
    }

    Column(modifier) {
        ZillitTextField(
            value = shown,
            onValueChange = { typed ->
                val next = typed.replace(",", "")
                if (!CalcExpression.isAllowedInput(next)) return@ZillitTextField
                draft = next
                if (!CalcExpression.isExpression(next)) {
                    onCommit(CalcExpression.plainValue(next)?.let(CalcExpression::plain).orEmpty())
                }
            },
            label = label,
            placeholder = placeholder,
            errorText = errorText,
            helperText = helperText,
            enabled = enabled,
            imeAction = ImeAction.Done,
            onImeAction = { focusManager.clearFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.hasFocus) settle() },
        )
        if (expression != null) {
            val result = CalcExpression.evaluate(expression)
            ZillitText(
                text = result?.let { "= ${CalcExpression.grouped(it)}" } ?: "= —",
                style = ZillitTheme.typography.labelSmall,
                color = if (result == null) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The calculator behind [ZillitCalcField] — `utils/calcExpression.js`: a
 * hand-written parser over numbers, the four operators, unary signs and
 * parentheses. Anything else, a division by zero or a non-finite answer is no
 * answer at all.
 */
object CalcExpression {

    private val PLAIN_NUMBER = Regex("^-?\\d*\\.?\\d*$")
    private val ALLOWED_INPUT = Regex("^[\\d.+\\-*/()\\s]*$")
    private const val THOUSANDS = 3
    private const val CENTS = 100.0
    private const val TENTH = 10L

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

    /** `String(n)` for a cents amount: `1200`, `1200.5`, `1200.55` — never `1200.00`, never `1.2E3`. */
    fun plain(value: Double): String {
        val cents = abs(value * CENTS).roundToLong()
        val whole = cents / CENTS.toLong()
        val fraction = cents % CENTS.toLong()
        val sign = if (value < 0 && cents != 0L) "-" else ""
        val tail = when {
            fraction == 0L -> ""
            fraction % TENTH == 0L -> ".${fraction / TENTH}"
            else -> "." + fraction.toString().padStart(2, '0')
        }
        return "$sign$whole$tail"
    }

    /** `groupAmount(n, 2)`: `1234.5` → `1,234.50`. */
    fun grouped(value: Double): String {
        val cents = abs(value * CENTS).roundToLong()
        val whole = (cents / CENTS.toLong()).toString()
        val fraction = (cents % CENTS.toLong()).toString().padStart(2, '0')
        val sign = if (value < 0 && cents != 0L) "-" else ""
        return "$sign${group(whole)}.$fraction"
    }

    /** How a committed amount reads at rest: grouped to two places; blank shows the placeholder. */
    fun committedDisplay(value: String): String {
        val number = value.trim().replace(",", "").toDoubleOrNull() ?: return value.trim()
        return grouped(number)
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
