// Credit Notes & Disputes: the list, its preview, history and delete confirmation.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import com.zillit.desktop.feature.invoices.domain.SalesInvoices
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.CreditNoteFilter
import com.zillit.desktop.feature.invoices.domain.CreditNoteSort
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.CreditEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * Credit Notes & Disputes — the web's `CreditsPage` list: search, New Credit
 * Note, New Dispute and Export over the status chips and the Date and Sort
 * pickers, then the table. A row opens its preview; its button applies or
 * resolves what is still open, in an open period.
 */
@Suppress("LongMethod") // Toolbar, filter row and table of one page, as the web lays them out.
@Composable
internal fun ColumnScope.CreditNotesPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
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
            placeholder = str(S.desktop_inv_search_credit_notes),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        ZillitButton(
            text = str(S.desktop_inv_new_credit_note),
            onClick = { onEvent(CreditEvent.New(CreditNoteType.CreditNote)) },
            leadingIcon = ZillitIcons.Add,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = str(S.desktop_inv_new_dispute),
            onClick = { onEvent(CreditEvent.New(CreditNoteType.Dispute)) },
            leadingIcon = ZillitIcons.Add,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        CreditExportMenu(state.busy, onEvent)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CreditNoteFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.creditNoteFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectCreditNoteFilter(filter)) },
            )
        }
        Spacer(Modifier.weight(1f))
        PickerLabel(str(S.date))
        ZillitSelect(
            value = state.credit.date,
            options = DateWindow.entries,
            onSelect = { onEvent(CreditEvent.SelectDate(it)) },
            label = { it.label },
            modifier = Modifier.width(DATE_PICKER_WIDTH),
        )
        PickerLabel(str(S.drive_sort))
        ZillitSelect(
            value = state.credit.sort,
            options = CreditNoteSort.entries,
            onSelect = { onEvent(CreditEvent.SelectSort(it)) },
            label = { it.label },
            modifier = Modifier.width(SORT_PICKER_WIDTH),
        )
    }
    val rows = state.shownCreditNotes(nowMs)
    TableCard(
        title = str(S.desktop_credit_notes),
        icon = ZillitIcons.File,
        meta = countMeta(rows.size, S.desktop_credit_note_count_one, S.desktop_credit_note_count_other),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditNoteColumns(state, onEvent),
            key = { it.id },
            // The row itself reads the note's unread (`CreditsPage.jsx:862`).
            onRowClick = { onEvent(CreditEvent.Preview(it, fromRow = true)) },
            // Nine columns, as the web has: narrower floors keep Apply / Resolve on screen.
            minColumnWidth = CREDIT_MIN_COLUMN,
            emptyTitle = str(S.desktop_inv_no_credit_notes),
            emptyMessage = if (state.creditNotes.isEmpty()) {
                str(S.desktop_inv_credit_notes_none_yet)
            } else {
                str(S.desktop_inv_credit_notes_no_match)
            },
            loading = state.creditNotesLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PickerLabel(text: String) {
    ZillitText(
        text = "${text.uppercase()}:",
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * Export, grouped as the web's menu is (`CreditsPage.jsx:97-106`): a
 * "Credit Notes" heading and a "Disputes" one, each with Export PDF and
 * Export Excel, their one-line descriptions and a format badge.
 */
@Composable
private fun CreditExportMenu(busy: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = if (busy) str(S.desktop_exporting) else str(S.asset_export),
            onClick = { open = true },
            leadingIcon = ZillitIcons.Download,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !busy,
        )
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                InvoiceExport.CreditNotes to str(S.desktop_credit_notes),
                InvoiceExport.Disputes to str(S.desktop_inv_disputes),
            ).forEach { (export, title) ->
                ZillitText(
                    text = title.uppercase(),
                    style = ZillitTheme.typography.columnHeader,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(
                        horizontal = ZillitTheme.spacing.md,
                        vertical = ZillitTheme.spacing.xs,
                    ),
                )
                InvoiceExportFormat.entries.forEach { format ->
                    ExportOption(format) {
                        open = false
                        onEvent(InvoicesEvent.Export(export, format))
                    }
                }
            }
        }
    }
}

/** One export: the badge, "Export PDF" and what it makes. */
@Composable
private fun ExportOption(format: InvoiceExportFormat, onClick: () -> Unit) {
    val pdf = format == InvoiceExportFormat.Pdf
    Row(
        modifier = Modifier
            .width(EXPORT_MENU_WIDTH)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatusPill(label = format.extension.uppercase(), tone = if (pdf) StatusTone.Rejected else StatusTone.Done)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = str(if (pdf) S.recce_export_pdf else S.desktop_dm_export_excel),
                style = ZillitTheme.typography.label,
            )
            MutedLine(str(if (pdf) S.desktop_hub_formatted_document_print_ready else S.desktop_hub_editable_spreadsheet_with_live_data))
        }
        MutedLine(".${format.extension}")
    }
}

