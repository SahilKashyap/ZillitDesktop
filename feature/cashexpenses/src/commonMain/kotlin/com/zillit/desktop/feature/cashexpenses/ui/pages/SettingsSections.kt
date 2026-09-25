package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashNominalCatalogue
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CoordinatorRules
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.SettingsEvent
import com.zillit.desktop.feature.cashexpenses.ui.TeamMemberDraft

// -- Team & Posting Rights ------------------------------------------------------------

/**
 * Team & Posting Rights — who is on the cash team, their posting limit, and
 * whether they may override the approval chain (`PCSettingsPage.jsx:643-706`).
 *
 * Rows, not a data table: a table inside this scrolling page measures to
 * nothing. Each change saves at once, as the web's `persistTeam` does.
 */
@Composable
internal fun TeamCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val members = state.settings?.teamMembers.orEmpty()
    ZillitSectionCard(
        title = str(S.ah_settings_team_posting),
        action = {
            SectionAdd(str(S.cs_add_member)) { onEvent(CashEvent.EditTeamMember(TeamMemberDraft())) }
        },
    ) {
        ZillitText(
            text = str(S.desktop_pc_team_desc),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        if (members.isEmpty()) {
            QuietLine(str(S.desktop_inv_no_team_members), Modifier.padding(vertical = ZillitTheme.spacing.md))
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldCaption(str(S.user_label), Modifier.weight(1f))
            FieldCaption(str(S.desktop_posting_limit_title), Modifier.width(LIMIT_WIDTH))
            FieldCaption(str(S.desktop_can_override), Modifier.width(FLAG_WIDTH))
            FieldCaption(str(S.desktop_senior), Modifier.width(SENIOR_WIDTH))
            Spacer(Modifier.width(ACTIONS_WIDTH))
        }
        members.forEachIndexed { index, member ->
            ZillitDivider()
            TeamRow(state, member, index, onEvent)
        }
    }
}

