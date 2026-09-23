package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.FraudSignalSource
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFileSink
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tax filing — the web's `TaxFilingShell`, `TaxFilingModule` and
 * `VATReturnView` behind one view model.
 *
 * The one screen in this application that files a legal document with a tax
 * authority, which shapes it throughout: a draft is built as often as anybody
 * wants, and a submission is confirmed, filed once, and never undone.
 *
 * [signals] is the anti-fraud description of this machine that HMRC requires.
 * Without it the two calls that reach HMRC are refused here rather than sent
 * incomplete — a return rejected for missing headers is a return the
 * accountant believes they filed.
 *
 * The work is split the way the account hub's is: [RegistrationActions] for the
 * companies list and its dialogs, [ReturnActions] for periods and submission,
 * [MappingActions] for the boxes and what fills them.
 */
class TaxFilingViewModel(
    internal val repository: TaxFilingRepository,
    internal val signals: FraudSignalSource? = null,
    /** A year back, and today, as `yyyy-mm-dd`. The sync's default window. */
    internal val obligationWindow: () -> Pair<String, String> = { "" to "" },
    /** Where exports land. Absent leaves both exports unavailable. */
    internal val fileSink: TaxFileSink? = null,
    /** `2026-09-12_1430` — the export's timestamp, the web's `exportTs`. */
    internal val exportStamp: () -> String = { "" },
    /**
     * The open production, as it changes. Every switch forgets the last one's
     * companies, periods and mapping — the view model outlives it.
     */
    projectChanges: Flow<String?>? = null,
    internal val connectPollMillis: Long = CONNECT_POLL_MILLIS,
    internal val connectPolls: Int = CONNECT_POLLS,
) : ZillitViewModel<TaxFilingUiState, TaxFilingEvent, TaxFilingEffect>(
    TaxFilingUiState(canReachAuthority = signals != null),
) {

    private val registrationActions = RegistrationActions(this)
    private val mappingActions = MappingActions(this)
    private val returnActions = ReturnActions(this, mappingActions)

    /** The dispatch chain; the first handler that claims an event takes it. */
    private val handlers: List<(TaxFilingEvent) -> Boolean> = listOf(
        ::onShellEvent,
        registrationActions::onEvent,
        returnActions::onEvent,
        mappingActions::onEvent,
    )

    /**
     * Bumped on a production switch, so an answer that belongs to the last
     * production lands nowhere instead of inside the next one's screens.
     */
    private var epoch = 0

    /** Whether the tool has been shown — nothing is fetched for a tool nobody opened. */
    private var shown = false

    init {
        projectChanges?.let { changes ->
            launch { changes.distinctUntilChanged().collect { onProjectChanged() } }
        }
    }

    override fun onEvent(event: TaxFilingEvent) {
        handlers.firstOrNull { it(event) }
    }

    /** Forgets the last production and, if the tool is showing, loads the new one. */
    fun onProjectChanged() {
        epoch++
        registrationActions.stopWatching()
        val route = currentState.route
        setState { TaxFilingUiState(canReachAuthority = signals != null, route = route) }
        if (shown) loadRoute(route)
    }

    private fun onShellEvent(event: TaxFilingEvent): Boolean {
        when (event) {
            is TaxFilingEvent.RouteChanged -> routeChanged(TaxFilingRoute.parse(event.path))
            is TaxFilingEvent.OpenFiling ->
                emit(TaxFilingEffect.Navigate(TaxFilingRoute.Filing(event.filing.country, event.filing.key).path))
            TaxFilingEvent.Back -> back()
            TaxFilingEvent.ShowCatalog -> emit(TaxFilingEffect.Navigate(TaxFilingRoute.BASE_PATH))
            TaxFilingEvent.LeaveToAccountHub -> emit(TaxFilingEffect.LeaveTool)
            TaxFilingEvent.Refresh -> loadRoute(currentState.route)
            else -> return false
        }
        return true
    }

    /**
     * Arrives on every showing, not only on a change: a route shown again is
     * refreshed quietly, and its open return — with any unsaved mapping — kept.
     */
    private fun routeChanged(route: TaxFilingRoute) {
        shown = true
        if (route != currentState.route) {
            registrationActions.stopWatching()
            setState {
                copy(
                    route = route,
                    view = TaxFilingView.Registrations,
                    returnState = ReturnState(),
                    draft = null,
                    removing = null,
                )
            }
        }
        loadRoute(route)
    }

    private fun back() {
        val state = currentState
        when {
            state.inReturn -> onEvent(TaxFilingEvent.BackToRegistrations)
            state.route is TaxFilingRoute.Filing -> emit(TaxFilingEffect.Navigate(TaxFilingRoute.BASE_PATH))
            else -> emit(TaxFilingEffect.LeaveTool)
        }
    }

    private fun loadRoute(route: TaxFilingRoute) = when (route) {
        TaxFilingRoute.Catalog -> loadCatalog()
        is TaxFilingRoute.Filing -> if (route.supported != null) loadFiling() else Unit
    }

    /**
     * The catalogue, and the companies that order it.
     *
     * A companies failure must not blank the page — it only decides which
     * filings come first — so it is tolerated, as the web tolerates it.
     */
    private fun loadCatalog() {
        setState { copy(catalog = catalog.copy(loading = !catalog.loaded)) }
        request({ repository.catalog() }, { rows ->
            setState { copy(catalog = catalog.copy(filings = rows, loading = false, loaded = true)) }
        }, { error ->
            setState { copy(catalog = catalog.copy(loading = false)) }
            fail(error)
        })
        request({ repository.companies() }, ::applyCompanies, { })
    }

    /** The companies and their registrations, fetched side by side. */
    internal fun loadFiling() {
        setState { copy(loading = registrations.isEmpty()) }
        request({ repository.companies() }, ::applyCompanies)
        request({ repository.registrations() }, { rows ->
            applyRegistrations(rows)
            setState { copy(loading = false) }
        }, { error ->
            setState { copy(loading = false) }
            fail(error)
        })
    }

    private fun applyCompanies(rows: List<TaxCompany>) = setState {
        copy(
            companies = rows,
            catalog = catalog.copy(companyCountries = rows.map { it.countryCode }.filter { it.isNotBlank() }.toSet()),
        )
    }

    /**
     * A fresh registrations list, carried into the open return too.
     *
     * A connection granted in the browser has to reach the return's header and
     * its Submit button; a registration removed elsewhere closes the return
     * rather than leaving a surface for something that no longer exists.
     */
    internal fun applyRegistrations(rows: List<TaxRegistration>) = setState {
        val open = returnState.registration ?: return@setState copy(registrations = rows)
        val fresh = rows.firstOrNull { it.id == open.id }?.named(companies)
        if (fresh == null) {
            copy(registrations = rows, view = TaxFilingView.Registrations, returnState = ReturnState())
        } else {
            copy(registrations = rows, returnState = returnState.copy(registration = fresh))
        }
    }

    // -- what the collaborators share ----------------------------------------------

    internal val current: TaxFilingUiState get() = currentState

    internal fun update(reducer: TaxFilingUiState.() -> TaxFilingUiState) = setState(reducer)

    internal fun emit(effect: TaxFilingEffect) = sendEffect(effect)

    internal fun work(block: suspend CoroutineScope.() -> Unit): Job = launch(block)

    /**
     * [launchResult], dropping the answer if the production changed while it
     * was on its way.
     */
    internal fun <T> request(
        block: suspend () -> ZillitResult<T>,
        onSuccess: (T) -> Unit,
        onError: (ZillitError) -> Unit = ::fail,
    ): Job {
        val asked = epoch
        return launchResult(
            block,
            { data -> if (asked == epoch) onSuccess(data) },
            { error -> if (asked == epoch) onError(error) },
        )
    }

    internal fun toast(message: String) = emit(TaxFilingEffect.Toast(TaxToast(message, TaxToastTone.Success)))

    internal fun info(message: String) = emit(TaxFilingEffect.Toast(TaxToast(message, TaxToastTone.Info)))

    internal fun fail(message: String) = emit(TaxFilingEffect.Toast(TaxToast(message, TaxToastTone.Error)))

    internal fun fail(error: ZillitError) = fail(error.localised())

    internal fun refuseWithoutSignals() = fail(
        str(S.desktop_tax_no_machine_details),
    )

    private companion object {
        /** How often a consent open in the browser is checked for. */
        const val CONNECT_POLL_MILLIS = 4_000L

        /** Five minutes of checking, then the accountant is told and it stops. */
        const val CONNECT_POLLS = 75
    }
}
