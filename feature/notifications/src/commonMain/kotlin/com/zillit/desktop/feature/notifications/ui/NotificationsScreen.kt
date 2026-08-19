package com.zillit.desktop.feature.notifications.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.notifications.domain.ProjectNotification

/**
 * The production's notifications: newest first, one card a row, a delete on
 * each and a "Delete all" in the header — Android's `NotificationActivity`
 * with the web drawer's card layout.
 */
@Composable
fun NotificationsScreen(state: NotificationsUiState, onEvent: (NotificationsEvent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Header(state, onEvent)
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(NotificationsEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            when {
                state.loading && state.rows.isEmpty() ->
                    Box(Modifier.fillMaxWidth(), Alignment.Center) { ZillitSpinner() }
                state.loaded && state.rows.isEmpty() -> ZillitEmptyState(
                    title = "No notifications",
                    message = "Nothing has been posted to this production's notification list.",
                    icon = ZillitIcons.Bell,
                )
                else -> NotificationList(state, onEvent)
            }
        }
        state.confirm?.let { ConfirmDialog(it, onEvent) }
    }
}

@Composable
private fun Header(state: NotificationsUiState, onEvent: (NotificationsEvent) -> Unit) {
    ZillitPageHeader(
        title = "Notifications",
        description = "Everything the production has sent you, newest first.",
        actions = {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = "Refresh notifications",
                onClick = { onEvent(NotificationsEvent.Refresh) },
                enabled = !state.loading,
            )
            ZillitButton(
                text = "Delete all",
                onClick = { onEvent(NotificationsEvent.AskDeleteAll) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Trash,
                enabled = state.rows.isNotEmpty() && !state.busy,
                loading = state.busy,
            )
        },
    )
}

@Composable
private fun NotificationList(state: NotificationsUiState, onEvent: (NotificationsEvent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.rows, key = { it.id }) { row -> NotificationRow(row, state.busy, onEvent) }
        if (state.hasMore || state.loadingMore) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm), Alignment.Center) {
                    if (state.loadingMore) {
                        ZillitSpinner()
                    } else {
                        ZillitButton(
                            text = "Show older",
                            onClick = { onEvent(NotificationsEvent.LoadOlder) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One notification. Clicking the card raises [NotificationsEvent.Open], which
 * is a no-op unless the host wired a destination — Android's tap does nothing.
 */
@Composable
private fun NotificationRow(
    row: ProjectNotification,
    busy: Boolean,
    onEvent: (NotificationsEvent) -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable { onEvent(NotificationsEvent.Open(row.id)) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                if (row.pathLabel.isNotBlank()) {
                    ZillitText(
                        text = row.pathLabel,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.accentText,
                        maxLines = 1,
                    )
                }
                ZillitText(text = row.text, style = ZillitTheme.typography.bodyMedium)
                ZillitText(
                    text = row.timeLabel,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete notification",
                onClick = { onEvent(NotificationsEvent.AskDelete(row.id)) },
                enabled = !busy,
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

/** Android's `showDialogWithButton` texts, verbatim (`NotificationActivity.kt`). */
@Composable
private fun ConfirmDialog(confirm: NotificationsConfirm, onEvent: (NotificationsEvent) -> Unit) {
    val message = when (confirm) {
        is NotificationsConfirm.DeleteOne -> "Are you sure you want to delete this notification?"
        NotificationsConfirm.DeleteAll -> "Are you sure you want to delete all notification?"
    }
    ZillitDialogShell(
        title = "Alert",
        onDismiss = { onEvent(NotificationsEvent.CancelDelete) },
        visible = true,
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "No",
                onClick = { onEvent(NotificationsEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Yes",
                onClick = { onEvent(NotificationsEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(text = message)
    }
}
