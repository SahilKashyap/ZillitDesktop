package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * The production's card configuration. Senior accountants only.
 *
 * ## Five sections, five saves
 *
 * Each section writes its own key and nothing else. The settings PATCH merges,
 * so a section-sized body leaves the rest of the document alone — which is
 * what stops two accountants with this page open from overwriting each other's
 * work in a part of it neither of them touched.
 *
 * These five are the whole document. An earlier build of this page offered
 * auto-matching, duplicate detection and a default card limit; none of them is
 * a column on the settings row, the server's allowlist dropped each one
 * silently, and the page said "Settings saved" every time.
 */
@Composable
fun CardSettingsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage {
            ZillitNotice(text = "Loading the project's card settings…", tone = StatusTone.Progress)
        }
        return
    }

    ScrollingPage {
        ZillitNotice(
            text = "These settings apply to every card on this production. Each section saves on its own.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )

        TeamSection(state, draft, onEvent)
        CoordinatorSection(state, draft, onEvent)
        OverridesSection(state, draft, onEvent)
        ProvidersSection(state, draft, onEvent)
        RequestCapSection(state, draft, onEvent)
    }
}

/**
 * Who on the accounts team may post, and up to what.
 *
 * The posting limit is three-valued and the row says which: **unlimited**,
 * **no access** at zero, and a ceiling otherwise. Treating a blank field as
 * zero is the mistake that silently takes posting away from someone who was
 * meant to have it without a ceiling, so the switch is explicit.
 */
@Suppress("LongMethod") // One section: the list, its rows and the save.
@Composable
private fun TeamSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val members = draft.teamMembers
    val dirty = members != state.settings?.teamMembers

    SettingsSectionCard(
        section = SettingsSection.Team,
        description = "Posting limits, override access and senior standing for the accounts team.",
        icon = ZillitIcons.Users,
        dirty = dirty,
        state = state,
        onEvent = onEvent,
        action = {
            ZillitButton(
                text = "Add member",
                onClick = {
                    onEvent(CardEvent.EditSettings(draft.copy(teamMembers = members + CardTeamMember(""))))
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
            )
        },
    ) {
        if (members.isEmpty()) {
            EmptySectionLine("Nobody is configured, so nobody can post. Add the accounts team here.")
            return@SettingsSectionCard
        }
        members.forEachIndexed { index, member ->
            if (index > 0) ZillitDivider()
            TeamMemberRow(
                member = member,
                people = state.people,
                currency = state.currency,
                onChange = { updated ->
                    onEvent(
                        CardEvent.EditSettings(
                            draft.copy(
                                teamMembers = members.mapIndexed { at, row -> if (at == index) updated else row },
                            ),
                        ),
                    )
                },
                onRemove = {
                    onEvent(
                        CardEvent.EditSettings(
                            draft.copy(teamMembers = members.filterIndexed { at, _ -> at != index }),
                        ),
                    )
                },
            )
        }
    }
}

@Suppress("LongMethod") // One person's whole row.
@Composable
private fun TeamMemberRow(
    member: CardTeamMember,
    people: List<CardPerson>,
    currency: String?,
    onChange: (CardTeamMember) -> Unit,
    onRemove: () -> Unit,
) {
    val person = people.firstOrNull { it.id == member.userId }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = person?.name ?: member.userId)
            ZillitSelect(
                value = person,
                options = people,
                onSelect = { onChange(member.copy(userId = it?.id.orEmpty()).normalised()) },
                label = { it?.pickerLabel ?: "Choose a person" },
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(
                label = when {
                    member.unlimited -> "Unlimited"
                    member.blocked -> "No access"
                    else -> "Up to ${money(member.postingLimit, currency)}"
                },
                tone = when {
                    member.unlimited -> StatusTone.Done
                    member.blocked -> StatusTone.Rejected
                    else -> StatusTone.Progress
                },
                dot = true,
            )
            ZillitButton(
                text = "",
                onClick = onRemove,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = member.postingLimit.asAmountField(),
                onValueChange = { text ->
                    onChange(member.copy(postingLimit = text.trim().toDoubleOrNull() ?: 0.0))
                },
                label = "Posting limit",
                placeholder = "0 blocks posting entirely",
                keyboardType = KeyboardType.Decimal,
                enabled = !member.unlimited,
                modifier = Modifier.width(LIMIT_FIELD),
            )
            ZillitSwitch(
                checked = member.unlimited,
                onCheckedChange = { on -> onChange(member.copy(postingLimit = if (on) null else 0.0)) },
                enabled = !member.isSenior,
                label = "Unlimited",
            )
            ZillitSwitch(
                checked = member.canOverride,
                onCheckedChange = { onChange(member.copy(canOverride = it)) },
                enabled = !member.isSenior,
                label = "Can override a chain",
            )
            // A senior is unlimited and can override by definition, so turning
            // this on takes the other two out of the person's hands rather
            // than leaving a contradiction on the row.
            ZillitSwitch(
                checked = member.isSenior,
                onCheckedChange = { onChange(member.copy(isSenior = it).normalised()) },
                label = "Senior",
            )
        }
    }
}

