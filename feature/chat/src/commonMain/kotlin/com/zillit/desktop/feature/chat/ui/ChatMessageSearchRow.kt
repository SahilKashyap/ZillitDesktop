package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.chat.data.MessageHit
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.chatTimeLabel

/**
 * One search hit resolved to a conversation the listing can actually open
 * (QA#12): the room it belongs to, or the person. Exactly one of [room] and
 * [contact] is set.
 */
internal data class ChatMessageSearchRow(
    val hit: MessageHit,
    val room: GroupRoom?,
    val contact: CrewContact?,
) {
    val title: String get() = room?.name ?: contact?.fullName.orEmpty()
}

/**
 * Resolution rule for the "Messages" section's rows: a room in [groups]
 * wins — the data layer's [MessageHit.isGroup] is best-effort, because a
 * thread restored from disk lost the flag — else a person in [crew]. A hit
 * whose conversation resolves to neither (a peer who left, a room the user
 * lost) is dropped rather than rendered unopenable.
 */
internal fun messageHitRows(
    hits: List<MessageHit>,
    groups: List<GroupRoom>,
    crew: List<CrewContact>,
): List<ChatMessageSearchRow> = hits.mapNotNull { hit ->
    val room = groups.firstOrNull { it.id == hit.peerId }
    val contact = if (room == null) crew.firstOrNull { it.userId == hit.peerId } else null
    if (room == null && contact == null) null else ChatMessageSearchRow(hit, room, contact)
}

/** The section label over the matched-message rows. */
@Composable
internal fun MessageHitsHeader() {
    ZillitText(
        text = "Messages",
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(
            top = ZillitTheme.spacing.sm,
            bottom = ZillitTheme.spacing.xxs,
        ),
    )
}

/**
 * One matched message: the conversation's name, the snippet, the clock.
 * Clicking opens the thread through the same events the plain rows use.
 * Scroll-to-the-matched-message is deliberately OUT of scope — the thread
 * pane owns scroll and pagination, and the hit's job is to find the
 * conversation, which opens at its newest page as always.
 */
@Composable
internal fun MessageHitRowItem(
    row: ChatMessageSearchRow,
    nowMillis: Long,
    onEvent: (ChatEvent) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) ZillitTheme.colors.surfaceHover else ZillitTheme.colors.surface)
            .hoverable(interaction)
            .clickable {
                row.room?.let { onEvent(ChatEvent.OpenGroup(it)) }
                    ?: row.contact?.let { onEvent(ChatEvent.OpenThread(it)) }
            }
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Chat,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = HIT_ICON,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = row.title,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ZillitText(
                    text = " ${chatTimeLabel(row.hit.timestampMillis, nowMillis)}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = row.hit.snippet,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

private val HIT_ICON = 18.dp
