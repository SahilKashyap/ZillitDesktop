// Pieces the Inbox review and Enter Invoice share: the vendor picker with quick-add, the pickers
// with their web placeholders, the split strip and message, and the effective date's floor.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** The label above a field, in the forms' own style. */
@Composable
internal fun FieldLabel(text: String, isError: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.label,
        color = if (isError) ZillitTheme.colors.danger else ZillitTheme.colors.textSecondary,
    )
}

/**
 * The vendor picker — the web's typeable `RichSelect` over `usePendingVendor`:
 * search the vendors, or type a new name and "Create" it. A pending vendor
 * shows as `Name (new)`; nothing is created until the form is submitted.
 */
@Composable
internal fun VendorPicker(
    state: InvoicesUiState,
    vendorId: String,
    pendingName: String?,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val pending = pendingName?.takeIf { vendorId.isBlank() && it.isNotBlank() }
    val options = state.vendors.keys.toList() + listOfNotNull(pending?.let { PENDING_KEY })
    ZillitSearchSelect(
        value = if (pending != null) PENDING_KEY else vendorId.takeIf { it.isNotBlank() },
        options = options,
        onSelect = { id -> if (id != PENDING_KEY) onPick(id) },
        label = { id ->
            if (id == PENDING_KEY) {
                str(S.desktop_inv_vendor_new, pending.orEmpty())
            } else {
                state.vendors[id]?.name.orEmpty()
            }
        },
        searchText = { id -> if (id == PENDING_KEY) pending.orEmpty() else state.vendors[id]?.let { "${it.name} ${it.email}" }.orEmpty() },
        placeholder = str(S.desktop_po_vendor_search_placeholder),
        enabled = enabled,
        isError = isError,
        onCreate = onCreate,
        dropdownWidth = VENDOR_DROPDOWN,
        modifier = modifier.fillMaxWidth(),
    )
}

/** "New vendor “X” will be created when you accept." — said in words, under the picker. */
@Composable
internal fun PendingVendorHint(name: String?) {
    if (name.isNullOrBlank()) return
    ZillitText(
        text = str(S.desktop_inv_new_vendor_on_accept, name),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.warning,
    )
}

/** A searchable select over ids, with the web's placeholder when nothing is picked. */
@Composable
internal fun IdPicker(
    value: String,
    options: List<String>,
    label: (String) -> String,
    placeholder: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    searchText: (String) -> String = label,
) {
    ZillitSearchSelect(
        value = value.takeIf { it.isNotBlank() },
        options = options,
        onSelect = onSelect,
        label = label,
        searchText = searchText,
        placeholder = placeholder,
        enabled = enabled,
        isError = isError,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Departments in the admin's order, as the web lists them (no sort). */
internal fun InvoicesUiState.departmentChoices(): List<String> = departmentNames.keys.toList()

/** `currencyLabel(code, symbol)`: "GBP £", or the code alone when it has no symbol of its own. */
internal fun InvoicesUiState.currencyLabel(code: String): String {
    val symbol = currencyCatalogue.firstOrNull { it.code.equals(code, ignoreCase = true) }?.symbol.orEmpty()
    return if (symbol.isBlank()) code else "$code $symbol"
}

/** The day after the closed period — the effective date's `min` (`lockedMinDateInput`); null with no lock. */
internal fun InvoicesUiState.effectiveMinDate(): LocalDate? {
    val through = periodLock.lockedThrough.trim().take(DATE_LENGTH).takeIf { it.isNotBlank() } ?: return null
    return runCatching { LocalDate.parse(InvoiceFormat.plusDays(through, 1)) }.getOrNull()
}

/** A designation key as words — the web's `formatLabel`. */
internal fun designationText(raw: String): String = raw.takeIf { it.isNotBlank() }?.localised().orEmpty()

/**
 * The inline strip under the amounts — `AmountSplitWarning`:
 * `Amounts don't match · £n + £t = £s · £d over Gross`. Said, never blocking.
 */
@Composable
internal fun SplitStrip(amounts: AmountSplit, currency: String) {
    if (!InboxTriage.splitMismatch(amounts)) return
    val parts = SplitFigures.of(amounts)
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.warningSoft, ZillitTheme.shapes.medium)
            .border(1.dp, ZillitTheme.colors.warning.copy(alpha = STRIP_BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(
                S.desktop_inv_split_strip,
                money(parts.net),
                money(parts.tax),
                money(parts.sum),
                money(abs(parts.diff)),
                parts.overUnder(),
            ),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.warning,
        )
    }
}

/** `describeAmountSplit`'s figures: `diff` is sum − gross, positive = over. */
internal data class SplitFigures(val net: Double, val tax: Double, val sum: Double, val gross: Double, val diff: Double) {
    fun overUnder(): String = if (diff > 0) str(S.desktop_over_lower) else str(S.desktop_inv_under)

    companion object {
        fun of(amounts: AmountSplit): SplitFigures {
            val net = InboxTriage.amount(amounts.net)
            val tax = InboxTriage.amount(amounts.tax)
            val gross = InboxTriage.amount(amounts.gross)
            val sum = cents(net + tax)
            return SplitFigures(net, tax, sum, gross, cents(sum - gross))
        }

        private fun cents(value: Double): Double = kotlin.math.round(value * CENTS) / CENTS
    }
}

/** A column of a label and its field. */
@Composable
internal fun LabelledField(
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(label, isError)
        content()
    }
}

private const val PENDING_KEY = "\u0000pending-vendor"
private const val DATE_LENGTH = 10
private const val CENTS = 100.0
private const val STRIP_BORDER_ALPHA = 0.4f
private val VENDOR_DROPDOWN = 400.dp
