@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceDirectory
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Invoices (Accounts Payable): the department view — my uploads, my
 * department's, and the ones waiting on me — or, for the accounts
 * department, the register, the inbox and the approval queue.
 */
class InvoicesViewModel(
    private val repository: InvoicesRepository,
    private val files: InvoiceFiles,
    private val resolveViewer: () -> InvoiceViewer,
    /**
     * The production's money: its default currency and the rates the others
     * convert through. One seam rather than two — a code without its rates
     * cannot add up a mixed-currency queue honestly.
     */
    private val projectMoney: () -> CurrencyRates,
    private val resolveUser: (String) -> String?,
    private val departmentName: (String) -> String?,
    private val nowMillis: () -> Long,
    /** Every department of the production, id → name; the Enter form's picker. */
    private val departments: () -> Map<String, String> = { emptyMap() },
    /** Who work can be handed to: the accounts team, and the whole production. */
    private val directory: InvoiceDirectory = InvoiceDirectory(),
) : ZillitViewModel<InvoicesUiState, InvoicesEvent, InvoicesEffect>(InvoicesUiState()) {

    private val actions = InvoiceActions(this)
    private val forms = InvoiceForms(this)
    private val payments = InvoicePayments(this)
    private val setup = InvoiceSetupActions(this)
    private val review = InvoiceReviewActions(this)

    /** Bumped per list load so a late answer for the previous tab is dropped. */
    private var loadToken = 0

    fun start() {
        setState {
            copy(
                viewer = resolveViewer(),
                projectCurrency = projectMoney().defaultCode.ifBlank { "GBP" },
                rates = projectMoney(),
                departmentNames = departmentNames + departments(),
            )
        }
        loadReference()
        listenOnce()
        refresh()
    }

    /**
     * Folds the socket's announcements into the screen: another client's
     * upload, decision or payment lands as a refetch of whatever tab is open
     * (and a re-read of an open detail), and vendor / tier / settings changes
     * re-pull the reference data — the web's `ah:invoice:*` refetch pattern.
     * Guarded so reopening the window does not stack collectors, and debounced
     * per kind because one action fans into several frames (the web coalesces
     * at `accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { kind ->
                syncJobs.remove(kind)?.cancel()
                syncJobs[kind] = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    when (kind) {
                        InvoiceRefresh.Rows -> {
                            refresh()
                            state.value.detail?.invoice?.id?.let(::refreshDetail)
                        }
                        InvoiceRefresh.Reference -> loadReference()
                    }
                }
            }
        }
    }

    private var listening = false
    private val syncJobs = mutableMapOf<InvoiceRefresh, Job>()

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    override fun onEvent(event: InvoicesEvent) {
        // The Settings page and the review overlay own their own events; both
        // answer false for everything else, so the list below is unchanged.
        if (setup.onEvent(event) || review.onEvent(event)) return
        when (event) {
            is InvoicesEvent.SelectDepartmentTab -> {
                setState { copy(departmentTab = event.tab, invoices = emptyList(), selected = emptySet()) }
                refresh()
            }
            is InvoicesEvent.SelectQuickFilter -> setState { copy(quickFilter = event.filter) }
            is InvoicesEvent.SelectPage -> {
                setState { copy(page = event.page, invoices = emptyList(), selected = emptySet(), search = "") }
                refresh()
            }
            is InvoicesEvent.ConfirmDuplicate -> judgeDuplicate(event.flagId, confirmed = true)
            is InvoicesEvent.DismissDuplicate -> judgeDuplicate(event.flagId, confirmed = false)
            is InvoicesEvent.SelectRegisterChip -> setState { copy(registerChip = event.chip) }
            is InvoicesEvent.SelectRegisterDepartment -> setState { copy(registerDepartment = event.departmentId) }
            is InvoicesEvent.SelectPostedFilter -> setState { copy(postedFilter = event.filter) }
            is InvoicesEvent.SelectCreditNoteFilter -> setState { copy(creditNoteFilter = event.filter) }
            is InvoicesEvent.SelectAccrualFilter -> setState { copy(accrualFilter = event.filter) }
            is InvoicesEvent.SelectPaymentTab -> setState { copy(paymentTab = event.tab, selected = emptySet()) }
            InvoicesEvent.ToggleSelectAll -> payments.toggleSelectAll()
            is InvoicesEvent.ProcessSelected -> payments.processSelected(event.method)
            InvoicesEvent.CancelPaymentRun -> setState { copy(runDraft = null) }
            is InvoicesEvent.ApproveRun -> payments.actOnRun(event.run, "Run approved") {
                repository.approvePaymentRun(it)
            }
            is InvoicesEvent.StartRejectRun -> setState { copy(rejectRun = RunRejection(event.run)) }
            is InvoicesEvent.RejectRunReasonChanged -> setState {
                copy(rejectRun = rejectRun?.copy(reason = event.reason))
            }
            InvoicesEvent.ConfirmRejectRun -> payments.confirmRejectRun()
            InvoicesEvent.CancelRejectRun -> setState { copy(rejectRun = null) }
            is InvoicesEvent.DeleteRun -> payments.actOnRun(event.run, "Run deleted") {
                repository.deletePaymentRun(it)
            }

            is InvoicesEvent.PostInvoice -> actOn(listOf(event.invoice), "Posted") { repository.postInvoice(it.id) }
            is InvoicesEvent.ReturnToApproval ->
                actOn(listOf(event.invoice), "Sent back for approval") { repository.returnToApproval(it.id) }

            is InvoicesEvent.SelectEntryFilter -> setState { copy(entryFilter = event.filter, selected = emptySet()) }
            is InvoicesEvent.SelectEntrySort -> setState { copy(entrySort = event.sort) }
            is InvoicesEvent.SelectPayFilter -> setState { copy(payFilter = event.method, selected = emptySet()) }
            InvoicesEvent.PostSelected -> actOn(payments.selectedRows(), "Posted") { repository.postInvoice(it.id) }
            InvoicesEvent.ReviewSelected ->
                actOn(payments.selectedRows(), "Sent for review") { repository.markUnderReview(it.id) }

            InvoicesEvent.StartAssign -> payments.startAssign()
            is InvoicesEvent.EditAssign -> setState { copy(assignFor = event.request) }
            InvoicesEvent.ConfirmAssign -> payments.confirmAssign()
            InvoicesEvent.CancelAssign -> setState { copy(assignFor = null) }

            InvoicesEvent.StartSalesInvoice -> setState {
                copy(salesDraft = SalesInvoiceDraft(currency = projectCurrency))
            }
            is InvoicesEvent.EditSalesInvoice -> setState { copy(salesDraft = event.draft) }
            InvoicesEvent.ConfirmSalesInvoice -> payments.confirmSalesInvoice()
            InvoicesEvent.CancelSalesInvoice -> setState { copy(salesDraft = null) }
            is InvoicesEvent.SendSalesInvoice ->
                payments.actOnSales("Sent to the client") { repository.sendSalesInvoice(event.invoice.id) }
            is InvoicesEvent.MarkSalesInvoicePaid ->
                payments.actOnSales("Marked paid") { repository.markSalesInvoicePaid(event.invoice.id) }
            is InvoicesEvent.DeleteSalesInvoice ->
                payments.actOnSales("Deleted") { repository.deleteSalesInvoice(event.invoice.id) }
            InvoicesEvent.RegenerateAccruals -> regenerateAccruals()
            is InvoicesEvent.ActOnCreditNote -> actOnCreditNote(event.note)
            is InvoicesEvent.SendToApproval -> sendToApproval(event.invoice)
            is InvoicesEvent.StartHold -> setState { copy(holdFor = HoldRequest(holdTargets(event.invoice))) }
            is InvoicesEvent.HoldReasonChanged -> setState { copy(holdFor = holdFor?.copy(reason = event.reason)) }
            is InvoicesEvent.HoldNotesChanged -> setState { copy(holdFor = holdFor?.copy(notes = event.notes)) }
            InvoicesEvent.ConfirmHold -> confirmHold()
            InvoicesEvent.CancelHold -> setState { copy(holdFor = null) }
            is InvoicesEvent.Release -> actOn(listOf(event.invoice), "Released") { repository.release(it.id) }
            is InvoicesEvent.Unmatch -> actOn(listOf(event.invoice), "PO removed") { repository.unmatch(it.id) }
            is InvoicesEvent.Search -> setState { copy(search = event.query) }
            InvoicesEvent.Refresh -> refresh()
            InvoicesEvent.DismissError -> setState { copy(error = null) }
            is InvoicesEvent.Export -> exportFile(event.export, event.format)
            InvoicesEvent.OpenShortcuts -> setState { copy(shortcutsOpen = true) }
            InvoicesEvent.CloseShortcuts -> setState { copy(shortcutsOpen = false) }

            is InvoicesEvent.Open -> openInvoice(event.invoice)
            InvoicesEvent.CloseDetail -> setState { copy(detail = null) }
            InvoicesEvent.OpenAttachment -> openAttachment()
            InvoicesEvent.ShowHistory -> showHistory()
            InvoicesEvent.HideHistory -> setState { copy(detail = detail?.copy(historyOpen = false)) }

            is InvoicesEvent.Approve -> actions.approve(event.invoice)
            InvoicesEvent.StartReject -> setState { copy(detail = detail?.copy(rejecting = true, rejectReason = "")) }
            is InvoicesEvent.RejectReasonChanged -> setState {
                copy(detail = detail?.copy(rejectReason = event.reason))
            }
            InvoicesEvent.ConfirmReject -> actions.reject()
            InvoicesEvent.CancelReject -> setState { copy(detail = detail?.copy(rejecting = false)) }
            is InvoicesEvent.Override -> actions.override(event.invoice)
            is InvoicesEvent.OverrideAndPay -> actions.overrideAndPay(event.invoice)
            is InvoicesEvent.Chase -> actions.chase(event.invoice)
            is InvoicesEvent.ToggleSelect -> setState {
                copy(selected = if (event.id in selected) selected - event.id else selected + event.id)
            }
            InvoicesEvent.ClearSelection -> setState { copy(selected = emptySet()) }
            InvoicesEvent.ApproveSelected -> actions.approveSelected()

            is InvoicesEvent.RequestDelete -> setState { copy(confirmDelete = event.invoice) }
            InvoicesEvent.ConfirmDelete -> actions.deleteConfirmed()
            InvoicesEvent.CancelDelete -> setState { copy(confirmDelete = null) }

            InvoicesEvent.UploadInvoice -> forms.uploadInvoice()
            is InvoicesEvent.ChooseUploadType -> setState { copy(upload = upload?.copy(type = event.type)) }
            InvoicesEvent.SendUpload -> forms.sendUpload()
            InvoicesEvent.CancelUpload -> setState { copy(upload = null) }

            InvoicesEvent.OpenEnter -> forms.openEnter()
            InvoicesEvent.CloseEnter -> setState { copy(enter = null) }
            is InvoicesEvent.SelectEnterTab -> setState { copy(enter = enter?.copy(tab = event.tab, error = null)) }
            InvoicesEvent.EnterPickFile -> forms.enterPickFile()
            is InvoicesEvent.EnterChanged -> setState { copy(enter = event.form.copy(error = null)) }
            is InvoicesEvent.EnterNetChanged -> forms.netChanged(event.value)
            is InvoicesEvent.EnterTaxChanged -> forms.taxChanged(event.value)
            is InvoicesEvent.EnterGrossChanged -> forms.grossChanged(event.value)
            InvoicesEvent.SubmitEnter -> forms.submitEnter()
            // Handled above by the Settings page and the review overlay.
            else -> Unit
        }
    }

    // -- loading -------------------------------------------------------------

    /** True when [page] loaded itself, so the list read is skipped. */
    private fun loadOwnPage(page: AccountantPage): Boolean {
        when (page) {
            AccountantPage.Settings -> setup.load()
            AccountantPage.Overview -> loadOverview()
            AccountantPage.Analytics -> loadAnalytics()
            AccountantPage.Credits -> loadCreditNotes()
            AccountantPage.Accruals -> loadAccruals()
            AccountantPage.Sales -> payments.loadSalesInvoices()
            else -> return false
        }
        return true
    }

    private fun loadAnalytics() {
        setState { copy(loading = false, analyticsLoading = true) }
        launch {
            when (val result = repository.analytics()) {
                is ZillitResult.Success -> setState {
                    // The spend rows name departments by id; the directory
                    // turns them into words, and an id nobody can resolve is
                    // shown as an em dash rather than printed raw.
                    val named = result.data.departments
                        .map { it.name.ifBlank { it.code } }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .mapNotNull { id -> departmentName(id)?.let { id to it } }
                    copy(
                        analyticsLoading = false,
                        analytics = result.data,
                        departmentNames = departmentNames + named,
                    )
                }
                is ZillitResult.Failure ->
                    setState { copy(analyticsLoading = false, error = result.error.localised()) }
            }
        }
    }


    /**
     * Fetches the file and hands it to the OS, as the web's download does.
     *
     * The name carries the date so a second export does not silently replace
     * the first in the Downloads folder.
     */
    private fun exportFile(export: InvoiceExport, format: InvoiceExportFormat) {
        if (state.value.busy) return
        setState { copy(busy = true) }
        launch {
            val result = files.export(export, format)
            setState { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                val stamp = InvoiceFormat.fileStamp(now())
                val saved = files.saveAndOpen("${export.fileStem}_$stamp.${format.extension}", result.data)
                if (saved is ZillitResult.Failure) {
                    setState { copy(error = saved.error.localised()) }
                } else {
                    notice("Exported")
                }
            }
        }
    }

    private fun loadAccruals() {
        setState { copy(loading = false, accrualsLoading = true) }
        launch {
            when (val result = repository.accruals()) {
                is ZillitResult.Success -> setState { copy(accrualsLoading = false, accruals = result.data) }
                is ZillitResult.Failure -> setState { copy(accrualsLoading = false, error = result.error.localised()) }
            }
        }
    }

    /** The server recomputes from the orders and invoices as they stand; then the list is re-read. */
    private fun regenerateAccruals() {
        if (state.value.busy) return
        setState { copy(busy = true) }
        launch {
            val result = repository.regenerateAccruals()
            setState { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                notice("Accruals recalculated")
                loadAccruals()
            }
        }
    }

    private fun loadCreditNotes() {
        setState { copy(loading = false, creditNotesLoading = true) }
        launch {
            when (val result = repository.creditNotes()) {
                is ZillitResult.Success -> setState { copy(creditNotesLoading = false, creditNotes = result.data) }
                is ZillitResult.Failure ->
                    setState { copy(creditNotesLoading = false, error = result.error.localised()) }
            }
        }
    }

    /**
     * The row's own button — the web's `STATUS_MAP.action`.
     *
     * A pending note is applied, a disputed one is resolved by disputing it
     * again on the server's side; the rest only open, so nothing is sent.
     */
    private fun actOnCreditNote(note: CreditNote) {
        if (!note.status.isActionable) return
        setState { copy(busy = true) }
        launch {
            val result = when (note.status) {
                CreditNoteStatus.Pending -> repository.applyCreditNote(note.id)
                else -> repository.disputeCreditNote(note.id)
            }
            setState { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                notice(if (note.status == CreditNoteStatus.Pending) "Credit note applied" else "Dispute raised")
                loadCreditNotes()
            }
        }
    }

    /** One row, or everything ticked — the web's per-row and bulk actions share a path. */
    private fun holdTargets(invoice: Invoice?): List<Invoice> = when {
        invoice != null -> listOf(invoice)
        else -> state.value.invoices.filter { it.id in state.value.selected }
    }

    private fun sendToApproval(invoice: Invoice?) {
        // A held invoice cannot be sent; the web excludes them from select-all
        // for the same reason.
        val rows = holdTargets(invoice).filter { it.status != InvoiceStatus.Held }
        if (rows.isEmpty()) {
            setState { copy(error = "Nothing to send.") }
            return
        }
        actOn(rows, "Sent for approval") { repository.sendToApproval(it.id) }
    }

    private fun confirmHold() {
        val request = state.value.holdFor ?: return
        val reason = request.reason ?: return
        if (!request.isReady || request.busy) return
        setState { copy(holdFor = holdFor?.copy(busy = true)) }
        launch {
            val failure = request.invoices.firstNotNullOfOrNull {
                (repository.hold(it.id, reason, request.notes) as? ZillitResult.Failure)?.error
            }
            setState {
                copy(
                    holdFor = if (failure == null) null else holdFor?.copy(busy = false),
                    // The review is where a hold is usually decided; once the
                    // hold lands the invoice has left the queue, so the
                    // overlay behind the dialog closes with it.
                    review = if (failure == null) null else review,
                    error = failure?.localised(),
                    selected = if (failure == null) emptySet() else selected,
                )
            }
            if (failure == null) refresh()
        }
    }

    /**
     * Runs [action] over every row, then reloads.
     *
     * Row by row rather than in one call because the service has no bulk
     * route for any of these — the web's loops do the same.
     */
    internal fun actOn(rows: List<Invoice>, done: String, action: suspend (Invoice) -> ZillitResult<Unit>) {
        if (rows.isEmpty()) return
        setState { copy(busy = true) }
        launch {
            val failure = rows.firstNotNullOfOrNull { (action(it) as? ZillitResult.Failure)?.error }
            setState { copy(busy = false, selected = emptySet(), error = failure?.localised()) }
            if (failure == null) notice("$done (${rows.size})")
            refresh()
        }
    }

    /** Which invoices the open accountant page lists; the pages that list none answer empty. */
    private suspend fun accountantRows(page: AccountantPage): ZillitResult<List<Invoice>> = when (page) {
        AccountantPage.Register -> repository.list(InvoiceQuery())
        AccountantPage.Inbox -> repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Inbox)))
        AccountantPage.ApprovalQueue ->
            repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Approval, InvoiceStatus.Rejected)))
        AccountantPage.Posted -> repository.postedInvoices()
        // Entry is what approval has cleared, or bypassed, and entry has not
        // yet posted — the web's `approved,under_review,override`.
        AccountantPage.Entry -> repository.list(
            InvoiceQuery(
                statuses = listOf(InvoiceStatus.Approved, InvoiceStatus.UnderReview, InvoiceStatus.Override),
            ),
        )
        // A payment run is built from what is approved and ready; the batches
        // themselves are a second read, running alongside.
        AccountantPage.Payments -> {
            payments.loadPaymentRuns()
            repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.ReadyToPay)))
        }
        // Vendors shows spend, which is every invoice this production has.
        AccountantPage.Vendors -> repository.list(InvoiceQuery())
        // What the production owes, grouped by vendor on the screen.
        AccountantPage.Creditors -> repository.list(InvoiceQuery(statuses = Creditors.OPEN_STATUSES))
        // Pre-approval is two queues in one list, as the web loads it.
        AccountantPage.Matching ->
            repository.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Matching, InvoiceStatus.Held)))
        // The dashboard has its own reads; every other page is either not
        // built yet or does not show this list.
        else -> ZillitResult.Success(emptyList())
    }

    /**
     * The dashboard and its duplicate flags.
     *
     * Two reads rather than one, as the web does: the duplicates panel has
     * its own spinner and its own empty state, so a slow flag check does not
     * hold up the tiles.
     */
    private fun loadOverview() {
        setState { copy(loading = false, overviewLoading = true, duplicatesLoading = true) }
        launch {
            when (val result = repository.overview()) {
                is ZillitResult.Success -> setState { copy(overviewLoading = false, overview = result.data) }
                is ZillitResult.Failure ->
                    setState { copy(overviewLoading = false, error = result.error.localised()) }
            }
        }
        launch {
            val flags = repository.duplicates()
            setState {
                copy(
                    duplicatesLoading = false,
                    duplicates = (flags as? ZillitResult.Success)?.data ?: duplicates,
                )
            }
        }
    }

    /**
     * Confirming keeps the flag and marks it real; dismissing clears it.
     *
     * The row changes here first so the click answers at once, and the server
     * is told after — a refusal puts the flag back rather than leaving the
     * screen claiming a judgement nobody stored.
     */
    private fun judgeDuplicate(flagId: String, confirmed: Boolean) {
        val previous = state.value.duplicates
        setState {
            copy(
                duplicates = if (confirmed) {
                    duplicates.map { if (it.id == flagId) it.copy(status = DuplicateFlag.CONFIRMED) else it }
                } else {
                    duplicates.filterNot { it.id == flagId }
                },
            )
        }
        launch {
            val result = if (confirmed) {
                repository.confirmDuplicate(flagId)
            } else {
                repository.dismissDuplicate(flagId)
            }
            if (result is ZillitResult.Failure) {
                setState { copy(duplicates = previous, error = result.error.localised()) }
            }
        }
    }

    private fun loadReference() {
        launch {
            when (val r = repository.settings()) {
                // Older backends have no settings; the buttons fall back to designation.
                is ZillitResult.Failure -> Unit
                is ZillitResult.Success -> setState { copy(viewer = viewer.withSettings(r.data)) }
            }
        }
        launch {
            when (val r = repository.approvalTiers()) {
                is ZillitResult.Failure -> setState {
                    copy(error = "Could not load approval tiers: ${r.error.localised()}")
                }
                is ZillitResult.Success -> setState { copy(tierConfigs = r.data) }
            }
        }
        launch {
            (repository.vendors() as? ZillitResult.Success)?.let { r ->
                setState { copy(vendors = r.data.associateBy { it.id }) }
            }
        }
        launch { (repository.bankAccounts() as? ZillitResult.Success)?.let { r -> setState { copy(banks = r.data) } } }
    }

    internal fun refresh() {
        val s = state.value
        // Three accountant pages are not invoice lists and fetch their own.
        if (s.isAccountant && loadOwnPage(s.page)) return
        val token = ++loadToken
        setState { copy(loading = true) }
        launch {
            val result = if (s.isAccountant) {
                accountantRows(s.page)
            } else {
                when (s.departmentTab) {
                    DepartmentTab.ApprovalQueue -> repository.approvalQueue()
                    DepartmentTab.MyDepartment -> if (s.viewer.departmentId.isBlank()) {
                        ZillitResult.Success(emptyList())
                    } else {
                        repository.list(InvoiceQuery(departmentId = s.viewer.departmentId))
                    }
                    DepartmentTab.MyInvoices -> repository.mine()
                }
            }
            if (token != loadToken) return@launch
            when (result) {
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error.localised()) }
                is ZillitResult.Success -> setState {
                    val ids = result.data.map { it.id }.toSet()
                    copy(
                        loading = false,
                        invoices = result.data,
                        departmentNames = departmentNames + namesOfDepartments(result.data),
                        userNames = userNames + namesOfAssignees(result.data),
                        assignees = assignees.ifEmpty { directory.accountsTeam() },
                        // Payment Runs opens with everything ticked, as the web
                        // does: the queue exists to be paid, not picked over.
                        selected = if (s.page == AccountantPage.Payments) {
                            ids
                        } else {
                            selected.filter { it in ids }.toSet()
                        },
                    )
                }
            }
        }
    }

    private fun namesOfAssignees(rows: List<Invoice>): Map<String, String> =
        rows.map { it.assignedTo }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> resolveUser(id)?.let { id to it } }.toMap()

    private fun namesOfDepartments(rows: List<Invoice>): Map<String, String> =
        rows.map { it.departmentId }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> departmentName(id)?.let { id to it } }.toMap()

    // -- detail --------------------------------------------------------------

    internal fun openInvoice(invoice: Invoice) {
        setState { copy(detail = InvoiceDetail(invoice = invoice, names = namesFor(invoice))) }
        launch {
            when (val r = repository.invoice(invoice.id)) {
                is ZillitResult.Failure -> setState {
                    copy(detail = detail?.copy(loading = false), error = r.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        val d = detail?.takeIf { it.invoice.id == invoice.id } ?: return@setState this
                        copy(detail = d.copy(invoice = r.data, loading = false, names = d.names + namesFor(r.data)))
                    }
                    loadPreview(r.data)
                }
            }
        }
    }

    /** Re-reads an open detail after a mutation so the chain and status are current. */
    internal fun refreshDetail(id: String) {
        if (state.value.detail?.invoice?.id != id) return
        launch {
            (repository.invoice(id) as? ZillitResult.Success)?.let { r ->
                setState {
                    val d = detail?.takeIf { it.invoice.id == id } ?: return@setState this
                    copy(detail = d.copy(invoice = r.data, names = d.names + namesFor(r.data)))
                }
            }
        }
    }

    private fun loadPreview(invoice: Invoice) {
        // PDFs are previewed too now — the pane renders their pages, so there
        // is no reason to fetch only pictures.
        val attachment = invoice.firstAttachment?.takeIf { it.isImage || it.isPdf } ?: return
        setState { copy(detail = detail?.copy(previewLoading = true)) }
        launch {
            val r = files.fetch(attachment)
            setState {
                val d = detail?.takeIf { it.invoice.id == invoice.id } ?: return@setState this
                copy(
                    detail = when (r) {
                        is ZillitResult.Success -> d.copy(preview = AttachmentBytes(r.data), previewLoading = false)
                        is ZillitResult.Failure -> d.copy(previewLoading = false, previewFailed = true)
                    },
                )
            }
        }
    }

    private fun openAttachment() {
        val d = state.value.detail ?: return
        val attachment = d.invoice.firstAttachment ?: return
        setState { copy(detail = detail?.copy(opening = true)) }
        launch {
            val bytes = d.preview?.bytes?.let { ZillitResult.Success(it) } ?: files.fetch(attachment)
            val outcome = when (bytes) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> files.saveAndOpen(
                    attachment.name.ifBlank { "invoice.${attachment.extension}" },
                    bytes.data,
                )
            }
            val failure = (outcome as? ZillitResult.Failure)?.error?.userMessage
            setState { copy(detail = detail?.copy(opening = false), error = failure) }
            if (outcome is ZillitResult.Success) sendEffect(InvoicesEffect.Notice("Saved to Downloads"))
        }
    }

    private fun showHistory() {
        val d = state.value.detail ?: return
        setState { copy(detail = detail?.copy(historyOpen = true, historyLoading = detail.history == null)) }
        if (d.history != null) return
        launch {
            when (val r = repository.history(d.invoice.id)) {
                is ZillitResult.Failure -> setState {
                    copy(detail = detail?.copy(historyLoading = false), error = r.error.localised())
                }
                is ZillitResult.Success -> setState {
                    val current = detail?.takeIf { it.invoice.id == d.invoice.id } ?: return@setState this
                    val actors = r.data.map { it.actionBy }.filter { it.isNotBlank() }.distinct()
                        .mapNotNull { id -> resolveUser(id)?.let { id to it } }
                    copy(
                        detail = current.copy(history = r.data, historyLoading = false, names = current.names + actors),
                    )
                }
            }
        }
    }

    // -- shared helpers for the action/form helpers ----------------------------

    internal fun tiersFor(invoice: Invoice): List<ResolvedTier> = ApprovalChain.tiersFor(
        state.value.tierConfigs,
        invoice,
    )

    /** The accounts team, as the assign sheet offers it. */
    internal fun team(): List<InvoiceAssignee> = directory.accountsTeam()

    /** Everyone on the production, for the sign-off chain. */
    internal fun people(): List<InvoiceAssignee> = directory.everyone().ifEmpty { directory.accountsTeam() }

    /** Fills the name lookups a page needs when it loads no invoices of its own. */
    internal fun fillDirectories() = setState {
        copy(
            assignees = assignees.ifEmpty { directory.accountsTeam() },
            departmentNames = departmentNames + departments(),
            userNames = userNames + directory.everyone().associate { it.id to it.name },
        )
    }

    internal fun namesFor(invoice: Invoice): Map<String, String> {
        val ids = buildSet {
            add(invoice.userId)
            add(invoice.updatedBy)
            add(invoice.rejectedBy)
            invoice.approvals.forEach { add(it.userId) }
            tiersFor(invoice).forEach { addAll(it.userIds) }
        }.filter { it.isNotBlank() }
        return ids.mapNotNull { id -> resolveUser(id)?.let { id to it } }.toMap()
    }

    internal fun update(reducer: InvoicesUiState.() -> InvoicesUiState) = setState(reducer)

    /** Puts a refusal in the page's own banner — every screen here shows errors that way. */
    internal fun fail(message: String) = setState { copy(error = message) }

    /**
     * Re-reads the settings document for the reader's own rights.
     *
     * Editing the team can change what the reader may do — posting, run
     * authorisation, override — so the buttons those gate have to follow.
     */
    internal fun reloadViewerRights() {
        launch {
            (repository.settings() as? ZillitResult.Success)?.let { r ->
                setState { copy(viewer = viewer.withSettings(r.data)) }
            }
        }
    }

    internal fun notice(text: String) = sendEffect(InvoicesEffect.Notice(text))

    internal fun run(block: suspend () -> Unit) = launch { block() }

    internal fun now(): Long = nowMillis()

    internal suspend fun pickOne(): PickedInvoiceFile? = files.pick().firstOrNull()

    internal suspend fun upload(file: PickedInvoiceFile) = files.upload(file)

    /** The signed fetch of a stored file — the detail dialog's and the review overlay's. */
    internal suspend fun fetchAttachment(attachment: InvoiceAttachment) = files.fetch(attachment)

    internal val repo: InvoicesRepository get() = repository

    internal fun matchVendor(name: String): String? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        val vendors = state.value.vendors.values
        val exact = vendors.firstOrNull { it.name.trim().lowercase() == needle }
        return (exact ?: vendors.firstOrNull { it.name.lowercase().contains(needle) })?.id
    }

    internal fun departmentUpload(flow: UploadFlow): DepartmentUpload = DepartmentUpload(
        type = flow.type ?: error("type chosen"),
        fileName = flow.file.name,
        attachment = flow.attachment,
        extraction = flow.extraction,
        departmentId = state.value.viewer.departmentId.ifBlank { null },
        projectCurrency = state.value.projectCurrency,
    )

    internal fun entered(form: EnterInvoiceForm): EnteredInvoice? {
        val attachment = form.attachment ?: return null
        val invoiceDate = InvoiceFormat.parseDateInput(form.invoiceDate) ?: return null
        val gross = form.grossValue ?: return null
        return EnteredInvoice(
            attachment = attachment,
            invoiceNumber = form.invoiceNumber.trim(),
            vendorId = form.vendorId,
            description = form.description.trim(),
            grossAmount = gross,
            invoiceDateMs = invoiceDate,
            dueDateMs = InvoiceFormat.parseDateInput(form.dueDate) ?: (nowMillis() + DEFAULT_TERMS_MS),
            effectiveDateMs = InvoiceFormat.parseDateInput(form.effectiveDate),
            payMethod = form.payMethod,
            currency = form.currency.trim().uppercase().ifBlank { state.value.projectCurrency },
            netAmount = form.netValue,
            taxAmount = form.taxValue,
            bankId = form.bankId.ifBlank { null },
            episode = form.episode.trim().ifBlank { null },
            departmentId = form.departmentId.ifBlank { null },
            poNumber = form.poNumber.trim().ifBlank { null },
            uploadId = form.extraction?.uploadId?.ifBlank { null },
        )
    }

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        val UPLOAD_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png")
        val ENTER_EXTENSIONS = UPLOAD_EXTENSIONS + setOf("doc", "docx")
        private const val DEFAULT_TERMS_MS = 30L * 86_400_000L

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}
