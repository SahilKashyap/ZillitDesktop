package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.AccountPatch
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.NewAccount

/**
 * The Chart of Accounts — the tree, the table and the edit form.
 *
 * Its own collaborator because it is the screen with the most surfaces: two
 * drawings of one tree, a full-page grid that autosaves row by row
 * ([ChartBulkActions]) and a tab of analytical dimensions with their own CRUD
 * ([ChartLayerActions]). The view model only routes.
 */
internal class ChartActions(private val vm: AccountHubViewModel) {

    private val bulk = ChartBulkActions(vm, onFinished = ::reloadAccounts)

    private val layers = ChartLayerActions(vm)

    fun load() {
        reloadAccounts()
        // The Layers tab's own source. Loaded beside the chart rather than on
        // the tab opening, so switching tabs does not stall on a request.
        layers.load()
    }

    /** Reads the chart once, quietly, for the screens whose typeaheads offer its codes. */
    fun ensureLoaded() {
        val chart = vm.setupState.chart
        if (chart.loaded || chart.loading) return
        vm.update { copy(chart = chart.copy(loading = true)) }
        vm.runResult(
            { vm.repo.accounts(activeOnly = false) },
            { rows -> updateTree { copy(accounts = rows, loading = false, loaded = true) } },
            { vm.update { copy(chart = this.chart.copy(loading = false)) } },
        )
    }

