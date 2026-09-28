// The Settings page — the web's `SettingsPage.jsx`, card for card.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.zillit.desktop.feature.invoices.ui.SetupEvent
import androidx.compose.foundation.clickable
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.InvoiceAlert
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.ui.InvoiceSetupSection
import com.zillit.desktop.feature.invoices.ui.InvoiceSetupState
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.TeamMemberDraft
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Invoices Setup — who may post and up to what value, which alerts go out,
 * the chain a payment run climbs, and the rules that put an arriving invoice
 * on somebody's desk.
 *
 * Three cards carry their own Save, which appears only once something in them
 * changed and reads "Saved" for a moment after. The team table is the
 * exception: a member persists the moment they are added, edited or removed,
 * as on the web.
 */
@Composable
internal fun ColumnScope.SettingsPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val setup = state.setup
    // A failed read shows the cards empty, as the web's does — its catch only logs.
    when {
        setup.loading -> SettingsSkeleton()

        else -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            TeamCard(state, onEvent)
            AlertsCard(setup, onEvent)
            RunAuthorisationCard(state, onEvent)
            RulesCard(state, onEvent)
        }
    }
}

/** The web's skeleton panels while the settings load (`SettingsPage.jsx:630-709`). */
@Composable
private fun ColumnScope.SettingsSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        listOf(
            str(S.ah_settings_team_posting) to ZillitIcons.Users,
            str(S.desktop_alert_preferences) to ZillitIcons.Bell,
            str(S.desktop_run_authorization) to ZillitIcons.Shield,
        ).forEach { (title, icon) ->
            ZillitSectionCard(title = title, icon = icon) {
                repeat(SKELETON_ROWS) {
                    ZillitSkeletonBar(modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs))
                }
            }
        }
    }
}

// -- team and posting rights --------------------------------------------------

@Composable
private fun TeamCard(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val setup = state.setup
    ZillitSectionCard(
        title = str(S.ah_settings_team_posting),
        icon = ZillitIcons.Users,
        action = {
            if (InvoiceSetupSection.Team in setup.saving) {
                ZillitStatusPill(label = str(S.ah_saving), tone = StatusTone.Pending)
            }
            ZillitButton(
                text = str(S.cs_add_member),
                onClick = { onEvent(InvoicesEvent.AddTeamMember) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        CardIntro(str(S.desktop_inv_team_intro))
        if (setup.edited.teamMembers.isEmpty()) {
            Hint(str(S.desktop_inv_no_team_members))
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            FieldCaption(str(S.user_label), Modifier.weight(TEAM_NAME_WEIGHT))
            FieldCaption(str(S.desktop_posting_limit_title), Modifier.weight(1f))
            FieldCaption(str(S.desktop_authorise_runs), Modifier.weight(1f))
            FieldCaption(str(S.desktop_can_override), Modifier.weight(1f))
            FieldCaption(str(S.desktop_senior), Modifier.weight(TEAM_FLAG_WEIGHT))
            Spacer(Modifier.width(TEAM_ACTIONS_WIDTH))
        }
        setup.edited.teamMembers.forEach { row ->
            HairRule()
            TeamRow(state, row, onEvent)
        }
    }
}

@Composable
private fun TeamRow(state: InvoicesUiState, row: InvoiceTeamRow, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val member = state.assignees.firstOrNull { it.id == row.userId }
    val name = member?.name ?: state.userNames[row.userId] ?: row.userId
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        PersonCell(name, row.userId, member?.role.orEmpty(), Modifier.weight(TEAM_NAME_WEIGHT))
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = row.limitLabel(state.projectCurrency),
                style = ZillitTheme.typography.numeric,
                maxLines = 1,
            )
            if (!row.isUnlimited) {
                Hint(
                    if (row.isSubmitOnly) {
                        str(S.desktop_no_direct_posting)
                    } else {
                        str(S.desktop_card_up_to_amount, row.limitLabel(state.projectCurrency))
                    },
                )
            }
        }
        // The stored flags as they are — the web prints `run_access` / `override_access` raw.
        YesNo(row.runAccess, Modifier.weight(1f))
        YesNo(row.overrideAccess, Modifier.weight(1f))
        Box(modifier = Modifier.weight(TEAM_FLAG_WEIGHT)) {
            if (row.isSenior) {
                ZillitStatusPill(label = str(S.desktop_senior), tone = StatusTone.Done)
            } else {
                ZillitText(text = str(S.no), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Row(
            modifier = Modifier.width(TEAM_ACTIONS_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.edit),
                onClick = { onEvent(InvoicesEvent.EditTeamMember(row)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.bs_chip_remove, name),
                onClick = { onEvent(InvoicesEvent.RequestRemoveTeamMember(row.userId)) },
            )
        }
    }
}

/** An avatar, a name and the role under it — the team table's first cell. */
@Composable
private fun PersonCell(name: String, userId: String, role: String, modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, userId = userId, size = AVATAR)
        Column {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Hint(role.ifBlank { str(S.desktop_team_member) })
        }
    }
}

@Composable
private fun YesNo(value: Boolean, modifier: Modifier) {
    ZillitText(
        text = if (value) str(S.yes) else str(S.no),
        style = ZillitTheme.typography.bodySmall.copy(
            fontWeight = if (value) FontWeight.Bold else FontWeight.Normal,
        ),
        color = if (value) ZillitTheme.colors.success else ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

// -- alerts -------------------------------------------------------------------

@Composable
private fun AlertsCard(setup: InvoiceSetupState, onEvent: (InvoicesEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_alert_preferences),
        icon = ZillitIcons.Bell,
        action = { SectionSaveButton(setup, InvoiceSetupSection.Alerts, onEvent) },
    ) {
        InvoiceAlert.entries.forEachIndexed { index, alert ->
            if (index > 0) HairRule()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    RowTitle(alert.label)
                    Hint(alert.hint)
                }
                ZillitSwitch(
                    checked = setup.edited.isOn(alert),
                    onCheckedChange = { onEvent(InvoicesEvent.ToggleAlert(alert)) },
                )
            }
        }
    }
}

