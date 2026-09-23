package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.accounthub.data.hubRefreshes
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubBadgeCounts
import com.zillit.desktop.feature.accounthub.domain.HubBadges
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubDocumentOpener
import com.zillit.desktop.feature.accounthub.domain.HubExporter
import com.zillit.desktop.feature.accounthub.domain.HubFiles
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubTarget
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Account Hub console's one view model.
 *
 * ## Nine screens, one model
 *
 * The hub is a console, and its screens share a viewer, a sidebar and a set of
 * reference data — currencies are read by Production Setup and by Vendors,
 * companies by Production Setup and by the bank editor, the chart by every
 * code typeahead. Nine view models would mean nine copies of that, fetched
 * nine times and disagreeing whenever one is refreshed.
 *
 * ## Rights first, and read at start
 *
 * Which sidebar rows exist, which screens open and whether saves are offered
 * all come from the viewer, so [start] resolves it before anything is fetched.
 * It is a lambda rather than a constructor value because the model is built
 * with the app, before a production is open — capturing rights at construction
 * fixes every user as "unknown" for the life of the process.
 *
 * ## Sections save one at a time
 *
 * Every Production Setup section commits on its own endpoint and re-snapshots
 * from the server's echo. That is not a fetching preference: a combined save
 * returns the whole merged settings document, and applying it would overwrite
 * whatever a sibling section has unsaved.
 *
 * ## One dispatcher, many collaborators
 *
 * Every screen's handling lives in its own class; the view model only decides
 * which one an event belongs to. Each collaborator answers whether it took the
 * event, so nothing is swallowed silently on the way down the chain.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One seam per host concern; see detekt.yml.