/**
 * Which department codes its own receipts, and who does it.
 *
 * A department with coding switched on routes every receipt through its
 * coordinators before the approval chain, which is why the row is a department
 * plus people plus a switch rather than three separate lists.
 */
@Suppress("LongMethod") // One section; see TeamSection.
@Composable
private fun CoordinatorSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val rows = draft.coordinators
    val dirty = rows != state.settings?.coordinators
    val departments = state.people
        .filter { it.departmentId.isNotBlank() }
        .distinctBy { it.departmentId }

    SettingsSectionCard(
        section = SettingsSection.Coordinators,
        description = "Who codes each department's receipts, and whether that department has to.",
        icon = ZillitIcons.Grid,
        dirty = dirty,
        state = state,
        onEvent = onEvent,
        action = {
            ZillitButton(
                text = "Add department",
                onClick = {
                    onEvent(CardEvent.EditSettings(draft.copy(coordinators = rows + DepartmentCoordinator(""))))
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (rows.isEmpty()) {
            EmptySectionLine("No department codes its own receipts. Every receipt goes straight to approval.")
            return@SettingsSectionCard
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) ZillitDivider()
            val inDepartment = state.people.filter { it.departmentId == row.departmentId }
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitSelect(
                        value = departments.firstOrNull { it.departmentId == row.departmentId },
                        options = departments,
                        onSelect = { chosen ->
                            // Changing the department empties the people: a
                            // coordinator from the old one is not in the new.
                            onEvent(
                                CardEvent.EditSettings(
                                    draft.copy(
                                        coordinators = rows.mapIndexed { at, existing ->
                                            if (at == index) {
                                                existing.copy(
                                                    departmentId = chosen?.departmentId.orEmpty(),
                                                    userIds = emptyList(),
                                                )
                                            } else {
                                                existing
                                            }
                                        },
                                    ),
                                ),
                            )
                        },
                        label = { it?.department?.ifBlank { it.departmentId } ?: "Choose a department" },
                        modifier = Modifier.weight(1f),
                    )
                    ZillitSwitch(
                        checked = row.codingRequired,
                        onCheckedChange = { on ->
                            onEvent(
                                CardEvent.EditSettings(
                                    draft.copy(
                                        coordinators = rows.mapIndexed { at, existing ->
                                            if (at == index) existing.copy(codingRequired = on) else existing
                                        },
                                    ),
                                ),
                            )
                        },
                        label = "Coding required",
                    )
                    ZillitButton(
                        text = "",
                        onClick = {
                            onEvent(
                                CardEvent.EditSettings(
                                    draft.copy(coordinators = rows.filterIndexed { at, _ -> at != index }),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Trash,
                    )
                }
                ZillitMultiSelect(
                    selected = row.userIds,
                    options = inDepartment.map { it.id },
                    label = { id -> inDepartment.firstOrNull { it.id == id }?.pickerLabel ?: id },
                    onChange = { next ->
                        onEvent(
                            CardEvent.EditSettings(
                                draft.copy(
                                    coordinators = rows.mapIndexed { at, existing ->
                                        if (at == index) existing.copy(userIds = next) else existing
                                    },
                                ),
                            ),
                        )
                    },
                    placeholder = if (row.departmentId.isBlank()) {
                        "Choose a department first"
                    } else {
                        "Choose the coordinators"
                    },
                    enabled = row.departmentId.isNotBlank(),
                    emptyText = "Nobody is listed in this department",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The four switches that let an accountant short-circuit an approval chain. */
@Composable
private fun OverridesSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val overrides = draft.overrides
    val dirty = overrides != state.settings?.overrides

    SettingsSectionCard(
        section = SettingsSection.Overrides,
        description = "When an accountant may step past the standard approval chain.",
        icon = ZillitIcons.Shield,
        dirty = dirty,
        state = state,
        onEvent = onEvent,
    ) {
        OverrideSwitch(
            checked = overrides.overrideCardRequests,
            label = "Accountants may approve card requests outright",
            detail = "The request skips the standard chain and is recorded against their name.",
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(overrideCardRequests = it)))) }
        OverrideSwitch(
            checked = overrides.overrideReceipts,
            label = "Accountants may approve receipts outright",
            detail = "Bypasses the coordinator and the department head.",
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(overrideReceipts = it)))) }
        OverrideSwitch(
            checked = overrides.requireCoordinatorCoding,
            label = "Receipts must be coded by a coordinator first",
            detail = "Nothing enters the approval queue until its department has coded it.",
        ) {
            onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(requireCoordinatorCoding = it))))
        }
        OverrideSwitch(
            checked = overrides.requireSeniorSignOff,
            label = "Senior sign-off before posting",
            detail = "Receipts cannot reach the ledger until a senior accountant has cleared them.",
        ) { onEvent(CardEvent.EditSettings(draft.copy(overrides = overrides.copy(requireSeniorSignOff = it)))) }
    }
}

