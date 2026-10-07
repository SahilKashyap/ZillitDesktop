package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.domain.SpendCoordinator
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.domain.SpendTeamMember
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupModalSection
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HairLine
import com.zillit.desktop.feature.accounthub.ui.components.HoverRow
import com.zillit.desktop.feature.accounthub.ui.components.HubMultiSelect
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.PersonChip
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.ToggleRow
import com.zillit.desktop.feature.accounthub.ui.components.groupAmount
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

/**
 * Production Expense Cards and Petty Cash Entry Setup — the web's
 * `CardExpensesSetupDetail` / `CashExpensesSetupDetail`, one modal over each
 * module's own settings document.
 *
 * Seven sections: accounts, team, coordinators, approval overrides, deduction
 * rules, quick codes, and the shared auto-assignment rules. The two modules
 * differ in the accounts pane (card providers against a float custodian and BS
 * range), the approval switches and a coordinator's "view floats" flag.
 */
internal const val SPEND_ACCOUNTS = "acct"
internal const val SPEND_TEAM = "team"
internal const val SPEND_COORDINATORS = "coord"
internal const val SPEND_APPROVALS = "appr"
internal const val SPEND_DEDUCTIONS = "rules"
internal const val SPEND_CODES = "codes"

// Not "rules": Deduction Rules owns that id, and the shell finds the active section by id.
internal const val SPEND_ASSIGNMENT = "assign"

internal fun spendSections(kind: SpendKind, state: AccountHubUiState): List<SetupModalSection> {
    // Counts read the document of this module; a stale other-module document counts as nothing.
    val value = state.setup.spendSetup.edited.takeIf { it.kind == kind } ?: SpendSettings(kind)
    return listOf(
        SetupModalSection(SPEND_ACCOUNTS, str(S.desktop_hub_sp_sec_accounts), value.accountCount),
        SetupModalSection(SPEND_TEAM, str(S.ah_settings_team_posting), value.team.size),
        SetupModalSection(SPEND_COORDINATORS, str(S.desktop_hub_sp_sec_coord), value.coordinators.size),
        SetupModalSection(SPEND_APPROVALS, str(S.desktop_hub_sp_sec_approval), value.approvalsOn),
        SetupModalSection(SPEND_DEDUCTIONS, str(S.desktop_hub_sp_sec_deduction), value.deductionRules.size),
        SetupModalSection(SPEND_CODES, str(S.desktop_hub_sp_sec_codes), value.quickCodes.size),
        SetupModalSection(SPEND_ASSIGNMENT, str(S.desktop_auto_assignment_rules), state.setup.spendRules.edited.size),
    )
}

@Composable
internal fun ColumnScope.SpendModalBody(
    sectionId: String,
    kind: SpendKind,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val value = state.setup.spendSetup.edited
    // The other module's document, left from an earlier open, is never drawn.
    if (value.kind != kind) return
    val editable = state.viewer.canEdit
    fun update(next: SpendSettings) = onEvent(AccountHubEvent.EditSpendSetup(next))
    when (sectionId) {
        SPEND_ACCOUNTS -> if (kind == SpendKind.Cards) {
            CardProvidersPane(value, state, editable, onEvent, ::update)
        } else {
            CashAccountsPane(value, state, editable, onEvent, ::update)
        }
        SPEND_TEAM -> TeamPane(value, state, editable, onEvent, ::update)
        SPEND_COORDINATORS -> CoordinatorsPane(value, state, editable, ::update)
        SPEND_APPROVALS -> ApprovalPane(value, editable, ::update)
        SPEND_DEDUCTIONS -> DeductionPane(value, state, editable, onEvent, ::update)
        SPEND_CODES -> QuickCodesPane(value, state, editable, onEvent, ::update)
        SPEND_ASSIGNMENT -> AssignmentRulesSection(
            rules = state.setup.spendRules.edited,
            module = kind.moduleKey,
            showVendors = false,
            state = state,
            editable = editable,
            onChange = { onEvent(AccountHubEvent.EditSpendRules(it)) },
        )
    }
}

