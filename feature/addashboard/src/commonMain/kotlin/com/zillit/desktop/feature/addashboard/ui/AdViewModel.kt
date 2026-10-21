package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.data.adRefreshes
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdHost
import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdViewer

/**
 * The AD department's day.
 *
 * One view model for the whole tool, with a small [actions] class per area —
 * the same arrangement Card Expenses uses, and for the same reason: sixteen
 * pages' handlers in one `when` is a file nobody can read, while sixteen view
 * models would each need the same day, the same register and the same viewer.
 *
 * [now] is injected so the day the dashboard opens on is testable — the
 * alternative is a test that passes only until midnight UTC.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class AdViewModel(
    private val repository: AdRepository,
    /**
     * Live changes from other clients; null keeps the tool load-once.
     *
     * Ahead of [viewer] deliberately: `viewer` is the trailing lambda at call
     * sites, and a parameter added after it would capture that lambda instead.
     */
    private val events: SocketEventBus? = null,
    /** The machine and the open production — the file dialog, the crew, the currencies. */
    private val host: AdHost = AdHost.None,
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once.
     */
    private val rights: RightsRequestBus? = null,
    /** The ledger's rows for this tool per unit, and the page read. */
    private val badges: TabBadgeSource = TabBadgeSource.None,
    private val now: () -> Long = { 0L },
    /** Read at start, not at construction: the rights arrive a beat after the production. */
    private val viewer: () -> AdViewer,
) : ZillitViewModel<AdUiState, AdEvent, AdEffect>(AdUiState(viewer = viewer())) {

    private var started = false
    private var listening = false
    private var watchingBadges = false

    /** Whether the window is in front — see [AdEvent.WindowFocus]. Nobody, until it says so. */
    private var watching = false

    internal val repo: AdRepository get() = repository

    internal val current: AdUiState get() = currentState

    internal val machine: AdHost get() = host

    internal val clock: () -> Long get() = now

    internal fun update(reducer: AdUiState.() -> AdUiState) = setState(reducer)

    internal fun fail(message: String) = sendEffect(AdEffect.Failed(message))

    internal fun emit(effect: AdEffect) = sendEffect(effect)

    internal fun notice(message: String) = setState { copy(notice = message) }

    internal fun run(block: suspend () -> Unit) = launch { block() }

    internal fun report(error: ZillitError) = sendEffect(AdEffect.Failed(error.localised()))

    // -- the areas -----------------------------------------------------------

    internal val registerActions = RegisterActions(this)
    internal val formActions = ArtisteFormActions(this)
    internal val importActions = ImportActions(this)
    internal val dayActions = DayActions(this)
    internal val addExtrasActions = AddExtrasActions(this)
    internal val additionalsActions = AdditionalsActions(this)
    internal val runnerActions = RunnerActions(this)
    internal val submitActions = SubmitActions(this)
    internal val agencyActions = AgencyActions(this)
    internal val queryActions = QueryActions(this)
    internal val ratesActions = RatesActions(this)
    internal val reportActions = ReportActions(this)

    // -- lifecycle -----------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        if (!watchingBadges) {
            watchingBadges = true
            launch {
                badges.counts.collect { counts ->
                    setState { copy(unread = counts) }
                    if (watching) readPage(currentState.destination)
                }
            }
            launch { badges.entityCounts.collect { tree -> setState { copy(entityUnread = tree) } } }
        }
        val identity = viewer()
        // Every date on this service is UTC midnight; a local one files the day
        // against the wrong date for a unit shooting in another zone.
        val today = AdDates.todayUtc(now())
        setState {
            copy(
                viewer = identity,
                shootDate = today,
                today = today,
                destination = landingFor(identity),
            )
        }
        // Money is per row on this service, so the symbols are read once and
        // kept: a page that fell back to one project-wide symbol would print
        // the wrong one for an agreement paid in another currency.
        launch { val money = host.currencies(); setState { copy(currencies = money) } }
        if (!identity.isBlocked) refresh()
        listenOnce()
    }

    /**
     * Where a viewer without posting rights lands.
     *
     * The web redirects their hidden URLs to Today's List rather than leaving
     * them on a page they cannot see.
     */
    private fun landingFor(identity: AdViewer): AdDestination =
        if (identity.canPost || !AdDestination.landing.postingOnly) {
            AdDestination.landing
        } else {
            AdDestination.TodaysList
        }

    /**
     * Somebody else's change to the day, the register, the schedule or a report.
     *
     * Only the page on screen is reloaded: reloading one nobody is looking at
     * spends a request to change nothing, and the page reloads on open anyway.
     *
     * Guarded so reopening the window does not stack collectors.
     */
    private fun listenOnce() {
        val bus = events ?: return
        if (listening) return
        listening = true
        launch {
            adRefreshes(bus).collect { kinds ->
                // Rates and the agency lists are deliberately not reloaded
                // while an edit is unsaved: the last Save wins, and a remote
                // change mid-edit would throw away typing.
                val blocked = AdRefresh.Rates in kinds &&
                    (currentState.rates.dirty || currentState.rates.saving)
                if (!blocked && kinds.any { it in currentState.destination.refresh }) {
                    load(currentState.destination)
                }
            }
        }
    }

    fun onProjectChanged() {
        started = false
        setState { AdUiState(viewer = viewer()) }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one. Only the viewer changes
     * here; the open page and its data are already right. A page that has
     * become hidden is left behind, as the web redirects it.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the state's own
        // viewer property rather than the supplier.
        val resolved = viewer()
        setState {
            val stillVisible = resolved.canPost || !destination.postingOnly
            copy(
                viewer = resolved,
                destination = if (stillVisible) destination else AdDestination.TodaysList,
            )
        }
    }

    // -- badges --------------------------------------------------------------

    /**
     * The page on screen is its read — the web's `emitAdTabRead`, each of its
     * whole units. The register and the queries read per row instead, so
     * opening either tab no longer clears every dot at once. The
     * notification-only units ride along: they have no page, and the dashboard
     * being looked at is the nearest thing to opening theirs.
     */
    private fun readPage(destination: AdDestination) {
        (destination.pageReads + AdBadges.NOTICE_ONLY)
            .filter { (currentState.unread[it] ?: 0) > 0 }
            .forEach(badges::read)
    }

    /** One record's rows read — the web's `emitAdItemRead`: the unit and `level_3`. */
    internal fun readEntity(unit: String, id: String) {
        val count = currentState.entityUnread[unit]?.get(id) ?: 0
        if (count > 0) badges.readEntity(unit, id)
    }

    /** The window in front, or not; coming to the front reads the page on screen. */
    private fun onWindowFocus(focused: Boolean) {
        val gained = focused && !watching
        watching = focused
        if (gained) readPage(currentState.destination)
    }

    // -- gates ---------------------------------------------------------------

    /** Refuses, and offers the way forward the phones offer on every refusal. */
    internal fun askForPostingRights() {
        rights?.ask("AD Dashboard", RightsKind.Post)
        fail(
            if (rights == null) {
                str(S.desktop_ad_no_posting_rights)
            } else {
                str(S.desktop_ad_no_posting_rights_asking_admin)
            },
        )
    }

    /**
     * Answers a refused edit by the gate that actually refused it.
     *
     * The two are fixed differently and only one of them is worth asking
     * about: a missing right is something an admin can grant, while a
     * submitted day is closed to everyone including them.
     */
    internal fun refuse() {
        when {
            !currentState.viewer.canPost -> askForPostingRights()
            !currentState.dateEditable -> fail(str(S.desktop_ad_future_day_read_only))
            else -> fail(str(S.desktop_ad_day_submitted_no_changes))
        }
    }

    /** Every write to the day goes through one gate; see [AdUiState.canEditDay]. */
    internal fun editDay(reload: Boolean = true, call: suspend () -> ZillitResult<Unit>) {
        if (!currentState.canEditDay) {
            refuse()
            return
        }
        launch {
            when (val result = call()) {
                is ZillitResult.Success -> if (reload) loadDay()
                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    /** Every write that is not the day's own still needs the posting right. */
    internal fun posting(call: () -> Unit) {
        if (currentState.viewer.canPost) call() else askForPostingRights()
    }

    // -- events --------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per area.
    override fun onEvent(event: AdEvent) {
        when (event) {
            is AdEvent.Open -> open(event.destination)
            is AdEvent.OpenWorkspace -> openWorkspace(event.workspace)
            AdEvent.Refresh -> refresh()
            is AdEvent.WindowFocus -> onWindowFocus(event.focused)
            AdEvent.ClearNotice -> setState { copy(notice = null) }
            AdEvent.DismissError -> setState { copy(error = null) }
            is AdEvent.ChangeDay -> changeDay(event.shootDate)
            is RegisterEvent -> registerActions.on(event)
            is FormEvent -> formActions.on(event)
            is ImportEvent -> importActions.on(event)
            is DayEvent -> dayActions.on(event)
            is AddExtrasEvent -> addExtrasActions.on(event)
            is AdditionalsEvent -> additionalsActions.on(event)
            is RunnerEvent -> runnerActions.on(event)
            is SubmitEvent -> submitActions.on(event)
            is AgencyEvent -> agencyActions.on(event)
            is QueryEvent -> queryActions.on(event)
            is RatesEvent -> ratesActions.on(event)
            is ReportEvent -> reportActions.on(event)
        }
    }

    private fun open(destination: AdDestination) {
        // A hidden page is not reachable even by a stale click.
        if (destination.postingOnly && !currentState.viewer.canPost) {
            askForPostingRights()
            return
        }
        setState { copy(destination = destination, error = null) }
        load(destination)
        readPage(destination)
    }

    /**
     * Switch the sidebar to the other half.
     *
     * The content follows rather than the other way round: the half entered
     * opens on its first page, which is what makes the showing half derivable
     * from the open one.
     */
    private fun openWorkspace(workspace: AdWorkspace) {
        if (currentState.workspace == workspace) return
        currentState.firstIn(workspace)?.let { open(it) }
    }

    private fun changeDay(shootDate: Long) {
        setState { copy(shootDate = AdDates.utcMidnight(shootDate)) }
        loadDay()
    }

    // -- loading -------------------------------------------------------------

    private fun refresh() {
        setState { copy(loading = true, error = null) }
        loadDay()
        loadRegister()
        load(currentState.destination)
    }

    internal fun load(destination: AdDestination) {
        when (destination) {
            AdDestination.TodaysList,
            AdDestination.Attendance,
            AdDestination.Additionals,
            AdDestination.LiveDay,
            AdDestination.SubmitDay,
            -> loadDay()

            AdDestination.AddExtras -> {
                loadDay()
                loadRegister()
            }

            AdDestination.Schedule -> loadShootDays()
            AdDestination.Register, AdDestination.Onboarding -> loadRegister()
            AdDestination.Agency -> agencyActions.load()
            AdDestination.Queries -> queryActions.load()
            AdDestination.Config -> ratesActions.load()
            AdDestination.PoQueue -> loadPoQueue()
            AdDestination.WrapReport -> reportActions.loadMerged()
            AdDestination.ExhibitG -> reportActions.loadExhibitG()
            AdDestination.Templates -> reportActions.loadTemplates()
            AdDestination.Budget -> Unit
        }
    }

    /**
     * The open day: its header, its number and its rows.
     *
     * All three because they move together — booking an artiste creates the
     * header, and submitting it moves every row.
     */
    internal fun loadDay() {
        val date = currentState.shootDate
        launch {
            when (val header = repository.today(date)) {
                is ZillitResult.Success -> setState { copy(day = header.data, loading = false) }
                is ZillitResult.Failure -> failWith(header.error)
            }
            when (val rows = repository.dayList(date)) {
                is ZillitResult.Success -> setState { copy(dayList = rows.data, loading = false) }
                is ZillitResult.Failure -> failWith(rows.error)
            }
            (repository.dayNumber(date) as? ZillitResult.Success)?.let { number ->
                setState { copy(dayNumber = number.data) }
            }
        }
    }

    internal fun loadRegister() {
        launch {
            when (val rows = repository.artistes()) {
                is ZillitResult.Success -> setState { copy(artistes = rows.data, loading = false) }
                is ZillitResult.Failure -> failWith(rows.error)
            }
        }
    }

    private fun loadShootDays() {
        setState { copy(loading = true) }
        launch {
            when (val rows = repository.shootDays()) {
                is ZillitResult.Success -> setState { copy(shootDays = rows.data, loading = false) }
                is ZillitResult.Failure -> failWith(rows.error)
            }
        }
    }

    private fun loadPoQueue() {
        setState { copy(poQueueLoading = true) }
        launch {
            when (val queue = repository.poQueue()) {
                is ZillitResult.Success -> setState { copy(poQueue = queue.data, poQueueLoading = false) }
                is ZillitResult.Failure -> {
                    setState { copy(poQueueLoading = false) }
                    failWith(queue.error)
                }
            }
        }
    }

    internal fun failWith(error: ZillitError) {
        setState { copy(loading = false, error = error.localised()) }
    }
}