    /**
     * Every row, active or not — the web's `list({ active_only: "false" })`.
     *
     * Filtering server-side would hide the retired codes the screen has a toggle
     * for, and would manufacture orphans out of rows whose only problem is an
     * inactive parent.
     */
    private fun reloadAccounts() {
        vm.update { copy(chart = chart.copy(loading = true, loadFailed = false)) }
        vm.runResult(
            { vm.repo.accounts(activeOnly = false) },
            { rows -> updateTree { copy(accounts = rows, loading = false, loaded = true, loadFailed = false) } },
            { error ->
                vm.update { copy(chart = chart.copy(loading = false, loadFailed = true)) }
                vm.report(error)
            },
        )
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.SwitchChartView -> updateTree { copy(view = event.view) }
            is AccountHubEvent.SearchChart -> vm.update { copy(chart = chart.copy(search = event.term)) }
            AccountHubEvent.ToggleInactiveAccounts -> updateTree { copy(showInactive = !showInactive) }
            is AccountHubEvent.SetChartMode -> vm.update { copy(chart = chart.copy(mode = event.mode)) }
            is AccountHubEvent.SortChart -> vm.update { copy(chart = chart.copy(sort = chart.sort.toggled(event.key))) }
            is AccountHubEvent.ToggleAccountExpanded ->
                vm.update { copy(chart = chart.toggled(event.id, event.depth)) }
            AccountHubEvent.ToggleExpandAll -> vm.update { copy(chart = chart.foldedAll()) }
            is AccountHubEvent.ComposeAccount -> composeAccount(event)
            AccountHubEvent.DismissAccountForm -> vm.update { copy(chart = chart.copy(form = null)) }
            AccountHubEvent.SaveAccount -> saveAccount()
            is AccountHubEvent.AskDeactivateAccount -> vm.update {
                copy(chart = chart.copy(confirmDeactivate = event.account))
            }
            AccountHubEvent.DismissDeactivateAccount -> vm.update {
                // Held open while the call is out, as the web's confirm is.
                if (chart.deactivating) this else copy(chart = chart.copy(confirmDeactivate = null))
            }
            AccountHubEvent.ConfirmDeactivateAccount -> confirmDeactivate()
            is AccountHubEvent.DeactivateAccount -> deactivate(event.id)
            is AccountHubEvent.SetAccountCostTypeInline -> setCostTypeInline(event.id, event.costType)
            is AccountHubEvent.QuickCreateCode -> quickCreate(event)
            else -> return bulk.onEvent(event) || layers.onEvent(event) || onFormEvent(event)
        }
        return true
    }

    /**
     * A change that can alter the top of the tree — a load, a tab, Show
     * inactive. When the roots differ afterwards, the rows opened and closed by
     * hand start over, as the web resets its open map; the fold is kept.
     */
    private fun updateTree(transform: ChartState.() -> ChartState) = vm.update {
        val next = chart.transform()
        val reset = next.rootIds != chart.rootIds
        copy(chart = if (reset) next.copy(expanded = emptySet(), collapsed = emptySet()) else next)
    }

    // -- the account form ------------------------------------------------------

    private fun composeAccount(event: AccountHubEvent.ComposeAccount) {
        if (!vm.mayActAsAccountant()) return
        val chart = vm.setupState.chart
        val form = event.editing?.let { AccountForm.editing(it, chart.accounts) }
            ?: AccountForm.adding(event.parent, chart.view.defaultCostType)
        vm.update { copy(chart = chart.copy(form = form)) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per field.
    private fun onFormEvent(event: AccountHubEvent): Boolean {
        val chart = vm.setupState.chart
        val form = chart.form ?: return false
        val next = when (event) {
            // Upper-cased as it is typed, as the web's field is.
            is AccountHubEvent.SetAccountCode -> if (form.isEdit) form else form.copy(code = event.code.uppercase())
            is AccountHubEvent.SetAccountName -> form.copy(name = event.name)
            is AccountHubEvent.SetAccountLineType -> if (form.structureLocked) form else form.copy(
                lineType = event.lineType,
                // The parent follows the level: kept while it still sits one
                // level above, otherwise the row the form was opened from, else
                // none — never a pairing the form would refuse to save.
                parentId = ChartOfAccounts.reparent(
                    event.lineType,
                    form.parentId,
                    form.preselectedParent,
                    chart.accounts,
                ),
            )
            is AccountHubEvent.SetAccountCostType ->
                if (form.structureLocked) form else form.copy(costType = event.costType)
            is AccountHubEvent.SetAccountParent ->
                if (form.structureLocked || form.lineType == CoaLineType.Header) {
                    form
                } else {
                    form.copy(parentId = event.parentId)
                }
            AccountHubEvent.ToggleAccountPosting -> form.copy(isPosting = !form.isPosting)
            AccountHubEvent.ToggleAccountActive -> form.copy(isActive = !form.isActive)
            else -> return false
        }
        vm.update { copy(chart = this.chart.copy(form = next)) }
        return true
    }

    private fun saveAccount() {
        val chart = vm.setupState.chart
        val form = chart.form ?: return
        if (!vm.mayActAsAccountant() || form.saving) return
        val problem = form.codeError(chart.accounts) ?: form.parentProblem(chart.accounts)
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        vm.update { copy(chart = this.chart.copy(form = form.copy(saving = true))) }
        val editing = form.editing
        if (editing == null) {
            val draft = NewAccount(
                code = form.code,
                name = form.name,
                lineType = form.lineType,
                costType = form.costType,
                parentId = form.parentId.takeIf { form.lineType != CoaLineType.Header },
                isPosting = form.isPosting,
                isActive = form.isActive,
            )
            vm.runResult({ vm.repo.createAccount(draft) }, { created ->
                vm.update {
                    copy(chart = this.chart.copy(form = null), notice = str(S.desktop_hub_code_x_created, created.code))
                }
                reloadAccounts()
            }, ::formFailed)
            return
        }
        val structural = form.structureChanged
        val patch = AccountPatch(
            name = form.name,
            // A budget row keeps its class: the server refuses a change of it.
            costType = if (editing.isFromBudget) editing.costType else form.costType,
            isActive = form.isActive,
            isPosting = form.isPosting,
            // Level and parent ride along only when they moved, as the web gates them.
            structureChanged = structural,
            lineType = form.lineType.takeIf { structural },
            parentId = form.parentId.takeIf { structural },
        )
        vm.runResult({ vm.repo.updateAccount(editing.id, patch) }, { saved ->
            vm.update {
                val notice = cascadeNotice(saved.cascadedDescendants) ?: str(S.saved)
                copy(chart = this.chart.copy(form = null), notice = notice)
            }
            reloadAccounts()
        }, ::formFailed)
    }

    private fun formFailed(error: com.zillit.desktop.core.common.ZillitError) {
        vm.update { copy(chart = chart.copy(form = chart.form?.copy(saving = false))) }
        vm.report(error)
    }

    /** The web's toast when a class change reached the rows beneath. */
    private fun cascadeNotice(count: Int): String? =
        when {
            count == 1 -> str(S.desktop_hub_cost_type_updated_one_descendant_also_updated)
            count > 1 -> str(S.desktop_hub_cost_type_updated_n_descendants_also_updated, count)
            else -> null
        }

    private fun confirmDeactivate() {
        val target = vm.setupState.chart.confirmDeactivate ?: return
        if (!vm.mayActAsAccountant() || vm.setupState.chart.deactivating) return
        vm.update { copy(chart = chart.copy(deactivating = true)) }
        vm.runResult({ vm.repo.deactivateAccount(target.id) }, {
            // Deactivated, not deleted: the code stays so historical postings
            // still resolve against it, chipped "Inactive" in the chart.
            vm.update {
                copy(
                    chart = chart.copy(confirmDeactivate = null, deactivating = false),
                    notice = str(S.desktop_code_deactivated),
                )
            }
            reloadAccounts()
        }, { error ->
            vm.update { copy(chart = chart.copy(deactivating = false)) }
            vm.report(error)
        })
    }

    private fun deactivate(id: String) {
        if (!vm.mayActAsAccountant()) return
        vm.runResult({ vm.repo.deactivateAccount(id) }, {
            vm.update { copy(notice = str(S.desktop_code_deactivated)) }
            reloadAccounts()
        }, vm::report)
    }

    /**
     * The table's cost-type select — a PATCH of the class alone, as the web
     * sends it, refused client-side on a budget row whose class the server
     * locks.
     *
     * The class cascades to every descendant server-side, so the chart is read
     * again afterwards: patching the one row locally would leave its children
     * in the tab it just left.
     */
    private fun setCostTypeInline(id: String, costType: CoaCostType) {
        if (!vm.mayActAsAccountant()) return
        val row = vm.setupState.chart.accounts.firstOrNull { it.id == id } ?: return
        if (row.costType == costType) return
        if (row.isFromBudget) {
            val message = str(S.desktop_hub_budget_imported_rows_are_permanently_classified_as_expense)
            vm.sendSideEffect(AccountHubEffect.Failed(message))
            return
        }
        // Moved at once, so the select shows the choice while the call is out.
        val moved = row.copy(costType = costType)
        vm.update { copy(chart = chart.copy(accounts = chart.accounts.map { if (it.id == id) moved else it })) }
        vm.runResult(
            { vm.repo.updateAccount(id, AccountPatch(costType = costType)) },
            { saved ->
                cascadeNotice(saved.cascadedDescendants)?.let { message -> vm.update { copy(notice = message) } }
                reloadAccounts()
            },
            { error ->
                vm.update { copy(chart = chart.copy(accounts = chart.accounts.map { if (it.id == id) row else it })) }
                vm.report(error)
            },
        )
    }

    /**
     * A typeahead's quick create: a postable category at the top of the
     * chart, exactly what the web's `CoaCodeInput` sends.
     */
    private fun quickCreate(event: AccountHubEvent.QuickCreateCode) {
        if (!vm.mayActAsAccountant()) return
        val draft = NewAccount(
            code = event.code,
            name = event.name.ifBlank { event.code },
            lineType = CoaLineType.Category,
            costType = event.costType,
            parentId = null,
            isPosting = true,
        )
        vm.runResult({ vm.repo.createAccount(draft) }, { created ->
            vm.update {
                copy(
                    chart = chart.copy(accounts = chart.accounts + created),
                    notice = str(S.desktop_hub_code_x_created, created.code),
                )
            }
        }, vm::report)
    }
}
