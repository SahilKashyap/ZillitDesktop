package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.RunCreated
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Paying and handing on — the Payment Runs and assignment mutations, split
 * out of the ViewModel so it stays a router.
 *
 * The server owns every transition. Where the web moves rows itself rather
 * than refetching — marking paid, building runs (`PaymentsPage.jsx:1078-1352`)
 * — so does this; the socket's refetch settles anything else.
 */
internal class InvoicePayments(private val vm: InvoicesViewModel) {

    private val wires = InvoiceWireAttachments(vm)

    /** Payment Runs' and Creditors' own events; false for everything else. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is PaymentsEvent.ToggleOpenItem -> vm.update {
                copy(pay = pay.copy(openItemsSelected = pay.openItemsSelected.toggled(event.id)))
            }
            is PaymentsEvent.ToggleWire -> vm.update {
                copy(pay = pay.copy(wiresSelected = pay.wiresSelected.toggled(event.id)))
            }
            is PaymentsEvent.ToggleCheque -> vm.update {
                copy(pay = pay.copy(chequesSelected = pay.chequesSelected.toggled(event.id)))
            }
            PaymentsEvent.ClearWires -> vm.update { copy(pay = pay.copy(wiresSelected = emptySet())) }
            PaymentsEvent.MarkWiresPaid -> {
                val state = vm.state.value
                markPaid(state.wireInvoices.map { it.id }.filter { it in state.pay.wiresSelected })
            }
            is PaymentsEvent.ClickRow -> clickRow(event.invoice)
            is PaymentsEvent.MarkPaidFromDetail -> markPaidOne(event.invoice)
            is PaymentsEvent.SelectCreditorFilter -> vm.update {
                copy(creditors = creditors.copy(filter = event.filter))
            }
            is PaymentsEvent.SelectCreditorSort -> vm.update { copy(creditors = creditors.copy(sort = event.sort)) }
            is PaymentsEvent.SelectCreditorAgeing -> vm.update {
                copy(creditors = creditors.copy(ageing = event.ageing))
            }
            else -> return wires.onEvent(event)
        }
        return true
    }

    /**
     * The Payments page needs its batches as well as its open items.
     *
     * Only the first read shows "Loading runs…"; a refetch (socket, a
     * decision, a new run) swaps the list silently, and a failed read empties
     * it, as the web's `fetchActiveRuns` does.
     */
    fun loadPaymentRuns() {
        val first = vm.state.value.paymentRuns.isEmpty()
        if (first) vm.update { copy(pay = pay.copy(runsLoading = true)) }
        vm.run {
            val runs = vm.repo.paymentRuns().getOrNull().orEmpty()
            vm.update { copy(paymentRuns = runs, pay = pay.copy(runsLoading = false)) }
        }
    }

    /**
     * "Recently Marked as Paid": the paid invoices, wires and faster payments
     * only — `list({status: "paid", perPage: 100})` (`PaymentsPage.jsx:1195-1203`).
     */
    fun loadRecentlyPaid() {
        vm.run {
            vm.repo.list(InvoiceQuery(statuses = listOf(InvoiceStatus.Paid), perPage = RECENTLY_PAID_PAGE))
                .getOrNull()
                ?.let { rows ->
                    val paid = rows.filter { it.payCode in PaymentRuns.WIRE_CODES }
                    vm.update { copy(pay = pay.copy(recentlyPaid = paid)) }
                }
        }
    }

    /**
     * The run authorisation bar's names — `full_name (formatLabel(designation))`,
     * or the bare id for somebody the directory does not know (`:1603-1612`).
     */
    fun authLabels(chain: List<RunAuthLevel>): Map<String, String> {
        val people = vm.people().associateBy { it.id }
        return chain.flatMap { it.userIds }.distinct().associateWith { id ->
            val person = people[id]
            val name = person?.name?.takeIf { it.isNotBlank() } ?: id
            val designation = person?.role?.takeIf { it.isNotBlank() }?.localised()
            if (designation.isNullOrBlank()) name else str(S.desktop_inv_name_with_designation, name, designation)
        }
    }

    /** Every open item ticked, or none — kept for the keyboard; the page has no Select All. */
    fun toggleSelectAll() {
        val ids = vm.state.value.invoices.map { it.id }.toSet()
        vm.update {
            val all = ids.isNotEmpty() && pay.openItemsSelected.containsAll(ids)
            copy(pay = pay.copy(openItemsSelected = if (all) emptySet() else ids))
        }
    }

