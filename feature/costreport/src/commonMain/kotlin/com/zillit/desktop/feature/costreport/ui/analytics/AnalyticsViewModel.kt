package com.zillit.desktop.feature.costreport.ui.analytics

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFilterSource
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsOption
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsQuery
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/**
 * The cost report's Analytics page — the web's `AnalyticsDesign`, reached
 * from both the accountant's worksheet and the crew report.
 *
 * Three reads drive it: the module strip, and either the overview or one
 * module's blocks. The strip follows the filters; the content follows the
 * filters and the selection; a module's tabs are fetched one at a time as
 * they are opened and kept until the module or the filters change. Filter
 * picks batch in a draft and fetch nothing until Done, and an unchanged
 * selection never re-fetches.
 */
class AnalyticsViewModel(
    private val repository: AnalyticsRepository,
    private val filterSource: AnalyticsFilterSource,
) : ZillitViewModel<AnalyticsUiState, AnalyticsEvent, AnalyticsEffect>(AnalyticsUiState()) {

    private var started = false
    private var modulesJob: Job? = null
    private var contentJob: Job? = null
    private var tabJob: Job? = null

    /** Bumped per request, so an answer that lost a race is dropped rather than drawn. */
    private var modulesToken = 0
    private var contentToken = 0
    private var tabToken = 0

    /**
     * First entry loads the choices, then the page in the project's default
     * currency. Coming back re-reads what is on screen — the web remounts the
     * page each visit — but keeps the selection and the filters.
     */
    fun start() {
        if (started) {
            currentState.applied?.let { query ->
                loadModules(query)
                loadContent()
            }
            return
        }
        started = true
        launch {
            val companies = async { filterSource.companies() }
            val departments = async { filterSource.departments() }
            val units = async { filterSource.units() }
            applyCurrencies()
            val initial = currentState.draft.toQuery(currentState.options)
            setState { copy(applied = initial) }
            loadModules(initial)
            loadContent()
            val entities = companies.await().map { AnalyticsOption(it.id, it.labelWithCountry) }
            val departmentOptions = departments.await()
            val unitOptions = units.await()
            setState {
                copy(options = options.copy(entities = entities, departments = departmentOptions, units = unitOptions))
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per control on the page.
    override fun onEvent(event: AnalyticsEvent) {
        when (event) {
            AnalyticsEvent.Back -> sendEffect(AnalyticsEffect.Back)
            AnalyticsEvent.Retry -> loadContent()
            is AnalyticsEvent.Select -> select(event.id)
            is AnalyticsEvent.SelectTab -> selectTab(event.id)
            is AnalyticsEvent.OpenLink -> sendEffect(AnalyticsEffect.OpenLink(event.href))
            AnalyticsEvent.ToggleFilters -> setState { copy(filtersOpen = !filtersOpen) }
            AnalyticsEvent.CloseFilters -> setState { copy(filtersOpen = false) }
            is AnalyticsEvent.SetPeriod -> draft { copy(period = event.period) }
            is AnalyticsEvent.SetDateFrom -> draft { copy(dateFrom = event.date) }
            is AnalyticsEvent.SetDateTo -> draft { copy(dateTo = event.date) }
            is AnalyticsEvent.SetCurrency -> draft { copy(currency = event.code) }
            is AnalyticsEvent.SetEntity -> draft { copy(entity = event.value) }
            is AnalyticsEvent.SetDepartments -> draft { copy(departmentIds = event.ids) }
            is AnalyticsEvent.ToggleUnit -> draft {
                copy(unitIds = if (event.id in unitIds) unitIds - event.id else unitIds + event.id)
            }
            AnalyticsEvent.AllUnits -> draft { copy(unitIds = emptyList()) }
            AnalyticsEvent.ResetFilters -> setState { copy(draft = AnalyticsFilterDraft()) }
            AnalyticsEvent.ApplyFilters -> applyFilters()
        }
    }

    private fun draft(
        change: AnalyticsFilterDraft.() -> AnalyticsFilterDraft,
    ) = setState { copy(draft = draft.change()) }

    private suspend fun applyCurrencies() {
        val loaded = filterSource.currencies() ?: return
        val currencies = loaded.currencies.map { AnalyticsCurrency(it.code, it.symbol.ifBlank { it.code }) }
        val default = loaded.defaultCode?.takeIf { it.isNotBlank() }
            ?: currencies.firstOrNull()?.code
            ?: AnalyticsFilterOptions.FALLBACK_CURRENCY
        setState { copy(options = options.copy(currencies = currencies, defaultCurrency = default)) }
    }

    private fun applyFilters() {
        val query = currentState.draft.toQuery(currentState.options)
        setState { copy(filtersOpen = false) }
        if (query == currentState.applied) return
        setState { copy(applied = query) }
        loadModules(query)
        loadContent()
    }

    private fun select(id: String) {
        if (id == currentState.selected) return
        setState { copy(selected = id) }
        loadContent()
    }

    private fun selectTab(id: String) {
        if (id == currentState.activeTab) return
        setState { copy(activeTab = id) }
        loadTab()
    }

    // -- reads ----------------------------------------------------------------------------------

    /** The strip's totals follow the filters; a failed read empties the strip, as the web's does. */
    private fun loadModules(query: AnalyticsQuery) {
        modulesJob?.cancel()
        val token = ++modulesToken
        modulesJob = launch {
            val modules = repository.modules(query).getOrNull().orEmpty()
            if (token == modulesToken) setState { copy(modules = modules) }
        }
    }

    /**
     * The overview or the selected module. A module with neither blocks nor
     * tabs is "no analytics yet"; the overview is empty only when the server
     * sends nothing at all. A new page drops the previous one's tab cache —
     * numbers fetched under other filters are never shown again.
     */
    private fun loadContent() {
        val state = currentState
        val query = state.applied ?: return
        val selected = state.selected
        contentJob?.cancel()
        tabJob?.cancel()
        val token = ++contentToken
        tabToken++
        setState {
            copy(
                status = AnalyticsStatus.Loading,
                error = null,
                page = null,
                activeTab = null,
                tabBlocks = emptyMap(),
                tabStatus = TabStatus.Idle,
            )
        }
        contentJob = launch {
            val result = if (state.isOverview) repository.overview(query) else repository.module(selected, query)
            if (token != contentToken) return@launch
            when (result) {
                is ZillitResult.Failure -> setState {
                    copy(status = AnalyticsStatus.Error, error = result.error.localised().ifBlank { FAILED })
                }
                is ZillitResult.Success -> {
                    val page = result.data
                    val ready = if (state.isOverview) page != null else page?.hasContent == true
                    setState {
                        copy(
                            status = if (ready) AnalyticsStatus.Ready else AnalyticsStatus.Empty,
                            page = page,
                            activeTab = page?.tabs?.firstOrNull()?.id,
                        )
                    }
                    loadTab()
                }
            }
        }
    }

    /** The open tab, fetched with `?sub=` unless it came inline or was fetched already. */
    private fun loadTab() {
        val state = currentState
        val tab = state.page?.tabs?.firstOrNull { it.id == state.activeTab } ?: return
        val query = state.applied ?: return
        tabJob?.cancel()
        val token = ++tabToken
        if (tab.blocks != null || tab.id in state.tabBlocks) {
            setState { copy(tabStatus = TabStatus.Idle) }
            return
        }
        setState { copy(tabStatus = TabStatus.Loading) }
        tabJob = launch {
            val result = repository.module(state.selected, query, sub = tab.id)
            if (token != tabToken) return@launch
            when (result) {
                is ZillitResult.Failure -> setState { copy(tabStatus = TabStatus.Error) }
                is ZillitResult.Success -> setState {
                    copy(tabBlocks = tabBlocks + (tab.id to result.data?.blocks.orEmpty()), tabStatus = TabStatus.Idle)
                }
            }
        }
    }

    private companion object {
        val FAILED: String get() = str(S.desktop_cr_analytics_failed)
    }
}
