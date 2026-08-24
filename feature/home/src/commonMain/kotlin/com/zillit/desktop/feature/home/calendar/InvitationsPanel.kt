package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Received invitations — the web's `RecivedEvents` modal, as a card over the
 * calendar: a tab per answer status, a row per invitation, and the answer
 * buttons where the answer is still owed.
 */
@Composable
internal fun InvitationsPanel(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    ZillitDialogShell(
        title = "Invitations",
        subtitle = "Answers the organisers are waiting on.",
        icon = ZillitIcons.Calendar,
        visible = state.invitationsOpen,
        onDismiss = { onEvent(CalendarEvent2Event.HideInvitations) },
        width = PANEL_WIDTH,
        // This body scrolls its own list. A second scroll from the shell would
        // measure that LazyColumn against an unbounded height, which Compose
        // refuses outright.
        scrollable = false,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            InvitationStatus.entries.forEach { status ->
                StatusTab(
                    status = status,
                    selected = status == state.invitationStatus,
                    onClick = { onEvent(CalendarEvent2Event.SetInvitationTab(status)) },
                )
            }
        }

        when {
            state.invitationsBusy && state.invitations.isEmpty() -> EmptyLine("Loading…")
            state.invitations.isEmpty() -> EmptyLine(state.invitationStatus.emptyLine())
            else -> {
                val inviteState = rememberLazyListState()
                ZillitLazyColumn(
                    state = inviteState,
                    modifier = Modifier.heightIn(max = PANEL_LIST_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    items(state.invitations, key = EventInvitation::id) { invitation ->
                        InvitationRow(invitation, state, onEvent)
                    }
                    // The envelope said there is another page.
                    if (state.invitationsCursor != null) {
                        item {
                            ZillitButton(
                                text = if (state.invitationsBusy) "Loading…" else "Load more",
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                                enabled = !state.invitationsBusy,
                                onClick = { onEvent(CalendarEvent2Event.LoadMoreInvitations) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTab(status: InvitationStatus, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(
                if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.canvas,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = status.label,
            style = ZillitTheme.typography.labelSmall,
            color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun InvitationRow(
    invitation: EventInvitation,
    state: CalendarUiState,
    onEvent: (CalendarEvent2Event) -> Unit,
) {
    val event = invitation.event
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.canvas)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            event?.let { Box(Modifier.size(EVENT_DOT).clip(CircleShape).background(it.tint())) }
            ZillitText(
                text = event?.title ?: "This event no longer exists",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (event != null) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        event?.let {
            ZillitText(
                text = "${it.dateLabel(state.zone)} · ${it.timeLabel(state.zone)}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            it.creatorName?.let { name ->
                ZillitText(
                    text = "Invited by $name",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }

        invitation.rejectionReason?.takeIf { it.isNotBlank() }?.let { reason ->
            ZillitText(
                text = "Declined: $reason",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }

        // Only an unanswered invitation for a live event can be answered.
        // Android's detail sheet (EventDetailBottomSheet.kt:142-503) drops
        // both buttons for a cancelled event and for one past its end
        // (`isExpired = endDatetime < now`, wording `event_expired`); the
        // list here follows, on the same midnight-today clock the popover
        // uses so a row does not flip while it is being read.
        if (invitation.status == InvitationStatus.Pending && event != null) {
            val now = state.today.startOfDayMillis(state.zone)
            when {
                event.isCancelled -> AnswerNote("This event has been cancelled.")
                event.hasFinished(now) -> AnswerNote("This event has expired.")
                else -> AnswerButtons(invitation, enabled = !state.invitationsBusy, onEvent)
            }
        }
    }
}

/** Why an unanswered invitation cannot be answered — in place of the buttons. */
@Composable
private fun AnswerNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AnswerButtons(
    invitation: EventInvitation,
    enabled: Boolean,
    onEvent: (CalendarEvent2Event) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
    ) {
        ZillitButton(
            text = "Decline",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = enabled,
            onClick = { onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = false)) },
        )
        ZillitButton(
            text = "Accept",
            size = ButtonSize.Small,
            enabled = enabled,
            onClick = { onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = true)) },
        )
    }
}

/**
 * The web's `RejectReasonPopUp`: a short optional note the organiser reads.
 *
 * Optional deliberately — iOS sends the reason only when one was written, and
 * requiring prose to say no turns declining into a chore people defer.
 */
@Composable
internal fun DeclineReasonDialog(
    declining: EventInvitation?,
    busy: Boolean,
    onEvent: (CalendarEvent2Event) -> Unit,
) {
    // The invitation being declined survives the exit animation — the state
    // goes null on dismiss, but the title must not blank while fading out.
    val shown = remember { mutableStateOf<EventInvitation?>(null) }
    if (declining != null) shown.value = declining
    val current = shown.value

    var reason by remember(current?.id) { mutableStateOf("") }

    ZillitDialogShell(
        title = "Decline \"${current?.event?.title ?: "this event"}\"?",
        subtitle = "A short note helps the organiser plan around you.",
        visible = declining != null,
        onDismiss = { onEvent(CalendarEvent2Event.CancelDecline) },
        width = PANEL_WIDTH,
    ) {
        ZillitTextField(
            value = reason,
            onValueChange = { reason = it },
            placeholder = "Reason (optional) — the organiser will see it",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(CalendarEvent2Event.CancelDecline) },
            )
            ZillitButton(
                text = "Decline",
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !busy,
                onClick = { onEvent(CalendarEvent2Event.ConfirmDecline(reason)) },
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(vertical = ZillitTheme.spacing.md),
    )
}

private fun InvitationStatus.emptyLine(): String = when (this) {
    InvitationStatus.Pending -> "Nothing waiting for an answer."
    InvitationStatus.Accepted -> "No accepted invitations."
    InvitationStatus.Rejected -> "No declined invitations."
    InvitationStatus.Expired -> "No expired invitations."
}

private val PANEL_WIDTH = 420.dp
private val PANEL_LIST_HEIGHT = 380.dp
private val EVENT_DOT = 8.dp
