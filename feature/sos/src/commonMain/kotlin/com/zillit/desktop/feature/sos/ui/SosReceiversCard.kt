package com.zillit.desktop.feature.sos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosCrewMember

/**
 * The receivers card: who the next alarm reaches, and the form that adds one.
 *
 * The web's two tabs (`SOS.jsx:528`, `:601`) are a segmented control here —
 * an underline strip inside a card is a second header competing with the
 * card's own. The picker for crew and the form for outsiders sit above the
 * list they add to, so the list is the thing the card is about.
 */
@Composable
internal fun SosReceiversCard(state: SosUiState, onEvent: (SosEvent) -> Unit, modifier: Modifier = Modifier) {
    val contacts = state.contacts
    val rows = if (contacts.tab == SosContactTab.Member) contacts.members else contacts.outsiders
    ZillitSectionCard(
        modifier = modifier,
        title = "Receivers",
        icon = ZillitIcons.Users,
        meta = if (contacts.rows.isEmpty()) null else "${contacts.rows.size}",
    ) {
        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSegmented(
                options = SosContactTab.entries.map { ZillitTab(id = it.id, label = it.label) },
                activeId = contacts.tab.id,
                onSelect = { onEvent(SosEvent.SelectContactTab(SosContactTab.fromId(it))) },
            )
            contacts.formError?.let { problem ->
                ZillitNotice(text = problem, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
            }
            if (contacts.isEditing) EditingBanner(state, onEvent)
            if (contacts.tab == SosContactTab.Member) MemberPicker(state, onEvent) else OutsiderForm(state, onEvent)
            ContactList(rows, state, onEvent)
        }
    }
}

/**
 * The row that says which receiver the form is repointing — the web's
 * `editIconClicked` state (`SOS.jsx:381-409`) made visible, with the one way
 * out of it.
 */
