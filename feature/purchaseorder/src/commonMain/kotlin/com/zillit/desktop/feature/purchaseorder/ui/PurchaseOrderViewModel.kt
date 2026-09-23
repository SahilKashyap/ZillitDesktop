package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.sync.NewOperation
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.data.LOCAL_ID_PREFIX
import com.zillit.desktop.feature.purchaseorder.data.PO_CREATE_KIND
import com.zillit.desktop.feature.purchaseorder.data.QueuedPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.data.toLocalOrder
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoProjectSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PoSortDirection
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTermsFiles
import com.zillit.desktop.feature.purchaseorder.domain.PoBadges
import com.zillit.desktop.feature.purchaseorder.domain.PoUnread
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The purchase order tool's view model.
 *
 * Same shape as the two expense tools — per-destination loading, reload after
 * every mutation — so the three read alike. See `CashExpensesViewModel` for the
 * reasoning behind that arrangement.
 *
 * ## How the work is split
 *
 * The tool has four surfaces that each carry their own state machine: the
 * order lists, the create/edit form, the accountant's processing page, and the
 * two registers (templates and delivery addresses). They are separate
 * collaborators — [PoFormActions], [PoEntryActions], [PoRegisterActions] —
 * because one class holding all four had already passed detekt's LargeClass
 * line twice, and because the reload rules differ: a template save must not
 * refetch an order list, and posting to the ledger must.
 *
 * ## Offline
 *
 * With [offline] wired, three things change and nothing else does: the form
 * is kept on disk as it is typed and restored on reopen; raising an order with
 * no network queues it (and it appears in the lists as "waiting to send")
 * instead of failing; and a list that cannot be fetched is shown from its last
 * good copy, dated. A raise that fails because the request never left the
 * machine is queued too — that is not a refusal.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One handler per user action, one host seam per concern.