// -- accounts (cash) ---------------------------------------------------------------

/**
 * The float custodian and the BS code range — the cash side's whole accounts
 * pane. The codes are balance-sheet accounts, offered from the chart's balance
 * sheet rows; a code typed that the chart does not hold still commits, as on
 * the web.
 */
@Composable
private fun ColumnScope.CashAccountsPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (SpendSettings) -> Unit,
) {
    val accounts = balanceSheet(state)
    val create = quickCreateHandler(state, onEvent)
    SubCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                CoaCodeField(
                    value = value.custodianAccount,
                    onValueChange = { update(value.copy(custodianAccount = it)) },
                    accounts = accounts,
                    label = str(S.desktop_ce_float_custodian_account),
                    placeholder = str(S.desktop_pc_enter_account_code),
                    enabled = editable,
                    costType = CoaCostType.Asset,
                    onCreate = create,
                )
                FieldHint(str(S.desktop_pc_custodian_help))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                CoaCodeField(
                    value = value.bsCodeFrom,
                    onValueChange = { update(value.copy(bsCodeFrom = it)) },
                    accounts = accounts,
                    label = str(S.desktop_pc_bs_code_from),
                    placeholder = str(S.desktop_pc_eg_1000),
                    enabled = editable,
                    costType = CoaCostType.Asset,
                    onCreate = create,
                )
                FieldHint(str(S.desktop_hub_sp_float_from_hint))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                CoaCodeField(
                    value = value.bsCodeTo,
                    onValueChange = { update(value.copy(bsCodeTo = it)) },
                    accounts = accounts,
                    label = str(S.desktop_pc_bs_code_to),
                    placeholder = str(S.desktop_pc_eg_1999),
                    enabled = editable,
                    costType = CoaCostType.Asset,
                    onCreate = create,
                )
                FieldHint(str(S.desktop_hub_sp_float_to_hint))
            }
        }
    }
}

/** The chart's balance-sheet rows — the web's Balance Sheet Codes — which custodian and float codes pick from. */
internal fun balanceSheet(state: AccountHubUiState): List<CoaAccount> =
    state.chart.accounts.filter { it.costType.isBalanceSheet }

// -- team ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.TeamPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (SpendSettings) -> Unit,
) {
    SubCard(
        action = {
            if (editable) {
                GhostAddButton(
                    str(S.desktop_hub_sp_add_member),
                    onClick = { onEvent(AccountHubEvent.ComposeSpendMember(null)) },
                )
            }
        },
        padded = false,
    ) {
        if (value.team.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_team_members_configured),
                message = str(S.desktop_hub_sp_team_hint),
                icon = ZillitIcons.Users,
                action = if (editable) {
                    {
                        ZillitButton(
                            text = str(S.desktop_hub_add_first_member),
                            onClick = { onEvent(AccountHubEvent.ComposeSpendMember(null)) },
                            size = ButtonSize.Small,
                        )
                    }
                } else {
                    null
                },
            )
        } else {
            TeamHeader()
            value.team.forEachIndexed { index, member ->
                TeamRow(
                    member = member,
                    state = state,
                    editable = editable,
                    onEdit = { onEvent(AccountHubEvent.ComposeSpendMember(index)) },
                    onRemove = { update(value.copy(team = value.team.filterIndexed { i, _ -> i != index })) },
                )
            }
        }
    }
}

@Composable
private fun TeamHeader() {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MonoLabel(str(S.user_label), Modifier.weight(2f))
        MonoLabel(str(S.desktop_posting_limit), Modifier.weight(1f))
        MonoLabel(str(S.desktop_can_override), Modifier.weight(OVERRIDE_WEIGHT))
        MonoLabel(str(S.desktop_senior), Modifier.weight(SENIOR_WEIGHT))
        Box(Modifier.width(ACTIONS_COLUMN))
    }
}

