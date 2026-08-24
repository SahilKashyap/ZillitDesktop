package com.zillit.desktop.feature.email.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.SavedContact
import com.zillit.desktop.feature.email.ui.DialogButtons
import com.zillit.desktop.feature.email.ui.ModalCard
import com.zillit.desktop.feature.email.ui.settings.SettingsHint
import com.zillit.desktop.feature.email.ui.settings.SettingsMessage
import com.zillit.desktop.feature.email.ui.settings.SettingsRow

/**
 * Contacts — Android's `ContactListActivity`, reached from the mail drawer
 * (`FolderDrawerFragment.kt:70-81`).
 *
 * The address book this mailbox keeps, not the production's crew: people
 * outside Zillit who are written to often enough to be worth remembering.
 * A row's primary action is writing to them, which is what the list is for.
 */
@Composable
internal fun EmailContactsScreen(
    state: EmailContactsUiState,
    onEvent: (EmailContactsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                title = "Contacts",
                description = "The addresses you write to, kept with your mailbox.",
                actions = {
                    ZillitButton(
                        text = "New contact",
                        leadingIcon = ZillitIcons.Add,
                        size = ButtonSize.Small,
                        onClick = { onEvent(EmailContactsEvent.Edit(null)) },
                    )
                },
            )

            state.error?.let { message ->
                SettingsMessage(message, StatusTone.Rejected, onDismiss = { onEvent(EmailContactsEvent.DismissError) })
            }

            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(EmailContactsEvent.QueryChanged(it)) },
                placeholder = "Search name, address or company",
                modifier = Modifier.fillMaxWidth(),
            )

            ContactList(state, onEvent)
        }

        state.draft?.let { draft -> ContactEditor(draft, state.isSaving, onEvent) }
        state.pendingDelete?.let { contact -> DeletePrompt(contact, onEvent) }
    }
}

@Composable
private fun ContactList(state: EmailContactsUiState, onEvent: (EmailContactsEvent) -> Unit) {
    val rows = state.visible
    when {
        state.isLoading && state.contacts.isEmpty() -> SettingsHint("Loading…")

        state.contacts.isEmpty() ->
            SettingsHint("No contacts yet. Anyone you add here shows up as you type an address.")

        rows.isEmpty() -> SettingsHint("Nothing matches \"${state.query.trim()}\".")

        else -> {
            val list = rememberLazyListState()
            ZillitLazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                items(rows, key = { it.id.ifBlank { it.address } }) { contact ->
                    ContactRow(contact, onEvent)
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: SavedContact, onEvent: (EmailContactsEvent) -> Unit) {
    SettingsRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = contact.displayName, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = listOfNotNull(
                    contact.address,
                    contact.company.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitButton(
            text = "Write",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = { onEvent(EmailContactsEvent.WriteTo(contact)) },
        )
        ZillitIconButton(
            icon = ZillitIcons.Edit,
            contentDescription = "Edit ${contact.displayName}",
            onClick = { onEvent(EmailContactsEvent.Edit(contact)) },
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Delete ${contact.displayName}",
            onClick = { onEvent(EmailContactsEvent.AskDelete(contact)) },
        )
    }
}

@Composable
private fun ContactEditor(draft: ContactDraft, isSaving: Boolean, onEvent: (EmailContactsEvent) -> Unit) {
    val contact = draft.contact
    ModalCard(onDismiss = { onEvent(EmailContactsEvent.CancelEdit) }) {
        ZillitText(
            text = if (draft.isNew) "New contact" else "Edit contact",
            style = ZillitTheme.typography.titleMedium,
        )
        draft.error?.let { message ->
            ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }
        ZillitTextField(
            value = contact.address,
            onValueChange = { onEvent(EmailContactsEvent.DraftChanged(contact.copy(address = it))) },
            label = "Email address",
            placeholder = "name@example.com",
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = contact.firstName,
                onValueChange = { onEvent(EmailContactsEvent.DraftChanged(contact.copy(firstName = it))) },
                label = "First name",
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = contact.lastName,
                onValueChange = { onEvent(EmailContactsEvent.DraftChanged(contact.copy(lastName = it))) },
                label = "Last name",
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            )
        }
        ContactExtras(contact, isSaving, onEvent)
        DialogButtons(
            action = if (draft.isNew) "Add contact" else "Save",
            enabled = contact.address.isNotBlank() && !isSaving,
            loading = isSaving,
            onConfirm = { onEvent(EmailContactsEvent.Save) },
            onDismiss = { onEvent(EmailContactsEvent.CancelEdit) },
        )
    }
}

/** Company and phone — the two fields nobody fills in first. */
@Composable
private fun ContactExtras(contact: SavedContact, isSaving: Boolean, onEvent: (EmailContactsEvent) -> Unit) {
    ZillitTextField(
        value = contact.company,
        onValueChange = { onEvent(EmailContactsEvent.DraftChanged(contact.copy(company = it))) },
        label = "Company",
        enabled = !isSaving,
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitTextField(
        value = contact.phone,
        onValueChange = { onEvent(EmailContactsEvent.DraftChanged(contact.copy(phone = it))) },
        label = "Phone",
        enabled = !isSaving,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeletePrompt(contact: SavedContact, onEvent: (EmailContactsEvent) -> Unit) {
    ModalCard(onDismiss = { onEvent(EmailContactsEvent.DismissDelete) }) {
        ZillitText(text = "Delete ${contact.displayName}?", style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = "${contact.address} will no longer be suggested as you type.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        DialogButtons(
            action = "Delete",
            variant = ButtonVariant.Danger,
            onConfirm = { onEvent(EmailContactsEvent.ConfirmDelete) },
            onDismiss = { onEvent(EmailContactsEvent.DismissDelete) },
        )
    }
}
