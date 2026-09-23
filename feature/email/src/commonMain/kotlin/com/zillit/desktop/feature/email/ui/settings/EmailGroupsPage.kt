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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
        title = str(S.email_groups),
        subtitle = str(S.desktop_email_groups_subtitle),
        onBack = onBack,
        actions = {
            ZillitButton(
                text = str(S.desktop_new_group),
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
            state.isLoading -> SettingsHint(str(S.ah_loading))
            state.groups.isEmpty() ->
                SettingsHint(str(S.desktop_email_no_groups_yet))
            else -> state.groups.forEach { group -> GroupRow(group, onEvent) }
        }
    }

    state.pendingDelete?.let { group ->
        ModalCard(onDismiss = { onEvent(EmailGroupsEvent.DismissDelete) }) {
            ZillitText(
                text = str(S.drive_delete_item_title_format, group.name),
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = str(S.desktop_email_delete_group_body),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            DialogButtons(
                action = str(S.delete),
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
                text = group.address ?: when (group.members.size) {
                    1 -> str(S.desktop_email_one_member)
                    else -> str(S.desktop_email_n_members, group.members.size)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitButton(
            text = str(S.edit),
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = { onEvent(EmailGroupsEvent.Edit(group)) },
        )
        ZillitButton(
            text = str(S.delete),
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
        title = str(if (draft.isNew) S.desktop_email_new_email_group else S.edit_email_group),
        subtitle = null,
        onBack = { onEvent(EmailGroupsEvent.CancelEdit) },
    ) {
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(EmailGroupsEvent.NameChanged(it)) },
            label = str(S.mtg_group_name_hint),
            placeholder = str(S.desktop_email_group_name_placeholder),
            enabled = draft.isNew,
            modifier = Modifier.fillMaxWidth(),
        )

        MemberField(draft, onEvent)

        if (draft.members.isEmpty()) {
            SettingsHint(str(S.desktop_email_no_members_yet))
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
                        contentDescription = str(S.bs_chip_remove, address),
                        onClick = { onEvent(EmailGroupsEvent.RemoveMember(address)) },
                    )
                }
            }
        }

        state.error?.let { message ->
            ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }

        DialogButtons(
            action = str(if (draft.isNew) S.create else S.save),
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
                label = str(S.members),
                placeholder = str(S.desktop_email_members_placeholder),
                errorText = draft.inputError,
                imeAction = ImeAction.Done,
                onImeAction = { onEvent(EmailGroupsEvent.AddTypedMember) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.add),
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
