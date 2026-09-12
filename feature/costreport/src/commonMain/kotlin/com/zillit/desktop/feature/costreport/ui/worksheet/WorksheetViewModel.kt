package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.CrReportLoader
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrViewMode
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.TreeToggles
import com.zillit.desktop.feature.costreport.domain.WeekWindow
import com.zillit.desktop.feature.costreport.domain.currentWeek
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The accountant's Cost Report — the web's `CostReportWorksheetModule`, the
 * Account Hub's REPORTS → Cost Report.
 *
 * Two panes over the same service: the **worksheet**, where ETC, EFC and VTP
 * are typed, saved as weekly versions, posted, locked and exported; and the
 * **Live CR**, a read-only view with its own filters and the posted history.
 * Each pane keeps its own picked-versus-computed filters, so changing one
 * never re-fetches the other.
 *
 * Collaborators carry the actions so this class stays the loader:
 * [WorksheetEditActions] (compute, typing, versions), [WorksheetPublishActions]
 * (publish, lock, export) and [WorksheetSnapshotActions] (history, snapshots,
 * the ledger).
 */
@Suppress("TooManyFunctions") // The loader plus one small handler per grid control.
class WorksheetViewModel(
    internal val repository: CostReportRepository,
    internal val exporter: CostReportExporter,
    internal val files: CostReportFiles,
    private val resolveViewer: () -> CostReportViewer,
    private val projectName: () -> String,
    /** The production company's name, credited on an export. */
    internal val companyName: () -> String? = { null },
    /** "Name · Designation" of whoever is signed in. */
    private val signedInUser: () -> String? = { null },
    /** "Name · Designation" for a user id, or null when unknown. */
    internal val resolveUser: (String) -> String? = { null },
    internal val nowMillis: () -> Long,
) : ZillitViewModel<WorksheetUiState, WorksheetEvent, WorksheetEffect>(WorksheetUiState()) {

    internal fun update(reducer: WorksheetUiState.() -> WorksheetUiState) = setState(reducer)
    internal fun launchWork(block: suspend () -> Unit): Job = launch { block() }
    internal fun emit(effect: WorksheetEffect) = sendEffect(effect)
    internal fun notice(text: String, error: Boolean = false) = sendEffect(WorksheetEffect.Notice(text, error))
    internal val ui: WorksheetUiState get() = currentState

    private val loader = CrReportLoader(repository)
    private val edits = WorksheetEditActions(this)
    private val publish = WorksheetPublishActions(this)
    private val snapshots = WorksheetSnapshotActions(this)

    private val paneJobs = mutableMapOf<WorksheetPane, Job>()
    private var listening = false
    private var syncJob: Job? = null

    fun start() {
        update { copy(viewer = resolveViewer(), projectName = projectName(), generatedBy = signedInUser()) }
        listenOnce()
        if (!ui.reference.loaded && !ui.reference.loading) loadReference()
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    override fun onEvent(event: WorksheetEvent) {
        if (edits.handles(event) || publish.handles(event) || snapshots.handles(event)) return
        when (event) {
            is WorksheetEvent.SelectPane -> {
                update { copy(pane = event.pane) }
                if (event.pane == WorksheetPane.Live && ui.liveTab == LiveTab.History) snapshots.refreshList()
            }
            is WorksheetEvent.SelectLiveTab -> {
                update { copy(liveTab = event.tab) }
                if (event.tab == LiveTab.History) snapshots.refreshList()
            }
            WorksheetEvent.Back -> emit(WorksheetEffect.Back)
            WorksheetEvent.OpenAnalytics -> emit(WorksheetEffect.OpenAnalytics)
            WorksheetEvent.RefreshSource -> {
                update { copy(sourceStale = false) }
                silentRefresh()
            }
            WorksheetEvent.Retry -> if (ui.reference.error != null) loadReference() else reloadBoth()
            WorksheetEvent.DismissProgress -> update { copy(progress = null) }
            WorksheetEvent.DismissExport -> update { copy(exporting = false, exportDone = false) }
            is WorksheetEvent.SetCompany -> pending(event.pane) { copy(companyId = event.companyId) }
            is WorksheetEvent.SetBudget -> pending(event.pane) { copy(budgetKey = event.budgetKey) }
            is WorksheetEvent.SetCurrency -> pending(event.pane) { copy(currency = event.code) }
            is WorksheetEvent.SetVersion -> pending(event.pane) { copy(versionId = event.versionId) }
            is WorksheetEvent.StepWeek -> stepWeek(event.pane, event.forward)
            is WorksheetEvent.GoToCurrentWeek -> moveToWeek(event.pane, currentWeek(nowMillis()))
            is WorksheetEvent.Search -> view(event.pane) { copy(search = event.query) }
            is WorksheetEvent.ToggleSection -> view(event.pane) { copy(toggles = toggles.toggleSection(event.id)) }
            is WorksheetEvent.ToggleHeader -> view(event.pane) { copy(toggles = toggles.toggleHeader(event.code)) }
            is WorksheetEvent.ToggleNominal -> view(
                event.pane,
            ) { copy(toggles = toggles.toggleNominal(event.identity)) }
            is WorksheetEvent.SetDecimals -> view(WorksheetPane.Worksheet) { copy(decimals = event.decimals) }
            is WorksheetEvent.SetViewMode -> setViewMode(event.mode)
            WorksheetEvent.ToggleExpandAll -> toggleExpandAll()
            is WorksheetEvent.SetFilter -> view(WorksheetPane.Worksheet) { copy(filter = event.filter) }
            is WorksheetEvent.SortBy -> view(WorksheetPane.Worksheet) { copy(sort = sort.toggled(event.column)) }
            WorksheetEvent.ClearSort -> view(WorksheetPane.Worksheet) { copy(sort = CrSort()) }
            WorksheetEvent.ClearFlat -> view(
                WorksheetPane.Worksheet,
            ) { copy(sort = CrSort(), filter = CrLineFilter.All) }
            else -> Unit
        }
    }

    // -- pane helpers -----------------------------------------------------------------------

    internal fun pane(pane: WorksheetPane): CrPane = when (pane) {
        WorksheetPane.Worksheet -> ui.ws
        WorksheetPane.Live -> ui.live
    }

    internal fun updatePane(pane: WorksheetPane, change: CrPane.() -> CrPane) = update {
        when (pane) {
            WorksheetPane.Worksheet -> copy(ws = ws.change())
            WorksheetPane.Live -> copy(live = live.change())
        }
    }

    private fun pending(pane: WorksheetPane, change: CrFilters.() -> CrFilters) =
        updatePane(pane) { copy(pending = pending.change()) }

    private fun view(pane: WorksheetPane, change: CrTableView.() -> CrTableView) = update {
        when (pane) {
            WorksheetPane.Worksheet -> copy(wsView = wsView.change())
            WorksheetPane.Live -> copy(liveView = liveView.change())
        }
    }

    private fun setViewMode(mode: CrViewMode) = view(WorksheetPane.Worksheet) {
        copy(viewMode = mode, toggles = TreeToggles.forViewMode(toggles, mode, ui.ws.sections))
    }

    private fun toggleExpandAll() = view(WorksheetPane.Worksheet) {
        val sections = ui.ws.sections
        copy(
            toggles = if (toggles.allHeadersOpen(sections)) {
                TreeToggles.collapsed(sections)
            } else {
                TreeToggles.expanded(sections)
            },
        )
    }

    /** The stepper moves the picked and the computed week together, and never past this week. */
    private fun stepWeek(pane: WorksheetPane, forward: Boolean) {
        val week = pane(pane).week ?: return
        val now = currentWeek(nowMillis())
        if (forward && week.startMs >= now.startMs) return
        moveToWeek(pane, if (forward) week.next() else week.previous())
    }

    private fun moveToWeek(pane: WorksheetPane, week: WeekWindow) {
        if (pane(pane).week == week) return
        updatePane(pane) { copy(week = week) }
        if (!ui.reference.metaMissing && ui.reference.loaded) {
            loadPane(pane)
            refreshVersions(pane)
        }
    }

    // -- reference ----------------------------------------------------------------------------

    /**
     * Everything both panes stand on, together: the chart and budgets (without
     * which nothing can run), companies, currencies, the lock and the posted
     * list — then both panes for this week.
     */
    private fun loadReference() {
        update {
            copy(
                reference = reference.copy(loading = true, error = null),
                ws = ws.copy(phase = CrPhase.Init),
                live = live.copy(phase = CrPhase.Init),
            )
        }
        launch {
            val fetched = coroutineScope {
                val coa = async { repository.chartOfAccounts() }
                val budgets = async { repository.budgets() }
                val companies = async { repository.companies() }
                val currencies = async { repository.currencies() }
                val lock = async { repository.lockState() }
                val posted = async { repository.snapshots(null) }
                Fetched(
                    coa.await(),
                    budgets.await(),
                    companies.await(),
                    currencies.await(),
                    lock.await(),
                    posted.await(),
                )
            }
            val failure = fetched.coa.errorOrNull() ?: fetched.budgets.errorOrNull()
            if (failure != null) {
                update {
                    copy(
                        reference = reference.copy(loading = false, error = failure.localised()),
                        ws = ws.copy(phase = null, loadedOnce = true),
                        live = live.copy(phase = null, loadedOnce = true),
                    )
                }
                return@launch
            }
            applyReference(fetched)
        }
    }

    private suspend fun applyReference(fetched: Fetched) {
        val currencies = fetched.currencies.getOrNull() ?: CurrencyOptions()
        val catalogue = if (currencies.currencies.isEmpty() || currencies.currencies.any { it.symbol.isBlank() }) {
            repository.currencyCatalogue().getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val reference = CrReference(
            loading = false,
            loaded = true,
            coa = fetched.coa.getOrNull().orEmpty(),
            budgets = fetched.budgets.getOrNull().orEmpty(),
            companies = fetched.companies.getOrNull().orEmpty(),
            currencies = currencies,
            catalogue = catalogue,
        )
        val codes = currencies.currencies.map { it.code }
        val currency = currencies.defaultCode?.takeIf { it in codes || codes.isEmpty() } ?: codes.firstOrNull()
        val filters = CrFilters(budgetKey = reference.initialBudgetKey, currency = currency)
        val week = currentWeek(nowMillis())
        update {
            copy(
                reference = reference,
                ws = ws.copy(pending = filters, applied = filters, week = week, phase = null),
                live = live.copy(pending = filters, applied = filters, week = week, phase = null),
                lock = fetched.lock.getOrNull() ?: CrLockState(loaded = true),
                snapshots = snapshots.copy(rows = fetched.posted.getOrNull().orEmpty(), loadedOnce = true),
            )
        }
        if (reference.metaMissing) {
            update { copy(ws = ws.copy(loadedOnce = true), live = live.copy(loadedOnce = true)) }
            return
        }
        reloadBoth()
        refreshVersions(WorksheetPane.Worksheet)
        refreshVersions(WorksheetPane.Live)
    }

    private fun reloadBoth() {
        loadPane(WorksheetPane.Worksheet)
        loadPane(WorksheetPane.Live)
    }

    // -- a pane's figures ------------------------------------------------------------------------

    /**
     * One pane's week, for its applied filters — from the week's posted
     * snapshot when there is one in this currency, live otherwise — with the
     * VTP baseline beside it. A [silent] re-pull keeps the figures on screen.
     */
    internal fun loadPane(pane: WorksheetPane, silent: Boolean = false) {
        val state = ui
        val current = pane(pane)
        val week = current.week ?: return
        if (!state.reference.loaded || state.reference.metaMissing) return
        val applied = current.applied
        val defaultCurrency = state.reference.currencies.defaultCode
        val posted = state.snapshots.rows
        val fromSnapshot = posted.any { it.periodStartMs == week.startMs } &&
            (posted.filter { it.periodStartMs == week.startMs }.maxByOrNull { it.generatedAtMs ?: 0L }?.currency
                ?: defaultCurrency) == (applied.currency ?: defaultCurrency)
        updatePane(pane) {
            copy(phase = if (fromSnapshot) CrPhase.Snapshot else CrPhase.Live, silent = silent, error = null)
        }
        paneJobs[pane]?.cancel()
        paneJobs[pane] = launch {
            val baseline = async { loader.baseline(posted, week) }
            val result = loader.load(
                coa = state.reference.coa,
                week = week,
                budget = state.reference.budget(applied.budgetKey),
                companyId = applied.companyId,
                currency = applied.currency,
                defaultCurrency = defaultCurrency,
                snapshots = posted,
            )
            when (result) {
                is ZillitResult.Failure -> {
                    baseline.cancel()
                    updatePane(pane) {
                        copy(
                            phase = null,
                            silent = false,
                            computing = false,
                            loadedOnce = true,
                            error = result.error.localised(),
                        )
                    }
                }
                is ZillitResult.Success -> {
                    val prior = baseline.await()
                    updatePane(pane) {
                        copy(
                            phase = null,
                            silent = false,
                            computing = false,
                            loadedOnce = true,
                            sections = result.data.sections,
                            serverCurrency = result.data.serverCurrency,
                            fromSnapshot = result.data.fromSnapshot,
                            baseline = prior,
                        )
                    }
                }
            }
        }
    }

    /** The versions saved for a pane's week — its Version picker. Best effort. */
    internal fun refreshVersions(pane: WorksheetPane, then: suspend () -> Unit = {}) {
        val week = pane(pane).week ?: return
        launch {
            val versions = repository.etcVersions(week.weekEnding).getOrNull()
            if (versions != null && pane(pane).week == week) updatePane(pane) { copy(versions = versions) }
            then()
        }
    }

    /**
     * A re-pull nobody asked for — a lock or a post landed elsewhere, or the
     * stale pill was pressed: the list, the lock and both panes, with the
     * figures left on screen rather than blanked under a loader.
     */
    internal fun silentRefresh() {
        launch {
            repository.snapshots(
                null,
            ).getOrNull()?.let { rows -> update { copy(snapshots = snapshots.copy(rows = rows)) } }
            repository.lockState().getOrNull()?.let { lock -> update { copy(lock = lock) } }
            loadPane(WorksheetPane.Worksheet, silent = true)
            loadPane(WorksheetPane.Live, silent = true)
        }
    }

    internal fun refreshLock() {
        launch { repository.lockState().getOrNull()?.let { lock -> update { copy(lock = lock) } } }
    }

    // -- the socket ---------------------------------------------------------------------------------

    /**
     * A lock or a post elsewhere re-pulls silently, debounced because a post
     * emits both events back to back; a feeder tool's approval only raises
     * the stale pill — the recompute is heavy, so the reader picks the moment.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.syncs.collect { sync ->
                when (sync) {
                    CostReportSync.Source -> update { copy(sourceStale = true) }
                    CostReportSync.Report -> {
                        syncJob?.cancel()
                        syncJob = launch {
                            delay(SYNC_DEBOUNCE_MILLIS)
                            silentRefresh()
                        }
                    }
                }
            }
        }
    }

    private class Fetched(
        val coa: ZillitResult<List<CoaRow>>,
        val budgets: ZillitResult<List<BudgetVersion>>,
        val companies: ZillitResult<List<CrCompany>>,
        val currencies: ZillitResult<CurrencyOptions>,
        val lock: ZillitResult<CrLockState>,
        val posted: ZillitResult<List<SnapshotHeader>>,
    )

    companion object {
        /** The web's refetch coalescing window — `accountHubListeners.js` `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}
