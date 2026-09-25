package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
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
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The Inbox — the web's `InboxPage`, `InboxReviewModal`, `BulkUploadPanel`
 * and `UploadsTab`: review and accept one invoice, process several, and
 * upload a batch for the server to extract.
 */
@Suppress("TooManyFunctions") // One handler per user act.
internal class InvoiceInboxActions(private val vm: InvoicesViewModel) {

    /**
     * The picked files' bytes, by ref — kept out of the screen state. A file
     * that failed on its way to storage keeps its bytes, so Retry can send it
     * again; the rest let go of theirs once handed over.
     */
    private val picked = mutableMapOf<Int, PickedInvoiceFile>()
    private var nextRef = 0

    /** The review's vendor quick-add. */
    private val vendors = PendingVendors(vm)

    /** Pending auto-dismissals, by batch id — `dismissTimers`. */
    private val dismissJobs = mutableMapOf<String, Job>()

    /** The list re-read a burst of progress frames coalesces into. */
    private var frameFetch: Job? = null

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InboxEvent.SelectTab -> selectTab(event.tab)
            is InboxEvent.Open -> open(event.invoice)
            InboxEvent.Close -> vm.update { copy(inboxReview = inboxReview?.takeIf { it.busy }) }
            is InboxEvent.Edit -> editForm(event.form)
            is InboxEvent.CreateVendor -> {
                val form = vm.state.value.inboxReview?.form ?: return true
                val name = event.name.trim().takeIf { it.isNotEmpty() } ?: return true
                editForm(form.copy(vendorId = "", pendingVendorName = name))
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
            InboxEvent.ToggleNoPoVerified -> edit { it.copy(noPoVerified = !it.noPoVerified) }
            InboxEvent.OpenQuery -> {
                val invoice = vm.state.value.inboxReview?.invoice ?: return true
                // The server files an invoice's query thread under the register for
                // accountants, whichever tab opened it (`InboxReviewModal`'s `queryScope`).
                AccountantPage.Register.badgeKey?.let { vm.readAccountantRow(it, invoice.id, QUERY_KIND) }
                vm.onEvent(QueryEvent.Open(invoice))
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
            is InboxEvent.DropBulkFiles -> addPicked(event.files)
            is InboxEvent.ToggleBulkPaid -> editPick { pick ->
                pick.copy(files = pick.files.map { if (it.ref == event.ref) it.copy(paid = !it.paid) else it })
            }
            is InboxEvent.SetAllBulkPaid -> editPick { pick ->
                pick.copy(files = pick.files.map { it.copy(paid = event.paid) })
            }
            is InboxEvent.RemoveBulkFile -> editPick { pick ->
                picked.remove(event.ref)
                pick.copy(files = pick.files.filterNot { it.ref == event.ref })
            }
            InboxEvent.ClearBulk -> editPick { pick ->
                pick.files.forEach { picked.remove(it.ref) }
                pick.copy(files = emptyList())
            }
            InboxEvent.SubmitBulk -> submitBulk()
            InboxEvent.CancelBulk -> {
                vm.state.value.bulkPick?.files?.forEach { picked.remove(it.ref) }
                vm.update { copy(bulkPick = null) }
            }
            is InboxEvent.DismissBatch -> dismiss(event.id)
            is InboxEvent.RetryBatch -> retry(event.id)
            InboxEvent.RefreshUploads -> loadUploads()
            is OverviewEvent.FollowLink -> followLink(event.href)
            else -> return false
        }
        return true
    }

    /**
     * `prefixHref`: an `/invoices/…` link opens that page here; `/vendors/…`
     * and `/purchase-orders/…` go to the Account Hub's own area, as the
     * web's `<Link>` sends them; anything already absolute goes as it is.
     */
    private fun followLink(href: String) {
        val link = href.trim()
        when {
            link.startsWith(INVOICES_LINK) -> {
                val page = AccountantPage.forHref(link)
                if (page != null) vm.onEvent(InvoicesEvent.SelectPage(page)) else vm.onEvent(InvoicesEvent.OpenRoute(link))
            }
            link.startsWith(HUB_ROOT) -> vm.navigate(link)
            link.startsWith(VENDORS_LINK) || link.startsWith(PURCHASE_ORDERS_LINK) -> vm.navigate(HUB_ROOT + link)
            link.isNotEmpty() -> vm.navigate(link)
            else -> vm.navigate(HUB_ROOT)
        }
    }

