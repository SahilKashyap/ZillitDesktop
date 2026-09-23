package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.BankDetail
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.domain.BankDetailType
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.IsoDate

/**
 * The web's `RichSelect` and friends, in Compose.
 *
 * ## Not a `DropdownMenu`
 *
 * The design system's menu hosts a lazy list, and a lazy list inside a menu
 * is measured against an unbounded height and crashes on open unless its
 * height is fixed (see the DropdownMenu note in memory). These lists are
 * composed in full inside a bounded, scrolling column under a `Popup`, which
 * is both correct and cheap at the sizes a picker ever reaches.
 */

/** A single-choice field with a searchable list. [value] null shows the placeholder; the clear ✕ sends null back. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun <T> HubSelect(
    value: T?,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    fieldLabel: String? = null,
    enabled: Boolean = true,
    searchable: Boolean = true,
    clearable: Boolean = false,
    secondary: ((T) -> String)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val colors = ZillitTheme.colors

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (fieldLabel != null) FieldLabel(fieldLabel)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ZillitDimens.controlHeight)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (enabled) colors.surface else colors.surfaceSunken)
                    .border(1.dp, if (open) colors.focusRing else colors.border, ZillitTheme.shapes.medium)
                    .clickable(enabled = enabled) { open = !open }
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = value?.let(label) ?: placeholder,
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (value == null) colors.textMuted else colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (clearable && value != null && enabled) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.ah_clear),
                        onClick = { onSelect(null) },
                    )
                }
                ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = ZillitDimens.iconSmall)
            }
            if (open) {
                PickerPopup(onDismiss = { open = false; search = "" }) {
                    if (searchable) {
                        ZillitSearchField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = str(S.search),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // The secondary line is searched too: a dial code is found by
                    // its country's name, not only by its digits.
                    val shown = options.filter {
                        search.isBlank() ||
                            label(it).contains(search, ignoreCase = true) ||
                            secondary?.invoke(it)?.contains(search, ignoreCase = true) == true
                    }
                    PickerList(
                        rows = shown,
                        selected = { it == value },
                        onPick = { onSelect(it); open = false; search = "" },
                        label = label,
                        secondary = secondary,
                        empty = if (options.isEmpty()) str(S.desktop_nothing_to_choose_from) else str(
                            S.desktop_hub_no_results_for_x,
                            search,
                        ),
                    )
                    PickerFooter(shown.size, options.size)
                }
            }
        }
    }
}

/** Several choices, shown as chips, with the same searchable list under a checkbox each — the web's `MultiSelect`. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> HubMultiSelect(
    selected: List<T>,
    options: List<T>,
    label: (T) -> String,
    onChange: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    fieldLabel: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val colors = ZillitTheme.colors

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (fieldLabel != null) FieldLabel(fieldLabel)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ZillitDimens.controlHeight)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (enabled) colors.surface else colors.surfaceSunken)
                    .border(1.dp, if (open) colors.focusRing else colors.border, ZillitTheme.shapes.medium)
                    .clickable(enabled = enabled) { open = !open }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                if (selected.isEmpty()) {
                    ZillitText(
                        text = placeholder,
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.xs),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        selected.forEach { item ->
                            Chip(text = label(item), onRemove = if (enabled) ({ onChange(selected - item) }) else null)
                        }
                    }
                }
                ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = ZillitDimens.iconSmall)
            }
            if (open) {
                PickerPopup(onDismiss = { open = false; search = "" }) {
                    ZillitSearchField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = str(S.search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val shown = options.filter { search.isBlank() || label(it).contains(search, ignoreCase = true) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = LIST_MAX)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (shown.isEmpty()) {
                            FieldHint(
                                if (options.isEmpty()) str(S.desktop_nothing_to_choose_from) else str(
                                    S.desktop_hub_no_results_for_x,
                                    search,
                                ),
                                Modifier.padding(ZillitTheme.spacing.sm),
                            )
                        }
                        shown.forEach { item ->
                            ZillitCheckbox(
                                checked = item in selected,
                                onCheckedChange = { on -> onChange(if (on) selected + item else selected - item) },
                                label = label(item),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
                            )
                        }
                    }
                    PickerFooter(shown.size, options.size, selected.size)
                }
            }
        }
    }
}

/** A removable chip, as a multi-select draws each choice. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.accentSoft)
            .padding(
                start = ZillitTheme.spacing.sm,
                end = if (onRemove == null) ZillitTheme.spacing.sm else ZillitTheme.spacing.xxs,
                top = 2.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = colors.accentText, maxLines = 1)
        if (onRemove != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.bs_chip_remove, text),
                onClick = onRemove,
                size = 18.dp,
                tint = colors.accentText,
            )
        }
    }
}

@Composable
private fun PickerPopup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Popup(
        offset = IntOffset(0, POPUP_DROP),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(POPUP_WIDTH)
                .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) { content() }
    }
}

@Composable
private fun <T> PickerList(
    rows: List<T>,
    selected: (T) -> Boolean,
    onPick: (T) -> Unit,
    label: (T) -> String,
    secondary: ((T) -> String)?,
    empty: String,
) {
    val colors = ZillitTheme.colors
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX).verticalScroll(rememberScrollState())) {
        if (rows.isEmpty()) FieldHint(empty, Modifier.padding(ZillitTheme.spacing.sm))
        rows.forEach { row ->
            val active = selected(row)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.surfaceSelected else colors.surfaceRaised)
                    .clickable { onPick(row) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = label(row), style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                    secondary?.invoke(row)?.takeIf { it.isNotBlank() }?.let { FieldHint(it) }
                }
                if (active) ZillitIcon(
                    icon = ZillitIcons.Check,
                    tint = colors.accentText,
                    size = ZillitDimens.iconSmall,
                )
            }
        }
    }
}

/** "N options · ↑↓ to move · ↵ to pick" — the web's footer, counts first. */
@Composable
private fun PickerFooter(shown: Int, total: Int, picked: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldHint(
            when {
                shown != total -> str(S.docusign_field_of, shown, total)
                total == 1 -> str(S.desktop_one_option)
                else -> str(S.desktop_n_options, total)
            },
        )
        if (picked != null) FieldHint(str(S.desktop_hub_n_selected_bullet, picked))
    }
}

