package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode

/**
 * The Call activity sheet — Android's `CallActivityDetailSheet`, opened from a
 * row's info affordance: who the call was with, what kind on which line, the
 * three chips (direction, start, duration) and the roster with a status
 * badge per person.
 *
 * Read-only: redialling stays with the row itself, and a dialog that could
 * also ring would be two ways to do one thing.
 */
@Composable
fun CallDetailDialog(
    entry: CallLogEntry,
    selfUserId: String?,
    nameFor: (String) -> String?,
    onDismiss: () -> Unit,
) {
    val title = entry.displayTitle(nameFor)
    ZillitDialogShell(
        title = str(S.txt_call_activity),
        subtitle = entry.detailSubtitle(),
        icon = ZillitIcons.Phone,
        onDismiss = onDismiss,
        visible = true,
        actions = {
            ZillitButton(text = str(S.close), onClick = onDismiss, variant = ButtonVariant.Tertiary)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitAvatar(
                name = title,
                userId = entry.peerUserId.takeIf { entry.mode != CallMode.Group },
                size = HEADER_AVATAR,
            )
            Column {
                ZillitText(text = title, style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = entry.detailSubtitle(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        DetailChips(entry)
        ParticipantList(entry, selfUserId, nameFor)
    }
}

/** Direction, start time and duration — each hidden when the row cannot say. */
@Composable
private fun DetailChips(entry: CallLogEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.surfaceHover)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        ) {
            DirectionMark(entry)
            ZillitText(text = entry.directionLabel(), style = ZillitTheme.typography.labelSmall)
        }
        clock12h(entry.startedAtMillis)?.let { at ->
            Chip(icon = ZillitIcons.Clock, text = at)
        }
        entry.detailDuration()?.let { pretty ->
            Chip(icon = null, text = pretty)
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(ZillitTheme.colors.surfaceHover)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        if (icon != null) {
            ZillitIcon(
                icon = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = CHIP_ICON,
            )
        }
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall)
    }
}

@Composable
private fun ParticipantList(
    entry: CallLogEntry,
    selfUserId: String?,
    nameFor: (String) -> String?,
) {
    val rows = entry.detailParticipants(selfUserId, nameFor)
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = participantsHeading(rows.size),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        rows.forEach { row -> ParticipantRow(row) }
    }
}

@Composable
private fun ParticipantRow(row: CallDetailParticipant) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = row.name, userId = row.userId, size = ROW_AVATAR)
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(text = row.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                if (row.isGuest) ZillitTag(str(S.txt_badge_guest), tone = TagTone.Neutral)
            }
            listOfNotNull(row.subLabel, row.meta).takeIf { it.isNotEmpty() }?.let { lines ->
                ZillitText(
                    text = lines.joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        row.badge?.let { badge -> ZillitTag(badge, tone = badgeTone(badge)) }
    }
}

/** The badge's colour says what the word says: green in, red missed, grey gone. */
private fun badgeTone(badge: String): TagTone = when (badge) {
    str(S.host_txt) -> TagTone.Accent
    str(S.txt_badge_in_call) -> TagTone.Success
    str(S.missed), str(S.declined_events) -> TagTone.Danger
    str(S.txt_ringing) -> TagTone.Warning
    else -> TagTone.Neutral
}

private val HEADER_AVATAR = 44.dp
private val ROW_AVATAR = 28.dp
private val CHIP_ICON = 12.dp