// -- run authorisation --------------------------------------------------------

@Composable
private fun RunAuthorisationCard(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val setup = state.setup
    val levels = setup.edited.runAuthorisation
    ZillitSectionCard(
        title = str(S.desktop_run_authorization),
        icon = ZillitIcons.Shield,
        action = { SectionSaveButton(setup, InvoiceSetupSection.RunAuthorisation, onEvent) },
    ) {
        CardIntro(str(S.desktop_inv_run_authorization_intro))
        if (levels.isEmpty()) {
            Hint(str(S.desktop_inv_no_auth_levels_line1))
            Hint(str(S.desktop_inv_no_auth_levels_line2))
        }
        InsertLevelRow(index = 0, onEvent = onEvent)
        levels.forEachIndexed { index, level ->
            LevelCard(state, level, onEvent)
            InsertLevelRow(index = index + 1, onEvent = onEvent)
        }
    }
}

/** The hairline with a + in the middle — the web inserts a level exactly here. */
@Composable
private fun InsertLevelRow(index: Int, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(ZillitTheme.colors.border))
        ZillitIconButton(
            icon = ZillitIcons.Add,
            contentDescription = str(S.desktop_insert_a_level_here),
            onClick = { onEvent(InvoicesEvent.AddRunAuthLevel(index)) },
            tint = ZillitTheme.colors.accentText,
        )
        Box(Modifier.weight(1f).height(1.dp).background(ZillitTheme.colors.border))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelCard(state: InvoicesUiState, level: RunAuthLevel, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier.size(TIER_BADGE).clip(ZillitTheme.shapes.pill).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = level.tier.toString(),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.accentText,
                )
            }
            RowTitle(str(S.desktop_level_n, level.tier))
            Spacer(Modifier.weight(1f))
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_remove_level_n, level.tier),
                onClick = { onEvent(InvoicesEvent.RemoveRunAuthLevel(level.tier)) },
                tint = colors.danger,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitButton(
                text = str(S.add_members),
                onClick = { onEvent(InvoicesEvent.OpenRunAuthPicker(level.tier)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            level.userIds.forEach { userId ->
                ApproverChip(state, level.tier, userId, onEvent)
            }
            if (level.userIds.isEmpty()) Hint(str(S.desktop_no_users_assigned))
        }
    }
}