// -- codes, amounts, dates ---------------------------------------------------

/**
 * A chart-code typeahead — the web's `CoaCodeInput` / `BsCodeInput`.
 *
 * Offers the active, postable leaves as the person types; a code not in the
 * chart can be committed as typed, and an accountant can create it on the
 * spot as a top-level category. Digits, hyphens and dots only, as the web
 * filters the input.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun CoaCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    accounts: List<CoaAccount>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "e.g. 4421",
    enabled: Boolean = true,
    costType: CoaCostType = CoaCostType.Expense,
    onCreate: ((code: String, name: String, costType: CoaCostType) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(value, accounts) { ChartOfAccounts.suggest(accounts, value) }
    val known = accounts.any { it.code.equals(value.trim(), ignoreCase = true) }
    val colors = ZillitTheme.colors

    Box(modifier = modifier) {
        ZillitTextField(
            value = value,
            onValueChange = { text ->
                onValueChange(text.filter { it.isDigit() || it == '-' || it == '.' || it.isLetter() })
            },
            label = label,
            placeholder = placeholder,
            enabled = enabled,
            helperText = when {
                value.isBlank() -> null
                known -> accounts.firstOrNull { it.code.equals(value.trim(), true) }?.name
                accounts.isEmpty() -> str(S.desktop_hub_the_chart_is_empty_the_code_will_be_stored_as)
                else -> str(S.desktop_hub_not_in_the_chart_stored_as_typed)
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val typingUnknownCode = focused && enabled && value.isNotBlank() && !known
        val hasChoices = suggestions.isNotEmpty() || onCreate != null
        if (typingUnknownCode && hasChoices) {
            Popup(offset = IntOffset(0, CODE_DROP), onDismissRequest = { focused = false }) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    suggestions.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable { onValueChange(account.code); focused = false }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = account.code,
                                style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
                            )
                            ZillitText(
                                text = account.name,
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    if (onCreate != null) {
                        ZillitButton(
                            text = str(S.desktop_hub_create_x_as_a_nominal, value.trim()),
                            onClick = { onCreate(value.trim(), value.trim(), costType); focused = false },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Add,
                        )
                    }
                }
            }
        }
    }
}

/**
 * An amount field that evaluates arithmetic — the web's `CalcInput`.
 *
 * `1500*3+200` becomes `4700` on Enter or when the field is left; thousands
 * are grouped for reading while the field is not focused. The stored value
 * is always the plain number text.
 *
 * As on the web, the field never holds garbage: letters are refused as they
 * are typed, a leading minus is refused unless [allowNegative], and an
 * expression that does not evaluate — or evaluates below zero — is discarded
 * on commit, putting back the last good value. It used to commit the text as
 * typed, which the wire then sent as null (or as a negative rate).
 */
