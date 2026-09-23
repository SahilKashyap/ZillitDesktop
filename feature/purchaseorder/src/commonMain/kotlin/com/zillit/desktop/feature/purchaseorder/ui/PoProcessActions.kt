package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.splitCadence
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository

/**
 * The PO Entry page — the web's `POEntry`, where an accountant codes an order
 * and posts it to the ledger.
 *
 * Its own collaborator because its rules are the ones most easily lost in
 * pieces, and every one of them is checked **here** rather than only on the
 * button that fires it:
 *
 * - opening re-reads the order and splits its persisted tax row off the coded
 *   lines, and every write appends that row again ([PoEntryLedger]);
 * - Save is the web's own PATCH, not the create/update body;
 * - Post carries the lines and the header (`handlePostPO`), after the web's
 *   four refusals — balance, a nominal on every line, an effective date, and a
 *   date outside the locked cost-report period;
 * - Send to Vendor saves first, so the vendor gets what is on screen.
 */
internal class PoProcessActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
) {
    fun onEvent(event: PoEvent) {
        when (event) {
            is PoEvent.ProcessOrder -> process(event.id)
            is PoEvent.EditEntry -> vm.update { copy(entry = event.entry) }
            PoEvent.AddEntryLine -> withEntry { entry -> entry.copy(lines = entry.lines + blankLine()) }
            is PoEvent.RemoveEntryLine -> withEntry { entry ->
                entry.copy(lines = entry.lines.filterIndexed { index, _ -> index != event.index })
            }

            // The two splits are exclusive, as the web's are: a rental line with
            // a divisible window splits by period only.
            is PoEvent.SplitEntryLine -> withEntry { entry ->
                val line = entry.lines.getOrNull(event.index)
                if (line == null || line.isDivisibleRental) {
                    entry
                } else {
                    entry.copy(lines = entry.lines.splitEvenly(event.index))
                }
            }

            is PoEvent.SplitEntryLineByPeriod -> splitByPeriod(event.index)

            PoEvent.SaveEntry -> save(then = null)
            PoEvent.PostEntry -> post()
            // "Back to Queue" means the Queue, not an empty PO Entry tab — which
            // is where it landed on the first live run.
            PoEvent.CloseEntry -> {
                vm.update { copy(entry = null, detail = null, destination = PoDestination.Queue) }
                vm.load(PoDestination.Queue)
            }

            else -> Unit
        }
    }

    /** Opens the processing page on an order, read fresh. */
    private fun process(id: String) {
        val order = vm.ui.orderById(id)
        if (order != null && !PoAccess.canProcess(order, vm.ui.viewer)) {
            vm.fail(str(S.desktop_po_not_yours_to_process))
            return
        }
        vm.update { copy(busy = true) }
        vm.launchWork {
            when (val answer = repository.order(id)) {
                is ZillitResult.Success -> {
                    val fresh = answer.data.withVendorName(vm.ui)
                    // Re-checked on the record: the row that offered Process may
                    // be minutes old.
                    if (!PoAccess.canProcess(fresh, vm.ui.viewer)) {
                        vm.update { copy(busy = false) }
                        vm.fail(str(S.desktop_po_not_yours_to_process))
                        return@launchWork
                    }
                    vm.update {
                        copy(
                            busy = false,
                            destination = PoDestination.Entry,
                            // The dialog opened this page; leaving it open would
                            // put a second, stale copy of the order behind it.
                            prompt = null,
                            detail = fresh,
                            entry = fresh.toEntry(),
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

    private fun splitByPeriod(index: Int) {
        val entry = vm.ui.entry ?: return
        if (entry.lines.getOrNull(index)?.isDivisibleRental != true) {
            vm.fail(str(S.desktop_po_period_split_needs_rent))
            return
        }
        val cadence = vm.ui.projectSettings.splitCadence
        val split = entry.lines.splitByPeriod(index, cadence.days)
        if (split == null) {
            vm.fail(str(S.desktop_po_rental_window_too_short, cadence.label.lowercase()))
            return
        }
        vm.update { copy(entry = entry.copy(lines = split)) }
    }

    /** The order the page is on, when it is the one [entry] codes. */
    private fun openOrder(entry: PoEntryState): PurchaseOrder? = vm.ui.detail?.takeIf { it.id == entry.orderId }

    /**
     * Whether this viewer may write to the order at all from here: it must
     * still be theirs to process, and its saved date must be outside the
     * locked period — the web hides Save and Post on a locked order.
     */
    private fun refusesWrite(order: PurchaseOrder): Boolean {
        val message = when {
            !PoAccess.canProcess(order, vm.ui.viewer) -> str(S.desktop_po_not_yours_to_process)
            vm.ui.isLocked(order) -> str(S.desktop_po_period_locked_read_only)
            else -> null
        } ?: return false
        vm.fail(message)
        return true
    }

    /**
     * Saves the page — the web's `saveNow`. Refused, as the web refuses it, when
     * the coded lines do not come to the order's gross.
     *
     * [then] runs with the order re-read after the save: Send to Vendor needs
     * the persisted lines, not the editor's.
     */
    fun save(then: (suspend (PurchaseOrder) -> Unit)?) {
        val entry = vm.ui.entry ?: return
        val order = openOrder(entry) ?: return
        if (refusesWrite(order)) return
        val ledger = entry.ledger(vm.ui)
        if (!ledger.balances(order)) {
            vm.fail(str(S.desktop_po_coded_lines_must_match_save))
            return
        }
        vm.update { copy(entry = entry.copy(saving = true)) }
        vm.launchWork {
            val update = ledger.update(entry.header(vm.ui.defaultCurrency()))
            when (val saved = repository.saveEntry(order.id, update)) {
                is ZillitResult.Success -> {
                    val fresh = repository.order(order.id).getOrNull()?.withVendorName(vm.ui)
                    vm.update {
                        copy(
                            detail = fresh ?: detail,
                            // Rehydrated from the record, as the web's getOne
                            // after a save: what was stored is what shows.
                            entry = (fresh?.toEntry()?.copy(previewOpen = entry.previewOpen) ?: entry)
                                .copy(saving = false),
                            notice = if (then == null) str(S.saved) else notice,
                        )
                    }
                    vm.load(vm.ui.destination)
                    if (then != null && fresh != null) then(fresh)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(entry = entry.copy(saving = false)) }
                    vm.fail(saved.error.localised())
                }
            }
        }
    }

    /**
     * Posts the order — the web's Post button, refusal for refusal, then
     * `handlePostPO`'s body. The page's lines go with the post; there is no
     * separate save first, because the web does not make one.
     */
    @Suppress("ReturnCount") // One refusal per web gate, each with its own words.
    private fun post() {
        val entry = vm.ui.entry ?: return
        val order = openOrder(entry) ?: return
        if (refusesWrite(order)) return
        if (!canPostStatus(order.status)) {
            vm.fail(str(S.desktop_po_cannot_post_in_status))
            return
        }
        val ledger = entry.ledger(vm.ui)
        if (ledger.net <= 0 || !ledger.balances(order)) {
            vm.fail(str(S.desktop_po_coded_lines_must_match_post))
            return
        }
        val missing = ledger.missingCodes
        if (missing.isNotEmpty()) {
            vm.update { copy(entry = entry.copy(missingCodes = missing)) }
            // The web's "Line 3" / "Lines 3, 7 need a nominal code…".
            vm.fail(
                if (missing.size == 1) {
                    str(S.desktop_po_line_needs_nominal, missing.first().toString())
                } else {
                    str(S.desktop_po_lines_need_nominal, missing.joinToString(", "))
                },
            )
            return
        }
        val date = entry.effectiveDate
        if (date == null) {
            vm.fail(str(S.desktop_po_effective_date_before_posting))
            return
        }
        if (vm.ui.periodLock.locks(date)) {
            vm.fail(str(S.desktop_po_effective_date_locked, vm.ui.periodLock.lockedThrough))
            return
        }
        vm.update { copy(entry = entry.copy(posting = true, missingCodes = emptyList())) }
        vm.launchWork {
            val request = ledger.post(order, entry.header(vm.ui.defaultCurrency()))
            when (val posted = repository.post(order.id, request)) {
                is ZillitResult.Success -> {
                    // The web lands on Posted; an assistant cannot open it, so
                    // they go back to the Queue they came from.
                    val next = PoDestination.Posted.takeIf { it.visibleTo(vm.ui.viewer) } ?: PoDestination.Queue
                    // `detail` goes with it: the dialog that started this still
                    // held the pre-post order and read "Acct Entered".
                    vm.update {
                        copy(entry = null, detail = null, notice = str(S.desktop_order_posted), destination = next)
                    }
                    vm.load(next)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(entry = entry.copy(posting = false)) }
                    vm.fail(posted.error.localised())
                }
            }
        }
    }

    companion object {
        /**
         * Whether an order in [status] can be posted from this page — the web's
         * `!isPending && !['posted','closed',…relieved].includes(status)`.
         * Relieved is a label of a posted order, so posted covers it.
         */
        fun canPostStatus(status: PoStatus): Boolean = status !in NOT_POSTABLE

        private val NOT_POSTABLE = setOf(
            PoStatus.AwaitingApproval,
            PoStatus.Posted,
            PoStatus.Closed,
            PoStatus.Draft,
            PoStatus.Rejected,
            PoStatus.Cancelled,
        )
    }
}
