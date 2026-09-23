@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceDirectory
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Invoices (Accounts Payable): the department view — my uploads, my
 * department's, and the ones waiting on me — or, for the accounts
 * department, the register, the inbox and the approval queue.
 */
@Suppress("LongParameterList", "LargeClass") // One host seam per concern; the pages' actions live in collaborators.
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
    /** The ledger's rows for this tool per page key, and the page read. */
    private val badges: TabBadgeSource = TabBadgeSource.None,
) : ZillitViewModel<InvoicesUiState, InvoicesEvent, InvoicesEffect>(InvoicesUiState()) {

    private val actions = InvoiceActions(this)
    private val forms = InvoiceForms(this)
    private val payments = InvoicePayments(this)
    private val setup = InvoiceSetupActions(this)
    private val review = InvoiceReviewActions(this)
    private val entry = InvoiceEntryActions(this)
    private val queries = InvoiceQueryActions(this)
    private val inbox = InvoiceInboxActions(this)
    private val quick = InvoiceQuickEntryActions(this)
    private val credits = InvoiceCreditActions(this)
    private val sales = InvoiceSalesActions(this)

    /** Bumped per list load so a late answer for the previous tab is dropped. */
    private var loadToken = 0

    /** A route asked for before [start] resolved who is looking; applied there. */
    private var pendingRoute: String? = null
    private var viewerResolved = false

    /**
     * A senior-only page a route asked for while seniority was still unknown
     * (the settings document not yet read). Overview shows meanwhile; the page
     * opens once the settings confirm it, unless the reader has moved on.
     */
    private var awaitingSenior: AccountantPage? = null

    fun start() {
        setState {
            copy(
                viewer = resolveViewer(),
                projectCurrency = projectMoney().defaultCode.ifBlank { "GBP" },
                rates = projectMoney(),
                departmentNames = departmentNames + departments(),
            )
        }
        viewerResolved = true
        pendingRoute?.let(::applyRoute)
        pendingRoute = null
        loadReference()
        listenOnce()
        refresh()
    }

    /** The page on screen is its read — its rows, whole, as it is looked at. */
    private fun readOpenPage() {
        val key = currentState.openBadgeKey ?: return
        if ((currentState.unread[key] ?: 0) > 0) badges.read(key)
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
        // A row landing on the open page is read as it lands.
        launch { badges.counts.collect { counts -> setState { copy(unread = counts) }.also { readOpenPage() } } }
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
        if (setup.onEvent(event) || review.onEvent(event) || entry.onEvent(event)) return
        if (queries.onEvent(event) || inbox.onEvent(event) || quick.onEvent(event)) return
        if (credits.onEvent(event) || sales.onEvent(event)) return
        when (event) {
            is InvoicesEvent.SelectDepartmentTab -> {
                setState { copy(departmentTab = event.tab, invoices = emptyList(), selected = emptySet()) }
                refresh()
                readOpenPage()
            }
            is InvoicesEvent.SelectQuickFilter -> setState { copy(quickFilter = event.filter) }
            is InvoicesEvent.SelectPage -> {
                // The sidebar hides a senior-only row; the handler refuses it
                // too, so no other path can open it for somebody else.
                if (event.page.seniorOnly && !currentState.viewer.isSenior) return
                awaitingSenior = null
                showPage(event.page)
            }
            is InvoicesEvent.OpenRoute -> openRoute(event.path)
            is InvoicesEvent.ConfirmDuplicate -> judgeDuplicate(event.flagId, confirmed = true)
            is InvoicesEvent.DismissDuplicate -> judgeDuplicate(event.flagId, confirmed = false)
            is InvoicesEvent.SelectRegisterChip -> setState { copy(registerChip = event.chip) }
            is InvoicesEvent.SelectRegisterDepartment -> setState { copy(registerDepartment = event.departmentId) }
            is InvoicesEvent.SelectRegisterDate -> setState { copy(registerDate = event.window) }
            is InvoicesEvent.ToggleGroupOpen -> setState {
                copy(
                    collapsedGroups = if (event.key in collapsedGroups) {
                        collapsedGroups - event.key
                    } else {
                        collapsedGroups + event.key
                    },
                )
            }
            is InvoicesEvent.SelectGroup -> setState {
                val all = selected.containsAll(event.ids)
                copy(selected = if (all) selected - event.ids.toSet() else selected + event.ids)
            }
            is InvoicesEvent.MarkPaidOne -> payments.markPaidOne(event.invoice)
            is InvoicesEvent.SelectPostedFilter -> setState { copy(postedFilter = event.filter) }
            is InvoicesEvent.SelectCreditNoteFilter -> setState { copy(creditNoteFilter = event.filter) }
            is InvoicesEvent.SelectAccrualFilter -> setState { copy(accrualFilter = event.filter) }
            is InvoicesEvent.SelectPaymentTab -> setState { copy(paymentTab = event.tab, selected = emptySet()) }
            InvoicesEvent.ToggleSelectAll -> if (currentState.page == AccountantPage.Entry) {
                // Select-all covers only what this reader may open, and nothing in a closed period.
                val ids = currentState.entrySelectableIds.toSet()
                setState { copy(selected = if (ids.isNotEmpty() && selected.containsAll(ids)) emptySet() else ids) }
            } else {
                payments.toggleSelectAll()
            }
            is InvoicesEvent.ProcessSelected -> payments.processSelected(event.method)
            InvoicesEvent.CancelPaymentRun -> setState { copy(runDraft = null) }
            is InvoicesEvent.OpenRun -> payments.openRun(event.run)
            InvoicesEvent.CloseRun -> setState { copy(runDetail = null) }
            is InvoicesEvent.ApproveRun -> payments.approveRun(event.run)
            InvoicesEvent.RequestCancelRun -> payments.requestCancelRun()
            InvoicesEvent.ConfirmCancelRun -> payments.confirmCancelRun()
            InvoicesEvent.KeepRun -> setState { copy(runDetail = runDetail?.copy(confirmCancel = false)) }
            is InvoicesEvent.StartRejectRun -> payments.startRejectRun(event.run)
            is InvoicesEvent.RejectRunReasonChanged -> setState {
                copy(rejectRun = rejectRun?.copy(reason = event.reason))
            }
            InvoicesEvent.ConfirmRejectRun -> payments.confirmRejectRun()
            InvoicesEvent.CancelRejectRun -> setState { copy(rejectRun = null) }

            is InvoicesEvent.PostInvoice -> actOn(
                listOf(event.invoice),
                str(S.ah_status_posted),
            ) { repository.postInvoice(it.id) }
            is InvoicesEvent.ReturnToApproval ->
                actOn(
                    listOf(event.invoice),
                    str(S.desktop_inv_sent_back_for_approval),
                ) { repository.returnToApproval(it.id) }

            is InvoicesEvent.SelectEntryFilter -> setState { copy(entryFilter = event.filter, selected = emptySet()) }
            is InvoicesEvent.SelectEntrySort -> setState { copy(entrySort = event.sort) }
            is InvoicesEvent.SelectPayFilter -> setState { copy(payFilter = event.method, selected = emptySet()) }
            // Seniors are the reviewers, so the hand-off is not theirs (ZL-20450);
            // a row in a closed period is never in the selection to begin with.
            InvoicesEvent.ReviewSelected -> if (!currentState.viewer.isSenior) {
                val rows = payments.selectedRows().filterNot { currentState.isLocked(it) }
                actOn(rows, str(S.desktop_sent_for_review)) { repository.markUnderReview(it.id) }
            }

            InvoicesEvent.StartAssign -> payments.startAssign()
            is InvoicesEvent.EditAssign -> setState { copy(assignFor = event.request) }
            InvoicesEvent.ConfirmAssign -> payments.confirmAssign()
            InvoicesEvent.CancelAssign -> setState { copy(assignFor = null) }

            InvoicesEvent.RegenerateAccruals -> regenerateAccruals()
            is InvoicesEvent.SendToApproval -> sendToApproval(event.invoice)
            is InvoicesEvent.StartHold -> setState { copy(holdFor = HoldRequest(holdTargets(event.invoice))) }
            is InvoicesEvent.HoldReasonChanged -> setState { copy(holdFor = holdFor?.copy(reason = event.reason)) }
            is InvoicesEvent.HoldNotesChanged -> setState { copy(holdFor = holdFor?.copy(notes = event.notes)) }
            InvoicesEvent.ConfirmHold -> confirmHold()
            InvoicesEvent.CancelHold -> setState { copy(holdFor = null) }
            is InvoicesEvent.Release -> actOn(
                listOf(event.invoice),
                str(S.desktop_released),
            ) { repository.release(it.id) }
            is InvoicesEvent.Unmatch -> actOn(
                listOf(event.invoice),
                str(S.desktop_po_removed),
            ) { repository.unmatch(it.id) }
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
            InvoicesEvent.StartReject -> setState {
                copy(detail = detail?.takeIf { it.decisions }?.copy(rejecting = true, rejectReason = "") ?: detail)
            }
            is InvoicesEvent.RejectReasonChanged -> setState {
                copy(detail = detail?.copy(rejectReason = event.reason))
            }
            InvoicesEvent.ConfirmReject -> actions.reject()
            InvoicesEvent.CancelReject -> setState { copy(detail = detail?.copy(rejecting = false)) }
            is InvoicesEvent.Override -> actions.override(event.invoice)
            is InvoicesEvent.OverrideAndPay -> actions.overrideAndPay(event.invoice)
            is InvoicesEvent.Chase -> actions.chase(event.invoice)
            is InvoicesEvent.ToggleSelect -> if (mayTick(event.id)) {
                setState { copy(selected = if (event.id in selected) selected - event.id else selected + event.id) }
            }
            InvoicesEvent.ClearSelection -> setState { copy(selected = emptySet()) }
            InvoicesEvent.ApproveSelected -> actions.approveSelected()

            is InvoicesEvent.RequestDelete -> setState { copy(confirmDelete = event.invoice) }
            InvoicesEvent.ConfirmDelete -> actions.deleteConfirmed()
            InvoicesEvent.CancelDelete -> setState { copy(confirmDelete = null) }

            // The department sends documents; accounts codes them — the web's BulkUploadModal.
            InvoicesEvent.UploadInvoice -> onEvent(InboxEvent.StartBulk(allowPaid = false))

            // Enter Invoice opens on its Upload tab, which is the bulk upload with Paid offered.
            InvoicesEvent.OpenEnter -> {
                forms.openEnter()
                setState { copy(bulkPick = BulkPick(allowPaid = true)) }
            }
            InvoicesEvent.CloseEnter -> setState { copy(enter = null, bulkPick = null) }
            is InvoicesEvent.SelectEnterTab -> setState {
                copy(
                    enter = enter?.copy(tab = event.tab, error = null),
                    bulkPick = if (event.tab == EnterTab.Upload) bulkPick ?: BulkPick(allowPaid = true) else bulkPick,
                )
            }
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

    /**
     * Whether a row may join the selection: never one dated in a closed
     * cost-report period, and on Invoice Entry never one the reader cannot
     * open — select-all and a single tick hold the same line (ZL-20496).
     */
    private fun mayTick(id: String): Boolean {
        val s = currentState
        val row = s.invoices.firstOrNull { it.id == id } ?: return true
        if (id in s.selected) return true
        if (s.isLocked(row)) return false
        return s.page != AccountantPage.Entry || s.canAccessEntry(row)
    }

    // -- routes --------------------------------------------------------------

    private fun showPage(page: AccountantPage) {
        setState {
            copy(
                page = page,
                invoices = emptyList(),
                selected = emptySet(),
                search = "",
                ledger = null,
                inboxTab = InboxTab.Queue,
                credit = credit.copy(form = null, preview = null, history = null, confirmDelete = null),
            )
        }
        refresh()
        readOpenPage()
    }

    private fun openRoute(path: String) {
        if (!viewerResolved) {
            pendingRoute = path
            return
        }
        if (applyRoute(path)) {
            refresh()
            readOpenPage()
        }
    }

    /**
     * Puts the page a route names on screen, without loading it; true when
     * the page changed.
     *
     * Only the accountant view has pages. A senior-only page is refused to
     * anyone who is not senior — the web bounces `/settings` to Overview — but
     * while seniority is still unknown the route is remembered rather than
     * refused, because the settings document may yet confirm it.
     */
    private fun applyRoute(path: String): Boolean {
        val s = currentState
        if (!s.isAccountant) return false
        val asked = AccountantPage.forRoute(path)
        awaitingSenior = null
        val page = when {
            !asked.seniorOnly || s.viewer.isSenior -> asked
            s.viewer.seniorFlag == null -> {
                awaitingSenior = asked
                AccountantPage.Overview
            }
            else -> AccountantPage.Overview
        }
        if (page == s.page) return false
        setState { copy(page = page, invoices = emptyList(), selected = emptySet(), search = "") }
        return true
    }

    /**
     * Folds the settings document's rights into the viewer, then settles the
     * page against them: a senior-only page the reader has just lost closes to
     * Overview, and one a route was waiting on opens once it is confirmed.
     */
    private fun applySettings(settings: InvoiceSettings) {
        setState {
            copy(
                viewer = viewer.withSettings(settings),
                runAuth = settings.runAuthorisation,
                hasRunAuthoriser = settings.hasRunAuthoriser,
            )
        }
        val s = currentState
        val waiting = awaitingSenior
        awaitingSenior = null
        when {
            s.page.seniorOnly && !s.viewer.isSenior -> showPage(AccountantPage.Overview)
            waiting != null && s.viewer.isSenior && s.page == AccountantPage.Overview -> showPage(waiting)
        }
    }

    // -- loading -------------------------------------------------------------

    /** True when [page] loaded itself, so the list read is skipped. */
    private fun loadOwnPage(page: AccountantPage): Boolean {
        when (page) {
            AccountantPage.Settings -> setup.load()
            AccountantPage.Overview -> loadOverview()
            AccountantPage.Analytics -> loadAnalytics()
            AccountantPage.Credits -> credits.load()
            AccountantPage.Accruals -> loadAccruals()
            AccountantPage.Sales -> sales.load()
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
                    val named = (result.data.departments.map { it.name.ifBlank { it.code } } +
                        result.data.departmentSpend.map { it.name })
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
                    notice(str(S.desktop_exported))
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
                notice(str(S.desktop_accruals_recalculated))
                loadAccruals()
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
            setState { copy(error = str(S.desktop_nothing_to_send)) }
            return
        }
        actOn(rows, str(S.ah_sent_for_approval_toast)) { repository.sendToApproval(it.id) }
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
                is ZillitResult.Failure -> awaitingSenior = null
                is ZillitResult.Success -> applySettings(r.data)
            }
        }
        launch {
            when (val r = repository.approvalTiers()) {
                is ZillitResult.Failure -> setState {
                    copy(error = str(S.desktop_inv_could_not_load_approval_tiers, r.error.localised()))
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
        // Production Setup's companies and tax types, for the ledger's selects.
        launch {
            (repository.projectSettings() as? ZillitResult.Success)?.let { r ->
                setState { copy(companies = r.data.companies, taxTypes = r.data.taxTypes, taxTypesKnown = true) }
            }
        }
        // The close boundary: what it dates on or before is read-only everywhere.
        launch {
            (repository.periodLock() as? ZillitResult.Success)?.let { r -> setState { copy(periodLock = r.data) } }
        }
    }

    /** Re-reads the invoice open on the coding screen, after something changed it from outside. */
    internal fun reloadLedger() = entry.reloadOpen()

    internal fun refresh() {
        val s = state.value
        // Three accountant pages are not invoice lists and fetch their own.
        if (s.isAccountant && loadOwnPage(s.page)) return
        if (loadUploadsOnly(s)) return
        val token = ++loadToken
        setState { copy(loading = true) }
        launch {
            val result = if (s.isAccountant) accountantRows(s.page) else departmentRows(s)
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

    /**
     * Ongoing Uploads is batches, not invoices: the department's tab reads
     * only those (true — nothing else to load); the Inbox's reads them beside
     * its queue.
     */
    private fun loadUploadsOnly(s: InvoicesUiState): Boolean {
        if (!s.isAccountant && s.departmentTab == DepartmentTab.Uploads) {
            setState { copy(loading = false) }
            inbox.loadUploads()
            return true
        }
        if (s.isAccountant && s.page == AccountantPage.Inbox && s.inboxTab == InboxTab.Uploads) inbox.loadUploads()
        return false
    }

    private suspend fun departmentRows(s: InvoicesUiState): ZillitResult<List<Invoice>> = when (s.departmentTab) {
        DepartmentTab.ApprovalQueue -> repository.approvalQueue()
        DepartmentTab.MyDepartment -> if (s.viewer.departmentId.isBlank()) {
            ZillitResult.Success(emptyList())
        } else {
            repository.list(InvoiceQuery(departmentId = s.viewer.departmentId))
        }
        DepartmentTab.MyInvoices -> repository.mine()
        // Read by loadUploadsOnly, as batches; never reaches here.
        DepartmentTab.Uploads -> ZillitResult.Success(emptyList())
    }

    private fun namesOfAssignees(rows: List<Invoice>): Map<String, String> =
        rows.map { it.assignedTo }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> resolveUser(id)?.let { id to it } }.toMap()

    private fun namesOfDepartments(rows: List<Invoice>): Map<String, String> =
        rows.map { it.departmentId }.filter { it.isNotBlank() }.distinct()
            .mapNotNull { id -> departmentName(id)?.let { id to it } }.toMap()

    // -- detail --------------------------------------------------------------

    internal fun openInvoice(invoice: Invoice) {
        setState {
            val register = isAccountant && page == AccountantPage.Register
            copy(detail = InvoiceDetail(invoice = invoice, names = namesFor(invoice), decisions = !register))
        }
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
            if (outcome is ZillitResult.Success) {
                sendEffect(InvoicesEffect.Notice(str(S.docusign_signing_attachment_saved)))
            }
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

    /** Puts names to user ids the screen is about to show — history actors, query authors. */
    internal fun rememberNames(ids: Collection<String>) {
        val known = state.value.userNames
        val fresh = ids.filter { it.isNotBlank() && it !in known }.distinct()
            .mapNotNull { id -> resolveUser(id)?.let { id to it } }
        if (fresh.isNotEmpty()) setState { copy(userNames = userNames + fresh) }
    }

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
            (repository.settings() as? ZillitResult.Success)?.let { r -> applySettings(r.data) }
        }
    }

    internal fun notice(text: String) = sendEffect(InvoicesEffect.Notice(text))

    internal fun run(block: suspend () -> Unit) = launch { block() }

    internal fun now(): Long = nowMillis()

    internal suspend fun pickOne(): PickedInvoiceFile? = files.pick().firstOrNull()

    internal suspend fun upload(file: PickedInvoiceFile) = files.upload(file)

    /** Every file the reader picks at once; empty when they cancel. */
    internal suspend fun pickMany(): List<PickedInvoiceFile> = files.pick()

    /** The signed fetch of a stored file — the detail dialog's and the review overlay's. */
    internal suspend fun fetchAttachment(attachment: InvoiceAttachment) = files.fetch(attachment)

    /** Saves a fetched file to Downloads and opens it — a credit note's attachments. */
    internal suspend fun saveAndOpen(name: String, bytes: ByteArray) = files.saveAndOpen(name, bytes)

    internal val repo: InvoicesRepository get() = repository

    internal fun matchVendor(name: String): String? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        val vendors = state.value.vendors.values
        val exact = vendors.firstOrNull { it.name.trim().lowercase() == needle }
        return (exact ?: vendors.firstOrNull { it.name.lowercase().contains(needle) })?.id
    }

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
            companyId = form.companyId.ifBlank { null },
            paid = form.paid,
            episode = form.episode.trim().ifBlank { null },
            departmentId = form.departmentId.ifBlank { null },
            poNumber = form.poNumber.trim().ifBlank { null },
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
