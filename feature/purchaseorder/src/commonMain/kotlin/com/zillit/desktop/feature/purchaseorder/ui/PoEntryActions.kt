package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository

/**
 * The accountant's side of an order: opening one, emailing it, and the four
 * dialogs that act on a selection. The processing page is [PoProcessActions].
 *
 * Grouped because they share one rule that is easy to get wrong in pieces — an
 * order is always **re-read** before it is acted on. A list row can be minutes
 * old, and approving or posting the row rather than the record is how two
 * accountants both approve the same order.
 */
internal class PoEntryActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per control; a flat table reads better.
    fun onEvent(event: PoEvent) {
        when (event) {
            is PoEvent.OpenOrder -> openDetail(event.id)
            PoEvent.CloseOrder -> vm.update { copy(detail = null, history = emptyList()) }
            is PoEvent.OpenAttachment -> vm.emit(PoEffect.OpenAttachment(event.attachment))
            is PoEvent.ViewPdf -> viewPdf(event.id)
            is PoEvent.SendVendorEmail -> sendVendorEmail(event.id, event.allowResend)

            is PoEvent.AskReassign -> askReassign(listOf(event.orderId))
            is PoEvent.AskBulkReassign -> askReassign(event.ids)
            is PoEvent.EditReassign -> vm.update { copy(reassign = event.reassign) }
            PoEvent.ConfirmReassign -> reassign()
            PoEvent.DismissReassign -> vm.update { copy(reassign = null) }

            is PoEvent.AskClose -> askClose(event.orderId)
            is PoEvent.EditClose -> vm.update { copy(closePo = event.close) }
            PoEvent.ConfirmClose -> closeOne()
            PoEvent.DismissClose -> vm.update { copy(closePo = null) }

            is PoEvent.AskCloseOff -> vm.update {
                copy(closeOff = PoCloseOffState(period = event.period, ids = event.ids))
            }

            is PoEvent.EditCloseOff -> vm.update { copy(closeOff = event.closeOff) }
            PoEvent.ConfirmCloseOff -> closeOffPeriod()
            PoEvent.DismissCloseOff -> vm.update { copy(closeOff = null) }

            is PoEvent.AskBulkDate -> vm.update { copy(bulkDate = PoBulkDateState(ids = event.ids)) }
            is PoEvent.EditBulkDate -> vm.update { copy(bulkDate = event.bulk) }
            PoEvent.ConfirmBulkDate -> applyBulkDate()
            PoEvent.DismissBulkDate -> vm.update { copy(bulkDate = null) }
            else -> Unit
        }
    }

    // -- the detail dialog -----------------------------------------------------

    /**
     * Opens one order, read fresh.
     *
     * The list row stands in while the read is in flight, so the dialog opens
     * immediately rather than on a spinner; the record replaces it a moment
     * later, and the actions are gated on the record.
     */
    private fun openDetail(id: String) {
        val row = vm.ui.orderById(id)
        vm.update { copy(detail = row, detailLoading = true, history = emptyList()) }
        // Opening the order reads its badge on every tab but the approval
        // queue, where the decision is the read (ZL-20775).
        if (vm.ui.destination != PoDestination.ApprovalQueue) vm.readOrderBadge(id)
        // A row that exists only here has no record to read and no history.
        if (id.startsWith(com.zillit.desktop.feature.purchaseorder.data.LOCAL_ID_PREFIX)) {
            vm.update { copy(detailLoading = false) }
            return
        }
        vm.launchWork {
            when (val answer = repository.order(id)) {
                is ZillitResult.Success ->
                    if (vm.ui.detail?.id == id) {
                        vm.update { copy(detail = answer.data.withVendorName(this), detailLoading = false) }
                    }

                is ZillitResult.Failure -> {
                    vm.update { copy(detailLoading = false) }
                    // Only complain when there was nothing to show anyway: the
                    // row is a true copy of the list, and an error over a
                    // readable order is noise.
                    if (row == null) vm.fail(answer.error.localised())
                }
            }
        }
        vm.launchWork {
            repository.history(id).getOrNull()?.let { entries ->
                if (vm.ui.detail?.id == id) vm.update { copy(history = entries) }
            }
        }
    }

    /**
     * Renders the order's PDF and hands it to the OS.
     *
     * Two calls: the service renders and stores it, the host presigns and opens
     * it — the same path every other document on this desktop takes.
     */
    private fun viewPdf(id: String) {
        vm.update { copy(busy = true) }
        vm.launchWork {
            when (val answer = repository.pdf(id, projectName = "", companyName = "")) {
                is ZillitResult.Success -> {
                    vm.update { copy(busy = false) }
                    vm.emit(PoEffect.OpenAttachment(answer.data))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    /**
     * Emails the order to its vendor.
     *
     * The receipt is folded into whatever copy of the order is on screen so the
     * action relabels itself ("Send to Vendor" → "Resend to Vendor") without a
     * refetch, which is what the web's own `emailAt` check does.
     */
    private fun sendVendorEmail(id: String, allowResend: Boolean) {
        val order = vm.ui.orderById(id) ?: return
        if (!PoAccess.canSendVendorEmail(order, vm.ui.viewer, allowResend)) {
            vm.fail(str(S.desktop_po_cannot_send_to_vendor))
            return
        }
        // From the processing page the page is saved first (the web's "Saves
        // FIRST" decision): the vendor must receive what the accountant is
        // looking at, and a refused save — unbalanced lines — sends nothing.
        if (vm.ui.entry?.orderId == id) {
            vm.processActions.save { saved -> deliver(saved.id) }
            return
        }
        vm.launchWork { deliver(id) }
    }

    private suspend fun deliver(id: String) {
        vm.update { copy(busy = true, entry = entry?.copy(sending = true)) }
        run {
            when (val answer = repository.sendVendorEmail(id)) {
                is ZillitResult.Success -> {
                    val receipt = answer.data
                    vm.update {
                        copy(
                            busy = false,
                            entry = entry?.copy(sending = false),
                            notice = if (receipt.to.isBlank()) {
                                str(S.desktop_po_sent_to_the_vendor)
                            } else {
                                str(S.desktop_po_sent_to, receipt.to)
                            },
                            detail = detail?.takeIf { it.id == id }
                                ?.copy(emailAt = receipt.at ?: vm.nowMillis(), emailBy = receipt.by)
                                ?: detail,
                            orders = orders.map { row ->
                                if (row.id == id) {
                                    row.copy(emailAt = receipt.at ?: vm.nowMillis(), emailBy = receipt.by)
                                } else {
                                    row
                                }
                            },
                        )
                    }
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false, entry = entry?.copy(sending = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    // -- reassignment ----------------------------------------------------------

    /**
     * Opens the Reassign dialog on one order or a selection.
     *
     * Orders that cannot be reassigned are dropped from the batch rather than
     * failing it: a selection of forty rows will usually contain a pending one,
     * and refusing the lot over it is not the accountant's intent.
     */
    private fun askReassign(ids: List<String>) {
        val viewer = vm.ui.viewer
        val eligible = ids.mapNotNull { vm.ui.orderById(it) }.filter { PoAccess.canReassign(it, viewer) }
        if (eligible.isEmpty()) {
            vm.fail(str(S.desktop_po_none_can_be_reassigned))
            return
        }
        vm.update {
            copy(
                reassign = PoReassignState(
                    orderId = eligible.first().id,
                    ids = eligible.map { it.id },
                    label = if (eligible.size == 1) {
                        eligible.first().number.ifBlank { str(S.desktop_po_this_order) }
                    } else {
                        str(S.desktop_po_count_pos, eligible.size)
                    },
                ),
            )
        }
    }

    private fun reassign() {
        val dialog = vm.ui.reassign ?: return
        val assignee = dialog.userId
        if (assignee.isNullOrBlank()) {
            vm.fail(str(S.desktop_po_choose_who_it_goes_to))
            return
        }
        val reason = dialog.resolvedReason
        if (reason.isBlank()) {
            vm.fail(str(S.desktop_a_reason_is_required))
            return
        }
        // Re-checked on the rows as they stand now, not as they stood when the
        // dialog opened: the handler is reachable without the dialog.
        val ids = dialog.ids.filter { id ->
            vm.ui.orderById(id)?.let { PoAccess.canReassign(it, vm.ui.viewer) } == true
        }
        if (ids.isEmpty()) {
            vm.update { copy(reassign = null) }
            vm.fail(str(S.desktop_po_none_can_be_reassigned))
            return
        }
        vm.update { copy(reassign = dialog.copy(saving = true)) }
        vm.launchWork {
            // One order is a PATCH of the record; a selection is one
            // `PATCH /bulk` with the reason in `data` — the web's two paths.
            val answer = if (ids.size == 1) {
                repository.reassign(ids.first(), assignee, reason)
            } else {
                repository.bulkReassign(ids, assignee, reason)
            }
            if (answer is ZillitResult.Failure) {
                vm.update { copy(reassign = dialog.copy(saving = false)) }
                vm.fail(answer.error.localised())
                return@launchWork
            }
            val done = ids.size
            vm.update {
                copy(
                    reassign = null,
                    selection = emptySet(),
                    notice = if (done == 1) {
                        str(S.desktop_order_reassigned)
                    } else {
                        str(S.desktop_po_orders_reassigned, done)
                    },
                )
            }
            vm.load(vm.ui.destination)
        }
    }

    // -- closing ---------------------------------------------------------------

    private fun askClose(id: String) {
        val order = vm.ui.orderById(id) ?: return
        if (!canClose(order)) {
            vm.fail(str(S.desktop_po_cannot_close_order))
            return
        }
        val number = order.number.ifBlank { str(S.desktop_po_this_order) }
        vm.update { copy(closePo = PoCloseState(orderId = id, number = number)) }
    }

    /**
     * Whether this order may be closed — the web's Posted-tab rule: a senior,
     * an order that is open or partially relieved (a fully relieved order has
     * nothing left to release), and not in the locked cost-report period.
     */
    private fun canClose(order: PurchaseOrder): Boolean =
        vm.ui.viewer.isSeniorAccountant && order.relief.isCloseable && !vm.ui.isLocked(order)

    private fun closeOne() {
        val dialog = vm.ui.closePo ?: return
        val order = vm.ui.orderById(dialog.orderId)
        if (order != null && !canClose(order)) {
            vm.update { copy(closePo = null) }
            vm.fail(str(S.desktop_po_cannot_close_order))
            return
        }
        if (dialog.date != null && vm.ui.periodLock.locks(dialog.date)) {
            vm.fail(str(S.desktop_po_effective_date_locked, vm.ui.periodLock.lockedThrough))
            return
        }
        vm.update { copy(closePo = dialog.copy(saving = true)) }
        vm.launchWork {
            // `{ reason, effective_date }`, both always — the web's Close PO.
            when (val answer = repository.close(dialog.orderId, dialog.reason.trim(), dialog.date)) {
                is ZillitResult.Success -> {
                    vm.update { copy(closePo = null, detail = null, notice = str(S.desktop_order_closed)) }
                    vm.load(vm.ui.destination)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(closePo = dialog.copy(saving = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    /**
     * Closes every open order in one accounting period.
     *
     * The effective date is the period the write-off lands in, and the confirm
     * asks for it explicitly: closing releases the remaining commitment into
     * the estimate to complete, and which period that shows up in is the whole
     * decision.
     */
    private fun closeOffPeriod() {
        val dialog = vm.ui.closeOff ?: return
        if (!dialog.confirmed) {
            vm.fail(str(S.desktop_po_tick_confirmation_close_off))
            return
        }
        // The web's closeable set, re-derived here: not already closed, and not
        // in the locked cost-report period. A senior's act, as the button is.
        val ids = dialog.ids.filter { id ->
            val order = vm.ui.orderById(id)
            order == null || (order.status != PoStatus.Closed && !vm.ui.isLocked(order))
        }
        if (!vm.ui.viewer.isSeniorAccountant || ids.isEmpty()) {
            vm.update { copy(closeOff = null) }
            vm.fail(str(S.desktop_po_cannot_close_order))
            return
        }
        if (dialog.date != null && vm.ui.periodLock.locks(dialog.date)) {
            vm.fail(str(S.desktop_po_effective_date_locked, vm.ui.periodLock.lockedThrough))
            return
        }
        vm.update { copy(closeOff = dialog.copy(saving = true)) }
        vm.launchWork {
            when (val answer = repository.closeAll(ids, dialog.date)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            closeOff = null,
                            selection = emptySet(),
                            notice = str(S.desktop_po_orders_closed, ids.size),
                        )
                    }
                    vm.load(vm.ui.destination)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(closeOff = dialog.copy(saving = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    private fun applyBulkDate() {
        val dialog = vm.ui.bulkDate ?: return
        val date = dialog.date
        if (date == null) {
            vm.fail(str(S.desktop_po_pick_an_effective_date))
            return
        }
        // The web caps this picker at the day after the lock, and never lets a
        // locked row into the selection at all.
        if (vm.ui.periodLock.locks(date)) {
            vm.fail(str(S.desktop_po_effective_date_locked, vm.ui.periodLock.lockedThrough))
            return
        }
        val ids = dialog.ids.filterNot { id -> vm.ui.orderById(id)?.let(vm.ui::isLocked) == true }
        vm.update { copy(bulkDate = dialog.copy(saving = true)) }
        vm.launchWork {
            when (val answer = repository.bulkSetEffectiveDate(ids, date)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(bulkDate = null, selection = emptySet(), notice = str(S.desktop_po_effective_date_set))
                    }
                    vm.load(vm.ui.destination)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(bulkDate = dialog.copy(saving = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }
}

/**
 * Whether the coded lines come to the order's own gross — with the consolidated
 * tax line substituted in, as the page and both writes see it. See
 * [PoEntryLedger.balances].
 */
internal fun PoEntryState.balances(order: PurchaseOrder, state: PoUiState): Boolean =
    ledger(state).balances(order)

/** Fills in the vendor's name from the picker, as the lists do. */
internal fun PurchaseOrder.withVendorName(state: PoUiState): PurchaseOrder =
    if (vendorName.isNotBlank()) this else copy(vendorName = state.vendorName(this))

/** The ledger lines a fresh order starts with. */
internal fun PurchaseOrder.ledgerLines(): List<PoLine> = lines.filterNot { it.isTax }
