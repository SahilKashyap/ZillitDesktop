package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.bankrec.data.BankRefresh
import com.zillit.desktop.feature.bankrec.data.bankRefreshes
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.StatementUploader
import kotlinx.coroutines.CoroutineScope

/**
 * Bank Reconciliation.
 *
 * Eight tabs over one month's statement: what it says, what the ledger says,
 * and every way the two disagree. The period is the unit throughout — more
 * than one can be open at once, because a statement spanning several months
 * opens a period per month.
 *
 * The tabs are not independent. Importing a statement produces transactions,
 * exceptions, fraud alerts and FX variances together; matching a line
 * auto-accepts its fraud alert and usually clears an exception. That fan-out
 * is the service's, and the socket collector mirrors it rather than reloading
 * everything on every frame.
 */
class BankRecViewModel(
    private val repository: BankRecRepository,
    /** Live changes from other clients; null keeps the module load-once. */
    private val events: SocketEventBus? = null,
    /** Where a statement file comes from. Absent leaves import unavailable. */
    private val uploader: StatementUploader? = null,
    /** Builds a recipient's URL from a link token. */
    private val portalUrl: (String) -> String = { it },
) : ZillitViewModel<BankRecUiState, BankRecEvent, BankRecEffect>(BankRecUiState()) {

    private var started = false
    private var listening = false

    internal val workspaceActions = WorkspaceActions(this)
    internal val exceptionActions = ExceptionActions(this)
    internal val fraudActions = FraudActions(this)
    internal val fxActions = FxActions(this)
    internal val portalActions = PortalActions(this, portalUrl)
    internal val rulesActions = RulesActions(this)
    internal val periodActions = PeriodActions(this, uploader)

    fun start() {
        if (started) return
        started = true
        setState { copy(canImport = uploader != null) }
        loadPeriods()
        listen()
    }

    /** Re-reads everything when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { BankRecUiState() }
        start()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per family; each delegates.
    override fun onEvent(event: BankRecEvent) {
        when (event) {
            is BankRecEvent.OpenTab -> openTab(event.tab)
            is BankRecEvent.OpenPeriod -> workspaceActions.openPeriod(event.periodId)
            // The period list as well as the tab: every tab's header names the
            // period, and a Refresh that left it stale would answer the
            // question the user pressed it to ask.
            BankRecEvent.Refresh -> {
                loadPeriods()
                reload(currentState.tab, force = true)
            }
            BankRecEvent.ClearNotice -> setState { copy(notice = null) }
            else -> route(event)
        }
    }

    private fun route(event: BankRecEvent) {
        val handled = workspaceActions.onEvent(event) ||
            exceptionActions.onEvent(event) ||
            fraudActions.onEvent(event) ||
            fxActions.onEvent(event) ||
            portalActions.onEvent(event) ||
            rulesActions.onEvent(event) ||
            periodActions.onEvent(event)
        if (!handled) report(ZillitError.Unknown("unhandled bank reconciliation event"))
    }

    private fun openTab(tab: BankTab) {
        if (currentState.tab == tab) return
        setState { copy(tab = tab) }
        reload(tab)
    }

    /**
     * Loads what a tab needs, and only on first sight.
     *
     * A tab already holding rows is left alone: switching between tabs is how
     * this module is read, and refetching on every switch would make the
     * cheapest interaction the most expensive one. [BankRecEvent.Refresh] and
     * the socket are what bring a tab up to date.
     */
    internal fun reload(tab: BankTab, force: Boolean = false) {
        when (tab) {
            BankTab.Overview, BankTab.History -> if (force || currentState.periods.isEmpty()) loadPeriods()
            BankTab.Workspace -> workspaceActions.open(force)
            BankTab.Exceptions -> exceptionActions.load(force)
            BankTab.FraudAlerts -> fraudActions.load(force)
            BankTab.FxVariances -> fxActions.load(force)
            BankTab.GuarantorPortal -> portalActions.load(force)
            BankTab.Settings -> rulesActions.load(force)
        }
    }

    internal fun loadPeriods() {
        setState { copy(periodsLoading = true) }
        launchResult(repository::bankAccounts, { rows ->
            setState { copy(bankAccounts = rows) }
        }, { })
        // Swallowed: without rates a foreign balance is shown in its own
        // currency and said to be so, which is the honest fallback rather
        // than an error over a page that otherwise works.
        launchResult(repository::projectCurrencies, { rates ->
            setState { copy(rates = rates) }
        }, { })
        launchResult(repository::periods, { rows ->
            setState { copy(periods = rows, periodsLoading = false) }
            // The workspace follows the period list rather than holding its
            // own: a period signed off or deleted elsewhere must not leave the
            // reconciliation open on it.
            workspaceActions.onPeriodsChanged()
        }, { error ->
            setState { copy(periodsLoading = false) }
            report(error)
        })
    }

    private fun listen() {
        val bus = events
        if (bus == null || listening) return
        listening = true
        launch {
            bankRefreshes(bus).collect { slices -> slices.forEach(::applyRefresh) }
        }
    }

    /**
     * A slice another client has changed.
     *
     * Only the tab on screen is refetched, plus the period list, which every
     * tab's header reads. A background tab is reloaded when it is next opened.
     */
    private fun applyRefresh(slice: BankRefresh) {
        when (slice) {
            BankRefresh.Periods -> loadPeriods()
            BankRefresh.Workspace -> if (currentState.tab == BankTab.Workspace) workspaceActions.open(force = true)
            BankRefresh.Exceptions -> if (currentState.tab == BankTab.Exceptions) exceptionActions.load(force = true)
            BankRefresh.Fraud -> if (currentState.tab == BankTab.FraudAlerts) fraudActions.load(force = true)
            BankRefresh.Fx -> if (currentState.tab == BankTab.FxVariances) fxActions.load(force = true)
            BankRefresh.PortalLinks ->
                if (currentState.tab == BankTab.GuarantorPortal) portalActions.load(force = true)

            BankRefresh.Rules -> if (currentState.tab == BankTab.Settings) rulesActions.load(force = true)
        }
    }

    // -- seams for the collaborators ---------------------------------------

    internal val repo: BankRecRepository get() = repository

    internal val ui: BankRecUiState get() = currentState

    internal fun update(reducer: BankRecUiState.() -> BankRecUiState) = setState(reducer)

    internal fun notify(message: String) = setState { copy(notice = message) }

    internal fun report(error: ZillitError) = sendEffect(BankRecEffect.Failed(error.localised()))

    internal fun refuse(message: String) = sendEffect(BankRecEffect.Failed(message))

    internal fun emit(effect: BankRecEffect) = sendEffect(effect)

    internal fun launchWork(block: suspend CoroutineScope.() -> Unit) = launch(block)

    internal fun <T> runResult(
        block: suspend () -> ZillitResult<T>,
        onSuccess: (T) -> Unit,
        onError: (ZillitError) -> Unit = ::report,
    ) = launchResult(block, onSuccess, onError)
}
