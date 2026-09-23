package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.payroll.domain.PayrollDocuments
import com.zillit.desktop.feature.payroll.domain.PayrollFiles
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
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
) : ZillitViewModel<PayrollUiState, PayrollEvent, PayrollEffect>(PayrollUiState(viewer = viewer())) {

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
    private val shared = SharedActions(this)

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
        setState { copy(viewer = resolved, people = crew, projectName = production, now = clock) }
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
        launch { settings.bankAccounts().getOrNull()?.let { setState { copy(bankAccounts = it) } } }
        launch { settings.companies().getOrNull()?.let { setState { copy(companies = it) } } }
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

    internal fun reloadOpen(silent: Boolean) {
        when (currentState.destination) {
            PayrollDestination.History -> history.reload(silent)
            PayrollDestination.Run -> run.reload(silent)
            PayrollDestination.Processing -> processing.reload(silent)
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
            PayrollDestination.Landing -> Unit
        }
    }

    override fun onEvent(event: PayrollEvent) {
        when (event) {
            is HistoryEvent -> history.onEvent(event)
            is RunEvent -> run.onEvent(event)
            is JournalEvent -> journal.onEvent(event)
            is ProcessingEvent -> processing.onEvent(event)
            else -> onShellEvent(event)
        }
    }

    private fun onShellEvent(event: PayrollEvent) {
        when (event) {
            is PayrollEvent.Route -> route(event.path)
            is PayrollEvent.OpenTile -> openTile(event.tile)
            PayrollEvent.BackToLanding -> emit(PayrollEffect.Navigate(PayrollDestination.Landing.path))
            PayrollEvent.ClearNotice -> setState { copy(notice = null) }
            else -> shared.onEvent(event)
        }
    }

    /**
     * Shows the screen a route names. A screen the viewer is not offered falls
     * back to the landing rather than drawing a page they have no tile for.
     */
    private fun route(path: String) {
        val asked = PayrollDestination.forRoute(path)
        val shown = asked.takeIf { it.visibleTo(currentState.viewer) } ?: PayrollDestination.Landing
        setState { copy(destination = shown, enteredAsTool = PayrollDestination.enteredAsTool(path)) }
        ensureLoaded()
    }

    /**
     * A tile is navigation, not a local switch: the route goes to the window
     * so the hub embedding this tool, the back stack and a torn-off window all
     * agree on where the viewer is. Entry Setup is the Account Hub's.
     */
    private fun openTile(tile: PayrollTile) {
        if (tile !in PayrollTile.visibleTo(currentState.viewer, currentState.enteredAsTool)) return
        val target = tile.destination?.path ?: PAYROLL_ENTRY_SETUP_ROUTE
        emit(PayrollEffect.Navigate(target))
    }

    companion object {
        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}
