package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallType

/**
 * The call history, as a list.
 *
 * Lives in `feature:calls` rather than in the chat tool it appears inside: the
 * chat module knows nothing about calls, and the app composes the two. Same
 * arrangement as the call buttons in the thread header.
 */
@Composable
fun CallLogPane(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    nowMillis: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitChoiceChip(
                label = "All",
                selected = !state.missedOnly,
                onClick = { onEvent(CallLogEvent.ShowAll) },
            )
            ZillitChoiceChip(
                label = "Missed",
                selected = state.missedOnly,
                onClick = { onEvent(CallLogEvent.ShowMissed) },
            )
        }

        when {
            state.entries.isEmpty() && state.isLoading -> PaneNote("Loading calls…")
            state.entries.isEmpty() && state.missedOnly -> PaneNote("No missed calls.")
            state.entries.isEmpty() -> PaneNote("No calls yet.")
            else -> CallLogList(state, onEvent, nameFor, nowMillis)
        }
    }
}

@Composable
private fun CallLogList(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    nowMillis: Long,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        items(state.entries, key = CallLogEntry::callUuid) { entry ->
            CallLogRow(entry, nameFor, nowMillis) { onEvent(CallLogEvent.Redial(entry)) }
        }
        if (state.canLoadMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEvent(CallLogEvent.LoadMore) }
                        .padding(ZillitTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = if (state.isLoading) "Loading…" else "Show older",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.accentText,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogEntry,
    nameFor: (String) -> String?,
    nowMillis: Long,
    onRedial: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val title = entry.displayTitle(nameFor)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_CORNER))
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            // Only rows that can actually ring something are pressable; a row
            // whose peer left the production has nothing to redial.
            .then(if (entry.isRedialable) Modifier.clickable(onClick = onRedial) else Modifier)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = title, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyMedium,
                // A missed call is the one row worth finding at a glance.
                color = if (entry.missed) colors.danger else colors.textPrimary,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                DirectionMark(entry)
                ZillitText(
                    text = entry.subtitle(nowMillis),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        // Under the cursor a redialable row says what a click does; at rest
        // it says what the call was. The two never show together — the phone
        // replaces the camera glyph rather than crowding it.
        when {
            hovered && entry.isRedialable -> ZillitIcon(
                icon = ZillitIcons.Phone,
                contentDescription = "Call again",
                tint = colors.success,
                size = ROW_ICON,
            )

            entry.type == CallType.Video -> ZillitIcon(
                icon = ZillitIcons.Camera,
                contentDescription = "Video call",
                tint = colors.textMuted,
                size = ROW_ICON,
            )
        }
    }
}

/**
 * The direction, on a tinted disc: red for missed, green for answered
 * incoming, the app accent for outgoing. The colour is the summary — the
 * list can be triaged without reading a word.
 */
@Composable
private fun DirectionMark(entry: CallLogEntry) {
    val colors = ZillitTheme.colors
    val outgoing = entry.direction == CallLogDirection.Outgoing
    val disc = when {
        entry.missed -> colors.dangerSoft
        outgoing -> colors.accentSoft
        else -> colors.successSoft
    }
    val glyph = when {
        entry.missed -> colors.danger
        outgoing -> colors.accentText
        else -> colors.success
    }
    Box(
        modifier = Modifier
            .size(DIRECTION_DISC)
            .clip(CircleShape)
            .background(disc),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = if (outgoing) ZillitIcons.ArrowRight else ZillitIcons.ArrowLeft,
            contentDescription = when {
                entry.missed -> "Missed"
                outgoing -> "Outgoing"
                else -> "Incoming"
            },
            tint = glyph,
            size = DIRECTION_GLYPH,
        )
    }
}

@Composable
private fun PaneNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val ROW_CORNER = 10.dp
private val DIRECTION_DISC = 18.dp
private val DIRECTION_GLYPH = 11.dp
private val ROW_AVATAR = 32.dp
private val ROW_ICON = 14.dp