@Composable
private fun TeamRow(
    member: SpendTeamMember,
    state: AccountHubUiState,
    editable: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    HairLine()
    val user = state.user(member.userId)
    HoverRow(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        onClick = if (editable) onEdit else null,
        actions = { _ ->
            Row(
                modifier = Modifier.width(ACTIONS_COLUMN),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editable) {
                    ZillitIconButton(icon = ZillitIcons.Edit, contentDescription = str(S.edit), onClick = onEdit)
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = onRemove,
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        },
    ) {
        PersonChip(
            name = user?.name ?: str(S.unkone_user),
            userId = member.userId,
            role = user?.roleLabel,
            modifier = Modifier.weight(2f),
        )
        ZillitText(
            text = limitLabel(member, state),
            style = ZillitTheme.typography.numeric,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Box(Modifier.weight(OVERRIDE_WEIGHT)) {
            Pill(
                text = if (member.canOverride) str(S.yes) else str(S.no),
                tone = if (member.canOverride) StatusTone.Done else StatusTone.Neutral,
                dot = true,
            )
        }
        Box(Modifier.weight(SENIOR_WEIGHT)) {
            if (member.isSenior) Pill(str(S.desktop_senior), tone = StatusTone.Progress) else FieldHint("—")
        }
    }
}

/** Null is unlimited, zero is submit only, anything else the ceiling — the web's `limitLabel`. */
private fun limitLabel(member: SpendTeamMember, state: AccountHubUiState): String {
    val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
    val limit = member.postingLimit
    return when {
        limit == null -> str(S.drive_link_views_unlimited)
        limit == 0.0 -> str(S.desktop_hub_sp_submit_only)
        else -> "$symbol${groupAmount(limit.toString().removeSuffix(".0"))}"
    }
}

// -- coordinators ---------------------------------------------------------------------

@Composable
private fun ColumnScope.CoordinatorsPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    update: (SpendSettings) -> Unit,
) {
    val cash = value.kind == SpendKind.Cash
    fun add() = update(value.copy(coordinators = value.coordinators + SpendCoordinator()))
    SubCard(
        hint = str(if (cash) S.desktop_hub_sp_coord_hint_cash else S.desktop_hub_sp_coord_hint_card),
        action = { if (editable) GhostAddButton(str(S.desktop_hub_sp_add_coordinator), ::add) },
        padded = false,
    ) {
        if (value.coordinators.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_sp_coord_empty_title),
                message = str(if (cash) S.desktop_hub_sp_coord_empty_cash else S.desktop_hub_sp_coord_empty_card),
                icon = ZillitIcons.Users,
                action = if (editable) {
                    { ZillitButton(str(S.desktop_hub_sp_add_first_coordinator), ::add, size = ButtonSize.Small) }
                } else {
                    null
                },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MonoLabel(str(S.department), Modifier.weight(1f))
                MonoLabel(str(S.invitees_tab_users), Modifier.weight(1.4f))
                MonoLabel(str(S.desktop_card_coding), Modifier.width(SWITCH_COLUMN))
                if (cash) MonoLabel(str(S.desktop_hub_sp_col_view_floats), Modifier.width(SWITCH_COLUMN))
                Box(Modifier.width(REMOVE_COLUMN))
            }
            value.coordinators.forEachIndexed { index, coordinator ->
                HairLine()
                CoordinatorRow(
                    coordinator = coordinator,
                    cash = cash,
                    state = state,
                    editable = editable,
                    onChange = { next ->
                        val rows = value.coordinators.mapIndexed { i, c -> if (i == index) next else c }
                        update(value.copy(coordinators = rows))
                    },
                    onRemove = {
                        update(value.copy(coordinators = value.coordinators.filterIndexed { i, _ -> i != index }))
                    },
                )
            }
        }
    }
}