private fun creditNoteColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<CreditNote>> = listOf(
    // The note's `credit_notes` chip beside its ref (`CreditsPage.jsx:178-181, 864`).
    TableColumn(str(S.desktop_ref), ColumnWidth.Weight(1f)) { note ->
        Box(Modifier.alpha(if (state.credit.deletingId == note.id) DIMMED else 1f)) {
            CellTextWithUnread(note.displayRef, state.pageRowUnread(AccountantPage.Credits, note.id))
        }
    },
    TableColumn(str(S.type), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.type.label, tone = it.type.tone())
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) {
        CellText(it.vendorName.ifBlank { "—" })
    },
    TableColumn(str(S.reason), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.reason.ifBlank { it.description }.ifBlank { "—" }, muted = true)
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_against), ColumnWidth.Weight(1f)) {
        if (it.againstInvoice.isBlank()) {
            CellText("—", muted = true)
        } else {
            ZillitStatusPill(label = it.againstInvoice, tone = StatusTone.Neutral)
        }
    },
    TableColumn(str(S.date), ColumnWidth.Fixed(DUE_WIDTH)) {
        CellText(InvoiceFormat.date(it.effectiveDateMs), muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) {
        ZillitStatusPill(label = it.status.label, tone = it.status.tone())
    },
    TableColumn(str(S.dd_actions), ColumnWidth.Fixed(ACTIONS_WIDTH)) { note ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            RowAction(state, note, onEvent)
        }
    },
)

/** Apply / Resolve for what is still open (never in a closed period); View for the rest. */
@Composable
private fun RowAction(state: InvoicesUiState, note: CreditNote, onEvent: (InvoicesEvent) -> Unit) {
    val applying = state.credit.applyingId == note.id
    val locked = note.isOpen && state.periodLock.isLocked(note.effectiveDateMs)
    ZillitButton(
        text = when {
            applying && note.status == CreditNoteStatus.Disputed -> str(S.desktop_inv_resolving)
            applying -> str(S.desktop_inv_applying)
            else -> note.status.action
        },
        onClick = {
            onEvent(if (note.isOpen) InvoicesEvent.ActOnCreditNote(note) else CreditEvent.Preview(note))
        },
        variant = if (note.isOpen) ButtonVariant.Secondary else ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !applying && !locked,
    )
}

/** The web's per-status colour. */
internal fun CreditNoteStatus.tone(): StatusTone = when (this) {
    CreditNoteStatus.Applied -> StatusTone.Done
    CreditNoteStatus.Pending -> StatusTone.Pending
    CreditNoteStatus.Disputed -> StatusTone.Rejected
    CreditNoteStatus.Resolved -> StatusTone.Ready
}

private fun CreditNoteType.tone(): StatusTone =
    if (this == CreditNoteType.Dispute) StatusTone.Rejected else StatusTone.Progress

// -- the preview ---------------------------------------------------------------------

/**
 * One note, read — the web's preview modal: the vendor, the dates and
 * reason, its lines, notes and files, who raised and last changed it, then
 * History and — while it is open — Edit (View in a closed period), Delete
 * and Apply / Resolve.
 */
@Composable
internal fun CreditNotePreviewDialog(state: InvoicesUiState, note: CreditNote, onEvent: (InvoicesEvent) -> Unit) {
    val locked = state.periodLock.isLocked(note.effectiveDateMs)
    ZillitDialogShell(
        title = note.reference.ifBlank { str(S.desktop_credit_note) },
        onDismiss = { onEvent(CreditEvent.ClosePreview) },
        visible = true,
        width = PREVIEW_WIDTH,
        icon = ZillitIcons.File,
        actions = { PreviewActions(state, note, locked, onEvent) },
    ) {
        ZillitStatusPill(label = note.status.label, tone = note.status.tone())
        PreviewVendor(state, note)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            Fact(str(S.ah_lbl_eff_date), InvoiceFormat.date(note.effectiveDateMs))
            Fact(str(S.asset_currency), note.currency.ifBlank { state.projectCurrency })
            Fact(str(S.desktop_inv_against_invoice), note.againstInvoice.ifBlank { "—" })
        }
        Fact(str(S.reason), note.reason.ifBlank { "—" }, Modifier.fillMaxWidth())
        if (note.lineItems.isNotEmpty()) PreviewLines(state, note)
        if (note.notes.isNotBlank()) Fact(str(S.notes), note.notes, Modifier.fillMaxWidth())
        if (note.attachments.isNotEmpty()) AttachmentList(note.attachments, removable = false, onEvent = onEvent)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            AuditFact(state, str(S.ah_lbl_created_by), note.createdBy, note.createdAtMs)
            if (note.updatedBy.isNotBlank()) {
                AuditFact(state, str(S.ah_lbl_updated_by), note.updatedBy, note.updatedAtMs)
            }
        }
    }
}