class PurchaseOrderViewModel(
    internal val repository: PurchaseOrderRepository,
    private val viewer: () -> PoViewer,
    internal val offline: OfflineSupport? = null,
    internal val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /**
     * The form the accountant configured for purchase orders.
     *
     * A seam rather than a repository call because the template belongs to the
     * account hub's service, not this one, and the default — an empty template
     * — is what "show every field" means.
     */
    private val formTemplate: suspend () -> ZillitResult<FormTemplate> = {
        ZillitResult.Success(FormTemplate())
    },
    /** The accounts team and the departments the rule pickers and Reassign offer; null offers none. */
    private val people: PoSettingsPeople? = null,
    /** Picking and storing the terms document; null leaves that block read-only. */
    termsFiles: PoTermsFiles? = null,
    /**
     * Companies, tax types, departments and currencies — the account hub's
     * project settings, which the form's selectors read.
     *
     * A seam for the same reason the form template is one: they belong to the
     * hub's service. The web fetches them once on entry
     * (`ProjectSettingsProvider`) and shares them between both role views.
     */
    private val projectSettings: PoProjectSettings? = null,
    /** Picking and uploading an order's paperwork; null leaves the attach button off. */
    internal val attachmentFiles: PoTermsFiles? = null,
    /** The ledger's rows for this tool, and its read. */
    internal val badges: PoBadges = PoBadges.None,
) : ZillitViewModel<PoUiState, PoEvent, PoEffect>(PoUiState(viewer = viewer())) {

    // The base class keeps its reducers protected; the collaborators work
    // through these.
    internal fun update(reducer: PoUiState.() -> PoUiState) = setState(reducer)
    internal fun launchWork(block: suspend () -> Unit): Job = launch { block() }
    internal fun emit(effect: PoEffect) = sendEffect(effect)
    internal fun fail(message: String) = sendEffect(PoEffect.Failed(message))
    internal fun ask(prompt: PoPrompt) = setState { copy(prompt = prompt) }
    /** The current state, for the collaborators. Named `ui` because `state` is the base class's flow. */
    internal val ui: PoUiState get() = currentState

    private val settingsActions = PoSettingsActions(this, repository, people, termsFiles)
    internal val formActions = PoFormActions(this, repository)
    internal val entryActions = PoEntryActions(this, repository)
    internal val processActions = PoProcessActions(this, repository)
    private val queryActions = PoQueryActions(this, repository)
    internal val registerActions = PoRegisterActions(this, repository)

    private var loadJob: Job? = null
    private var syncWatch: Job? = null
    private var started = false
    internal val json = Json { ignoreUnknownKeys = true }

    /** Resolves the viewer and opens their landing page. Idempotent. */
    fun start() {
        if (started) return
        started = true
        loadFormTemplate()
        val identity = viewer()
        setState { copy(viewer = identity, destination = PoDestination.landingFor(identity)) }
        launch { loadVendors() }
        launch { loadTeam() }
        launch { loadProjectSettings() }
        launch { loadPoSettings() }
        loadWorkflow()
        launch { formActions.restoreDraft() }
        watchSync()
        listenOnce()
        load(currentState.destination)
    }

    /**
     * Folds the socket's announcements into the screen: another client's
     * raise, decision or vendor edit lands as a reload of whatever is open —
     * the web's own port shape (every `po:*` frame becomes a parameterless
     * refetch). Guarded so a project switch restarting the tool does not
     * stack collectors, and debounced per kind because the backend fans one
     * action into several frames — the web coalesces the same way
     * (`accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch { badges.leaves.collect { leaves -> setState { copy(unread = PoUnread(leaves)) } } }
        launch {
            repository.refreshes.collect { kind ->
                syncJobs.remove(kind)?.cancel()
                syncJobs[kind] = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    when (kind) {
                        PoRefresh.Orders -> load(currentState.destination)
                        PoRefresh.Vendors -> loadVendors()
                        // The accountant changed which fields this form has.
                        PoRefresh.FormTemplate -> loadFormTemplate()
                        // Another accountant saved the settings or a rule; the
                        // open tab re-reads quietly, as the web's does.
                        PoRefresh.Settings -> {
                            loadPoSettings()
                            if (currentState.destination == PoDestination.Settings) settingsActions.load(silent = true)
                        }

                        // A template or a saved address changed. The orders
                        // reload with them: an order's header prints its
                        // delivery address, so an edited address dates every
                        // row on screen.
                        PoRefresh.Register -> {
                            registerActions.reload()
                            load(currentState.destination)
                        }
                    }
                }
            }
        }
    }

    private var listening = false
    private val syncJobs = mutableMapOf<PoRefresh, Job>()

    fun onProjectChanged() {
        started = false
        setState {
            copy(
                form = null,
                entry = null,
                localOrders = emptyList(),
                staleSince = null,
                templates = emptyList(),
                addresses = emptyList(),
            )
        }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on.
     *
     * One extra care here: the department view's All POs tab is gated on a
     * right that arrives with this call, so a viewer sitting on a tab that has
     * just become invisible is moved to their landing page rather than left
     * looking at rows they may no longer see.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        val moved = !currentState.destination.visibleTo(resolved)
        setState {
            copy(
                viewer = resolved,
                destination = if (moved) PoDestination.landingFor(resolved) else destination,
            )
        }
        if (moved) load(currentState.destination)
        // A route asked for before the rights said who this is — `/queue`
        // before the viewer was known to be in accounts — is honoured now.
        pendingRoute?.let(::openRoute)
    }

    /**
     * The route the host last asked for, while it names a page this viewer
     * cannot see *yet* — re-applied when the rights arrive.
     */
    private var pendingRoute: String? = null

    /**
     * Honours a tool route — every time the host shows the tool, not only the
     * first.
     *
     * The Account Hub re-embeds this tool on its bare path and hands off
     * deeper ones (`/queue/my`, `/posted`, `/new`); [start] is idempotent, so a
     * route applied only there was ignored on every re-entry and the tool
     * stayed wherever it was left. The bare path is the role's landing, as the
     * web's bare `/purchase-orders` is — except over a half-filled form or an
     * open processing page, which a re-entry must not throw away.
     */
    fun openRoute(path: String) {
        val state = currentState
        val page = PoDestination.forRoute(path, state.viewer)
        if (page == PoDestination.Form) {
            pendingRoute = null
            if (state.form == null) onEvent(PoEvent.CreateOrder)
            return
        }
        if (page == null && (state.form != null || state.entry != null)) return
        val target = page ?: PoDestination.landingFor(state.viewer)
        if (!target.visibleTo(state.viewer)) {
            pendingRoute = path
            return
        }
        pendingRoute = null
        // The landing is the web's `/queue/my`; a named half wins.
        val scope = PoDestination.queueScopeFor(path) ?: PoQueueScope.Mine.takeIf { page == null }
        if (scope != null && scope != state.queueScope) setState { copy(queueScope = scope) }
        if (target != state.destination) open(target)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per user action.
    override fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.Refresh -> {
                loadWorkflow()
                load(currentState.destination)
            }
            is PoEvent.Open -> open(event.destination)
            is PoEvent.OpenQueue -> {
                setState { copy(queueScope = event.scope) }
            }

            is PoEvent.Search -> setState { copy(search = event.query) }
            is PoEvent.Filter -> setState { copy(quickFilter = event.filter) }
            is PoEvent.FilterDepartment -> setState { copy(departmentFilter = event.departmentId) }
            is PoEvent.Sort -> setState { copy(sortKey = event.key, sortColumn = null) }
            is PoEvent.SortColumn -> setState {
                // The same column again flips the direction; a different one
                // starts ascending, which is what a first click means.
                if (sortColumn == event.column) {
                    copy(
                        sortDirection = if (sortDirection == PoSortDirection.Ascending) {
                            PoSortDirection.Descending
                        } else {
                            PoSortDirection.Ascending
                        },
                    )
                } else {
                    copy(sortColumn = event.column, sortDirection = PoSortDirection.Ascending)
                }
            }

            // A row in the locked cost-report period never joins a selection —
            // the web's `toggleOne` refuses it — so no bulk action can reach it.
            is PoEvent.ToggleSelection -> setState {
                val locked = orderById(event.id)?.let(::isLocked) == true
                when {
                    event.id in selection -> copy(selection = selection - event.id)
                    locked -> this
                    else -> copy(selection = selection + event.id)
                }
            }

            is PoEvent.SelectAll -> setState {
                // All on, or all off: a header checkbox that only ever adds is
                // a control with no way back.
                val ids = event.ids.filterNot { id -> orderById(id)?.let(::isLocked) == true }
                copy(selection = if (ids.isEmpty() || selection.containsAll(ids)) emptySet() else ids.toSet())
            }

            PoEvent.ClearSelection -> setState { copy(selection = emptySet()) }
            PoEvent.ClearNotice -> setState { copy(notice = null) }

            is PoEvent.Ask -> setState { copy(prompt = event.prompt) }
            is PoEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            PoEvent.DismissPrompt -> setState { copy(prompt = null) }
            PoEvent.ConfirmPrompt -> resolvePrompt()

            PoEvent.OpenVendors -> sendEffect(PoEffect.OpenVendors)
            PoEvent.OpenInvoices -> sendEffect(PoEffect.OpenInvoices)
            PoEvent.OpenFormConfiguration -> sendEffect(PoEffect.OpenFormConfig)

            is PoEvent.EditSettings, is PoEvent.SaveSettings, PoEvent.PickTermsDocument,
            PoEvent.OpenTermsDocument, PoEvent.AddRule, is PoEvent.EditRule, is PoEvent.RemoveRule,
            -> settingsActions.onEvent(event)

            is PoEvent.ProcessOrder, is PoEvent.EditEntry, PoEvent.AddEntryLine, is PoEvent.RemoveEntryLine,
            is PoEvent.SplitEntryLine, is PoEvent.SplitEntryLineByPeriod,
            PoEvent.SaveEntry, PoEvent.PostEntry, PoEvent.CloseEntry,
            -> processActions.onEvent(event)

            is PoEvent.OpenQuery, is PoEvent.EditQuery, PoEvent.SendQuery, PoEvent.CloseQuery,
            -> queryActions.onEvent(event)

            is PoEvent.OpenOrder, PoEvent.CloseOrder, is PoEvent.OpenAttachment, is PoEvent.ViewPdf,
            is PoEvent.SendVendorEmail, is PoEvent.AskReassign, is PoEvent.AskBulkReassign,
            is PoEvent.EditReassign, PoEvent.ConfirmReassign, PoEvent.DismissReassign,
            is PoEvent.AskClose, is PoEvent.EditClose, PoEvent.ConfirmClose, PoEvent.DismissClose,
            is PoEvent.AskCloseOff, is PoEvent.EditCloseOff, PoEvent.ConfirmCloseOff,
            PoEvent.DismissCloseOff, is PoEvent.AskBulkDate, is PoEvent.EditBulkDate,
            PoEvent.ConfirmBulkDate, PoEvent.DismissBulkDate,
            -> entryActions.onEvent(event)

            PoEvent.CreateOrder, is PoEvent.EditOrder, is PoEvent.ResumeDraft, is PoEvent.UseTemplate,
            is PoEvent.EditTemplate, PoEvent.CreateTemplate, is PoEvent.EditForm, PoEvent.AddLine,
            is PoEvent.RemoveLine, is PoEvent.SplitLine, is PoEvent.SplitLineByPeriod,
            PoEvent.AttachFile, is PoEvent.RemoveAttachment, PoEvent.CloseForm, PoEvent.SubmitForm,
            PoEvent.SaveDraft, is PoEvent.SaveAsTemplate, PoEvent.NameTemplate,
            is PoEvent.PickSavedAddress,
            -> formActions.onEvent(event)

            PoEvent.AddAddress, is PoEvent.EditAddressRow, is PoEvent.EditAddress, PoEvent.SaveAddress,
            PoEvent.DismissAddress, is PoEvent.DeleteTemplate,
            -> registerActions.onEvent(event)
        }
    }

    /**
     * Opens a tab.
     *
     * The quick-filter chip goes back to "All" — each tab offers a different set
     * of chips, so a chip carried across could match nothing there. The search,
     * the department filter and the sort stay: the web keeps them in module
     * state across tabs, and an accountant looking one vendor up across All POs,
     * the Queue and Posted should not retype it three times.
     */
    private fun open(destination: PoDestination) {
        if (destination == PoDestination.Vendors) {
            sendEffect(PoEffect.OpenVendors)
            return
        }
        if (destination == PoDestination.Invoices) {
            if (currentState.unread.invoices > 0) badges.readInvoices()
            sendEffect(PoEffect.OpenInvoices)
            return
        }
        setState {
            copy(
                destination = destination,
                quickFilter = com.zillit.desktop.feature.purchaseorder.domain.PoQuickFilter.All,
                selection = emptySet(),
                error = null,
                staleSince = null,
                // A tab switch leaves the form and the processing page: both
                // take over the page, so staying open would hide the tab the
                // person just chose.
                form = null,
                entry = null,
            )
        }
        load(destination)
    }

    // -- reads ---------------------------------------------------------------

    internal fun load(destination: PoDestination) {
        loadJob?.cancel()
        when (destination) {
            PoDestination.Settings -> {
                // Its own reads — the document, the rules and the pickers' lists.
                setState { copy(loading = false, error = null) }
                settingsActions.load()
                return
            }

            PoDestination.Templates -> {
                setState { copy(loading = false, error = null) }
                registerActions.loadTemplates()
                return
            }

            PoDestination.DeliveryAddresses -> {
                setState { copy(loading = false, error = null) }
                registerActions.loadAddresses()
                return
            }

            // Not a list: the web shows a placeholder here and so does this.
            PoDestination.Reports, PoDestination.Vendors, PoDestination.Invoices -> {
                setState { copy(loading = false, error = null) }
                return
            }

            else -> Unit
        }
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val result = fetch(destination)) {
                is ZillitResult.Success -> {
                    val rows = result.data.forPage(destination)
                    setState { copy(loading = false, orders = rows.named(vendors), staleSince = null) }
                    remember(destination.cacheName, ListSerializer(PurchaseOrder.serializer()), rows)
                }

                is ZillitResult.Failure -> recover(destination, result.error)
            }
        }
    }

    private suspend fun fetch(destination: PoDestination) = when (destination) {
        PoDestination.ApprovalQueue -> repository.approvalQueue()
        PoDestination.MyPos -> repository.myOrders()
        PoDestination.Drafts -> repository.orders(PoStatus.Draft)
        // The server scopes this one; see the repository.
        PoDestination.DepartmentPos -> repository.orders(null, currentState.viewer.departmentId)
        else -> repository.orders(null)
    }

    /**
     * The rows a page keeps from its endpoint's answer.
     *
     * Only one page trims: All POs drops drafts, which are private to whoever
     * wrote them and have their own tab. The web does the same and says why.
     */
    private fun List<PurchaseOrder>.forPage(destination: PoDestination) = when (destination) {
        PoDestination.AllPos, PoDestination.DepartmentAllPos -> filterNot { it.status == PoStatus.Draft }
        else -> this
    }

    private suspend fun recover(destination: PoDestination, error: ZillitError) {
        val saved = recallIfUnreachable(destination.cacheName, error, ListSerializer(PurchaseOrder.serializer()))
        when {
            saved != null -> setState {
                copy(loading = false, orders = saved.first.named(vendors), staleSince = saved.second)
            }

            // No copy to show, but the person's own unsent orders are still
            // theirs to see: an empty list under them beats an error page that
            // hides them.
            error.isUnreachable() && destination.showsLocalOrders && currentState.localOrders.isNotEmpty() ->
                setState { copy(loading = false, orders = emptyList(), staleSince = null) }

            else -> setState { copy(loading = false, error = error, staleSince = null) }
        }
    }

    private suspend fun loadVendors() {
        when (val fetched = repository.vendors()) {
            is ZillitResult.Success -> {
                setState { copy(vendors = fetched.data, orders = orders.named(fetched.data)) }
                remember(VENDORS_CACHE, ListSerializer(Vendor.serializer()), fetched.data)
            }

            is ZillitResult.Failure ->
                recallIfUnreachable(VENDORS_CACHE, fetched.error, ListSerializer(Vendor.serializer()))
                    ?.let { (saved, _) -> setState { copy(vendors = saved, orders = orders.named(saved)) } }
        }
    }

    /**
     * The accounts team, for the assignee column and the Reassign picker.
     *
     * Swallowed on failure: a name that cannot be resolved shows as "Assigned",
     * which is still true, and an error over it would be noise about something
     * nobody reading an order list can fix.
     */
    private suspend fun loadTeam() {
        val roster = people ?: return
        runCatching { roster.team() }.getOrNull()?.let { members -> setState { copy(team = members) } }
        runCatching { roster.everyone() }.getOrNull()?.let { members -> setState { copy(people = members) } }
        runCatching { roster.departments() }.getOrNull()?.let { rows -> setState { copy(departments = rows) } }
    }

    /** Companies, tax types and currencies — the form's selectors. Swallowed for the same reason. */
    private suspend fun loadProjectSettings() {
        val settings = projectSettings ?: return
        runCatching { settings.companies() }.getOrNull()?.let { rows -> setState { copy(companies = rows) } }
        runCatching { settings.taxTypes() }.getOrNull()?.let { rows -> setState { copy(taxTypes = rows) } }
        runCatching { settings.currencies() }.getOrNull()?.let { rows -> setState { copy(currencies = rows) } }
        // The hub's departments where the roster gave none — the form needs a
        // picker whether or not this viewer is on the accounts team.
        if (currentState.departments.isEmpty()) {
            runCatching { settings.departments() }.getOrNull()?.let { rows -> setState { copy(departments = rows) } }
        }
    }

    /**
     * The project's purchase-order settings.
     *
     * Read on every viewer, not just the ones who may edit them: the amend gate
     * and the rental split cadence are project-level and the form obeys both.
     * A failure leaves the defaults, which is what the web's `usePoSettings`
     * null means.
     */
    private suspend fun loadPoSettings() {
        repository.settings().getOrNull()?.let { bundle ->
            setState { copy(projectSettings = bundle.settings) }
        }
    }

    /**
     * The three account-hub reads the order workflow gates on: the approval
     * tiers (who may approve which tier), the cost-report lock (which orders
     * are read-only) and the currency rates (how a mixed total converts).
     *
     * Each is swallowed on failure and each failure is the conservative
     * reading — no tiers is nobody approving, which is the web's answer too; no
     * lock is the server's to enforce; no rates adds at face value. The tiers
     * are retried a few times, as the web's module does, because an approver
     * with no buttons has nothing on screen that says why.
     */
    private fun loadWorkflow() {
        launch {
            repeat(TIER_ATTEMPTS) { attempt ->
                val tiers = repository.approvalTiers().getOrNull()
                if (tiers != null) {
                    setState { copy(tiers = tiers) }
                    return@launch
                }
                if (attempt < TIER_ATTEMPTS - 1) delay(TIER_RETRY_MILLIS)
            }
        }
        launch { repository.periodLock().getOrNull()?.let { lock -> setState { copy(periodLock = lock) } } }
        launch { repository.currencyRates().getOrNull()?.let { rates -> setState { copy(rates = rates) } } }
    }

    /**
     * Fills in vendor names from the vendor list: the server keys an order on
     * `vendor_id` and sends no name, exactly as Android's `POMapper` resolves
     * `vendorObj?.name`. An order whose vendor is not in the list keeps blank.
     */
    private fun List<PurchaseOrder>.named(vendors: List<Vendor>): List<PurchaseOrder> {
        if (vendors.isEmpty()) return this
        val names = vendors.associate { it.id to it.name }
        return map { order ->
            if (order.vendorName.isNotBlank()) order else order.copy(vendorName = names[order.vendorId].orEmpty())
        }
    }

    // -- the outbox, as rows -------------------------------------------------

    private fun watchSync() {
        val support = offline ?: return
        syncWatch?.cancel()
        syncWatch = launch {
            var pending = support.engine.status.value.pending
            support.engine.status.collect { status ->
                setState { copy(offline = !status.online) }
                refreshLocalOrders()
                // Something queued has gone through: the server now has a row
                // where the local one was, so the list is fetched again.
                if (status.online && status.pending < pending) load(currentState.destination)
                pending = status.pending
            }
        }
    }

    internal suspend fun refreshLocalOrders() {
        val support = offline ?: return
        val local = support.engine.operations().mapNotNull { it.toLocalOrder(json) }
        setState { copy(localOrders = local) }
    }

    /** Queues an order the network could not carry, so nobody retypes it later. */
    internal suspend fun queueOrder(support: OfflineSupport, request: NewPurchaseOrder) {
        val queued = QueuedPurchaseOrder(order = request, raisedBy = currentState.viewer.userId, queuedAt = nowMillis())
        val label = str(S.desktop_po_outbox_label, request.vendorName, request.description).take(LABEL_MAX)
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = PO_CREATE_KIND,
                label = label,
                payload = json.encodeToString(QueuedPurchaseOrder.serializer(), queued),
            ),
        )
        if (enqueued == null) {
            setState { copy(busy = false) }
            sendEffect(PoEffect.Failed(str(S.desktop_po_open_project_first)))
            return
        }
        formActions.forgetDraft()
        setState { copy(busy = false, form = null, notice = QUEUED_NOTICE) }
        refreshLocalOrders()
    }

    // -- the read cache ------------------------------------------------------

    internal suspend fun <T> remember(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        support.cache.put(scope, name, json.encodeToString(serializer, value), nowMillis())
    }

    /** The saved copy, with when it was fetched — only when the failure is the network, not the server. */
    internal suspend fun <T> recallIfUnreachable(
        name: String,
        error: ZillitError,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Pair<T, Long>? {
        val scope = offline?.currentScope()
        if (!error.isUnreachable() || scope == null) return null
        val cached = offline?.cache?.get(scope, name) ?: return null
        return runCatching { json.decodeFromString(serializer, cached.json) }.getOrNull()
            ?.let { it to cached.fetchedAt }
    }

    /**
     * Reads the form's configuration.
     *
     * Failures are swallowed: the empty template shows every field, which is
     * this form as it was before templates, and an error over a working form
     * would be noise about something the person raising an order cannot fix.
     */
    private fun loadFormTemplate() {
        launchResult(formTemplate, { template -> setState { copy(formTemplate = template) } }, { })
    }

    // -- actions on existing orders --------------------------------------------

    /**
     * Whether this person may not carry out [prompt].
     *
     * Every gate the screen draws is re-checked here, on the order as it now
     * stands, because an event can arrive without the button:
     *
     * - **Approve / Reject** — the viewer must be an approver of the order's
     *   *next* tier (the web's `getApprovalVisibility(...).canApprove`), and the
     *   order must be outside the locked period. The old "the approval queue is
     *   scoped by data" reasoning held for one tab only, and accountants who sit
     *   on a tier never see that tab.
     * - **Delete** — [PoAccess.canDelete], with the processing page's own rule
     *   when the page is on that order, and never a locked order.
     * - A rule is a senior's.
     */
    private fun refusesPrompt(prompt: PoPrompt): Boolean {
        val state = currentState
        val targetId = when (prompt) {
            is PoPrompt.Confirm -> prompt.targetId
            is PoPrompt.WithReason -> prompt.targetId
        }
        val order = state.orderById(targetId)
        val decides = { order != null && state.approvalStep(order).canApprove && !state.isLocked(order) }
        val allowed = when (prompt) {
            is PoPrompt.Confirm -> when (prompt.action) {
                PoConfirmAction.Approve -> decides()
                PoConfirmAction.Delete -> order != null && state.mayDelete(order)
                PoConfirmAction.RemoveRule -> state.viewer.isSeniorAccountant
                else -> true
            }

            is PoPrompt.WithReason -> prompt.action != PoReasonAction.Reject || decides()
        }
        return !allowed
    }

    /**
     * [PoAccess.canDelete] from wherever the ask came — the processing page's
     * own rule when the page is on this order — and never a locked order.
     */
    private fun PoUiState.mayDelete(order: PurchaseOrder): Boolean {
        if (isLocked(order)) return false
        if (PoAccess.canDelete(order, viewer, onProcessingPage = entry?.orderId == order.id)) return true
        // A draft of one's own on the Drafts tab: the server lists only the
        // viewer's drafts, and some of them carry no raiser.
        return order.status == PoStatus.Draft && order.raisedBy == null
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        if (refusesPrompt(prompt)) {
            sendEffect(PoEffect.Failed(str(S.desktop_po_no_rights_on_project)))
            return
        }
        when (prompt) {
            is PoPrompt.Confirm -> when (prompt.action) {
                PoConfirmAction.Approve -> approve(prompt.targetId)
                PoConfirmAction.Delete -> {
                    // Deleting the order the processing page is on leaves the
                    // page, and lands where "Back to Queue" would have.
                    val leavesEntry = currentState.entry?.orderId == prompt.targetId
                    if (leavesEntry) {
                        setState { copy(entry = null, destination = PoDestination.Queue) }
                    }
                    act(str(S.desktop_order_deleted)) { repository.delete(prompt.targetId) }
                    setState { copy(detail = null) }
                }

                PoConfirmAction.DeleteTemplate -> registerActions.deleteTemplate(prompt.targetId)
                PoConfirmAction.RemoveRule -> settingsActions.deleteRule(prompt.targetId)
                // Confirmed: the amendment opens the form on the order.
                PoConfirmAction.AmendOrder -> formActions.openOrder(prompt.targetId, PoFormMode.EditOrder)
            }

            is PoPrompt.WithReason -> resolveAnswer(prompt)
        }
    }

    /**
     * A prompt that asked for a sentence: a rejection reason, or a template's
     * name. Blank is refused in the prompt's own words rather than a generic
     * one, because the two are asking for very different things.
     */
    private fun resolveAnswer(prompt: PoPrompt.WithReason) {
        val answer = prompt.reason.trim()
        if (answer.isEmpty()) {
            sendEffect(
                PoEffect.Failed(
                    when (prompt.action) {
                        PoReasonAction.NameTemplate -> str(S.ah_template_name_required)
                        PoReasonAction.Reject -> str(S.desktop_a_reason_is_required)
                    },
                ),
            )
            setState { copy(prompt = prompt) }
            return
        }
        when (prompt.action) {
            PoReasonAction.Reject -> {
                act(str(S.desktop_order_rejected), readsBadgeOf = prompt.targetId) {
                    repository.reject(prompt.targetId, answer)
                }
                setState { copy(detail = null) }
            }
            PoReasonAction.NameTemplate -> formActions.saveTemplate(answer)
        }
    }

    /**
     * Approves the order's next tier — the tier this viewer was cleared to
     * decide, sent as `{ tier_number, total_tiers }` exactly as the web does.
     * The dialog and the processing page both leave with it, as the web's
     * `setDetailPO(null)`.
     */
    private fun approve(id: String) {
        val order = currentState.orderById(id) ?: return
        val step = currentState.approvalStep(order)
        act(str(S.desktop_order_approved), readsBadgeOf = id) {
            repository.approve(id, step.tierNumber, step.tierCount)
        }
        setState { copy(detail = null) }
    }

    /**
     * [readsBadgeOf]: an order whose badge the act settles — a decision on
     * the approval queue reads its rows only once the server has taken it
     * (ZL-20775: a failed approve or reject leaves the badge lit, because the
     * order is still waiting on one).
     */
    internal fun act(
        success: String,
        readsBadgeOf: String? = null,
        block: suspend () -> ZillitResult<Unit>,
    ) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState { copy(busy = false, notice = success) }
                readsBadgeOf?.let { readOrderBadge(it) }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(PoEffect.Failed(result.error.localised()))
            }
        }
    }

    /**
     * An order's rows on the open tab are read — when its detail opens on any
     * badged tab (`PODetailModal`'s `readScope`), or when it is decided on the
     * approval queue. Nothing to read is nothing to send.
     */
    internal fun readOrderBadge(orderId: String) {
        val scope = currentState.destination.badgeScope ?: return
        if (currentState.unread.order(scope, orderId) > 0) badges.readOrder(scope.tool, scope.level1, orderId)
    }

    /** Keyed by what is fetched, not which tab asked — several tabs share one list. */
    private val PoDestination.cacheName: String
        get() = when (this) {
            PoDestination.ApprovalQueue -> "po.orders.approval"
            PoDestination.MyPos -> "po.orders.my"
            PoDestination.Drafts -> "po.orders.drafts"
            PoDestination.DepartmentPos -> "po.orders.department"
            else -> "po.orders.all"
        }

    internal fun ZillitError.isUnreachable() = this is ZillitError.NoConnection || this is ZillitError.Timeout

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCOUNTS_ENTERED = "ACCT_ENTERED"
        const val STATUS_DRAFT = "DRAFT"
        const val DRAFT_KIND = "po.draft"
        const val VENDORS_CACHE = "po.vendors"
        val QUEUED_NOTICE: String get() = str(S.desktop_po_queued_offline)

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L

        /** The web's tier-config retry: a few attempts, two seconds apart. */
        private const val TIER_ATTEMPTS = 3
        private const val TIER_RETRY_MILLIS = 2_000L
        internal const val DRAFT_SAVE_DEBOUNCE_MILLIS = 400L
        private const val LABEL_MAX = 80
    }
}

/**
 * Whether an order answers a search.
 *
 * The same six fields the web searches, and for the same reason it includes the
 * amount: people look an order up by what it cost as often as by its number.
 */
internal fun PurchaseOrder.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return number.lowercase().contains(needle) ||
        vendorName.lowercase().contains(needle) ||
        description.lowercase().contains(needle) ||
        nominalCode.orEmpty().lowercase().contains(needle) ||
        episode.orEmpty().lowercase().contains(needle) ||
        lines.any { it.description.lowercase().contains(needle) } ||
        formatAmount(gross).contains(needle)
}

/** Two decimals, as the search box's user typed them. */
private fun formatAmount(value: Double): String {
    val pennies = kotlin.math.round(value * PENNIES_PER_UNIT).toLong()
    return "${pennies / PENNIES_PER_UNIT_INT}." +
        (pennies % PENNIES_PER_UNIT_INT).toString().padStart(2, '0')
}

private const val PENNIES_PER_UNIT = 100.0
private const val PENNIES_PER_UNIT_INT = 100L
