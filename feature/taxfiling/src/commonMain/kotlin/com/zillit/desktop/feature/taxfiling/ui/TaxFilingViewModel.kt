package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FraudSignalSource
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.LedgerExportSink
import com.zillit.desktop.feature.taxfiling.domain.ledgerCsv
import com.zillit.desktop.feature.taxfiling.domain.ledgerFileName
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration

sealed interface TaxFilingEvent {
    data object Load : TaxFilingEvent

    data class Open(val registration: TaxRegistration) : TaxFilingEvent
    data object BackToRegistrations : TaxFilingEvent

    data object ComposeRegistration : TaxFilingEvent
    data class EditDraft(val draft: RegistrationDraft) : TaxFilingEvent
    data object DismissDraft : TaxFilingEvent
    data object SaveRegistration : TaxFilingEvent

    data class AskRemove(val registration: TaxRegistration) : TaxFilingEvent
    data object DismissRemove : TaxFilingEvent
    data object ConfirmRemove : TaxFilingEvent

    /** Opens the authority's consent page in the machine's browser. */
    data class Connect(val registration: TaxRegistration) : TaxFilingEvent

    data class SelectPeriod(val periodKey: String) : TaxFilingEvent
    data object SyncObligations : TaxFilingEvent
    data class EditMapping(val mapping: BoxMapping) : TaxFilingEvent
    data object SaveMapping : TaxFilingEvent

    /** Saves the mapping, then builds the nine boxes from the ledger. */
    data object Calculate : TaxFilingEvent

    /** Writes the ledger lines behind the boxes to a spreadsheet. */
    data object ExportLedger : TaxFilingEvent

    data object AskSubmit : TaxFilingEvent
    data object DismissSubmit : TaxFilingEvent
    data object ConfirmSubmit : TaxFilingEvent

    data object ClearNotice : TaxFilingEvent
}

sealed interface TaxFilingEffect {
    data class Failed(val message: String) : TaxFilingEffect

    /** The consent page. Opened in a browser, not in this application. */
    data class OpenInBrowser(val url: String) : TaxFilingEffect
}

/**
 * HMRC Making Tax Digital.
 *
 * The one screen in this application that files a legal document with a tax
 * authority, which shapes it throughout: a draft is built as often as anybody
 * wants, and a submission is confirmed, filed once, and never undone.
 *
 * [signals] is the anti-fraud description of this machine that HMRC requires.
 * Without it the two calls that reach HMRC are refused here rather than sent
 * incomplete — a return rejected for missing headers is a return the
 * accountant believes they filed.
 */
