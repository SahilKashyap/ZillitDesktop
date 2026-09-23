package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
 *
 * The builder is the web's full-page editor, `ApproversModule`: levels, each
 * holding one or more rules (Default, or "Amount greater than" a threshold),
 * each rule its own approvers; a picker that stages people before adding them;
 * and the web's save flow step for step — see [save].
 */
@Suppress("TooManyFunctions") // One handler per action.
internal class ApprovalActions(private val vm: AccountHubViewModel) {

    /**
     * The open module's chains, and on a first read the other modules' pills
     * and who may be picked.
     *
     * A read of the module already on screen is silent — no spinner over a
     * page that is showing — which is what the web's socket refetch does
     * after another accountant saves. Only the module still open when the
     * answer lands is written: a quick switch must not paint one module's
     * chains under another's name.
     */
    fun load() {
        val approvals = vm.setupState.approvals
        val module = approvals.module
        val silent = approvals.loadedModule == module
        vm.update { copy(approvals = this.approvals.copy(loading = !silent, loadError = null)) }
        vm.runResult({ vm.repo.approvalConfigs(module) }, { rows ->
            onModule(module) {
                copy(
                    configs = rows,
                    loading = false,
                    loadedModule = module,
                    // The module on screen is answered from its own configs —
                    // fresher than any snapshot, and after a save it is the
                    // only correct value.
                    configured = configured + (module to rows.any { it.isConfigured }),
                )
            }
        }, { error ->
            onModule(module) { copy(loading = false, loadError = error.localised()) }
        })
        if (!silent) {
            loadApprovalSummary()
            loadCandidates(module)
        }
    }

    private fun onModule(module: ApprovalModule, change: ApprovalsState.() -> ApprovalsState) = vm.update {
        if (approvals.module != module) this else copy(approvals = approvals.change())
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

    /**
     * Who holds view rights on this module's tool. A failure is recorded as
     * nobody, so the picker offers the accounts team alone — the web fails
     * closed here, and so does [com.zillit.desktop.feature.accounthub.domain.ApprovalCandidates].
     */
    private fun loadCandidates(module: ApprovalModule) {
        vm.update { copy(approvals = approvals.copy(candidateIds = null)) }
        vm.runResult({ vm.repo.approverCandidateIds(module.tool) }, { ids ->
            onModule(module) { copy(candidateIds = ids) }
        }, {
            onModule(module) { copy(candidateIds = emptySet()) }
        })
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.SwitchApprovalModule -> switchModule(event.module)
            AccountHubEvent.ReloadApprovalConfigs -> load()
            is AccountHubEvent.SearchApprovalModules -> vm.update {
                copy(approvals = approvals.copy(moduleSearch = event.term))
            }
            is AccountHubEvent.SearchDepartments -> vm.update {
                copy(approvals = approvals.copy(departmentSearch = event.term))
            }
            is AccountHubEvent.FilterDepartments -> vm.update {
                copy(approvals = approvals.copy(departmentFilter = event.filter))
            }
            is AccountHubEvent.ToggleDepartmentExpanded -> vm.update {
                val next = approvals.expanded.toMutableSet()
                if (!next.add(event.departmentId)) next.remove(event.departmentId)
                copy(approvals = approvals.copy(expanded = next))
            }
            AccountHubEvent.EditDefaultApprovals -> editDefault()
            is AccountHubEvent.EditDepartmentConfig -> editDepartment(event.departmentId)
            is AccountHubEvent.InsertApprovalLevel -> insertLevel(event.position)
            is AccountHubEvent.RemoveApprovalLevel -> removeLevel(event.order)
            is AccountHubEvent.AddApprovalRule -> addRule(event.tier)
            is AccountHubEvent.RemoveApprovalRule -> removeRule(event.tier, event.rule)
            is AccountHubEvent.SetApprovalRuleType -> setRuleType(event.tier, event.rule, event.type)
            is AccountHubEvent.SetApprovalRuleAmount -> editRule(event.tier, event.rule) {
                it.copy(amountThreshold = event.amount)
            }
            is AccountHubEvent.RemoveApprover -> editRule(event.tier, event.rule) {
                it.copy(userIds = it.userIds - event.userId)
            }
            is AccountHubEvent.OpenApproverPicker -> openPicker(event.tier, event.rule)
            AccountHubEvent.CloseApproverPicker -> updateBuilder { closedPicker() }
            is AccountHubEvent.SearchApproverPicker -> updateBuilder { copy(pickerSearch = event.term) }
            is AccountHubEvent.ToggleApproverPick -> togglePick(event.userId)
            AccountHubEvent.AddPickedApprovers -> addPicked()
            AccountHubEvent.SaveApprovalConfig -> save()
            AccountHubEvent.ConfirmApprovalSave -> confirmSave()
            AccountHubEvent.DismissApprovalConfirm -> updateBuilder { copy(confirm = null) }
            AccountHubEvent.DismissApprovalConfig -> closeBuilder()
            else -> return false
        }
        return true
    }

