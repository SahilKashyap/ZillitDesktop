package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.AssignmentRules
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollAccounts
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import kotlinx.coroutines.async

/**
 * The three drill-down modals — Purchase Orders, Invoices, Payroll.
 *
 * Each is the web's `SetupModalShell` over one settings document plus, for the
 * first two, the module's auto-assignment rules. Opening one reads its document
 * afresh, as the web's details do on mount, with the shell's loading and error
 * states in between — the page's own read may be minutes old, or may have
 * failed. A save writes the settings when they changed and then diffs the
 * rules, exactly as `POSetupDetail` and `InvoicesSetupDetail` do; closing the
 * modal throws unsaved work away, which is what a modal's close means there too.
 */
@Suppress("TooManyFunctions") // One handler per modal action.
internal class SetupModalActions(private val vm: AccountHubViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.OpenSetupModal -> open(event.modal)
            AccountHubEvent.RetrySetupModal -> vm.setupState.setup.modal?.let { read(it.modal) }
            AccountHubEvent.CloseSetupModal -> close()
            is AccountHubEvent.SwitchModalSection -> vm.update {
                copy(setup = setup.copy(modal = setup.modal?.copy(section = event.section)))
            }
            AccountHubEvent.SaveSetupModal -> save()
            is AccountHubEvent.EditPoRules -> vm.update {
                copy(setup = setup.copy(poRules = setup.poRules.edit(event.rules)))
            }
            is AccountHubEvent.EditInvoiceRules ->
                vm.update { copy(setup = setup.copy(invoiceRules = setup.invoiceRules.edit(event.rules))) }
            is AccountHubEvent.ComposeInvoiceMember -> composeMember(event.index)
            is AccountHubEvent.EditInvoiceMember -> vm.update {
                copy(setup = setup.copy(invoiceMemberDraft = setup.invoiceMemberDraft?.copy(member = event.member)))
            }
            AccountHubEvent.CommitInvoiceMember -> commitMember()
            AccountHubEvent.DismissInvoiceMember -> vm.update { copy(setup = setup.copy(invoiceMemberDraft = null)) }
            is AccountHubEvent.OpenUserPicker -> openPicker(event.purpose, event.index)
            is AccountHubEvent.SearchUserPicker -> vm.update {
                copy(setup = setup.copy(userPicker = setup.userPicker?.copy(search = event.term)))
            }
            is AccountHubEvent.ToggleUserPick -> togglePick(event.userId)
            AccountHubEvent.ApplyUserPicker -> applyPicker()
            AccountHubEvent.DismissUserPicker -> vm.update { copy(setup = setup.copy(userPicker = null)) }
            is AccountHubEvent.ComposePayrollGroup -> vm.update {
                copy(setup = setup.copy(payrollGroupDraft = event.group ?: PayrollGroup()))
            }
            is AccountHubEvent.EditPayrollGroup -> vm.update {
                copy(setup = setup.copy(payrollGroupDraft = event.group))
            }
            AccountHubEvent.SavePayrollGroup -> savePayrollGroup()
            AccountHubEvent.DismissPayrollGroup -> vm.update { copy(setup = setup.copy(payrollGroupDraft = null)) }
            AccountHubEvent.OpenPayrollAccounts -> openPayrollAccounts()
            is AccountHubEvent.EditPayrollAccounts -> vm.update {
                copy(setup = setup.copy(payrollAccounts = setup.payrollAccounts?.copy(rows = event.rows)))
            }
            AccountHubEvent.SavePayrollAccounts -> savePayrollAccounts()
            AccountHubEvent.DismissPayrollAccounts -> vm.update { copy(setup = setup.copy(payrollAccounts = null)) }
            else -> return false
        }
        return true
    }

    // -- open / close / save --------------------------------------------------

    /**
     * Opens the modal and reads its document. Only over Production Setup: a
     * deep link sends `Open(ProductionSetup)` first, and a modal opened over
     * another page would sit hidden in state until that page was left.
     */
    private fun open(modal: SetupModal) {
        if (vm.setupState.area != HubArea.ProductionSetup) return
        vm.update { copy(setup = setup.copy(modal = SetupModalState(modal = modal, section = firstSection(modal)))) }
        read(modal)
        // The pickers behind the assign-to fields and the nominal multi-select
        // read the chart, so it is made sure of here.
        vm.chart.ensureLoaded()
    }

    private fun firstSection(modal: SetupModal): String = when (modal) {
        SetupModal.PurchaseOrders -> "format"
        SetupModal.Invoices -> "team"
        SetupModal.Payroll -> "approvers"
    }

    /**
     * The document, and the module's rules beside it, read together — the
     * web's `Promise.all` on mount. Either failing is the modal's load error,
     * and nothing is editable until a read lands (a modal on defaults is one
     * Save away from overwriting the stored settings).
     */
    private fun read(modal: SetupModal) {
        vm.update { copy(setup = setup.copy(modal = setup.modal?.copy(loading = true, loadError = null))) }
        when (modal) {
            SetupModal.PurchaseOrders -> readWithRules(
                modal = modal,
                section = SetupSection.PoSetup,
                document = vm.repo::purchaseOrderSetup,
                module = PURCHASE_ORDERS,
                applyDocument = { copy(poSetup = SectionEdit(it)) },
                applyRules = { copy(poRules = SectionEdit(it)) },
            )
            SetupModal.Invoices -> readWithRules(
                modal = modal,
                section = SetupSection.InvoicesSetup,
                document = vm.repo::invoicesSetup,
                module = INVOICES,
                applyDocument = { copy(invoicesSetup = SectionEdit(it)) },
                applyRules = { copy(invoiceRules = SectionEdit(it)) },
            )
            SetupModal.Payroll -> {
                vm.runResult(vm.repo::payrollSettings, { value ->
                    settle(modal, SetupSection.PayrollSettings, null) { copy(payrollSettings = SectionEdit(value)) }
                }, { error -> settle(modal, SetupSection.PayrollSettings, error) { this } })
                loadPayrollGroups()
            }
        }
    }

    private fun <T> readWithRules(
        modal: SetupModal,
        section: SetupSection,
        document: suspend () -> ZillitResult<T>,
        module: String,
        applyDocument: SetupState.(T) -> SetupState,
        applyRules: SetupState.(List<AssignmentRule>) -> SetupState,
    ) {
        vm.update { copy(setup = setup.copy(rulesLoading = true)) }
        loadRuleVendors()
        vm.launchWork {
            val settings = async { document() }
            val rules = async { vm.repo.assignmentRules(module) }
            val doc = settings.await()
            val rows = rules.await()
            val failure = (doc as? ZillitResult.Failure)?.error ?: (rows as? ZillitResult.Failure)?.error
            vm.update { copy(setup = setup.copy(rulesLoading = false)) }
            if (failure != null) {
                settle(modal, section, failure) { this }
            } else {
                val value = (doc as ZillitResult.Success).data
                val ruleRows = (rows as ZillitResult.Success).data
                settle(modal, section, null) { applyDocument(value).applyRules(ruleRows) }
            }
        }
    }

    /**
     * Lands a modal read: on success the document is the new baseline and its
     * slice counts as loaded; on failure the modal shows the error. A modal
     * closed or switched meanwhile keeps nothing but the fresh document.
     */
    private fun settle(
        modal: SetupModal,
        section: SetupSection,
        error: ZillitError?,
        applying: SetupState.() -> SetupState,
    ) = vm.update {
        val open = setup.modal?.takeIf { it.modal == modal }
        if (error != null) {
            copy(setup = setup.copy(modal = open?.copy(loading = false, loadError = error.localised()) ?: setup.modal))
        } else {
            val landed = if (open != null) setup.applying() else setup
            copy(
                setup = landed.copy(
                    modal = open?.copy(loading = false, loadError = null) ?: landed.modal,
                    slices = if (open != null) landed.slices.succeeded(section) else landed.slices,
                ),
            )
        }
    }

    /**
     * The vendor list the rules' multi-select offers, read by the modal itself
     * as the web's `AssignmentRulesSection` does — it was only there when the
     * Vendors page had been opened first.
     */
    private fun loadRuleVendors() {
        if (vm.setupState.setup.ruleVendors.isNotEmpty()) return
        vm.runResult({ vm.repo.vendors("") }, { rows ->
            vm.update { copy(setup = setup.copy(ruleVendors = rows)) }
        })
    }

    /** Closing discards: the web's detail holds local state that goes with the modal. */
    private fun close() = vm.update {
        copy(
            setup = setup.copy(
                modal = null,
                poSetup = setup.poSetup.reverted(),
                invoicesSetup = setup.invoicesSetup.reverted(),
                payrollSettings = setup.payrollSettings.reverted(),
                poRules = setup.poRules.reverted(),
                invoiceRules = setup.invoiceRules.reverted(),
                invoiceMemberDraft = null,
                userPicker = null,
                payrollAccounts = null,
                payrollGroupDraft = null,
            ),
        )
    }

    private fun save() {
        val modal = vm.setupState.setup.modal ?: return
        // Nothing read, nothing to save over: the shell hides the body while
        // loading or failed, and the shortcut must not reach around it.
        if (modal.loading || modal.loadError != null) return
        if (!vm.mayEdit()) return
        when (modal.modal) {
            SetupModal.PurchaseOrders -> {
                // An inverted price range never leaves the modal — the same
                // guard the PO module's Settings page applies before its PATCH.
                val assetError = vm.setupState.setup.poSetup.edited.assetFilters.error
                if (assetError != null) {
                    vm.fail(assetError)
                    return
                }
                if (vm.setupState.setup.poSetup.dirty) vm.onEvent(AccountHubEvent.SaveSection(SetupSection.PoSetup))
                saveRules(PURCHASE_ORDERS, { poRules }, { copy(setup = setup.copy(poRules = it)) })
            }
            SetupModal.Invoices -> {
                if (vm.setupState.setup.invoicesSetup.dirty) {
                    vm.onEvent(AccountHubEvent.SaveSection(SetupSection.InvoicesSetup))
                }
                saveRules(INVOICES, { invoiceRules }, { copy(setup = setup.copy(invoiceRules = it)) })
            }
            SetupModal.Payroll ->
                if (vm.setupState.setup.payrollSettings.dirty) {
                    vm.onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollSettings))
                }
        }
    }

    /**
     * The rules diff — the web's `persistRuleDiff`.
     *
     * Removed rows are deleted best-effort, then every changed or new row is
     * written in order; the section re-snapshots to what the server holds.
     */
    private fun saveRules(
        module: String,
        pick: SetupState.() -> SectionEdit<List<AssignmentRule>>,
        put: AccountHubUiState.(SectionEdit<List<AssignmentRule>>) -> AccountHubUiState,
    ) {
        val section = vm.setupState.setup.pick()
        if (!section.dirty) return
        val diff = AssignmentRules.diff(section.saved, section.edited)
        vm.update { put(setup.pick().copy(saving = true)) }
        vm.launchWork {
            diff.removed.forEach { vm.repo.deleteAssignmentRule(it.id) }
            val next = mutableListOf<AssignmentRule>()
            var failure: ZillitError? = null
            for (rule in section.edited) {
                val result = when {
                    !rule.persisted -> vm.repo.createAssignmentRule(rule.copy(module = module))
                    diff.updated.any { it.id == rule.id } -> vm.repo.updateAssignmentRule(rule)
                    else -> ZillitResult.Success(rule)
                }
                when (result) {
                    is ZillitResult.Success -> next += result.data
                    is ZillitResult.Failure -> {
                        failure = result.error
                        next += rule
                    }
                }
            }
            val error = failure
            if (error == null) {
                vm.update {
                    put(SectionEdit(
                        saved = next.toList(),
                        edited = next.toList(),
                    )).copy(notice = str(S.desktop_assignment_rules_saved))
                }
            } else {
                vm.update { put(setup.pick().copy(saving = false)) }
                vm.report(error)
            }
        }
    }

    // -- invoices team members -----------------------------------------------

    private fun composeMember(index: Int?) = vm.update {
        val member = index?.let { setup.invoicesSetup.edited.teamMembers.getOrNull(it) }
        copy(setup = setup.copy(invoiceMemberDraft = InvoiceMemberDraft(index, member ?: InvoiceTeamMemberDefault)))
    }

    /**
     * Commits the dialog. One row per person — the web offers only people not
     * already on the team — and a senior goes as the full-rights row it is.
     */
    private fun commitMember() {
        val draft = vm.setupState.setup.invoiceMemberDraft ?: return
        if (draft.member.userId.isBlank()) {
            vm.sendSideEffect(AccountHubEffect.Failed(str(S.desktop_hub_pick_a_team_member)))
            return
        }
        val members = vm.setupState.setup.invoicesSetup.edited.teamMembers
        val onTeamAlready = members.withIndex().any { (i, m) -> i != draft.index && m.userId == draft.member.userId }
        if (onTeamAlready) {
            vm.fail(str(S.desktop_hub_already_on_the_team))
            return
        }
        val member = draft.member.forWire()
        vm.update {
            val current = setup.invoicesSetup.edited
            val next = if (draft.index != null && draft.index in current.teamMembers.indices) {
                current.teamMembers.mapIndexed { i, m -> if (i == draft.index) member else m }
            } else {
                current.teamMembers + member
            }
            copy(
                setup = setup.copy(
                    invoicesSetup = setup.invoicesSetup.edit(current.copy(teamMembers = next)),
                    invoiceMemberDraft = null,
                ),
            )
        }
    }

    // -- the shared user picker ----------------------------------------------

    private fun openPicker(purpose: UserPickerPurpose, index: Int) {
        val setup = vm.setupState.setup
        val selected = when (purpose) {
            UserPickerPurpose.PayrollApprovers -> setup.payrollSettings.edited.approverIds
            UserPickerPurpose.InvoiceTeamMember -> listOfNotNull(
                setup.invoiceMemberDraft?.member?.userId?.takeIf { it.isNotBlank() },
            )
            UserPickerPurpose.RunAuthorisation ->
                setup.invoicesSetup.edited.runAuthorisation.getOrNull(index)?.userIds.orEmpty()
            UserPickerPurpose.PayrollGroupAssignee -> listOfNotNull(
                setup.payrollGroupDraft?.assigneeId?.takeIf { it.isNotBlank() },
            )
            UserPickerPurpose.PayrollGroupCrew -> setup.payrollGroupDraft?.userIds.orEmpty()
            UserPickerPurpose.ClosingRecipients ->
                vm.setupState.periodClose.publish.packages.firstOrNull { it.id == index }?.userIds.orEmpty()
        }
        val multiple =
            purpose != UserPickerPurpose.InvoiceTeamMember && purpose != UserPickerPurpose.PayrollGroupAssignee
        vm.update {
            copy(setup = this.setup.copy(userPicker = UserPickerState(
                purpose,
                selected,
                index = index,
                multiple = multiple,
            )))
        }
    }

    private fun togglePick(userId: String) = vm.update {
        val picker = setup.userPicker ?: return@update this
        // Somebody already signing another level is not offered for this one
        // (the web's `alreadyAssigned`): one person on two levels would sign
        // the same run twice.
        if (userId in setup.pickerExcluded(picker)) return@update this
        val next = when {
            userId in picker.selected -> picker.selected - userId
            picker.multiple -> picker.selected + userId
            else -> listOf(userId)
        }
        copy(setup = setup.copy(userPicker = picker.copy(selected = next)))
    }

    @Suppress("CyclomaticComplexMethod") // One branch per purpose.
    private fun applyPicker() {
        val picker = vm.setupState.setup.userPicker ?: return
        val ids = (picker.selected - vm.setupState.setup.pickerExcluded(picker)).distinct()
        vm.update {
            val next = when (picker.purpose) {
                UserPickerPurpose.PayrollApprovers ->
                    setup.copy(
                        payrollSettings = setup.payrollSettings.edit(
                            setup.payrollSettings.edited.copy(approverIds = ids),
                        ),
                    )
                UserPickerPurpose.InvoiceTeamMember -> setup.copy(
                    invoiceMemberDraft = setup.invoiceMemberDraft?.let {
                        it.copy(member = it.member.copy(userId = ids.firstOrNull().orEmpty()))
                    },
                )
                UserPickerPurpose.RunAuthorisation -> {
                    val current = setup.invoicesSetup.edited
                    val tiers = current.runAuthorisation.mapIndexed { i, tier ->
                        if (i == picker.index) tier.copy(userIds = ids) else tier
                    }
                    setup.copy(invoicesSetup = setup.invoicesSetup.edit(current.copy(runAuthorisation = tiers)))
                }
                UserPickerPurpose.PayrollGroupAssignee ->
                    setup.copy(
                        payrollGroupDraft = setup.payrollGroupDraft?.copy(assigneeId = ids.firstOrNull().orEmpty()),
                    )
                UserPickerPurpose.PayrollGroupCrew ->
                    setup.copy(payrollGroupDraft = setup.payrollGroupDraft?.copy(userIds = ids))
                UserPickerPurpose.ClosingRecipients -> setup
            }
            val publish = if (picker.purpose == UserPickerPurpose.ClosingRecipients) {
                periodClose.publish.copy(
                    packages = periodClose.publish.packages.map { pkg ->
                        if (pkg.id == picker.index) pkg.copy(userIds = ids) else pkg
                    },
                )
            } else {
                periodClose.publish
            }
            copy(setup = next.copy(userPicker = null), periodClose = periodClose.copy(publish = publish))
        }
    }

    // -- payroll groups ------------------------------------------------------

    private fun loadPayrollGroups() {
        vm.update { copy(setup = setup.copy(payrollGroupsLoading = true)) }
        vm.runResult(vm.repo::payrollGroups, { rows ->
            vm.update { copy(setup = setup.copy(payrollGroups = rows, payrollGroupsLoading = false)) }
        }, { error ->
            vm.update { copy(setup = setup.copy(payrollGroupsLoading = false)) }
            vm.report(error)
        })
    }

    private fun savePayrollGroup() {
        val draft = vm.setupState.setup.payrollGroupDraft ?: return
        if (!draft.canSave || !vm.mayEdit()) return
        vm.update { copy(setup = setup.copy(payrollGroupSaving = true)) }
        vm.runResult(
            { if (draft.id.isBlank()) vm.repo.createPayrollGroup(draft) else vm.repo.updatePayrollGroup(draft) },
            { saved ->
                vm.update {
                    val rows = setup.payrollGroups
                    val next =
                        if (rows.any { it.id == saved.id }) rows.map { if (it.id == saved.id) saved else it }
                        else rows + saved
                    copy(
                        setup = setup.copy(payrollGroups = next, payrollGroupDraft = null, payrollGroupSaving = false),
                        notice = str(S.desktop_payroll_group_saved),
                    )
                }
                loadPayrollGroups()
            },
            { error ->
                vm.update { copy(setup = setup.copy(payrollGroupSaving = false)) }
                vm.report(error)
            },
        )
    }

    // -- payroll accounts --------------------------------------------------------

    /**
     * The grid, seeded from the saved codes joined to the chart — the web's
     * `PayrollAccountsPage` seeding. A code the chart cannot resolve is not
     * seeded: with no chart id it would be sent as a create for an account
     * that already exists. It is listed above the grid instead.
     */
    private fun openPayrollAccounts() {
        val state = vm.setupState
        val byCode = state.chart.accounts.associateBy { it.code.lowercase() }
        val codes = state.setup.payrollSettings.edited.payrollAccounts
        val seeded = codes.mapNotNull { code ->
            val account = byCode[code.lowercase()] ?: return@mapNotNull null
            PayrollAccountRow(id = account.id, code = code, name = account.name, lineType = account.lineType)
        }
        vm.update {
            copy(
                setup = setup.copy(
                    payrollAccounts = PayrollAccountsDraft(
                        rows = seeded,
                        seeds = seeded.associateBy { it.id.orEmpty() },
                        unmatched = codes.filter { byCode[it.lowercase()] == null },
                    ),
                ),
            )
        }
        vm.chart.ensureLoaded()
    }

    /**
     * Sends the new and changed rows only, then re-reads the codes. The
     * settings the modal holds are left alone: taking the batch's echo as the
     * whole document threw away approvers and a pay period nobody had saved.
     */
    private fun savePayrollAccounts() {
        val draft = vm.setupState.setup.payrollAccounts ?: return
        if (!vm.mayEdit()) return
        val rows = PayrollAccounts.outgoing(draft.rows, draft.seeds)
        if (rows.isEmpty()) {
            vm.update { copy(setup = setup.copy(payrollAccounts = null)) }
            return
        }
        vm.update { copy(setup = setup.copy(payrollAccounts = draft.copy(saving = true))) }
        vm.runResult({ vm.repo.updatePayrollAccounts(rows) }, {
            vm.update {
                copy(setup = setup.copy(payrollAccounts = null), notice = str(S.desktop_payroll_accounts_saved))
            }
            reloadPayrollAccounts()
            vm.chart.load()
        }, { error ->
            vm.update { copy(setup = setup.copy(payrollAccounts = draft.copy(saving = false))) }
            vm.report(error)
        })
    }

    /**
     * The saved codes re-read and set on both sides of the section — the web's
     * `PayrollAccountsBody.load`. They never ride the settings PATCH, so they
     * change nothing about what the modal has unsaved.
     */
    fun reloadPayrollAccounts() {
        vm.runResult(vm.repo::payrollSettings, { fresh ->
            vm.update {
                val section = setup.payrollSettings
                copy(
                    setup = setup.copy(
                        payrollSettings = section.copy(
                            saved = section.saved.copy(payrollAccounts = fresh.payrollAccounts),
                            edited = section.edited.copy(payrollAccounts = fresh.payrollAccounts),
                        ),
                    ),
                )
            }
        }, vm::report)
    }

    private companion object {
        const val PURCHASE_ORDERS = "purchase_orders"
        const val INVOICES = "invoices"

        /** A new member starts submit-only, as the web's dialog does. */
        val InvoiceTeamMemberDefault = InvoiceTeamMember(postingLimit = InvoiceTeamMember.SUBMIT_ONLY)
    }
}

/**
 * Who the open picker must not offer: for a run-authorisation level, everybody
 * already on another level (the web's `alreadyAssigned`). Nobody otherwise.
 */
internal fun SetupState.pickerExcluded(picker: UserPickerState): Set<String> = when (picker.purpose) {
    UserPickerPurpose.RunAuthorisation -> invoicesSetup.edited.runAuthorisation
        .filterIndexed { i, _ -> i != picker.index }
        .flatMap { it.userIds }
        .toSet()
    else -> emptySet()
}

/** The invoices setup with its run levels renumbered — used by the section and the modal alike. */
internal fun InvoicesSetup.withRenumberedLevels(): InvoicesSetup = renumbered()
