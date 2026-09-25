package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardHubSource
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.FundRouting
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.TopUpBoard

/**
 * Top-Up To Do, Smart Alerts, the Settings modals and the fund-request
 * routing — the web's page-local handlers, each checked against the viewer
 * here rather than trusted to the screen that raised it.
 *
 * The web fires Mark Topped Up, Skip, Investigate and Dismiss on the press,
 * with no confirmation (`TopUpToDoPage.jsx:442-456`, `SmartAlertsPage.jsx:157-175`),
 * and so do these.
 */
@Suppress("TooManyFunctions") // One handler per page action.
internal class CardInsightActions(
    private val vm: CardExpensesViewModel,
    private val hub: CardHubSource,
    private val banks: suspend () -> List<CardBank>,
) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    fun handle(event: InsightsEvent) {
        when (event) {
            is InsightsEvent.FilterTopUps -> edit { copy(topUpFilter = event.filter) }
            is InsightsEvent.MarkToppedUp -> markToppedUp(event.topUpId)
            is InsightsEvent.SkipTopUp -> skip(event.topUpId)
            is InsightsEvent.OpenPartial -> openPartial(event.topUpId)
            is InsightsEvent.EditPartial ->
                edit { copy(partial = partial?.copy(amount = event.amount, note = event.note)) }

            InsightsEvent.SubmitPartial -> submitPartial()
            InsightsEvent.ClosePartial -> edit { copy(partial = null) }
            InsightsEvent.DismissLimitAlert -> edit { copy(limitAlert = null) }
            is InsightsEvent.OpenTopUpDetail -> edit { copy(topUpDetailId = event.topUpId) }
            is InsightsEvent.OpenHistory -> openHistory(event.topUpId, event.holderName)
            InsightsEvent.CloseHistory -> edit { copy(history = null) }

            is InsightsEvent.FilterAlerts -> edit { copy(alertFilter = event.filter) }
            is InsightsEvent.Investigate -> alertAction(event.alertId, AlertAction.Investigate)
            is InsightsEvent.Dismiss -> alertAction(event.alertId, AlertAction.Dismiss)
            is InsightsEvent.OpenResolve -> edit { copy(resolve = event.alertId?.let { ResolveDraft(it) }) }
            is InsightsEvent.EditResolveNote -> edit { copy(resolve = resolve?.copy(note = event.note)) }
            InsightsEvent.ConfirmResolve -> resolve()

            is InsightsEvent.OpenMemberEditor -> openMember(event.index)
            is InsightsEvent.EditMember -> edit { copy(memberEditor = event.editor) }
            InsightsEvent.CloseMemberEditor -> edit { copy(memberEditor = null) }
            InsightsEvent.SaveMember -> saveMember()
            is InsightsEvent.RemoveMember -> removeMember(event.index)

            is InsightsEvent.OpenCoordinatorPicker -> openPicker(event.index, event.users)
            is InsightsEvent.EditCoordinatorPicker -> edit { copy(coordinatorPicker = event.picker) }
            InsightsEvent.ApplyCoordinatorPicker -> applyPicker()
            InsightsEvent.CloseCoordinatorPicker -> edit { copy(coordinatorPicker = null) }
            is InsightsEvent.RemoveCoordinator -> removeCoordinator(event.index)

            InsightsEvent.AddRule -> addRule()
            is InsightsEvent.EditRule ->
                editRules { mapIndexed { at, row -> if (at == event.index) event.rule else row } }
            is InsightsEvent.RemoveRule -> removeRule(event.index)
            InsightsEvent.SaveRules -> saveRules()

            is InsightsEvent.PickFundBank -> vm.update {
                copy(funds = funds?.copy(draft = FundRouting.pickBank(funds.draft, event.bankId, providers)))
            }

            is InsightsEvent.PickFundAccount -> vm.update {
                copy(funds = funds?.copy(draft = FundRouting.pickAccount(funds.draft, event.code, providers)))
            }
        }
    }

    // -- page reads (called by the page loader) ---------------------------------

    /** `/analytics/overview` with no window — the web sends none (`AnalyticsPage.jsx:99`). */
    suspend fun analyticsPage(): ZillitResult<Reducer> {
        val read = vm.repo.analytics(null, null)
        if (read is ZillitResult.Failure) return read
        val analytics = (read as ZillitResult.Success).data
        val departments = vm.current.insights.departments.ifEmpty { hub.departments() }
        return ZillitResult.Success {
            copy(analytics = analytics, insights = insights.copy(departments = departments))
        }
    }

    /** The settings document, plus the banks, companies and departments its pickers offer. */
    suspend fun settingsPage(): ZillitResult<Reducer> {
        val read = vm.repo.settings()
        if (read is ZillitResult.Failure) return read
        val settings = (read as ZillitResult.Success).data
        val accounts = banks()
        val companies = hub.companies()
        val departments = hub.departments()
        return ZillitResult.Success {
            copy(
                settings = settings,
                settingsDraft = settings,
                banks = accounts,
                insights = insights.copy(
                    companies = companies,
                    departments = departments,
                    coordinatorErrors = emptyMap(),
                ),
            )
        }
    }

    // -- top-ups ------------------------------------------------------------------

    /** Funds the row in full — or explains why it cannot (`handleCompleteGroup`). */
    private fun markToppedUp(topUpId: String) {
        if (!vm.current.viewer.isAccountant) return refuse()
        val topUp = vm.current.topUps.firstOrNull { it.id == topUpId } ?: return
        if (topUp.overfills(topUp.amount)) {
            edit { copy(limitAlert = TopUpBoard.limitAlert(topUp, topUp.amount)) }
            return
        }
        edit { copy(completingId = topUpId) }
        vm.run {
            val done = vm.repo.completeTopUp(topUpId)
            edit { copy(completingId = null) }
            settle(done, str(S.desktop_ce_insights_topup_completed))
        }
    }

    private fun skip(topUpId: String) {
        if (!vm.current.viewer.isAccountant) return refuse()
        edit { copy(skippingId = topUpId) }
        vm.run {
            val done = vm.repo.skipTopUp(topUpId)
            edit { copy(skippingId = null) }
            settle(done, str(S.ah_topup_skipped_toast))
        }
    }

    /** Pre-filled with the row's own amount (`TopUpToDoPage.jsx:449`). */
    private fun openPartial(topUpId: String) {
        if (!vm.current.viewer.isAccountant) return refuse()
        val topUp = vm.current.topUps.firstOrNull { it.id == topUpId } ?: return
        edit { copy(partial = PartialTopUpDraft(topUpId, plainAmount(topUp.amount))) }
    }

    private fun submitPartial() {
        val draft = vm.current.insights.partial ?: return
        if (!vm.current.viewer.isAccountant) return refuse()
        val note = draft.note.trim()
        val amount = draft.amount.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (!partialReady(draft, amount)) return
        edit { copy(partial = partial?.copy(submitting = true)) }
        vm.run {
            when (val done = vm.repo.partialTopUp(draft.topUpId, amount, note)) {
                is ZillitResult.Success -> {
                    edit { copy(partial = null) }
                    vm.update { copy(notice = str(S.ah_partial_topup_toast)) }
                    vm.reload()
                }

                is ZillitResult.Failure -> {
                    edit { copy(partial = partial?.copy(submitting = false)) }
                    vm.fail(done.error.localised())
                }
            }
        }
    }

    /**
     * The note is required and the amount optional; a typed amount must be
     * positive, and one past the card's limit opens the explainer instead.
     */
    private fun partialReady(draft: PartialTopUpDraft, amount: Double?): Boolean {
        val typed = draft.amount.trim()
        val topUp = vm.current.topUps.firstOrNull { it.id == draft.topUpId }
        return when {
            draft.note.isBlank() || draft.submitting -> false
            typed.isNotEmpty() && (amount == null || amount <= 0) -> {
                vm.fail(str(S.desktop_card_amount_greater_than_zero))
                false
            }

            amount != null && topUp != null && topUp.overfills(amount) -> {
                edit { copy(limitAlert = TopUpBoard.limitAlert(topUp, amount)) }
                false
            }

            else -> true
        }
    }

    /** "Top-Up History", newest first (`TopUpToDoPage.jsx:746-756`). */
    private fun openHistory(topUpId: String, holderName: String) {
        edit { copy(history = TopUpHistoryView(holderName), topUpDetailId = null) }
        vm.run {
            val trail = vm.repo.topUpHistory(topUpId).getOrNull().orEmpty().sortedByDescending { it.at ?: 0L }
            edit { copy(history = history?.copy(loading = false, entries = trail)) }
        }
    }

    // -- smart alerts -----------------------------------------------------------

    /**
     * Investigate and Dismiss, on the press. The row is patched in place, as
     * the web's is — no reload, so the list does not jump under the pointer.
     */
    private fun alertAction(alertId: String, action: AlertAction) {
        val state = vm.current
        val alert = state.alerts.firstOrNull { it.id == alertId }
        val allowed = state.viewer.isAccountant && alert != null && when (action) {
            AlertAction.Investigate -> alert.status == CardAlert.ACTIVE
            else -> alert.isOpen
        }
        if (!allowed) return refuse()
        if (alertId in state.insights.alertBusy) return
        edit { copy(alertBusy = alertBusy + (alertId to action)) }
        vm.run {
            val (done, status, notice) = when (action) {
                AlertAction.Investigate -> Triple(
                    vm.repo.investigateAlert(alertId),
                    CardAlert.INVESTIGATING,
                    str(S.desktop_ce_insights_alert_investigating),
                )

                else -> Triple(vm.repo.dismissAlert(alertId), CardAlert.DISMISSED, str(S.ah_alert_dismissed_toast))
            }
            edit { copy(alertBusy = alertBusy - alertId) }
            when (done) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        alerts = alerts.map { if (it.id == alertId) it.copy(status = status) else it },
                        notice = notice,
                    )
                }

                is ZillitResult.Failure -> vm.fail(done.error.localised())
            }
        }
    }

    /** Confirm Resolution: the note is optional and always sent (`SmartAlertsPage.jsx:144-155`). */
    private fun resolve() {
        val draft = vm.current.insights.resolve ?: return
        val state = vm.current
        val open = state.alerts.any { it.id == draft.alertId && it.isOpen }
        if (!state.viewer.isAccountant || !open) return refuse()
        if (draft.alertId in state.insights.alertBusy) return
        edit { copy(alertBusy = alertBusy + (draft.alertId to AlertAction.Resolve)) }
        vm.run {
            val done = vm.repo.resolveAlert(draft.alertId, draft.note)
            // The modal closes either way, as the web's does.
            edit { copy(alertBusy = alertBusy - draft.alertId, resolve = null) }
            when (done) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        alerts = alerts.map { alert ->
                            if (alert.id == draft.alertId) {
                                alert.copy(
                                    status = CardAlert.RESOLVED,
                                    resolution = draft.note.takeIf { it.isNotBlank() },
                                )
                            } else {
                                alert
                            }
                        },
                        notice = str(S.ah_alert_resolved_toast),
                    )
                }

                is ZillitResult.Failure -> vm.fail(done.error.localised())
            }
        }
    }

    // -- settings: team -----------------------------------------------------------

    private fun openMember(index: Int?) {
        if (!vm.current.viewer.canOpenSettings) return refuse()
        val members = vm.current.settings?.teamMembers.orEmpty()
        val editor = if (index == null) {
            TeamMemberEditor(CardTeamMember(userId = "", postingLimit = 0.0))
        } else {
            val member = members.getOrNull(index) ?: return
            TeamMemberEditor(member, index, member.postingLimit?.takeIf { it > 0 }?.let(::plainAmount).orEmpty())
        }
        edit { copy(memberEditor = editor) }
    }

    /** Add / Update PATCHes `{team_members}` at once; the modal stays open on failure. */
    private fun saveMember() {
        val editor = vm.current.insights.memberEditor ?: return
        if (editor.member.userId.isBlank() || vm.current.insights.teamSaving) return
        val members = vm.current.settings?.teamMembers.orEmpty()
        val member = editor.member.normalised()
        val next = editor.index?.let { at -> members.mapIndexed { i, row -> if (i == at) member else row } }
            ?: (members + member)
        persistTeam(next, closeEditor = true)
    }

    private fun removeMember(index: Int) {
        val members = vm.current.settings?.teamMembers.orEmpty()
        if (index !in members.indices || vm.current.insights.teamSaving) return
        persistTeam(members.filterIndexed { at, _ -> at != index }, closeEditor = false)
    }

    /** The web's `persistTeam`: the list commits only when the server kept it. */
    private fun persistTeam(next: List<CardTeamMember>, closeEditor: Boolean) {
        if (!vm.current.viewer.canOpenSettings) return refuse()
        val base = vm.current.settings ?: return
        edit { copy(teamSaving = true) }
        vm.run {
            val saved = vm.repo.updateSettings(SettingsSection.Team, base.copy(teamMembers = next))
            edit { copy(teamSaving = false) }
            when (saved) {
                is ZillitResult.Success -> teamSaved(saved.data.teamMembers, next, closeEditor)
                is ZillitResult.Failure -> vm.fail(saved.error.localised())
            }
        }
    }

    /** Judges the echo, not the status: a PATCH can answer `status:1` and keep nothing. */
    private fun teamSaved(stored: List<CardTeamMember>, sent: List<CardTeamMember>, closeEditor: Boolean) {
        val kept = stored == sent.map { it.normalised() }
        vm.update {
            copy(
                settings = settings?.copy(teamMembers = stored),
                settingsDraft = settingsDraft?.copy(teamMembers = stored),
            )
        }
        if (!kept) return vm.fail(str(S.desktop_card_section_not_stored, SettingsSection.Team.label))
        vm.update { copy(notice = str(S.desktop_card_section_saved, SettingsSection.Team.label)) }
        if (closeEditor) edit { copy(memberEditor = null) }
    }

    // -- settings: coordinators -------------------------------------------------

    private fun openPicker(index: Int, users: Boolean) {
        val row = vm.current.settingsDraft?.coordinators?.getOrNull(index) ?: return
        if (users && row.departmentId.isBlank()) return
        val errorKey = if (users) "${index}_users" else "${index}_dept"
        edit {
            copy(
                coordinatorPicker = CoordinatorPicker(
                    index = index,
                    users = users,
                    departmentId = row.departmentId,
                    userIds = row.userIds,
                ),
                coordinatorErrors = coordinatorErrors - errorKey,
            )
        }
    }

    /** Done: the buffered choice lands on the row; a new department clears its people. */
    private fun applyPicker() {
        val picker = vm.current.insights.coordinatorPicker ?: return
        vm.update {
            val draft = settingsDraft ?: return@update this
            val rows = draft.coordinators.mapIndexed { at, row ->
                when {
                    at != picker.index -> row
                    picker.users -> row.copy(userIds = picker.userIds)
                    picker.departmentId != row.departmentId ->
                        row.copy(departmentId = picker.departmentId, userIds = emptyList())

                    else -> row
                }
            }
            copy(settingsDraft = draft.copy(coordinators = rows), insights = insights.copy(coordinatorPicker = null))
        }
    }

    private fun removeCoordinator(index: Int) = vm.update {
        val draft = settingsDraft ?: return@update this
        copy(
            settingsDraft = draft.copy(coordinators = draft.coordinators.filterIndexed { at, _ -> at != index }),
            insights = insights.copy(coordinatorErrors = emptyMap()),
        )
    }

    // -- settings: assignment rules ---------------------------------------------

    private fun addRule() {
        val state = vm.current
        val assignee = state.people.firstOrNull { it.isAccountsTeam }?.id.orEmpty()
        val localId = "temp-${state.settingsDraft?.assignmentRules.orEmpty().size + 1}-${state.people.size}"
        editRules { this + CardAssignmentRule(id = localId, assignTo = assignee) }
    }

    private fun editRules(change: List<CardAssignmentRule>.() -> List<CardAssignmentRule>) = vm.update {
        val draft = settingsDraft ?: return@update this
        copy(settingsDraft = draft.copy(assignmentRules = draft.assignmentRules.change()))
    }

    /** A saved rule is deleted at once (`removeAssignmentRule`); a new one just leaves. */
    private fun removeRule(index: Int) {
        if (!vm.current.viewer.canOpenSettings) return refuse()
        val rule = vm.current.settingsDraft?.assignmentRules?.getOrNull(index) ?: return
        editRules { filterIndexed { at, _ -> at != index } }
        if (!rule.persisted) return
        vm.update {
            copy(settings = settings?.copy(assignmentRules = settings.assignmentRules.filter { it.id != rule.id }))
        }
        vm.run {
            when (val done = hub.deleteAssignmentRule(rule.id)) {
                is ZillitResult.Success -> vm.update { copy(notice = str(S.desktop_ce_insights_rule_deleted)) }
                is ZillitResult.Failure -> vm.fail(done.error.localised())
            }
        }
    }

    /** Each rule in turn — PATCH if it is saved, POST if new — then the list the server returned. */
    private fun saveRules() {
        if (!vm.current.viewer.canOpenSettings) return refuse()
        val rules = vm.current.settingsDraft?.assignmentRules ?: return
        if (vm.current.insights.rulesSaving) return
        edit { copy(rulesSaving = true) }
        vm.run {
            val saved = mutableListOf<CardAssignmentRule>()
            for (rule in rules) {
                when (val done = hub.saveAssignmentRule(rule)) {
                    is ZillitResult.Success -> saved += done.data.copy(persisted = true)
                    is ZillitResult.Failure -> {
                        edit { copy(rulesSaving = false) }
                        vm.fail(done.error.localised())
                        return@run
                    }
                }
            }
            edit { copy(rulesSaving = false) }
            vm.update {
                copy(
                    settings = settings?.copy(assignmentRules = saved),
                    settingsDraft = settingsDraft?.copy(assignmentRules = saved),
                    notice = str(S.desktop_ce_insights_rules_saved),
                )
            }
        }
    }

    // -- plumbing -----------------------------------------------------------------

    private fun edit(block: InsightsState.() -> InsightsState) = vm.update { copy(insights = insights.block()) }

    private fun settle(done: ZillitResult<Unit>, notice: String) {
        when (done) {
            is ZillitResult.Success -> {
                vm.update { copy(notice = notice) }
                vm.reload()
            }

            is ZillitResult.Failure -> vm.fail(done.error.localised())
        }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))
}
