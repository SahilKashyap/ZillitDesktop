package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.AlertFilter
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardCompany
import com.zillit.desktop.feature.cardexpenses.domain.CardDepartment
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.TopUpBoard
import com.zillit.desktop.feature.cardexpenses.domain.TopUpLimitAlert

/**
 * What Top-Up To Do, Smart Alerts, Analytics and Settings hold between
 * events — the per-page drafts and in-flight markers the web keeps in
 * component state. One field of [CardUiState] carries all of it.
 */
data class InsightsState(
    // -- top-ups ----------------------------------------------------------------
    val topUpFilter: String = TopUpBoard.ALL,
    val partial: PartialTopUpDraft? = null,
    /** The over-limit explainer, open over whatever raised it. */
    val limitAlert: TopUpLimitAlert? = null,
    /** The done-table row open in "Top-Up Details". */
    val topUpDetailId: String? = null,
    val history: TopUpHistoryView? = null,
    /** The row a Mark Topped Up or Skip is in flight for (`skippingKey`). */
    val completingId: String? = null,
    val skippingId: String? = null,

    // -- smart alerts -----------------------------------------------------------
    val alertFilter: AlertFilter = AlertFilter.All,
    /** Which action is in flight per alert (`busy`, `SmartAlertsPage.jsx:109`). */
    val alertBusy: Map<String, AlertAction> = emptyMap(),
    val resolve: ResolveDraft? = null,

    // -- settings and analytics: the hub's lists -------------------------------
    val companies: List<CardCompany> = emptyList(),
    val departments: List<CardDepartment> = emptyList(),
    val memberEditor: TeamMemberEditor? = null,
    val teamSaving: Boolean = false,
    /** Per-row coordinator errors, keyed `"<index>_dept"` / `"<index>_users"` as the web's. */
    val coordinatorErrors: Map<String, String> = emptyMap(),
    val coordinatorPicker: CoordinatorPicker? = null,
    val rulesSaving: Boolean = false,
)

data class PartialTopUpDraft(
    val topUpId: String,
    val amount: String,
    val note: String = "",
    val submitting: Boolean = false,
)

data class TopUpHistoryView(
    val holderName: String,
    val loading: Boolean = true,
    val entries: List<CardHistoryEntry> = emptyList(),
)

enum class AlertAction { Investigate, Resolve, Dismiss }

data class ResolveDraft(val alertId: String, val note: String = "")

/**
 * The Add / Edit Team Member modal (`SettingsPage.jsx:594-667`).
 * [index] is null when adding.
 */
data class TeamMemberEditor(
    val member: CardTeamMember,
    val index: Int? = null,
    /** The posting-limit field as typed; blank with [CardTeamMember.postingLimit] null is Unlimited. */
    val limitText: String = "",
) {
    val adding: Boolean get() = index == null
}

/** The department or users picker over one coordinator row (`SettingsPage.jsx:753-845`). */
data class CoordinatorPicker(
    val index: Int,
    val users: Boolean,
    val search: String = "",
    /** The buffered choice, applied only on Done. */
    val departmentId: String = "",
    val userIds: List<String> = emptyList(),
)

/** The insight pages' own events; dispatched by [CardInsightActions]. */
sealed interface InsightsEvent : CardEvent {
    // top-ups
    data class FilterTopUps(val filter: String) : InsightsEvent
    data class MarkToppedUp(val topUpId: String) : InsightsEvent
    data class SkipTopUp(val topUpId: String) : InsightsEvent
    data class OpenPartial(val topUpId: String) : InsightsEvent
    data class EditPartial(val amount: String, val note: String) : InsightsEvent
    data object SubmitPartial : InsightsEvent
    data object ClosePartial : InsightsEvent
    data object DismissLimitAlert : InsightsEvent
    data class OpenTopUpDetail(val topUpId: String?) : InsightsEvent
    data class OpenHistory(val topUpId: String, val holderName: String) : InsightsEvent
    data object CloseHistory : InsightsEvent

    // smart alerts
    data class FilterAlerts(val filter: AlertFilter) : InsightsEvent
    data class Investigate(val alertId: String) : InsightsEvent
    data class Dismiss(val alertId: String) : InsightsEvent
    data class OpenResolve(val alertId: String?) : InsightsEvent
    data class EditResolveNote(val note: String) : InsightsEvent
    data object ConfirmResolve : InsightsEvent

    // settings: team
    data class OpenMemberEditor(val index: Int?) : InsightsEvent
    data class EditMember(val editor: TeamMemberEditor) : InsightsEvent
    data object CloseMemberEditor : InsightsEvent
    data object SaveMember : InsightsEvent
    data class RemoveMember(val index: Int) : InsightsEvent

    // settings: coordinators
    data class OpenCoordinatorPicker(val index: Int, val users: Boolean) : InsightsEvent
    data class EditCoordinatorPicker(val picker: CoordinatorPicker) : InsightsEvent
    data object ApplyCoordinatorPicker : InsightsEvent
    data object CloseCoordinatorPicker : InsightsEvent
    data class RemoveCoordinator(val index: Int) : InsightsEvent

    // settings: assignment rules
    data object AddRule : InsightsEvent
    data class EditRule(val index: Int, val rule: CardAssignmentRule) : InsightsEvent
    data class RemoveRule(val index: Int) : InsightsEvent
    data object SaveRules : InsightsEvent

    // fund requests
    data class PickFundBank(val bankId: String) : InsightsEvent
    data class PickFundAccount(val code: String) : InsightsEvent
}
