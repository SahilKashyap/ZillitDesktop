package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository

/**
 * The accountant's side of an order: opening one, processing it, and the four
 * dialogs that act on a selection.
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

            is PoEvent.ProcessOrder -> process(event.id)
            is PoEvent.EditEntry -> vm.update { copy(entry = event.entry) }
            PoEvent.AddEntryLine -> withEntry { entry -> entry.copy(lines = entry.lines + blankLine()) }
            is PoEvent.RemoveEntryLine -> withEntry { entry ->
                entry.copy(lines = entry.lines.filterIndexed { index, _ -> index != event.index })
            }

            PoEvent.SaveEntry -> saveEntry(post = false)
            PoEvent.PostEntry -> saveEntry(post = true)
            // "Back to Queue" means the Queue, not an empty PO Entry tab — which
            // is where it landed on the first live run.
            PoEvent.CloseEntry -> {
                vm.update { copy(entry = null, detail = null, destination = PoDestination.Queue) }
                vm.load(PoDestination.Queue)
            }

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
            vm.fail("This order cannot be sent to its vendor from here.")
            return
        }
        vm.update { copy(busy = true, entry = entry?.copy(sending = true)) }
        vm.launchWork {
            when (val answer = repository.sendVendorEmail(id)) {
                is ZillitResult.Success -> {
                    val receipt = answer.data
                    vm.update {
                        copy(
                            busy = false,
                            entry = entry?.copy(sending = false),
                            notice = if (receipt.to.isBlank()) {
                                "Sent to the vendor"
                            } else {
                                "Sent to ${receipt.to}"
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

    // -- the PO Entry page -----------------------------------------------------

    /**
     * Opens the processing page on an order.
     *
     * The ledger lines start as a copy of the order's own, minus any persisted
     * tax row — that row is regenerated from the tax the accountant types, and
     * carrying it in as an editable line would let it be counted twice.
     */
    private fun process(id: String) {
        val order = vm.ui.orderById(id)
        if (order != null && !PoAccess.canProcess(order, vm.ui.viewer)) {
            vm.fail("This order is not yours to process.")
            return
        }
        vm.update { copy(busy = true) }
        vm.launchWork {
            when (val answer = repository.order(id)) {
                is ZillitResult.Success -> {
                    val fresh = answer.data.withVendorName(vm.ui)
                    vm.update {
                        copy(
                            busy = false,
                            destination = PoDestination.Entry,
                            // The dialog opened this page; leaving it open would
                            // put a second, stale copy of the order behind it.
                            prompt = null,
                            detail = fresh,
                            entry = PoEntryState(
                                orderId = id,
                                lines = fresh.lines.filterNot { it.isTax },
                                effectiveDate = fresh.effectiveDate,
                                nominalCode = fresh.nominalCode.orEmpty(),
                            ),
                        )
                    }
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    private inline fun withEntry(crossinline reducer: (PoEntryState) -> PoEntryState) {
        val current = vm.ui.entry ?: return
        vm.update { copy(entry = reducer(current)) }
    }

    /**
     * Saves the coded lines, and posts them when asked.
     *
     * Two refusals, both the web's and both worth stating rather than letting
     * the server find: the coded lines must come to the order's own gross, and
     * posting needs an effective date because the project enforces period
     * close. Neither is a server error waiting to happen — they are questions
     * only the person coding can answer.
     */
    private fun saveEntry(post: Boolean) {
        val entry = vm.ui.entry ?: return
        val order = vm.ui.detail?.takeIf { it.id == entry.orderId } ?: return
        if (!entry.balances(order)) {
            vm.fail(
                if (post) {
                    "Coded lines must match the PO total before you can post"
                } else {
                    "Coded lines must match the PO total before you can save"
                },
            )
            return
        }
        if (post && entry.effectiveDate == null) {
            vm.fail("Enter an effective date before posting to the ledger")
            return
        }
        vm.update { copy(entry = entry.copy(saving = !post, posting = post)) }
        vm.launchWork {
            val request = order.toForm(PoFormMode.EditOrder)
                .copy(
                    lines = entry.lines,
                    effectiveDate = entry.effectiveDate,
                    nominalCode = entry.nominalCode,
                )
                .toRequest(status = null, layout = vm.ui.formLayout)
            when (val saved = repository.update(order.id, request)) {
                is ZillitResult.Success -> if (post) {
                    postToLedger(order.id)
                } else {
                    vm.update { copy(entry = entry.copy(saving = false), notice = "Saved") }
                    vm.load(vm.ui.destination)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(entry = entry.copy(saving = false, posting = false)) }
                    vm.fail(saved.error.localised())
                }
            }
        }
    }

    private suspend fun postToLedger(id: String) {
        when (val posted = repository.post(id, null)) {
            is ZillitResult.Success -> {
                // `detail` goes with it: the dialog that started this still held
                // the pre-post order and read "Acct Entered" over a posted one.
                vm.update {
                    copy(entry = null, detail = null, notice = "Order posted", destination = PoDestination.Queue)
                }
                vm.load(PoDestination.Queue)
            }

            is ZillitResult.Failure -> {
                vm.update { copy(entry = entry?.copy(posting = false)) }
                vm.fail(posted.error.localised())
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
            vm.fail("None of the selected orders can be reassigned.")
            return
        }
        vm.update {
            copy(
                reassign = PoReassignState(
                    orderId = eligible.first().id,
                    ids = eligible.map { it.id },
                    label = if (eligible.size == 1) {
                        eligible.first().number.ifBlank { "this order" }
                    } else {
                        "${eligible.size} POs"
                    },
                ),
            )
        }
    }

    private fun reassign() {
        val dialog = vm.ui.reassign ?: return
        val assignee = dialog.userId
        if (assignee.isNullOrBlank()) {
            vm.fail("Choose who the order goes to.")
            return
        }
        val reason = dialog.resolvedReason
        if (reason.isBlank()) {
            vm.fail("A reason is required.")
            return
        }
        vm.update { copy(reassign = dialog.copy(saving = true)) }
        vm.launchWork {
            // One call per order: there is no bulk reassign route, and the
            // reason belongs on each record. The first refusal stops the run
            // and says how far it got, rather than reporting a clean success
            // over a half-done batch.
            var done = 0
            for (id in dialog.ids) {
                when (val answer = repository.reassign(id, assignee, reason)) {
                    is ZillitResult.Success -> done++
                    is ZillitResult.Failure -> {
                        vm.update { copy(reassign = null) }
                        vm.fail(
                            if (done == 0) {
                                answer.error.localised()
                            } else {
                                "$done of ${dialog.ids.size} reassigned, then: ${answer.error.localised()}"
                            },
                        )
                        vm.load(vm.ui.destination)
                        return@launchWork
                    }
                }
            }
            vm.update {
                copy(
                    reassign = null,
                    selection = emptySet(),
                    notice = if (done == 1) "Order reassigned" else "$done orders reassigned",
                )
            }
            vm.load(vm.ui.destination)
        }
    }

    // -- closing ---------------------------------------------------------------

    private fun askClose(id: String) {
        val order = vm.ui.orderById(id) ?: return
        vm.update { copy(closePo = PoCloseState(orderId = id, number = order.number.ifBlank { "this order" })) }
    }

    private fun closeOne() {
        val dialog = vm.ui.closePo ?: return
        vm.update { copy(closePo = dialog.copy(saving = true)) }
        vm.launchWork {
            when (val answer = repository.close(dialog.orderId, dialog.reason.takeIf { it.isNotBlank() })) {
                is ZillitResult.Success -> {
                    vm.update { copy(closePo = null, detail = null, notice = "Order closed") }
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
            vm.fail("Tick the confirmation before closing off the period.")
            return
        }
        vm.update { copy(closeOff = dialog.copy(saving = true)) }
        vm.launchWork {
            when (val answer = repository.closeAll(dialog.ids, dialog.date)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            closeOff = null,
                            selection = emptySet(),
                            notice = "${dialog.ids.size} order(s) closed",
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
            vm.fail("Pick an effective date.")
            return
        }
        vm.update { copy(bulkDate = dialog.copy(saving = true)) }
        vm.launchWork {
            when (val answer = repository.bulkSetEffectiveDate(dialog.ids, date)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(bulkDate = null, selection = emptySet(), notice = "Effective date set")
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
 * Whether the coded lines come to the order's own gross.
 *
 * Judged to the penny, because both sides are sums of converted decimals and
 * exact equality reports a balanced set of lines as broken.
 */
internal fun PoEntryState.balances(order: PurchaseOrder): Boolean =
    kotlin.math.abs(ledgerTotal - order.gross) < PENNY

private const val PENNY = 0.01

/** Fills in the vendor's name from the picker, as the lists do. */
internal fun PurchaseOrder.withVendorName(state: PoUiState): PurchaseOrder =
    if (vendorName.isNotBlank()) this else copy(vendorName = state.vendorName(this))

/** The ledger lines a fresh order starts with. */
internal fun PurchaseOrder.ledgerLines(): List<PoLine> = lines.filterNot { it.isTax }
