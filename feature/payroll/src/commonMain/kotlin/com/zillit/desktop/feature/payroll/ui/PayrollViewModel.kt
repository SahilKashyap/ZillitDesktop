package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.payroll.domain.ActiveDealSource
import com.zillit.desktop.feature.payroll.domain.PayrollDocuments
import com.zillit.desktop.feature.payroll.domain.PayrollEstimator
import com.zillit.desktop.feature.payroll.domain.PayrollProducerSeams
import com.zillit.desktop.feature.payroll.domain.PayrollFiles
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.ProductionReportSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The payroll tool's view model: the landing and the three screens behind it.
 *
 * ## One tool, four screens, one view model
 *
 * The web routes `/payroll/:tile` to four modules; here the route picks a
 * [PayrollDestination] and each screen's behaviour lives in its own
 * collaborator — [HistoryActions], [RunActions] (with [JournalActions]),
 * [ProcessingActions] and the dialogs any of them can open, [SharedActions].
 * What they share is resolved once: who the viewer is, the production's pay
 * period, its approvers, its lock date and its settling accounts.
 *
 * ## Every write is gated here, not only on screen
 *
 * Each collaborator re-checks the rule the button was drawn by before it
 * sends anything, so an event that reaches the handler without the button —
 * a stale screen, a test, a future caller — cannot do what the button would
 * not have offered.
 */
