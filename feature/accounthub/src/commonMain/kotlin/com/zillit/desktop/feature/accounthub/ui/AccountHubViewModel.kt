package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
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
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> AccountHubViewer,
) : ZillitViewModel<AccountHubUiState, AccountHubEvent, AccountHubEffect>(AccountHubUiState()) {

    private var started = false
    private var searchJob: Job? = null

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
            currentState.area?.let(::load)
        }
    }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { AccountHubUiState() }
        start()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action; the work is delegated.
    override fun onEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.Open -> open(event.area)
            is AccountHubEvent.OpenTool -> handOff(event)
            AccountHubEvent.Refresh -> currentState.area?.let(::load)
            AccountHubEvent.ClearNotice -> setState { copy(notice = null) }
            else -> onScreenEvent(event)
        }
    }

    private fun onScreenEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SwitchSetupTab -> setState { copy(setup = setup.copy(tab = event.tab)) }
            else -> onSetupEvent(event)
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
            HubArea.Approvers -> loadApprovals()
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
        launchResult(repository::projectBudget, { budget ->
            setState { copy(setup = setup.copy(budget = setup.budget.loaded(BudgetForm.from(budget)))) }
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

    @Suppress("CyclomaticComplexMethod") // One branch per action.
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
            is AccountHubEvent.SaveSection -> saveSection(event.section)
            is AccountHubEvent.RevertSection -> revertSection(event.section)
            else -> onCompanyEvent(event)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action.
    private fun saveSection(section: SetupSection) {
        if (!requireEdit()) return
        val setup = currentState.setup
        when (section) {
            SetupSection.Companies -> commit(
                marking = { copy(setup = this.setup.copy(companies = this.setup.companies.copy(saving = true))) },
                call = { repository.saveCompanies(setup.companies.edited) },
                done = { rows -> copy(setup = this.setup.copy(companies = this.setup.companies.committed(rows))) },
                failed = { copy(setup = this.setup.copy(companies = this.setup.companies.copy(saving = false))) },
                notice = "Companies saved.",
            )
            SetupSection.Currencies -> commit(
                marking = { copy(setup = this.setup.copy(currencies = this.setup.currencies.copy(saving = true))) },
                call = { repository.saveCurrencies(setup.currencies.edited) },
                done = { value -> copy(setup = this.setup.copy(currencies = this.setup.currencies.committed(value))) },
                failed = { copy(setup = this.setup.copy(currencies = this.setup.currencies.copy(saving = false))) },
                notice = "Currencies saved.",
            )
            SetupSection.TaxTypes -> commit(
                marking = { copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.copy(saving = true))) },
                call = { repository.saveTaxTypes(setup.taxTypes.edited) },
                done = { rows -> copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.committed(rows))) },
                failed = { copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.copy(saving = false))) },
                notice = "Tax types saved.",
            )
            SetupSection.AssetTags -> commit(
                marking = { copy(setup = this.setup.copy(assetTags = this.setup.assetTags.copy(saving = true))) },
                call = { repository.saveAssetTags(setup.assetTags.edited) },
                done = { tags -> copy(setup = this.setup.copy(assetTags = this.setup.assetTags.committed(tags))) },
                failed = { copy(setup = this.setup.copy(assetTags = this.setup.assetTags.copy(saving = false))) },
                notice = "Asset tags saved.",
            )
            SetupSection.Budget -> commit(
                marking = { copy(setup = this.setup.copy(budget = this.setup.budget.copy(saving = true))) },
                call = { repository.saveProjectBudget(setup.budget.edited.toDomain()) },
                done = { value ->
                    copy(setup = this.setup.copy(budget = this.setup.budget.committed(BudgetForm.from(value))))
                },
                failed = { copy(setup = this.setup.copy(budget = this.setup.budget.copy(saving = false))) },
                notice = "Budget saved.",
            )
            SetupSection.Schedule -> commit(
                marking = { copy(setup = this.setup.copy(schedule = this.setup.schedule.copy(saving = true))) },
                call = { repository.saveProductionSchedule(setup.schedule.edited.toDomain()) },
                done = { value ->
                    copy(setup = this.setup.copy(schedule = this.setup.schedule.committed(ScheduleForm.from(value))))
                },
                failed = { copy(setup = this.setup.copy(schedule = this.setup.schedule.copy(saving = false))) },
                notice = "Schedule saved.",
            )
            SetupSection.PayrollDefaults -> commit(
                marking = {
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.copy(saving = true)))
                },
                call = { repository.savePayrollDefaults(setup.payrollDefaults.edited) },
                done = { value ->
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.committed(value)))
                },
                failed = {
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.copy(saving = false)))
                },
                notice = "Payroll defaults saved.",
            )
        }
    }

    private fun revertSection(section: SetupSection) = setState {
        val next = when (section) {
            SetupSection.Companies -> setup.copy(companies = setup.companies.reverted())
            SetupSection.Currencies -> setup.copy(currencies = setup.currencies.reverted())
            SetupSection.TaxTypes -> setup.copy(taxTypes = setup.taxTypes.reverted())
            SetupSection.AssetTags -> setup.copy(assetTags = setup.assetTags.reverted())
            SetupSection.Budget -> setup.copy(budget = setup.budget.reverted())
            SetupSection.Schedule -> setup.copy(schedule = setup.schedule.reverted())
            SetupSection.PayrollDefaults -> setup.copy(payrollDefaults = setup.payrollDefaults.reverted())
        }
        copy(setup = next)
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

    private fun loadVendors() {
        setState { copy(vendors = vendors.copy(loading = true)) }
        launchResult({ repository.vendors(currentState.vendors.search) }, { rows ->
            setState { copy(vendors = vendors.copy(rows = rows, loading = false)) }
        }, { error ->
            setState { copy(vendors = vendors.copy(loading = false)) }
            report(error)
        })
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action.
    private fun onVendorEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SearchVendors -> {
                setState { copy(vendors = vendors.copy(search = event.term)) }
                debounced(::loadVendors)
            }
            is AccountHubEvent.SelectVendor -> selectVendor(event.id)
            is AccountHubEvent.ComposeVendor -> composeVendor(event)
            is AccountHubEvent.UpdateVendorDraft -> setState {
                copy(vendors = vendors.copy(form = vendors.form?.copy(draft = event.draft)))
            }
            AccountHubEvent.DismissVendorForm -> setState { copy(vendors = vendors.copy(form = null)) }
            AccountHubEvent.SaveVendor -> saveVendor()
            is AccountHubEvent.VerifyVendor -> verifyVendor(event.id)
            is AccountHubEvent.DeleteVendor -> deleteVendor(event.id)
            else -> onApprovalEvent(event)
        }
    }

    private fun selectVendor(id: String?) {
        setState { copy(vendors = vendors.copy(selectedId = id, history = emptyList())) }
        val vendorId = id ?: return
        setState { copy(vendors = vendors.copy(historyLoading = true)) }
        launchResult({ repository.vendorHistory(vendorId) }, { rows ->
            setState { copy(vendors = vendors.copy(history = rows, historyLoading = false)) }
        }, { error ->
            setState { copy(vendors = vendors.copy(historyLoading = false)) }
            report(error)
        })
    }

    private fun composeVendor(event: AccountHubEvent.ComposeVendor) {
        if (!requireEdit()) return
        val editing = event.editing
        setState {
            copy(
                vendors = vendors.copy(
                    form = VendorForm(
                        editingId = editing?.id,
                        draft = editing?.let(NewVendor::from) ?: NewVendor(),
                    ),
                ),
            )
        }
    }

    private fun saveVendor() {
        val form = currentState.vendors.form ?: return
        val problem = form.draft.validationError()
        if (problem != null) {
            sendEffect(AccountHubEffect.Failed(problem))
            return
        }
        setState { copy(vendors = vendors.copy(form = form.copy(saving = true))) }
        launchResult(
            { form.editingId?.let { repository.updateVendor(it, form.draft) } ?: repository.createVendor(form.draft) },
            {
                setState { copy(vendors = vendors.copy(form = null), notice = "Vendor saved.") }
                loadVendors()
            },
            { error ->
                setState { copy(vendors = vendors.copy(form = form.copy(saving = false))) }
                report(error)
            },
        )
    }

    private fun verifyVendor(id: String) {
        // Not `requireEdit`: verification is one of the two operations the
        // service reserves for the accounts department.
        if (!requireAccountant()) return
        launchResult({ repository.verifyVendor(id) }, {
            setState { copy(notice = "Vendor verified.") }
            loadVendors()
        }, ::report)
    }

    private fun deleteVendor(id: String) {
        if (!requireEdit()) return
        launchResult({ repository.deleteVendor(id) }, {
            setState { copy(vendors = vendors.copy(selectedId = null), notice = "Vendor removed.") }
            loadVendors()
        }, ::report)
    }

    // -- approvals ----------------------------------------------------------

    private fun loadApprovals() {
        setState { copy(approvals = approvals.copy(loading = true)) }
        launchResult({ repository.approvalConfigs(currentState.approvals.module) }, { rows ->
            setState { copy(approvals = approvals.copy(configs = rows, loading = false)) }
        }, { error ->
            setState { copy(approvals = approvals.copy(loading = false)) }
            report(error)
        })
    }

    private fun onApprovalEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SwitchApprovalModule -> {
                setState { copy(approvals = approvals.copy(module = event.module, configs = emptyList())) }
                loadApprovals()
            }
            is AccountHubEvent.EditApprovalConfig -> editApprovalConfig(event.config)
            is AccountHubEvent.UpdateApprovalConfig ->
                setState { copy(approvals = approvals.copy(editing = event.config)) }
            AccountHubEvent.AddApprovalLevel -> addApprovalLevel()
            is AccountHubEvent.RemoveApprovalLevel -> removeApprovalLevel(event.order)
            AccountHubEvent.SaveApprovalConfig -> saveApprovalConfig()
            AccountHubEvent.DismissApprovalConfig -> setState { copy(approvals = approvals.copy(editing = null)) }
            else -> Unit
        }
    }

    private fun editApprovalConfig(config: ApprovalConfig?) {
        if (!requireAccountant()) return
        val target = config ?: ApprovalConfig(
            module = currentState.approvals.module,
            scope = ApprovalScope.All,
            tiers = listOf(ApprovalTier(order = 1)),
        )
        setState {
            // A chain with no levels gets one, so the editor opens on something
            // to fill in rather than on an empty panel with an Add button.
            val seeded = if (target.tiers.isEmpty()) target.copy(tiers = listOf(ApprovalTier(1))) else target
            copy(approvals = approvals.copy(editing = seeded))
        }
    }

    private fun addApprovalLevel() = setState {
        val editing = approvals.editing ?: return@setState this
        val next = editing.tiers + ApprovalTier(order = editing.tiers.size + 1)
        copy(approvals = approvals.copy(editing = editing.copy(tiers = next)))
    }

    private fun removeApprovalLevel(order: Int) = setState {
        val editing = approvals.editing ?: return@setState this
        // Renumbered on removal so the levels stay 1..N — a gap in `order` is
        // what the sequence rule reads as an unfilled level.
        val next = editing.tiers.filterNot { it.order == order }
            .mapIndexed { index, tier -> tier.copy(order = index + 1) }
        copy(approvals = approvals.copy(editing = editing.copy(tiers = next)))
    }

    private fun saveApprovalConfig() {
        val editing = currentState.approvals.editing ?: return
        if (!requireAccountant()) return
        val problem = ApprovalSequence.validationError(editing.tiers)
        if (problem != null) {
            sendEffect(AccountHubEffect.Failed(problem))
            return
        }
        setState { copy(approvals = approvals.copy(saving = true)) }
        launchResult(
            // Compacted on the way out: trailing blanks the user left behind
            // are dropped rather than persisted as empty levels that stall a
            // document forever.
            { repository.saveApprovalConfig(editing.copy(tiers = ApprovalSequence.compacted(editing.tiers))) },
            {
                setState {
                    copy(
                        approvals = approvals.copy(editing = null, saving = false),
                        notice = "Approvers saved.",
                    )
                }
                loadApprovals()
            },
            { error ->
                setState { copy(approvals = approvals.copy(saving = false)) }
                report(error)
            },
        )
    }

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
    private fun debounced(block: () -> Unit) {
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            block()
        }
    }

    private fun report(error: ZillitError) {
        sendEffect(AccountHubEffect.Failed(error.localised()))
    }

    private fun newLocalId(prefix: String): String = "$prefix-${localIdCounter++}"

    private var localIdCounter = 1

    private companion object {
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