/**
 * The issuers cards can be held with.
 *
 * Every card form reads this list, and a production with none configured can
 * still raise cards — the provider is soft-required everywhere, because
 * blocking on it would make the card forms unusable until somebody came here.
 */
@Composable
private fun ProvidersSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val providers = draft.providers
    val dirty = providers != state.settings?.providers

    SettingsSectionCard(
        section = SettingsSection.Providers,
        description = "The issuers a card can be held with. Offered on every card form.",
        icon = ZillitIcons.CreditCard,
        dirty = dirty,
        state = state,
        onEvent = onEvent,
        action = {
            ZillitButton(
                text = "Add provider",
                onClick = {
                    val next = providers + CardProvider(id = newProviderId(providers), name = "")
                    onEvent(CardEvent.EditSettings(draft.copy(providers = next)))
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (providers.isEmpty()) {
            EmptySectionLine("No providers configured. Cards are raised without an issuer named.")
            return@SettingsSectionCard
        }
        providers.forEachIndexed { index, provider ->
            ProviderRow(
                provider = provider,
                onRename = { name ->
                    val next = providers.mapIndexed { at, row -> if (at == index) row.copy(name = name) else row }
                    onEvent(CardEvent.EditSettings(draft.copy(providers = next)))
                },
                onRemove = {
                    val next = providers.filterIndexed { at, _ -> at != index }
                    onEvent(CardEvent.EditSettings(draft.copy(providers = next)))
                },
            )
        }
    }
}

@Composable
private fun ProviderRow(provider: CardProvider, onRename: (String) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = provider.name,
            onValueChange = onRename,
            placeholder = "Barclaycard Business",
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "",
            onClick = onRemove,
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
    }
}

/**
 * The most anyone may ask for on a card request.
 *
 * A ceiling on the *request*, not on what is granted: an accountant sets the
 * authorised limit when they approve, and this stops a request arriving for a
 * figure nobody was ever going to agree to.
 */
@Composable
private fun RequestCapSection(state: CardUiState, draft: CardSettings, onEvent: (CardEvent) -> Unit) {
    val dirty = draft.requestCap != state.settings?.requestCap

    SettingsSectionCard(
        section = SettingsSection.RequestCap,
        description = "The most a card request may ask for. Leave it empty for no ceiling.",
        icon = ZillitIcons.Wallet,
        dirty = dirty,
        state = state,
        onEvent = onEvent,
    ) {
        ZillitTextField(
            value = draft.requestCap.asAmountField(),
            onValueChange = { text ->
                onEvent(CardEvent.EditSettings(draft.copy(requestCap = text.trim().toDoubleOrNull())))
            },
            label = "Maximum request",
            placeholder = "No ceiling",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(LIMIT_FIELD),
        )
    }
}

/**
 * The shell every section shares: a heading, its body, and its own save.
 *
 * The save is disabled until the section is dirty, and only that section's
 * "unsaved" marker shows — a page-wide dirty flag would tell somebody editing
 * the providers that the coordinators were unsaved too.
 */
@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    dirty: Boolean,
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
    action: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    com.zillit.desktop.core.designsystem.component.ZillitSectionCard(
        title = section.label,
        icon = icon,
        meta = if (dirty) "Unsaved" else null,
        action = action,
    ) {
        ZillitText(
            text = description,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        content()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = "Save",
                onClick = { onEvent(CardEvent.SaveSettings(section)) },
                size = ButtonSize.Small,
                enabled = dirty && !state.busy,
                loading = state.busy,
            )
            if (dirty) {
                ZillitButton(
                    text = "Discard",
                    onClick = { onEvent(CardEvent.DiscardSettings) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun OverrideSwitch(
    checked: Boolean,
    label: String,
    detail: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitSwitch(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun EmptySectionLine(text: String) {
    FieldGroupLabel("Nothing configured")
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
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

/**
 * A whole figure reads as a whole figure.
 *
 * `Double.toString()` puts a `.0` on every round number, so a limit of 2,500
 * came back into its own field as "2500.0" and looked like a typo the moment
 * anybody re-opened the page.
 */
private fun Double?.asAmountField(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> toLong().toString()
    else -> toString()
}

private const val PROVIDER_PREFIX = "provider-"
private val LIMIT_FIELD = 220.dp