@Composable
fun CalcField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "0.00",
    enabled: Boolean = true,
    helperText: String? = null,
    allowNegative: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember(value, focused) { mutableStateOf(if (focused) value else groupAmount(value)) }
    fun commit() {
        val committed = Calc.committed(text, previous = value, allowNegative = allowNegative)
        text = committed
        onValueChange(committed)
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            if (Calc.accepts(typed, allowNegative)) {
                text = typed
                if (Calc.isPlainNumber(typed)) onValueChange(typed.replace(",", ""))
            }
        },
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        helperText = helperText,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
        onImeAction = ::commit,
        modifier = modifier.onFocusChanged {
            val was = focused
            focused = it.isFocused
            if (was && !it.isFocused) commit()
        },
    )
}

private const val THOUSANDS = 3

/** `1234567.5` → `1,234,567.5`; anything that is not a number comes back untouched. */
fun groupAmount(raw: String): String {
    val number = raw.trim().replace(",", "").toDoubleOrNull() ?: return raw
    val whole = kotlin.math.abs(number).toLong().toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
    val fraction = raw.trim().replace(",", "").substringAfter('.', "")
    val sign = if (number < 0) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

/** A tiny expression evaluator: `+ - * /`, parentheses, decimals, thousands commas. */
object Calc {
    fun isPlainNumber(text: String): Boolean = text.trim().replace(",", "").toDoubleOrNull() != null

    /**
     * Whether [typed] may stand in the field at all — calculator characters
     * only, and no leading minus in a positive-only field (the web's
     * `isAllowedCalcInput` and its `allowNegative` guard). A minus inside an
     * expression is fine; only the result is checked, on commit.
     */
    fun accepts(typed: String, allowNegative: Boolean = false): Boolean =
        typed.all { it in ALLOWED } && (allowNegative || !typed.trimStart().startsWith('-'))

    /**
     * What a commit stores: the evaluated [text], blank for blank, or
     * [previous] when the text does not evaluate or comes out negative in a
     * positive-only field — discarded rather than clamped, as on the web.
     */
    fun committed(text: String, previous: String, allowNegative: Boolean = false): String {
        val evaluated = evaluate(text) ?: return previous
        val number = evaluated.toDoubleOrNull()
        return if (number != null && number < 0 && !allowNegative) previous else evaluated
    }

    private const val ALLOWED = "0123456789.,+-*/() "

    fun evaluate(text: String): String? {
        val cleaned = text.replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return ""
        if (cleaned.none { it in "+-*/()" }) return cleaned.toDoubleOrNull()?.let(::print)
        return runCatching { Parser(cleaned).parse() }.getOrNull()?.let(::print)
    }

    private fun print(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val value = expression()
            check(i == s.length)
            return value
        }

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

/** A `YYYY-MM-DD` field with the web's own guard rails: a min, a max, and a per-field error. */
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    min: String? = null,
    max: String? = null,
    errorText: String? = null,
) {
    val parsed = IsoDate.toEpochMillis(value)
    val minMillis = min?.let(IsoDate::toEpochMillis)
    val maxMillis = max?.let(IsoDate::toEpochMillis)
    val bounds = when {
        parsed == null -> null
        minMillis != null && parsed < minMillis -> str(S.desktop_hub_must_be_on_or_after_x, min)
        maxMillis != null && parsed > maxMillis -> str(S.desktop_hub_must_be_on_or_before_x, max)
        else -> null
    }
    // A calendar to pick from, as the web's `<input type="date">` has. Typing
    // still works — see [ZillitDateField].
    ZillitDateField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        enabled = enabled,
        errorText = errorText ?: bounds,
        // Only once something has been typed that will not save — a date
        // half-entered is not yet an error.
        helperText = value.takeIf { it.isNotBlank() && parsed == null }
            ?.let { str(S.desktop_hub_not_a_date_yet_use_yyyy_mm_dd) },
        modifier = modifier,
    )
}