@Composable
private fun EditingBanner(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val editing = state.contacts.rows.firstOrNull { it.id == state.contacts.editingId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.accentSoft)
            .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.xs)
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Edit, tint = colors.accentText, size = ZillitDimens.iconSmall)
        ZillitText(
            text = editing?.let { "Editing ${it.displayName}" } ?: "Editing a receiver",
            style = ZillitTheme.typography.label,
            color = colors.accentText,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Cancel",
            onClick = { onEvent(SosEvent.CancelEdit) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

// Member picker ------------------------------------------------------------

/**
 * The member picker: a search over the crew, and the first [CREW_SHOWN]
 * matches as rows with an Add button.
 *
 * The web offers a `Select` over the crew (`SOS.jsx:546-558`); a desktop list
 * with a search above it does the same job without a dropdown that has to
 * hold a two-hundred-name production.
 */
@Composable
private fun MemberPicker(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val contacts = state.contacts
    val addable = contacts.addableCrew(state.viewer.userId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSearchField(
            value = contacts.crewSearch,
            onValueChange = { onEvent(SosEvent.CrewSearchChanged(it)) },
            placeholder = "Search the crew to add",
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            addable.isEmpty() -> MutedLine(
                when {
                    contacts.crewSearch.isNotBlank() -> "No crew match."
                    contacts.crew.isEmpty() -> "Nobody on the crew to add."
                    else -> "Everyone on the crew is already a receiver."
                },
            )

            else -> Column {
                addable.take(CREW_SHOWN).forEach { member -> CrewRow(member, state, onEvent) }
                if (addable.size > CREW_SHOWN) {
                    MutedLine("${addable.size - CREW_SHOWN} more — search to narrow the list.")
                }
            }
        }
    }
}

@Composable
private fun CrewRow(member: SosCrewMember, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    HoverRow {
        ZillitAvatar(name = member.fullName, userId = member.userId, size = ROW_AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(text = member.fullName, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            if (state.showsDesignation && member.designation.isNotBlank()) {
                ZillitText(
                    text = member.designation,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        ZillitButton(
            text = if (state.contacts.isEditing) "Use" else "Add",
            onClick = { onEvent(SosEvent.SubmitMember(member.userId)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
            enabled = !state.contacts.busy,
        )
    }
}

// Outsider form ------------------------------------------------------------

/**
 * The outsider form — name and relationship on one line, dialling code and
 * number on the next.
 *
 * Refused outright until the viewer's profile carries a phone number, which is
 * what the web's warning modal enforces (`SOS.jsx:129-135`, `:775-785`).
 */
@Composable
private fun OutsiderForm(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    if (!state.canAddOutsider) {
        ZillitNotice(
            text = "Add a mobile number to your profile before adding an outside contact.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Phone,
        )
        return
    }
    val contacts = state.contacts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = contacts.draft.contactName,
                onValueChange = { onEvent(SosEvent.ContactNameChanged(it)) },
                label = "Name",
                placeholder = "Their name",
                leadingIcon = ZillitIcons.User,
                modifier = Modifier.weight(1f),
            )
            RelationField(state, onEvent, Modifier.weight(1f))
        }
        CountryCodeField(state, onEvent, Modifier.fillMaxWidth())
        ZillitTextField(
            value = contacts.draft.phoneNumber,
            onValueChange = { onEvent(SosEvent.PhoneChanged(it)) },
            label = "Mobile number",
            placeholder = "Digits only",
            leadingIcon = ZillitIcons.Phone,
            keyboardType = KeyboardType.Phone,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = if (contacts.isEditing) "Save receiver" else "Add receiver",
                onClick = { onEvent(SosEvent.SubmitOutsider) },
                leadingIcon = if (contacts.isEditing) ZillitIcons.Check else ZillitIcons.UserPlus,
                enabled = !contacts.busy,
                loading = contacts.busy,
            )
        }
    }
}

@Composable
private fun RelationField(state: SosUiState, onEvent: (SosEvent) -> Unit, modifier: Modifier = Modifier) {
    val names = state.contacts.relations.map { it.name }
    Labelled("Relationship", modifier) {
        ZillitSelect(
            value = state.contacts.draft.relation,
            options = names,
            onSelect = { onEvent(SosEvent.RelationChanged(it)) },
            label = { it.ifBlank { "Select" } },
            // A select fills whatever it is given, so it is pinned by the
            // column's weight rather than left to swallow the form — see the
            // Documents & Signature port.
            modifier = Modifier.fillMaxWidth(),
            enabled = names.isNotEmpty(),
        )
    }
}

/**
 * The dialling code: a search that narrows ~240 countries, and the select
 * that picks from what is left, side by side under one caption. The select
 * leads with the code so a clipped label still says the thing that matters.
 */
@Composable
private fun CountryCodeField(state: SosUiState, onEvent: (SosEvent) -> Unit, modifier: Modifier = Modifier) {
    val contacts = state.contacts
    val codes = contacts.visibleCodes
    Labelled("Country code", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSearchField(
                value = contacts.codeSearch,
                onValueChange = { onEvent(SosEvent.CodeSearchChanged(it)) },
                placeholder = "Search",
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = contacts.draft.countryCode,
                options = codes.take(CODES_SHOWN).map { it.dialCode },
                onSelect = { onEvent(SosEvent.CountryCodeChanged(it)) },
                label = { dial ->
                    codes.firstOrNull { it.dialCode == dial }?.let { "${it.dialCode}  ${it.name}" }
                        ?: dial.ifBlank { "Select" }
                },
                modifier = Modifier.weight(CODE_SELECT_WEIGHT),
                enabled = codes.isNotEmpty(),
            )
        }
    }
}

/** A caption over a control, styled the way [ZillitTextField] captions its own. */
@Composable
private fun Labelled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        content()
    }
}

// The list -----------------------------------------------------------------

@Composable
private fun ContactList(rows: List<SosContact>, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val kind = if (state.contacts.tab == SosContactTab.Member) "crew" else "outside contacts"
    ZillitSectionLabel(if (rows.isEmpty()) "Receiving" else "Receiving · ${rows.size}")
    if (state.contacts.loading && rows.isEmpty()) {
        MutedLine("Loading receivers…")
        return
    }
    if (rows.isEmpty()) {
        ContactsEmpty("No $kind on the list yet.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        rows.forEach { row -> ContactRow(row, state, onEvent) }
    }
}

@Composable
private fun ContactsEmpty(text: String) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(HAIRLINE, colors.divider, ZillitTheme.shapes.medium)
            .padding(vertical = ZillitTheme.spacing.lg, horizontal = ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(ZillitIcons.Users, tint = colors.textMuted, size = ZillitDimens.iconLarge)
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun ContactRow(row: SosContact, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val editing = row.id == state.contacts.editingId
    HoverRow(tint = if (editing) ZillitTheme.colors.accentSoft else null) {
        ZillitAvatar(name = row.displayName, userId = row.userId, size = ROW_AVATAR)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = row.displayName, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            ContactRowMeta(row, state.showsDesignation)
        }
        ZillitIconButton(
            icon = ZillitIcons.Edit,
            contentDescription = "Edit receiver",
            onClick = { onEvent(SosEvent.EditContact(row.id)) },
            enabled = !state.contacts.busy,
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Remove receiver",
            onClick = { onEvent(SosEvent.AskDeleteContact(row.id)) },
            enabled = !state.contacts.busy,
            tint = ZillitTheme.colors.danger,
        )
    }
}

@Composable
private fun ContactRowMeta(row: SosContact, showsDesignation: Boolean) {
    val meta = buildList {
        if (row.phoneLabel.isNotBlank()) add(row.phoneLabel)
        if (row.relation.isNotBlank()) add(row.relation)
        if (showsDesignation && row.userDesignation.isNotBlank()) add(row.userDesignation)
    }.joinToString("  ·  ")
    if (meta.isBlank()) return
    ZillitText(
        text = meta,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * A list row that tints on hover. The controls inside are always composed;
 * only the tint answers the pointer, so nothing has to be hovered before it
 * can be clicked.
 */
@Composable
private fun HoverRow(tint: Color? = null, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = tint ?: if (hovered) colors.surfaceHover else Color.Transparent,
        label = "row-hover",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .hoverable(interaction)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

@Composable
private fun MutedLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm)) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** How many crew the picker offers before the search has to narrow it. */
private const val CREW_SHOWN = 30

/** How many dialling codes the dropdown holds; the full list is ~240 rows. */
private const val CODES_SHOWN = 40

/** The code select gets more of the row than its search: it has a country name to show. */
private const val CODE_SELECT_WEIGHT = 1.4f

private val HAIRLINE = 1.dp
private val ROW_AVATAR = 30.dp
