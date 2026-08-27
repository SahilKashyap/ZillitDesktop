package com.zillit.desktop.feature.sos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosCrewMember

/**
 * The SOS page.
 *
 * One scrolling column, two cards: the alerts this production has raised, and
 * the receivers who get the next one. The whole page scrolls rather than the
 * list — a lazy list inside a scrolling page crashes on infinite constraints,
 * and a page of at most fifty rows does not need virtualising.
 *
 * Every colour comes from [ZillitTheme], so light and dark are the same code.
 */
@Composable
fun SosScreen(state: SosUiState, onEvent: (SosEvent) -> Unit, mayCall: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            SosHeader(state, onEvent)
            SendNotice()
            state.error?.let { message -> ErrorNotice(message, onEvent) }
            AlertsCard(state, mayCall, onEvent)
            ContactsCard(state, onEvent)
        }
        state.confirm?.let { ConfirmDialog(it, onEvent) }
    }
}

@Composable
private fun SosHeader(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    ZillitPageHeader(
        title = "SOS",
        description = "Raise the alarm, and choose who it reaches.",
        actions = {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = "Refresh alerts",
                onClick = { onEvent(SosEvent.Refresh) },
                enabled = !state.loading,
            )
            ZillitButton(
                text = "Clear all",
                onClick = { onEvent(SosEvent.AskDeleteAllAlerts) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Trash,
                enabled = state.alerts.isNotEmpty() && !state.busy,
            )
            ZillitButton(
                text = "Send SOS",
                onClick = { onEvent(SosEvent.AskSendAlert) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Siren,
                enabled = !state.busy,
                loading = state.busy,
            )
        },
    )
}

/** The web's tooltip on the alarm, said out loud (`SOSMain.jsx:589-605`). */
@Composable
private fun SendNotice() {
    ZillitNotice(
        text = "Sending an SOS shares your location with every receiver on this production. " +
            "Use it only if you are in real danger.",
        tone = StatusTone.Escalated,
        icon = ZillitIcons.Siren,
    )
}

@Composable
private fun ErrorNotice(message: String, onEvent: (SosEvent) -> Unit) {
    ZillitNotice(
        text = message,
        tone = StatusTone.Rejected,
        action = {
            ZillitButton(
                text = "Dismiss",
                onClick = { onEvent(SosEvent.DismissError) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    )
}

// Alerts -------------------------------------------------------------------

@Composable
private fun AlertsCard(state: SosUiState, mayCall: Boolean, onEvent: (SosEvent) -> Unit) {
    ZillitSectionCard(
        title = "SOS alerts received",
        icon = ZillitIcons.Bell,
        meta = if (state.alerts.isEmpty()) null else "${state.alerts.size}",
    ) {
        when {
            state.loading && state.alerts.isEmpty() ->
                Box(Modifier.fillMaxWidth(), Alignment.Center) { ZillitSpinner() }

            state.loaded && state.alerts.isEmpty() -> ZillitEmptyState(
                title = "No SOS alerts",
                message = "Nobody on this production has raised the alarm.",
                icon = ZillitIcons.Siren,
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                state.alerts.forEach { alert ->
                    AlertRow(
                        alert = alert,
                        viewerId = state.viewer.userId,
                        busy = state.busy,
                        mayCall = mayCall,
                        onEvent = onEvent,
                    )
                }
                MoreRow(state, onEvent)
            }
        }
    }
}

@Composable
private fun MoreRow(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    if (!state.hasMore && !state.loadingMore) return
    Box(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm), Alignment.Center) {
        if (state.loadingMore) {
            ZillitSpinner()
        } else {
            ZillitButton(
                text = "Show older",
                onClick = { onEvent(SosEvent.LoadOlder) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * One alert: who raised it, what it said, when, and the two things that can be
 * done with it — open the map link and delete the row.
 */
@Composable
private fun AlertRow(
    alert: SosAlert,
    viewerId: String,
    busy: Boolean,
    mayCall: Boolean,
    onEvent: (SosEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        AlertRowHead(alert, viewerId, busy, mayCall, onEvent)
        ZillitText(text = alert.text, style = ZillitTheme.typography.bodyMedium)
        if (alert.contactInfo.isNotBlank()) {
            ZillitText(
                text = "Contact: ${alert.contactInfo}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        if (alert.isEntertainment) {
            ZillitText(
                text = "You can call GSM contacts through a mobile.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun AlertRowHead(
    alert: SosAlert,
    viewerId: String,
    busy: Boolean,
    mayCall: Boolean,
    onEvent: (SosEvent) -> Unit,
) {
    val sent = alert.senderId.isNotBlank() && alert.senderId == viewerId
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                // "Sent" / "Received", the two words the web puts on the card
                // (`SOSMain.jsx:451-457`).
                text = if (sent) "Sent" else "Received",
                style = ZillitTheme.typography.labelSmall,
                color = if (sent) ZillitTheme.colors.textMuted else ZillitTheme.colors.danger,
                maxLines = 1,
            )
            if (alert.senderNameHint.isNotBlank()) {
                ZillitText(
                    text = alert.senderNameHint,
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = alert.timeLabel,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        // Only on somebody else's alert: your own has nobody to ring, and a
        // button that always answers "this is your own alert" is furniture.
        if (mayCall && !sent && alert.senderId.isNotBlank()) {
            ZillitIconButton(
                icon = ZillitIcons.Phone,
                contentDescription = "Call them",
                onClick = { onEvent(SosEvent.CallSender(alert.id, video = false)) },
                enabled = !busy,
            )
            ZillitIconButton(
                icon = ZillitIcons.Camera,
                contentDescription = "Video call them",
                onClick = { onEvent(SosEvent.CallSender(alert.id, video = true)) },
                enabled = !busy,
            )
        }
        ZillitIconButton(
            icon = ZillitToolIcons.Location,
            contentDescription = "Open location",
            onClick = { onEvent(SosEvent.OpenMap(alert.id)) },
            enabled = alert.mapsUrl.isNotBlank(),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Delete alert",
            onClick = { onEvent(SosEvent.AskDeleteAlert(alert.id)) },
            enabled = !busy,
            tint = ZillitTheme.colors.danger,
        )
    }
}

// Receivers ----------------------------------------------------------------

@Composable
private fun ContactsCard(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val contacts = state.contacts
    ZillitSectionCard(title = "SOS receivers", icon = ZillitIcons.Users) {
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(id = SosContactTab.Member.id, label = SosContactTab.Member.label),
                ZillitTab(id = SosContactTab.Outsider.id, label = SosContactTab.Outsider.label),
            ),
            activeId = contacts.tab.id,
            onSelect = { onEvent(SosEvent.SelectContactTab(SosContactTab.fromId(it))) },
        )
        contacts.formError?.let { problem ->
            ZillitNotice(text = problem, tone = StatusTone.Rejected)
        }
        if (contacts.tab == SosContactTab.Member) {
            MemberForm(state, onEvent)
            ZillitDivider()
            ContactList(contacts.members, state, onEvent)
        } else {
            OutsiderForm(state, onEvent)
            ZillitDivider()
            ContactList(contacts.outsiders, state, onEvent)
        }
    }
}

/**
 * The member picker.
 *
 * The web offers a `Select` over the crew (`SOS.jsx:546-558`); a desktop list
 * with a search above it does the same job without a dropdown that has to hold
 * a two-hundred-name production.
 */
@Composable
private fun MemberForm(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val contacts = state.contacts
    val addable = contacts.addableCrew(state.viewer.userId)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = contacts.crewSearch,
                onValueChange = { onEvent(SosEvent.CrewSearchChanged(it)) },
                placeholder = "Search the crew",
                modifier = Modifier.weight(1f),
            )
            if (contacts.isEditing) {
                ZillitButton(
                    text = "Cancel edit",
                    onClick = { onEvent(SosEvent.CancelEdit) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        if (addable.isEmpty()) {
            ZillitText(
                text = "No crew left to add.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            addable.take(CREW_SHOWN).forEach { member ->
                CrewRow(member = member, state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun CrewRow(member: SosCrewMember, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
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
            enabled = !state.contacts.busy,
        )
    }
}

/**
 * The outsider form — name, relationship, dialling code, number.
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
        )
        return
    }
    val contacts = state.contacts
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = contacts.draft.contactName,
            onValueChange = { onEvent(SosEvent.ContactNameChanged(it)) },
            label = "Name",
            placeholder = "Enter name",
        )
        RelationField(state, onEvent)
        CountryCodeField(state, onEvent)
        ZillitTextField(
            value = contacts.draft.phoneNumber,
            onValueChange = { onEvent(SosEvent.PhoneChanged(it)) },
            label = "Mobile number",
            placeholder = "Enter mobile number",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = if (contacts.isEditing) "Save receiver" else "Add receiver",
                onClick = { onEvent(SosEvent.SubmitOutsider) },
                enabled = !contacts.busy,
                loading = contacts.busy,
            )
            if (contacts.isEditing) {
                ZillitButton(
                    text = "Cancel edit",
                    onClick = { onEvent(SosEvent.CancelEdit) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }
    }
}

@Composable
private fun RelationField(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val names = state.contacts.relations.map { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "Relationship",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitSelect(
            value = state.contacts.draft.relation,
            options = names,
            onSelect = { onEvent(SosEvent.RelationChanged(it)) },
            label = { it.ifBlank { "Select" } },
            // A select fills whatever it is given, so it is pinned rather than
            // left to swallow the form — see the Documents & Signature port.
            modifier = Modifier.width(FIELD_WIDTH),
            enabled = names.isNotEmpty(),
        )
    }
}

@Composable
private fun CountryCodeField(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val contacts = state.contacts
    val codes = contacts.visibleCodes
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "Country code",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = contacts.codeSearch,
                onValueChange = { onEvent(SosEvent.CodeSearchChanged(it)) },
                placeholder = "Search a country or code",
                modifier = Modifier.width(FIELD_WIDTH),
            )
            ZillitSelect(
                value = contacts.draft.countryCode,
                options = codes.take(CODES_SHOWN).map { it.dialCode },
                onSelect = { onEvent(SosEvent.CountryCodeChanged(it)) },
                label = { dial -> codes.firstOrNull { it.dialCode == dial }?.label ?: dial.ifBlank { "Select" } },
                modifier = Modifier.width(FIELD_WIDTH),
                enabled = codes.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ContactList(rows: List<SosContact>, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    if (rows.isEmpty()) {
        ZillitText(
            text = "No receivers on this list yet.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        rows.forEach { row -> ContactRow(row = row, state = state, onEvent = onEvent) }
    }
}

@Composable
private fun ContactRow(row: SosContact, state: SosUiState, onEvent: (SosEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
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
    }.joinToString("  •  ")
    if (meta.isBlank()) return
    ZillitText(
        text = meta,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

// Confirmation --------------------------------------------------------------

/**
 * One dialog for all four irreversible acts. The delete wording is Android's
 * `record_delete_confirmation`; the send wording is new, because neither other
 * client asks before raising the alarm.
 */
@Composable
private fun ConfirmDialog(confirm: SosConfirm, onEvent: (SosEvent) -> Unit) {
    val message = when (confirm) {
        SosConfirm.SendAlert ->
            "Send an SOS to every receiver on this production, with your location? " +
                "Do this only if you are in real danger."
        is SosConfirm.DeleteAlert -> "Are you sure you want to delete this record?"
        SosConfirm.DeleteAllAlerts -> "Are you sure you want to clear all SOS alerts?"
        is SosConfirm.DeleteContact -> "Are you sure you want to delete this record?"
    }
    ZillitDialogShell(
        title = if (confirm == SosConfirm.SendAlert) "Send SOS?" else "Alert",
        onDismiss = { onEvent(SosEvent.CancelConfirm) },
        visible = true,
        icon = if (confirm == SosConfirm.SendAlert) ZillitIcons.Siren else ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "No",
                onClick = { onEvent(SosEvent.CancelConfirm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Yes",
                onClick = { onEvent(SosEvent.ConfirmAction) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(text = message)
    }
}

/** How many crew the picker offers before the search has to narrow it. */
private const val CREW_SHOWN = 30

/** How many dialling codes the dropdown holds; the full list is ~240 rows. */
private const val CODES_SHOWN = 40

private val FIELD_WIDTH = 260.dp
