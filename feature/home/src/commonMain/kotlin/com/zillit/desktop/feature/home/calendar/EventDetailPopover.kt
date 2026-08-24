package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * One event, in full.
 *
 * A port of the web's `EventDetailPopover`. It is the calendar's read surface:
 * everything about an event that a grid block cannot fit — where it is, who
 * made it, who is coming, and what this user is expected to do about it.
 *
 * Editing is not here. The web's event form is its own several-hundred-line
 * component and its own task; this offers the actions that need no form —
 * accepting, declining, and deleting.
 */
@Composable
internal fun EventDetailPopover(
    detail: EventDetailState,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
            // A barrier, not a control.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEvent(CalendarEvent2Event.CloseDetail) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = POPOVER_WIDTH)
                .heightIn(max = POPOVER_MAX_HEIGHT)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            DetailHeader(detail, onEvent)

            ZillitScrollColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                DetailRows(detail)
            }

            DetailActions(detail, onEvent)
        }
    }
}

@Composable
private fun DetailHeader(detail: EventDetailState, onEvent: (CalendarEvent2Event) -> Unit) {
    val colors = ZillitTheme.colors
    val event = detail.event

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // Vertical stripe indicator
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(ZillitTheme.shapes.pill)
                .background(colors.accent)
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = event.title,
                style = ZillitTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (event.isCancelled) TextDecoration.LineThrough else null,
                ),
            )

            val tags = listOfNotNull(
                "Cancelled".takeIf { event.isCancelled },
                "Repeats".takeIf { event.isRecurring },
                "Finished".takeIf { detail.hasFinished && !event.isCancelled },
            )
            if (tags.isNotEmpty()) {
                ZillitText(
                    text = tags.joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close",
            onClick = { onEvent(CalendarEvent2Event.CloseDetail) },
            modifier = Modifier.offset(y = (-4).dp)
        )
    }
}

/** Everything the grid block could not fit. */
@Composable
private fun DetailRows(detail: EventDetailState) {
    val event = detail.event

    InfoRow(
        icon = ZillitIcons.Calendar,
        primary = detail.dateLabel,
        secondary = detail.timeLabel,
    )

    durationLabel(event.startMillis, event.endMillis).takeIf { it.isNotBlank() }?.let { duration ->
        InfoRow(icon = ZillitIcons.Reload, primary = duration, secondary = "Duration")
    }

    event.location?.takeIf { it.isNotBlank() }?.let { location ->
        InfoRow(icon = ZillitIcons.Info, primary = location, secondary = "Location")
    }

    if (event.hasCall) {
        InfoRow(icon = ZillitIcons.Monitor, primary = "Video call", secondary = "Joining is on the call sheet")
    }

    if (event.reminderMinutes > 0) {
        InfoRow(
            icon = ZillitIcons.Info,
            primary = reminderLabel(event.reminderMinutes),
            secondary = "Reminder",
        )
    }

    event.creatorName?.takeIf { it.isNotBlank() }?.let { creator ->
        InfoRow(icon = ZillitIcons.User, primary = creator, secondary = "Created by")
    }

    if (event.invitedCount > 0) {
        InfoRow(
            icon = ZillitIcons.User,
            primary = "${event.invitedCount} invited",
            secondary = "Invitees",
        )
    }

    event.description?.takeIf { it.isNotBlank() }?.let { description ->
        ZillitText(
            text = description,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.padding(vertical = ZillitTheme.spacing.sm),
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, primary: String, secondary: String) {
    val colors = ZillitTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = icon,
            contentDescription = null,
            tint = colors.textMuted,
            size = ROW_ICON,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = primary, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = secondary,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * What this user can do about it.
 *
 * Nothing at all for an event they neither own nor were invited to, which is
 * the common case for most of a production's calendar — so the whole bar is
 * absent rather than showing disabled buttons.
 */
@Composable
private fun DetailActions(detail: EventDetailState, onEvent: (CalendarEvent2Event) -> Unit) {
    val colors = ZillitTheme.colors
    val statusLine = detail.event.inviteStatus.message

    if (!detail.permissions.canManage && !detail.permissions.canRespond && statusLine == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        statusLine?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }

        ActionButtons(detail, onEvent)

        detail.error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun ActionButtons(detail: EventDetailState, onEvent: (CalendarEvent2Event) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (detail.permissions.canRespond) {
            // Only the answer they have not already given. Offering "Accept" to
            // someone who has accepted is a button that does nothing.
            if (detail.event.inviteStatus != InviteStatus.Accepted) {
                ZillitButton(
                    text = "Accept",
                    size = ButtonSize.Small,
                    loading = detail.isBusy,
                    onClick = { onEvent(CalendarEvent2Event.RespondToInvite(accept = true)) },
                )
            }
            if (detail.event.inviteStatus != InviteStatus.Rejected) {
                ZillitButton(
                    text = "Decline",
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    loading = detail.isBusy,
                    onClick = { onEvent(CalendarEvent2Event.RespondToInvite(accept = false)) },
                )
            }
        }

        if (detail.permissions.canManage) {
            ZillitButton(
                text = "Edit",
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                onClick = { onEvent(CalendarEvent2Event.OpenForm(detail.event)) },
            )
            ZillitButton(
                text = "Delete",
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = detail.isBusy,
                onClick = { onEvent(CalendarEvent2Event.AskDeleteEvent) },
            )
        }
    }
}

private val POPOVER_WIDTH = 480.dp
private val POPOVER_MAX_HEIGHT = 620.dp
private val ROW_ICON = 16.dp
private const val SCRIM_ALPHA = 0.55f
