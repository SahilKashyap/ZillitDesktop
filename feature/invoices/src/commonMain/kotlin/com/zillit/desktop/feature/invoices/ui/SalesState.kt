package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.ClientAddress
import com.zillit.desktop.feature.invoices.domain.ClientCountry
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceProjectInfo
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.SalesFilter
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesTerms

/**
 * A sales invoice being written or edited — `SalesPage`'s full-page form
 * (`SalesPage.jsx:528-805`): Client / Billed To, Payment Terms, the address,
 * the dates and currency, then the lines.
 */
data class SalesInvoiceDraft(
    /** The draft being edited — Update Invoice; null raises a new one. */
    val editingId: String? = null,
    val clientName: String = "",
    val payTerms: SalesTerms = SalesTerms.Days30,
    val address: ClientAddress = ClientAddress(),
    val currency: String = "",
    /** `YYYY-MM-DD`; the web opens the form on today. */
    val invoiceDate: String = "",
    /** `YYYY-MM-DD`; follows the invoice date and terms, and can be typed over. */
    val dueDate: String = "",
    /** The lines, whose gross is the invoice's — the web's `LineItemsEditor`. */
    val lines: LineDraft = LineDraft(),
    /** The save's refusal of the lines, in the web's words. */
    val lineError: String? = null,
    /** "Client name is required", "Invoice date is required" — under their fields. */
    val errors: Map<SalesField, String> = emptyMap(),
    val busy: Boolean = false,
    /** The saved `line_items`, so an edit keeps the layers and tags this form does not show. */
    val savedLinesJson: String = "",
) {
    val dueDateMs: Long? get() = InvoiceFormat.parseDateInput(dueDate)

    val dateIsWrong: Boolean get() = dueDate.isNotBlank() && dueDateMs == null

    val invoiceDateIsWrong: Boolean get() = InvoiceFormat.parseDateInput(invoiceDate) == null

    /** Whether the save would pass the header checks — the button itself is never disabled, as on the web. */
    val isReady: Boolean
        get() = clientName.isNotBlank() && !invoiceDateIsWrong && !dateIsWrong
}

/** The form fields the save names when they are missing. */
enum class SalesField { ClientName, InvoiceDate }

/**
 * Sales Invoices beyond the list and the form — the status chip, the preview
 * opened from a row (and the row spinning while it loads), its history, the
 * PDF, Mark Sent in flight and the row being deleted.
 */
data class SalesUi(
    val filter: SalesFilter = SalesFilter.All,
    /** The list's first read is out — the skeleton shows until it lands. */
    val loading: Boolean = false,
    val previewLoadingId: String? = null,
    val preview: SalesInvoice? = null,
    /** The production's own name and address, read as the preview opens. */
    val project: InvoiceProjectInfo = InvoiceProjectInfo(),
    val history: SalesHistory? = null,
    val pdf: SalesPdf? = null,
    /** Mark Sent is on its way — "Marking…". */
    val markingSent: Boolean = false,
    /** The row being deleted, dimmed until the list comes back. */
    val deletingId: String? = null,
    /** Who raised and last changed the invoice open in the preview — name and designation. */
    val people: Map<String, InvoiceAssignee> = emptyMap(),
    /** The address's Country options — read as the form first opens. */
    val countries: List<ClientCountry> = emptyList(),
)

data class SalesHistory(val invoice: SalesInvoice, val rows: List<HistoryEntry>? = null, val loading: Boolean = true)

/** The generated PDF — "Sales Invoice — {ref}", with its pages once they arrive. */
data class SalesPdf(val reference: String, val bytes: AttachmentBytes? = null, val loading: Boolean = true)

/** Sales Invoices' own events, routed to [InvoiceSalesActions]. */
sealed interface SalesEvent : InvoicesEvent {
    data class SelectFilter(val filter: SalesFilter) : SalesEvent

    /** A row: `GET /:id`, then the preview. */
    data class Preview(val invoice: SalesInvoice) : SalesEvent
    data object ClosePreview : SalesEvent

    /** The preview's Edit — a draft only. */
    data object Edit : SalesEvent

    /** The preview's Mark Sent — a draft only. */
    data object MarkSent : SalesEvent

    /** The preview's delete icon — a draft only, after the confirm. */
    data object RequestDelete : SalesEvent

    data object ShowHistory : SalesEvent
    data object HideHistory : SalesEvent

    data object ViewPdf : SalesEvent
    data object ClosePdf : SalesEvent

    /** Saves the PDF as `sales-invoice-{ref}.pdf` and opens it. */
    data object DownloadPdf : SalesEvent
}
