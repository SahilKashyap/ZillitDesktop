package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier

/**
 * Who signs off, in what order, per module and per department.
 *
 * Its own collaborator for the same reason the others are — and because one
 * rule here is not obvious from the screen: a department chain emptied
 * entirely is not saved as an empty chain, it is *removed*, so the department
 * falls back to the production's. The absence of a row is what "inherits"
 * means to the server.
 */
internal class ApprovalActions(private val vm: AccountHubViewModel) {






    fun load() {
        val module = vm.setupState.approvals.module
        vm.update { copy(approvals = approvals.copy(loading = true)) }
        vm.runResult({ vm.repo.approvalConfigs(module) }, { rows ->
            vm.update {
                copy(
                    approvals = approvals.copy(
                        configs = rows,
                        loading = false,
                        // The module on screen is answered from its own
                        // configs — fresher than any snapshot, and after a
                        // save it is the only correct value.
                        configured = approvals.configured +
                            (module to rows.any { it.isConfigured }),
                    ),
                )
            }
        }, { error ->
            vm.update { copy(approvals = approvals.copy(loading = false)) }
            vm.report(error)
        })
        loadApprovalSummary()
    }

    /**
     * The other modules' configured state, in one call.
     *
     * Merged under what is already known rather than over it, and a module the
     * server does not mention is left absent — see [ApprovalsState.configured].
     * A failure is silent: this only labels tabs, and an error toast about a
     * pill would be noise over a screen that otherwise works.
     */
    private fun loadApprovalSummary() {
        vm.runResult({ vm.repo.approvalSummary() }, { rows ->
            vm.update {
                copy(approvals = approvals.copy(configured = rows + approvals.configured))
            }
        }, { })
    }

    fun onEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SwitchApprovalModule -> {
                vm.update { copy(approvals = approvals.copy(module = event.module, configs = emptyList())) }
                load()
            }
            is AccountHubEvent.EditApprovalConfig -> editApprovalConfig(event.config)
            is AccountHubEvent.UpdateApprovalConfig ->
                vm.update { copy(approvals = approvals.copy(editing = event.config)) }
            AccountHubEvent.AddApprovalLevel -> addApprovalLevel()
            is AccountHubEvent.RemoveApprovalLevel -> removeApprovalLevel(event.order)
            AccountHubEvent.SaveApprovalConfig -> saveApprovalConfig()
            AccountHubEvent.DismissApprovalConfig -> vm.update { copy(approvals = approvals.copy(editing = null)) }
            else -> Unit
        }
    }

    private fun editApprovalConfig(config: ApprovalConfig?) {
        if (!vm.mayActAsAccountant()) return
        val target = config ?: ApprovalConfig(
            module = vm.setupState.approvals.module,
            scope = ApprovalScope.All,
            tiers = listOf(ApprovalTier(order = 1)),
        )
        vm.update {
            // A chain with no levels gets one, so the editor opens on something
            // to fill in rather than on an empty panel with an Add button.
            val seeded = if (target.tiers.isEmpty()) target.copy(tiers = listOf(ApprovalTier(1))) else target
            copy(approvals = approvals.copy(editing = seeded))
        }
    }

    private fun addApprovalLevel() = vm.update {
        val editing = approvals.editing ?: return@update this
        val next = editing.tiers + ApprovalTier(order = editing.tiers.size + 1)
        copy(approvals = approvals.copy(editing = editing.copy(tiers = next)))
    }

    private fun removeApprovalLevel(order: Int) = vm.update {
        val editing = approvals.editing ?: return@update this
        // Renumbered on removal so the levels stay 1..N — a gap in `order` is
        // what the sequence rule reads as an unfilled level.
        val next = editing.tiers.filterNot { it.order == order }
            .mapIndexed { index, tier -> tier.copy(order = index + 1) }
        copy(approvals = approvals.copy(editing = editing.copy(tiers = next)))
    }

    private fun saveApprovalConfig() {
        val editing = vm.setupState.approvals.editing ?: return
        if (!vm.mayActAsAccountant()) return
        // A department chain emptied entirely means "use the production's".
        // Saving it as an empty chain would leave documents waiting at a level
        // with nobody in it; the server's own answer is to delete the config
        // so the department falls back, which is what the web does too.
        if (editing.scope == ApprovalScope.Department &&
            editing.id.isNotBlank() &&
            ApprovalSequence.compacted(editing.tiers).isEmpty()
        ) {
            return revertDepartmentToGlobal(editing.id)
        }
        val problem = ApprovalSequence.validationError(editing.tiers)
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        vm.update { copy(approvals = approvals.copy(saving = true)) }
        vm.runResult(
            // Compacted on the way out: trailing blanks the user left behind
            // are dropped rather than persisted as empty levels that stall a
            // document forever.
            { vm.repo.saveApprovalConfig(editing.copy(tiers = ApprovalSequence.compacted(editing.tiers))) },
            {
                vm.update {
                    copy(
                        approvals = approvals.copy(editing = null, saving = false),
                        notice = "Approvers saved.",
                    )
                }
                load()
            },
            { error ->
                vm.update { copy(approvals = approvals.copy(saving = false)) }
                vm.report(error)
            },
        )
    }

    /**
     * Drops a department's own chain so it inherits the production's.
     *
     * The absence of a department row is what "inherits" means to the server —
     * it resolves the department first and falls back — so removing the row is
     * how a department is put back on the default, not a deletion of anything
     * a person configured deliberately.
     */
    private fun revertDepartmentToGlobal(configId: String) {
        vm.update { copy(approvals = approvals.copy(saving = true)) }
        vm.runResult({ vm.repo.deleteApprovalConfig(configId) }, {
            vm.update {
                copy(
                    approvals = approvals.copy(editing = null, saving = false),
                    notice = "This department now uses the production's approvers.",
                )
            }
            load()
        }, { error ->
            vm.update { copy(approvals = approvals.copy(saving = false)) }
            vm.report(error)
        })
    }

}