    /** A new module starts with a clean toolbar, as the web's `setActive` resets search and filter. */
    private fun switchModule(module: ApprovalModule) {
        vm.update {
            copy(
                approvals = approvals.copy(
                    module = module,
                    configs = emptyList(),
                    loadedModule = null,
                    loadError = null,
                    builder = null,
                    expanded = emptySet(),
                    departmentSearch = "",
                    departmentFilter = DepartmentFilter.All,
                ),
            )
        }
        load()
    }

    /** The production-wide chain, or one untyped level to start it. */
    private fun editDefault(origin: BuilderOrigin = BuilderOrigin.Approvers) {
        if (!vm.mayActAsAccountant()) return
        val approvals = vm.setupState.approvals
        val saved = approvals.defaultConfig
        openBuilder(
            ApprovalConfig(
                id = saved?.id.orEmpty(),
                module = approvals.module,
                scope = ApprovalScope.All,
                tiers = saved?.tiers.orEmpty(),
            ),
            origin,
        )
    }

    /**
     * Forms Configuration's "Set Approver Level": the same chain, opened from
     * another page — the web's second surface over the same approval-tiers
     * route, with this page's builder and save rules rather than a copy of
     * them.
     *
     * [configs] were just read for [module] and become this page's as well, so
     * the save's department checks read what the builder was seeded from, and
     * the Approvers page next opens on the module last edited. A department
     * without its own chain starts from the production's, as it does here and
     * as the web's `handleScopeSelect` does.
     */
    fun openFromForms(
        module: ApprovalModule,
        configs: List<ApprovalConfig>,
        scope: ApprovalScope,
        departmentId: String?,
    ) {
        if (!vm.mayActAsAccountant()) return
        val switching = vm.setupState.approvals.module != module
        vm.update {
            val base = if (switching) {
                approvals.copy(
                    expanded = emptySet(),
                    departmentSearch = "",
                    departmentFilter = DepartmentFilter.All,
                )
            } else {
                approvals
            }
            copy(
                approvals = base.copy(
                    module = module,
                    configs = configs,
                    loadedModule = module,
                    loading = false,
                    loadError = null,
                    configured = base.configured + (module to configs.any { it.isConfigured }),
                ),
            )
        }
        if (switching || vm.setupState.approvals.candidateIds == null) loadCandidates(module)
        when (scope) {
            ApprovalScope.All -> editDefault(BuilderOrigin.Forms)
            ApprovalScope.Department -> departmentId?.let { editDepartment(it, BuilderOrigin.Forms) }
        }
    }

    /**
     * A department's own chain — or, when it has none, the default chain as a
     * blueprint, which is what the web's `openBuilder` does.
     *
     * Seeding from the default only fills the editor. Nothing is written until
     * Save, so opening a department and cancelling leaves it inheriting.
     */
    private fun editDepartment(departmentId: String, origin: BuilderOrigin = BuilderOrigin.Approvers) {
        if (!vm.mayActAsAccountant()) return
        val state = vm.setupState
        val approvals = state.approvals
        val own = approvals.configFor(departmentId)
        val tiers = own?.tiers?.takeIf { it.isNotEmpty() } ?: approvals.defaultConfig?.tiers.orEmpty()
        openBuilder(
            ApprovalConfig(
                id = own?.id.orEmpty(),
                module = approvals.module,
                scope = ApprovalScope.Department,
                departmentId = departmentId,
                departmentName = state.departmentName(departmentId).ifBlank { own?.departmentName.orEmpty() },
                tiers = tiers,
            ),
            origin,
        )
    }

    /**
     * The editor opens on something to fill in: a chain with no levels gets
     * one, a level with no rules gets an untyped rule (the web's `makeTier`),
     * and levels are numbered by position so "Level N" and the addressing
     * agree even when the server's orders have gaps.
     */
    private fun openBuilder(target: ApprovalConfig, origin: BuilderOrigin) {
        val tiers = target.tiers.ifEmpty { listOf(newLevel()) }.map { tier ->
            if (tier.rules.isEmpty()) tier.copy(rules = listOf(ApprovalRule())) else tier
        }
        val seeded = target.copy(tiers = tiers.renumbered())
        vm.update { copy(approvals = approvals.copy(builder = ApprovalBuilder(seeded, seeded, origin = origin))) }
    }