    /**
     * A Wires or Cheques row: while anything on that tab is ticked a click
     * ticks it too; otherwise it opens the detail (`PaymentsPage.jsx:1845, 2011`).
     */
    private fun clickRow(invoice: Invoice) {
        val state = vm.state.value
        when {
            state.paymentTab == PaymentTab.Wires && state.pay.wiresSelected.isNotEmpty() ->
                onEvent(PaymentsEvent.ToggleWire(invoice.id))
            state.paymentTab == PaymentTab.Cheques && state.pay.chequesSelected.isNotEmpty() ->
                onEvent(PaymentsEvent.ToggleCheque(invoice.id))
            else -> vm.openInvoice(invoice)
        }
    }

    /**
     * Processes the ticked open items — the web's `handleProcessGroupAction`.
     *
     * [code] is a Process-sheet card's method; null is the header button,
     * which acts only when every tick shares one method and otherwise opens
     * the sheet. BACs becomes runs; wire and faster are marked paid; a cheque
     * and any other method have no action yet ("Cheque printing isn't
     * available yet", "Process").
     */
    fun processSelected(code: String?) {
        val state = vm.state.value
        // The buttons are disabled without run access; so is the handler.
        if (!state.viewer.canOperateRuns) return
        val rows = state.selectedPaymentRows
        if (rows.isEmpty()) return
        val chosen = code ?: state.selectedPayCode
        if (chosen == null) {
            vm.update { copy(runDraft = ProcessRequest(rows)) }
            return
        }
        when (chosen) {
            PayMethod.Bacs.wire -> createBacsRuns()
            in PaymentRuns.WIRE_CODES -> markPaid(rows.filter { it.payCode == chosen }.map { it.id }, chosen)
            else -> Unit
        }
    }

    /**
     * One BACs run per vendor and currency, numbered on from the runs already
     * there — then the Active Runs tab, on the last run made (`:1294-1352`).
     *
     * The runs that land stay landed: a failure half way through stops the
     * rest and is reported, rather than being retried into duplicates.
     */
    fun createBacsRuns() {
        val state = vm.state.value
        if (!state.viewer.canOperateRuns || state.pay.creatingRun) return
        val groups = state.bacsGroups
        if (groups.isEmpty()) return
        vm.update { copy(runDraft = runDraft?.copy(busy = PayMethod.Bacs.wire), pay = pay.copy(creatingRun = true)) }
        vm.run {
            val existing = vm.state.value.paymentRuns
            var failure: ZillitError? = null
            var last: RunCreated? = null
            val done = mutableSetOf<String>()
            for ((index, group) in groups.withIndex()) {
                val result = vm.repo.createPaymentRunWithMessage(
                    name = group.runName,
                    number = PaymentRuns.nextNumber(existing, index),
                    payMethod = PayMethod.Bacs,
                    invoiceIds = group.ids,
                )
                if (result is ZillitResult.Failure) {
                    failure = result.error
                    break
                }
                last = (result as? ZillitResult.Success)?.data
                done += group.ids
            }
            val error = failure
            if (error != null) {
                vm.update {
                    copy(
                        runDraft = runDraft?.copy(busy = null),
                        pay = pay.copy(creatingRun = false),
                        error = error.localised(),
                    )
                }
                loadPaymentRuns()
                vm.refresh()
            } else {
                landOnNewRun(done, last, groups.size)
            }
        }
    }

    /** After the runs are made: their invoices leave Open Items, and the last run opens on Active Runs. */
    private suspend fun landOnNewRun(done: Set<String>, last: RunCreated?, made: Int) {
        vm.update { withoutOpenItems(done).let { it.copy(runDraft = null, pay = it.pay.copy(creatingRun = false)) } }
        vm.notice(last?.message?.localisedMessage() ?: str(S.desktop_inv_payment_run_created_n, made))
        val runs = vm.repo.paymentRuns().getOrNull() ?: vm.state.value.paymentRuns
        vm.update { copy(paymentRuns = runs, paymentTab = PaymentTab.Runs) }
        val id = last?.id?.takeIf { it.isNotBlank() } ?: return
        openRun(runs.firstOrNull { it.id == id } ?: PaymentRun(id = id))
    }

    /**
     * One wire or faster payment settled at the bank — the Wires row's and the
     * detail's Mark Paid (`handleMarkPaid`, `:1078-1115`). It moves to
     * "Recently Marked as Paid" and the detail closes if it was this one.
     */
    fun markPaidOne(invoice: Invoice) {
        val state = vm.state.value
        if (!state.viewer.canOperateRuns || invoice.id in state.pay.markingPaid) return
        if (invoice.payCode !in PaymentRuns.WIRE_CODES || invoice.status == InvoiceStatus.Paid) return
        vm.update { copy(pay = pay.copy(markingPaid = pay.markingPaid + invoice.id)) }
        vm.run {
            val result = vm.repo.markPaidWithMessage(listOf(invoice.id))
            vm.update {
                val done = copy(pay = pay.copy(markingPaid = pay.markingPaid - invoice.id))
                when (result) {
                    is ZillitResult.Failure -> done.copy(error = result.error.localised())
                    is ZillitResult.Success -> done.markedPaid(setOf(invoice.id), vm.now()).let {
                        it.copy(
                            pay = it.pay.copy(wiresSelected = it.pay.wiresSelected - invoice.id),
                            detail = it.detail?.takeIf { open -> open.invoice.id != invoice.id },
                        )
                    }
                }
            }
            if (result is ZillitResult.Success) {
                vm.notice(result.data?.localisedMessage() ?: str(S.desktop_inv_marked_paid_n, 1))
            }
        }
    }