@Suppress("LongMethod") // A row of cells, read left to right; the order is the reading order.
@Composable
private fun CoordinatorRow(
    coordinator: SpendCoordinator,
    cash: Boolean,
    state: AccountHubUiState,
    editable: Boolean,
    onChange: (SpendCoordinator) -> Unit,
    onRemove: () -> Unit,
) {
    val department = state.departmentList.firstOrNull { it.id == coordinator.departmentId }
    // A department the list no longer holds stays chosen, named by whatever the page can still call it.
    val departments = state.departmentList + listOfNotNull(
        coordinator.departmentId.takeIf { it.isNotBlank() && department == null }?.let { HubDepartment(id = it) },
    )
    val pool = departmentUsers(state, department)
    val chosen = coordinator.userIds.map { id -> state.user(id) ?: HubUser(id = id, name = state.userName(id)) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        HubSelect(
            value = departments.firstOrNull { it.id == coordinator.departmentId },
            options = departments,
            label = { state.departmentName(it.id).ifBlank { it.name } },
            onSelect = { picked ->
                // The users are scoped to the department, so a different one starts them afresh.
                if (picked != null && picked.id != coordinator.departmentId) {
                    onChange(coordinator.copy(departmentId = picked.id, userIds = emptyList()))
                }
            },
            placeholder = str(S.desktop_hub_sp_pick_department),
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(1.4f)) {
            if (coordinator.departmentId.isBlank()) {
                FieldHint(str(S.desktop_hub_sp_pick_dept_first))
            } else {
                HubMultiSelect(
                    selected = chosen,
                    options = pool + chosen.filter { picked -> pool.none { it.id == picked.id } },
                    label = { it.name.ifBlank { state.userName(it.id) } },
                    onChange = { picked -> onChange(coordinator.copy(userIds = picked.map { it.id })) },
                    placeholder = str(
                        if (pool.isEmpty()) S.desktop_hub_sp_no_dept_users else S.desktop_hub_sp_pick_users,
                    ),
                    enabled = editable,
                )
            }
        }
        Box(Modifier.width(SWITCH_COLUMN).padding(top = ZillitTheme.spacing.sm), contentAlignment = Alignment.Center) {
            ZillitSwitch(
                checked = coordinator.codingRequired,
                onCheckedChange = { onChange(coordinator.copy(codingRequired = it)) },
                enabled = editable,
            )
        }
        if (cash) {
            Box(
                Modifier.width(SWITCH_COLUMN).padding(top = ZillitTheme.spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                ZillitSwitch(
                    checked = coordinator.viewDepartmentFloats,
                    onCheckedChange = { onChange(coordinator.copy(viewDepartmentFloats = it)) },
                    enabled = editable,
                )
            }
        }
        Box(Modifier.width(REMOVE_COLUMN), contentAlignment = Alignment.Center) {
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.remove),
                    onClick = onRemove,
                    tint = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

/** The accepted crew of a department — the web's `getUsersByDepartment`. */
private fun departmentUsers(state: AccountHubUiState, department: HubDepartment?): List<HubUser> {
    department ?: return emptyList()
    return HubUsers.accepted(state.users).filter {
        it.departmentId == department.id ||
            (department.identifier.isNotBlank() && it.departmentIdentifier == department.identifier)
    }
}

// -- approvals ------------------------------------------------------------------------

@Composable
private fun ColumnScope.ApprovalPane(value: SpendSettings, editable: Boolean, update: (SpendSettings) -> Unit) {
    SubCard {
        value.kind.approvalToggles.forEachIndexed { index, toggle ->
            if (index > 0) HairLine()
            ToggleRow(
                label = str(toggle.labelKey),
                hint = str(toggle.hintKey),
                checked = value.approvalOn(toggle.key),
                onCheckedChange = { update(value.copy(approval = value.approval + (toggle.key to it))) },
                enabled = editable,
            )
        }
    }
}

/** Shares of the team table's width after the person; the limit takes one. */
private const val OVERRIDE_WEIGHT = 1.2f
private const val SENIOR_WEIGHT = 0.9f
private val ACTIONS_COLUMN = 72.dp
private val SWITCH_COLUMN = 100.dp
private val REMOVE_COLUMN = 36.dp
