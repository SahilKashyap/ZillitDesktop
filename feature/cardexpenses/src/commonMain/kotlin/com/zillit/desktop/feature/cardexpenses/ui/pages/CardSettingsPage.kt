package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CARD_RULE_NOMINALS
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardCompany
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapProblem
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.ownerOf
import com.zillit.desktop.feature.cardexpenses.domain.problem
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import com.zillit.desktop.feature.cardexpenses.ui.TeamMemberEditor
import com.zillit.desktop.feature.cardexpenses.ui.components.CardCalcInput
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightEyebrow
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightTag
import com.zillit.desktop.feature.cardexpenses.ui.plainAmount

/**
 * The production's card configuration — the web's `SettingsPage.jsx:505-1301`.
 * Senior accountants only.
 *
 * Web order: Card Accounts & Custodian, Team & Posting Rights, Department
 * Coordinator Designations, Approval & Override, Request Cap, then the
 * Auto-Assignment Rules. Each section saves its own key when it has changes
 * — the Save appears only then, and there is no Discard. The team saves on
 * each add, edit and delete from its modal, as the web's does.
 *
 * Deduction & Processing Rules and Quick Codes are not here: they save
 * `deduction_rules` and `quick_codes`, keys the card settings document does
 * not hold, so the server drops both.
 */
@Composable
fun CardSettingsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage {
            ZillitNotice(text = str(S.desktop_card_loading_settings), tone = StatusTone.Progress)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        ScrollingPage {
            ProvidersSection(state, draft, onEvent)
            TeamSection(state, onEvent)
            CoordinatorSection(state, draft, onEvent)
            OverridesSection(state, draft, onEvent)
            RequestCapSection(state, draft, onEvent)
            AssignmentRulesSection(state, draft, onEvent)
        }
        TeamMemberDialog(state, onEvent)
        CoordinatorPickerDialog(state, onEvent)
    }
}

// -- Card Accounts & Custodian ----------------------------------------------------

/** The card providers editor (`CardProvidersEditor.jsx:166-251`). */
@Composable
private fun ProvidersSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val providers = draft.providers
    val dirty = providers != state.settings?.providers
    val edit: (List<CardProvider>) -> Unit = { onEvent(CardEvent.EditSettings(draft.copy(providers = it))) }

    SettingsCard(
        title = str(S.ah_settings_card_accounts),
        icon = ZillitIcons.CreditCard,
        action = { SaveButton(dirty, state) { onEvent(CardEvent.SaveSettings(SettingsSection.Providers)) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_ce_insights_card_providers),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_ce_insights_add_card_provider),
                onClick = { edit(providers + CardProvider(id = newProviderId(providers), name = "")) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        Muted(str(S.desktop_ce_insights_providers_description))
        if (providers.isEmpty()) {
            Muted(str(S.desktop_ce_insights_providers_empty))
            return@SettingsCard
        }
        providers.forEachIndexed { index, provider ->
            if (index > 0) ZillitDivider()
            ProviderRow(
                provider = provider,
                banks = state.banks,
                companies = state.insights.companies,
                onChange = { updated -> edit(providers.mapIndexed { at, row -> if (at == index) updated else row }) },
                onRemove = { edit(providers.filterIndexed { at, _ -> at != index }) },
            )
        }
    }
}

/**
 * One provider: name (required), bank (required), the company that owns the
 * bank — locked when one does — and the custodian and float codes. Picking a
 * bank fills its owning company (`setBank`, `CardProvidersEditor.jsx:137-142`).
 */
