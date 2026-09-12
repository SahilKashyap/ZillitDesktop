@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.costreport.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrReportLoader
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.currentWeek
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Cost Report, the crew-facing film tool (`/film-tools/cost-report`): the
 * current week read-only, with its own Company · Budget · Currency filters
 * that apply as they change, and the timeline of posted snapshots. The
 * accountant's editable worksheet is [com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetViewModel].
 */
class CostReportViewModel(
    private val repository: CostReportRepository,
    private val exporter: CostReportExporter,
    private val files: CostReportFiles,
    private val resolveViewer: () -> CostReportViewer,
    private val projectName: () -> String,
    private val resolveUser: (String) -> String?,
    private val nowMillis: () -> Long,
) : ZillitViewModel<CostReportUiState, CostReportEvent, CostReportEffect>(CostReportUiState()) {

    private var liveJob: Job? = null
    private val loader = CrReportLoader(repository)

    fun start() {
        setState { copy(viewer = resolveViewer(), projectName = projectName()) }
        listenOnce()
        loadReference()
    }

    /**
     * Folds the socket's announcements into the screen. A `Report` sync — the
     * report locked or posted elsewhere — re-pulls whatever tab is open (and
     * the posted timeline if it has been fetched, since a post grows it); a
     * `Source` sync only raises the stale pill, exactly as the web does —
     * see [CostReportSync]. Guarded so reopening the window does not stack
     * collectors, and the reload debounced because a post emits both events
     * back to back (the web coalesces at `accountHubListeners.js`
     * `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.syncs.collect { sync ->
                when (sync) {
                    CostReportSync.Source -> setState { copy(sourceStale = true) }
                    CostReportSync.Report -> {
                        syncJob?.cancel()
                        syncJob = launch {
                            delay(SYNC_DEBOUNCE_MILLIS)
                            refresh()
                            if (currentState.tab != CostReportTab.Posted && currentState.posted.loadedOnce) {
                                loadPosted()
                            }
                        }
                    }
                }
            }
        }
    }

    private var listening = false
    private var syncJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    override fun onEvent(event: CostReportEvent) {
        when (event) {
            is CostReportEvent.SelectTab -> selectTab(event.tab)
            CostReportEvent.Refresh -> refresh()
            CostReportEvent.DismissError -> setState {
                copy(
                    referenceError = null,
                    current = current.copy(error = null),
                    posted = posted.copy(error = null),
                    snapshot = snapshot?.copy(error = null),
                )
            }
            is CostReportEvent.SelectCompany -> refilter { copy(companyId = event.companyId) }
            is CostReportEvent.SelectBudget -> refilter { copy(budgetKey = event.budgetKey) }
            is CostReportEvent.SelectCurrency -> refilter { copy(currencyCode = event.code) }
            is CostReportEvent.SearchCurrent -> setState { copy(current = current.copy(query = event.query)) }
            is CostReportEvent.ToggleSection -> setState {
                copy(current = current.copy(toggles = current.toggles.toggleSection(event.sectionId)))
            }
            is CostReportEvent.ToggleHeader -> setState {
                copy(current = current.copy(toggles = current.toggles.toggleHeader(event.code)))
            }
            is CostReportEvent.ToggleNominal -> setState {
                copy(current = current.copy(toggles = current.toggles.toggleNominal(event.identity)))
            }
            is CostReportEvent.OpenLedger -> openLedger(event.nominal, event.column)
            CostReportEvent.CloseLedger -> setState { copy(ledger = null) }
            is CostReportEvent.SelectPostedFilter -> setState { copy(posted = posted.copy(filter = event.filter)) }
            CostReportEvent.RefreshPosted -> loadPosted()
            is CostReportEvent.OpenSnapshot -> openSnapshot(event.header)
            CostReportEvent.CloseSnapshot -> setState { copy(snapshot = null) }
            is CostReportEvent.SearchSnapshot -> setState { copy(snapshot = snapshot?.copy(query = event.query)) }
            is CostReportEvent.ToggleSnapshotSection -> setState {
                copy(snapshot = snapshot?.let { it.copy(toggles = it.toggles.toggleSection(event.sectionId)) })
            }
            is CostReportEvent.ToggleSnapshotHeader -> setState {
                copy(snapshot = snapshot?.let { it.copy(toggles = it.toggles.toggleHeader(event.code)) })
            }
            is CostReportEvent.Export -> export(event.format)
            CostReportEvent.OpenAnalytics -> sendEffect(CostReportEffect.OpenAnalytics)
        }
    }

    private fun selectTab(tab: CostReportTab) {
        setState { copy(tab = tab, snapshot = null, ledger = null) }
        if (tab == CostReportTab.Posted && !state.value.posted.loadedOnce && !state.value.posted.loading) loadPosted()
    }

    private fun refresh() {
        // Any full re-pull answers the stale pill, whoever pressed it.
        setState { copy(sourceStale = false) }
        when (state.value.tab) {
            CostReportTab.Current -> {
                val current = state.value.current
                val liveOnly = current.loaded && !current.unavailable && state.value.referenceError == null
                if (liveOnly) loadLive(refresh = true) else loadReference()
            }
            CostReportTab.Posted -> loadPosted()
        }
    }

    private fun refilter(change: CurrentCr.() -> CurrentCr) {
        setState { copy(current = current.change()) }
        if (!state.value.current.unavailable && !state.value.referenceLoading) loadLive(refresh = true)
    }

    // -- reference data --------------------------------------------------------

    /** COA, budgets, companies and currencies, together; the first two decide whether the tool can run at all. */
    private fun loadReference() {
        setState { copy(referenceLoading = true, referenceError = null, current = current.copy(phase = PHASE_INIT)) }
        launch {
            val loaded = coroutineScope {
                val coa = async { repository.chartOfAccounts() }
                val budgets = async { repository.budgets() }
                val companies = async { repository.companies() }
                val currencies = async { repository.currencies() }
                Reference(coa.await(), budgets.await(), companies.await(), currencies.await())
            }
            val failure = loaded.coa.errorOrNull() ?: loaded.budgets.errorOrNull()
            if (failure != null) {
                setState {
                    copy(
                        referenceLoading = false,
                        referenceError = failure.userMessage,
                        current = current.copy(phase = null),
                    )
                }
                return@launch
            }
            val coa = loaded.coa.getOrNull().orEmpty()
            val budgets = loaded.budgets.getOrNull().orEmpty()
            val currencies = loaded.currencies.getOrNull() ?: CurrencyOptions()
            val catalogue = if (currencies.currencies.isEmpty() || currencies.currencies.any { it.symbol.isBlank() }) {
                repository.currencyCatalogue().getOrNull().orEmpty()
            } else {
                emptyList()
            }
            applyReference(coa, budgets, loaded, currencies, catalogue)
        }
    }

    private fun applyReference(
        coa: List<CoaRow>,
        budgets: List<BudgetVersion>,
        loaded: Reference,
        currencies: CurrencyOptions,
        catalogue: List<CrCurrency>,
    ) {
        val unavailable = coa.isEmpty() || budgets.isEmpty()
        setState {
            val kept = current.budgetKey?.takeIf { key -> budgets.any { it.version == key } }
            val budgetKey = kept ?: pickInitialBudgetKey(budgets)
            copy(
                referenceLoading = false,
                coa = coa,
                currencyCatalogue = catalogue,
                current = current.copy(
                    phase = null,
                    unavailable = unavailable,
                    companies = loaded.companies.getOrNull().orEmpty(),
                    budgets = budgets,
                    budgetKey = budgetKey,
                    currencies = currencies.currencies,
                    currencyCode = current.currencyCode ?: currencies.defaultCode
                        ?: currencies.currencies.firstOrNull()?.code,
                ),
            )
        }
        if (!unavailable) loadLive(refresh = false)
    }

    // -- live ------------------------------------------------------------------

    private fun loadLive(refresh: Boolean) {
        val now = nowMillis()
        val week = currentWeek(now)
        val current = state.value.current
        val phase = if (refresh) PHASE_REFRESH else PHASE_LIVE
        setState { copy(current = this.current.copy(phase = phase, error = null, week = week, todayMs = now)) }
        liveJob?.cancel()
        liveJob = launch {
            // The same list feeds the week's own posted snapshot and the VTP baseline.
            val posted = repository.snapshots(null).getOrNull().orEmpty()
            val baseline = async { loader.baseline(posted, week) }
            val result = loader.load(
                coa = state.value.coa,
                week = week,
                budget = current.selectedBudget,
                companyId = current.companyId,
                currency = current.currencyCode,
                defaultCurrency = null,
                snapshots = posted,
            )
            when (result) {
                is ZillitResult.Failure -> {
                    baseline.cancel()
                    setState { copy(current = this.current.copy(phase = null, error = result.error.localised())) }
                }
                is ZillitResult.Success -> {
                    val prior = baseline.await()
                    val shown = result.data
                    setState {
                        copy(
                            current = this.current.copy(
                                phase = null,
                                report = LiveReport(emptyList(), currency = shown.serverCurrency),
                                sections = shown.sections,
                                baseline = prior,
                                fromSnapshot = shown.fromSnapshot,
                                symbol = symbolFor(shown.serverCurrency ?: this.current.currencyCode),
                            ),
                        )
                    }
                }
            }
        }
    }

    // -- posted ----------------------------------------------------------------

    private fun loadPosted() {
        setState { copy(posted = posted.copy(loading = true, error = null)) }
        launch {
            when (val result = repository.snapshots(null)) {
                is ZillitResult.Failure -> setState {
                    copy(posted = posted.copy(loading = false, loadedOnce = true, error = result.error.localised()))
                }
                is ZillitResult.Success -> setState {
                    copy(posted = posted.copy(loading = false, loadedOnce = true, rows = result.data))
                }
            }
        }
    }

    private fun openSnapshot(header: SnapshotHeader) {
        setState {
            copy(snapshot = SnapshotView(header = header, symbol = symbolFor(header.currency ?: current.currencyCode)))
        }
        launch {
            when (val result = repository.snapshot(header.id)) {
                is ZillitResult.Failure -> setState {
                    val open = snapshot?.takeIf { it.header.id == header.id } ?: return@setState this
                    copy(snapshot = open.copy(loading = false, error = result.error.localised()))
                }
                is ZillitResult.Success -> setState {
                    val open = snapshot?.takeIf { it.header.id == header.id } ?: return@setState this
                    copy(
                        snapshot = open.copy(
                            loading = false,
                            detail = result.data,
                            sections = buildSections(coa, result.data.lines),
                            symbol = symbolFor(result.data.header.currency ?: current.currencyCode),
                        ),
                    )
                }
            }
        }
    }

    private fun export(format: ExportFormat) {
        val open = state.value.snapshot ?: return
        if (open.exporting != null) return
        val header = open.shownHeader
        setState { copy(snapshot = snapshot?.copy(exporting = format)) }
        launch {
            val body = buildJsonObject {
                put("project_name", JsonPrimitive(state.value.projectName))
                state.value.current.companies.firstOrNull { it.id == header.companyId }?.let {
                    put("company_name", JsonPrimitive(it.name))
                }
                header.postedBy?.let { put("generated_by", JsonPrimitive(resolveUser(it) ?: it)) }
            }
            val outcome = when (val bytes = exporter.export(header.id, format, body)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> files.saveAndOpen(exportFileName(header, format), bytes.data)
            }
            setState { copy(snapshot = snapshot?.copy(exporting = null, error = outcome.errorOrNull()?.userMessage)) }
            if (outcome is ZillitResult.Success) sendEffect(CostReportEffect.Notice("${format.label} downloaded"))
        }
    }

    // -- ledger ----------------------------------------------------------------

    /** Contractual rows have no ledger; every other row opens, Non-Allocated ones included. */
    private fun openLedger(nominal: CrNominal, column: CrColumn?) {
        if (nominal.isContractual) return
        val current = state.value.current
        setState {
            copy(
                ledger = LedgerView(
                    nominal = nominal,
                    type = column?.ledgerType,
                    source = column?.ledgerSource,
                    budget = nominal.line.budget,
                    symbol = current.symbol,
                ),
            )
        }
        launch {
            val result = repository.accountLineItems(
                code = nominal.apiCode,
                type = column?.ledgerType,
                source = column?.ledgerSource,
                currency = current.currencyCode,
            )
            setState {
                val open = ledger?.takeIf { it.nominal.identity == nominal.identity } ?: return@setState this
                when (result) {
                    is ZillitResult.Failure ->
                        copy(ledger = open.copy(loading = false, error = result.error.localised()))
                    is ZillitResult.Success ->
                        copy(ledger = open.copy(loading = false, result = result.data))
                }
            }
        }
    }

    private class Reference(
        val coa: ZillitResult<List<CoaRow>>,
        val budgets: ZillitResult<List<BudgetVersion>>,
        val companies: ZillitResult<List<CrCompany>>,
        val currencies: ZillitResult<CurrencyOptions>,
    )

    companion object {
        private const val PHASE_INIT = "Loading…"
        private const val PHASE_LIVE = "Computing live report"
        private const val PHASE_REFRESH = "Refreshing live report…"

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}

/** LIVE → APPROVED → first, keyed by `version` (spec §4.2). */
fun pickInitialBudgetKey(budgets: List<BudgetVersion>): String? =
    (budgets.firstOrNull { it.status == "LIVE" } ?: budgets.firstOrNull { it.status == "APPROVED" }
        ?: budgets.firstOrNull())?.version

/** `{reference || name}.{fmt}`, with path-hostile characters flattened. */
fun exportFileName(header: SnapshotHeader, format: ExportFormat): String {
    val stem = header.reference.ifBlank { header.name }.ifBlank { "cr-snapshot" }
    val safe = stem.replace(Regex("""[\\/:*?"<>|]"""), "-").trim()
    return "$safe.${format.wire}"
}
