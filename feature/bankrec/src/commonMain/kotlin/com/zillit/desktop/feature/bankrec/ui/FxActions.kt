package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxStatus
import com.zillit.desktop.feature.bankrec.domain.FxVariance

/**
 * What a foreign payment cost against what it was budgeted at.
 *
 * Posting writes a journal, so it names the nominal it lands on and cannot be
 * undone from here. Posting a whole period at once is confirmed separately —
 * it is the same act repeated, and repeating it by mistake is a page of
 * journals to reverse.
 */
internal class FxActions(private val vm: BankRecViewModel) {

    private val state: FxState get() = vm.ui.fx

    private fun edit(reducer: FxState.() -> FxState) = vm.update { copy(fx = fx.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.FilterFx -> {
                edit { copy(periodId = event.periodId) }
                load(force = true)
            }

            is BankRecEvent.ComposeFxPosting -> compose(event.variance)
            is BankRecEvent.EditFxPosting ->
                edit { copy(nominalCode = event.nominalCode, costCentre = event.costCentre) }

            BankRecEvent.DismissFxPosting -> edit { copy(posting = null) }
            BankRecEvent.ConfirmFxPosting -> post()
            BankRecEvent.AskPostAllFx -> edit { copy(confirmingPostAll = true) }
            BankRecEvent.DismissPostAllFx -> edit { copy(confirmingPostAll = false) }
            BankRecEvent.ConfirmPostAllFx -> postAll()
            else -> return false
        }
        return true
    }

    fun load(force: Boolean) {
        if (!force && state.rows.isNotEmpty()) return
        val periodId = state.periodId
        edit { copy(loading = true) }
        vm.runResult({ vm.repo.fxVariances(periodId.takeIf { it.isNotBlank() }) }, { rows ->
            edit { copy(rows = rows, loading = false) }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
    }

    private fun compose(variance: FxVariance) = edit {
        copy(posting = variance, nominalCode = FxPosting.DEFAULT_NOMINAL, costCentre = costCentre)
    }

    private fun post() {
        val variance = state.posting ?: return
        val posting = FxPosting(nominalCode = state.nominalCode.trim(), costCentre = state.costCentre)
        if (posting.nominalCode.isBlank()) {
            return vm.refuse("Give the nominal code this variance posts to.")
        }
        edit { copy(saving = true) }
        vm.runResult({ vm.repo.postFxVariance(variance.id, posting) }, {
            edit {
                copy(
                    saving = false,
                    posting = null,
                    rows = rows.map { if (it.id == variance.id) it.copy(status = FxStatus.Posted) else it },
                )
            }
            vm.notify("Variance posted to ${posting.nominalCode}.")
        }, { error ->
            edit { copy(saving = false) }
            vm.report(error)
        })
    }

    private fun postAll() {
        val periodId = state.periodId.ifBlank { vm.ui.currentPeriod?.id.orEmpty() }
        if (periodId.isBlank()) {
            edit { copy(confirmingPostAll = false) }
            return vm.refuse("Choose the period to post.")
        }
        edit { copy(saving = true, confirmingPostAll = false) }
        vm.runResult({ vm.repo.postAllFxVariances(periodId) }, {
            edit { copy(saving = false) }
            vm.notify("Every unposted variance in the period was posted.")
            load(force = true)
        }, { error ->
            edit { copy(saving = false) }
            vm.report(error)
        })
    }
}