@Suppress("LongMethod") // Two rows of three fields, as the web lays them out.
@Composable
private fun ProviderRow(
    provider: CardProvider,
    banks: List<CardBank>,
    companies: List<CardCompany>,
    onChange: (CardProvider) -> Unit,
    onRemove: () -> Unit,
) {
    val owner = companies.ownerOf(provider.bankId)
    val required = str(S.docusign_prop_required)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ZillitTextField(
                value = provider.name,
                onValueChange = { onChange(provider.copy(name = it)) },
                label = "${str(S.desktop_ce_insights_provider_name)} *",
                placeholder = str(S.desktop_ce_insights_provider_placeholder),
                errorText = required.takeIf { provider.name.isBlank() },
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                InsightEyebrow("${str(S.desktop_bank)} *")
                ZillitSelect(
                    value = banks.firstOrNull { it.id == provider.bankId },
                    options = listOf<CardBank?>(null) + banks,
                    onSelect = { bank ->
                        val id = bank?.id.orEmpty()
                        onChange(provider.copy(bankId = id, companyId = companies.ownerOf(id)?.id.orEmpty()))
                    },
                    label = { bank -> bank?.let(::bankLabel) ?: str(S.desktop_ce_insights_search_bank) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (provider.bankId.isBlank()) ErrorLine(required)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                InsightEyebrow(str(S.company))
                if (owner != null) {
                    ZillitTextField(
                        value = owner.name,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ZillitText(
                        text = str(S.desktop_ce_insights_owns_bank),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                } else {
                    ZillitSelect(
                        value = companies.firstOrNull { it.id == provider.companyId },
                        options = listOf<CardCompany?>(null) + companies,
                        onSelect = { onChange(provider.copy(companyId = it?.id.orEmpty())) },
                        label = { company ->
                            company?.name ?: if (provider.bankId.isBlank()) {
                                str(S.desktop_ce_insights_pick_bank_first)
                            } else {
                                str(S.desktop_ce_insights_search_company)
                            }
                        },
                        enabled = provider.bankId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = provider.custodianAccount,
                onValueChange = { onChange(provider.copy(custodianAccount = it)) },
                label = str(S.desktop_ce_insights_custodian_account),
                placeholder = str(S.desktop_pc_enter_account_code),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = provider.floatMin,
                onValueChange = { onChange(provider.copy(floatMin = it)) },
                label = str(S.desktop_ce_insights_float_from),
                placeholder = str(S.desktop_pc_eg_1000),
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitTextField(
                    value = provider.floatMax,
                    onValueChange = { onChange(provider.copy(floatMax = it)) },
                    label = str(S.desktop_ce_insights_float_to),
                    placeholder = str(S.desktop_pc_eg_1999),
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = "",
                    onClick = onRemove,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
    }
}

private fun bankLabel(bank: CardBank): String = listOfNotNull(bank.name, bank.currency).joinToString(" · ")

// -- Team & Posting Rights ---------------------------------------------------------

/**
 * The team table (`SettingsPage.jsx:563-592`). Add and Edit open the modal;
 * the delete writes at once.
 */
@Suppress("LongMethod") // The table: header and one row per member.
@Composable
private fun TeamSection(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val members = state.settings?.teamMembers.orEmpty()
    val symbol = Money.symbol(state.currency).trim()
    SettingsCard(
        title = str(S.ah_settings_team_posting),
        icon = ZillitIcons.Users,
        description = str(S.desktop_pc_team_desc),
        action = {
            ZillitButton(
                text = str(S.cs_add_member),
                onClick = { onEvent(InsightsEvent.OpenMemberEditor(null)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (members.isEmpty()) {
            Muted(str(S.desktop_inv_no_team_members))
            return@SettingsCard
        }
        Row(Modifier.fillMaxWidth()) {
            Header(str(S.user_label), Modifier.weight(2f))
            Header(str(S.desktop_posting_limit_title), Modifier.weight(1.4f), end = true)
            Header(str(S.desktop_can_override), Modifier.weight(1f), center = true)
            Header(str(S.desktop_senior), Modifier.weight(1f), center = true)
            Spacer(Modifier.width(ACTIONS_WIDTH))
        }
        ZillitDivider()
        members.forEachIndexed { index, member ->
            if (index > 0) ZillitDivider()
            val person = state.people.firstOrNull { it.id == member.userId }
            val limit = member.postingLimit
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZillitAvatar(name = person?.name ?: "?", userId = member.userId, size = 28.dp)
                    Column {
                        ZillitText(
                            text = person?.name ?: "—",
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                        )
                        ZillitText(
                            text = person?.designation.orEmpty(),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.End) {
                    val figure = "$symbol${Money.group(limit ?: 0.0, 2)}"
                    ZillitText(
                        text = when {
                            limit == null -> str(S.drive_link_views_unlimited)
                            limit == 0.0 -> str(S.dd_publish_no_access_badge)
                            else -> figure
                        },
                        style = ZillitTheme.typography.numeric,
                    )
                    if (limit != null) {
                        ZillitText(
                            text = if (limit == 0.0) {
                                str(S.desktop_pc_submit_to_senior)
                            } else {
                                str(S.desktop_pc_above_submit_to_senior, figure)
                            },
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ZillitText(
                        text = if (member.canOverride) str(S.yes) else str(S.no),
                        style = ZillitTheme.typography.bodySmall,
                        color = if (member.canOverride) ZillitTheme.colors.success else ZillitTheme.colors.textMuted,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (member.isSenior) {
                        InsightTag(str(S.desktop_senior), ZillitTheme.colors.successSoft, ZillitTheme.colors.success)
                    } else {
                        ZillitText(
                            text = str(S.no),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
                Row(Modifier.width(ACTIONS_WIDTH), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    ZillitButton(
                        text = str(S.edit),
                        onClick = { onEvent(InsightsEvent.OpenMemberEditor(index)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = "",
                        onClick = { onEvent(InsightsEvent.RemoveMember(index)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Trash,
                        enabled = !state.insights.teamSaving,
                    )
                }
            }
        }
    }
}

/** "Add Team Member" / "Edit Team Member" (`SettingsPage.jsx:594-667`). */
@Suppress("LongMethod") // The user, the limit with its Unlimited switch, and the two toggles.
@Composable
private fun TeamMemberDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val editor = state.insights.memberEditor ?: return
    val member = editor.member
    val members = state.settings?.teamMembers.orEmpty()
    // Accounts team only; nobody already on the list when adding (`:648-651`).
    val people = state.people.filter { person ->
        person.isAccountsTeam && (!editor.adding || members.none { it.userId == person.id })
    }
    val saving = state.insights.teamSaving
    val unlimited = member.postingLimit == null
    val edit: (TeamMemberEditor) -> Unit = {
        onEvent(InsightsEvent.EditMember(it))
    }
    ZillitDialogShell(
        title = if (editor.adding) {
            str(S.desktop_ce_insights_add_team_member)
        } else {
            str(S.desktop_ce_insights_edit_team_member)
        },
        icon = ZillitIcons.UserPlus,
        visible = true,
        width = MEMBER_WIDTH,
        onDismiss = { onEvent(InsightsEvent.CloseMemberEditor) },
        actions = {
            ZillitButton(
                text = when {
                    saving -> str(S.ah_saving)
                    editor.adding -> str(S.add)
                    else -> str(S.update)
                },
                onClick = { onEvent(InsightsEvent.SaveMember) },
                enabled = member.userId.isNotBlank() && !saving,
                loading = saving,
            )
        },
    ) {
        InsightEyebrow("${str(S.user_label)} *")
        ZillitSelect(
            value = people.firstOrNull { it.id == member.userId },
            options = listOf<CardPerson?>(null) + people,
            onSelect = { edit(editor.copy(member = member.copy(userId = it?.id.orEmpty()))) },
            label = { person ->
                person?.let { "${it.name} — ${it.designation}" } ?: str(S.desktop_pc_select_user)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            InsightEyebrow(
                str(S.desktop_pc_posting_limit_in, Money.symbol(state.currency).trim()),
                modifier = Modifier.weight(1f),
            )
            ZillitCheckbox(
                checked = unlimited,
                onCheckedChange = { on ->
                    edit(editor.copy(member = member.copy(postingLimit = if (on) null else 0.0), limitText = ""))
                },
                label = str(S.drive_link_views_unlimited),
                enabled = !member.isSenior,
            )
        }
        // A cleared limit is Unlimited, not a 0 block (`emptyValue={null}`, `:622-629`).
        CardCalcInput(
            value = editor.limitText,
            onValueChange = { text ->
                edit(editor.copy(member = member.copy(postingLimit = text.toDoubleOrNull()), limitText = text))
            },
            placeholder = str(S.desktop_pc_enter_limit),
            enabled = !unlimited,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleLine(
            title = str(S.desktop_can_override),
            detail = str(S.desktop_pc_can_override_desc),
            checked = member.canOverride,
            enabled = !member.isSenior,
        ) { edit(editor.copy(member = member.copy(canOverride = it))) }
        ToggleLine(
            title = str(S.desktop_pc_is_senior),
            detail = str(S.desktop_pc_is_senior_desc),
            checked = member.isSenior,
        ) { on ->
            val next = if (on) {
                member.copy(isSenior = true, postingLimit = null, canOverride = true)
            } else {
                member.copy(isSenior = false)
            }
            edit(editor.copy(member = next, limitText = if (on) "" else editor.limitText))
        }
    }
}

// -- Department Coordinator Designations -----------------------------------------

/**
 * Department | User | Coding Required | Remove (`SettingsPage.jsx:669-751`).
 * Each picker opens a modal; each row carries its own error.
 */
@Suppress("LongMethod") // The grid: a header and one row per coordinator.
@Composable
private fun CoordinatorSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val rows = draft.coordinators
    val dirty = rows != state.settings?.coordinators
    val errors = state.insights.coordinatorErrors
    SettingsCard(
        title = str(S.ah_settings_dept_coordinator),
        icon = ZillitIcons.Grid,
        action = {
            SaveButton(dirty, state) { onEvent(CardEvent.SaveSettings(SettingsSection.Coordinators)) }
            ZillitButton(
                text = str(S.desktop_pc_add_coordinator),
                onClick = {
                    onEvent(CardEvent.EditSettings(draft.copy(coordinators = rows + DepartmentCoordinator(""))))
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (rows.isEmpty()) {
            Muted(str(S.desktop_ce_insights_coordinators_empty))
            return@SettingsCard
        }
        Row(Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(vertical = 8.dp)) {
            Header(str(S.department), Modifier.weight(1.2f).padding(horizontal = 12.dp))
            Header(str(S.user_label), Modifier.weight(1.4f).padding(horizontal = 12.dp))
            Header(str(S.desktop_pc_col_coding_required), Modifier.width(CODING_WIDTH), center = true)
            Spacer(Modifier.width(REMOVE_WIDTH))
        }
        rows.forEachIndexed { index, row ->
            ZillitDivider()
            CoordinatorRow(state, index, row, errors, draft, onEvent)
        }
    }
}

@Suppress("LongMethod") // Four cells: department, people, the switch and Remove.
@Composable
private fun CoordinatorRow(
    state: CardUiState,
    index: Int,
    row: DepartmentCoordinator,
    errors: Map<String, String>,
    draft: CardSettings,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val department = state.insights.departments.firstOrNull { it.id == row.departmentId }?.name
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.2f).padding(horizontal = 12.dp)) {
            ZillitButton(
                text = department
                    ?: if (row.departmentId.isBlank()) str(S.desktop_pc_select_department_dots) else "—",
                onClick = { onEvent(InsightsEvent.OpenCoordinatorPicker(index, users = false)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            errors["${index}_dept"]?.let { ErrorLine(it) }
        }
        Column(Modifier.weight(1.4f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val people = row.userIds.mapNotNull { id -> state.people.firstOrNull { it.id == id } }
            if (people.isEmpty()) {
                ZillitButton(
                    text = if (row.departmentId.isBlank()) {
                        str(S.desktop_pc_select_department_first)
                    } else {
                        str(S.desktop_pc_select_users_dots)
                    },
                    onClick = { onEvent(InsightsEvent.OpenCoordinatorPicker(index, users = true)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = row.departmentId.isNotBlank(),
                )
            } else {
                people.forEach { person ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZillitAvatar(name = person.name, userId = person.id, size = 28.dp)
                        Column {
                            ZillitText(
                                text = person.name,
                                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                            )
                            ZillitText(
                                text = person.designation,
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(InsightsEvent.OpenCoordinatorPicker(index, users = true)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            errors["${index}_users"]?.let { ErrorLine(it) }
        }
        Box(Modifier.width(CODING_WIDTH), contentAlignment = Alignment.Center) {
            ZillitSwitch(
                checked = row.codingRequired,
                onCheckedChange = { on ->
                    val next = draft.coordinators.mapIndexed { at, existing ->
                        if (at == index) existing.copy(codingRequired = on) else existing
                    }
                    onEvent(CardEvent.EditSettings(draft.copy(coordinators = next)))
                },
            )
        }
        Box(Modifier.width(REMOVE_WIDTH), contentAlignment = Alignment.Center) {
            ZillitButton(
                text = str(S.remove),
                onClick = { onEvent(InsightsEvent.RemoveCoordinator(index)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

/** "Select Department" / "Select Users" — search, the list, "N selected", Done (`:753-845`). */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Two lists in one modal.
@Composable
private fun CoordinatorPickerDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val picker = state.insights.coordinatorPicker ?: return
    val query = picker.search.trim().lowercase()
    val row = state.settingsDraft?.coordinators?.getOrNull(picker.index)
    ZillitDialogShell(
        title = if (picker.users) str(S.select_users) else str(S.select_department),
        visible = true,
        width = PICKER_WIDTH,
        onDismiss = { onEvent(InsightsEvent.CloseCoordinatorPicker) },
        actions = {
            ZillitText(
                text = when {
                    picker.users -> str(S.dd_n_selected, picker.userIds.size)
                    picker.departmentId.isNotBlank() -> str(S.dd_n_selected, 1)
                    else -> str(S.dm_nda_none_selected)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ah_done), onClick = { onEvent(InsightsEvent.ApplyCoordinatorPicker) })
        },
    ) {
        ZillitTextField(
            value = picker.search,
            onValueChange = { onEvent(InsightsEvent.EditCoordinatorPicker(picker.copy(search = it))) },
            placeholder = if (picker.users) {
                str(S.desktop_pc_search_select_users)
            } else {
                str(S.desktop_pc_search_departments)
            },
            leadingIcon = ZillitIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        if (picker.users) {
            val people = state.people
                .filter { it.departmentId == row?.departmentId }
                .filter { query.isEmpty() || "${it.name} ${it.designation}".lowercase().contains(query) }
            if (people.isEmpty()) Muted(str(S.desktop_drive_no_results_found))
            people.forEach { person ->
                val chosen = person.id in picker.userIds
                PickerLine(chosen, onClick = {
                    val next = if (chosen) picker.userIds - person.id else picker.userIds + person.id
                    onEvent(InsightsEvent.EditCoordinatorPicker(picker.copy(userIds = next)))
                }) {
                    ZillitAvatar(name = person.name, userId = person.id, size = 28.dp)
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = person.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                        ZillitText(
                            text = person.designation,
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
            }
        } else {
            val departments = state.insights.departments
                .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            if (departments.isEmpty()) Muted(str(S.desktop_drive_no_results_found))
            departments.forEach { department ->
                PickerLine(department.id == picker.departmentId, onClick = {
                    onEvent(InsightsEvent.EditCoordinatorPicker(picker.copy(departmentId = department.id)))
                }) {
                    ZillitText(
                        text = department.name,
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerLine(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        if (selected) {
            ZillitIcon(ZillitIcons.Check, tint = colors.accent, size = 14.dp)
        }
    }
}

// -- Approval & Override -----------------------------------------------------------

/**
 * The web's three switches (`APPROVAL_TOGGLE_DEFS`). The senior sign-off
 * flag has no switch on the web; it still rides along in the saved object,
 * unchanged, as the web's does.
 */
@Composable
private fun OverridesSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val overrides = draft.overrides
    val dirty = overrides != state.settings?.overrides
    SettingsCard(
        title = str(S.ah_settings_approval_override),
        icon = ZillitIcons.Shield,
        action = { SaveButton(dirty, state) { onEvent(CardEvent.SaveSettings(SettingsSection.Overrides)) } },
    ) {
        ToggleLine(
            str(S.desktop_ce_insights_override_card_label),
            str(S.desktop_ce_insights_override_card_desc),
            overrides.overrideCardRequests,
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(overrideCardRequests = it)))) }
        ZillitDivider()
        ToggleLine(
            str(S.desktop_ce_insights_override_receipt_label),
            str(S.desktop_ce_insights_override_receipt_desc),
            overrides.overrideReceipts,
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(overrideReceipts = it)))) }
        ZillitDivider()
        ToggleLine(
            str(S.desktop_pc_coord_code_label),
            str(S.desktop_ce_insights_coord_code_desc),
            overrides.requireCoordinatorCoding,
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(requireCoordinatorCoding = it)))) }
    }
}

// -- Request Cap -----------------------------------------------------------------------

/**
 * The web's shared `RequestCapSection` with `noun="card"`: the master
 * switch, the basis, and the figures — visible but disabled while off, so the
 * stored policy can be read without toggling. The save is refused while the
 * cap would block everything (`RequestCapSection.jsx:76-78`).
 */
@Suppress("LongMethod") // The switch, the basis, two layouts of the figures and the note.
@Composable
private fun RequestCapSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val cap = draft.requestCap
    val dirty = cap != state.settings?.requestCap
    val weekly = cap.basis == RequestCapBasis.WeeklySalary
    val problem = cap.problem()
    val symbol = Money.symbol(state.currency).trim()
    val edit: (RequestCap) -> Unit = { onEvent(CardEvent.EditSettings(draft.copy(requestCap = it))) }
    SettingsCard(
        title = str(S.desktop_pc_request_cap),
        icon = ZillitIcons.Wallet,
        action = {
            SaveButton(dirty, state, enabled = problem == null) {
                onEvent(CardEvent.SaveSettings(SettingsSection.RequestCap))
            }
        },
    ) {
        ToggleLine(
            str(S.desktop_pc_cap_toggle_label),
            str(S.desktop_ce_insights_cap_toggle_desc),
            cap.enabled,
        ) { edit(cap.copy(enabled = it)) }
        ZillitDivider()
        InsightEyebrow(str(S.dm_rule_basis))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestCapBasis.entries.forEach { basis ->
                ZillitButton(
                    text = basis.label,
                    onClick = { edit(cap.copy(basis = basis)) },
                    variant = if (cap.basis == basis) ButtonVariant.Primary else ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = cap.enabled,
                )
            }
        }
        ZillitDivider()
        if (weekly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightEyebrow(str(S.desktop_pc_with_a_deal), modifier = Modifier.width(CAP_LABEL))
                ZillitText(
                    text = str(S.desktop_pc_weekly_rate_times),
                    style = ZillitTheme.typography.bodyMedium,
                )
                CardCalcInput(
                    value = plainAmount(cap.salaryMultiplier),
                    onValueChange = { edit(cap.copy(salaryMultiplier = it.toDoubleOrNull() ?: 0.0)) },
                    placeholder = "1",
                    enabled = cap.enabled,
                    modifier = Modifier.width(MULTIPLIER_WIDTH),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (weekly) InsightEyebrow(str(S.desktop_pc_with_no_deal), modifier = Modifier.width(CAP_LABEL))
            if (weekly) {
                ZillitText(text = str(S.desktop_pc_maximum), style = ZillitTheme.typography.bodyMedium)
            } else {
                InsightEyebrow(str(S.desktop_ce_max_amount))
            }
            CardCalcInput(
                value = cap.maxAmount.takeIf { it > 0 }?.let(::plainAmount).orEmpty(),
                onValueChange = { edit(cap.copy(maxAmount = it.toDoubleOrNull() ?: 0.0)) },
                placeholder = "${symbol}0.00",
                enabled = cap.enabled,
                modifier = Modifier.width(AMOUNT_WIDTH),
            )
            ZillitText(
                text = str(S.desktop_pc_project_default_currency),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        when (problem) {
            RequestCapProblem.Multiplier -> ErrorLine(str(S.desktop_pc_cap_needs_multiplier))
            RequestCapProblem.Amount -> ErrorLine(str(S.desktop_ce_cap_needs_amount))
            null -> Unit
        }
        if (weekly) {
            ZillitNotice(
                text = str(S.desktop_pc_cap_weekly_note),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Info,
            )
        }
    }
}

// -- Auto-Assignment Rules ----------------------------------------------------------

/**
 * The hub's rules filed under `card_expenses` (`SettingsPage.jsx:1190-1297`):
 * the assignee, then departments, nominal codes and an amount, OR'd. A saved
 * rule's delete is immediate; Save writes each rule in turn.
 */
@Suppress("LongMethod") // One rule: its header, its assignee and three conditions.
@Composable
private fun AssignmentRulesSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val rules = draft.assignmentRules
    val dirty = rules != state.settings?.assignmentRules
    val accounts = state.people.filter { it.isAccountsTeam }
    val departments = state.insights.departments
    val symbol = Money.symbol(state.currency).trim()
    SettingsCard(
        title = str(S.desktop_auto_assignment_rules),
        icon = ZillitIcons.Hierarchy,
        action = {
            if (dirty) {
                ZillitButton(
                    text = if (state.insights.rulesSaving) str(S.ah_saving) else str(S.save),
                    onClick = { onEvent(InsightsEvent.SaveRules) },
                    size = ButtonSize.Small,
                    enabled = !state.insights.rulesSaving,
                    loading = state.insights.rulesSaving,
                )
            }
            ZillitButton(
                text = str(S.desktop_pc_add_rule),
                onClick = { onEvent(InsightsEvent.AddRule) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (rules.isEmpty()) {
            Muted(str(S.desktop_ce_insights_rules_empty))
            return@SettingsCard
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            val edit: (CardAssignmentRule) -> Unit = {
                onEvent(InsightsEvent.EditRule(index, it))
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InsightEyebrow(str(S.desktop_pc_rule_n, index + 1), modifier = Modifier.weight(1f))
                    ZillitSwitch(checked = rule.isActive, onCheckedChange = { edit(rule.copy(isActive = it)) })
                    ZillitButton(
                        text = "",
                        onClick = { onEvent(InsightsEvent.RemoveRule(index)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Trash,
                    )
                }
                InsightEyebrow(str(S.desktop_assign_to))
                ZillitSelect(
                    value = accounts.firstOrNull { it.id == rule.assignTo },
                    options = listOf<CardPerson?>(null) + accounts,
                    onSelect = { edit(rule.copy(assignTo = it?.id.orEmpty())) },
                    label = { person -> person?.pickerLabel ?: str(S.desktop_pc_select_user) },
                    modifier = Modifier.fillMaxWidth(),
                )
                InsightEyebrow(str(S.desktop_pc_any_condition))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InsightEyebrow(str(S.departments))
                        ZillitMultiSelect(
                            selected = rule.departments,
                            options = departments.map { it.id },
                            label = { id -> departments.firstOrNull { it.id == id }?.name ?: "—" },
                            onChange = { edit(rule.copy(departments = it)) },
                            placeholder = str(S.desktop_pc_select_departments),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InsightEyebrow(str(S.desktop_pc_cost_code_nominal))
                        ZillitMultiSelect(
                            selected = rule.nominalCodes,
                            options = CARD_RULE_NOMINALS.map { it.first },
                            label = { code ->
                                CARD_RULE_NOMINALS.firstOrNull { it.first == code }
                                    ?.let { "$code — ${it.second}" } ?: code
                            },
                            onChange = { edit(rule.copy(nominalCodes = it)) },
                            placeholder = str(S.desktop_pc_select_nominals),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InsightEyebrow(str(S.desktop_pc_amount_max_in, symbol))
                        CardCalcInput(
                            value = rule.amountMin,
                            onValueChange = { edit(rule.copy(amountMin = it)) },
                            placeholder = str(S.desktop_pc_amount_placeholder, symbol),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// -- shared bits -------------------------------------------------------------------

/** A section card whose header carries its own actions — the web's `SectionSaveButton` and Add. */
@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    description: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitSectionCard(title = title, icon = icon, action = action) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            description?.let { Muted(it) }
            content()
        }
    }
}

/** Save, only while the section has changes; no Discard, as on the web. */
@Composable
private fun SaveButton(dirty: Boolean, state: CardUiState, enabled: Boolean = true, onSave: () -> Unit) {
    if (!dirty) return
    ZillitButton(
        text = if (state.busy) str(S.ah_saving) else str(S.save),
        onClick = onSave,
        size = ButtonSize.Small,
        enabled = enabled && !state.busy,
        loading = state.busy,
    )
}

@Composable
private fun ToggleLine(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            ZillitText(text = title, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Header(text: String, modifier: Modifier, end: Boolean = false, center: Boolean = false) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        textAlign = when {
            end -> TextAlign.End
            center -> TextAlign.Center
            else -> TextAlign.Start
        },
        modifier = modifier,
    )
}

@Composable
private fun Muted(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
}

@Composable
private fun ErrorLine(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
}

/**
 * An id for a provider the server has not seen.
 *
 * The card forms pin a provider by id and the settings row is the only place
 * one is minted, so it has to be stable and unique within the list — the
 * highest existing number plus one, rather than a timestamp, so a list saved
 * twice in the same second does not collide.
 */
private fun newProviderId(existing: List<CardProvider>): String {
    val highest = existing.mapNotNull { it.id.removePrefix(PROVIDER_PREFIX).toIntOrNull() }.maxOrNull() ?: 0
    return "$PROVIDER_PREFIX${highest + 1}"
}

private const val PROVIDER_PREFIX = "provider-"
private val ACTIONS_WIDTH = 110.dp
private val CODING_WIDTH = 120.dp
private val REMOVE_WIDTH = 100.dp
private val MEMBER_WIDTH = 420.dp
private val PICKER_WIDTH = 480.dp
private val CAP_LABEL = 130.dp
private val MULTIPLIER_WIDTH = 90.dp
private val AMOUNT_WIDTH = 150.dp