    // -- the review ---------------------------------------------------------------

    private fun selectTab(tab: InboxTab) {
        // The ticks survive a tab switch, as the web keeps them.
        vm.update { copy(inboxTab = tab) }
        if (tab == InboxTab.Uploads) loadUploads()
    }

    /**
     * Opens the review: the row at once, then the full record — its form
     * seeded from it, the vendor from the OCR'd supplier name when the record
     * has none (a new supplier becomes a vendor to create on accept), the
     * bank and company filled where there is only one answer — the order
     * suggestions for that vendor, and the document. The web's `openReview`,
     * which also reads the row's own unread (`emitInvoiceLevelRead`,
     * `InboxPage.jsx:252-259`). Opened from the Register it reads nothing: the
     * Register mounts the same review without the read (`RegisterPage.jsx:576-592`).
     */
    private fun open(row: Invoice) {
        if (!vm.state.value.isAccountant) return
        vm.readPageRow(AccountantPage.Inbox, row.id)
        val (creator, role) = vm.creatorOf(row.userId)
        vm.update {
            copy(
                inboxReview = InboxReview(
                    invoice = row,
                    form = seed(row),
                    creatorName = creator,
                    creatorRole = role,
                ),
                inboxProcessError = null,
            )
        }
        vm.run {
            val invoice = (vm.repo.invoice(row.id) as? ZillitResult.Success)?.data ?: row
            val form = autoFilled(seed(invoice))
            val (name, designation) = vm.creatorOf(invoice.userId)
            vm.update {
                val open = inboxReview?.takeIf { it.invoice.id == row.id } ?: return@update this
                copy(
                    inboxReview = open.copy(
                        invoice = invoice,
                        form = form,
                        loading = false,
                        creatorName = name,
                        creatorRole = designation,
                    ),
                )
            }
            loadSuggestions(invoice.id, form.vendorId)
            loadPreview(invoice)
        }
    }

    /** `resolveSupplierVendor`: an exact match is picked; a new supplier is a vendor to create on accept. */
    private fun seed(invoice: Invoice): InboxForm {
        if (invoice.vendorId.isNotBlank()) return InboxForm.of(invoice, invoice.vendorId)
        val seed = InboxTriage.seedVendor(invoice.supplierName, vm.state.value.vendors.values)
        return InboxForm.of(invoice, seed?.vendorId.orEmpty(), seed?.pendingName)
    }

    /**
     * `resolveAutoFill` over the form, as the web's effect runs it on every
     * bank or company change: each rule fills only an empty field, and an
     * auto-filled company brings its country's currency (`applyCompany`).
     */
    private fun autoFilled(form: InboxForm): InboxForm {
        val s = vm.state.value
        var out = form
        repeat(AUTO_FILL_PASSES) {
            val (bank, company) = InboxTriage.autoFill(out.bankId, out.companyId, s.banks, s.companies)
            if (bank != null) out = out.copy(bankId = bank)
            if (company != null) out = out.copy(companyId = company, currency = s.currencyFor(company) ?: out.currency)
        }
        return out
    }

    /** A picked company fills the currency from its country (`applyCompany`); then the auto-fill. */
    private fun cascade(old: InboxForm, next: InboxForm): InboxForm {
        val s = vm.state.value
        val withCurrency = if (next.companyId != old.companyId && next.companyId.isNotBlank()) {
            next.copy(currency = s.currencyFor(next.companyId) ?: next.currency)
        } else {
            next
        }
        return autoFilled(withCurrency)
    }

    private fun editForm(next: InboxForm) {
        val review = vm.state.value.inboxReview ?: return
        if (review.busy || vm.state.value.isLocked(review.invoice)) return
        val form = cascade(review.form, next)
        vm.update {
            copy(
                inboxReview = inboxReview?.takeIf { it.invoice.id == review.invoice.id }
                    ?.let { it.copy(form = form, errors = it.errors - filled(form)) }
                    ?: inboxReview,
            )
        }
        val vendorMoved = form.vendorId != review.form.vendorId || form.pendingVendorName != review.form.pendingVendorName
        if (vendorMoved) loadSuggestions(review.invoice.id, form.vendorId)
    }