@Composable
private fun RowScope.PreviewActions(
    state: InvoicesUiState,
    note: CreditNote,
    locked: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    ZillitButton(
        text = str(S.history),
        onClick = { onEvent(CreditEvent.ShowHistory) },
        leadingIcon = ZillitIcons.Clock,
        variant = ButtonVariant.Tertiary,
    )
    Spacer(Modifier.weight(1f))
    if (note.isOpen) {
        ZillitButton(
            text = if (locked) str(S.view) else str(S.edit),
            onClick = { onEvent(CreditEvent.Edit(note)) },
            variant = ButtonVariant.Secondary,
        )
        if (!locked) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.desktop_inv_delete_credit_note),
                onClick = { onEvent(CreditEvent.RequestDelete) },
            )
            val applying = state.credit.applyingId == note.id
            val dispute = note.type == CreditNoteType.Dispute
            ZillitButton(
                text = when {
                    applying && dispute -> str(S.desktop_inv_resolving)
                    applying -> str(S.desktop_inv_applying)
                    dispute -> str(S.desktop_inv_resolve_dispute)
                    else -> str(S.desktop_inv_apply_credit_note)
                },
                onClick = { onEvent(InvoicesEvent.ActOnCreditNote(note)) },
                enabled = state.credit.applyingId == null,
                loading = applying,
            )
        }
    }
    ZillitButton(
        text = str(S.close),
        onClick = { onEvent(CreditEvent.ClosePreview) },
        variant = ButtonVariant.Tertiary,
    )
}

@Composable
private fun PreviewVendor(state: InvoicesUiState, note: CreditNote) {
    val vendor = state.vendors[note.vendorId]
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FactLabel(str(S.ah_lbl_vendor))
            ZillitText(text = vendor?.name ?: note.vendorName.ifBlank { "—" }, style = ZillitTheme.typography.label)
            vendor?.address?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
            listOfNotNull(vendor?.phone, vendor?.email).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                MutedLine(it.joinToString(" · "))
            }
        }
        ZillitStatusPill(label = note.typeLabel, tone = note.type.tone())
    }
}

/** Created By / Updated By: the name, the designation, and `DD Mon YYYY | hh:mm AM` (`CreditsPage.jsx:1046-1063`). */
@Composable
private fun AuditFact(state: InvoicesUiState, label: String, userId: String, atMs: Long?) {
    val person = state.credit.people[userId]
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(label)
        ZillitText(text = person?.name ?: state.userNames[userId] ?: "—", style = ZillitTheme.typography.label)
        person?.role?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        if (atMs != null) MutedLine(SalesInvoices.stamp(atMs))
    }
}

/** The web's preview table: Description, Qty, Unit, Tax, Tax Amt, Amount, and the Gross Total under it. */
@Composable
private fun PreviewLines(state: InvoicesUiState, note: CreditNote) {
    val currency = note.currency.ifBlank { state.projectCurrency }
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(str(S.ah_line_items))
        PreviewLine(
            listOf(
                str(S.description),
                str(S.ah_lbl_qty),
                str(S.dm_step2_unit),
                str(S.ah_lbl_vat),
                str(S.desktop_inv_tax_amt),
                str(S.amount),
            ),
            header = true,
        )
        note.lineItems.forEach { line ->
            val tax = note.lineTaxAmounts[line.id] ?: 0.0
            PreviewLine(
                listOf(
                    (if (line.isSplit) "↳ " else "") + line.description.ifBlank { "—" },
                    InvoiceFormat.plain(line.quantity),
                    money(line.unitPrice),
                    "${InvoiceFormat.plain(line.taxRate ?: 0.0)}%",
                    money(tax),
                    money(line.amount + tax),
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Total(str(S.ah_lbl_gross_total), money(note.grossAmount), strong = true)
        }
    }
}

@Composable
private fun PreviewLine(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        cells.forEachIndexed { index, text ->
            ZillitText(
                text = if (header) text.uppercase() else text,
                style = if (header) ZillitTheme.typography.columnHeader else ZillitTheme.typography.bodySmall,
                color = if (header) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
                maxLines = 1,
                modifier = if (index == 0) Modifier.weight(DESCRIPTION_SHARE) else Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.label)
    }
}

@Composable
internal fun FactLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
    )
}

// -- history and delete --------------------------------------------------------------

@Composable
internal fun CreditHistorySheet(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val history = state.credit.history ?: return
    HistorySheet(
        invoiceNumber = history.note.displayRef,
        rows = history.rows,
        loading = history.loading,
        nameOf = { id -> state.userNames[id] ?: id.ifBlank { str(S.desktop_unknown) } },
        onClose = { onEvent(CreditEvent.HideHistory) },
        subtitle = history.note.displayRef,
    )
}

@Composable
internal fun CreditDeleteDialog(note: CreditNote, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_delete_credit_note),
        onDismiss = { onEvent(CreditEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CreditEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(CreditEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_inv_delete_credit_confirm, note.reference.ifBlank { str(S.desktop_inv_this_credit_note) }),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

private const val DIMMED = 0.4f
private val CREDIT_MIN_COLUMN = 84.dp
private const val DESCRIPTION_SHARE = 2.4f
private val DATE_PICKER_WIDTH = 150.dp
private val SORT_PICKER_WIDTH = 170.dp
private val PREVIEW_WIDTH = 680.dp
private val EXPORT_MENU_WIDTH = 300.dp