    /**
     * Several at once in one call — the Open Items header, a Process-sheet
     * card, or the Wires bulk bar, which marks every ticked wire whether wire
     * or faster (`handleBulkMarkPaid`, `:1121-1147`). [code] names the sheet
     * card whose button spins.
     */
    fun markPaid(ids: List<String>, code: String? = null) {
        val state = vm.state.value
        if (ids.isEmpty() || !state.viewer.canOperateRuns || state.pay.bulkMarking) return
        vm.update { copy(pay = pay.copy(bulkMarking = true), runDraft = runDraft?.copy(busy = code)) }
        vm.run {
            val result = vm.repo.markPaidWithMessage(ids)
            vm.update {
                val done = copy(pay = pay.copy(bulkMarking = false))
                when (result) {
                    is ZillitResult.Failure -> done.copy(
                        runDraft = runDraft?.copy(busy = null),
                        error = result.error.localised(),
                    )
                    is ZillitResult.Success -> done.markedPaid(ids.toSet(), vm.now()).let {
                        it.copy(runDraft = null, pay = it.pay.copy(wiresSelected = emptySet()))
                    }
                }
            }
            if (result is ZillitResult.Success) {
                vm.notice(result.data?.localisedMessage() ?: str(S.desktop_inv_marked_paid_n, ids.size))
            }
        }
    }

    // -- one run ------------------------------------------------------------

    /** Opens a run's detail — `GET /active-runs/:id`, with the row shown until it answers. */
    fun openRun(run: PaymentRun) {
        vm.update { copy(runDetail = RunDetailView(run = run)) }
        reloadRun(run.id)
    }

    private fun reloadRun(id: String) {
        vm.run {
            val result = vm.repo.paymentRun(id)
            vm.update {
                val open = runDetail?.takeIf { it.run.id == id } ?: return@update this
                when (result) {
                    is ZillitResult.Success -> copy(runDetail = open.copy(detail = result.data, loading = false))
                    is ZillitResult.Failure -> copy(
                        runDetail = open.copy(loading = false),
                        error = result.error.localised(),
                    )
                }
            }
        }
    }

    /** The freshest copy of [run]: the open detail's when it is the same run. */
    private fun latest(run: PaymentRun): PaymentRun =
        vm.state.value.runDetail?.shown?.takeIf { it.id == run.id } ?: run

