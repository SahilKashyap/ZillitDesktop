package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.AccountPatch
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingSets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Chart of Accounts — the tree, the table, the bulk grid and the layers.
 *
 * Its own collaborator because it is the screen with the most surfaces: two
 * drawings of one tree, a full-page grid that autosaves row by row, and a tab
 * of analytical dimensions with their own CRUD. The view model only routes.
 */
@Suppress("TooManyFunctions") // One handler per action.
internal class ChartActions(private val vm: AccountHubViewModel) {

    private val bulkJobs = mutableMapOf<String, Job>()

    fun load() {
        vm.update { copy(chart = chart.copy(loading = true)) }
        vm.runResult(
            // Every row, active or not. Filtering server-side would hide the
            // inactive codes the screen has a toggle for, and would manufacture
            // orphans out of rows whose only problem is an inactive parent.
            { vm.repo.accounts(activeOnly = false) },
            { rows -> vm.update { copy(chart = chart.copy(accounts = rows, loading = false, loaded = true)) } },
            { error ->
                vm.update { copy(chart = chart.copy(loading = false)) }
                vm.report(error)
            },
        )
        // The Layers tab's own source. Loaded beside the chart rather than on
        // the tab opening, so switching tabs does not stall on a request.
        vm.runResult(vm.repo::trackingSets, { sets ->
            vm.update { copy(chart = chart.copy(trackingSets = sets)) }
        }, vm::report)
    }

    /** Reads the chart once, quietly, for the screens whose typeaheads offer its codes. */
    fun ensureLoaded() {
        val chart = vm.setupState.chart
        if (chart.loaded || chart.loading) return
        vm.update { copy(chart = chart.copy(loading = true)) }
        vm.runResult(
            { vm.repo.accounts(activeOnly = false) },
            { rows -> vm.update { copy(chart = this.chart.copy(accounts = rows, loading = false, loaded = true)) } },
            { vm.update { copy(chart = this.chart.copy(loading = false)) } },
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.SwitchChartView -> vm.update { copy(chart = chart.copy(view = event.view)) }
            is AccountHubEvent.SearchChart -> vm.update { copy(chart = chart.copy(search = event.term)) }
            AccountHubEvent.ToggleInactiveAccounts ->
                vm.update { copy(chart = chart.copy(showInactive = !chart.showInactive)) }
            is AccountHubEvent.SetChartMode -> vm.update { copy(chart = chart.copy(mode = event.mode)) }
            is AccountHubEvent.SortChart -> vm.update { copy(chart = chart.copy(sort = chart.sort.toggled(event.key))) }
            is AccountHubEvent.ToggleAccountExpanded -> vm.update {
                val next = chart.expanded.toMutableSet()
                if (!next.add(event.id)) next.remove(event.id)
                copy(chart = chart.copy(expanded = next))
            }
            AccountHubEvent.ToggleExpandAll -> vm.update {
                if (chart.expandedAll) {
                    copy(chart = chart.copy(expanded = emptySet(), expandedAll = false))
                } else {
                    copy(chart = chart.copy(expanded = chart.accounts.map { it.id }.toSet(), expandedAll = true))
                }
            }
            is AccountHubEvent.ComposeAccount -> composeAccount(event)
            AccountHubEvent.DismissAccountForm -> vm.update { copy(chart = chart.copy(form = null)) }
            AccountHubEvent.SaveAccount -> saveAccount()
            is AccountHubEvent.AskDeactivateAccount -> vm.update {
                copy(chart = chart.copy(confirmDeactivate = event.account))
            }
            AccountHubEvent.DismissDeactivateAccount -> vm.update { copy(chart = chart.copy(confirmDeactivate = null)) }
            AccountHubEvent.ConfirmDeactivateAccount ->
                vm.setupState.chart.confirmDeactivate?.let { deactivateAccount(it.id) }
            is AccountHubEvent.DeactivateAccount -> deactivateAccount(event.id)
            is AccountHubEvent.SetAccountCostTypeInline -> setCostTypeInline(event.id, event.costType)
            is AccountHubEvent.QuickCreateCode -> quickCreate(event)
            is AccountHubEvent.OpenBulkAdd -> openBulk(event.parent)
            is AccountHubEvent.EditBulkRow -> editBulkRow(event.row)
            is AccountHubEvent.AddBulkRows -> addBulkRows(event.count)
            is AccountHubEvent.RemoveBulkRow -> removeBulkRow(event.localId)
            AccountHubEvent.FinishBulkAdd -> finishBulk()
            is AccountHubEvent.ToggleLayerOpen -> vm.update {
                val next = chart.openLayers.toMutableSet()
                if (!next.add(event.setId)) next.remove(event.setId)
                copy(chart = chart.copy(openLayers = next))
            }
            is AccountHubEvent.ComposeLayerSet -> composeLayerSet(event.set)
            is AccountHubEvent.EditLayerSet -> vm.update {
                copy(chart = chart.copy(layerSetDraft = chart.layerSetDraft?.copy(set = event.set)))
            }
            AccountHubEvent.SaveLayerSet -> saveLayerSet()
            AccountHubEvent.DismissLayerSet -> vm.update { copy(chart = chart.copy(layerSetDraft = null)) }
            is AccountHubEvent.ComposeLayerNode -> composeLayerNode(event.setId, event.node)
            is AccountHubEvent.EditLayerNode -> vm.update {
                copy(chart = chart.copy(layerNodeDraft = chart.layerNodeDraft?.copy(node = event.node)))
            }
            AccountHubEvent.SaveLayerNode -> saveLayerNode()
            AccountHubEvent.DismissLayerNode -> vm.update { copy(chart = chart.copy(layerNodeDraft = null)) }
            is AccountHubEvent.AskDeleteLayer -> vm.update { copy(chart = chart.copy(layerDelete = event.delete)) }
            AccountHubEvent.DismissDeleteLayer -> vm.update { copy(chart = chart.copy(layerDelete = null)) }
            AccountHubEvent.ConfirmDeleteLayer -> confirmDeleteLayer()
            AccountHubEvent.DismissLayerInUse -> vm.update { copy(chart = chart.copy(layerInUse = null)) }
            else -> return onFormEvent(event)
        }
        return true
    }

