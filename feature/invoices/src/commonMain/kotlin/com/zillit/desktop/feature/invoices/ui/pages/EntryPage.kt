package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryFilter
import com.zillit.desktop.feature.invoices.domain.EntrySort
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * Invoice Entry — the web's `EntryPage`.
 *
 * The third stage: an invoice that approval has cleared is coded here and
 * posted to the ledger. Who may touch a row is not a look but a rule — a
 * non-senior only sees their own assignments as actionable, and an
 * unassigned invoice is nobody's until a senior hands it over. A row the
 * reader may not open is dimmed with a lock beside its assignee
 * (`EntryPage.jsx:566`, `:588-592`).
 */
@Suppress("LongMethod") // Toolbar, bulk bar and table of one page; each part is a few lines.
@Composable
internal fun ColumnScope.EntryPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_invoices_entry),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitSelect(
            value = state.entrySort,
            options = EntrySort.entries,
            onSelect = { onEvent(InvoicesEvent.SelectEntrySort(it)) },
            label = { it.label },
            modifier = Modifier.width(ENTRY_SELECT_WIDTH),
        )
        // The web's four (`:495-501`) — the methods an entry row is paid by.
        ZillitSelect(
            value = state.payFilter,
            options = listOf<PayMethod?>(null) + ENTRY_PAY_FILTERS,
            onSelect = { onEvent(InvoicesEvent.SelectPayFilter(it)) },
            label = { it?.label ?: str(S.desktop_all_pay_methods) },
            modifier = Modifier.width(ENTRY_SELECT_WIDTH),
        )
        Spacer(Modifier.weight(1f))
        // The web's two floating buttons, in the toolbar — gone while rows are ticked, as there.
        if (state.selected.isEmpty()) {
            ZillitButton(
                text = str(S.desktop_enter_invoice),
                onClick = { onEvent(InvoicesEvent.OpenEnter) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.File,
            )
            ZillitButton(
                text = str(S.desktop_br_quick_entry),
                onClick = { onEvent(EntryEvent.StartQuick) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
    EntryFilterRow(state, onEvent)
    val rows = state.entryRows
    val selectable = state.entrySelectableIds
    EntryBulkBar(state, onEvent)
    TableCard(
        title = str(S.desktop_entry_queue),
        icon = ZillitIcons.File,
        action = {
            // The header tick: every row this reader may act on, or none.
            ZillitCheckbox(
                checked = selectable.isNotEmpty() && state.selected.containsAll(selectable),
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelectAll) },
                label = str(S.select_all),
                enabled = selectable.isNotEmpty(),
            )
            ZillitStatusPill(label = str(S.desktop_inv_awaiting_entry, rows.size), tone = StatusTone.Pending)
        },
    ) {
        ZillitDataTable(
            rows = rows,
            columns = entryColumns(state, onEvent),
            key = { it.id },
            // With a selection under way a click ticks, as the web's does; otherwise it opens
            // the coding screen — for a row this reader may open.
            onRowClick = { row ->
                when {
                    !state.canAccessEntry(row) -> Unit
                    state.selected.isNotEmpty() -> onEvent(InvoicesEvent.ToggleSelect(row.id))
                    else -> onEvent(EntryEvent.Open(row))
                }
            },
            isSelected = { it.id in state.selected },
            emptyTitle = str(S.desktop_inv_no_invoices_awaiting_entry),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The web's quick filters. */
@Composable
private fun EntryFilterRow(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.entryFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectEntryFilter(filter)) },
            )
        }
    }
}

/**
 * What can be done to everything ticked — the web's selection bar: Assign,
 * Submit for Review (not for a senior, who is the reviewer) and Clear.
 *
 * There is no bulk Post, on purpose: the web has none either, because posting
 * is gated on the coding screen — a bank, an effective date, the lines
 * reconciled and a nominal on every one — and a bulk post would skip them all.
 */
@Composable
private fun EntryBulkBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.selected.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = state.selected.size
        ZillitText(
            text = if (count == 1) {
                str(S.desktop_inv_one_invoice_selected, count)
            } else {
                str(S.desktop_inv_n_invoices_selected_many, count)
            },
            style = ZillitTheme.typography.bodySmall,
        )
        ZillitButton(
            text = str(S.assign),
            onClick = { onEvent(InvoicesEvent.StartAssign) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        if (!state.viewer.isSenior) {
            ZillitButton(
                text = str(S.desktop_submit_for_review),
                onClick = { onEvent(InvoicesEvent.ReviewSelected) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(InvoicesEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

@Suppress("LongMethod") // One cell per column, in the web's order.
private fun entryColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        // A row this viewer cannot open cannot be bulk-actioned either, or
        // select-all would hand them invoices they are not allowed to see; a
        // row dated in a closed period cannot be ticked at all, and says why.
        if (state.canAccessEntry(invoice)) {
            val locked = state.isLocked(invoice)
            ZillitTooltip(
                text = if (locked) str(S.desktop_inv_locked_period, state.periodLock.lockedThrough) else "",
            ) {
                ZillitCheckbox(
                    checked = invoice.id in state.selected,
                    onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
                    enabled = !locked,
                )
            }
        } else {
            Dimmed(true) { CellText("—", muted = true) }
        }
    },
    // The row's `invoice_entry` chip (`EntryPage.jsx:134-141, 581`).
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) {
        Dimmed(!state.canAccessEntry(it)) {
            CellTextWithUnread(it.displayNumber, state.pageRowUnread(AccountantPage.Entry, it.id))
        }
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) {
        Dimmed(!state.canAccessEntry(it)) { CellText(state.vendorName(it)) }
    },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        Dimmed(!state.canAccessEntry(it)) {
            CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
        }
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        Dimmed(!state.canAccessEntry(it)) { MoneyText(it.grossAmount, it.currency, state.projectCurrency) }
    },
    TableColumn(str(S.desktop_pay_method), ColumnWidth.Fixed(PAY_WIDTH)) {
        Dimmed(!state.canAccessEntry(it)) { ZillitStatusPill(label = it.payMethodLabel, tone = it.payMethod.tone()) }
    },
    TableColumn(str(S.assigned), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val name = state.assigneeName(invoice)
        if (state.canAccessEntry(invoice)) {
            CellText(name ?: str(S.unassigned), muted = name == null)
        } else {
            // Not this reader's to open: a lock beside whoever holds it (`:588-592`).
            Dimmed(true) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitIcon(icon = ZillitIcons.Lock, tint = ZillitTheme.colors.textMuted, size = LOCK_ICON)
                    CellText(name ?: str(S.unassigned), muted = true)
                }
            }
        }
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val review = invoice.status == InvoiceStatus.UnderReview
        Dimmed(!state.canAccessEntry(invoice)) {
            ZillitStatusPill(
                label = if (review) str(S.ah_under_review) else str(S.dd_csv_status_ready),
                tone = if (review) StatusTone.Progress else StatusTone.Ready,
            )
        }
    },
)

/** A row the reader may not open reads faded, as the web's `opacity-60`. */
@Composable
private fun Dimmed(dim: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (dim) DIMMED_ALPHA else 1f)) { content() }
}

/** The web's pay-method filter: BACs, Wire, Cheque and Faster Payment. */
private val ENTRY_PAY_FILTERS = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster)

internal val ENTRY_SELECT_WIDTH = 180.dp
private val LOCK_ICON = 12.dp
private const val DIMMED_ALPHA = 0.6f