    /**
     * Signs the next tier — `{tier_number, total_tiers}`, as the web sends.
     *
     * Refused here, not only hidden: the tier comes from the run's own chain,
     * so a reader who is not on the next tier, or who has signed an earlier
     * one (separation of duties), or a run no longer pending, gets nothing.
     */
    fun approveRun(run: PaymentRun) {
        val current = latest(run)
        val decision = vm.state.value.runApproval(current)
        val tier = decision.nextTier
        if (!decision.canApprove || tier == null || vm.state.value.busy) return
        vm.update { copy(busy = true, runDetail = runDetail?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.approvePaymentRunWithMessage(current.id, tier, decision.totalTiers)
            vm.update {
                copy(
                    busy = false,
                    runDetail = runDetail?.copy(busy = false),
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                // The run's unread is read by the decision, never by opening it (ZL-21219).
                vm.readDepartmentRow(current.id)
                vm.readPaymentRunRow(current.id)
                vm.notice(result.data?.localisedMessage() ?: str(S.ah_run_approved_toast))
                // The web re-reads the run, so the next tier's signer sees it move on.
                reloadRun(current.id)
                loadPaymentRuns()
            }
        }
    }

    /** Rejecting sits beside Approve, so it answers to the same chain. */
    fun startRejectRun(run: PaymentRun) {
        if (!vm.state.value.runApproval(latest(run)).canApprove) return
        vm.update { copy(rejectRun = RunRejection(latest(run))) }
    }

    fun confirmRejectRun() {
        val request = vm.state.value.rejectRun ?: return
        if (!request.isReady || request.busy) return
        vm.update { copy(rejectRun = rejectRun?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.rejectPaymentRunWithMessage(request.run.id, request.reason)
            vm.update {
                val landed = result is ZillitResult.Success
                copy(
                    rejectRun = if (landed) null else rejectRun?.copy(busy = false),
                    // The web closes the run once it is turned down.
                    runDetail = if (landed) runDetail?.takeIf { it.run.id != request.run.id } else runDetail,
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                vm.readDepartmentRow(request.run.id)
                vm.readPaymentRunRow(request.run.id)
                vm.notice(result.data?.localisedMessage() ?: str(S.ah_run_rejected_toast))
                loadPaymentRuns()
                vm.refresh()
            }
        }
    }

    /** "Cancel run" asks first; only someone who may operate runs is offered it. */
    fun requestCancelRun() {
        if (!vm.state.value.viewer.canOperateRuns) return
        vm.update { copy(runDetail = runDetail?.copy(confirmCancel = true)) }
    }

    /** Cancelling deletes the run; its invoices go back to open items. */
    fun confirmCancelRun() {
        val open = vm.state.value.runDetail ?: return
        if (!vm.state.value.viewer.canOperateRuns || open.busy) return
        vm.update { copy(runDetail = runDetail?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.deletePaymentRunWithMessage(open.run.id)
            vm.update {
                copy(
                    runDetail = if (result is ZillitResult.Success) {
                        null
                    } else {
                        runDetail?.copy(busy = false, confirmCancel = false)
                    },
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                vm.notice(result.data?.localisedMessage() ?: str(S.desktop_run_deleted))
                loadPaymentRuns()
                vm.refresh()
            }
        }
    }

    /** The rows behind the tick boxes, in the order they are on screen. */
    fun selectedRows(): List<Invoice> {
        val picked = vm.state.value.selected
        return vm.state.value.entryRows.filter { it.id in picked }
    }

    /** The assign sheet opens over what is ticked; nothing ticked, nothing to hand on. */
    fun startAssign() {
        val ids = vm.state.value.selected.toList()
        if (ids.isEmpty()) {
            vm.update { copy(error = str(S.desktop_inv_tick_to_assign_first)) }
            return
        }
        vm.update { copy(assignFor = AssignRequest(invoiceIds = ids), assignees = vm.team()) }
    }

    /**
     * Assigns one at a time, as the web does.
     *
     * A part-done assignment is still an assignment: the rows that landed keep
     * their new owner, and only the failure is reported.
     */
    fun confirmAssign() {
        val request = vm.state.value.assignFor ?: return
        if (!request.isReady || request.busy) return
        vm.update { copy(assignFor = assignFor?.copy(busy = true)) }
        vm.run {
            val failure = request.invoiceIds.firstNotNullOfOrNull { id ->
                (vm.repo.assign(id, request.userId, request.reasonText) as? ZillitResult.Failure)?.error
            }
            vm.update {
                copy(
                    assignFor = if (failure == null) null else assignFor?.copy(busy = false),
                    selected = if (failure == null) emptySet() else selected,
                    error = failure?.localised(),
                )
            }
            if (failure == null) {
                vm.notice(str(S.desktop_inv_assigned_n, request.invoiceIds.size))
                vm.refresh()
                // Assigned from the coding screen: its new owner shows there too.
                vm.reloadLedger()
            }
        }
    }

    companion object {
        /** Open Items reads `perPage: 500` (`PaymentsPage.jsx:1194`). */
        const val OPEN_ITEMS_PAGE = 500

        /** "Recently Marked as Paid" reads `perPage: 100`. */
        private const val RECENTLY_PAID_PAGE = 100
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/**
 * [ids] leave Open Items; the rest are ticked again, as the web re-ticks the
 * whole list whenever its rows change (`PaymentsPage.jsx:1394-1396`).
 */
private fun InvoicesUiState.withoutOpenItems(ids: Set<String>): InvoicesUiState {
    val left = invoices.filterNot { it.id in ids }
    return copy(invoices = left, pay = pay.copy(openItemsSelected = left.map { it.id }.toSet()))
}

/**
 * [ids] were marked paid: out of Open Items, and — wires and faster payments
 * — onto the top of "Recently Marked as Paid", stamped now, unless already there.
 */
private fun InvoicesUiState.markedPaid(ids: Set<String>, nowMs: Long): InvoicesUiState {
    val moved = invoices
        .filter { it.id in ids && it.payCode in PaymentRuns.WIRE_CODES }
        .filter { row -> pay.recentlyPaid.none { it.id == row.id } }
        .map { it.copy(status = InvoiceStatus.Paid, paidAtMs = nowMs) }
    return withoutOpenItems(ids).let { it.copy(pay = it.pay.copy(recentlyPaid = moved + it.pay.recentlyPaid)) }
}