    // -- the account form ------------------------------------------------------

    private fun composeAccount(event: AccountHubEvent.ComposeAccount) {
        if (!vm.mayActAsAccountant()) return
        val editing = event.editing
        val form = if (editing != null) {
            AccountForm(
                editing = editing,
                name = editing.name,
                costType = editing.costType,
                isActive = editing.isActive,
                isPosting = editing.isPosting,
                draft = NewAccount(
                    code = editing.code,
                    name = editing.name,
                    lineType = editing.lineType,
                    costType = editing.costType,
                    parentId = editing.parentId,
                    isPosting = editing.isPosting,
                ),
            )
        } else {
            // One level below the row it was raised from, which is what
            // "add under this" almost always means; a header otherwise.
            val lineType = event.parent?.lineType?.childType ?: CoaLineType.Header
            val costType =
                if (vm.setupState.chart.view == ChartView.BalanceSheet) CoaCostType.Asset else CoaCostType.Expense
            AccountForm(draft = NewAccount(lineType = lineType, parentId = event.parent?.id, costType = costType))
        }
        vm.update { copy(chart = chart.copy(form = form)) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per field.
    private fun onFormEvent(event: AccountHubEvent): Boolean {
        val form = vm.setupState.chart.form ?: return false
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
            else -> return false
        }
        vm.update { copy(chart = chart.copy(form = next)) }
        return true
    }

    @Suppress("ReturnCount") // One guard per rule; merging them loses which failed.

    private fun saveAccount() {
        val form = vm.setupState.chart.form ?: return
        if (!vm.mayActAsAccountant()) return
        val editing = form.editing
        if (editing != null) {
            val structural = form.structureChanged
            if (structural) {
                val parent = vm.setupState.chart.accounts.firstOrNull { it.id == form.draft.parentId }
                val problem = com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
                    .parentProblem(form.draft.lineType, parent)
                if (problem != null) return vm.sendSideEffect(AccountHubEffect.Failed(problem))
            }
            vm.update { copy(chart = chart.copy(form = form.copy(saving = true))) }
            vm.runResult(
                {
                    vm.repo.updateAccount(
                        editing.id,
                        AccountPatch(
                            name = form.name,
                            // A budget row keeps its class: the budget's own is what the cost report reads.
                            costType = if (editing.isFromBudget) editing.costType else form.costType,
                            isActive = form.isActive,
                            isPosting = form.isPosting,
                            structureChanged = structural,
                            lineType = form.draft.lineType,
                            parentId = form.draft.parentId,
                        ),
                    )
                },
                {
                    vm.update { copy(chart = chart.copy(form = null), notice = "Account updated.") }
                    load()
                },
                { error ->
                    vm.update { copy(chart = chart.copy(form = form.copy(saving = false))) }
                    vm.report(error)
                },
            )
            return
        }
        val problem = form.draft.validationError(vm.setupState.chart.accounts)
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        vm.update { copy(chart = chart.copy(form = form.copy(saving = true))) }
        vm.runResult({ vm.repo.createAccount(form.draft) }, {
            vm.update { copy(chart = chart.copy(form = null), notice = "Account created.") }
            load()
        }, { error ->
            vm.update { copy(chart = chart.copy(form = form.copy(saving = false))) }
            vm.report(error)
        })
    }

    private fun deactivateAccount(id: String) {
        if (!vm.mayActAsAccountant()) return
        vm.update { copy(chart = chart.copy(confirmDeactivate = null)) }
        vm.runResult({ vm.repo.deactivateAccount(id) }, {
            // Deactivated, not deleted: the code stays so historical postings
            // still resolve against it.
            vm.update { copy(notice = "Account deactivated.") }
            load()
        }, vm::report)
    }

    /** The table's cost-type select — a PATCH of one field, refused on a budget row. */
    private fun setCostTypeInline(id: String, costType: CoaCostType) {
        if (!vm.mayActAsAccountant()) return
        val row = vm.setupState.chart.accounts.firstOrNull { it.id == id } ?: return
        if (row.isFromBudget) {
            vm.sendSideEffect(AccountHubEffect.Failed("A budget-imported code keeps the budget's cost type."))
            return
        }
        vm.update {
            copy(
                chart = chart.copy(
                    accounts = chart.accounts.map { if (it.id == id) it.copy(costType = costType) else it },
                ),
            )
        }
        vm.runResult(
            { vm.repo.updateAccount(id, AccountPatch(row.name, costType, row.isActive, row.isPosting)) },
            { },
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
                copy(chart = chart.copy(accounts = chart.accounts + created), notice = "Code ${created.code} created.")
            }
        }, vm::report)
    }

    // -- bulk add ("New COA Entry") ---------------------------------------------

    private fun openBulk(parent: CoaAccount?) {
        if (!vm.mayActAsAccountant()) return
        val costType =
            if (vm.setupState.chart.view == ChartView.BalanceSheet) CoaCostType.Asset else CoaCostType.Expense
        val first = newBulkRow(parent, costType)
        vm.update {
            copy(chart = chart.copy(bulk = BulkAddState(parent = parent, rows = listOf(first), costType = costType)))
        }
    }

    private fun newBulkRow(parent: CoaAccount?, costType: CoaCostType) = CoaBulkRow(
        localId = vm.newLocalId("bulk"),
        lineType = parent?.lineType?.childType ?: CoaLineType.Header,
        parentId = parent?.id,
        costType = costType,
    )

    private fun addBulkRows(count: Int) = vm.update {
        val bulk = chart.bulk ?: return@update this
        val rows = List(count) { newBulkRow(bulk.parent, bulk.costType) }
        copy(chart = chart.copy(bulk = bulk.copy(rows = bulk.rows + rows)))
    }

    /**
     * Edits a row and schedules its autosave.
     *
     * Two seconds after the last keystroke, as the web's grid does; a row
     * without a code is never sent, and a duplicate is marked rather than
     * created.
     */
    private fun editBulkRow(row: CoaBulkRow) {
        vm.update {
            val bulk = chart.bulk ?: return@update this
            copy(
                chart = chart.copy(
                    bulk = bulk.copy(rows = bulk.rows.map { if (it.localId == row.localId) row else it }),
                ),
            )
        }
        bulkJobs[row.localId]?.cancel()
        bulkJobs[row.localId] = vm.launchWork {
            delay(CoaBulk.SAVE_DEBOUNCE_MS)
            flushRow(row.localId)
        }
    }

    private suspend fun flushRow(localId: String) {
        val bulk = vm.setupState.chart.bulk ?: return
        val row = bulk.rows.firstOrNull { it.localId == localId } ?: return
        if (!CoaBulk.isReady(row)) return
        if (CoaBulk.isDuplicate(row, bulk.rows, vm.setupState.chart.accounts)) {
            setRowStatus(localId, CoaBulkStatus.Duplicate)
            return
        }
        setRowStatus(localId, CoaBulkStatus.Saving)
        val result: ZillitResult<CoaAccount> = when {
            !row.isSaved -> vm.repo.createAccount(row.toNewAccount())
            // A rename is a create under the new code then a retire of the old:
            // the code is the natural key, and the server does not rename in place.
            row.isRename -> vm.repo.createAccount(row.toNewAccount()).also { created ->
                if (created is ZillitResult.Success) vm.repo.deactivateAccount(row.serverId!!)
            }
            else -> vm.repo.updateAccount(
                row.serverId!!,
                AccountPatch(row.name, row.costType, row.isActive, row.isPosting),
            )
        }
        when (result) {
            is ZillitResult.Success -> vm.update {
                val current = chart.bulk ?: return@update this
                copy(
                    chart = chart.copy(
                        bulk = current.copy(
                            rows = current.rows.map {
                                if (it.localId == localId) {
                                    it.copy(
                                        serverId = result.data.id,
                                        savedCode = result.data.code,
                                        status = CoaBulkStatus.Saved,
                                        error = "",
                                    )
                                } else {
                                    it
                                }
                            },
                        ),
                        // Straight into the chart, so the parent pickers and
                        // duplicate check see it without a reload.
                        accounts = chart.accounts.filterNot { it.id == result.data.id } + result.data,
                    ),
                )
            }
            is ZillitResult.Failure -> setRowStatus(localId, CoaBulkStatus.Error, result.error)
        }
    }

    private fun setRowStatus(localId: String, status: CoaBulkStatus, error: ZillitError? = null) = vm.update {
        val bulk = chart.bulk ?: return@update this
        copy(
            chart = chart.copy(
                bulk = bulk.copy(
                    rows = bulk.rows.map {
                        if (it.localId == localId) it.copy(
                            status = status,
                            error = error?.localised().orEmpty(),
                        ) else it
                    },
                ),
            ),
        )
    }

    private fun CoaBulkRow.toNewAccount() = NewAccount(
        code = code,
        name = name.ifBlank { code },
        lineType = lineType,
        costType = costType,
        parentId = parentId,
        isPosting = isPosting,
    )

    /** Removing a saved row retires it; an unsaved one just goes. */
    private fun removeBulkRow(localId: String) {
        bulkJobs.remove(localId)?.cancel()
        val row = vm.setupState.chart.bulk?.rows?.firstOrNull { it.localId == localId } ?: return
        vm.update {
            val bulk = chart.bulk ?: return@update this
            copy(chart = chart.copy(bulk = bulk.copy(rows = bulk.rows.filterNot { it.localId == localId })))
        }
        row.serverId?.let { id -> vm.runResult({ vm.repo.deactivateAccount(id) }, { }, vm::report) }
    }

    /** Done: flush anything mid-debounce so a fast click cannot drop an edit, then close and reload. */
    private fun finishBulk() {
        val pending = bulkJobs.keys.toList()
        bulkJobs.values.forEach { it.cancel() }
        bulkJobs.clear()
        vm.launchWork {
            pending.forEach { flushRow(it) }
            vm.update { copy(chart = chart.copy(bulk = null)) }
            load()
        }
    }

    // -- layers -------------------------------------------------------------------

    private fun composeLayerSet(set: TrackingSet?) {
        if (!vm.mayActAsAccountant()) return
        val draft = set ?: TrackingSet(
            id = "",
            color = TrackingSets.colorFor(vm.setupState.chart.trackingSets.size),
            isActive = true,
        )
        vm.update { copy(chart = chart.copy(layerSetDraft = LayerSetDraft(draft, isNew = set == null))) }
    }

    private fun saveLayerSet() {
        val draft = vm.setupState.chart.layerSetDraft ?: return
        val problem = TrackingSets.setProblem(draft.set.name, draft.set.prefix)
        if (problem != null) return vm.sendSideEffect(AccountHubEffect.Failed(problem))
        vm.update { copy(chart = chart.copy(layerSetDraft = draft.copy(saving = true))) }
        vm.runResult(
            { if (draft.isNew) vm.repo.createTrackingSet(draft.set) else vm.repo.updateTrackingSet(draft.set) },
            {
                vm.update {
                    copy(
                        chart = chart.copy(layerSetDraft = null),
                        notice = if (draft.isNew) "Layer created." else "Layer saved.",
                    )
                }
                reloadLayers()
            },
            { error ->
                vm.update { copy(chart = chart.copy(layerSetDraft = draft.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    private fun composeLayerNode(setId: String, node: TrackingNode?) {
        if (!vm.mayActAsAccountant()) return
        val draft = node ?: TrackingNode(id = "", setId = setId, isActive = true)
        vm.update { copy(chart = chart.copy(layerNodeDraft = LayerNodeDraft(draft, isNew = node == null))) }
    }

    private fun saveLayerNode() {
        val draft = vm.setupState.chart.layerNodeDraft ?: return
        val problem = TrackingSets.nodeProblem(draft.node.code, draft.node.name)
        if (problem != null) return vm.sendSideEffect(AccountHubEffect.Failed(problem))
        vm.update { copy(chart = chart.copy(layerNodeDraft = draft.copy(saving = true))) }
        vm.runResult(
            { if (draft.isNew) vm.repo.createTrackingNode(draft.node) else vm.repo.updateTrackingNode(draft.node) },
            {
                vm.update {
                    copy(
                        chart = chart.copy(layerNodeDraft = null),
                        notice = if (draft.isNew) "Code added." else "Code saved.",
                    )
                }
                reloadLayers()
            },
            { error ->
                vm.update { copy(chart = chart.copy(layerNodeDraft = draft.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    /**
     * Deletes a layer or a code. A refusal because something still uses it is
     * shown in the server's words, in its own dialog, as the web's `InUseModal`.
     */
    private fun confirmDeleteLayer() {
        val delete = vm.setupState.chart.layerDelete ?: return
        vm.update { copy(chart = chart.copy(layerDelete = null)) }
        vm.runResult(
            {
                when (delete) {
                    is LayerDelete.WholeSet -> vm.repo.deleteTrackingSet(delete.set.id)
                    is LayerDelete.OneNode -> vm.repo.deleteTrackingNode(delete.setId, delete.node.id)
                }
            },
            {
                vm.update { copy(notice = "Deleted.") }
                reloadLayers()
            },
            { error ->
                vm.update { copy(chart = chart.copy(layerInUse = error.localised())) }
            },
        )
    }

    private fun reloadLayers() {
        vm.runResult(vm.repo::trackingSets, { sets ->
            vm.update { copy(chart = chart.copy(trackingSets = sets)) }
        }, vm::report)
    }
}
