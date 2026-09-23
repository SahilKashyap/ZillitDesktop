package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.QuickEntry

/**
 * Invoice Entry's Quick Entry — a single-code invoice straight to
 * ready-to-pay (the web's `quickPost`). Accountants only, here as on screen.
 */
internal class InvoiceQuickEntryActions(private val vm: InvoicesViewModel) {

    /** True when [event] was Quick Entry's own. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            EntryEvent.StartQuick -> if (vm.state.value.isAccountant) {
                vm.update { copy(quickEntry = QuickEntryDraft()) }
            }
            is EntryEvent.EditQuick -> vm.update { copy(quickEntry = event.draft) }
            EntryEvent.PostQuick -> post()
            EntryEvent.CancelQuick -> vm.update { copy(quickEntry = quickEntry?.takeIf { it.busy }) }
            else -> return false
        }
        return true
    }

    /**
     * Creates the single-code invoice at ready-to-pay, then shows it where it
     * went — Payment Runs, as the web navigates there.
     */
    private fun post() {
        val state = vm.state.value
        val draft = state.quickEntry ?: return
        val net = draft.netValue ?: return
        if (!state.isAccountant) return
        if (!draft.isReady || draft.busy) return
        vm.update { copy(quickEntry = quickEntry?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.quickEntry(
                QuickEntry(
                    reference = draft.reference,
                    vendorId = draft.vendorId,
                    nominal = draft.nominal,
                    costCentre = draft.costCentre,
                    net = net,
                    taxRate = state.taxTypes.firstOrNull { it.identifier == draft.taxType }?.rate,
                    effectiveDate = draft.effectiveDate,
                    today = InvoiceFormat.today(vm.now()),
                ),
            )
            when (result) {
                is ZillitResult.Failure -> vm.update {
                    copy(quickEntry = quickEntry?.copy(busy = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    vm.update { copy(quickEntry = null) }
                    vm.notice(str(S.ah_posted_to_ledger_toast))
                    vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Payments))
                }
            }
        }
    }
}