class TaxFilingViewModel(
    private val repository: TaxFilingRepository,
    private val signals: FraudSignalSource? = null,
    /** A year back, and today, as `yyyy-mm-dd`. The sync's default window. */
    private val obligationWindow: () -> Pair<String, String> = { "" to "" },
    /** Where an export lands. Absent leaves the export unavailable. */
    private val exportSink: LedgerExportSink? = null,
) : ZillitViewModel<TaxFilingUiState, TaxFilingEvent, TaxFilingEffect>(
    TaxFilingUiState(canReachAuthority = signals != null),
) {

    private var started = false

    fun start() {
        if (started) return
        started = true
        onEvent(TaxFilingEvent.Load)
    }

    // One branch per event and nothing else: the complexity is the number of
    // things this screen can do, and splitting the dispatch would hide which
    // events exist rather than simplify anything.
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: TaxFilingEvent) {
        when (event) {
            TaxFilingEvent.Load -> load()
            is TaxFilingEvent.Open -> open(event.registration)
            TaxFilingEvent.BackToRegistrations -> setState {
                copy(view = TaxFilingView.Registrations, returnState = ReturnState())
            }

            TaxFilingEvent.ComposeRegistration -> setState { copy(draft = RegistrationDraft()) }
            is TaxFilingEvent.EditDraft -> setState { copy(draft = event.draft) }
            TaxFilingEvent.DismissDraft -> setState { copy(draft = null) }
            TaxFilingEvent.SaveRegistration -> saveRegistration()

            is TaxFilingEvent.AskRemove -> setState { copy(removing = event.registration) }
            TaxFilingEvent.DismissRemove -> setState { copy(removing = null) }
            TaxFilingEvent.ConfirmRemove -> removeRegistration()

            is TaxFilingEvent.Connect -> connect(event.registration)

            is TaxFilingEvent.SelectPeriod -> selectPeriod(event.periodKey)
            TaxFilingEvent.SyncObligations -> syncObligations()
            is TaxFilingEvent.EditMapping -> editMapping(event.mapping)
            TaxFilingEvent.SaveMapping -> saveMapping(thenCalculate = false)
            TaxFilingEvent.Calculate -> saveMapping(thenCalculate = true)
            TaxFilingEvent.ExportLedger -> exportLedger()

            TaxFilingEvent.AskSubmit -> setState { copy(returnState = returnState.copy(confirmingSubmit = true)) }
            TaxFilingEvent.DismissSubmit -> setState {
                copy(returnState = returnState.copy(confirmingSubmit = false))
            }
            TaxFilingEvent.ConfirmSubmit -> submit()

            TaxFilingEvent.ClearNotice -> setState { copy(notice = null) }
        }
    }

    private fun load() {
        setState { copy(loading = true) }
        launchResult(repository::catalog, { rows -> setState { copy(filings = rows) } }, ::report)
        launchResult(repository::companies, { rows -> setState { copy(companies = rows) } }, ::report)
        launchResult(repository::registrations, { rows ->
            setState { copy(registrations = rows, loading = false) }
        }, { error ->
            setState { copy(loading = false) }
            report(error)
        })
    }

    private fun open(registration: TaxRegistration) {
        setState {
            copy(
                view = TaxFilingView.Return,
                returnState = ReturnState(registration = registration, loading = true),
            )
        }
        launchResult({ repository.obligations(registration.id) }, { rows ->
            setState {
                copy(
                    returnState = returnState.copy(
                        obligations = rows,
                        // The first period still owed, which is the one an
                        // accountant opening this came to file.
                        periodKey = rows.firstOrNull { it.isOpen }?.periodKey.orEmpty(),
                        loading = false,
                    ),
                )
            }
        }, { error ->
            setState { copy(returnState = returnState.copy(loading = false)) }
            report(error)
        })
        launchResult({ repository.boxMap(registration.companyId) }, { rows ->
            setState { copy(returnState = returnState.copy(boxMap = rows)) }
        }, ::report)
        loadFiled(registration.id)
    }

    /**
     * The receipts, for periods filed from here.
     *
     * Failures are swallowed: a missing receipt panel is a smaller loss than
     * an error over a screen that otherwise works, and the obligation list
     * already says which periods are done.
     */
    private fun loadFiled(registrationId: String) {
        launchResult({ repository.filedReturns(registrationId) }, { rows ->
            setState { copy(returnState = returnState.copy(filed = rows)) }
        }, { })
    }

    private fun selectPeriod(periodKey: String) = setState {
        // The draft belongs to the period it was built for; keeping it across
        // a change would show one period's figures under another's heading.
        copy(
            returnState = returnState.copy(
                periodKey = periodKey,
                draft = null,
                diagnostics = DraftDiagnostics(),
            ),
        )
    }

    private fun saveRegistration() {
        val draft = currentState.draft ?: return
        draft.problem?.let { return sendEffect(TaxFilingEffect.Failed(it)) }
        setState { copy(draft = draft.copy(saving = true)) }
        launchResult(
            { repository.createRegistration(draft.companyId, draft.digits, draft.frequency) },
            {
                setState { copy(draft = null, notice = "Registration added.") }
                load()
            },
            { error ->
                setState { copy(draft = draft.copy(saving = false)) }
                report(error)
            },
        )
    }

    private fun removeRegistration() {
        val registration = currentState.removing ?: return
        launchResult({ repository.deleteRegistration(registration.id) }, {
            setState { copy(removing = null, notice = "Registration removed.") }
            load()
        }, { error ->
            setState { copy(removing = null) }
            report(error)
        })
    }

    private fun connect(registration: TaxRegistration) {
        launchResult({ repository.connectUrl(registration.id) }, { url ->
            if (url.isBlank()) {
                sendEffect(TaxFilingEffect.Failed("The authority did not return a consent page."))
            } else {
                sendEffect(TaxFilingEffect.OpenInBrowser(url))
            }
        }, ::report)
    }

    /**
     * Re-asks HMRC which periods are owed.
     *
     * One of the two calls that leaves Zillit, so it carries the machine's
     * anti-fraud description and is refused outright without it.
     */
    private fun syncObligations() {
        val registration = currentState.returnState.registration ?: return
        val source = signals ?: return refuseWithoutSignals()
        val (from, to) = obligationWindow()
        setState { copy(returnState = returnState.copy(syncing = true)) }
        launch {
            val collected = source.collect()
            if (!collected.isComplete) {
                setState { copy(returnState = returnState.copy(syncing = false)) }
                return@launch refuseWithoutSignals()
            }
            launchResult({ repository.syncObligations(registration.id, from, to, collected) }, { rows ->
                setState {
                    copy(
                        returnState = returnState.copy(
                            obligations = rows,
                            syncing = false,
                            periodKey = returnState.periodKey.takeIf { key -> rows.any { it.periodKey == key } }
                                ?: rows.firstOrNull { it.isOpen }?.periodKey.orEmpty(),
                        ),
                    )
                }
            }, { error ->
                setState { copy(returnState = returnState.copy(syncing = false)) }
                report(error)
            })
        }
    }

    private fun editMapping(mapping: BoxMapping) = setState {
        val rows = returnState.boxMap
        val next = if (rows.any { it.box == mapping.box }) {
            rows.map { if (it.box == mapping.box) mapping else it }
        } else {
            rows + mapping
        }
        // The draft was built from the old mapping, so it no longer describes
        // what is on screen.
        copy(returnState = returnState.copy(boxMap = next, draft = null, diagnostics = DraftDiagnostics()))
    }

    /**
     * Saves the mapping, and on [thenCalculate] builds the return from it.
     *
     * In that order and never the other way: a draft built from a mapping the
     * server has not been told about is figures nobody can reproduce.
     */
    private fun saveMapping(thenCalculate: Boolean) {
        val state = currentState.returnState
        val registration = state.registration ?: return
        setState { copy(returnState = returnState.copy(calculating = thenCalculate)) }
        launchResult({ repository.saveBoxMap(registration.companyId, state.boxMap) }, {
            if (thenCalculate) {
                calculate()
            } else {
                setState { copy(notice = "Box mapping saved.") }
            }
        }, { error ->
            setState { copy(returnState = returnState.copy(calculating = false)) }
            report(error)
        })
    }

    private fun calculate() {
        val state = currentState.returnState
        val registration = state.registration ?: return
        if (state.periodKey.isBlank()) {
            setState { copy(returnState = returnState.copy(calculating = false)) }
            return sendEffect(TaxFilingEffect.Failed("Choose the period to calculate."))
        }
        launchResult({ repository.buildDraft(registration.id, state.periodKey) }, { built ->
            setState {
                copy(
                    returnState = returnState.copy(
                        draft = built.vatReturn,
                        diagnostics = built.diagnostics,
                        calculating = false,
                    ),
                )
            }
        }, { error ->
            setState { copy(returnState = returnState.copy(calculating = false)) }
            report(error)
        })
    }

    /**
     * The ledger lines behind the boxes.
     *
     * The answer to "why is box 6 that figure", and the reason it is offered
     * beside the draft rather than after filing: a mapping is checked against
     * the rows it selected, not against a total.
     */
    private fun exportLedger() {
        val state = currentState.returnState
        val registration = state.registration ?: return
        val sink = exportSink ?: return sendEffect(
            TaxFilingEffect.Failed("This installation cannot save files."),
        )
        if (state.periodKey.isBlank()) {
            return sendEffect(TaxFilingEffect.Failed("Choose the period to export."))
        }

        setState { copy(returnState = returnState.copy(exporting = true)) }
        launchResult({ repository.ledgerLines(registration.id, state.periodKey) }, { rows ->
            if (rows.isEmpty()) {
                setState {
                    copy(
                        returnState = returnState.copy(exporting = false),
                        notice = "No ledger rows for this period.",
                    )
                }
                return@launchResult
            }
            launch {
                val (_, today) = obligationWindow()
                sink.save(ledgerFileName(state.periodKey, today), ledgerCsv(rows))
                setState {
                    copy(
                        returnState = returnState.copy(exporting = false),
                        notice = "Exported ${rows.size} ledger row(s).",
                    )
                }
            }
        }, { error ->
            setState { copy(returnState = returnState.copy(exporting = false)) }
            report(error)
        })
    }

    /**
     * Files the return.
     *
     * The only irreversible act in this module. A period, once filed, is
     * fulfilled and cannot be filed again — so the guard is checked here as
     * well as on the screen, and the confirmation is not a formality.
     */
    private fun submit() {
        val state = currentState.returnState
        val registration = state.registration ?: return
        val draft = state.draft ?: return
        val source = signals ?: return refuseWithoutSignals()
        if (!state.canSubmit) return

        setState { copy(returnState = returnState.copy(submitting = true, confirmingSubmit = false)) }
        launch {
            val collected = source.collect()
            if (!collected.isComplete) {
                setState { copy(returnState = returnState.copy(submitting = false)) }
                return@launch refuseWithoutSignals()
            }
            launchResult(
                { repository.submitReturn(registration.id, state.periodKey, draft, collected) },
                {
                    setState {
                        copy(
                            returnState = returnState.copy(
                                submitting = false,
                                filedPeriodKey = state.periodKey,
                            ),
                            notice = "Return filed for ${state.periodKey}.",
                        )
                    }
                    // Re-ask HMRC rather than re-read the local list: the
                    // authority decides when a period is fulfilled, and the
                    // stored rows still say open until it has said otherwise.
                    resyncAfterFiling(registration.id, collected)
                    loadFiled(registration.id)
                },
                { error ->
                    setState { copy(returnState = returnState.copy(submitting = false)) }
                    report(error)
                },
            )
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
        val (from, to) = obligationWindow()
        launchResult({ repository.syncObligations(registrationId, from, to, collected) }, { rows ->
            setState { copy(returnState = returnState.copy(obligations = rows)) }
        }, { })
    }

    private fun refuseWithoutSignals() = sendEffect(
        TaxFilingEffect.Failed(
            "This machine cannot be described to HMRC, which every filing requires. " +
                "Nothing was sent.",
        ),
    )

    private fun report(error: ZillitError) = sendEffect(TaxFilingEffect.Failed(error.localised()))
}
