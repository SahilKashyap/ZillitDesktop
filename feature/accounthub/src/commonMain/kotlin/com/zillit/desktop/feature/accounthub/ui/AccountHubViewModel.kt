package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.accounthub.data.hubRefreshes
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubTarget
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.validationError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Account Hub console's one view model.
 *
 * ## Four screens, one model
 *
 * The hub is a console, and its screens share a viewer, a sidebar and a set of
 * reference data — currencies are read by Production Setup and by Vendors,
 * companies by Production Setup and by the bank editor. Four view models would
 * mean four copies of that, fetched four times and disagreeing whenever one is
 * refreshed.
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
 */
@Suppress("TooManyFunctions") // One handler per user action; see detekt.yml.
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
) : ZillitViewModel<AccountHubUiState, AccountHubEvent, AccountHubEffect>(AccountHubUiState()) {

    private var started = false
    private var listening = false
    private var searchJob: Job? = null
    private val setupSections = SetupSections(this)

    /**
     * Resolves who this is, then opens their landing screen.
     *
     * Idempotent: a torn-off window and the tab in the frame are the same view
     * model shown twice, and fetching the whole hub again for the second is
     * pure waste.
     */
    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState {
            copy(
                viewer = identity,
                sections = HubNavigation.visibleTo(identity),
                area = HubNavigation.landing(identity),
            )
        }
        if (!identity.isBlocked) {
            val area = currentState.area
            if (area != null) {
                load(area)
            } else {
                // No console screen for this person: open their work instead
                // of a dead end. The web puts the same user in Purchase
                // Orders (`HubNavigation.landingTool`).
                HubNavigation.landingTool(identity)?.let { row ->
                    onEvent(AccountHubEvent.OpenTool(row))
                }
            }
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
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action; the work is delegated.
    override fun onEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.Open -> open(event.area)
            is AccountHubEvent.OpenTool -> handOff(event)
            AccountHubEvent.PickAgreementFiles,
            is AccountHubEvent.EditAgreementQueue,
            AccountHubEvent.UploadAgreementFiles,
            is AccountHubEvent.DeleteAgreementDocument,
            -> agreementActions.onEvent(event)
            AccountHubEvent.OpenTimecardSetup ->
                sendEffect(AccountHubEffect.OpenTool(TIMECARD_TOOL_PATH, "Time Card"))
            is AccountHubEvent.OpenSpendSetup ->
                sendEffect(AccountHubEffect.OpenTool(event.which.route, event.which.title))
            AccountHubEvent.Refresh -> currentState.area?.let(::load)
            AccountHubEvent.ClearNotice -> setState { copy(notice = null) }
            else -> onScreenEvent(event)
        }
    }

    private fun onScreenEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SwitchSetupTab -> setState { copy(setup = setup.copy(tab = event.tab)) }
            else -> if (!formConfigActions.onEvent(event)) onSetupEvent(event)
        }
    }


    // -- shell --------------------------------------------------------------

    private fun open(area: HubArea) {
        if (area !in HubNavigation.areasFor(currentState.viewer)) return
        setState { copy(area = area) }
        load(area)
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
        sendEffect(AccountHubEffect.OpenTool(target.toolPath, event.item.label))
    }

    private fun load(area: HubArea) {
        when (area) {
            HubArea.ProductionSetup -> loadSetup()
            HubArea.ChartOfAccounts -> loadChart()
            HubArea.Vendors -> loadVendors()
            HubArea.Approvers -> approvalActions.load()
            HubArea.Budget -> reportActions.loadBudget()
            HubArea.TrialBalance -> reportActions.openTrialBalance()
            HubArea.PeriodClose -> reportActions.loadPeriodLock()
            HubArea.BibleReport -> reportActions.openBibleReport()
            HubArea.FormConfig -> formConfigActions.open()
        }
    }

    // -- production setup ---------------------------------------------------

    /**
     * Each slice on its own call.
     *
     * The combined endpoint would be one round trip, but its *response* is the
     * whole merged document — see the class doc. Losing an accountant's unsaved
     * tax rates to a currency save is worse than eight parallel gets.
     */
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
        launch {
            // The flag clears on its own rather than being counted down by each
            // call: a counter would have to survive one of them failing, and a
            // spinner that never stops is a worse bug than one that stops early.
            delay(LOAD_SETTLE_MS)
            setState { copy(setup = setup.copy(loading = false)) }
        }
    }

    private fun loadBanks() {
        launchResult(repository::bankAccounts, { rows ->
            setState { copy(setup = setup.copy(banks = rows, banksLoading = false)) }
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
    }

    // One line per setup event; a map keyed by event type would hide which
    // section each one belongs to, which is the only thing worth reading here.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun onSetupEvent(event: AccountHubEvent) {
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
            is AccountHubEvent.EditTrialBalanceQuery -> setState {
                copy(trialBalance = trialBalance.copy(draft = event.query))
            }
            AccountHubEvent.RefreshTrialBalance -> reportActions.runTrialBalance()
            is AccountHubEvent.ProposePeriodClose -> setState {
                copy(periodClose = periodClose.copy(pendingCloseMillis = event.asOfMillis))
            }
            AccountHubEvent.ConfirmPeriodClose -> reportActions.confirmPeriodClose()
            is AccountHubEvent.EditBibleQuery -> setState { copy(bible = bible.copy(draft = event.query)) }
            AccountHubEvent.RefreshBibleReport -> reportActions.runBibleReport()
            is AccountHubEvent.ToggleBibleAccount -> setState {
                val next = if (event.code in bible.collapsed) {
                    bible.collapsed - event.code
                } else {
                    bible.collapsed + event.code
                }
                copy(bible = bible.copy(collapsed = next))
            }
            AccountHubEvent.CancelPeriodClose -> setState {
                copy(periodClose = periodClose.copy(pendingCloseMillis = null))
            }
            AccountHubEvent.OpenBudgetImport,
            AccountHubEvent.CloseBudgetImport,
            AccountHubEvent.PickBudgetFile,
            is AccountHubEvent.EditBudgetImportMeta,
            is AccountHubEvent.SetCoaImportMode,
            AccountHubEvent.CommitBudgetImport,
            -> budgetImportActions.onEvent(event)
            is AccountHubEvent.SelectBudgetVersion -> {
                setState { copy(budget = budget.copy(selectedId = event.id, lines = emptyList())) }
                event.id?.let(reportActions::loadBudgetLines)
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
            AccountHubEvent.ClearPoTerms -> setState {
                copy(setup = setup.copy(poSetup = setup.poSetup.edit(setup.poSetup.edited.copy(termsDocument = null))))
            }
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
            else -> onCompanyEvent(event)
        }
    }


    private fun onCompanyEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.EditCompany -> setState {
                copy(setup = setup.copy(companyDraft = event.company ?: Company(id = newLocalId("co"))))
            }
            is AccountHubEvent.UpdateCompanyDraft ->
                setState { copy(setup = setup.copy(companyDraft = event.company)) }
            AccountHubEvent.DismissCompanyDraft -> setState { copy(setup = setup.copy(companyDraft = null)) }
            AccountHubEvent.CommitCompanyDraft -> commitCompanyDraft()
            is AccountHubEvent.RemoveCompany -> setState {
                val remaining = setup.companies.edited.filterNot { it.id == event.id }
                copy(setup = setup.copy(companies = setup.companies.edit(remaining)))
            }
            else -> onBankEvent(event)
        }
    }

    /**
     * Folds the drafted company back into the list.
     *
     * The bank re-assignment happens here rather than in the dialog because it
     * touches *other* companies: a bank moved without being taken from its
     * previous owner ends up owned twice. See [Companies.linking].
     */
    private fun commitCompanyDraft() {
        val draft = currentState.setup.companyDraft ?: return
        setState {
            val existing = setup.companies.edited
            val merged = if (existing.any { it.id == draft.id }) {
                existing.map { if (it.id == draft.id) draft else it }
            } else {
                existing + draft
            }
            copy(
                setup = setup.copy(
                    companies = setup.companies.edit(Companies.linking(merged, draft.id, draft.bankIds)),
                    companyDraft = null,
                ),
            )
        }
    }

    private fun onBankEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.EditBank -> setState {
                copy(setup = setup.copy(bankDraft = event.account ?: BankAccount(id = "")))
            }
            is AccountHubEvent.UpdateBankDraft -> setState { copy(setup = setup.copy(bankDraft = event.account)) }
            AccountHubEvent.DismissBankDraft -> setState { copy(setup = setup.copy(bankDraft = null)) }
            AccountHubEvent.CommitBankDraft -> commitBankDraft()
            is AccountHubEvent.DeleteBank -> deleteBank(event.id)
            else -> onChartEvent(event)
        }
    }

    private fun commitBankDraft() {
        val draft = currentState.setup.bankDraft ?: return
        if (!requireEdit()) return
        if (draft.name.isBlank()) {
            sendEffect(AccountHubEffect.Failed("Give the bank account a name."))
            return
        }
        launchResult(
            { if (draft.id.isBlank()) repository.createBankAccount(draft) else repository.updateBankAccount(draft) },
            {
                setState { copy(setup = setup.copy(bankDraft = null), notice = "Bank account saved.") }
                loadBanks()
            },
            ::report,
        )
    }

    private fun deleteBank(id: String) {
        if (!requireEdit()) return
        launchResult({ repository.deleteBankAccount(id) }, {
            setState { copy(notice = "Bank account removed.") }
            loadBanks()
        }, ::report)
    }

    // -- chart of accounts --------------------------------------------------

    private fun loadChart() {
        setState { copy(chart = chart.copy(loading = true)) }
        launchResult(
            // Every row, active or not. Filtering server-side would hide the
            // inactive codes the screen has a toggle for, and would manufacture
            // orphans out of rows whose only problem is an inactive parent.
            { repository.accounts(activeOnly = false) },
            { rows -> setState { copy(chart = chart.copy(accounts = rows, loading = false)) } },
            { error ->
                setState { copy(chart = chart.copy(loading = false)) }
                report(error)
            },
        )
        // The Layers tab's own source. Loaded beside the chart rather than on
        // the tab opening, so switching tabs does not stall on a request.
        launchResult(repository::trackingSets, { sets ->
            setState { copy(chart = chart.copy(trackingSets = sets)) }
        }, ::report)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action.
    private fun onChartEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SwitchChartView -> setState { copy(chart = chart.copy(view = event.view)) }
            is AccountHubEvent.SearchChart -> setState { copy(chart = chart.copy(search = event.term)) }
            AccountHubEvent.ToggleInactiveAccounts ->
                setState { copy(chart = chart.copy(showInactive = !chart.showInactive)) }
            is AccountHubEvent.ToggleAccountExpanded -> setState {
                val next = chart.expanded.toMutableSet()
                if (!next.add(event.id)) next.remove(event.id)
                copy(chart = chart.copy(expanded = next))
            }
            is AccountHubEvent.ComposeAccount -> composeAccount(event)
            AccountHubEvent.DismissAccountForm -> setState { copy(chart = chart.copy(form = null)) }
            AccountHubEvent.SaveAccount -> saveAccount()
            is AccountHubEvent.DeactivateAccount -> deactivateAccount(event.id)
            else -> onAccountFormEvent(event)
        }
    }

    private fun composeAccount(event: AccountHubEvent.ComposeAccount) {
        if (!requireAccountant()) return
        val editing = event.editing
        val form = if (editing != null) {
            AccountForm(
                editing = editing,
                name = editing.name,
                costType = editing.costType,
                isActive = editing.isActive,
                isPosting = editing.isPosting,
            )
        } else {
            // One level below the row it was raised from, which is what
            // "add under this" almost always means.
            val lineType = event.parent?.lineType?.childType ?: CoaLineType.Header
            AccountForm(draft = NewAccount(lineType = lineType, parentId = event.parent?.id))
        }
        setState { copy(chart = chart.copy(form = form)) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per field.
    private fun onAccountFormEvent(event: AccountHubEvent) {
        val form = currentState.chart.form ?: return onVendorEvent(event)
        val next = when (event) {
            is AccountHubEvent.SetAccountCode -> form.copy(draft = form.draft.copy(code = event.code))
            is AccountHubEvent.SetAccountName ->
                form.copy(name = event.name, draft = form.draft.copy(name = event.name))
            is AccountHubEvent.SetAccountLineType -> form.copy(
                // The parent is cleared with the level: a category's parent is
                // not a valid parent for a section, and leaving it set is how a
                // form ends up refusing to save with no visible reason.
                draft = form.draft.copy(lineType = event.lineType, parentId = null),
            )
            is AccountHubEvent.SetAccountCostType ->
                form.copy(costType = event.costType, draft = form.draft.copy(costType = event.costType))
            is AccountHubEvent.SetAccountParent -> form.copy(draft = form.draft.copy(parentId = event.parentId))
            AccountHubEvent.ToggleAccountPosting -> form.copy(
                isPosting = !form.isPosting,
                draft = form.draft.copy(isPosting = !form.isPosting),
            )
            AccountHubEvent.ToggleAccountActive -> form.copy(isActive = !form.isActive)
            else -> return onVendorEvent(event)
        }
        setState { copy(chart = chart.copy(form = next)) }
    }

    private fun saveAccount() {
        val form = currentState.chart.form ?: return
        if (!requireAccountant()) return
        val editing = form.editing
        if (editing != null) {
            launchResult(
                { repository.updateAccount(editing.id, form.name, form.costType, form.isActive, form.isPosting) },
                {
                    setState { copy(chart = chart.copy(form = null), notice = "Account updated.") }
                    loadChart()
                },
                { error ->
                    setState { copy(chart = chart.copy(form = form.copy(saving = false))) }
                    report(error)
                },
            )
            return
        }
        val problem = form.draft.validationError(currentState.chart.accounts)
        if (problem != null) {
            sendEffect(AccountHubEffect.Failed(problem))
            return
        }
        setState { copy(chart = chart.copy(form = form.copy(saving = true))) }
        launchResult({ repository.createAccount(form.draft) }, {
            setState { copy(chart = chart.copy(form = null), notice = "Account created.") }
            loadChart()
        }, { error ->
            setState { copy(chart = chart.copy(form = form.copy(saving = false))) }
            report(error)
        })
    }

    private fun deactivateAccount(id: String) {
        if (!requireAccountant()) return
        launchResult({ repository.deactivateAccount(id) }, {
            // Deactivated, not deleted: the code stays so historical postings
            // still resolve against it.
            setState { copy(notice = "Account deactivated.") }
            loadChart()
        }, ::report)
    }

    // -- vendors ------------------------------------------------------------

    private val vendorActions = VendorActions(this)

    private val agreementActions = AgreementActions(this, agreementFiles)

    private val reportActions = ReportActions(this, defaultReportPeriod)

    private val formConfigActions = FormConfigActions(this)

    private val approvalActions = ApprovalActions(this)

    /** The chain of screen dispatchers ends here — see [VendorActions]. */
    internal fun onApprovalEvent(event: AccountHubEvent) = approvalActions.onEvent(event)

    private val budgetImportActions = BudgetImportActions(this, agreementFiles)

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

    private fun loadVendors() = vendorActions.load()

    private fun onVendorEvent(event: AccountHubEvent) = vendorActions.onEvent(event)

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

    // -- seams for SetupSections -------------------------------------------

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

        const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * How long the setup spinner runs.
         *
         * The eight slice fetches land independently and any one of them may
         * fail; a counter would have to be unwound correctly in every failure
         * path, and a spinner that never stops is worse than one that stops
         * a moment early over data that is already on screen.
         */
        const val LOAD_SETTLE_MS = 400L
    }
}
