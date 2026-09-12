package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration

/**
 * One registration's return: opening it, choosing and syncing its periods, and
 * the submission — the web's `VATReturnView` handlers.
 */
internal class ReturnActions(
    private val vm: TaxFilingViewModel,
    private val mapping: MappingActions,
) {

    fun onEvent(event: TaxFilingEvent): Boolean {
        when (event) {
            is TaxFilingEvent.Open -> open(event.registration)
            TaxFilingEvent.BackToRegistrations -> vm.update {
                copy(view = TaxFilingView.Registrations, returnState = ReturnState())
            }
            is TaxFilingEvent.SelectPeriod -> select(event.periodKey)
            TaxFilingEvent.SyncObligations -> sync()
            TaxFilingEvent.AskSubmit -> askSubmit()
            TaxFilingEvent.DismissSubmit -> vm.update {
                if (returnState.submitting) this else copy(returnState = returnState.copy(confirmingSubmit = false))
            }
            TaxFilingEvent.ConfirmSubmit -> submit()
            else -> return false
        }
        return true
    }

    /**
     * Opens the return for a connected registration.
     *
     * The web offers "Open VAT return" only once HMRC is connected; the same
     * rule is kept here rather than trusted to the button.
     */
    private fun open(registration: TaxRegistration) {
        val named = registration.named(vm.current.companies)
        if (!named.connected) return vm.info("Connect to HMRC to file a VAT return.")
        vm.update {
            copy(
                view = TaxFilingView.Return,
                returnState = ReturnState(registration = named, obligationsLoading = true, mappingLoading = true),
            )
        }
        loadObligations(named.id)
        loadFiled(named.id)
        mapping.loadMap(named)
        mapping.loadLookups()
    }

    private fun isOpen(registrationId: String) = vm.current.returnState.registration?.id == registrationId

    private fun loadObligations(registrationId: String) {
        vm.request({ vm.repository.obligations(registrationId) }, { rows ->
            if (isOpen(registrationId)) {
                vm.update { copy(returnState = returnState.copy(obligations = rows, obligationsLoading = false)) }
            }
        }, { error ->
            if (isOpen(registrationId)) vm.update { copy(returnState = returnState.copy(obligationsLoading = false)) }
            vm.fail(error)
        })
    }

    /**
     * The receipts, for periods filed from here.
     *
     * Failures are swallowed, as on the web: a missing receipt panel is a
     * smaller loss than an error over a screen that otherwise works, and the
     * obligation list already says which periods are done.
     */
    private fun loadFiled(registrationId: String) {
        vm.request({ vm.repository.filedReturns(registrationId) }, { rows ->
            if (isOpen(registrationId)) vm.update { copy(returnState = returnState.copy(filed = rows)) }
        }, { })
    }

    /** The draft belongs to the period it was built for, so a new period drops it. */
    private fun select(periodKey: String) = vm.update {
        copy(
            returnState = returnState.copy(
                periodKey = periodKey,
                draft = null,
                diagnostics = DraftDiagnostics(),
                draftStale = false,
                confirmingSubmit = false,
            ),
        )
    }

    /**
     * Re-asks HMRC which periods are owed.
     *
     * One of the two calls that leaves Zillit, so it carries the machine's
     * anti-fraud description and is refused outright without it.
     */
    private fun sync() {
        val state = vm.current.returnState
        val registration = state.registration ?: return
        if (state.syncing) return
        val source = vm.signals ?: return vm.refuseWithoutSignals()
        val (from, to) = vm.obligationWindow()
        vm.update { copy(returnState = returnState.copy(syncing = true, obligationsLoading = true)) }
        vm.work {
            val collected = source.collect()
            if (!collected.isComplete) {
                vm.update { copy(returnState = returnState.copy(syncing = false, obligationsLoading = false)) }
                return@work vm.refuseWithoutSignals()
            }
            vm.request({ vm.repository.syncObligations(registration.id, from, to, collected) }, { rows ->
                if (!isOpen(registration.id)) return@request
                vm.update {
                    val kept = returnState.periodKey.takeIf { key -> rows.any { it.periodKey == key } }
                    copy(
                        returnState = returnState.copy(
                            obligations = rows,
                            syncing = false,
                            obligationsLoading = false,
                            periodKey = kept.orEmpty(),
                            draft = if (kept == null) null else returnState.draft,
                        ),
                    )
                }
                vm.toast("Synced obligations from HMRC")
            }, { error ->
                if (isOpen(registration.id)) {
                    vm.update { copy(returnState = returnState.copy(syncing = false, obligationsLoading = false)) }
                }
                vm.fail(error)
            })
        }
    }

    /** The web's own refusals, word for word, before anything is confirmed. */
    private fun askSubmit() {
        val state = vm.current.returnState
        when {
            !state.connected -> vm.info("Connect to HMRC before submitting.")
            state.draft == null -> vm.info("Calculate the return before submitting.")
            state.draftStale -> vm.info("The mapping has changed. Recalculate before submitting.")
            state.canSubmit -> vm.update { copy(returnState = returnState.copy(confirmingSubmit = true)) }
        }
    }

    /**
     * Files the return.
     *
     * The only irreversible act in this module. A period, once filed, is
     * fulfilled and cannot be filed again — so the guard is checked here as
     * well as on the screen, and the confirmation is not a formality.
     */
    private fun submit() {
        val state = vm.current.returnState
        val registration = state.registration ?: return
        val draft = state.draft ?: return
        if (!state.canSubmit) return
        val source = vm.signals ?: return vm.refuseWithoutSignals()
        val periodKey = draft.periodKey.ifBlank { state.periodKey }

        vm.update { copy(returnState = returnState.copy(submitting = true, confirmingSubmit = false)) }
        vm.work {
            val collected = source.collect()
            if (!collected.isComplete) {
                vm.update { copy(returnState = returnState.copy(submitting = false)) }
                return@work vm.refuseWithoutSignals()
            }
            vm.request({ vm.repository.submitReturn(registration.id, periodKey, draft, collected) }, {
                vm.update {
                    copy(
                        returnState = returnState.copy(
                            submitting = false,
                            draft = null,
                            draftStale = false,
                            diagnostics = DraftDiagnostics(),
                            filedPeriodKey = periodKey,
                        ),
                    )
                }
                vm.toast("Return submitted to HMRC")
                // Re-ask HMRC rather than re-read the local list: the authority
                // decides when a period is fulfilled, and the stored rows still
                // say open until it has said otherwise.
                resyncAfterFiling(registration.id, collected)
                loadFiled(registration.id)
            }, { error ->
                vm.update { copy(returnState = returnState.copy(submitting = false)) }
                vm.fail(error)
            })
        }
    }

    /**
     * Asks HMRC again, so the period just filed reads as fulfilled.
     *
     * Failures are swallowed: the return is filed either way, and an error
     * over a successful submission reads as a submission that failed. The
     * period shows fulfilled at the next sync instead.
     */
    private fun resyncAfterFiling(registrationId: String, collected: FraudSignals) {
        val (from, to) = vm.obligationWindow()
        vm.request({ vm.repository.syncObligations(registrationId, from, to, collected) }, { rows ->
            if (isOpen(registrationId)) vm.update { copy(returnState = returnState.copy(obligations = rows)) }
        }, { })
    }
}
