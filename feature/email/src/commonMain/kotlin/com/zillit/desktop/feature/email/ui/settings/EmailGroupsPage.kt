package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailGroup
import com.zillit.desktop.feature.email.ui.DialogButtons
import com.zillit.desktop.feature.email.ui.ModalCard

/**
 * Email Groups — Android `EmailGroupsActivity` and `EditEmailGroupActivity`
 * (`ui/settings/`), as one page: the list, or the editor in its place.
 */
@Composable
internal fun EmailGroupsPage(
    state: EmailGroupsUiState,
    onEvent: (EmailGroupsEvent) -> Unit,
    onBack: () -> Unit,
) {
    val draft = state.draft
    if (draft != null) {
        GroupEditor(state, onEvent)
        return
    }

    SettingsPage(
        title = "Email Groups",
        subtitle = "Manage your email groups",
        onBack = onBack,
        actions = {
            ZillitButton(
                text = "New group",
                leadingIcon = ZillitIcons.Add,
                size = ButtonSize.Small,
                onClick = { onEvent(EmailGroupsEvent.Edit(null)) },
            )
        },
    ) {
        state.error?.let { message ->
            SettingsMessage(text = message, tone = StatusTone.Rejected, onDismiss = { onEvent(EmailGroupsEvent.Load) })
        }
        when {
            state.isLoading -> SettingsHint("Loading…")
            state.groups.isEmpty() ->
                SettingsHint("No email groups yet. Create one to write to several people at once.")
            else -> state.groups.forEach { group -> GroupRow(group, onEvent) }
        }
    }

    state.pendingDelete?.let { group ->
        ModalCard(onDismiss = { onEvent(EmailGroupsEvent.DismissDelete) }) {
            ZillitText(text = "Delete \"${group.name}\"?", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "The group and its member list will be removed. This cannot be undone.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            DialogButtons(
                action = "Delete",
                variant = ButtonVariant.Danger,
                onConfirm = { onEvent(EmailGroupsEvent.ConfirmDelete) },
                onDismiss = { onEvent(EmailGroupsEvent.DismissDelete) },
            )
        }
    }
}

@Composable
private fun GroupRow(group: EmailGroup, onEvent: (EmailGroupsEvent) -> Unit) {
    SettingsRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = group.name, style = ZillitTheme.typography.bodyMedium)
            // The group's own address, when it has one, is the useful line:
            // it is what goes in a To field.
            ZillitText(
                text = group.address ?: "${group.members.size} member${if (group.members.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitButton(
            text = "Edit",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = { onEvent(EmailGroupsEvent.Edit(group)) },
        )
        ZillitButton(
            text = "Delete",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = { onEvent(EmailGroupsEvent.AskDelete(group)) },
        )
    }
}

/**
 * The editor: a name, and a member list built from typed addresses or crew.
 *
 * The name locks once a group exists (Android `EditEmailGroupActivity.kt:62`):
 * a group can be given its own mailbox, and renaming it would not rename that.
 */
@Composable
private fun GroupEditor(state: EmailGroupsUiState, onEvent: (EmailGroupsEvent) -> Unit) {
    val draft = state.draft ?: return

    SettingsPage(
        title = if (draft.isNew) "New email group" else "Edit email group",
        subtitle = null,
        onBack = { onEvent(EmailGroupsEvent.CancelEdit) },
    ) {
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(EmailGroupsEvent.NameChanged(it)) },
            label = "Group name",
            placeholder = "e.g. Camera department",
            enabled = draft.isNew,
            modifier = Modifier.fillMaxWidth(),
        )

        MemberField(draft, onEvent)

        if (draft.members.isEmpty()) {
            SettingsHint("No members yet. Add at least one address.")
        } else {
            draft.members.forEach { address ->
                SettingsRow {
                    ZillitText(
                        text = address,
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = "Remove $address",
                        onClick = { onEvent(EmailGroupsEvent.RemoveMember(address)) },
                    )
                }
            }
        }

        state.error?.let { message ->
            ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }

        DialogButtons(
            action = if (draft.isNew) "Create" else "Save",
            enabled = draft.canSave,
            loading = state.isSaving,
            onConfirm = { onEvent(EmailGroupsEvent.Save) },
            onDismiss = { onEvent(EmailGroupsEvent.CancelEdit) },
        )
    }
}

/** The address field, with the crew who match under it. */
@Composable
private fun MemberField(draft: GroupDraft, onEvent: (EmailGroupsEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.memberInput,
                onValueChange = { onEvent(EmailGroupsEvent.MemberInputChanged(it)) },
                label = "Members",
                placeholder = "Type an address or a crew name",
                errorText = draft.inputError,
                imeAction = ImeAction.Done,
                onImeAction = { onEvent(EmailGroupsEvent.AddTypedMember) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Add",
                variant = ButtonVariant.Secondary,
                onClick = { onEvent(EmailGroupsEvent.AddTypedMember) },
                modifier = Modifier.padding(top = FIELD_LABEL_OFFSET),
            )
        }
        draft.suggestions.forEach { contact ->
            SuggestionRow(contact) { onEvent(EmailGroupsEvent.AddMember(contact.address)) }
        }
    }
}

/** One crew match under a field. Shared with the BCC page. */
@Composable
internal fun SuggestionRow(contact: EmailContact, onPick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .background(colors.surfaceRaised)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.small)
            .clickable(onClick = onPick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = contact.label, style = ZillitTheme.typography.bodyMedium)
            if (contact.name.isNotBlank()) {
                ZillitText(
                    text = contact.address,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        if (contact.subtitle.isNotBlank()) ZillitTag(label = contact.subtitle)
    }
}

/** Lines the Add button up with a labelled field's input, not its label. */
private val FIELD_LABEL_OFFSET = 20.dp
