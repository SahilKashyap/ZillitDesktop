package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingSets

/**
 * The Layers tab — the web's `TrackingCodesTab`: analytical dimensions
 * (Locations, Episodes, Funding Source…), each a flat list of codes, with
 * their own CRUD beside the nominal chart.
 */
internal class ChartLayerActions(private val vm: AccountHubViewModel) {

    fun load() {
        vm.update { copy(chart = chart.copy(layersLoading = true)) }
        vm.runResult(vm.repo::trackingSets, { sets ->
            vm.update { copy(chart = chart.copy(trackingSets = sets, layersLoading = false)) }
        }, { error ->
            vm.update { copy(chart = chart.copy(layersLoading = false)) }
            vm.report(error)
        })
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            // One set open at a time, as on the web — a project with six layers stays readable.
            is AccountHubEvent.ToggleLayerOpen -> vm.update {
                copy(chart = chart.copy(openLayer = event.setId.takeIf { it != chart.openLayer }))
            }
            is AccountHubEvent.ComposeLayerSet -> composeSet(event.set)
            is AccountHubEvent.EditLayerSet -> vm.update {
                copy(chart = chart.copy(layerSetDraft = chart.layerSetDraft?.copy(set = event.set)))
            }
            AccountHubEvent.SaveLayerSet -> saveSet()
            AccountHubEvent.DismissLayerSet -> vm.update {
                if (chart.layerSetDraft?.saving == true) this else copy(chart = chart.copy(layerSetDraft = null))
            }
            is AccountHubEvent.ComposeLayerNode -> composeNode(event.setId, event.node)
            is AccountHubEvent.EditLayerNode -> vm.update {
                copy(chart = chart.copy(layerNodeDraft = chart.layerNodeDraft?.copy(node = event.node)))
            }
            AccountHubEvent.SaveLayerNode -> saveNode()
            AccountHubEvent.DismissLayerNode -> vm.update {
                if (chart.layerNodeDraft?.saving == true) this else copy(chart = chart.copy(layerNodeDraft = null))
            }
            is AccountHubEvent.AskDeleteLayer -> vm.update { copy(chart = chart.copy(layerDelete = event.delete)) }
            AccountHubEvent.DismissDeleteLayer -> vm.update {
                if (chart.layerDeleting) this else copy(chart = chart.copy(layerDelete = null))
            }
            AccountHubEvent.ConfirmDeleteLayer -> confirmDelete()
            AccountHubEvent.DismissLayerInUse -> vm.update { copy(chart = chart.copy(layerInUse = null)) }
            else -> return false
        }
        return true
    }

    private fun composeSet(set: TrackingSet?) {
        if (!vm.mayActAsAccountant()) return
        val draft = set ?: TrackingSet(
            id = "",
            color = TrackingSets.colorFor(vm.setupState.chart.trackingSets.size),
            isActive = true,
        )
        vm.update { copy(chart = chart.copy(layerSetDraft = LayerSetDraft(draft, isNew = set == null))) }
    }

    private fun saveSet() {
        val draft = vm.setupState.chart.layerSetDraft ?: return
        if (draft.saving) return
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
                load()
            },
            { error ->
                vm.update { copy(chart = chart.copy(layerSetDraft = chart.layerSetDraft?.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    private fun composeNode(setId: String, node: TrackingNode?) {
        if (!vm.mayActAsAccountant()) return
        val draft = node ?: TrackingNode(id = "", setId = setId, isActive = true)
        vm.update { copy(chart = chart.copy(layerNodeDraft = LayerNodeDraft(draft, isNew = node == null))) }
    }

    private fun saveNode() {
        val draft = vm.setupState.chart.layerNodeDraft ?: return
        if (draft.saving) return
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
                load()
            },
            { error ->
                vm.update { copy(chart = chart.copy(layerNodeDraft = chart.layerNodeDraft?.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    /**
     * Deletes a layer or a code, holding the confirmation open while it goes.
     *
     * A refusal — normally a code still carried by line items — closes the
     * confirmation and opens the web's `InUseModal` with the server's own
     * words, which name the modules still holding it.
     */
    private fun confirmDelete() {
        val delete = vm.setupState.chart.layerDelete ?: return
        if (vm.setupState.chart.layerDeleting) return
        vm.update { copy(chart = chart.copy(layerDeleting = true)) }
        vm.runResult(
            {
                when (delete) {
                    is LayerDelete.WholeSet -> vm.repo.deleteTrackingSet(delete.set.id)
                    is LayerDelete.OneNode -> vm.repo.deleteTrackingNode(delete.setId, delete.node.id)
                }
            },
            {
                vm.update { copy(chart = chart.copy(layerDelete = null, layerDeleting = false), notice = "Deleted.") }
                load()
            },
            { error ->
                val refusal = LayerInUse(
                    title = delete.refusalTitle,
                    message = error.localised().ifBlank { delete.fallbackRefusal },
                )
                vm.update {
                    copy(chart = chart.copy(layerDelete = null, layerDeleting = false, layerInUse = refusal))
                }
            },
        )
    }
}
