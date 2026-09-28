package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.QuickEntry

/**
 * Invoice Entry's Quick Entry — a single-code invoice straight to
 * ready-to-pay (the web's `quickPost`). Accountants only, here as on screen.
 */
internal class InvoiceQuickEntryActions(private val vm: InvoicesViewModel) {

    /** A vendor typed that does not exist yet is created on Post, once (`usePendingVendor`). */
    private val pendingVendors = PendingVendors(vm)

    /** True when [event] was Quick Entry's own. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            EntryEvent.StartQuick -> if (vm.state.value.isAccountant) {
                vm.update { copy(quickEntry = QuickEntryDraft()) }
                vm.loadEntryRefs()
            }
            is EntryEvent.EditQuick -> vm.update {
                val before = quickEntry
                copy(quickEntry = before?.let { edited(it, event.draft) } ?: event.draft)
            }
            EntryEvent.PostQuick -> post()
            EntryEvent.CancelQuick -> vm.update { copy(quickEntry = quickEntry?.takeIf { it.busy }) }
            else -> return false
        }
        return true
    }

    /**
     * An edit, with an effective date inside the closed period refused — the
     * web's field bars it with `min={effMin}` (`EntryPage.jsx:700`). A date
     * still being typed is let be.
     */
    private fun edited(before: QuickEntryDraft, after: QuickEntryDraft): QuickEntryDraft {
        val date = after.effectiveDate
        val locked = date != before.effectiveDate &&
            InvoiceFormat.parseDateInput(date) != null &&
            vm.state.value.periodLock.isLocked(date)
        return if (locked) after.copy(effectiveDate = before.effectiveDate) else after
    }

    /**
     * Creates the single-code invoice at ready-to-pay — a typed vendor first,
     * when one was — then shows it where it went: Payment Runs, as the web
     * navigates there.
     */
    private fun post() {
        val state = vm.state.value
        val draft = state.quickEntry ?: return
        val net = draft.netValue ?: return
        if (!state.isAccountant) return
        if (!draft.isReady || draft.busy) return
        vm.update { copy(quickEntry = quickEntry?.copy(busy = true)) }
        vm.run {
            val vendorId = pendingVendors.resolve(draft.vendorId, draft.pendingVendorName)
            if (vendorId == null) {
                vm.update { copy(quickEntry = quickEntry?.copy(busy = false)) }
                return@run
            }
            val result = vm.repo.quickEntryWithMessage(
                QuickEntry(
                    reference = draft.reference,
                    vendorId = vendorId,
                    nominal = draft.nominal,
                    costCentre = draft.costCentre,
                    net = net,
                    taxRate = state.taxTypes.firstOrNull { it.identifier == draft.taxType }?.rate,
                    effectiveDate = draft.effectiveDate,
                    today = InvoiceFormat.today(vm.now()),
                    tags = draft.tags,
                ),
            )
            when (result) {
                is ZillitResult.Failure -> vm.update {
                    copy(
                        // The vendor landed even if the invoice did not: keep its id, not the pending name.
                        quickEntry = quickEntry?.copy(busy = false, vendorId = vendorId, pendingVendorName = null),
                        error = result.error.localised(),
                    )
                }
                is ZillitResult.Success -> {
                    vm.update { copy(quickEntry = null) }
                    vm.notice(
                        result.data?.localisedMessage()?.takeIf { it.isNotBlank() }
                            ?: str(S.ah_posted_to_ledger_toast),
                    )
                    vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Payments))
                }
            }
        }
    }
}
