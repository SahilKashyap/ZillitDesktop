package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.CreditNoteSort
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineEdit

/**
 * Credit Notes & Disputes beyond the list itself — the web's `CreditsPage`
 * state: its Date and Sort pickers, the create/edit form, the preview, its
 * history and the delete confirmation.
 */
data class CreditNotesUi(
    val date: DateWindow = DateWindow.All,
    val sort: CreditNoteSort = CreditNoteSort.Newest,
    val form: CreditNoteForm? = null,
    val preview: CreditNote? = null,
    val history: CreditHistory? = null,
    val confirmDelete: CreditNote? = null,
    /** What the Against Invoice picker offers — read as the form opens. */
    val invoices: List<Invoice> = emptyList(),
    /** The row whose Apply / Resolve is on its way. */
    val applyingId: String? = null,
    /** The row being deleted — dimmed until the list comes back. */
    val deletingId: String? = null,
    /** An attachment open in the in-app viewer — the web's `CreditAttachmentViewer`. */
    val viewing: AttachmentView? = null,
    /** Who raised and last changed the note open in the preview — name and designation. */
    val people: Map<String, InvoiceAssignee> = emptyMap(),
)

/** One attachment being looked at: loading, its bytes, or the failure. */
data class AttachmentView(
    val attachment: CreditAttachment,
    val bytes: AttachmentBytes? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

data class CreditHistory(val note: CreditNote, val rows: List<HistoryEntry>? = null, val loading: Boolean = true)

/** The fields the save names when they are wrong. */
enum class CreditField { Vendor, EffectiveDate, InvoiceRef, Reason, Lines }

/**
 * A credit note or dispute being written — `CreditsPage`'s form view.
 *
 * The Against box searches as it is typed ([invoiceQuery]); picking a result
 * sets the link ([invoiceRef], the invoice number) and brings its vendor and
 * currency with it. A blank [currency] is the project's.
 */
data class CreditNoteForm(
    val editingId: String? = null,
    val type: CreditNoteType = CreditNoteType.CreditNote,
    val invoiceRef: String = "",
    val invoiceId: String = "",
    val invoiceQuery: String = "",
    val vendorId: String = "",
    val reason: String = "",
    /** `YYYY-MM-DD`, as the web's date input holds it. */
    val effectiveDate: String = "",
    val currency: String = "",
    val disputeAmount: String = "",
    val notes: String = "",
    val lines: LineDraft = LineDraft(),
    val attachments: List<CreditAttachment> = emptyList(),
    val errors: Map<CreditField, String> = emptyMap(),
    val saving: Boolean = false,
    /** Editing a note dated in a closed cost-report period: it can be looked at, not changed. */
    val locked: Boolean = false,
    /** The saved `line_items`, so an edit keeps the layers and tags this form does not show. */
    val savedLinesJson: String = "",
    /** The Against Invoice list is open — on focus, even with nothing typed (`CreditsPage.jsx:526`). */
    val pickerOpen: Boolean = false,
) {
    val isDispute: Boolean get() = type == CreditNoteType.Dispute
    val frozen: Boolean get() = locked || saving
}

/** Credit Notes & Disputes' own events, routed to [InvoiceCreditActions]. */
sealed interface CreditEvent : InvoicesEvent {
    data class New(val type: CreditNoteType) : CreditEvent
    data class Edit(val note: CreditNote) : CreditEvent
    data class Change(val form: CreditNoteForm) : CreditEvent
    data class Lines(val edit: LineEdit) : CreditEvent
    data class PickInvoice(val invoice: Invoice) : CreditEvent
    data object ClearInvoice : CreditEvent
    data object AddAttachment : CreditEvent
    data class RemoveAttachment(val index: Int) : CreditEvent
    data class OpenAttachment(val attachment: CreditAttachment) : CreditEvent
    data object CloseAttachment : CreditEvent
    data object DownloadAttachment : CreditEvent

    /** The Against Invoice list: opened as the box takes focus, shut by a click outside. */
    data object OpenInvoicePicker : CreditEvent
    data object CloseInvoicePicker : CreditEvent
    data object Save : CreditEvent
    data object CloseForm : CreditEvent

    /**
     * The preview. [fromRow] is a click on the row itself, which reads the
     * note's unread first (`CreditsPage.jsx:862`); the row's View button opens
     * the same preview and reads nothing (`CreditsPage.jsx:884-887`).
     */
    data class Preview(val note: CreditNote, val fromRow: Boolean = false) : CreditEvent
    data object ClosePreview : CreditEvent
    data object ShowHistory : CreditEvent
    data object HideHistory : CreditEvent
    data object RequestDelete : CreditEvent
    data object ConfirmDelete : CreditEvent
    data object CancelDelete : CreditEvent
    data class SelectDate(val date: DateWindow) : CreditEvent
    data class SelectSort(val sort: CreditNoteSort) : CreditEvent
}
