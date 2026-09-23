package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.data.BankRefresh
import com.zillit.desktop.feature.bankrec.data.bankRefreshes
import com.zillit.desktop.feature.bankrec.domain.BankRecBadges
import com.zillit.desktop.feature.bankrec.domain.BankRecDirectory
import com.zillit.desktop.feature.bankrec.domain.BankRecFiles
import com.zillit.desktop.feature.bankrec.domain.BankRecLookups
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.StatementFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Bank Reconciliation.
 *
 * Nine tabs over one month's statement: what it says, what the ledger says,
 * and every way the two disagree. The period is the unit throughout — more
 * than one can be open at once, because a statement spanning several months
 * opens a period per month.
 *
 * ## One copy of each list, for every tab
 *
 * The periods, exceptions, fraud alerts and FX variances are fetched **here**,
 * once, and every tab filters its own view out of them — the web's module
 * does the same, and for the same reason: they outlive the tab switches, and
 * a tab refetching its own copy on every visit re-asks for what the module
 * already holds. The socket collector keeps them current.
 *
 * The tabs are not independent. Importing a statement produces transactions,
 * exceptions, fraud alerts and FX variances together; matching a line
 * auto-accepts its fraud alert and usually clears an exception. That fan-out
 * is the service's, and the reloads after each write mirror it.
 */
