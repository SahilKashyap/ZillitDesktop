package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.BulkBatch
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkFileStatus
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.InboxAccept
import com.zillit.desktop.feature.invoices.domain.InboxField
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import kotlin.random.Random

/**
 * The Inbox — the web's `InboxPage`, `InboxReviewModal`, `BulkUploadPanel`
 * and `UploadsTab`: review and accept one invoice, process several, and
 * upload a batch for the server to extract.
 */
@Suppress("TooManyFunctions") // One handler per user act.
internal class InvoiceInboxActions(private val vm: InvoicesViewModel) {

    /** The picked files' bytes, by ref — kept out of the screen state. */
    private val picked = mutableMapOf<Int, PickedInvoiceFile>()
    private var nextRef = 0

    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InboxEvent.SelectTab -> selectTab(event.tab)
            is InboxEvent.Open -> open(event.invoice)
            InboxEvent.Close -> vm.update { copy(inboxReview = inboxReview?.takeIf { it.busy }) }
            is InboxEvent.Edit -> edit { current ->
                val next = event.form
                if (next.vendorId != current.form.vendorId) loadSuggestions(current.invoice.id, next.vendorId)
                current.copy(form = next, errors = current.errors - filled(next))
            }
            is InboxEvent.EditAmount -> edit { current ->
                val amounts = InboxTriage.applyAmountEdit(current.form.amounts, event.field, event.value)
                val form = current.form.copy(amounts = amounts)
                current.copy(form = form, errors = current.errors - filled(form))
            }
            is InboxEvent.AddPo -> edit { current ->
                if (current.form.picks.any { it.id == event.pick.id }) return@edit current
                current.copy(form = current.form.copy(picks = current.form.picks + event.pick))
            }
            is InboxEvent.RemovePo -> edit { current ->
                current.copy(form = current.form.copy(picks = current.form.picks.filterNot { it.id == event.id }))
            }
            InboxEvent.Accept -> accept()
            InboxEvent.ConfirmSplit -> afterSplit()
            InboxEvent.ConfirmNoPo -> submit()
            InboxEvent.CancelConfirm -> vm.update {
                copy(inboxReview = inboxReview?.copy(confirmSplit = false, confirmNoPo = false))
            }
            InboxEvent.ProcessSelected -> processSelected()
            InboxEvent.DismissBlocked -> vm.update { copy(blockedProcess = emptyList()) }
            is InboxEvent.StartBulk -> startBulk(event.allowPaid)
            InboxEvent.AddBulkFiles -> addBulkFiles()
            is InboxEvent.ToggleBulkPaid -> vm.update {
                val files = bulkPick?.files?.map { if (it.ref == event.ref) it.copy(paid = !it.paid) else it }
                copy(bulkPick = bulkPick?.copy(files = files.orEmpty()))
            }
            is InboxEvent.RemoveBulkFile -> {
                picked.remove(event.ref)
                vm.update { copy(bulkPick = bulkPick?.copy(files = bulkPick.files.filterNot { it.ref == event.ref })) }
            }
            InboxEvent.SubmitBulk -> submitBulk()
            InboxEvent.CancelBulk -> {
                vm.state.value.bulkPick?.files?.forEach { picked.remove(it.ref) }
                vm.update { copy(bulkPick = null) }
            }
            is InboxEvent.DismissBatch -> vm.update { copy(bulkBatches = bulkBatches.filterNot { it.id == event.id }) }
            InboxEvent.RefreshUploads -> loadUploads()
            else -> return false
        }
        return true
    }

    // -- the review ---------------------------------------------------------------

    private fun selectTab(tab: InboxTab) {
        vm.update { copy(inboxTab = tab, selected = emptySet()) }
        if (tab == InboxTab.Uploads) loadUploads()
    }

    /**
     * Opens the review: the row at once, then the full record — its form
     * seeded from it, and the vendor from the OCR'd supplier name when the
     * record has none — the order suggestions for that vendor, and the
     * document. The web's `openReview`.
     */
    private fun open(row: Invoice) {
        if (!vm.state.value.isAccountant) return
        vm.update { copy(inboxReview = InboxReview(invoice = row, form = seed(row))) }
        vm.run {
            val invoice = (vm.repo.invoice(row.id) as? ZillitResult.Success)?.data ?: row
            val form = seed(invoice)
            vm.update {
                val open = inboxReview?.takeIf { it.invoice.id == row.id } ?: return@update this
                copy(inboxReview = open.copy(invoice = invoice, form = form, loading = false))
            }
            loadSuggestions(invoice.id, form.vendorId)
            loadPreview(invoice)
        }
    }

    private fun seed(invoice: Invoice): InboxForm {
        val vendors = vm.state.value.vendors.values
        val vendor = invoice.vendorId.ifBlank { InboxTriage.vendorFor(invoice.supplierName, vendors).orEmpty() }
        return InboxForm.of(invoice, vendor)
    }

    private fun loadSuggestions(id: String, vendorId: String) {
        vm.update { copy(inboxReview = inboxReview?.copy(suggestionsLoading = true)) }
        vm.run {
            val result = vm.repo.poSuggestions(id, vendorId.ifBlank { null })
            vm.update {
                val open = inboxReview?.takeIf { it.invoice.id == id } ?: return@update this
                copy(
                    inboxReview = open.copy(
                        suggestions = (result as? ZillitResult.Success)?.data ?: open.suggestions,
                        suggestionsLoading = false,
                    ),
                )
            }
        }
    }

    private fun loadPreview(invoice: Invoice) {
        val attachment = invoice.firstAttachment?.takeIf { it.isImage || it.isPdf } ?: return
        vm.update { copy(inboxReview = inboxReview?.copy(previewLoading = true)) }
        vm.run {
            val result = vm.fetchAttachment(attachment)
            vm.update {
                val open = inboxReview?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                copy(
                    inboxReview = when (result) {
                        is ZillitResult.Success -> open.copy(
                            preview = AttachmentBytes(result.data),
                            previewLoading = false,
                        )
                        is ZillitResult.Failure -> open.copy(previewLoading = false, previewFailed = true)
                    },
                )
            }
        }
    }

    /** Edits the open review unless it is sending, or its invoice sits in a closed period. */
    private fun edit(change: (InboxReview) -> InboxReview) {
        val review = vm.state.value.inboxReview ?: return
        if (review.busy || vm.state.value.isLocked(review.invoice)) return
        vm.update { copy(inboxReview = inboxReview?.let(change)) }
    }

    /** The required fields a form now satisfies — their errors clear as they are filled. */
    private fun filled(form: InboxForm): Set<InboxField> =
        InboxField.entries.toSet() - InboxTriage.missing(form.values(vm.state.value.projectCurrency)).toSet()

    /**
     * Accept and Match to PO: the required fields first, then the amounts
     * (a mismatch asks, never blocks), then — with no order picked — whether to
     * go on without one.
     */
    private fun accept() {
        val state = vm.state.value
        val review = state.inboxReview ?: return
        if (review.busy || state.isLocked(review.invoice)) return
        val missing = InboxTriage.missing(review.form.values(state.projectCurrency))
        if (missing.isNotEmpty()) {
            vm.update { copy(inboxReview = inboxReview?.copy(errors = missing.toSet())) }
            return
        }
        if (InboxTriage.splitMismatch(review.form.amounts)) {
            vm.update { copy(inboxReview = inboxReview?.copy(confirmSplit = true)) }
            return
        }
        afterSplit()
    }

    private fun afterSplit() {
        val review = vm.state.value.inboxReview ?: return
        if (review.form.picks.isEmpty()) {
            vm.update { copy(inboxReview = inboxReview?.copy(confirmSplit = false, confirmNoPo = true)) }
            return
        }
        submit()
    }

    /**
     * `POST /process` with the edits, then the match notes on every picked
     * order — best-effort, because the accept has already landed and a note
     * must not undo it.
     */
    private fun submit() {
        val state = vm.state.value
        val review = state.inboxReview ?: return
        if (review.busy) return
        val form = review.form
        vm.update { copy(inboxReview = inboxReview?.copy(busy = true, confirmSplit = false, confirmNoPo = false)) }
        vm.run {
            val result = vm.repo.process(listOf(review.invoice.id), acceptOf(form, state.projectCurrency))
            if (result is ZillitResult.Failure) {
                vm.update { copy(inboxReview = inboxReview?.copy(busy = false), error = result.error.localised()) }
                return@run
            }
            val note = form.matchNotes.trim()
            if (note.isNotEmpty()) form.picks.forEach { vm.repo.matchNote(review.invoice.id, it.id, note) }
            vm.update {
                copy(
                    inboxReview = null,
                    selected = selected - review.invoice.id,
                    invoices = invoices.filterNot { it.id == review.invoice.id },
                )
            }
            vm.notice(str(S.desktop_inv_invoice_accepted))
            vm.refresh()
        }
    }

    private fun acceptOf(form: InboxForm, defaultCurrency: String) = InboxAccept(
        invoiceNumber = form.invoiceNumber,
        vendorId = form.vendorId,
        description = form.description,
        invoiceDate = form.invoiceDate,
        dueDate = form.dueDate,
        effectiveDate = form.effectiveDate,
        net = form.amounts.net,
        tax = form.amounts.tax,
        gross = form.amounts.gross,
        poIds = form.picks.map { it.id },
        payMethod = form.payMethod,
        departmentId = form.departmentId,
        currency = form.currency.ifBlank { defaultCurrency },
        companyId = form.companyId,
        bankId = form.bankId,
        episode = form.episode,
    )

    /**
     * The queue's Process: one ticked row opens its review; two or more are
     * checked against the same required fields as a single accept and sent
     * only when every one passes — `/process` is all-or-nothing, so a partial
     * result would leave the page claiming something it cannot see.
     */
    private fun processSelected() {
        val state = vm.state.value
        if (!state.isAccountant || state.busy) return
        val rows = state.invoices.filter { it.id in state.selected }
        when {
            rows.isEmpty() -> return
            rows.size == 1 -> open(rows.single())
            else -> {
                val blocked = rows.mapNotNull { row ->
                    InboxTriage.missing(InboxTriage.valuesOf(row, state.projectCurrency))
                        .takeIf { it.isNotEmpty() }
                        ?.let { BlockedEntry(row, it) }
                }
                if (blocked.isNotEmpty()) {
                    vm.update { copy(blockedProcess = blocked) }
                    return
                }
                vm.update { copy(busy = true) }
                vm.run {
                    val result = vm.repo.process(rows.map { it.id })
                    vm.update { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
                    if (result is ZillitResult.Success) {
                        vm.notice(str(S.desktop_inv_moved_to_register, rows.size))
                        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Register))
                    }
                }
            }
        }
    }

    // -- bulk upload ----------------------------------------------------------------

    /** The picker, then the checked list. The department's upload needs a posting right. */
    private fun startBulk(allowPaid: Boolean) {
        val state = vm.state.value
        if (!state.isAccountant && !state.viewer.mayPost) {
            vm.update { copy(error = str(S.desktop_inv_no_posting_rights)) }
            return
        }
        picked.clear()
        vm.update { copy(bulkPick = BulkPick(allowPaid = allowPaid && state.isAccountant)) }
        addBulkFiles()
    }

    /** Adds picked files, each checked on the spot, up to the batch's cap. */
    private fun addBulkFiles() {
        vm.state.value.bulkPick ?: return
        vm.run {
            val files = vm.pickMany()
            if (files.isEmpty()) return@run
            vm.update { copy(bulkPick = bulkPick?.copy(checking = true)) }
            val room = BulkUploads.MAX_BATCH_FILES - (vm.state.value.bulkPick?.files?.size ?: 0)
            if (files.size > room) vm.notice(str(S.desktop_inv_too_many_files, BulkUploads.MAX_BATCH_FILES))
            val checked = files.take(room.coerceAtLeast(0)).map { file ->
                val ref = ++nextRef
                picked[ref] = file
                val pages = if (file.extension == PDF) pdfPageCount(file.bytes) else null
                BulkFile(
                    ref = ref,
                    name = file.name,
                    size = file.bytes.size.toLong(),
                    problem = BulkUploads.problemWith(file, pages),
                )
            }
            vm.update { copy(bulkPick = bulkPick?.copy(files = bulkPick.files + checked, checking = false)) }
        }
    }

    /**
     * Starts the batch and sends the reader to watch it: the files that
     * passed go to storage and are handed over one by one; the ones refused
     * never leave the machine. Runs on after the sheet is gone.
     */
    private fun submitBulk() {
        val state = vm.state.value
        val pick = state.bulkPick ?: return
        if (pick.sendable == 0 || pick.checking) return
        val batch = BulkBatch(
            id = BulkUploads.newBatchId(vm.now(), Random.nextLong(0, Long.MAX_VALUE)),
            files = pick.files.map { if (it.problem != null) it.copy(status = BulkFileStatus.Invalid) else it },
            createdAtMs = vm.now(),
        )
        vm.update { copy(bulkPick = null, enter = null, bulkBatches = listOf(batch) + bulkBatches) }
        watchUploads()
        vm.run { runBatch(batch) }
    }

    /** The accountant watches on the Inbox's Ongoing Uploads; the department on its own tab. */
    private fun watchUploads() {
        if (vm.state.value.isAccountant) {
            if (vm.state.value.page != AccountantPage.Inbox) vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Inbox))
            selectTab(InboxTab.Uploads)
        } else {
            vm.onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.Uploads))
        }
    }

    private suspend fun runBatch(batch: BulkBatch) {
        batch.files.filter { it.status == BulkFileStatus.Pending }.forEach { file ->
            val bytes = picked.remove(file.ref) ?: return@forEach
            patchFile(batch.id, file.ref) { it.copy(status = BulkFileStatus.Uploading) }
            val uploaded = vm.upload(bytes)
            if (uploaded is ZillitResult.Failure) {
                val error = uploaded.error.localised()
                patchFile(batch.id, file.ref) { it.copy(status = BulkFileStatus.Failed, error = error) }
                return@forEach
            }
            val attachment = (uploaded as ZillitResult.Success).data
            patchFile(batch.id, file.ref) { it.copy(status = BulkFileStatus.Uploaded) }
            // Never retried: a refused hand-off may still have been queued.
            val sent = vm.repo.bulkUpload(batch.id, attachment, file.size, file.paid)
            patchFile(batch.id, file.ref) {
                when (sent) {
                    is ZillitResult.Success -> it.copy(status = BulkFileStatus.Sent)
                    is ZillitResult.Failure -> it.copy(
                        status = BulkFileStatus.SendFailed,
                        error = sent.error.localised(),
                    )
                }
            }
        }
        vm.update { copy(bulkBatches = bulkBatches.map { if (it.id == batch.id) it.copy(postingDone = true) else it }) }
        loadUploads()
        vm.refresh()
    }

    private fun patchFile(batchId: String, ref: Int, change: (BulkFile) -> BulkFile) = vm.update {
        copy(
            bulkBatches = bulkBatches.map { batch ->
                if (batch.id != batchId) batch else batch.copy(
                    files = batch.files.map { if (it.ref == ref) change(it) else it },
                )
            },
        )
    }

    /** The server's half of Ongoing Uploads; a batch seen once and then gone has finished. */
    fun loadUploads() {
        vm.run {
            (vm.repo.bulkBatches() as? ZillitResult.Success)?.data?.let { batches ->
                vm.update { copy(serverBatches = batches, seenBatches = seenBatches + batches.map { it.batchId }) }
            }
        }
    }

    private companion object {
        const val PDF = "pdf"
    }
}