@Composable
private fun ApproverChip(
    state: InvoicesUiState,
    tier: Int,
    userId: String,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val person = (state.setup.people + state.assignees).firstOrNull { it.id == userId }
    val name = person?.name ?: state.userNames[userId] ?: userId
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.pill)
            .padding(start = ZillitTheme.spacing.xs, end = ZillitTheme.spacing.xxs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitAvatar(name = name, userId = userId, size = CHIP_AVATAR)
        ZillitText(text = name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
        person?.role?.takeIf { it.isNotBlank() }?.let { Hint(it) }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_remove_named_from_level_n, name, tier),
            onClick = { onEvent(InvoicesEvent.RemoveRunAuthUser(tier, userId)) },
            size = CHIP_AVATAR,
        )
    }
}

// -- auto-assignment rules ----------------------------------------------------

@Composable
private fun RulesCard(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val setup = state.setup
    ZillitSectionCard(
        title = str(S.desktop_auto_assignment_rules),
        icon = ZillitIcons.Ledger,
        action = {
            SectionSaveButton(setup, InvoiceSetupSection.Rules, onEvent)
            ZillitButton(
                text = str(S.desktop_add_rule),
                onClick = { onEvent(InvoicesEvent.AddAssignmentRule) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = InvoiceSetupSection.Rules !in setup.saving,
            )
        },
    ) {
        CardIntro(str(S.desktop_inv_rules_intro))
        when {
            setup.rulesLoading -> Hint(str(S.desktop_loading_rules))
            setup.rules.isEmpty() -> Hint(str(S.desktop_inv_no_rules_configured))
            else -> setup.rules.forEachIndexed { index, rule -> RuleCard(state, rule, index + 1, onEvent) }
        }
    }
}

/**
 * One rule — the web's `RuleRow`: who it assigns to across the top, then the
 * four conditions, any of which sends the invoice that way. An inactive rule
 * keeps its place, greyed.
 */
@Suppress("LongMethod") // Read top to bottom; the order is the reading order.
@Composable
private fun RuleCard(
    state: InvoicesUiState,
    rule: InvoiceAssignmentRule,
    number: Int,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val setup = state.setup
    val colors = ZillitTheme.colors
    val update = { next: InvoiceAssignmentRule -> onEvent(InvoicesEvent.EditAssignmentRule(next)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.sm)
            .height(IntrinsicSize.Min)
            .clip(ZillitTheme.shapes.large)
            .background(if (rule.isActive) colors.surface else colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .alpha(if (rule.isActive) 1f else INACTIVE_ALPHA),
    ) {
        Box(
            Modifier
                .width(RULE_BAR)
                .fillMaxHeight()
                .background(if (rule.isActive) colors.accent else colors.borderStrong),
        )
        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Row one: Rule N · toggle · delete chip; row two: Assign to, full width (`SettingsPage.jsx:171-194`).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                FieldCaption(str(S.desktop_pc_rule_n, number))
                Spacer(Modifier.weight(1f))
                ZillitSwitch(
                    checked = rule.isActive,
                    onCheckedChange = { update(rule.copy(isActive = it)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_remove_rule),
                    onClick = { onEvent(InvoicesEvent.RequestRemoveRule(rule.id)) },
                )
            }
            FieldCaption(str(S.desktop_assign_to))
            ZillitSelect<InvoiceAssignee?>(
                value = state.assignees.firstOrNull { it.id == rule.assignTo },
                options = state.assignees,
                onSelect = { person -> person?.let { update(rule.copy(assignTo = it.id)) } },
                label = { it?.label ?: str(S.desktop_pick_assignee) },
                modifier = Modifier.fillMaxWidth(),
            )
            FieldCaption(str(S.desktop_if_any_condition_matches))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                ConditionColumn(str(S.departments), Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.departments,
                        options = (state.departmentNames.keys.toList() + rule.departments).distinct(),
                        label = { id -> state.departmentName(id) },
                        onChange = { update(rule.copy(departments = it)) },
                        placeholder = str(S.desktop_pc_select_departments),
                    )
                }
                ConditionColumn(str(S.ah_vendors), Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.vendors,
                        options = (state.vendors.keys.toList() + rule.vendors).distinct(),
                        label = { id -> state.vendors[id]?.name ?: id },
                        onChange = { update(rule.copy(vendors = it)) },
                        placeholder = str(S.desktop_inv_select_vendors_ellipsis),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                ConditionColumn(str(S.dm_section_nominal), Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.nominalCodes,
                        options = (setup.nominals.map { it.code } + rule.nominalCodes).distinct(),
                        label = { code -> setup.nominals.firstOrNull { it.code == code }?.label ?: code },
                        onChange = { update(rule.copy(nominalCodes = it)) },
                        placeholder = str(S.desktop_pc_select_nominals),
                    )
                }
                ConditionColumn(str(S.desktop_amount_min), Modifier.weight(1f)) {
                    // The web's `CalcInput min={0}`: arithmetic resolves as the field is left, and
                    // nothing below nought is kept.
                    ZillitCalcField(
                        value = rule.amountMin,
                        onCommit = { typed ->
                            val clamped = typed.toDoubleOrNull()?.takeIf { it < 0 }?.let { "0" } ?: typed
                            update(rule.copy(amountMin = clamped))
                        },
                        placeholder = str(S.desktop_pc_amount_placeholder, Money.symbol(state.projectCurrency)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// -- the small parts ----------------------------------------------------------

/**
 * A card's Save — the web's `SectionSaveButton`: absent until something
 * changed, "Saving…" while it goes, "Saved" for a moment after.
 */
@Composable
private fun SectionSaveButton(
    setup: InvoiceSetupState,
    section: InvoiceSetupSection,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val dirty = setup.isDirty(section)
    val saving = section in setup.saving
    val saved = section in setup.justSaved
    if (!dirty && !saved) return
    ZillitButton(
        text = when {
            saving -> str(S.ah_saving)
            saved -> str(S.saved)
            else -> str(S.save)
        },
        onClick = { onEvent(InvoicesEvent.SaveSetupSection(section)) },
        variant = if (saved) ButtonVariant.Secondary else ButtonVariant.Primary,
        size = ButtonSize.Small,
        leadingIcon = if (saved) ZillitIcons.Check else null,
        loading = saving,
        enabled = dirty && !saving && !saved,
    )
}

@Composable
private fun ConditionColumn(caption: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldCaption(caption)
        content()
    }
}

@Composable
private fun CardIntro(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        modifier = Modifier.padding(bottom = ZillitTheme.spacing.xs),
    )
}

@Composable
private fun RowTitle(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
}

@Composable
private fun Hint(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

@Composable
private fun FieldCaption(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun HairRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

// -- the sheets over the page -------------------------------------------------

/** Add or edit one team member — the web's `TeamMemberModal`. */
@Composable
internal fun TeamMemberSheet(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val draft = state.setup.memberDraft ?: return
    val row = draft.row
    val taken = state.setup.edited.teamMembers.map { it.userId }.toSet()
    val offered = state.assignees.filter { draft.isNew && it.id !in taken || it.id == row.userId }
    fun update(next: TeamMemberDraft) =
        onEvent(InvoicesEvent.ChangeTeamMemberDraft(next))
    SetupSheet(
        title = if (draft.isNew) str(S.desktop_add_team_member) else str(S.desktop_edit_team_member),
        onDismiss = { onEvent(InvoicesEvent.CancelTeamMember) },
        confirmText = if (draft.isNew) str(S.cs_add_member) else str(S.ah_save_changes),
        confirmEnabled = draft.isReady,
        busy = draft.busy,
        onConfirm = { onEvent(InvoicesEvent.CommitTeamMember) },
    ) {
        FieldCaption(str(S.desktop_team_member))
        if (draft.isNew) {
            ZillitSelect<InvoiceAssignee?>(
                value = offered.firstOrNull { it.id == row.userId },
                options = offered,
                onSelect = { person -> person?.let { update(draft.copy(row = row.copy(userId = it.id))) } },
                label = { it?.label ?: str(S.desktop_select_a_user) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            RowTitle(state.assignees.firstOrNull { it.id == row.userId }?.name ?: row.userId)
        }
        PostingLimitField(draft, ::update)
        MemberRights(draft, ::update)
    }
}

/** The ceiling and its Unlimited tick; a senior has neither to set. */
@Composable
private fun PostingLimitField(draft: TeamMemberDraft, update: (TeamMemberDraft) -> Unit) {
    val row = draft.row
    val off = draft.unlimited || row.isSenior
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        FieldCaption(str(S.desktop_inv_posting_limit_all_curr), Modifier.weight(1f))
        ZillitCheckbox(
            checked = off,
            // Unticking starts the limit at nought, as the web's does.
            onCheckedChange = { update(draft.copy(unlimited = it, limitText = if (it) "" else "0")) },
            enabled = !row.isSenior,
            label = str(S.drive_link_views_unlimited),
        )
    }
    ZillitCalcField(
        value = if (off) "" else draft.limitText,
        onCommit = { typed ->
            val clamped = typed.toDoubleOrNull()?.takeIf { it < 0 }?.let { "0" } ?: typed
            update(draft.copy(limitText = clamped))
        },
        placeholder = if (off) str(S.drive_link_views_unlimited) else "0",
        enabled = !off,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The three rights, two of which a senior holds whether they are ticked or not. */
@Composable
private fun MemberRights(draft: TeamMemberDraft, update: (TeamMemberDraft) -> Unit) {
    val row = draft.row
    SheetToggle(
        title = str(S.desktop_can_authorise_payment_runs),
        hint = str(S.desktop_inv_run_access_desc),
        checked = row.isSenior || row.runAccess,
        enabled = !row.isSenior,
        onChange = { update(draft.copy(row = row.copy(runAccess = it))) },
    )
    SheetToggle(
        title = str(S.desktop_can_override_approvals),
        hint = str(S.desktop_inv_override_access_desc),
        checked = row.isSenior || row.overrideAccess,
        enabled = !row.isSenior,
        onChange = { update(draft.copy(row = row.copy(overrideAccess = it))) },
    )
    SheetToggle(
        title = str(S.desktop_is_senior),
        hint = str(S.desktop_inv_senior_desc),
        checked = row.isSenior,
        enabled = true,
        onChange = { senior ->
            update(draft.copy(row = row.asSenior(senior), unlimited = if (senior) true else draft.unlimited))
        },
    )
}

@Composable
private fun SheetToggle(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title)
            Hint(hint)
        }
        ZillitSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * The user picker one sign-off level opens — the web's `RunAuthUserPicker`
 * (`SettingsPage.jsx:242-314`): search, tick several (each ticked person a
 * removable chip at the top), anyone already on a level shown "Added", and
 * "Add N users" to put them all on the level at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunAuthPickerSheet(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val tier = state.setup.pickingForTier ?: return
    val already = state.setup.edited.allApprovers.toSet()
    val staged = state.setup.pickerStaged
    val needle = state.setup.pickerSearch.trim().lowercase()
    val everyone = state.setup.people.ifEmpty { state.assignees }
    val people = everyone.filter {
        needle.isEmpty() || it.name.lowercase().contains(needle) || it.role.lowercase().contains(needle)
    }
    ZillitDialogShell(
        title = str(S.desktop_add_users_level_n, tier),
        visible = true,
        scrollable = true,
        onDismiss = { onEvent(InvoicesEvent.CloseRunAuthPicker) },
        actions = if (staged.isEmpty()) {
            null
        } else {
            {
                ZillitButton(
                    text = str(if (staged.size == 1) S.desktop_inv_add_n_users_one else S.desktop_inv_add_n_users_other, staged.size),
                    onClick = { onEvent(SetupEvent.AddStagedRunAuthUsers) },
                )
            }
        },
    ) {
        ZillitTextField(
            value = state.setup.pickerSearch,
            onValueChange = { onEvent(InvoicesEvent.SearchRunAuthPicker(it)) },
            placeholder = str(S.invitees_search_users),
            leadingIcon = ZillitIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        if (staged.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                staged.forEach { id ->
                    val name = everyone.firstOrNull { it.id == id }?.name ?: id
                    Row(
                        modifier = Modifier
                            .clip(ZillitTheme.shapes.pill)
                            .background(ZillitTheme.colors.accentSoft)
                            .padding(start = ZillitTheme.spacing.sm, end = ZillitTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = name.substringBefore(' '),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.accentText,
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = str(S.bs_chip_remove, name),
                            onClick = { onEvent(InvoicesEvent.PickRunAuthUser(id)) },
                            size = CHIP_AVATAR,
                        )
                    }
                }
            }
        }
        if (people.isEmpty()) Hint(str(S.desktop_inv_no_users_found))
        people.forEach { person ->
            val picked = person.id in staged
            val onChain = person.id in already
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (picked) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
                    .alpha(if (onChain) INACTIVE_ALPHA else 1f)
                    .clickable(enabled = !onChain) { onEvent(InvoicesEvent.PickRunAuthUser(person.id)) }
                    .padding(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitAvatar(name = person.name, userId = person.id, size = AVATAR)
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = person.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                    Hint(person.role)
                }
                when {
                    picked -> ZillitIcon(icon = AhIcons.CheckCircle, tint = ZillitTheme.colors.accentText)
                    onChain -> ZillitStatusPill(label = str(S.history_added), tone = StatusTone.Done)
                }
            }
        }
    }
}

/** The two confirms this page raises: dropping a member, and deleting a stored rule. */
@Composable
internal fun SetupConfirmSheets(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    state.setup.removingMember?.let { userId ->
        val name = state.assignees.firstOrNull { it.id == userId }?.name ?: userId
        SetupSheet(
            title = str(S.desktop_remove_team_member),
            onDismiss = { onEvent(InvoicesEvent.CancelRemoveTeamMember) },
            confirmText = str(S.remove),
            confirmEnabled = true,
            busy = InvoiceSetupSection.Team in state.setup.saving,
            danger = true,
            onConfirm = { onEvent(InvoicesEvent.ConfirmRemoveTeamMember) },
        ) {
            ZillitText(
                text = str(S.desktop_inv_remove_from_team_confirm, name),
                style = ZillitTheme.typography.bodyMedium,
            )
        }
    }
    state.setup.removingRule?.let { rule ->
        val assignee = state.assignees.firstOrNull { it.id == rule.assignTo }?.label ?: str(S.desktop_unknown)
        SetupSheet(
            title = str(S.desktop_delete_assignment_rule),
            onDismiss = { onEvent(InvoicesEvent.CancelRemoveRule) },
            confirmText = str(S.delete),
            confirmEnabled = true,
            busy = false,
            danger = true,
            onConfirm = { onEvent(InvoicesEvent.ConfirmRemoveRule) },
        ) {
            ZillitText(
                text = str(S.desktop_inv_remove_rule_confirm, rule.summary(state.projectCurrency), assignee),
                style = ZillitTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The page's sheets all look the same: a title, a body, Cancel and one
 * decision. A thin wrapper over the shell so each sheet below reads as its
 * content rather than its chrome.
 */
@Composable
private fun SetupSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    danger: Boolean = false,
    /**
     * Off by default, which is what makes a short sheet hug its content: a
     * scrolling body carries the rail, and the rail fills the height it is
     * given, so a scrollable dialog stretches to its maximum with its buttons
     * stranded under a half-empty card. Only the user picker, whose list can
     * run long, turns it on.
     */
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitDialogShell(
        title = title,
        visible = true,
        scrollable = scrollable,
        onDismiss = { if (!busy) onDismiss() },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
                enabled = !busy,
            )
            ZillitButton(
                text = confirmText,
                onClick = onConfirm,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                enabled = confirmEnabled && !busy,
                loading = busy,
            )
        },
        content = content,
    )
}

private val AVATAR = 28.dp
private val CHIP_AVATAR = 18.dp
private val TIER_BADGE = 24.dp
private val TEAM_ACTIONS_WIDTH = 110.dp
private const val SKELETON_ROWS = 3
private val RULE_BAR = 4.dp
private const val TEAM_NAME_WEIGHT = 2.2f
private const val TEAM_FLAG_WEIGHT = 0.8f
private const val INACTIVE_ALPHA = 0.7f
