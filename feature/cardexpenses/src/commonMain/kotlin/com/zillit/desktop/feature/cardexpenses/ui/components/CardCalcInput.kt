package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField

/**
 * An amount field that doubles as a calculator — the web's `CalcInput`
 * (`components/ui/CalcInput.jsx`).
 *
 * A plain number is committed as it is typed; an expression (`1200*3`) is
 * evaluated on Enter or when the field is left. Letters and a leading minus
 * are refused as they are typed, and an expression that does not evaluate —
 * or comes out negative — is discarded on commit, putting back the last good
 * value, as the web's `allowNegative={false}` does. [value] is always the
 * plain number text, blank for an empty field.
 *
 * The Account Hub and Bank Reconciliation each carry their own copy of this;
 * the card module cannot depend on either, so it has its own too.
 */
@Composable
fun CardCalcInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "0.00",
    enabled: Boolean = true,
    errorText: String? = null,
    /** Printed inside the field's end — the web's currency symbol after the amount. */
    suffix: String? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    fun commit() {
        val committed = CardCalc.committed(text, previous = value)
        text = committed
        if (committed != value) onValueChange(committed)
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            if (CardCalc.accepts(typed)) {
                text = typed
                if (CardCalc.isPlainNumber(typed) || typed.isBlank()) onValueChange(typed.replace(",", "").trim())
            }
        },
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        errorText = errorText,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
        onImeAction = ::commit,
        trailingContent = suffix?.takeIf { it.isNotBlank() }?.let { symbol ->
            {
                ZillitText(
                    text = symbol,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}

/** `+ - * /`, parentheses and decimals — enough for a sum typed into an amount. */
object CardCalc {
    private const val ALLOWED = "0123456789.,+-*/() "

    fun isPlainNumber(text: String): Boolean = text.trim().replace(",", "").toDoubleOrNull() != null

    fun accepts(typed: String): Boolean = typed.all { it in ALLOWED } && !typed.trimStart().startsWith('-')

    /** The evaluated [text], blank for blank, or [previous] when it does not evaluate or is negative. */
    fun committed(text: String, previous: String): String {
        val evaluated = evaluate(text) ?: return previous
        val number = evaluated.toDoubleOrNull()
        return if (number != null && number < 0) previous else evaluated
    }

    fun evaluate(text: String): String? {
        val cleaned = text.replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return ""
        val value = if (cleaned.none { it in "+-*/()" }) {
            cleaned.toDoubleOrNull()
        } else {
            runCatching { Parser(cleaned).parse() }.getOrNull()?.takeIf { it.isFinite() }
        } ?: return null
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double = expression().also { check(i == s.length) }

        private fun expression(): Double {
            var value = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val rhs = term()
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun term(): Double {
            var value = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val rhs = factor()
                value = if (op == '*') value * rhs else value / rhs
            }
            return value
        }

        private fun factor(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -factor()
            }
            if (i < s.length && s[i] == '(') {
                i++
                val value = expression()
                check(i < s.length && s[i] == ')')
                i++
                return value
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