    private fun closeBuilder() = vm.update { copy(approvals = approvals.copy(builder = null)) }

    private fun updateBuilder(change: ApprovalBuilder.() -> ApprovalBuilder) = vm.update {
        val builder = approvals.builder ?: return@update this
        copy(approvals = approvals.copy(builder = builder.change()))
    }

    private fun updateConfig(change: (ApprovalConfig) -> ApprovalConfig) = updateBuilder {
        copy(config = change(config))
    }

    private fun editTier(order: Int, change: (ApprovalTier) -> ApprovalTier) = updateConfig { config ->
        config.copy(tiers = config.tiers.map { if (it.order == order) change(it) else it })
    }

    private fun editRule(order: Int, index: Int, change: (ApprovalRule) -> ApprovalRule) = editTier(order) { tier ->
        tier.copy(rules = tier.rules.mapIndexed { i, rule -> if (i == index) change(rule) else rule })
    }

    private fun newLevel() = ApprovalTier(order = 0, rules = listOf(ApprovalRule()))

    private fun insertLevel(position: Int) = updateConfig { config ->
        val rows = config.tiers.toMutableList()
        rows.add(position.coerceIn(0, rows.size), newLevel())
        config.copy(tiers = rows.renumbered())
    }

    private fun removeLevel(order: Int) = updateConfig { config ->
        // Renumbered on removal so the levels stay 1..N — the cards are
        // labelled and addressed by that number.
        config.copy(tiers = config.tiers.filterNot { it.order == order }.renumbered())
    }

    private fun List<ApprovalTier>.renumbered() = mapIndexed { index, tier -> tier.copy(order = index + 1) }

    /**
     * "Add more". A level that already has a Default can only gain amount
     * rules — the web adds one typed and locked — so anything else starts
     * untyped for the user to choose.
     */
    private fun addRule(order: Int) = editTier(order) { tier ->
        tier.copy(rules = tier.rules + ApprovalRule(type = if (tier.hasDefault) ApprovalRule.AMOUNT else ""))
    }

    /** A level keeps at least one rule; the web only offers the remove when there are two. */
    private fun removeRule(order: Int, index: Int) = editTier(order) { tier ->
        if (tier.rules.size <= 1) tier else tier.copy(rules = tier.rules.filterIndexed { i, _ -> i != index })
    }

    /**
     * A rule's kind, clearing its threshold as the web does. A locked rule —
     * an amount rule on a level with a Default — is refused here as well as
     * disabled on screen, and choosing the kind a rule already has changes
     * nothing, so re-picking "Amount greater than" does not wipe the amount.
     */
    private fun setRuleType(order: Int, index: Int, type: String) = editTier(order) { tier ->
        val rule = tier.rules.getOrNull(index)
        if (rule == null || rule.type == type || tier.locks(rule)) {
            tier
        } else {
            val retyped = rule.copy(type = type, amountThreshold = null)
            tier.copy(rules = tier.rules.mapIndexed { i, r -> if (i == index) retyped else r })
        }
    }

    /** Only a rule with a kind has an Add Users, so only one can open the picker. */
    private fun openPicker(order: Int, index: Int) = updateBuilder {
        val rule = config.level(order)?.rules?.getOrNull(index)
        if (rule == null || !rule.isTyped) {
            this
        } else {
            copy(pickerTier = order, pickerRule = index, pickerSearch = "", picked = emptyList())
        }
    }

    private fun ApprovalBuilder.closedPicker() =
        copy(pickerTier = null, pickerRule = 0, pickerSearch = "", picked = emptyList())

    /** Someone already anywhere on the level is shown as Added and cannot be ticked. */
    private fun togglePick(userId: String) = updateBuilder {
        val level = config.level(pickerTier) ?: return@updateBuilder this
        when (userId) {
            in picked -> copy(picked = picked - userId)
            in level.userIds -> this
            else -> copy(picked = picked + userId)
        }
    }

