package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.domain.LineItems
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite
import com.zillit.desktop.feature.invoices.domain.SalesTerms
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Sales Invoices — money owed to the production (the web's `SalesPage`):
 * the list with its chips, the preview a row opens (`GET /:id`) with its
 * History, View PDF, and — for a draft — Edit, Delete and Mark Sent; and the
 * full-page form that raises or updates one. Every write needs the posting
 * right, here as on the page's buttons.
 */
@Suppress("TooManyFunctions") // One handler per control on the page.
internal class InvoiceSalesActions(private val vm: InvoicesViewModel) {

    private var nextLine = 0

    /** The postcode lookup waiting out its pause, or on the wire. One at a time. */
    private var postcodeJob: Job? = null

    /** True when [event] was this page's own. */
    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            InvoicesEvent.StartSalesInvoice -> startSalesInvoice()
            is InvoicesEvent.EditSalesLines -> editSalesLines(event.edit)
            is InvoicesEvent.EditSalesInvoice -> editDraft(event.draft)
            InvoicesEvent.ConfirmSalesInvoice -> confirmSalesInvoice()
            InvoicesEvent.CancelSalesInvoice -> vm.update { copy(salesDraft = salesDraft?.takeIf { it.busy }) }
            is InvoicesEvent.SendSalesInvoice -> markSent(event.invoice)
            // A draft only, and only after "delete it?" — the web's ConfirmModal.
            is InvoicesEvent.DeleteSalesInvoice -> if (event.invoice.status == SalesInvoiceStatus.Draft) {
                vm.update { copy(confirmSalesDelete = event.invoice) }
            }
            InvoicesEvent.CancelDeleteSales -> vm.update { copy(confirmSalesDelete = null) }
            InvoicesEvent.ConfirmDeleteSales -> delete()
            is SalesEvent.SelectFilter -> ui { copy(filter = event.filter) }
            is SalesEvent.Preview -> preview(event.invoice)
            SalesEvent.ClosePreview -> ui { copy(preview = null) }
            SalesEvent.Edit -> vm.state.value.sales.preview?.let(::startEdit)
            SalesEvent.MarkSent -> vm.state.value.sales.preview?.let(::markSent)
            SalesEvent.RequestDelete -> vm.state.value.sales.preview?.let { onEvent(InvoicesEvent.DeleteSalesInvoice(it)) }
            SalesEvent.ShowHistory -> showHistory()
            SalesEvent.HideHistory -> ui { copy(history = null) }
            SalesEvent.ViewPdf -> viewPdf()
            SalesEvent.ClosePdf -> ui { copy(pdf = null) }
            SalesEvent.DownloadPdf -> downloadPdf()
            else -> return false
        }
        return true
    }

    /**
     * The list — `?perPage=200`. The skeleton shows until it lands; a failed
     * read empties the list without a banner, as the web's `catch` does.
     */
    fun load() {
        vm.update { copy(loading = false, sales = sales.copy(loading = salesInvoices.isEmpty())) }
        vm.run {
            val result = vm.repo.salesInvoices()
            vm.update {
                copy(
                    salesInvoices = (result as? ZillitResult.Success)?.data.orEmpty(),
                    sales = sales.copy(loading = false),
                )
            }
        }
    }

    // -- the preview ------------------------------------------------------------------

    /**
     * A row: its unread read, its spinner while `GET /:id` runs, then the
     * preview — the row itself if the read fails. The read goes first, on
     * every click, as the web's row handler emits before it fetches
     * (`SalesPage.jsx:950-955`).
     */
    private fun preview(invoice: SalesInvoice) {
        vm.readPageRow(AccountantPage.Sales, invoice.id)
        if (vm.state.value.sales.previewLoadingId != null) return
        ui { copy(previewLoadingId = invoice.id) }
        vm.run {
            val full = (vm.repo.salesInvoice(invoice.id) as? ZillitResult.Success)?.data ?: invoice
            vm.rememberNames(listOf(full.createdBy, full.updatedBy))
            val audit = listOf(full.createdBy, full.updatedBy).filter { it.isNotBlank() }.toSet()
            val people = vm.people().filter { it.id in audit }.associateBy { it.id }
            ui { copy(previewLoadingId = null, preview = full, project = vm.projectInfo(), people = people) }
        }
    }

    private fun showHistory() {
        val invoice = vm.state.value.sales.preview ?: return
        ui { copy(history = SalesHistory(invoice)) }
        vm.run {
            val result = vm.repo.salesInvoiceHistory(invoice.id)
            val rows = (result as? ZillitResult.Success)?.data.orEmpty()
            vm.rememberNames(rows.map { it.actionBy })
            ui { copy(history = history?.takeIf { it.invoice.id == invoice.id }?.copy(rows = rows, loading = false)) }
            (result as? ZillitResult.Failure)?.let { vm.fail(it.error.localised()) }
        }
    }

    /** "Generating…", then the PDF in its own viewer; a failure just closes it, as the web's `catch` logs only. */
    private fun viewPdf() {
        val invoice = vm.state.value.sales.preview ?: return
        if (vm.state.value.sales.pdf?.loading == true) return
        val reference = invoice.reference.ifBlank { invoice.id.take(PDF_ID_CHARS) }
        ui { copy(pdf = SalesPdf(reference)) }
        vm.run {
            when (val result = vm.salesInvoicePdf(invoice.id)) {
                is ZillitResult.Success -> ui {
                    copy(pdf = pdf?.takeIf { it.reference == reference }?.copy(bytes = AttachmentBytes(result.data), loading = false))
                }
                is ZillitResult.Failure -> ui { copy(pdf = null) }
            }
        }
    }

    /** The viewer's Download — `sales-invoice-{ref}.pdf`. */
    private fun downloadPdf() {
        val pdf = vm.state.value.sales.pdf ?: return
        val bytes = pdf.bytes?.bytes ?: return
        vm.run {
            val saved = vm.saveAndOpen("sales-invoice-${pdf.reference}.pdf", bytes)
            (saved as? ZillitResult.Failure)?.let { vm.fail(it.error.localised()) }
        }
    }

    /** Mark Sent — `POST /:id/send`, "Marking…" while it goes; the preview closes when it lands. */
    private fun markSent(invoice: SalesInvoice) {
        val state = vm.state.value
        if (!invoice.status.canSend || state.sales.markingSent) return
        ui { copy(markingSent = true) }
        vm.run {
            val result = vm.repo.sendSalesInvoiceWithMessage(invoice.id)
            ui { copy(markingSent = false, preview = preview.takeUnless { result is ZillitResult.Success }) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notice(result.data?.localisedMessage() ?: str(S.desktop_sent_to_the_client))
                    load()
                }
                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    /** After the confirm: the preview closes, the row dims, and the list is read again. */
    private fun delete() {
        val invoice = vm.state.value.confirmSalesDelete ?: return
        vm.update { copy(confirmSalesDelete = null) }
        ui { copy(preview = null, deletingId = invoice.id) }
        vm.run {
            val result = vm.repo.deleteSalesInvoiceWithMessage(invoice.id)
            ui { copy(deletingId = null) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notice(result.data?.localisedMessage() ?: str(S.drive_deleted_default))
                    load()
                }
                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    // -- the form ------------------------------------------------------------------------

    /** Create Invoice: dated today, due in thirty days, the project's currency, one empty line — `resetForm`. */
    private fun startSalesInvoice() {
        val state = vm.state.value
        val today = InvoiceFormat.today(vm.now())
        vm.update {
            copy(
                salesDraft = SalesInvoiceDraft(
                    currency = projectCurrency,
                    invoiceDate = today,
                    dueDate = SalesTerms.dueDate(today, SalesTerms.Days30),
                    lines = LineDraft(listOf(CodedLine(newLineId()))),
                ),
            )
        }
        loadFormReference()
    }

    /**
     * Edit — `startEdit`: the saved invoice in the form, its address unpacked,
     * its lines as stored (none stays none), and the saved lines kept so the
     * layers and tags survive. Drafts only.
     */
    private fun startEdit(invoice: SalesInvoice) {
        val state = vm.state.value
        if (invoice.status != SalesInvoiceStatus.Draft) return
        val today = InvoiceFormat.today(vm.now())
        vm.update {
            copy(
                salesDraft = SalesInvoiceDraft(
                    editingId = invoice.id,
                    clientName = invoice.clientName,
                    payTerms = SalesTerms.from(invoice.payTerms),
                    address = invoice.clientAddress,
                    currency = invoice.currency,
                    invoiceDate = InvoiceFormat.toDateInput(invoice.invoiceDateMs).ifBlank { today },
                    dueDate = InvoiceFormat.toDateInput(invoice.dueDateMs),
                    lines = LineDraft(invoice.lineItems),
                    savedLinesJson = invoice.lineItemsJson,
                ),
                sales = sales.copy(preview = null),
            )
        }
        loadFormReference()
    }

    /**
     * A change to the form. The due date follows the invoice date and the
     * terms — the web's effect (`SalesPage.jsx:263-269`) — and a field's
     * refusal clears as it is filled in. A new postcode or country looks the
     * city and state up again.
     */
    private fun editDraft(next: SalesInvoiceDraft) {
        val old = vm.state.value.salesDraft ?: return
        if (old.busy) return
        val moved = old.invoiceDate != next.invoiceDate || old.payTerms != next.payTerms
        val dated = next.invoiceDate.isNotBlank() && !next.invoiceDateIsWrong
        val cleared = buildSet {
            if (old.clientName != next.clientName) add(SalesField.ClientName)
            if (old.invoiceDate != next.invoiceDate) add(SalesField.InvoiceDate)
        }
        vm.update {
            copy(
                salesDraft = next.copy(
                    dueDate = if (moved && dated) SalesTerms.dueDate(next.invoiceDate, next.payTerms) else next.dueDate,
                    errors = next.errors - cleared,
                ),
            )
        }
        if (old.address.postalCode != next.address.postalCode || old.address.country != next.address.country) {
            lookUpPostcode()
        }
    }

    /**
     * The city and state from the postcode, a second after the last change
     * to the postcode or country (`usePostcodeAutofill`). A confirmed answer
     * is written whatever was there — a postcode with no place clears them —
     * and a failed lookup changes nothing.
     */
    private fun lookUpPostcode() {
        postcodeJob?.cancel()
        val draft = vm.state.value.salesDraft ?: return
        val code = vm.state.value.sales.countries.firstOrNull { it.name == draft.address.country }?.code.orEmpty()
        val postcode = draft.address.postalCode.trim()
        if (code.isBlank() || postcode.length < MIN_POSTCODE) return
        postcodeJob = vm.run {
            delay(POSTCODE_PAUSE_MS)
            val place = (vm.repo.postcodePlace(code, postcode) as? ZillitResult.Success)?.data ?: return@run
            vm.update {
                val now = salesDraft ?: return@update this
                if (now.address.postalCode.trim() != postcode) return@update this
                copy(salesDraft = now.copy(address = now.address.copy(city = place.city, state = place.state)))
            }
        }
    }

    private fun editSalesLines(edit: LineEdit) {
        val draft = vm.state.value.salesDraft ?: return
        if (draft.busy) return
        vm.update {
            val lines = LineItems.apply(draft.lines, edit, ::newLineId)
            copy(salesDraft = salesDraft?.copy(lines = lines, lineError = null))
        }
    }

    private fun newLineId(): String = "li-${vm.now()}-${++nextLine}"

    /** What the form's pickers need that the list did not read: countries, layers, the currency catalogue. */
    private fun loadFormReference() {
        val state = vm.state.value
        if (state.sales.countries.isEmpty()) {
            vm.run {
                (vm.repo.countries() as? ZillitResult.Success)?.data?.takeIf { it.isNotEmpty() }?.let { rows ->
                    ui { copy(countries = rows) }
                }
            }
        }
        vm.loadLineReference()
    }

    /**
     * Create or Update — `handleCreate`: "Client name is required", "Invoice
     * date is required" and the web's line message, all at once; then the
     * write. A new one gets `SI-` and the time's last six digits; a blank due
     * date is today plus the terms.
     */
    private fun confirmSalesInvoice() {
        val state = vm.state.value
        val draft = state.salesDraft ?: return
        if (draft.busy) return
        val errors = buildMap {
            if (draft.clientName.isBlank()) put(SalesField.ClientName, str(S.desktop_inv_client_name_required))
            if (draft.invoiceDateIsWrong) put(SalesField.InvoiceDate, str(S.desktop_inv_invoice_date_required))
        }
        val check = LineItems.check(draft.lines.lines)
        if (errors.isNotEmpty() || !check.ok || draft.dateIsWrong) {
            val flagged = check.problems.map { it.lineId }.toSet()
            vm.update {
                copy(
                    salesDraft = salesDraft?.copy(
                        errors = errors,
                        lineError = lineMessage(check),
                        lines = draft.lines.copy(flagged = flagged),
                    ),
                )
            }
            return
        }
        vm.update { copy(salesDraft = salesDraft?.copy(busy = true, errors = emptyMap(), lineError = null)) }
        val today = InvoiceFormat.today(vm.now())
        val editing = draft.editingId
        val write = SalesInvoiceWrite(
            clientName = draft.clientName,
            clientAddress = draft.address,
            currency = draft.currency.ifBlank { state.projectCurrency },
            invoiceDate = draft.invoiceDate.trim(),
            dueDate = draft.dueDate.trim().ifBlank { SalesTerms.dueDate(today, draft.payTerms) },
            lines = draft.lines.lines,
            reference = if (editing == null) "SI-${vm.now().toString().takeLast(REF_DIGITS)}" else null,
            savedLinesJson = draft.savedLinesJson,
        )
        vm.run {
            val result = if (editing == null) {
                vm.repo.createSalesInvoiceWithMessage(write)
            } else {
                vm.repo.updateSalesInvoiceWithMessage(editing, write)
            }
            vm.update { copy(salesDraft = if (result is ZillitResult.Success) null else salesDraft?.copy(busy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    val fallback = if (editing == null) S.desktop_inv_sales_invoice_raised else S.desktop_inv_sales_invoice_updated
                    vm.notice(result.data?.localisedMessage() ?: str(fallback))
                    load()
                }
                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    private fun ui(change: SalesUi.() -> SalesUi) = vm.update { copy(sales = sales.change()) }

    private companion object {
        /** `SI-` and the last six digits of the time — the web's generated reference. */
        const val REF_DIGITS = 6

        /** `inv.reference || inv.id.slice(0, 8)` — the PDF's name. */
        const val PDF_ID_CHARS = 8

        /** The web's autofill waits a second after the last keystroke. */
        const val POSTCODE_PAUSE_MS = 1_000L

        /** `pc.length < 3` never asks. */
        const val MIN_POSTCODE = 3
    }
}
