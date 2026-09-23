package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.InboxField
import com.zillit.desktop.feature.invoices.domain.InboxValues
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PoPick
import com.zillit.desktop.feature.invoices.domain.PoSuggestions

/** The Inbox page's two halves — the web's `?tab=` toggle. */
enum class InboxTab(private val labelKey: String) {
    Queue(S.desktop_inv_inbox_queue),
    Uploads(S.desktop_inv_ongoing_uploads),
    ;

    val label: String get() = str(labelKey)
}

/** The Inbox review's form — `InboxReviewModal`'s `form`, as typed. Dates are `YYYY-MM-DD`. */
data class InboxForm(
    val invoiceNumber: String = "",
    val vendorId: String = "",
    val description: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val effectiveDate: String = "",
    val amounts: AmountSplit = AmountSplit("", "", "", grossAnchored = true),
    val picks: List<PoPick> = emptyList(),
    val payMethod: PayMethod = PayMethod.Bacs,
    val departmentId: String = "",
    /** Blank shows — and sends — the project's currency. */
    val currency: String = "",
    val companyId: String = "",
    val bankId: String = "",
    val episode: String = "",
    /** Stored on every picked order's link after the accept lands. */
    val matchNotes: String = "",
) {
    fun values(defaultCurrency: String): InboxValues = InboxValues(
        vendorId = vendorId,
        departmentId = departmentId,
        currency = currency.ifBlank { defaultCurrency },
        effectiveDate = effectiveDate,
        payMethod = payMethod.wire,
        gross = amounts.gross.trim().replace(",", "").toDoubleOrNull(),
    )

    companion object {
        /** Seeded from the full record; a saved gross is the anchor from the start. */
        fun of(invoice: Invoice, vendorId: String): InboxForm = InboxForm(
            invoiceNumber = invoice.invoiceNumber,
            vendorId = vendorId,
            description = invoice.description,
            invoiceDate = InvoiceFormat.toDateInput(invoice.invoiceDateMs),
            dueDate = InvoiceFormat.toDateInput(invoice.dueDateMs),
            effectiveDate = InvoiceFormat.toDateInput(invoice.effectiveDateMs),
            amounts = AmountSplit(
                net = invoice.netAmount?.let(InvoiceFormat::plain).orEmpty(),
                tax = invoice.taxAmount?.let(InvoiceFormat::plain).orEmpty(),
                gross = InvoiceFormat.plain(invoice.grossAmount),
                grossAnchored = true,
            ),
            picks = invoice.linkedPos.map { PoPick(it.poId, it.poNumber, it.poGrossTotal) },
            payMethod = invoice.payMethod,
            departmentId = invoice.departmentId,
            currency = invoice.currency,
            companyId = invoice.companyId,
            bankId = invoice.bankId,
            episode = invoice.episode,
        )
    }
}

/**
 * One inbox invoice open for review — the web's `InboxReviewModal`: the
 * document beside the editable form, the order suggestions, and the two
 * confirmations Accept can raise.
 */
data class InboxReview(
    val invoice: Invoice,
    val form: InboxForm,
    val loading: Boolean = true,
    val suggestions: PoSuggestions = PoSuggestions(),
    val suggestionsLoading: Boolean = false,
    val preview: AttachmentBytes? = null,
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
    /** Required fields Accept found empty; each clears as it is filled. */
    val errors: Set<InboxField> = emptySet(),
    /** "Amounts don't match" is up. */
    val confirmSplit: Boolean = false,
    /** "No PO Selected" is up. */
    val confirmNoPo: Boolean = false,
    val busy: Boolean = false,
)

/**
 * Files picked for a bulk upload, checked and waiting to be sent — the web's
 * `BulkUploadPanel` before its batch starts.
 */
data class BulkPick(
    val files: List<BulkFile> = emptyList(),
    /** The accountant's upload may mark a file already paid; the department's may not. */
    val allowPaid: Boolean = false,
    val checking: Boolean = false,
) {
    val sendable: Int get() = files.count { it.problem == null }
}

/** A queue row refused by bulk Process, and what it is missing. */
data class BlockedEntry(val invoice: Invoice, val missing: List<InboxField>)

/** The Inbox's own events, routed to [InvoiceInboxActions]. */
sealed interface InboxEvent : InvoicesEvent {
    data class SelectTab(val tab: InboxTab) : InboxEvent
    data class Open(val invoice: Invoice) : InboxEvent
    data object Close : InboxEvent
    data class Edit(val form: InboxForm) : InboxEvent
    data class EditAmount(val field: AmountField, val value: String) : InboxEvent
    data class AddPo(val pick: PoPick) : InboxEvent
    data class RemovePo(val id: String) : InboxEvent
    data object Accept : InboxEvent
    data object ConfirmSplit : InboxEvent
    data object ConfirmNoPo : InboxEvent
    data object CancelConfirm : InboxEvent

    /** The queue's bulk Process: one opens its review, two or more are checked then sent. */
    data object ProcessSelected : InboxEvent
    data object DismissBlocked : InboxEvent

    // -- bulk upload ---------------------------------------------------------

    /** Opens the file picker, then the checked list; [allowPaid] for the accountant's. */
    data class StartBulk(val allowPaid: Boolean) : InboxEvent
    data object AddBulkFiles : InboxEvent
    data class ToggleBulkPaid(val ref: Int) : InboxEvent
    data class RemoveBulkFile(val ref: Int) : InboxEvent
    data object SubmitBulk : InboxEvent
    data object CancelBulk : InboxEvent
    data class DismissBatch(val id: String) : InboxEvent
    data object RefreshUploads : InboxEvent
}
