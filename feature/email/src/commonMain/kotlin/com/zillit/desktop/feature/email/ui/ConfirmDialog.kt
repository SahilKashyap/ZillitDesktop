package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Asks before a delete.
 *
 * The web asks before every one of them — "Are you sure you want to delete
 * the selected emails?" even for a move to Trash (`DeleteConfirmModal`) —
 * and this follows it: the same words for the same action on both clients.
 * Destroying mail already in Trash, emptying Trash and deleting a folder
 * say so in their own words, because those cannot be undone.
 */
@Composable
internal fun ConfirmDialog(
    pending: PendingConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalCard(onDismiss = onDismiss, modifier = modifier) {
        ZillitText(text = pending.title, style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = pending.body,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        DialogButtons(
            action = pending.action,
            // Says what will happen, not "OK". A destructive button labelled
            // with its consequence is the last chance to notice this is not
            // the dialog you thought it was.
            variant = ButtonVariant.Danger,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The shell every dialog in this module shares: a scrim, and a card on it.
 *
 * Shared so a second dialog cannot drift on scrim opacity, corner radius or —
 * the one that matters — whether clicks fall through to the screen behind.
 */
@Composable
internal fun ModalCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
            // A barrier, not a control: no ripple, and clicking it dismisses.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = DIALOG_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                // Swallows clicks so they do not reach the scrim behind.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            content = content,
        )
    }
}

@Composable
internal fun DialogButtons(
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
    ) {
        ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, onClick = onDismiss)
        ZillitButton(
            text = action,
            variant = variant,
            enabled = enabled,
            loading = loading,
            onClick = onConfirm,
        )
    }
}

private val PendingConfirm.title: String
    get() = when (this) {
        is PendingConfirm.TrashSelected -> str(S.desktop_email_delete_email_title)
        is PendingConfirm.DeleteDrafts -> str(if (draftIds.size == 1) S.ah_delete_draft else S.delete_drafts)
        is PendingConfirm.DeleteOne -> str(S.desktop_email_confirm_deletion)
        is PendingConfirm.Destroy -> if (messageIds.size == 1) {
            str(S.desktop_email_delete_message_forever)
        } else {
            str(S.desktop_email_delete_n_messages_forever, messageIds.size)
        }
        PendingConfirm.EmptyTrash -> str(S.confirmation_label)
        is PendingConfirm.DeleteFolder -> str(S.drive_delete_item_title_format, folder.displayName)
    }

private val PendingConfirm.body: String
    get() = when (this) {
        is PendingConfirm.TrashSelected -> str(S.desktop_email_delete_selected_emails_confirm)
        is PendingConfirm.DeleteDrafts -> str(S.desktop_email_delete_selected_drafts_confirm)
        is PendingConfirm.DeleteOne -> str(S.are_you_sure_you_want_to_delete)
        is PendingConfirm.Destroy -> str(S.desktop_cannot_be_undone)
        PendingConfirm.EmptyTrash -> str(S.desktop_email_empty_trash_confirm)
        // Says what is at stake without overstating what is known: the cached
        // count is a floor, since a partly synced folder holds more.
        is PendingConfirm.DeleteFolder -> when {
            cachedCount == 1 -> str(S.desktop_email_delete_folder_confirm_one)
            cachedCount > 1 -> str(S.desktop_email_delete_folder_confirm_many, cachedCount)
            else -> str(S.desktop_email_delete_folder_confirm)
        }
    }

private val PendingConfirm.action: String
    get() = when (this) {
        is PendingConfirm.TrashSelected, is PendingConfirm.DeleteDrafts, is PendingConfirm.DeleteOne -> str(S.delete)
        is PendingConfirm.Destroy -> str(S.desktop_delete_forever)
        PendingConfirm.EmptyTrash -> str(S.desktop_email_yes_empty)
        is PendingConfirm.DeleteFolder -> str(S.delete)
    }

private val DIALOG_WIDTH = 420.dp
private const val SCRIM_ALPHA = 0.55f