class AccountHubViewModel(
    private val repository: AccountHubRepository,
    /**
     * Live changes from other clients; null keeps the hub load-once.
     *
     * Ahead of [viewer] deliberately: `viewer` is the trailing lambda at call
     * sites, and a parameter added after it would capture that lambda instead.
     */
    private val events: SocketEventBus? = null,
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> AccountHubViewer,
    /**
     * Choosing and storing agreement documents; null leaves that section
     * read-only. The picker and the upload are the host's, not this service's.
     */
    private val agreementFiles: AgreementFiles? = null,
    /**
     * The period a report opens on — the calendar year so far, from the host.
     *
     * A seam because a year boundary needs a calendar and a zone, and this
     * module has neither. The default is a zero window, which the report reads
     * as "nothing asked for yet".
     */
    private val defaultReportPeriod: () -> ReportPeriod = { ReportPeriod(0, 0) },
    /** Now, for the period close's "this week". Injected so it is testable. */
    private val clock: () -> Long = { 0 },
    /**
     * Department id to name.
     *
     * The host's, because the hub's own service does not list them and the
     * pay breakdown's scope names departments rather than numbering them.
     */
    private val departments: suspend () -> Map<String, String> = { emptyMap() },
    /** The crew roster, for every user picker. The host's, like the departments. */
    private val users: suspend () -> List<HubUser> = { emptyList() },
    /** Departments with their designations, for the payroll groups and approvers. */
    private val departmentList: suspend () -> List<HubDepartment> = { emptyList() },
    /** The binary report exports; null hides the export menus. */
    internal val exporter: HubExporter? = null,
    /** Where an export lands; null hides the export menus too. */
    internal val files: HubFiles? = null,
    /** Opens a stored document in the OS; null hides the Open actions. */
    internal val documentOpener: HubDocumentOpener? = null,
    /** The open production, for the tour's per-project seen flag. */
    private val projectId: () -> String = { "" },
    /** Its name, for the bible's banner and the export headers. */
    private val projectName: () -> String = { "" },
    /** Whether the setup tour has been dismissed on this production before. */
    private val tourSeen: suspend (String) -> Boolean = { true },
    private val markTourSeen: suspend (String) -> Unit = {},
    /**
     * Whether the host renders the other film tools inside this console.
     *
     * The web does — Purchase Orders, Invoices, the spend tools and the reports
     * are nested routes under the hub shell. When true, a sidebar tool row and
     * the setup tiles' hand-offs show the tool in place and the console lands
     * on Purchase Orders as the web does; when false they open the tool in its
     * own window.
     */
    private val embedsTools: Boolean = false,
    /**
     * A sidebar tool row taken: the host reads the row's ledger rows where
     * the tool behind it has no finer read of its own (the spend and invoice
     * modules), so the sidebar's count falls the way the web's does once the
     * module is on screen. Purchase Orders and Bank Reconciliation read their
     * own tabs and rows and are left alone here.
     */
    private val readToolRow: (itemId: String, isAccountant: Boolean) -> Unit = { _, _ -> },
) : ZillitViewModel<AccountHubUiState, AccountHubEvent, AccountHubEffect>(AccountHubUiState()) {

    private var started = false
    private var listening = false
    private var searchJob: Job? = null

    private val setupSections = SetupSections(this)
    private val setupUi = SetupUiActions(this)
    private val setupModal = SetupModalActions(this)
    private val ruleImport = RuleImportActions(this)
    private val chartActions = ChartActions(this)
    private val vendorActions = VendorActions(this)
    private val agreementActions = AgreementActions(this, agreementFiles)
    private val reportActions = ReportActions(this, defaultReportPeriod)
    private val trialBalanceActions = TrialBalanceActions(this)
    private val bibleActions = BibleActions(this)
    private val formConfigActions = FormConfigActions(this)
    private val approvalActions = ApprovalActions(this)
    private val budgetImportActions = BudgetImportActions(this, agreementFiles)
    private val tourActions = TourActions(this, projectId, tourSeen, markTourSeen)

    /** The dispatch chain, in the order the screens are reached. */
    private val handlers: List<(AccountHubEvent) -> Boolean> = listOf(
        ::onShellEvent,
        tourActions::onEvent,
        setupUi::onEvent,
        setupModal::onEvent,
        ruleImport::onEvent,
        ::onSetupEvent,
        agreementActions::onEvent,
        budgetImportActions::onEvent,
        chartActions::onEvent,
        vendorActions::onEvent,
        approvalActions::onEvent,
        reportActions::onEvent,
        trialBalanceActions::onEvent,
        bibleActions::onEvent,
        formConfigActions::onEvent,
    )

    /**
     * Resolves who this is, then opens their landing screen.
     *
     * Idempotent: a torn-off window and the tab in the frame are the same view
     * model shown twice, and fetching the whole hub again for the second is
     * pure waste.
     */
    /**
     * Whether the crew directory has been read.
     *
     * It is fetched once at start, but the project context that answers it
     * loads asynchronously — a console opened a second after a production
     * does get an empty list, and every approver, assignee and "verified by"
     * on every screen then shows an id. So an empty roster is re-read the
     * next time a screen opens.
     */
    private var rosterLoaded = false

    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState {
            copy(
                viewer = identity,
                sections = HubNavigation.visibleTo(identity),
                area = HubNavigation.landing(identity),
                vendors = vendors.copy(viewerId = identity.userId),
                projectName = projectName(),
            )
        }
        if (!identity.isBlocked) {
            loadRoster()
            val area = currentState.area
            if (area != null) load(area)
            // The web lands everyone on Purchase Orders (`poEntryPath.js`) with
            // the hub beside it. Embedding makes that possible here too; a host
            // that opens tools in their own windows keeps the console's own
            // landing, and only a person with no console screen is handed off.
            val landingRow = if (embedsTools) HubNavigation.purchaseOrdersRow(identity) else null
            (landingRow ?: HubNavigation.landingTool(identity))?.let { row -> onEvent(AccountHubEvent.OpenTool(row)) }
        }

        // A vendor verified, an account code changed. Only the area on screen
        // reloads; every area reloads on open anyway. `listening` outlives
        // `started`, which onProjectChanged resets, so a production switch
        // does not stack a second collector.
        val bus = events
        if (bus != null && !listening) {
            listening = true
            launch {
                hubRefreshes(bus).collect { area ->
                    if (currentState.area == area) load(area)
                }
            }
            formConfigActions.listen(bus)
        }
    }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { AccountHubUiState() }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Only the viewer changes here; the open page and its data are already
     * right. The sidebar is rebuilt too, because which rows exist is the
     * viewer's to say — and losing view access mid-session empties it, which
     * is the web's live redirect (`useAccountHubViewGate`).
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState {
            copy(
                viewer = resolved,
                sections = HubNavigation.visibleTo(resolved),
                vendors = vendors.copy(viewerId = resolved.userId),
            )
        }
    }

    /** The host's badge ledger, mapped to the sidebar's units. */
    fun onBadges(counts: HubBadgeCounts) = setState { copy(badges = counts) }

    override fun onEvent(event: AccountHubEvent) {
        handlers.firstOrNull { it(event) }
    }

    // -- shell --------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One branch per shell action.
    private fun onShellEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.Open -> open(event.area)
            is AccountHubEvent.OpenTool -> handOff(event)
            is AccountHubEvent.EmbedRoute -> setState {
                copy(embedded = embedded?.copy(path = event.path) ?: EmbeddedTool(event.path, ""))
            }
            AccountHubEvent.CloseEmbedded -> setState { copy(embedded = null) }
            AccountHubEvent.Back -> sendEffect(AccountHubEffect.Back)
            AccountHubEvent.BackToHub -> backToHub()
            AccountHubEvent.OpenTimecardSetup -> show(TIMECARD_TOOL_PATH, "Time Card")
            is AccountHubEvent.OpenSpendSetup -> show(event.which.route, event.which.title)
            is AccountHubEvent.CreatePurchaseOrder -> show(PURCHASE_ORDER_NEW_PATH, "Purchase Orders")
            AccountHubEvent.Refresh -> currentState.area?.let(::load)
            AccountHubEvent.ClearNotice -> setState { copy(notice = null) }
            is AccountHubEvent.SwitchSetupTab -> setState { copy(setup = setup.copy(tab = event.tab)) }
            else -> return false
        }
        return true
    }

    private fun open(area: HubArea) {
        if (area !in HubNavigation.areasFor(currentState.viewer)) return
        setState { copy(area = area, embedded = null) }
        load(area)
        tourActions.onAreaOpened(area)
    }

    /**
     * Where a report page's back arrow goes: the console's landing, as
     * [start] opens it. The web's `/film-tools/account-hub` redirects to
     * Purchase Orders inside the shell, which is what embedding reproduces;
     * without it the viewer's own landing screen is the hub's front door.
     */
    private fun backToHub() {
        val identity = currentState.viewer
        HubNavigation.landing(identity)?.let(::open)
        val landingRow = if (embedsTools) HubNavigation.purchaseOrdersRow(identity) else null
        (landingRow ?: HubNavigation.landingTool(identity))?.let { row -> onEvent(AccountHubEvent.OpenTool(row)) }
    }

    /**
     * Shows another film tool: inside the console when the host embeds tools,
     * as its own window otherwise. One place, so the sidebar rows, the setup
     * tiles and "Create PO" cannot disagree about which.
     */
    private fun show(path: String, title: String) {
        if (embedsTools) {
            setState { copy(embedded = EmbeddedTool(path, title)) }
            tourActions.onToolShown()
        } else {
            sendEffect(AccountHubEffect.OpenTool(path, title))
        }
    }

    /**
     * A sidebar row that points at another tool.
     *
     * Reported as an effect rather than handled here: the target owns its own
     * routes and its own idea of the user's role, and the hub knows only where
     * it lives.
     */
    private fun handOff(event: AccountHubEvent.OpenTool) {
        val target = event.item.target as? HubTarget.Tool ?: return
        val badge = HubBadges.countFor(event.item.id, currentState.viewer.isAccountant, currentState.badges)
        if (badge > 0) readToolRow(event.item.id, currentState.viewer.isAccountant)
        show(target.toolPath, event.item.label)
    }

    private fun load(area: HubArea) {
        // A roster that came back empty is read again here: the context that
        // answers it may simply not have landed when the console started.
        if (!rosterLoaded || currentState.users.isEmpty()) loadRoster()
        when (area) {
            HubArea.ProductionSetup -> loadSetup()
            HubArea.ChartOfAccounts -> chartActions.load()
            HubArea.Vendors -> vendorActions.load()
            HubArea.Approvers -> approvalActions.load()
            HubArea.Budget -> reportActions.loadBudget()
            HubArea.TrialBalance -> trialBalanceActions.open()
            HubArea.PeriodClose -> reportActions.openPeriodClose()
            HubArea.BibleReport -> bibleActions.open()
            HubArea.FormConfig -> formConfigActions.open()
        }
    }

    /**
     * The crew and the departments, once.
     *
     * Every picker on every screen reads these, and the setup's own load
     * would otherwise fetch them again for each. Both are swallowed on
     * failure: a picker with ids instead of names is worse to read but never
     * loses a choice somebody made.
     */
    private fun loadRoster() {
        launchResult({ ZillitResult.Success(users()) }, { rows -> setState { copy(users = rows) } }, { })
        rosterLoaded = true
        launchResult(
            { ZillitResult.Success(departmentList()) },
            { rows -> setState { copy(departmentList = rows) } },
            { },
        )
    }

    // -- production setup ---------------------------------------------------

    /**
     * Each slice on its own call.
     *
     * The combined endpoint would be one round trip, but its *response* is the
     * whole merged document — see the class doc. Losing an accountant's unsaved
     * tax rates to a currency save is worse than eight parallel gets.
     */
    @Suppress("LongMethod") // One launch per slice; the list is the contract.
    private fun loadSetup() {
        setState { copy(setup = setup.copy(loading = true, banksLoading = true)) }

        launchResult(repository::companies, { rows ->
            setState { copy(setup = setup.copy(companies = setup.companies.loaded(rows))) }
        }, ::report)
        launchResult(repository::currencies, { settings ->
            setState { copy(setup = setup.copy(currencies = setup.currencies.loaded(settings))) }
        }, ::report)
        launchResult(repository::taxTypes, { rows ->
            setState { copy(setup = setup.copy(taxTypes = setup.taxTypes.loaded(rows))) }
        }, ::report)
        launchResult(repository::assetTags, { tags ->
            setState { copy(setup = setup.copy(assetTags = setup.assetTags.loaded(tags))) }
        }, ::report)
        launchResult(repository::productionSchedule, { schedule ->
            setState {
                copy(setup = setup.copy(schedule = setup.schedule.loaded(ScheduleForm.from(schedule))))
            }
        }, ::report)
        launchResult(repository::payrollDefaults, { defaults ->
            setState {
                copy(setup = setup.copy(payrollDefaults = setup.payrollDefaults.loaded(defaults)))
            }
        }, ::report)
        launchResult(repository::dealConditions, { rows ->
            setState { copy(setup = setup.copy(dealConditions = setup.dealConditions.loaded(rows))) }
        }, ::report)
        launchResult(repository::payrollBureaus, { rows ->
            setState { copy(setup = setup.copy(payrollBureaus = setup.payrollBureaus.loaded(rows))) }
        }, ::report)
        launchResult(repository::allowancesRentals, { value ->
            setState { copy(setup = setup.copy(allowances = setup.allowances.loaded(value))) }
        }, ::report)
        launchResult(repository::payrollSettings, { value ->
            setState { copy(setup = setup.copy(payrollSettings = setup.payrollSettings.loaded(value))) }
        }, ::report)
        launchResult(repository::purchaseOrderSetup, { value ->
            setState { copy(setup = setup.copy(poSetup = setup.poSetup.loaded(value))) }
        }, ::report)
        launchResult(repository::invoicesSetup, { value ->
            setState { copy(setup = setup.copy(invoicesSetup = setup.invoicesSetup.loaded(value))) }
        }, ::report)
        launchResult(repository::nonUnionPay, { value ->
            setState { copy(setup = setup.copy(nonUnionPay = setup.nonUnionPay.loaded(value))) }
        }, ::report)
        // Swallowed: without names the picker shows ids, which is worse to
        // read but never loses a scope the production configured.
        launchResult({ ZillitResult.Success(departments()) }, { rows ->
            setState { copy(setup = setup.copy(departments = rows)) }
        }, { })
        launchResult(repository::dayTypes, { rows ->
            // Seeded on read as well as on save, so a fresh project shows the
            // three defaults rather than an empty catalogue — and so the
            // section does not read as unsaved the moment it loads.
            setState { copy(setup = setup.copy(dayTypes = setup.dayTypes.loaded(DayTypes.seeded(rows)))) }
        }, ::report)
        agreementActions.load()
        loadBanks()
        loadCatalogues()
        // The code typeaheads on this page read the chart; loaded quietly so
        // a bank's nominal can be checked against it before it is sent.
        chartActions.ensureLoaded()
        launch {
            // The flag clears on its own rather than being counted down by each
            // call: a counter would have to survive one of them failing, and a
            // spinner that never stops is a worse bug than one that stops early.
            delay(LOAD_SETTLE_MS)
            setState { copy(setup = setup.copy(loading = false, loaded = true)) }
            tourActions.onSetupLoaded()
        }
    }

    internal fun loadBanks() {
        launchResult(repository::bankAccounts, { rows ->
            setState { copy(setup = setup.copy(banks = rows, banksLoading = false, banksLoaded = true)) }
        }, { error ->
            setState { copy(setup = setup.copy(banksLoading = false)) }
            report(error)
        })
    }

    /**
     * Reference catalogues, fetched once.
     *
     * Neither changes while the app is open, and both are wanted by more than
     * one section — re-fetching per section is what makes a settings page feel
     * slow for data that is effectively static.
     */
    private fun loadCatalogues() {
        if (currentState.setup.currencyCatalogue.isEmpty()) {
            launchResult(repository::currencyCatalogue, { rows ->
                setState { copy(setup = setup.copy(currencyCatalogue = rows)) }
            }, ::report)
        }
        if (currentState.setup.countryTaxes.isEmpty()) {
            launchResult(repository::taxesByCountry, { rows ->
                setState { copy(setup = setup.copy(countryTaxes = rows)) }
            }, ::report)
        }
        // The company editor's country picker reads the ISD list the vendor
        // form reads (ZL-20594) — one row per country, not per currency.
        vendorActions.loadCountries()
    }

    // One line per setup event; a map keyed by event type would hide which
    // section each one belongs to, which is the only thing worth reading here.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun onSetupEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.EditCompanies ->
                setState { copy(setup = setup.copy(companies = setup.companies.edit(event.companies))) }
            is AccountHubEvent.EditCurrencies ->
                setState { copy(setup = setup.copy(currencies = setup.currencies.edit(event.settings))) }
            is AccountHubEvent.EditTaxTypes ->
                setState { copy(setup = setup.copy(taxTypes = setup.taxTypes.edit(event.taxTypes))) }
            is AccountHubEvent.EditAssetTags ->
                setState { copy(setup = setup.copy(assetTags = setup.assetTags.edit(event.tags))) }
            is AccountHubEvent.EditBudget ->
                setState { copy(setup = setup.copy(budget = setup.budget.edit(event.budget))) }
            is AccountHubEvent.EditSchedule ->
                setState { copy(setup = setup.copy(schedule = setup.schedule.edit(event.schedule))) }
            is AccountHubEvent.EditPayrollDefaults -> setState {
                copy(setup = setup.copy(payrollDefaults = setup.payrollDefaults.edit(event.defaults)))
            }
            is AccountHubEvent.EditDealConditions -> setState {
                copy(setup = setup.copy(dealConditions = setup.dealConditions.edit(event.conditions)))
            }
            is AccountHubEvent.EditNonUnionPay -> setState {
                copy(setup = setup.copy(nonUnionPay = setup.nonUnionPay.edit(event.value)))
            }
            is AccountHubEvent.ApplyPayToEveryone -> setState {
                val pay = setup.nonUnionPay
                copy(
                    setup = setup.copy(
                        nonUnionPay = pay.edit(
                            if (event.everyone) {
                                pay.edited.appliedToEveryone()
                            } else {
                                pay.edited.appliedTo(pay.edited.departmentIds)
                            },
                        ),
                    ),
                )
            }
            is AccountHubEvent.TogglePayDepartment -> setState {
                val pay = setup.nonUnionPay
                val next = if (event.on) {
                    pay.edited.departmentIds + event.departmentId
                } else {
                    pay.edited.departmentIds - event.departmentId
                }
                copy(setup = setup.copy(nonUnionPay = pay.edit(pay.edited.appliedTo(next))))
            }
            is AccountHubEvent.EditDayTypes -> setState {
                copy(setup = setup.copy(dayTypes = setup.dayTypes.edit(event.rows)))
            }
            is AccountHubEvent.EditInvoicesSetup -> setState {
                copy(setup = setup.copy(invoicesSetup = setup.invoicesSetup.edit(event.value)))
            }
            is AccountHubEvent.EditPoSetup -> setState {
                copy(setup = setup.copy(poSetup = setup.poSetup.edit(event.value)))
            }
            AccountHubEvent.PickPoTerms -> agreementActions.pickPoTerms()
            AccountHubEvent.OpenPoTerms -> agreementActions.openPoTerms()
            is AccountHubEvent.EditPayrollSettings -> setState {
                copy(setup = setup.copy(payrollSettings = setup.payrollSettings.edit(event.value)))
            }
            is AccountHubEvent.EditAllowances -> setState {
                copy(setup = setup.copy(allowances = setup.allowances.edit(event.value)))
            }
            is AccountHubEvent.EditPayrollBureaus -> setState {
                copy(setup = setup.copy(payrollBureaus = setup.payrollBureaus.edit(event.bureaus)))
            }
            is AccountHubEvent.SaveSection -> setupSections.save(event.section)
            is AccountHubEvent.RevertSection -> setupSections.revert(event.section)
            else -> return false
        }
        return true
    }

    /** Whether a budget file can be imported at all — the host wired storage. */
    internal val canImportBudget: Boolean get() = budgetImportActions.isAvailable

    /**
     * Re-reads the versions after an import created one, and shows it.
     *
     * The list otherwise keeps whatever was selected, which after an import is
     * the version the accountant was looking at *before* they made a new one.
     */
    internal fun reloadBudget(selecting: String?) {
        if (selecting != null) {
            setState { copy(budget = budget.copy(selectedId = selecting, lines = emptyList())) }
        }
        reportActions.loadBudget()
    }

    /** Whether the agreements section can accept a file at all. */
    internal val canAttachAgreements: Boolean get() = agreementFiles != null

    /** Whether the report pages may offer an export — both host seams wired. */
    internal val canExport: Boolean get() = exporter != null && files != null

    internal val canOpenDocuments: Boolean get() = documentOpener != null

    // -- shared -------------------------------------------------------------

    /**
     * Refuses a write this person cannot make.
     *
     * The server 403s anyway; this is so the refusal names the reason instead
     * of surfacing a raw rejection after the work has been typed.
     */
    private fun requireEdit(): Boolean {
        if (currentState.viewer.canEdit) return true
        sendEffect(AccountHubEffect.Failed("You do not have permission to change this."))
        return false
    }

    /**
     * Refuses an operation the service reserves for the accounts department.
     *
     * Separate from [requireEdit] because an admin passes that and still gets
     * `accountant_access_only` here — see [AccountHubViewer.canActAsAccountant].
     */
    private fun requireAccountant(): Boolean {
        if (currentState.viewer.canActAsAccountant) return true
        sendEffect(AccountHubEffect.Failed("Only the accounts department can do that."))
        return false
    }

    private fun <T> commit(
        marking: AccountHubUiState.() -> AccountHubUiState,
        call: suspend () -> ZillitResult<T>,
        done: AccountHubUiState.(T) -> AccountHubUiState,
        failed: AccountHubUiState.() -> AccountHubUiState,
        notice: String,
    ) {
        setState(marking)
        launchResult(call, { value ->
            setState { done(value).copy(notice = notice) }
        }, { error ->
            setState(failed)
            report(error)
        })
    }

    /**
     * Waits for typing to stop.
     *
     * Without it the register fires one request per keystroke — nineteen for a
     * single search term, measured on Document Distribution before the same
     * fix went in there.
     */
    internal fun debounced(block: () -> Unit) {
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            block()
        }
    }

    internal fun report(error: ZillitError) {
        sendEffect(AccountHubEffect.Failed(error.localised()))
    }

    /** A refusal this client made itself — a validation message, already in the user's words. */
    internal fun fail(message: String) {
        sendEffect(AccountHubEffect.Failed(message))
    }

    // -- seams for the collaborators ---------------------------------------

    internal val repo: AccountHubRepository get() = repository

    internal val setupState: AccountHubUiState get() = currentState

    internal fun update(reducer: AccountHubUiState.() -> AccountHubUiState) = setState(reducer)

    internal fun mayEdit(): Boolean = requireEdit()

    internal fun mayActAsAccountant(): Boolean = requireAccountant()

    /** The host's clock, for the one screen that needs "now" on screen. */
    internal fun nowMillis(): Long = clock()

    internal fun sendSideEffect(effect: AccountHubEffect) = sendEffect(effect)

    /** [launch] is protected on the base class; collaborators need it too. */
    internal fun launchWork(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = launch(block)

    /** [launchResult] is protected on the base class; collaborators need it too. */
    internal fun <T> runResult(
        block: suspend () -> ZillitResult<T>,
        onSuccess: (T) -> Unit,
        onError: (ZillitError) -> Unit = {},
    ) = launchResult(block, onSuccess, onError)

    internal fun newLocalId(prefix: String): String = "$prefix-${localIdCounter++}"

    internal fun <T> commitSection(
        marking: AccountHubUiState.() -> AccountHubUiState,
        call: suspend () -> ZillitResult<T>,
        done: AccountHubUiState.(T) -> AccountHubUiState,
        failed: AccountHubUiState.() -> AccountHubUiState,
        notice: String,
    ) = commit(marking, call, done, failed, notice)

    /** The chart collaborator, for the screens that reach into it — the bank editor's code check. */
    internal val chart: ChartActions get() = chartActions

    internal val reports: ReportActions get() = reportActions

    internal val approvals: ApprovalActions get() = approvalActions

    private var localIdCounter = 1

    private companion object {
        /**
         * Where the Time Card tile hands off to.
         *
         * The literal rather than `TIMECARD_PATH`: this module does not depend
         * on `feature:timecard`, and the sidebar's own tool rows name their
         * routes the same way.
         */
        const val TIMECARD_TOOL_PATH = "/film-tools/timecard"

        /** A department user's "Create PO" — the PO tool's new-order page. */
        const val PURCHASE_ORDER_NEW_PATH = "/film-tools/purchase-order/new"

        const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * How long the setup spinner runs.
         *
         * The slice fetches land independently and any one of them may fail;
         * a counter would have to be unwound correctly in every failure path,
         * and a spinner that never stops is worse than one that stops a moment
         * early over data that is already on screen.
         */
        const val LOAD_SETTLE_MS = 400L
    }
}