@Suppress("LongMethod") // One table row: five cells, read left to right.
@Composable
private fun TeamRow(state: CashUiState, member: CashTeamMember, index: Int, onEvent: (CashEvent) -> Unit) {
    val limit = member.postingLimit
    val symbolLimit = limit?.let { state.formatAggregate(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CashPerson(
            userId = member.userId,
            recordedName = member.name,
            secondary = state.assignees.designationOf(member.userId),
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.width(LIMIT_WIDTH)) {
            ZillitText(
                text = when {
                    limit == null -> str(S.drive_link_views_unlimited)
                    limit == 0.0 -> str(S.dd_publish_no_access_badge)
                    else -> symbolLimit.orEmpty()
                },
                style = ZillitTheme.typography.numeric,
            )
            if (limit != null) {
                QuietLine(
                    if (limit == 0.0) {
                        str(S.desktop_pc_submit_to_senior)
                    } else {
                        str(S.desktop_pc_above_submit_to_senior, symbolLimit.orEmpty())
                    },
                )
            }
        }
        Box(Modifier.width(FLAG_WIDTH)) {
            ZillitText(
                text = if (member.canOverride) str(S.yes) else str(S.no),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (member.canOverride) ZillitTheme.colors.success else ZillitTheme.colors.textMuted,
            )
        }
        Box(Modifier.width(SENIOR_WIDTH)) {
            if (member.isSenior) {
                ZillitTag(label = str(S.desktop_senior), tone = TagTone.Success)
            } else {
                QuietLine(str(S.no))
            }
        }
        Row(
            modifier = Modifier.width(ACTIONS_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.edit),
                onClick = {
                    onEvent(
                        CashEvent.EditTeamMember(
                            TeamMemberDraft(
                                userId = member.userId,
                                postingLimit = member.postingLimit?.let(::plainFigure),
                                canOverride = member.canOverride,
                                isSenior = member.isSenior,
                                index = index,
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.desktop_pc_remove_member),
                onClick = { onEvent(CashEvent.RemoveTeamMember(index)) },
                tint = ZillitTheme.colors.danger,
                enabled = !state.busy,
            )
        }
    }
}

/** "Designer · Camera" — what the crew list says a person does, or null. */
private fun List<AssigneeOption>.designationOf(userId: String): String? =
    firstOrNull { it.userId == userId }?.designation?.takeIf { it.isNotBlank() }

/**
 * Add or edit one team member — the web's Team Member modal
 * (`PCSettingsPage.jsx:708-771`). A senior is unlimited and may override, so
 * both controls lock while Is Senior is on.
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
        width = TEAM_DIALOG_WIDTH,
    ) {
        if (draft == null) return@ZillitDialogShell
        fun edit(next: TeamMemberDraft) = onEvent(CashEvent.EditTeamMember(next))
        // Adding offers only those not on the team yet; editing offers the whole team.
        val team = BatchAssignment.accountsTeam(state.assignees).filter { person ->
            draft.index != null || members.none { it.userId == person.userId }
        }
        FieldCaption(str(S.desktop_pc_user_required))
        ZillitSelect(
            value = draft.userId,
            options = (listOf("") + team.map { it.userId } + listOfNotNull(draft.userId.ifBlank { null })).distinct(),
            onSelect = { edit(draft.copy(userId = it)) },
            label = { id ->
                if (id.isBlank()) {
                    str(S.desktop_pc_select_user)
                } else {
                    listOfNotNull(people.nameOf(id), state.assignees.designationOf(id)).joinToString(" — ")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldCaption(str(S.desktop_pc_posting_limit_in, state.defaultSymbol()), Modifier.weight(1f))
            ZillitCheckbox(
                checked = draft.postingLimit == null,
                onCheckedChange = { edit(draft.copy(postingLimit = if (it) null else "0")) },
                label = str(S.drive_link_views_unlimited),
                enabled = !draft.isSenior,
            )
        }
        ZillitTextField(
            value = draft.postingLimit.orEmpty(),
            onValueChange = { edit(draft.copy(postingLimit = it)) },
            placeholder = str(S.desktop_pc_enter_limit),
            keyboardType = KeyboardType.Decimal,
            enabled = draft.postingLimit != null && !draft.isSenior,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingToggleRow(
            label = str(S.desktop_can_override),
            detail = str(S.desktop_pc_can_override_desc),
            checked = draft.canOverride || draft.isSenior,
            onChange = { if (!draft.isSenior) edit(draft.copy(canOverride = it)) },
            enabled = !draft.isSenior,
        )
        SettingToggleRow(
            label = str(S.desktop_pc_is_senior),
            detail = str(S.desktop_pc_is_senior_desc),
            checked = draft.isSenior,
            onChange = { senior ->
                edit(
                    if (senior) {
                        draft.copy(isSenior = true, postingLimit = null, canOverride = true)
                    } else {
                        draft.copy(isSenior = false)
                    },
                )
            },
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

// -- Department Coordinator Designations -------------------------------------------

/**
 * Who codes each department's receipts, and whether they see its floats —
 * `department_coordinators` (`PCSettingsPage.jsx:773-849`). The cells open the
 * department and people pickers ([CoordinatorPickerDialog]).
 */
@Composable
internal fun CoordinatorsCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    val rows = draft.departmentCoordinators
    ZillitSectionCard(
        title = str(S.ah_settings_dept_coordinator),
        action = {
            SectionSaveFor(CashSettingsSection.Coordinators, state, draft, onEvent)
            SectionAdd(str(S.desktop_pc_add_coordinator)) { onEvent(SettingsEvent.AddCoordinator) }
        },
    ) {
        if (rows.isEmpty()) {
            QuietLine(str(S.desktop_pc_coordinators_empty), Modifier.padding(vertical = ZillitTheme.spacing.md))
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldCaption(str(S.department), Modifier.weight(DEPT_WEIGHT))
            FieldCaption(str(S.user_label), Modifier.weight(USER_WEIGHT))
            FieldCaption(str(S.desktop_pc_col_coding_required), Modifier.width(TOGGLE_WIDTH))
            FieldCaption(str(S.desktop_pc_col_view_floats), Modifier.width(TOGGLE_WIDTH))
            Spacer(Modifier.width(REMOVE_WIDTH))
        }
        rows.forEachIndexed { index, row ->
            ZillitDivider()
            CoordinatorRow(state, index, row, onEvent)
        }
    }
}

@Suppress("LongMethod") // One table row: five cells, read left to right.
@Composable
private fun CoordinatorRow(state: CashUiState, index: Int, row: DepartmentCoordinator, onEvent: (CashEvent) -> Unit) {
    val errors = state.settingsUi.coordErrors
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(DEPT_WEIGHT)
                .clickable { onEvent(SettingsEvent.OpenCoordinatorPicker(index, users = false)) },
        ) {
            val name = state.departmentName(row.departmentId)
            ZillitText(
                text = name ?: str(S.desktop_pc_select_department_dots),
                style = ZillitTheme.typography.bodyMedium,
                color = if (name == null) colors.textMuted else colors.textPrimary,
            )
            if (CoordinatorRules.key(index, CoordinatorRules.DEPARTMENT) in errors) {
                ZillitText(
                    text = str(S.desktop_select_a_department),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.danger,
                )
            }
        }
        val hasDepartment = row.departmentId.isNotBlank()
        Column(
            modifier = Modifier
                .weight(USER_WEIGHT)
                .clickable(enabled = hasDepartment) {
                    onEvent(SettingsEvent.OpenCoordinatorPicker(index, users = true))
                },
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (row.userIds.isEmpty()) {
                ZillitText(
                    text = str(
                        if (hasDepartment) S.desktop_pc_select_users_dots else S.desktop_pc_select_department_first,
                    ),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }
            row.userIds.forEach { id ->
                CashPerson(userId = id, secondary = state.assignees.designationOf(id))
            }
            if (CoordinatorRules.key(index, CoordinatorRules.USERS) in errors) {
                ZillitText(
                    text = str(S.desktop_bs_select_at_least_one_user),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.danger,
                )
            }
        }
        Box(Modifier.width(TOGGLE_WIDTH)) {
            ZillitSwitch(
                checked = row.codingRequired,
                onCheckedChange = { onEvent(SettingsEvent.UpdateCoordinator(index, row.copy(codingRequired = it))) },
            )
        }
        Box(Modifier.width(TOGGLE_WIDTH)) {
            ZillitSwitch(
                checked = row.viewDepartmentFloats,
                onCheckedChange = {
                    onEvent(SettingsEvent.UpdateCoordinator(index, row.copy(viewDepartmentFloats = it)))
                },
            )
        }
        Box(Modifier.width(REMOVE_WIDTH)) {
            ZillitButton(
                text = str(S.remove),
                onClick = { onEvent(SettingsEvent.RemoveCoordinator(index)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

// -- Request Cap ------------------------------------------------------------------------

/**
 * Request Cap — the ceiling on a float request (`RequestCapSection.jsx`),
 * saved on its own. The figures stay visible, disabled, while the cap is off,
 * so the stored policy can be read without switching it on.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The switch, the basis, the two rows and the note.
@Composable
internal fun RequestCapCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val stored = state.settings?.requestCap ?: RequestCap()
    val cap = state.capDraft ?: stored
    val dirty = state.capDraft != null && cap != stored
    val symbol = state.defaultSymbol()
    fun edit(next: RequestCap) = onEvent(CashEvent.EditRequestCap(next))
    val multiplierMissing = cap.enabled && cap.isWeekly && cap.salaryMultiplier <= 0
    val amountMissing = cap.enabled && cap.maxAmount <= 0
    ZillitSectionCard(
        title = str(S.desktop_pc_request_cap),
        action = {
            SectionSave(dirty = dirty, saving = false, enabled = !cap.blocked && !state.busy) {
                onEvent(CashEvent.SaveRequestCap)
            }
        },
    ) {
        SettingToggleRow(
            label = str(S.desktop_pc_cap_toggle_label),
            detail = str(S.desktop_pc_cap_toggle_desc),
            checked = cap.enabled,
            onChange = { edit(cap.copy(enabled = it)) },
        )
        ZillitDivider()
        FieldCaption(str(S.dm_rule_basis), Modifier.padding(top = ZillitTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            listOf(
                RequestCap.MAX_AMOUNT to str(S.desktop_card_cap_max_amount),
                RequestCap.WEEKLY_SALARY to str(S.desktop_card_cap_weekly_salary),
            ).forEach { (basis, label) ->
                ZillitChoiceChip(
                    label = label,
                    selected = cap.basis == basis,
                    onClick = { if (cap.enabled) edit(cap.copy(basis = basis)) },
                )
            }
        }
        ZillitDivider()
        if (cap.isWeekly) {
            CapRow(caption = str(S.desktop_pc_with_a_deal), words = str(S.desktop_pc_weekly_rate_times)) {
                ZillitTextField(
                    value = plainFigure(cap.salaryMultiplier),
                    onValueChange = { edit(cap.copy(salaryMultiplier = it.nonNegative())) },
                    placeholder = "1",
                    keyboardType = KeyboardType.Decimal,
                    enabled = cap.enabled,
                    modifier = Modifier.width(MULTIPLIER_WIDTH),
                )
            }
        }
        CapRow(
            caption = if (cap.isWeekly) str(S.desktop_pc_with_no_deal) else null,
            words = if (cap.isWeekly) str(S.desktop_pc_maximum) else str(S.desktop_ce_max_amount),
        ) {
            ZillitText(text = symbol, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
            ZillitTextField(
                value = plainFigure(cap.maxAmount),
                onValueChange = { edit(cap.copy(maxAmount = it.nonNegative())) },
                keyboardType = KeyboardType.Decimal,
                enabled = cap.enabled,
                modifier = Modifier.width(AMOUNT_WIDTH),
            )
            QuietLine(str(S.desktop_pc_project_default_currency))
        }
        if (multiplierMissing || amountMissing) {
            ZillitText(
                text = str(if (multiplierMissing) S.desktop_pc_cap_needs_multiplier else S.desktop_ce_cap_needs_amount),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
        if (cap.isWeekly) {
            ZillitNotice(text = str(S.desktop_pc_cap_weekly_note), tone = StatusTone.Progress, icon = ZillitIcons.Info)
        }
    }
}

/** A typed figure, never below zero; unreadable text is zero, as the web's `num` reads it. */
private fun String.nonNegative(): Double = toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

@Composable
private fun CapRow(caption: String?, words: String, field: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caption != null) FieldCaption(caption, Modifier.width(CAP_CAPTION_WIDTH))
        ZillitText(text = words, style = ZillitTheme.typography.bodyMedium)
        field()
    }
}

// -- Auto-Assignment Rules -------------------------------------------------------------

/**
 * Auto-Assignment — who a batch lands with when it matches
 * (`PCSettingsPage.jsx:1284-1380`), written through the account hub's rules
 * route under module `cash_expenses`. A saved rule is deleted the moment it
 * is removed; the rest save with the section's Save.
 */
@Suppress("LongMethod") // One rule: its header, assignee, and the three conditions.
@Composable
internal fun AssignmentRulesCard(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val stored = state.settings?.assignmentRules.orEmpty()
    val rules = state.rulesDraft ?: stored
    val team = BatchAssignment.accountsTeam(state.assignees)
    val people = LocalCashPeople.current
    val symbol = state.defaultSymbol()
    fun edit(next: List<CashAssignmentRule>) = onEvent(CashEvent.EditAssignmentRules(next))
    ZillitSectionCard(
        title = str(S.desktop_auto_assignment_rules),
        action = {
            SectionSave(dirty = state.rulesDraft != null && rules != stored, saving = false, enabled = !state.busy) {
                onEvent(CashEvent.SaveAssignmentRules)
            }
            SectionAdd(str(S.desktop_pc_add_rule)) { onEvent(SettingsEvent.AddAssignmentRule) }
        },
    ) {
        if (rules.isEmpty()) {
            QuietLine(str(S.desktop_pc_assign_empty), Modifier.padding(vertical = ZillitTheme.spacing.md))
            return@ZillitSectionCard
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            fun change(next: CashAssignmentRule) = edit(rules.mapIndexed { i, old -> if (i == index) next else old })
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldCaption(str(S.desktop_pc_rule_n, index + 1), Modifier.weight(1f))
                    ZillitSwitch(checked = rule.isActive, onCheckedChange = { change(rule.copy(isActive = it)) })
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.desktop_remove_rule),
                        onClick = { onEvent(SettingsEvent.RemoveAssignmentRule(index)) },
                        tint = ZillitTheme.colors.danger,
                    )
                }
                FieldCaption(str(S.desktop_assign_to))
                ZillitSelect(
                    value = rule.assignTo,
                    options = (listOf("") + team.map { it.userId } + listOfNotNull(rule.assignTo.ifBlank { null }))
                        .distinct(),
                    onSelect = { change(rule.copy(assignTo = it)) },
                    label = { id ->
                        if (id.isBlank()) {
                            str(S.desktop_pc_select_user)
                        } else {
                            listOfNotNull(people.nameOf(id), state.assignees.designationOf(id)).joinToString(" · ")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldCaption(str(S.desktop_pc_any_condition))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        FieldCaption(str(S.departments))
                        ZillitMultiSelect(
                            selected = rule.departments,
                            options = (state.departments.map { it.id } + rule.departments).distinct(),
                            label = { id -> state.departmentName(id) ?: id },
                            onChange = { change(rule.copy(departments = it)) },
                            placeholder = str(S.desktop_pc_select_departments),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        FieldCaption(str(S.desktop_pc_cost_code_nominal))
                        ZillitMultiSelect(
                            selected = rule.nominalCodes,
                            options = (CashNominalCatalogue.codes.keys.toList() + rule.nominalCodes).distinct(),
                            label = CashNominalCatalogue::label,
                            onChange = { change(rule.copy(nominalCodes = it)) },
                            placeholder = str(S.desktop_pc_select_nominals),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        // The web labels `amount_min` "Amount Max"; the wire key stays.
                        FieldCaption(str(S.desktop_pc_amount_max_in, symbol))
                        ZillitTextField(
                            value = rule.amountMin,
                            onValueChange = { change(rule.copy(amountMin = it)) },
                            placeholder = str(S.desktop_pc_amount_placeholder, symbol),
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private val LIMIT_WIDTH = 190.dp
private val FLAG_WIDTH = 100.dp
private val SENIOR_WIDTH = 80.dp
private val ACTIONS_WIDTH = 110.dp
private val TEAM_DIALOG_WIDTH = 420.dp
private const val DEPT_WEIGHT = 1.2f
private const val USER_WEIGHT = 1.4f
private val TOGGLE_WIDTH = 110.dp
private val REMOVE_WIDTH = 90.dp
private val MULTIPLIER_WIDTH = 90.dp
private val AMOUNT_WIDTH = 150.dp
private val CAP_CAPTION_WIDTH = 130.dp