    /** A pending vendor has no orders yet, so the suggestions go out without a vendor. */
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
     * A pending vendor is created first — a refusal stops here with the
     * pick intact, so the next Accept tries again — and its real id written
     * back, so a failed accept after it leaves the new vendor selected. Then
     * `POST /process` with the edits, then the match notes on every picked
     * order — best-effort, because the accept has already landed and a note
     * must not undo it.
     */
    private fun submit() {
        val state = vm.state.value
        val review = state.inboxReview ?: return
        if (review.busy) return
        vm.update { copy(inboxReview = inboxReview?.copy(busy = true, confirmSplit = false, confirmNoPo = false)) }
        vm.run {
            val vendorId = vendors.resolve(review.form.vendorId, review.form.pendingVendorName)
            if (vendorId == null) {
                vm.update { copy(inboxReview = inboxReview?.copy(busy = false)) }
                return@run
            }
            val form = review.form.copy(vendorId = vendorId, pendingVendorName = null)
            if (form != review.form) {
                vm.update {
                    copy(
                        inboxReview = inboxReview?.takeIf { it.invoice.id == review.invoice.id }
                            ?.let { it.copy(form = it.form.copy(vendorId = vendorId, pendingVendorName = null)) }
                            ?: inboxReview,
                    )
                }
            }
            val result = vm.repo.processWithMessage(listOf(review.invoice.id), acceptOf(form, state.projectCurrency))
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
            val said = (result as? ZillitResult.Success)?.data?.takeIf { it.isNotBlank() }?.localisedMessage()
            vm.notice(said ?: str(S.desktop_inv_invoice_accepted))
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
     * result would leave the page claiming something it cannot see. A refusal
     * is said beside the button, as the web's floating bar says it.
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
                vm.update { copy(busy = true, inboxProcessError = null) }
                vm.run {
                    val result = vm.repo.processWithMessage(rows.map { it.id })
                    when (result) {
                        is ZillitResult.Failure -> vm.update {
                            copy(busy = false, inboxProcessError = result.error.localised())
                        }
                        is ZillitResult.Success -> {
                            vm.update { copy(busy = false) }
                            val said = result.data?.takeIf { it.isNotBlank() }?.localisedMessage()
                            vm.notice(said ?: str(S.desktop_inv_moved_to_register, rows.size))
                            vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Register))
                        }
                    }
                }
            }
        }
    }

    // -- bulk upload ----------------------------------------------------------------

    /**
     * The picker, then the checked list. Open to everyone, as the web's
     * "Upload Invoices" is (`DepartmentInvoiceModule.jsx:1050-1054`).
     */
    private fun startBulk(allowPaid: Boolean) {
        val state = vm.state.value
        // Only a pick left open lets go of its bytes: a failed batch's are kept for its Retry.
        state.bulkPick?.files?.forEach { picked.remove(it.ref) }
        vm.update { copy(bulkPick = BulkPick(allowPaid = allowPaid && state.isAccountant)) }
        addBulkFiles()
    }

    /** The list is frozen while its files are being checked, as the web's panel freezes it. */
    private fun editPick(change: (BulkPick) -> BulkPick) {
        val pick = vm.state.value.bulkPick ?: return
        if (pick.checking) return
        vm.update { copy(bulkPick = bulkPick?.let(change)) }
    }

    private fun addBulkFiles() {
        val pick = vm.state.value.bulkPick ?: return
        if (pick.checking) return
        vm.run { addPicked(vm.pickMany()) }
    }

    /**
     * Adds picked or dropped files, each checked on the spot. Nothing is
     * dropped for being over the cap — the list says so and the reader
     * chooses what goes (`BulkUploadPanel`); the same name and size twice is
     * a double drop, not two invoices, and is added once.
     */
    private fun addPicked(files: List<PickedInvoiceFile>) {
        val pick = vm.state.value.bulkPick ?: return
        if (pick.checking || files.isEmpty()) return
        val fresh = files
            .distinctBy { it.name to it.bytes.size }
            .filter { file -> pick.files.none { BulkUploads.sameFile(it, file.name, file.bytes.size.toLong()) } }
        if (fresh.isEmpty()) return
        vm.update { copy(bulkPick = bulkPick?.copy(checking = true)) }
        val checked = fresh.map { file ->
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

    /**
     * Starts the batch and sends the reader to watch it. Only a list that
     * passes whole is sent — a refused file, or one too many, keeps the sheet
     * open with nothing uploaded. Runs on after the sheet is gone.
     */
    private fun submitBulk() {
        val pick = vm.state.value.bulkPick ?: return
        if (!pick.canSubmit) return
        start(pick.files.map { it.copy(paid = pick.allowPaid && it.paid) })
    }

    private fun start(files: List<BulkFile>) {
        val batch = BulkBatch(
            id = BulkUploads.newBatchId(vm.now(), Random.nextLong(0, Long.MAX_VALUE)),
            files = files.map { it.copy(status = BulkFileStatus.Pending, error = "", retried = false) },
            createdAtMs = vm.now(),
        )
        vm.update { copy(bulkPick = null, enter = null, bulkBatches = listOf(batch) + bulkBatches) }
        watchUploads()
        vm.run { runBatch(batch) }
    }

    /**
     * Retry N — the batch's storage failures, sent again as a new batch (the
     * old id may be closed on the server), paid flags and all. The old rows
     * are retired first so a second click finds nothing to send twice.
     */
    private fun retry(batchId: String) {
        val batch = vm.state.value.bulkBatches.firstOrNull { it.id == batchId } ?: return
        val files = BulkUploads.retryable(batch).mapNotNull { file ->
            val bytes = picked.remove(file.ref) ?: return@mapNotNull null
            val ref = ++nextRef
            picked[ref] = bytes
            file.copy(ref = ref)
        }
        if (files.isEmpty()) return
        vm.update {
            copy(
                bulkBatches = bulkBatches.map { b ->
                    if (b.id != batchId) {
                        b
                    } else {
                        b.copy(files = b.files.map { if (it.status == BulkFileStatus.Failed) it.copy(retried = true) else it })
                    }
                },
            )
        }
        dismissJobs.remove(batchId)?.cancel()
        start(files)
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

    /**
     * Every file runs upload → hand-off on its own, three lanes at a time, so
     * extraction on the first starts while the last is still going up. Then,
     * with nothing left to hand over, the server's counts can be believed.
     */
    private suspend fun runBatch(batch: BulkBatch) {
        val queue = Channel<BulkFile>(Channel.UNLIMITED)
        val pending = batch.files.filter { it.status == BulkFileStatus.Pending }
        pending.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(minOf(BulkUploads.LANES, pending.size)) {
                launch { for (file in queue) sendOne(batch.id, file) }
            }
        }
        vm.update {
            copy(
                bulkBatches = bulkBatches.map {
                    if (it.id != batch.id) {
                        it
                    } else {
                        it.copy(postingDone = true, error = if (it.sentCount == 0) str(S.desktop_inv_no_file_reached_server) else "")
                    }
                },
            )
        }
        val done = vm.state.value.bulkBatches.firstOrNull { it.id == batch.id }
        // Completion is read from the batch leaving the server's list, and
        // that only means something once it has been seen there.
        if (done != null && done.sentCount > 0) loadUploads()
        vm.refresh()
    }

    /**
     * One file: storage, tried three times with a short backoff (a PUT is
     * idempotent), then the hand-off — never retried: a refused hand-off may
     * still have been queued, and sending it twice is how one invoice becomes
     * two payables.
     */
    private suspend fun sendOne(batchId: String, file: BulkFile) {
        val bytes = picked[file.ref] ?: return
        patchFile(batchId, file.ref) { it.copy(status = BulkFileStatus.Uploading) }
        var uploaded: ZillitResult<InvoiceAttachment> = vm.upload(bytes)
        var attempt = 1
        while (uploaded is ZillitResult.Failure && attempt < BulkUploads.UPLOAD_ATTEMPTS) {
            delay(BulkUploads.RETRY_BASE_MS * attempt)
            attempt++
            uploaded = vm.upload(bytes)
        }
        val attachment = when (uploaded) {
            is ZillitResult.Failure -> {
                val error = uploaded.error.localised()
                patchFile(batchId, file.ref) { it.copy(status = BulkFileStatus.Failed, error = error) }
                return
            }
            is ZillitResult.Success -> uploaded.data
        }
        patchFile(batchId, file.ref) { it.copy(status = BulkFileStatus.Uploaded, error = "") }
        val sent = vm.repo.bulkUpload(batchId, attachment, file.size, file.paid)
        picked.remove(file.ref)
        patchFile(batchId, file.ref) {
            when (sent) {
                is ZillitResult.Success -> it.copy(status = BulkFileStatus.Sent)
                is ZillitResult.Failure -> it.copy(status = BulkFileStatus.SendFailed, error = sent.error.localised())
            }
        }
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

    /** Drops our half of a batch; the server's row, if any, goes on its own once finished. */
    private fun dismiss(batchId: String) {
        dismissJobs.remove(batchId)?.cancel()
        vm.state.value.bulkBatches.firstOrNull { it.id == batchId }?.files?.forEach { picked.remove(it.ref) }
        vm.update { copy(bulkBatches = bulkBatches.filterNot { it.id == batchId }) }
    }

    /**
     * A finished, clean batch clears itself a few seconds after the server
     * drops it — `expireWhenIdle`. Idempotent, so repeated reads do not keep
     * pushing the deadline back.
     */
    private fun expireWhenIdle(batchId: String) {
        if (dismissJobs.containsKey(batchId)) return
        dismissJobs[batchId] = vm.run {
            delay(BulkUploads.AUTO_DISMISS_MS)
            dismissJobs.remove(batchId)
            val batch = vm.state.value.bulkBatches.firstOrNull { it.id == batchId }
            if (batch != null && !BulkUploads.needsAttention(batch)) dismiss(batchId)
        }
    }

    /**
     * A `invoice:bulk_upload_progress` frame: kept as the newest word on our
     * batch — for a finished batch the only delivery of its final counts —
     * then the list re-read, whichever page is open (`BulkUploadWatcher`).
     * A burst of frames coalesces into one read.
     */
    fun onProgressFrame(frame: ServerBatch) {
        vm.update { copy(bulkBatches = BulkUploads.recordFrame(bulkBatches, frame)) }
        frameFetch?.cancel()
        frameFetch = vm.run {
            delay(InvoicesViewModel.SYNC_DEBOUNCE_MILLIS)
            fetchUploads()
        }
    }

    /** The server's half of Ongoing Uploads. */
    fun loadUploads() {
        vm.run { fetchUploads() }
    }

    /**
     * `fetchBatches`: the list, each of our batches marked seen with its
     * snapshot kept (a terminal one never downgraded), and every batch of
     * ours that has left the list started on its countdown to clearing.
     */
    private suspend fun fetchUploads() {
        val listed = (vm.repo.bulkBatches() as? ZillitResult.Success)?.data ?: return
        vm.update {
            copy(
                serverBatches = listed,
                seenBatches = seenBatches + listed.map { it.batchId },
                bulkBatches = BulkUploads.applyListed(bulkBatches, listed),
            )
        }
        BulkUploads.expired(vm.state.value.bulkBatches, listed).forEach(::expireWhenIdle)
    }

    private companion object {
        const val PDF = "pdf"

        /** The web's `AH_BASE`, and the link roots `prefixHref` puts under it. */
        const val HUB_ROOT = "/film-tools/account-hub"
        const val INVOICES_LINK = "/invoices"
        const val VENDORS_LINK = "/vendors"
        const val PURCHASE_ORDERS_LINK = "/purchase-orders"

        /** The `level_2` bucket a query thread's unread is filed under. */
        const val QUERY_KIND = "query_chat"

        /** A lone bank fills first; its account holder can follow only on the next pass. */
        const val AUTO_FILL_PASSES = 2
    }
}

/**
 * The vendor quick-add both invoice forms share — `usePendingVendor`'s
 * `resolveVendorId`: a pending name becomes a real vendor at submit time,
 * once. A name already created here answers its id again, so a submit that
 * fails after the vendor landed does not create a second one on retry.
 */
internal class PendingVendors(private val vm: InvoicesViewModel) {
    private val created = mutableMapOf<String, String>()

    /**
     * The id to send: [vendorId] as it is, or the pending [name] created
     * first. Null when the create was refused — the reason is on screen and
     * the pick stays pending for the next try.
     */
    suspend fun resolve(vendorId: String, name: String?, onError: (String) -> Unit = vm::fail): String? {
        if (vendorId.isNotBlank() || name.isNullOrBlank()) return vendorId
        created[name]?.let { return it }
        return when (val made = vm.repo.createVendor(name)) {
            is ZillitResult.Failure -> {
                onError(made.error.localised())
                null
            }
            is ZillitResult.Success -> {
                created[name] = made.data.id
                vm.update { copy(vendors = vendors + (made.data.id to made.data)) }
                made.data.id
            }
        }
    }
}
