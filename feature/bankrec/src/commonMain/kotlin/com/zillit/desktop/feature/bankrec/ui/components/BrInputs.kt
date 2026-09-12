package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.CostCentre
import com.zillit.desktop.feature.bankrec.domain.NominalCode
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.TaxOption

/**
 * A chart-of-accounts code typeahead — the web's `CoaCodeInput`.
 *
 * Offers the postable codes matching what has been typed, by code or by name;
 * a code not in the chart is kept as typed, and the list says so. Digits and
 * hyphens only, as the web filters it: codes are numeric, and segmented budget
 * codes carry a hyphen. A `Popup` rather than a menu, because a menu takes the
 * focus from the field the person is typing in.
 */
@Suppress("LongMethod") // The field and its suggestion popup share focus state.
@Composable
internal fun NominalCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    codes: List<NominalCode>,
    modifier: Modifier = Modifier,
    placeholder: String = "Search code or name…",
) {
    var focused by remember { mutableStateOf(false) }
    val query = value.trim()
    val matches = remember(query, codes) { rankCodes(codes, query).take(MAX_SUGGESTIONS) }
    val known = codes.firstOrNull { it.code == query }
    val colors = ZillitTheme.colors
    val drop = with(LocalDensity.current) { CODE_DROP.roundToPx() }

    Box(modifier) {
        ZillitTextField(
            value = value,
            onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == '-' }.trimStart('-')) },
            placeholder = placeholder,
            helperText = when {
                query.isEmpty() -> null
                known != null -> known.name
                else -> "Not in the Chart of Accounts — used as typed."
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val offer = matches.isNotEmpty() || codes.isEmpty()
        if (focused && known == null && offer) {
            Popup(
                offset = IntOffset(0, drop),
                onDismissRequest = { focused = false },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(POPUP_WIDTH).shadow(10.dp, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large).background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large),
                ) {
                    if (codes.isEmpty()) {
                        ZillitText(
                            "This project's Chart of Accounts is empty — add codes in Chart of Accounts first.",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    // A plain scrolling column: a rail would stretch a short
                    // list to the popup's full height.
                    Column(Modifier.heightIn(max = POPUP_HEIGHT).verticalScroll(rememberScrollState())) {
                        matches.forEach { code ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onValueChange(code.code)
                                    focused = false
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ZillitText(code.code, style = mono(12.sp, FontWeight.Medium), maxLines = 1)
                                ZillitText(
                                    code.name,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Code matches first, then name matches; the whole chart, by code, when nothing is typed. */
internal fun rankCodes(codes: List<NominalCode>, query: String): List<NominalCode> {
    val sorted = codes.sortedWith(compareBy({ it.code.toLongOrNull() ?: Long.MAX_VALUE }, { it.code }))
    if (query.isEmpty()) return sorted
    val q = query.lowercase()
    val byCode = sorted.filter { it.code.lowercase().startsWith(q) }
    val inCode = sorted.filter { q in it.code.lowercase() && it !in byCode }
    val inName = sorted.filter { q in it.name.lowercase() && it !in byCode && it !in inCode }
    return byCode + inCode + inName
}

/**
 * An amount that evaluates simple arithmetic on commit — the web's
 * `CalcInput`: `1500*3+200` is `4700` when Enter is pressed or the field is
 * left. The value is always the plain number text.
 */
@Composable
internal fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
    currency: String? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    fun commit() {
        val evaluated = Arithmetic.evaluate(text)
        if (evaluated != null && evaluated != value) onValueChange(evaluated) else if (evaluated == null) text = value
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || it in ARITHMETIC }
            if (Arithmetic.isPlainNumber(text)) onValueChange(text.replace(",", ""))
        },
        placeholder = currency?.let { "${Money.symbol(it)}$placeholder" } ?: placeholder,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
        onImeAction = ::commit,
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}

/** `+ - * /`, parentheses, decimals — enough for a sum typed into an amount. */
internal object Arithmetic {
    fun isPlainNumber(text: String): Boolean = text.trim().replace(",", "").toDoubleOrNull() != null

    /** The result as field text, "" for an empty field, or null for something that is not a sum. */
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

/**
 * The tax selector: Production Setup's types grouped by country, then the
 * custom rate — and, under it, the rate the choice carries. A predefined type's
 * rate is shown and not editable; "Other" takes a typed percentage, clamped to
 * 0–100 as the web clamps it.
 */
@Composable
internal fun TaxField(
    form: QuickAddForm,
    options: List<TaxOption>,
    onChange: (QuickAddForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GroupedSelect(
            value = form.vatType,
            groups = taxGroups(options),
            label = { id ->
                when (id) {
                    "" -> "— Select tax… —"
                    TaxOption.OTHER -> "Other"
                    else -> options.firstOrNull { it.identifier == id }?.optionLabel ?: id
                }
            },
            onSelect = { id -> onChange(form.withTax(id, options)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when (form.vatType) {
            "" -> Unit
            TaxOption.OTHER -> ZillitTextField(
                value = form.vatRate?.let { TaxOption.percentText(it) }.orEmpty(),
                onValueChange = { text ->
                    val clamped = text.toDoubleOrNull()?.coerceIn(0.0, MAX_RATE)
                    onChange(form.copy(vatRate = if (text.isBlank()) null else clamped ?: form.vatRate))
                },
                placeholder = "%",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> ZillitTextField(
                value = form.vatRate?.let { "${TaxOption.percentText(it)}%" }.orEmpty(),
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The form with a new tax type chosen.
 *
 * Only an existing *custom* rate survives a switch to "Other" — moving there
 * from a predefined type must not inherit its rate, which the web shipped once
 * and fixed.
 */
internal fun QuickAddForm.withTax(id: String, options: List<TaxOption>): QuickAddForm {
    val option = options.firstOrNull { it.identifier == id }
    val rate = when {
        option?.ratePercent != null -> option.ratePercent
        id == TaxOption.OTHER && vatType == TaxOption.OTHER -> vatRate ?: 0.0
        else -> 0.0
    }
    return copy(vatType = id, vatRate = rate)
}

private fun taxGroups(options: List<TaxOption>): List<Pair<String?, List<String>>> {
    val byCountry = options.filter { it.country.isNotBlank() }.groupBy { it.country.trim() }.toSortedMap()
    val custom = options.filter { it.country.isBlank() }.map { it.identifier } + TaxOption.OTHER
    return listOf<Pair<String?, List<String>>>(null to listOf("")) +
        byCountry.map { (country, rows) -> country to rows.map { it.identifier } } +
        ("Custom" to custom)
}

/** A select whose options sit under group headings — a tax type's country. */
@Composable
internal fun GroupedSelect(
    value: String,
    groups: List<Pair<String?, List<String>>>,
    label: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(ZillitTheme.shapes.medium)
                .background(colors.surface).border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                label(value),
                style = ZillitTheme.typography.bodyMedium,
                color = if (value.isBlank()) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, tint = colors.textMuted, size = 14.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.surfaceRaised),
        ) {
            groups.forEach { (heading, ids) ->
                heading?.let {
                    ZillitText(
                        it.uppercase(),
                        style = eyebrow(10.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                ids.forEach { id ->
                    DropdownMenuItem(
                        text = {
                            ZillitText(
                                label(id),
                                style = ZillitTheme.typography.bodyMedium,
                                color = if (id == value) colors.accentText else colors.textPrimary,
                            )
                        },
                        onClick = {
                            open = false
                            onSelect(id)
                        },
                        modifier = Modifier.background(
                            if (id == value) colors.surfaceSelected else colors.surfaceRaised,
                        ),
                    )
                }
            }
        }
    }
}

/** The shared cost-centre list — one for both quick forms. */
@Composable
internal fun CostCentreSelect(value: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    ZillitSelect(
        value = value,
        options = listOf("") + CostCentre.entries.map { it.code },
        onSelect = onSelect,
        label = { code -> CostCentre.entries.firstOrNull { it.code == code }?.label ?: "Select cost centre…" },
        modifier = modifier,
    )
}

/** A date field capped by the cost report's lock — the web's `min` on its date input. */
@Composable
internal fun LockedDateField(
    value: String,
    onValueChange: (String) -> Unit,
    lockedThrough: String?,
    modifier: Modifier = Modifier,
) {
    val locked = lockedThrough?.takeIf { it.isNotBlank() }
    val blocked = locked != null && value.isNotBlank() && value <= locked
    ZillitDateField(
        value = value,
        onValueChange = onValueChange,
        errorText = if (blocked) "Must be after $locked — the cost report is locked." else null,
        helperText = if (!blocked && locked != null) "Locked through $locked" else null,
        modifier = modifier,
    )
}

/**
 * A few lines of free text — a sign-off note. The web's textarea: it opens
 * three lines tall rather than as a one-line field that a paragraph has to
 * scroll inside.
 */
@Composable
internal fun BrNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = NOTE_HEIGHT).clip(ZillitTheme.shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, if (focused) colors.accent else colors.border, ZillitTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) {
                    ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
                }
                inner()
            }
        },
    )
}

private const val MAX_SUGGESTIONS = 60
private val NOTE_HEIGHT = 76.dp
private const val MAX_RATE = 100.0
private const val ARITHMETIC = ".,+-*/() "
private val POPUP_WIDTH = 300.dp
private val CODE_DROP = 38.dp
private val POPUP_HEIGHT = 256.dp
