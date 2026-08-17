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

/**
 * Asks before something irreversible.
 *
 * Only two things in this module reach here — destroying mail already in Trash,
 * and emptying Trash — and both are unrecoverable. Everything else, including
 * an ordinary delete, moves to Trash and needs no dialog: a confirmation on a
 * reversible action just trains people to dismiss confirmations.
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
        ZillitButton(text = "Cancel", variant = ButtonVariant.Tertiary, onClick = onDismiss)
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
        is PendingConfirm.Destroy -> if (messageIds.size == 1) {
            "Delete this message forever?"
        } else {
            "Delete ${messageIds.size} messages forever?"
        }
        PendingConfirm.EmptyTrash -> "Empty Trash?"
        is PendingConfirm.DeleteFolder -> "Delete \"${folder.displayName}\"?"
    }

private val PendingConfirm.body: String
    get() = when (this) {
        is PendingConfirm.Destroy -> "This cannot be undone."
        PendingConfirm.EmptyTrash -> "Everything in Trash will be deleted. This cannot be undone."
        // Says what is at stake without overstating what is known: the cached
        // count is a floor, since a partly synced folder holds more.
        is PendingConfirm.DeleteFolder -> if (cachedCount > 0) {
            "The folder and the mail in it will be deleted — at least $cachedCount " +
                "message${if (cachedCount == 1) "" else "s"}. This cannot be undone."
        } else {
            "The folder and any mail in it will be deleted. This cannot be undone."
        }
    }

private val PendingConfirm.action: String
    get() = when (this) {
        is PendingConfirm.Destroy -> "Delete forever"
        PendingConfirm.EmptyTrash -> "Empty Trash"
        is PendingConfirm.DeleteFolder -> "Delete folder"
    }

private val DIALOG_WIDTH = 420.dp
private const val SCRIM_ALPHA = 0.55f