// -- typed extra details -----------------------------------------------------

/**
 * The two-phase rows of a bank's extra details — the web's
 * `BankAdditionalDetailsEditor`: a row is defined (title and type), then
 * filled. A row without a title is never persisted, so it cannot block a
 * save; a filled row is soft-checked against its type as it is typed.
 */
@Suppress("LongMethod") // One detail row, read left to right; the order is the reading order.
@Composable
fun TypedDetailsEditor(
    rows: List<BankDetail>,
    onChange: (List<BankDetail>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    addLabel: String = str(S.desktop_add_detail),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (rows.isEmpty()) FieldHint(str(S.desktop_no_additional_details))
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = row.title,
                    onValueChange = { text ->
                        onChange(rows.mapIndexed { i, r -> if (i == index) r.copy(title = text) else r })
                    },
                    placeholder = str(S.desktop_field_name),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitSelect(
                    value = row.fieldType,
                    options = BankDetailType.entries,
                    onSelect = { type ->
                        onChange(rows.mapIndexed { i, r -> if (i == index) r.copy(fieldType = type) else r })
                    },
                    label = { it.label },
                    enabled = enabled,
                    modifier = Modifier.width(TYPE_WIDTH),
                )
                ZillitTextField(
                    value = row.value,
                    onValueChange = { text ->
                        onChange(rows.mapIndexed { i, r -> if (i == index) r.copy(value = text) else r })
                    },
                    placeholder = str(S.ah_addl_value_hint),
                    enabled = enabled,
                    errorText = if (row.isTitled && !row.isValid) str(
                        S.desktop_hub_not_a_valid_x,
                        row.fieldType.label.lowercase(),
                    ) else null,
                    keyboardType = when (row.fieldType) {
                        BankDetailType.Number -> KeyboardType.Decimal
                        BankDetailType.Email -> KeyboardType.Email
                        BankDetailType.Phone -> KeyboardType.Phone
                        BankDetailType.Url -> KeyboardType.Uri
                        BankDetailType.Text -> KeyboardType.Text
                    },
                    modifier = Modifier.weight(1f),
                )
                if (enabled) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.bs_chip_remove, row.title.ifBlank { str(S.desktop_detail) }),
                        onClick = { onChange(rows.filterIndexed { i, _ -> i != index }) },
                    )
                }
            }
        }
        if (enabled) GhostAddButton(addLabel, onClick = { onChange(rows + BankDetail()) })
    }
}

private val POPUP_WIDTH = 340.dp
private val LIST_MAX = 280.dp
private val TYPE_WIDTH = 120.dp
private val POPUP_ELEVATION = 12.dp
private const val POPUP_DROP = 36
private const val CODE_DROP = 60


/**
 * The code field's "create it" hand-off, for an accountant only.
 *
 * Null for everyone else, which hides the offer: creating a chart code is the
 * accounts department's, and a picker that offered it to a department user
 * would fail on the server after the fact.
 */
fun quickCreateHandler(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
): ((code: String, name: String, costType: CoaCostType) -> Unit)? =
    if (state.viewer.canActAsAccountant) {
        { code, name, costType -> onEvent(AccountHubEvent.QuickCreateCode(code, name, costType)) }
    } else {
        null
    }
