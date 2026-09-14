package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.email.domain.isDeletable
import com.zillit.desktop.feature.email.domain.message

/**
 * Creating or renaming a folder.
 *
 * One dialog for both: they differ only in their title, their button and
 * whether the field starts filled. Two dialogs would be two places to fix the
 * validation message.
 */
@Composable
internal fun FolderDialog(
    edit: FolderEdit,
    onEvent: (EmailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalCard(onDismiss = { onEvent(EmailEvent.DismissFolderEdit) }, modifier = modifier) {
        ZillitText(text = edit.title, style = ZillitTheme.typography.titleMedium)

        ZillitTextField(
            value = edit.name,
            onValueChange = { onEvent(EmailEvent.FolderNameChanged(it)) },
            placeholder = "Folder name",
            errorText = edit.error?.message,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Delete lives here rather than as a second icon on the folder row:
            // two hover targets in an 8dp column is how a sidebar becomes
            // fiddly, and this dialog is already "edit this folder".
            if (edit.renaming?.isDeletable == true) {
                ZillitButton(
                    text = "Delete",
                    variant = ButtonVariant.Tertiary,
                    onClick = { onEvent(EmailEvent.DeleteFolder()) },
                )
            }

            Spacer(Modifier.weight(1f))

            DialogButtons(
                action = edit.action,
                // Disabled on an empty name rather than letting the press
                // report the obvious. Everything else is worth saying out loud.
                enabled = edit.name.isNotBlank(),
                loading = edit.isSaving,
                onConfirm = { onEvent(EmailEvent.SaveFolder) },
                onDismiss = { onEvent(EmailEvent.DismissFolderEdit) },
            )
        }
    }
}
