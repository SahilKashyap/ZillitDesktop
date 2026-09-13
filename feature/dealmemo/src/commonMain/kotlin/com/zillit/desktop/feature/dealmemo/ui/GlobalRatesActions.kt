package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/**
 * Global Production Rates (`DMConfigPage.jsx` + the module's full-page chrome).
 *
 * Read-only apart from Refresh data. Every load cancels the one it replaces:
 * the web lets a slow territory's unions land under a newer territory's
 * heading, and this does not.
 */
internal class GlobalRatesActions(private val vm: DealMemoViewModel) {

    private var coverageJob: Job? = null
    private var territoryJob: Job? = null
    private var agreementsJob: Job? = null
    private var agreementJob: Job? = null
    private var ratesJob: Job? = null
    private val statusJobs = mutableMapOf<String, Job>()

    @Suppress("CyclomaticComplexMethod") // One branch per navigation step.
    fun onEvent(event: RatesEvent) {
        when (event) {
            is RatesEvent.SidebarSearch -> edit { copy(sidebarSearch = event.query) }
            is RatesEvent.SelectTerritory -> selectTerritory(event.territoryId)
            RatesEvent.ViewAgreements -> viewTerritoryAgreements()
            is RatesEvent.SelectBranch -> selectBranch(event.branch)
            is RatesEvent.SelectAgreement -> selectAgreement(event.agreement.identifier, event.origin)
            is RatesEvent.TerritoryFilter -> edit { copy(territoryFilter = event.query) }
            is RatesEvent.AgreementsFilter -> edit { copy(agreementsFilter = event.query) }
            RatesEvent.BackToWelcome -> {
                cancelViews()
                edit { copy(view = RatesView.Welcome, territoryId = null, territoryFilter = "") }
            }
            RatesEvent.BackToTerritory -> backToTerritory()
            RatesEvent.BackFromAgreement -> backFromAgreement()
            RatesEvent.Refresh -> refresh()
        }
    }

    /** Opening the page: the coverage the sidebar and welcome pane are built from. */
    fun enter() {
        if (vm.ui.rates.coveredLoading || vm.ui.rates.covered.isEmpty()) loadCoverage()
    }

    private fun loadCoverage() {
        coverageJob?.cancel()
        edit { copy(coveredLoading = true) }
        coverageJob = vm.work {
            // A failure fails open: an empty set lists every territory.
            val covered = vm.reference.coveredTerritories().getOrNull().orEmpty()
            edit { copy(coveredLoading = false, covered = covered) }
        }
    }

    /** Re-clicking the open territory runs it all again, as the web does. */
    private fun selectTerritory(id: String) {
        cancelViews()
        edit {
            copy(
                view = RatesView.Territory,
                territoryId = id,
                unions = emptyList(),
                branches = emptyList(),
                branch = null,
                agreements = emptyList(),
                agreement = null,
                agreementFailed = false,
                unionsLoading = true,
                territoryFilter = "",
                agreementsFilter = "",
            )
        }
        territoryJob = vm.work {
            val unions = async { vm.reference.unions(id) }
            val branches = async { vm.reference.branches(id) }
            val unionRows = unions.await().getOrNull().orEmpty()
            val branchRows = branches.await().getOrNull().orEmpty()
            edit { copy(unions = unionRows, branches = branchRows, unionsLoading = false) }
        }
    }

    private fun viewTerritoryAgreements() {
        val territory = vm.ui.rates.territoryId ?: return
        agreementsJob?.cancel()
        edit {
            copy(
                view = RatesView.AgreementsList,
                branch = null,
                agreements = emptyList(),
                agreement = null,
                agreementsLoading = true,
                agreementsFilter = "",
            )
        }
        agreementsJob = vm.work {
            val listing = vm.reference.agreements(territory = territory).getOrNull()
            listing?.let { rememberStatuses(territory, it.empStatuses) }
            edit { copy(agreements = listing?.agreements.orEmpty(), agreementsLoading = false) }
        }
    }

