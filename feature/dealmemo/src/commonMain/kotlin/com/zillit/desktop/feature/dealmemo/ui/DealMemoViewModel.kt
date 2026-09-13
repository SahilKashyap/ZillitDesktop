package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealPerson
import com.zillit.desktop.feature.dealmemo.domain.DealRefresh
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.domain.rates.DealReferenceData
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderActions
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPdfWork
import com.zillit.desktop.feature.dealmemo.ui.documents.platformPdfWork
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random
import kotlin.time.Clock

/** The badge ledger's deal-memo counts, and the reads that clear them. */
interface DealMemoBadgeSource {
    val counts: Flow<DealBadgeCounts>

    /** `notification:level:read` for a whole tab. */
    fun readTab(unit: DealBadgeUnit)

    /** The same, narrowed to one deal (`level_3`). */
    fun readDeal(unit: DealBadgeUnit, dealId: String)
}

/** Where an exported file lands — the host saves and opens it. */
fun interface DealFileSaver {
    suspend fun save(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}

/**
 * The deal memo tool — the web's `DealMemoModule`: one entry, many pages,
 * and the rights, approver metadata, setups and directory every page shares.
 *
 * Each page's work lives in its own collaborator; this class owns only what is
 * shared — where the viewer is, what they may see, and when a page is entered.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One seam per host concern, each a test hook with a default.
class DealMemoViewModel(
    internal val repository: DealMemoRepository,
    internal val reference: DealReferenceData,
    private val viewer: () -> DealMemoViewer,
    private val loadPeople: suspend () -> Map<String, DealPerson> = { emptyMap() },
    internal val badges: DealMemoBadgeSource? = null,
    internal val files: DealFileSaver? = null,
    internal val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val metadataRetryDelays: List<Long> = DealMemoMetadata.RETRY_DELAYS_MILLIS,
    /** The production's own data — companies, units, countries, chart of accounts. */
    internal val productionData: DealProductionData = DealProductionData.None,
    /** Storage and the signature library; without it documents can't be opened or signed. */
    internal val store: DealDocumentStore? = null,
    internal val pdf: DealPdfWork = platformPdfWork(),
    /** Where PDF rendering and stamping run — never the UI thread. */
    internal val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    internal val newId: () -> String = { "row-${Random.nextLong().toULong().toString(RADIX)}" },
) : ZillitViewModel<DealMemoUiState, DealMemoEvent, DealMemoEffect>(DealMemoUiState()) {

    private val deals = DealListActions(this)
    private val tabPages = TabPageActions(this)
    private val notices = NoticesActions(this)
    private val noticeTemplate = NoticeTemplateActions(this)
    private val rates = GlobalRatesActions(this)
    internal val preview = DealPreviewActions(this)
    internal val templateStore = TemplateStore(this)
    internal val builder = BuilderActions(this)
    private val hub = SetupHubActions(this)
    private var coaJob: Job? = null
    private var productionJob: Job? = null
    private var settingsJob: Job? = null
    private var settingsReadAt = 0L
    private var crewJob: Job? = null
    private var directoryJob: Job? = null
    private var agenciesJob: Job? = null

    private var started = false
    private var listening = false
    private var enteredPage: DealMemoRoute? = null
    private val sessionJobs = mutableListOf<Job>()
    private val refreshJobs = mutableMapOf<DealRefreshKey, Job>()
    private var toastCounter = 0L

    /** Opens the tool: rights, metadata, directory and setups, then the first page. */
    fun start() {
        if (started) return
        started = true
        setState { copy(viewer = viewer()) }
        loadMetadata()
        loadDirectory()
        if (currentState.rights.canPost) loadTemplates()
        listenOnce()
        syncPage()
    }

    /** A production switch starts the tool over, keeping only the page asked for. */
    fun onProjectChanged() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        builder.reset()
        templateStore.reset()
        listOf(coaJob, productionJob, settingsJob, crewJob, agenciesJob).forEach { it?.cancel() }
        coaJob = null
        productionJob = null
        settingsJob = null
        settingsReadAt = 0L
        crewJob = null
        agenciesJob = null
        preview.reset()
        started = false
        enteredPage = null
        val route = currentState.route
        setState { DealMemoUiState(route = route) }
        start()
    }

    /** The tool grid or member list answered — re-resolve what the viewer may see. */
    fun onRightsChanged() {
        val resolved = viewer()
        setState { copy(viewer = resolved) }
        if (resolved.rights.canPost && currentState.templates.rows == null && !templateStore.loading) loadTemplates()
        syncPage()
    }

    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: DealMemoEvent) {
        when (event) {
            is DealsEvent -> deals.onEvent(event)
            is OverviewEvent -> tabPages.onEvent(event)
            is MyDealEvent -> tabPages.onEvent(event)
            is NoticesEvent -> notices.onEvent(event)
            is NoticeTemplateEvent -> noticeTemplate.onEvent(event)
            is RatesEvent -> rates.onEvent(event)
            is RulesEvent -> if (currentState.builder?.rules != null) builder.onRules(event) else preview.onEvent(event)
            is PreviewEvent, is SignerEvent, is NominalsEvent, is CrewFormEvent -> preview.onEvent(event)
            is BuilderEvent -> builder.onEvent(event)
            is SetupHubEvent -> hub.onEvent(event)
            is DealMemoEvent.OpenPath -> navigate(DealMemoRoute.parse(event.path.removePrefix(DEAL_MEMO_PATH)))
            is DealMemoEvent.Navigate -> navigate(event.route)
            DealMemoEvent.LeaveTool -> sendEffect(DealMemoEffect.LeaveTool)
            DealMemoEvent.DismissToast -> setState { copy(toast = null) }
            is DealMemoEvent.OpenHistory -> openHistory(event.deal)
            DealMemoEvent.CloseHistory -> setState { copy(history = null) }
        }
    }

    // -- seams for the collaborators -------------------------------------------------

    internal val ui: DealMemoUiState get() = currentState

    internal fun update(reducer: DealMemoUiState.() -> DealMemoUiState) = setState(reducer)

    internal fun work(block: suspend CoroutineScope.() -> Unit): Job = launch(block)

    internal fun navigate(route: DealMemoRoute?) {
        setState { copy(route = route) }
        syncPage()
    }

    /** A success toast: the server's message, else the web's fallback key, in words. */
    internal fun toastSuccess(serverMessage: String?, fallbackKey: String) =
        toast(DealMessages.text(serverMessage, fallbackKey), DealToastTone.Success)

    internal fun toastError(error: ZillitError, fallbackKey: String? = null) {
        val server = (error as? ZillitError.Http)?.serverMessage?.takeIf { it.isNotBlank() }
        val message = when {
            server != null -> DealMessages.override(server) ?: error.localised()
            fallbackKey != null && error is ZillitError.Http -> DealMessages.text(null, fallbackKey)
            else -> error.localised()
        }
        toast(message, DealToastTone.Error)
    }

    /** The chart of accounts, once per production — every nominal picker shares it. */
    internal fun ensureCoa() {
        val coa = currentState.coa
        if (coa.loaded || coa.loading) return
        setState { copy(coa = this.coa.copy(loading = true, failed = false)) }
        coaJob = launch {
            when (val result = productionData.chartOfAccounts()) {
                is ZillitResult.Success -> setState { copy(coa = CoaState(loaded = true, accounts = result.data)) }
                is ZillitResult.Failure -> setState { copy(coa = CoaState(failed = true)) }
            }
        }
    }

    internal fun toast(message: String, tone: DealToastTone) {
        toastCounter += 1
        setState { copy(toast = DealToast(message, tone, toastCounter)) }
    }

    /**
     * The setups sweep's result, waiting for the one in flight rather than
     * starting another — the Create gate asks while the first load may still
     * be running.
     */
    internal suspend fun awaitTemplates() {
        templateStore.await()
    }

    /** The setups list moved — the hub's inline create may have just been answered. */
    internal fun onTemplatesChanged() {
        hub.reconcile()
    }

    /** Waits for the departments master — a saved deal hydrates its department and role through it. */
    internal suspend fun awaitDirectory() {
        directoryJob?.join()
    }

    /** The companies, units and countries the pages name things with — once per production. */
    internal fun ensureProduction() {
        if (currentState.production.loaded || productionJob?.isActive == true) return
        val data = productionData
        setState { copy(production = production.copy(project = data.project(), webOrigin = data.webOrigin())) }
        productionJob = launch {
            val companies = data.companies()
            val units = data.units()
            val countries = data.countries()
            setState {
                copy(
                    production = production.copy(
                        companies = companies,
                        units = units,
                        countries = countries,
                        loaded = true,
                    ),
                )
            }
            builder.reconcile()
        }
    }

    /**
     * Production Setup's document: loaded on first use, then refreshed
     * silently on every later entry older than two seconds — the web's
     * nested provider refresh.
     */
    internal fun ensureProjectSettings() {
        val settings = currentState.projectSettings
        when {
            settingsJob?.isActive == true -> Unit
            !settings.loaded -> {
                setState { copy(projectSettings = projectSettings.copy(loading = true)) }
                readProjectSettings()
            }
            clock() - settingsReadAt >= SETTINGS_FRESH_MILLIS -> readProjectSettings()
        }
    }

    /** A silent re-read after a page wrote to Production Setup — a read already in flight may predate the write. */
    internal fun refreshProjectSettings() {
        settingsJob?.cancel()
        readProjectSettings()
    }

    /** [refreshProjectSettings], waited for — the page shows the written rows before it moves on. */
    internal suspend fun reloadProjectSettings() {
        refreshProjectSettings()
        settingsJob?.join()
    }

    /** A failed re-read keeps what was loaded; only a first failure leaves the page with nothing. */
    private fun readProjectSettings() {
        settingsJob = launch {
            val result = repository.projectSettings()
            if (result is ZillitResult.Success) settingsReadAt = clock()
            setState {
                val kept = projectSettings.view.takeIf { result is ZillitResult.Failure && projectSettings.loaded }
                copy(
                    projectSettings = ProjectSettingsState(
                        view = kept ?: ProjectSettingsView(result.getOrNull()),
                        loading = false,
                        loaded = true,
                    ),
                )
            }
            builder.reconcile()
        }
    }

    /** The production's members with their master ids — read once per production. */
    internal fun ensureCrewDirectory() {
        if (currentState.crewDirectory != null || crewJob?.isActive == true) return
        crewJob = launch {
            val crew = reference.crew().getOrNull().orEmpty()
            setState { copy(crewDirectory = crew) }
        }
    }

    /** The project's registered agencies, once per production — the Representing Agency picker. */
    internal fun ensureAgencies() {
        if (agenciesJob != null) return
        agenciesJob = launch {
            reference.agencies().getOrNull()?.let { agencies ->
                setState { copy(production = production.copy(agencies = agencies)) }
            }
        }
    }

    // -- pages ------------------------------------------------------------------------

    /** Enters the page now on screen, once — mounting a page is entering it. */
    @Suppress("CyclomaticComplexMethod")
    private fun syncPage() {
        val page = currentState.page
        if (page == enteredPage) return
        enteredPage = page
        if (!page.isBuilder) builder.leave()
        when (page) {
            is DealMemoRoute.Tab -> when (page.tab) {
                DealTab.Overview -> tabPages.enterOverview()
                DealTab.Deals -> deals.enter()
                DealTab.MyDeal -> tabPages.enterMyDeal()
                DealTab.ApprovalQueue -> tabPages.enterQueue()
                DealTab.Notices -> notices.enter()
            }
            DealMemoRoute.GlobalRates -> rates.enter()
            DealMemoRoute.NoticeTemplate -> noticeTemplate.enter()
            is DealMemoRoute.Deal -> preview.enter(page)
            DealMemoRoute.CompleteDetails -> tabPages.enterCompleteDetails()
            is DealMemoRoute.QuickDeal, is DealMemoRoute.EditDeal, is DealMemoRoute.TemplateBuilder ->
                builder.enter(page)
            is DealMemoRoute.SetupHub -> if (page.setupId != null) builder.enter(page) else hub.enter()
            else -> Unit
        }
    }

    /**
     * `GET /metadata`, retried after 2 s, 8 s and 30 s. Until it answers with
     * data, the approver's gates stay closed; a 200 without data is a failure.
     */
    private fun loadMetadata() {
        sessionJobs += launch {
            for (wait in listOf(0L) + metadataRetryDelays) {
                if (wait > 0) delay(wait)
                val result = repository.metadata()
                if (result is ZillitResult.Success) {
                    setState { copy(metadata = result.data) }
                    syncPage()
                    return@launch
                }
            }
        }
    }

    /** The departments master and the production's people — every list resolves names through them. */
    private fun loadDirectory() {
        directoryJob = launch {
            reference.departments().getOrNull()?.let { catalogue -> setState { copy(catalogue = catalogue) } }
            setState { copy(directoryReady = true) }
        }.also(sessionJobs::add)
        sessionJobs += launch {
            val people = loadPeople()
            setState { copy(people = people) }
        }
    }

    /** One sweep per entry: the slim list, then every setup's payload for its group. */
    private fun loadTemplates() {
        templateStore.load()
        sessionJobs += launch {
            templateStore.await()
            builder.reconcile()
            hub.reconcile()
        }
    }

    /**
     * Other clients' deal announcements, coalesced per key over the web's 500 ms,
     * and answered with a silent reload of whichever page listens to that key.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch { repository.refreshes.collect(::onRefresh) }
        badges?.let { source ->
            launch {
                source.counts.collect { counts ->
                    val before = currentState.badges.tab(DealBadgeUnit.Notices)
                    setState { copy(badges = counts) }
                    // Notices reads its tab on entry and on every change while open.
                    val page = currentState.page
                    if (page == DealMemoRoute.Tab(DealTab.Notices) && counts.tab(DealBadgeUnit.Notices) != before) {
                        source.readTab(DealBadgeUnit.Notices)
                    }
                }
            }
        }
    }

    private fun onRefresh(refresh: DealRefresh) {
        // A detail frame for another deal is not this page's business.
        val foreign = refresh.dealId != null && refresh.dealId != currentState.preview?.dealId
        refresh.keys.filterNot { it == DealRefreshKey.Detail && foreign }.forEach { key ->
            refreshJobs[key]?.cancel()
            refreshJobs[key] = launch {
                delay(REFRESH_DEBOUNCE_MILLIS)
                reloadFor(key)
            }
        }
    }

    private fun reloadFor(key: DealRefreshKey) {
        val page = currentState.page as? DealMemoRoute.Tab
        when (key) {
            DealRefreshKey.Registry -> when (page?.tab) {
                DealTab.Deals -> deals.reload()
                DealTab.Overview -> tabPages.reloadOverview()
                DealTab.Notices -> notices.reload()
                else -> Unit
            }
            DealRefreshKey.Approval -> if (page?.tab == DealTab.ApprovalQueue) tabPages.reloadQueue()
            DealRefreshKey.Mine -> {
                val mine = page?.tab == DealTab.MyDeal || currentState.page == DealMemoRoute.CompleteDetails
                if (mine) tabPages.reloadMyDeal()
            }
            DealRefreshKey.Detail -> if (currentState.page is DealMemoRoute.Deal) preview.refresh()
            DealRefreshKey.Templates -> Unit
        }
    }

    private fun openHistory(deal: DealDoc) {
        val id = deal.id
        setState { copy(history = HistoryState(dealId = id, subtitle = deal.reference ?: deal.crewName.orEmpty())) }
        launch {
            val result = repository.history(id)
            setState {
                if (history?.dealId != id) return@setState this
                copy(
                    history = when (result) {
                        is ZillitResult.Success -> history.copy(
                            loading = false,
                            entries = result.data.sortedByDescending { it.actionAt ?: 0L },
                        )
                        is ZillitResult.Failure -> history.copy(loading = false, error = result.error.localised())
                    },
                )
            }
        }
    }

    companion object {
        /** The web's refetch coalescing window — `accountHubListeners.js` `DEBOUNCE_MS`. */
        const val REFRESH_DEBOUNCE_MILLIS = 500L
        private const val RADIX = 36

        /** `ENTRY_FRESH_MS`: an entry within this long of the last read skips the refresh. */
        private const val SETTINGS_FRESH_MILLIS = 2_000L
    }
}

const val DEAL_MEMO_PATH = "/film-tools/deal-memo"
