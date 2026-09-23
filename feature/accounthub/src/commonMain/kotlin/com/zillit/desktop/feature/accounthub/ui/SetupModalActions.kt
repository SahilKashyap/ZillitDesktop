package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.AssignmentRules
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup

/**
 * The three drill-down modals — Purchase Orders, Invoices, Payroll.
 *
 * Each is the web's `SetupModalShell` over one settings document plus, for the
 * first two, the module's auto-assignment rules. A save writes the settings
 * when they changed and then diffs the rules, exactly as `POSetupDetail` and
 * `InvoicesSetupDetail` do; closing the modal throws unsaved work away, which
 * is what a modal's close means there too.
 */
@Suppress("TooManyFunctions") // One handler per modal action.
internal class SetupModalActions(private val vm: AccountHubViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.OpenSetupModal -> open(event.modal)
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

    private fun open(modal: SetupModal) {
        vm.update {
            copy(
                setup = setup.copy(
                    modal = SetupModalState(
                        modal = modal,
                        section = firstSection(modal),
                        loading = modal != SetupModal.Payroll,
                    ),
                ),
            )
        }
        when (modal) {
            SetupModal.PurchaseOrders -> loadRules(PURCHASE_ORDERS) { rows ->
                vm.update { copy(setup = setup.copy(poRules = setup.poRules.committed(rows))) }
            }
            SetupModal.Invoices -> loadRules(INVOICES) { rows ->
                vm.update { copy(setup = setup.copy(invoiceRules = setup.invoiceRules.committed(rows))) }
            }
            SetupModal.Payroll -> loadPayrollGroups()
        }
        // The pickers behind the assign-to fields and the nominal multi-select
        // read the chart, so it is made sure of here.
        vm.chart.ensureLoaded()
    }

    private fun firstSection(modal: SetupModal): String = when (modal) {
        SetupModal.PurchaseOrders -> "format"
        SetupModal.Invoices -> "team"
        SetupModal.Payroll -> "approvers"
    }

    private fun loadRules(module: String, done: (List<AssignmentRule>) -> Unit) {
        vm.update { copy(setup = setup.copy(rulesLoading = true)) }
        vm.runResult({ vm.repo.assignmentRules(module) }, { rows ->
            done(rows)
            vm.update { copy(setup = setup.copy(rulesLoading = false, modal = setup.modal?.copy(loading = false))) }
        }, { error ->
            vm.update {
                copy(
                    setup = setup.copy(
                        rulesLoading = false,
                        modal = setup.modal?.copy(loading = false, loadError = "Failed to load settings"),
                    ),
                )
            }
            vm.report(error)
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
            var failure: com.zillit.desktop.core.common.ZillitError? = null
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
                    )).copy(notice = "Assignment rules saved.")
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

    private fun commitMember() {
        val draft = vm.setupState.setup.invoiceMemberDraft ?: return
        if (draft.member.userId.isBlank()) {
            vm.sendSideEffect(AccountHubEffect.Failed("Pick a team member."))
            return
        }
        vm.update {
            val current = setup.invoicesSetup.edited
            val members = current.teamMembers
            val next = if (draft.index != null && draft.index in members.indices) {
                members.mapIndexed { i, m -> if (i == draft.index) draft.member else m }
            } else {
                members + draft.member
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
        val ids = picker.selected
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
                        notice = "Payroll group saved.",
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

    /** The grid, seeded from the saved codes joined to the chart. */
    private fun openPayrollAccounts() {
        val state = vm.setupState
        val byCode = state.chart.accounts.associateBy { it.code.lowercase() }
        val rows = state.setup.payrollSettings.edited.payrollAccounts.map { code ->
            val account = byCode[code.lowercase()]
            PayrollAccountRow(
                id = account?.id,
                code = code,
                name = account?.name.orEmpty(),
                lineType = account?.lineType ?: CoaLineType.Category,
            )
        }
        vm.update { copy(setup = setup.copy(payrollAccounts = PayrollAccountsDraft(rows = rows))) }
        vm.chart.ensureLoaded()
    }

    private fun savePayrollAccounts() {
        val draft = vm.setupState.setup.payrollAccounts ?: return
        if (!vm.mayEdit()) return
        val rows = draft.rows.filter { it.code.isNotBlank() }
        if (rows.isEmpty()) {
            vm.update { copy(setup = setup.copy(payrollAccounts = null)) }
            return
        }
        vm.update { copy(setup = setup.copy(payrollAccounts = draft.copy(saving = true))) }
        vm.runResult({ vm.repo.updatePayrollAccounts(rows) }, { settings: PayrollSettings ->
            vm.update {
                copy(
                    setup = setup.copy(
                        payrollSettings = setup.payrollSettings.committed(settings),
                        payrollAccounts = null,
                    ),
                    notice = "Payroll accounts saved.",
                )
            }
            vm.chart.load()
        }, { error ->
            vm.update { copy(setup = setup.copy(payrollAccounts = draft.copy(saving = false))) }
            vm.report(error)
        })
    }

    private companion object {
        const val PURCHASE_ORDERS = "purchase_orders"
        const val INVOICES = "invoices"
        val InvoiceTeamMemberDefault =
            com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember(postingLimit = "0")
    }
}

/** The invoices setup with its run levels renumbered — used by the section and the modal alike. */
internal fun InvoicesSetup.withRenumberedLevels(): InvoicesSetup = renumbered()