    /** The "union" view is a branch: its governing agreements, its territory's statuses, its rate card. */
    private fun selectBranch(branch: Branch) {
        agreementsJob?.cancel()
        edit {
            copy(
                view = RatesView.Branch,
                branch = branch,
                agreements = emptyList(),
                agreement = null,
                agreementsLoading = true,
            )
        }
        agreementsJob = vm.work {
            val listing = vm.reference.agreements(branchIdentifier = branch.identifier).getOrNull()
            edit { copy(agreements = listing?.agreements.orEmpty(), agreementsLoading = false) }
        }
        loadStatuses(branch.territory)
        loadRates(branch)
    }

    private fun selectAgreement(identifier: String, origin: AgreementOrigin) {
        agreementJob?.cancel()
        edit {
            copy(
                view = RatesView.Agreement,
                agreementOrigin = origin,
                agreement = null,
                agreementLoading = true,
                agreementFailed = false,
            )
        }
        agreementJob = vm.work {
            when (val result = vm.reference.agreement(identifier)) {
                is ZillitResult.Success -> {
                    edit { copy(agreement = result.data, agreementLoading = false) }
                    loadStatuses(result.data.territory)
                }
                is ZillitResult.Failure -> edit { copy(agreementLoading = false, agreementFailed = true) }
            }
        }
    }

    /** Back to the territory keeps its unions and branches — no refetch. */
    private fun backToTerritory() {
        agreementsJob?.cancel()
        agreementJob?.cancel()
        ratesJob?.cancel()
        edit {
            copy(
                view = RatesView.Territory,
                branch = null,
                agreements = emptyList(),
                agreement = null,
                agreementFailed = false,
                agreementsFilter = "",
            )
        }
    }

    /** Back to where the agreement was opened from, on that view's cached list; a branch's rate card refetches. */
    private fun backFromAgreement() {
        agreementJob?.cancel()
        val state = vm.ui.rates
        when (state.agreementOrigin) {
            AgreementOrigin.Branch -> {
                edit { copy(view = RatesView.Branch, agreement = null, agreementFailed = false) }
                state.branch?.let(::loadRates)
            }
            AgreementOrigin.AgreementsList, null ->
                edit { copy(view = RatesView.AgreementsList, agreement = null, agreementFailed = false) }
        }
    }

    private fun loadRates(branch: Branch) {
        ratesJob?.cancel()
        edit { copy(rates = emptyList(), ratesLoading = true) }
        ratesJob = vm.work {
            val rows = vm.reference.designationRates(branch.identifier).getOrNull().orEmpty()
            edit { copy(rates = rows, ratesLoading = false) }
        }
    }

    /** A territory's employment statuses, fetched once per territory until a refresh. */
    private fun loadStatuses(territory: String?) {
        val key = territory?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        if (key in vm.ui.rates.empStatuses || statusJobs[key]?.isActive == true) return
        statusJobs[key] = vm.work {
            vm.reference.agreements(territory = key).getOrNull()?.let { rememberStatuses(key, it.empStatuses) }
        }
    }

    private fun rememberStatuses(
        territory: String,
        statuses: List<EmpStatus>,
    ) {
        edit { copy(empStatuses = empStatuses + (territory.lowercase() to statuses)) }
    }

    /**
     * Re-seeds the global catalogue. No confirmation, as on the web. Success
     * resets the page to the welcome pane and reloads coverage (the search
     * stays); a failure changes nothing.
     */
    private fun refresh() {
        val state = vm.ui.rates
        if (state.refreshing || !vm.ui.rights.canPost) return
        edit { copy(refreshing = true) }
        vm.work {
            when (val result = vm.reference.importDefaults()) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "rates_refreshed")
                    cancelViews()
                    statusJobs.values.forEach { it.cancel() }
                    statusJobs.clear()
                    edit {
                        GlobalRatesState(sidebarSearch = sidebarSearch, refreshing = false)
                    }
                    loadCoverage()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "something_went_wrong")
                    edit { copy(refreshing = false) }
                }
            }
        }
    }

    private fun cancelViews() {
        territoryJob?.cancel()
        agreementsJob?.cancel()
        agreementJob?.cancel()
        ratesJob?.cancel()
    }

    private fun edit(reducer: GlobalRatesState.() -> GlobalRatesState) = vm.update { copy(rates = rates.reducer()) }
}