class PayrollViewModel(
    internal val repository: PayrollRepository,
    private val viewer: () -> PayrollViewer,
    private val now: () -> Long,
    /** The production's crew, for names — the rows carry only user ids. */
    private val people: () -> Map<String, PayrollPerson> = { emptyMap() },
    private val projectName: () -> String = { "" },
    /** The payslip and export files; null hides the downloads. */
    internal val documents: PayrollDocuments? = null,
    internal val files: PayrollFiles? = null,
    /**
     * What the producer boards need beyond the payroll service: the unit's
     * production report, a crew member's deal, and the engine that prices a
     * day from the two. Absent, they behave as the web does when its own
     * engine bundle fails to load — nothing is priced locally, and the boards
     * say so rather than showing a figure nobody computed.
     */
    private val seams: PayrollProducerSeams = PayrollProducerSeams(),
) : ZillitViewModel<PayrollUiState, PayrollEvent, PayrollEffect>(PayrollUiState(viewer = viewer())) {

    internal val reports: ProductionReportSource? get() = seams.reports
    internal val deals: ActiveDealSource? get() = seams.deals
    internal val estimator: PayrollEstimator? get() = seams.estimator

    internal fun update(reducer: PayrollUiState.() -> PayrollUiState) = setState(reducer)
    internal fun launchWork(block: suspend () -> Unit): Job = launch { block() }
    internal fun emit(effect: PayrollEffect) = sendEffect(effect)
    internal fun fail(message: String) = sendEffect(PayrollEffect.Failed(message))
    internal fun notify(message: String?) = message?.takeIf { it.isNotBlank() }?.let { setState { copy(notice = it) } }

    /** The current state, for the collaborators. Named `ui` because `state` is the base class's flow. */
    internal val ui: PayrollUiState get() = currentState

    private val history = HistoryActions(this)
    private val run = RunActions(this)
    private val journal = JournalActions(this, run)
    private val processing = ProcessingActions(this)
    private val producer = ProducerBoardActions(this)
    private val estimate = ProductionReportActions(this)
    private val shared = SharedActions(this)

    /** Crew whose holiday-pay rate has been asked for, however it turned out. */
    private val holidayPayAsked = mutableSetOf<String>()

    private var started = false
    private var listening = false
    private var syncJob: Job? = null

    /** Resolves the viewer and the production's payroll settings. Idempotent. */
    fun start() {
        if (started) return
        started = true
        // Resolved outside the state lambdas: inside them `viewer`, `people`
        // and `projectName` are the state's own properties, not the suppliers.
        val resolved = viewer()
        val crew = people()
        val production = projectName()
        val clock = now()
        setState {
            copy(
                viewer = resolved,
                people = crew,
                projectName = production,
                now = clock,
                hasPayEngine = estimator != null,
            )
        }
        listenOnce()
        loadSettings()
    }

    fun onProjectChanged() {
        started = false
        val resolved = viewer()
        setState { PayrollUiState(viewer = resolved, destination = destination, enteredAsTool = enteredAsTool) }
        start()
    }

    /** The rights and the crew land after the production opens; only those change here. */
    fun onRightsChanged() {
        val resolved = viewer()
        val crew = people()
        setState {
            copy(viewer = resolved.copy(onApproverList = viewer.onApproverList), people = crew)
        }
        // A viewer who has just become able to see the screen they were routed to gets it.
        ensureLoaded()
    }

    /**
     * The production's payroll settings. The metadata decides the week
     * boundary, so the screens wait for it before their first read — the
     * web's `metaLoading` gate, without which a Wednesday-start production
     * asks for a Monday week and gets an empty queue.
     */
    private fun loadSettings() {
        val settings = repository.settings
        launch {
            val metadata = settings.metadata()
            setState {
                copy(
                    metadata = (metadata as? ZillitResult.Success)?.data ?: this.metadata,
                    metadataLoaded = true,
                    viewer = viewer.copy(
                        onApproverList = (metadata as? ZillitResult.Success)?.data?.isFinalApprover == true,
                    ),
                )
            }
            if (metadata is ZillitResult.Failure) fail(metadata.error.localised())
            ensureLoaded()
        }
        launch {
            // Fails closed: no flags, no Override.
            val flags = settings.overrideFlags().getOrNull()
            setState { copy(overrideFlags = flags) }
        }
        launch { settings.lockedDate().getOrNull().let { setState { copy(lockedDate = it) } } }
        launch { settings.companies().getOrNull()?.let { setState { copy(companies = it) } } }
        launch { settings.defaultCurrency().getOrNull()?.let { setState { copy(defaultCurrency = it) } } }
        // Reference data for the journal's Layers and Tags. Absent, those
        // cells offer nothing to pick rather than blocking the ledger.
        launch {
            settings.journalReference().getOrNull()?.let { reference ->
                setState { copy(run = run.copy(journal = run.journal.copy(reference = reference))) }
            }
        }
    }

    /**
     * Folds the socket's announcements into whichever screen is open — the
     * web's `ah:payroll:list` refetch, debounced as the web debounces it,
     * because a batch emits one frame per timecard.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                syncJob?.cancel()
                syncJob = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    reloadOpen(silent = true)
                }
            }
        }
    }

    /**
     * Resolves a crew member's holiday-pay rate, once each.
     *
     * A failure is remembered as an attempt rather than retried: the card is
     * supplementary, and a crew member with no deal would otherwise be looked
     * up again every time they were opened.
     */
    internal fun resolveHolidayPay(userId: String) {
        val source = deals ?: return
        val engine = estimator ?: return
        if (userId.isBlank() || !holidayPayAsked.add(userId)) return
        launch {
            val deal = source.activeDeal(userId).getOrNull() ?: return@launch
            val rate = engine.load(deal).getOrNull()?.holidayPayRate ?: return@launch
            if (rate > 0) setState { copy(holidayPayRates = holidayPayRates + (userId to rate)) }
        }
    }

    internal fun reloadOpen(silent: Boolean) {
        when (currentState.destination) {
            PayrollDestination.History -> history.reload(silent)
            PayrollDestination.Run -> run.reload(silent)
            PayrollDestination.Processing -> processing.reload(silent)
            PayrollDestination.ProducerBoard -> producer.reload(silent)
            PayrollDestination.ProductionReport -> estimate.reload(silent)
            PayrollDestination.Landing -> Unit
        }
    }

    /** Loads the open screen's first page once the week boundary is known. */
    private fun ensureLoaded() {
        if (!currentState.metadataLoaded) return
        when (currentState.destination) {
            PayrollDestination.History -> history.ensureLoaded()
            PayrollDestination.Run -> run.ensureLoaded()
            PayrollDestination.Processing -> processing.ensureLoaded()
            PayrollDestination.ProducerBoard -> producer.ensureLoaded()
            PayrollDestination.ProductionReport -> estimate.ensureLoaded()
            PayrollDestination.Landing -> Unit
        }
    }

    override fun onEvent(event: PayrollEvent) {
        when (event) {
            is HistoryEvent -> history.onEvent(event)
            is RunEvent -> run.onEvent(event)
            is JournalEvent -> journal.onEvent(event)
            is ProcessingEvent -> processing.onEvent(event)
            is ProducerBoardEvent -> producer.onEvent(event)
            is ProductionReportEvent -> estimate.onEvent(event)
            else -> onShellEvent(event)
        }
    }

    private fun onShellEvent(event: PayrollEvent) {
        when (event) {
            is PayrollEvent.Route -> route(event.path)
            is PayrollEvent.OpenTile -> openTile(event.tile)
            // Back keeps the entry it came in on, so a producer screen
            // reached from the Film Tools tile returns to the producer landing.
            PayrollEvent.BackToLanding ->
                emit(PayrollEffect.Navigate(PayrollDestination.Landing.path(currentState.enteredAsTool)))
            PayrollEvent.ClearNotice -> setState { copy(notice = null) }
            else -> shared.onEvent(event)
        }
    }

    /**
     * Shows the screen a route names. A screen the viewer is not offered falls
     * back to the landing rather than drawing a page they have no tile for.
     */
    private fun route(path: String) {
        // The marker is read BEFORE the gate: which screens a viewer is
        // offered depends on which entry they came through, so a route that
        // carries `?entry=tool` must be judged as a tool entry, not against
        // whatever the last route was.
        val asTool = PayrollDestination.enteredAsTool(path)
        val asked = PayrollDestination.forRoute(path)
        val shown = asked.takeIf { it.visibleTo(currentState.viewer, asTool) } ?: PayrollDestination.Landing
        setState { copy(destination = shown, enteredAsTool = asTool) }
        ensureLoaded()
    }

    /**
     * A tile is navigation, not a local switch: the route goes to the window
     * so the hub embedding this tool, the back stack and a torn-off window all
     * agree on where the viewer is. Entry Setup is the Account Hub's.
     */
    private fun openTile(tile: PayrollTile) {
        if (tile !in PayrollTile.visibleTo(currentState.viewer, currentState.enteredAsTool)) return
        val target = tile.destination?.path(currentState.enteredAsTool) ?: PAYROLL_ENTRY_SETUP_ROUTE
        emit(PayrollEffect.Navigate(target))
    }

    companion object {
        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}
