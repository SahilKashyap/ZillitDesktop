package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.CountryCurrency
import com.zillit.desktop.feature.invoices.domain.InboxField
import com.zillit.desktop.feature.invoices.domain.InboxValues
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
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
    /**
     * A vendor to create on accept — an OCR'd supplier nobody has set up, or
     * a name typed into the picker (`usePendingVendor`). Set only while
     * [vendorId] is blank; nothing is created until the accept is sent.
     */
    val pendingVendorName: String? = null,
) {
    fun values(defaultCurrency: String): InboxValues = InboxValues(
        // A pending vendor fills the field: it becomes real before the accept is sent.
        vendorId = vendorId.ifBlank { pendingVendorName?.let { PENDING_VENDOR }.orEmpty() },
        departmentId = departmentId,
        currency = currency.ifBlank { defaultCurrency },
        effectiveDate = effectiveDate,
        payMethod = payMethod.wire,
        gross = amounts.gross.trim().replace(",", "").toDoubleOrNull(),
    )

    companion object {
        private const val PENDING_VENDOR = "pending-vendor"

        /**
         * The match notes already on the invoice's links — `linked_pos[i].notes`,
         * trimmed, each once, a blank line between (`InboxReviewModal`).
         */
        fun notesOf(invoice: Invoice): String =
            invoice.linkedPos.flatMap { it.notes }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .joinToString("\n\n")

        /** Seeded from the full record; a saved gross is the anchor from the start. */
        fun of(invoice: Invoice, vendorId: String, pendingVendorName: String? = null): InboxForm = InboxForm(
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
            matchNotes = notesOf(invoice),
            pendingVendorName = pendingVendorName?.takeIf { vendorId.isBlank() },
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
    /** Who entered the invoice, their designation, and when — the review's "Created By". */
    val creatorName: String = "",
    val creatorRole: String = "",
    /**
     * "No PO — verified": a tick for the reviewer's own benefit, and nothing
     * more — not sent, not validated, and No PO Selected still asks.
     */
    val noPoVerified: Boolean = false,
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

    /** Files that cannot be sent — every one must go or be replaced first. */
    val rejected: Int get() = files.count { it.problem != null }

    /** Over the batch cap: nothing is dropped for the reader, they choose what goes. */
    val tooMany: Boolean get() = files.size > BulkUploads.MAX_BATCH_FILES

    /** `canSubmit`: something picked, checked, nothing refused, within the cap. */
    val canSubmit: Boolean get() = files.isNotEmpty() && !checking && rejected == 0 && !tooMany

    /** The header switch reads "are they all on" — a setter, not a state of its own. */
    val allPaid: Boolean get() = files.isNotEmpty() && files.all { it.paid }
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
    /** The picker's "Create 'name'": the vendor is made on accept, not now. */
    data class CreateVendor(val name: String) : InboxEvent
    data object ToggleNoPoVerified : InboxEvent

    /** The review's Query: its thread opens, and the thread's unread is read (`markQueryRead`). */
    data object OpenQuery : InboxEvent
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
    /** Files dropped on the panel from the OS — the web's dropzone. */
    data class DropBulkFiles(val files: List<PickedInvoiceFile>) : InboxEvent
    data class ToggleBulkPaid(val ref: Int) : InboxEvent

    /** The header switch: every file paid, or none. */
    data class SetAllBulkPaid(val paid: Boolean) : InboxEvent

    /** Clear — empties the list, the panel stays open. */
    data object ClearBulk : InboxEvent

    /** Retry N — the storage failures of a batch, sent again as a new batch. */
    data class RetryBatch(val id: String) : InboxEvent
    data class RemoveBulkFile(val ref: Int) : InboxEvent
    data object SubmitBulk : InboxEvent
    data object CancelBulk : InboxEvent
    data class DismissBatch(val id: String) : InboxEvent
    data object RefreshUploads : InboxEvent
}

/** Enter Invoice's own events beyond the form edit, routed to [InvoiceForms]. */
sealed interface EnterEvent : InvoicesEvent {
    /** The vendor picker's "Create 'name'": made on submit, just before the invoice. */
    data class CreateVendor(val name: String) : EnterEvent

    /** Net, Tax or Gross committed — the shared split rules keep the three consistent. */
    data class Amount(val field: AmountField, val value: String) : EnterEvent

    /** "Create anyway" on Amounts don't match. */
    data object ConfirmSplit : EnterEvent

    /** "Go back". */
    data object CancelSplit : EnterEvent

    /** The attachment's ✕ — the file comes off the form. */
    data object ClearFile : EnterEvent
}

/**
 * The currency a company trades in, from its country — `applyCompany`'s
 * `getCurrencyForCountry`; null when there is no confident answer, and the
 * form's currency is then left as it was.
 */
internal fun InvoicesUiState.currencyFor(companyId: String): String? =
    companies.firstOrNull { it.id == companyId }
        ?.let { CountryCurrency.forCountry(it.country, currencyCatalogue) }

/** The dashboard's links, routed to [InvoiceInboxActions]. */
sealed interface OverviewEvent : InvoicesEvent {
    /**
     * A vendor alert's button or a pending action — the web's `prefixHref`:
     * an invoices page opens here, and a vendors or purchase-orders link
     * leaves for that area of the Account Hub.
     */
    data class FollowLink(val href: String) : OverviewEvent
}