    /**
     * "Add N users": the ticked people join the rule the picker was opened
     * for. Anyone already on the level is dropped on the way in — the web's
     * `handleAddUsers` guards the data, not just the picker.
     */
    private fun addPicked() = updateBuilder {
        val order = pickerTier ?: return@updateBuilder this
        val taken = config.level(order)?.userIds.orEmpty().toSet()
        val fresh = picked.distinct().filterNot { it in taken }
        val index = pickerRule
        copy(
            config = config.copy(
                tiers = config.tiers.map { tier ->
                    if (tier.order != order) {
                        tier
                    } else {
                        tier.copy(
                            rules = tier.rules.mapIndexed { i, rule ->
                                if (i == index) rule.copy(userIds = rule.userIds + fresh) else rule
                            },
                        )
                    }
                },
            ),
        ).closedPicker()
    }

    /**
     * The web's `handleSave`, in its order.
     *
     * 1. Untyped rules are dropped, and levels left with nobody compact away.
     * 2. Nothing left: a department with a saved chain asks before reverting
     *    to the global approvers, a department never saved simply closes,
     *    and the default chain is refused.
     * 3. Every surviving "Amount greater than" rule needs an amount over 0.
     * 4. A filled level below an empty one asks before the levels move up.
     *    Trailing empty levels are dropped without asking.
     */
    @Suppress("ReturnCount") // One exit per step; merging them hides which step stopped the save.
    private fun save() {
        if (!vm.mayActAsAccountant()) return
        val approvals = vm.setupState.approvals
        val config = approvals.builder?.config ?: return
        val raw = ApprovalSequence.forSave(config.tiers)
        val compacted = ApprovalSequence.compacted(raw)
        if (compacted.isEmpty()) {
            if (config.scope == ApprovalScope.All) return refuse(str(S.desktop_hub_please_add_at_least_one_level))
            val savedId = config.departmentId?.let(approvals::configFor)?.id?.takeIf { it.isNotBlank() }
                ?: return closeBuilder()
            return updateBuilder { copy(confirm = BuilderConfirm.RevertToGlobal(savedId), error = null) }
        }
        if (ApprovalSequence.hasInvalidAmount(compacted)) {
            return refuse(str(S.desktop_hub_enter_an_amount_greater_than_0_for_each_amount_greater))
        }
        val payload = config.copy(tiers = compacted)
        if (!ApprovalSequence.inSequence(raw)) {
            val levels = ApprovalSequence.emptyLevels(raw)
            return updateBuilder { copy(confirm = BuilderConfirm.EmptyLevels(levels, payload), error = null) }
        }
        write(payload)
    }

    private fun refuse(message: String) = updateBuilder { copy(error = message) }

    private fun confirmSave() {
        if (!vm.mayActAsAccountant()) return
        when (val confirm = vm.setupState.approvals.builder?.confirm) {
            is BuilderConfirm.EmptyLevels -> write(confirm.payload)
            is BuilderConfirm.RevertToGlobal -> revertDepartmentToGlobal(confirm.configId)
            null -> Unit
        }
    }

    private fun write(config: ApprovalConfig) = commit(
        call = { vm.repo.saveApprovalConfig(config) },
        done = str(S.desktop_hub_approval_levels_saved_successfully),
        fallback = str(S.desktop_hub_failed_to_save_approval_levels),
    )

    /**
     * Drops a department's own chain so it inherits the production's.
     *
     * The absence of a department row is what "inherits" means to the server —
     * it resolves the department first and falls back — so removing the row is
     * how a department is put back on the default, not a deletion of anything
     * a person configured deliberately.
     */
    private fun revertDepartmentToGlobal(configId: String) = commit(
        call = { vm.repo.deleteApprovalConfig(configId) },
        done = str(S.desktop_hub_this_department_will_now_use_the_global_approvers),
        fallback = str(S.desktop_hub_failed_to_update_approvers),
    )

    /**
     * Sends a save or a revert. Success closes the builder and re-reads the
     * chains; a refusal stays in the builder with the server's reason, the
     * confirmation closed, as on the web.
     */
    private fun <T> commit(call: suspend () -> ZillitResult<T>, done: String, fallback: String) {
        vm.update {
            val cleared = approvals.builder?.copy(confirm = null, error = null)
            copy(approvals = approvals.copy(saving = true, builder = cleared))
        }
        vm.runResult(call, {
            vm.update { copy(approvals = approvals.copy(builder = null, saving = false), notice = done) }
            load()
        }, { error ->
            vm.update {
                copy(
                    approvals = approvals.copy(
                        saving = false,
                        builder = approvals.builder?.copy(confirm = null, error = error.saveMessage(fallback)),
                    ),
                )
            }
        })
    }

    /** The server's reason when it gave one, the web's fallback wording when it did not. */
    private fun ZillitError.saveMessage(fallback: String): String =
        if (this is ZillitError.Http && serverMessage.isNullOrBlank()) fallback else localised()
}
