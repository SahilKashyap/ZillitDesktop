package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Chat for the duration of the call, and no longer.
 *
 * Deliberately not the room's thread. These lines are a side channel for the
 * people currently on the call — a link, a name, a "one minute" — and they are
 * never written anywhere: no history, no unread state that survives, nothing
 * to come back to. Anything worth keeping goes in the room afterwards, which
 * is the same bargain the phones and the web client strike.
 *
 * The empty state says so out loud, because a chat box that silently discards
 * what was typed into it would be a trap.
 */
@Composable
fun CallChatPanel(
    lines: List<CallChatLine>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Why this user cannot write — the host turned chat off, or blocked
     * them — shown in the composer's place. Null when they can. The web's
     * `ChatPanel` does the same: reading stays, sending goes.
     */
    lockedReason: String? = null,
) {
    val colors = ZillitTheme.colors
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follows the conversation. Keyed on the count so it only moves when
    // something arrives, leaving a user who scrolled up to re-read alone
    // until the next line.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(CHAT_CORNER))
            .background(colors.surfaceRaised)
            .border(CHAT_BORDER, colors.border, RoundedCornerShape(CHAT_CORNER))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "Call chat",
            style = ZillitTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
        ZillitText(
            text = "Only for this call — nothing here is saved.",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                ZillitText(
                    text = "No messages yet.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ZillitLazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    items(lines, key = CallChatLine::id) { line -> ChatLineRow(line) }
                }
            }
        }

        if (lockedReason != null) {
            ZillitText(
                text = lockedReason,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
            )
        } else {
            Composer(draft = draft, onDraft = { draft = it }, onSend = {
                onSend(draft)
                draft = ""
            })
        }
    }
}

/** The field and its send. Enter sends, matching every chat surface in the app. */
@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val colors = ZillitTheme.colors
    ZillitTextField(
        value = draft,
        onValueChange = onDraft,
        placeholder = "Message the call",
        singleLine = true,
        maxLength = CHAT_TEXT_LIMIT,
        imeAction = ImeAction.Send,
        onImeAction = onSend,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            ZillitIcon(
                icon = ZillitIcons.Send,
                contentDescription = "Send",
                tint = if (draft.isBlank()) colors.textDisabled else colors.accent,
                size = SEND_ICON,
                modifier = Modifier.clickable(enabled = draft.isNotBlank(), onClick = onSend),
            )
        },
    )
}

/**
 * One line, leaning to its sender's side.
 *
 * Own lines carry no name — in a panel this narrow the label would cost more
 * width than it explains, and the side already says who sent it.
 */
@Composable
private fun ChatLineRow(line: CallChatLine) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX)
                .clip(RoundedCornerShape(BUBBLE_CORNER))
                .background(if (line.fromSelf) colors.accentSoft else colors.surfaceHover)
                .padding(
                    horizontal = ZillitTheme.spacing.sm,
                    vertical = ZillitTheme.spacing.xs,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            if (!line.fromSelf && line.name.isNotBlank()) {
                ZillitText(
                    text = line.name,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = line.text,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
            )
        }
    }
}

/** Matches the coordinator's outbound cap, so the field cannot outrun the wire. */
private const val CHAT_TEXT_LIMIT = 1_000
private val CHAT_CORNER = 12.dp
private val CHAT_BORDER = 1.dp
private val BUBBLE_CORNER = 10.dp
private val BUBBLE_MAX = 220.dp
private val SEND_ICON = 18.dp