@Suppress(
    "LongParameterList", // Each is a seam the host fills; a holder object would only rename them.
    "TooManyFunctions", // The module's own lists, plus the small seams its collaborators call through.
)
class BankRecViewModel(
    private val repository: BankRecRepository,
    /** Live changes from other clients; null keeps the module load-once. */
    private val events: SocketEventBus? = null,
    /** Where a statement file comes from and is kept. Absent leaves import unavailable. */
    private val statements: StatementFiles? = null,
    /** Builds a recipient's URL from a link token. */
    private val portalUrl: (String) -> String = { it },
    /** Where exported PDFs and CSVs go. Absent leaves every export unavailable. */
    private val files: BankRecFiles? = null,
    private val directory: BankRecDirectory = BankRecDirectory { null },
    lookups: BankRecLookups = BankRecLookups.None,
    /** The tab counts. Absent draws no chips. */
    private val badges: BankRecBadges? = null,
) : ZillitViewModel<BankRecUiState, BankRecEvent, BankRecEffect>(BankRecUiState()) {

    private val lookupSource = lookups
    private var started = false
    private var listening = false
    private var badgeJob: Job? = null

    internal val periodActions = PeriodActions(this)
    internal val importActions = ImportActions(this, statements)
    internal val workspaceActions = WorkspaceActions(this)
    internal val matchActions = MatchActions(this)
    internal val quickEntryActions = QuickEntryActions(this)
    internal val exceptionActions = ExceptionActions(this)
    internal val fraudActions = FraudActions(this)
    internal val fxActions = FxActions(this)
    internal val portalActions = PortalActions(this, portalUrl)
    internal val rulesActions = RulesActions(this)

    fun start() {
        if (started) return
        started = true
        setState { copy(canImport = statements != null) }
        loadPeriods()
        loadExceptions()
        loadFraudAlerts()
        loadFxVariances()
        loadLookups()
        listen()
        collectBadges()
        markTabRead(currentState.tab)
    }

    /** Re-reads everything when the open production changes. */
    fun onProjectChanged() {
        started = false
        badgeJob?.cancel()
        setState { BankRecUiState() }
        start()
    }

    /**
     * Opens the tab a route names — `…/bank-reconciliation/exceptions` — so a
     * link or a sidebar deep link lands where the web's URL would.
     */
    fun openRoute(tail: String) {
        val slug = tail.trim('/').substringBefore('/').substringBefore('?')
        if (slug.isBlank()) return
        onEvent(BankRecEvent.OpenTab(BankTab.from(slug)))
    }

    override fun onEvent(event: BankRecEvent) {
        when (event) {
            is BankRecEvent.OpenTab -> openTab(event.tab)
            is BankRecEvent.OpenPeriod -> workspaceActions.openPeriod(event.periodId)
            BankRecEvent.Refresh -> refreshAll()
            BankRecEvent.ClearNotice -> setState { copy(notice = null) }
            else -> route(event)
        }
    }

    private fun route(event: BankRecEvent) {
        val handled = periodActions.onEvent(event) ||
            importActions.onEvent(event) ||
            workspaceActions.onEvent(event) ||
            matchActions.onEvent(event) ||
            quickEntryActions.onEvent(event) ||
            exceptionActions.onEvent(event) ||
            fraudActions.onEvent(event) ||
            fxActions.onEvent(event) ||
            portalActions.onEvent(event) ||
            rulesActions.onEvent(event)
        if (!handled) report(ZillitError.Unknown("unhandled bank reconciliation event"))
    }

    private fun openTab(tab: BankTab) {
        val previous = currentState.tab
        setState { copy(tab = tab) }
        markTabRead(tab)
        when (tab) {
            // A bare tab click opens the newest open period inline; only an
            // explicit Open arrives in the full view.
            BankTab.Workspace -> if (previous != BankTab.Workspace) workspaceActions.openCurrent()
            BankTab.GuarantorPortal -> portalActions.open()
            BankTab.Settings -> rulesActions.load(force = false)
            else -> Unit
        }
    }

    private fun refreshAll() {
        loadPeriods()
        loadExceptions()
        loadFraudAlerts()
        loadFxVariances()
        when (currentState.tab) {
            BankTab.Workspace -> workspaceActions.refresh()
            BankTab.GuarantorPortal -> portalActions.open()
            BankTab.Settings -> rulesActions.load(force = true)
            else -> Unit
        }
    }

    // -- the module's own lists ----------------------------------------------

    internal fun loadPeriods() {
        launchResult(repository::bankAccounts, { rows ->
            setState {
                copy(
                    bankAccounts = rows,
                    // History opens on the first account, as the web's does.
                    historyAccountId = historyAccountId.takeIf { id -> rows.any { it.id == id } }
                        ?: rows.firstOrNull()?.id.orEmpty(),
                )
            }
        }, { })
        // Swallowed: without rates a foreign balance is shown in its own
        // currency and said to be so, which is the honest fallback rather
        // than an error over a page that otherwise works.
        launchResult(repository::projectCurrencies, { rates -> setState { copy(rates = rates) } }, { })
        launchResult(repository::periods, { rows ->
            setState { copy(periods = rows, periodsLoading = false) }
            periodActions.onPeriodsChanged()
            workspaceActions.onPeriodsChanged()
            portalActions.onPeriodsChanged()
        }, { error ->
            setState { copy(periodsLoading = false) }
            report(error)
        })
    }

    internal fun loadExceptions() {
        launchResult(repository::exceptions, { rows ->
            setState { copy(exceptions = rows, exceptionsLoading = false) }
        }, { error ->
            setState { copy(exceptionsLoading = false) }
            report(error)
        })
    }

    internal fun loadFraudAlerts() {
        launchResult(repository::fraudAlerts, { rows ->
            setState { copy(fraudAlerts = rows, fraudLoading = false) }
        }, { error ->
            setState { copy(fraudLoading = false) }
            report(error)
        })
    }

    internal fun loadFxVariances() {
        launchResult(repository::fxVariances, { rows ->
            setState { copy(fxVariances = rows, fxLoading = false) }
        }, { error ->
            setState { copy(fxLoading = false) }
            report(error)
        })
    }

    /** Tax types, the chart, the lock and departments — each empty on failure, see [BankRecLookups]. */
    private fun loadLookups() {
        setState { copy(lookups = lookups.copy(company = lookupSource.company())) }
        // Four services, so four independent reads: one slow chart must not
        // hold back the tax types a form is already waiting on.
        launch {
            val taxTypes = lookupSource.taxTypes()
            setState { copy(lookups = lookups.copy(taxTypes = taxTypes)) }
        }
        launch {
            val codes = lookupSource.nominalCodes()
            setState { copy(lookups = lookups.copy(nominalCodes = codes)) }
        }
        launch {
            val locked = lookupSource.lockedThrough()
            setState { copy(lookups = lookups.copy(lockedThrough = locked)) }
        }
        launch {
            val departments = lookupSource.departments()
            setState { copy(lookups = lookups.copy(departments = departments)) }
        }
    }

    private fun listen() {
        val bus = events
        if (bus == null || listening) return
        listening = true
        launch {
            bankRefreshes(bus).collect { slices -> slices.forEach(::applyRefresh) }
        }
    }

    private fun collectBadges() {
        val source = badges ?: return
        badgeJob = launch { source.counts.collect { counts -> setState { copy(badges = counts) } } }
    }

    /** A tab's chip clears when it is looked at — the web's clear-on-view. */
    private fun markTabRead(tab: BankTab) {
        val key = tab.badgeKey ?: return
        val source = badges ?: return
        launch { source.markRead(key) }
    }

    /**
     * A slice another client has changed.
     *
     * Every list is the module's, so each is refreshed wherever it is shown;
     * the portal and rules are refetched only when their tab has been opened.
     */
    private fun applyRefresh(slice: BankRefresh) {
        when (slice) {
            BankRefresh.Periods -> loadPeriods()
            BankRefresh.Workspace -> {
                loadPeriods()
                workspaceActions.refresh()
            }

            BankRefresh.Exceptions -> loadExceptions()
            BankRefresh.Fraud -> loadFraudAlerts()
            BankRefresh.Fx -> loadFxVariances()
            BankRefresh.PortalLinks -> if (currentState.portal.loaded) portalActions.loadLinks()
            BankRefresh.Rules -> if (currentState.rules.loaded) rulesActions.load(force = true)
        }
    }

    // -- seams for the collaborators ---------------------------------------

    internal val repo: BankRecRepository get() = repository

    internal val exportFiles: BankRecFiles? get() = files

    internal val people: BankRecDirectory get() = directory

    internal val company get() = lookupSource.company()

    internal val ui: BankRecUiState get() = currentState

    internal fun update(reducer: BankRecUiState.() -> BankRecUiState) = setState(reducer)

    internal fun notify(message: String) = setState { copy(notice = message) }

    internal fun report(error: ZillitError) = sendEffect(BankRecEffect.Failed(error.localised()))

    internal fun refuse(message: String) = sendEffect(BankRecEffect.Failed(message))

    internal fun emit(effect: BankRecEffect) = sendEffect(effect)

    internal fun launchWork(block: suspend CoroutineScope.() -> Unit): Job = launch(block)

    /** Runs [block] after [millis] — the module's timed flashes and auto-closes. */
    internal fun after(millis: Long, block: () -> Unit): Job = launch {
        delay(millis)
        block()
    }

    internal fun <T> runResult(
        block: suspend () -> ZillitResult<T>,
        onSuccess: (T) -> Unit,
        onError: (ZillitError) -> Unit = ::report,
    ): Job = launchResult(block, onSuccess, onError)

    /** Saves an export and opens it, reporting either failure. */
    internal suspend fun deliver(fileName: String, bytes: ZillitResult<ByteArray>): Boolean {
        val target = files ?: run {
            refuse(str(S.desktop_cannot_save_exports))
            return false
        }
        return when (bytes) {
            is ZillitResult.Failure -> {
                // The host's byte POST words its refusal from the server's own
                // envelope ("Export answered 403: …"); an Unknown error would
                // otherwise surface as "Something went wrong" and hide it.
                val error = bytes.error
                val said = (error as? ZillitError.Unknown)?.technical?.takeIf { it.isNotBlank() }
                if (said != null) refuse(said) else report(error)
                false
            }

            is ZillitResult.Success -> when (val saved = target.saveAndOpen(fileName, bytes.data)) {
                is ZillitResult.Failure -> {
                    report(saved.error)
                    false
                }

                is ZillitResult.Success -> {
                    notify(str(S.desktop_exported_file, fileName))
                    true
                }
            }
        }
    }
}
