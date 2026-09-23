package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.TeamMemberDraft

/**
 * Team & Posting Rights — who is on the cash team, their posting limit, and
 * whether they may override the approval chain (`PCSettingsPage.jsx:640-760`).
 *
 * Rows, not a table: a data table inside this scrolling page measures to
 * nothing. Each change saves at once, as the web's `persistTeam` does.
 */
@Composable
internal fun TeamCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val members = state.settings?.teamMembers.orEmpty()
    ZillitSectionCard(
        title = str(S.ah_settings_team_posting),
        icon = ZillitIcons.Users,
        meta = str(S.desktop_ce_member_count, members.size),
        action = {
            ZillitButton(
                text = str(S.txt_add_member),
                onClick = { onEvent(CashEvent.EditTeamMember(TeamMemberDraft())) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                enabled = !state.busy,
            )
        },
    ) {
        if (members.isEmpty()) {
            ZillitText(
                text = str(S.desktop_ce_team_empty_note),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        members.forEachIndexed { index, member ->
            if (index > 0) ZillitDivider()
            TeamRow(member, index, state.busy, onEvent)
        }
    }
}

/** One member: who, their standing, their limit, and Edit / Remove — never revealed by hover. */
@Composable
private fun TeamRow(member: CashTeamMember, index: Int, busy: Boolean, onEvent: (CashEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CashPerson(userId = member.userId, recordedName = member.name, modifier = Modifier.weight(1f))
        ZillitStatusPill(
            label = if (member.isSenior) str(S.desktop_senior) else str(S.desktop_ce_team),
            tone = if (member.isSenior) StatusTone.Done else StatusTone.Neutral,
        )
        if (member.canOverride) {
            ZillitStatusPill(label = str(S.dm_nom_table_override), tone = StatusTone.Pending)
        }
        ZillitText(
            // No limit is a real answer, and printing it as "0.00" would read
            // as "may post nothing".
            text = member.postingLimit?.let { Money.format(it, null) } ?: str(S.desktop_ce_no_limit),
            style = ZillitTheme.typography.numeric,
            modifier = Modifier.width(LIMIT_WIDTH),
        )
        ZillitIconButton(
            icon = ZillitIcons.Edit,
            contentDescription = str(S.edit),
            onClick = {
                onEvent(
                    CashEvent.EditTeamMember(
                        TeamMemberDraft(
                            userId = member.userId,
                            postingLimit = member.postingLimit?.let(::plainNumber),
                            canOverride = member.canOverride,
                            isSenior = member.isSenior,
                            index = index,
                        ),
                    ),
                )
            },
            enabled = !busy,
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_ce_remove_member),
            onClick = { onEvent(CashEvent.RemoveTeamMember(index)) },
            tint = ZillitTheme.colors.danger,
            enabled = !busy,
        )
    }
}

/**
 * Add or edit one team member — the web's Team Member modal. A senior is
 * unlimited and may override, so both controls lock while Senior is on.
 */
@Suppress("LongMethod") // The modal's four fields and its two buttons.
@Composable
internal fun TeamMemberDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val draft = state.teamEditor
    val people = LocalCashPeople.current
    val members = state.settings?.teamMembers.orEmpty()
    ZillitDialogShell(
        title = if (draft?.index == null) str(S.desktop_add_team_member) else str(S.desktop_edit_team_member),
        visible = draft != null,
        onDismiss = { onEvent(CashEvent.EditTeamMember(null)) },
        icon = ZillitIcons.Users,
    ) {
        if (draft == null) return@ZillitDialogShell
        fun edit(next: TeamMemberDraft) = onEvent(CashEvent.EditTeamMember(next))
        val team = BatchAssignment.accountsTeam(state.assignees).filter { person ->
            draft.index != null || members.none { it.userId == person.userId }
        }
        ZillitText(text = str(S.desktop_team_member), style = ZillitTheme.typography.label)
        ZillitSelect(
            value = draft.userId,
            options = (listOf("") + team.map { it.userId } + listOfNotNull(draft.userId.ifBlank { null })).distinct(),
            onSelect = { edit(draft.copy(userId = it)) },
            label = { id -> if (id.isBlank()) str(S.select_user) else people.nameOf(id) },
            enabled = draft.index == null,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitCheckbox(
            checked = draft.postingLimit == null,
            onCheckedChange = { edit(draft.copy(postingLimit = if (it) null else "0")) },
            label = str(S.desktop_ce_no_limit),
            enabled = !draft.isSenior,
        )
        ZillitTextField(
            value = draft.postingLimit.orEmpty(),
            onValueChange = { edit(draft.copy(postingLimit = it)) },
            label = str(S.desktop_posting_limit),
            placeholder = str(S.desktop_ce_enter_limit),
            keyboardType = KeyboardType.Decimal,
            enabled = draft.postingLimit != null && !draft.isSenior,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitSwitch(
            checked = draft.canOverride || draft.isSenior,
            onCheckedChange = { if (!draft.isSenior) edit(draft.copy(canOverride = it)) },
            label = str(S.desktop_ce_can_override),
            enabled = !draft.isSenior,
        )
        ZillitSwitch(
            checked = draft.isSenior,
            onCheckedChange = { senior ->
                edit(
                    if (senior) {
                        draft.copy(isSenior = true, postingLimit = null, canOverride = true)
                    } else {
                        draft.copy(isSenior = false)
                    },
                )
            },
            label = str(S.desktop_senior),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CashEvent.EditTeamMember(null)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (draft.index == null) str(S.add) else str(S.update),
                onClick = { onEvent(CashEvent.SaveTeamMember) },
                enabled = draft.userId.isNotBlank() && !state.busy,
                loading = state.busy,
            )
        }
    }
}

/**
 * Request Cap — the ceiling on a float request (`RequestCapSection`), saved on
 * its own. A switched-on cap of nothing would refuse every request, so it
 * cannot be saved without an amount.
 */
@Suppress("LongMethod") // The switch, the basis and the two figures.
@Composable
internal fun RequestCapCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val stored = state.settings?.requestCap ?: RequestCap()
    val cap = state.capDraft ?: stored
    fun edit(next: RequestCap) = onEvent(CashEvent.EditRequestCap(next))
    ZillitSectionCard(
        title = str(S.desktop_ce_request_cap),
        icon = ZillitIcons.Shield,
        action = {
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(CashEvent.SaveRequestCap) },
                size = ButtonSize.Small,
                enabled = state.capDraft != null && cap != stored && !cap.blocked && !state.busy,
            )
        },
    ) {
        ZillitSwitch(
            checked = cap.enabled,
            onCheckedChange = { edit(cap.copy(enabled = it)) },
            label = str(S.desktop_ce_cap_float_requests),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                ZillitText(text = str(S.desktop_ce_cap_basis), style = ZillitTheme.typography.label)
                ZillitSelect(
                    value = cap.basis,
                    options = listOf(RequestCap.MAX_AMOUNT, RequestCap.WEEKLY_SALARY),
                    onSelect = { edit(cap.copy(basis = it)) },
                    label = { basis ->
                        if (basis == RequestCap.WEEKLY_SALARY) {
                            str(S.desktop_ce_weekly_salary)
                        } else {
                            str(S.desktop_ce_max_amount)
                        }
                    },
                    enabled = cap.enabled,
                    modifier = Modifier.width(BASIS_WIDTH),
                )
            }
            if (cap.isWeekly) {
                ZillitTextField(
                    value = plainNumber(cap.salaryMultiplier),
                    onValueChange = { edit(cap.copy(salaryMultiplier = it.toDoubleOrNull() ?: 0.0)) },
                    label = str(S.desktop_ce_salary_multiplier),
                    keyboardType = KeyboardType.Decimal,
                    enabled = cap.enabled,
                    modifier = Modifier.width(FIGURE_WIDTH),
                )
            }
            ZillitTextField(
                value = plainNumber(cap.maxAmount),
                onValueChange = { edit(cap.copy(maxAmount = it.toDoubleOrNull() ?: 0.0)) },
                label = if (cap.isWeekly) str(S.desktop_ce_fallback_maximum) else str(S.desktop_ce_max_amount),
                keyboardType = KeyboardType.Decimal,
                enabled = cap.enabled,
                modifier = Modifier.width(FIGURE_WIDTH),
            )
        }
        if (cap.blocked) {
            ZillitText(
                text = str(S.desktop_ce_cap_needs_amount),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/**
 * Auto-Assignment — who a batch lands with when it matches
 * (`PCSettingsPage.jsx:1283-1320`), written through the account hub's rules
 * route under module `cash_expenses`.
 *
 * Departments are shown and kept as they are; choosing them needs the
 * production's departments, which this tool is not handed.
 */
@Suppress("LongMethod") // The rule rows and their Save.
@Composable
internal fun AssignmentRulesCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val stored = state.settings?.assignmentRules.orEmpty()
    val rules = state.rulesDraft ?: stored
    val team = BatchAssignment.accountsTeam(state.assignees)
    val people = LocalCashPeople.current
    fun edit(next: List<CashAssignmentRule>) = onEvent(CashEvent.EditAssignmentRules(next))
    ZillitSectionCard(
        title = str(S.desktop_ce_auto_assignment),
        icon = ZillitIcons.Hierarchy,
        meta = str(S.desktop_ce_rule_count, rules.size),
        action = {
            ZillitButton(
                text = str(S.desktop_add_rule),
                onClick = {
                    val fresh = CashAssignmentRule(
                        id = "local-${rules.size}-${stored.size}",
                        assignTo = team.firstOrNull()?.userId.orEmpty(),
                    )
                    edit(rules + fresh)
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(CashEvent.SaveAssignmentRules) },
                size = ButtonSize.Small,
                enabled = state.rulesDraft != null && rules != stored && !state.busy,
            )
        },
    ) {
        if (rules.isEmpty()) {
            ZillitText(
                text = str(S.desktop_ce_no_assignment_rules),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            fun change(next: CashAssignmentRule) = edit(rules.mapIndexed { i, old -> if (i == index) next else old })
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = rule.nominalCodes.joinToString(", "),
                    onValueChange = { typed ->
                        change(rule.copy(nominalCodes = typed.split(",").map(String::trim).filter(String::isNotBlank)))
                    },
                    label = str(S.dm_section_nominal),
                    placeholder = str(S.desktop_ce_any),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = rule.amountMin,
                    onValueChange = { change(rule.copy(amountMin = it)) },
                    label = str(S.desktop_ce_amount_from),
                    placeholder = str(S.desktop_ce_any),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.width(FIGURE_WIDTH),
                )
                Column {
                    ZillitText(text = str(S.desktop_assign_to), style = ZillitTheme.typography.label)
                    ZillitSelect(
                        value = rule.assignTo,
                        options = (team.map { it.userId } + listOfNotNull(rule.assignTo.ifBlank { null })).distinct(),
                        onSelect = { change(rule.copy(assignTo = it)) },
                        label = { people.nameOf(it) },
                        modifier = Modifier.width(ASSIGNEE_WIDTH),
                    )
                }
                ZillitSwitch(checked = rule.isActive, onCheckedChange = { change(rule.copy(isActive = it)) })
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_remove_rule),
                    onClick = { edit(rules.filterIndexed { i, _ -> i != index }) },
                    tint = ZillitTheme.colors.danger,
                )
            }
            if (rule.departments.isNotEmpty()) {
                ZillitText(
                    text = str(S.desktop_ce_rule_departments, rule.departments.size),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/** `40`, `40.5` — a figure as it would be typed. */
private fun plainNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private val LIMIT_WIDTH = 110.dp
private val BASIS_WIDTH = 200.dp
private val FIGURE_WIDTH = 160.dp
private val ASSIGNEE_WIDTH = 220.dp
