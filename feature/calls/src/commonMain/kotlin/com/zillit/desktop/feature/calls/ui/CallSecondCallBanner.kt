package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.domain.declineReplyTarget

/**
 * A second ring while we are on a call — the web's compact banner
 * (`Line2Presence.jsx`, `CallOverlays.tsx:100-130`): who, from where, and
 * the answers. Never the full ring card, never the full ringtone: a soft
 * chime and a strip, over a call that carries on until the user decides.
 *
 * Message opens the same quick replies the ring card offers, underneath the
 * strip rather than over it: the call behind this banner is live, and a panel
 * covering it would hide the people we are still talking to.
 */
@Composable
fun CallSecondCallBanner(waiting: CallSession, onEvent: (CallEvent) -> Unit, modifier: Modifier = Modifier) {
    // Keyed on the waiting call: one ring answered or gone, the next one's
    // banner opens closed rather than inheriting a panel nobody opened.
    var replying by remember(waiting.callUuid) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .widthIn(max = BANNER_MAX_WIDTH)
            .shadow(BANNER_ELEVATION, RoundedCornerShape(BANNER_CORNER))
            .clip(RoundedCornerShape(BANNER_CORNER))
            .background(CallPalette.menu)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        BannerRow(
            waiting = waiting,
            onEvent = onEvent,
            // Nobody to reply to — the Message action would decline while
            // sending nothing, so it is not offered. Nor is it while the panel
            // is open, where it would only restate the heading below it; the
            // panel's own Cancel is the way back.
            canReply = waiting.declineReplyTarget() != null && !replying,
            onReply = { replying = true },
        )
        if (replying) {
            CallQuickReplyPanel(
                // The WAITING call's reply, not the live one's: this banner is
                // about the person ringing in, who is not who we are talking to.
                onSend = { text -> onEvent(CallEvent.DeclineSecondCallWithMessage(text)) },
                onCancel = { replying = false },
            )
        }
    }
}

/** The strip itself: who is ringing, from where, and the answers. */
@Composable
private fun BannerRow(
    waiting: CallSession,
    onEvent: (CallEvent) -> Unit,
    canReply: Boolean,
    onReply: () -> Unit,
) {
    val what = buildString {
        append(if (waiting.type == CallType.Video) str(S.txt_video_call_label) else str(S.call))
        val where = if (waiting.mode == CallMode.Group) waiting.title else ""
        if (where.isNotBlank()) append(" · ").append(where)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = waiting.callerName.ifBlank { "?" }, userId = waiting.displayUserId, size = AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(S.desktop_call_is_calling, waiting.callerName.ifBlank { str(S.history_someone) }),
                style = ZillitTheme.typography.titleSmall,
                color = CallPalette.text,
                maxLines = 1,
            )
            ZillitText(text = what, style = ZillitTheme.typography.labelSmall, color = CallPalette.muted, maxLines = 1)
        }
        ZillitButton(
            text = str(S.decline),
            size = ButtonSize.Small,
            variant = ButtonVariant.Tertiary,
            onClick = { onEvent(CallEvent.DeclineSecondCall) },
        )
        if (canReply) {
            ZillitButton(
                text = str(S.message),
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                onClick = onReply,
            )
        }
        ZillitButton(
            text = str(S.txt_end_accept),
            size = ButtonSize.Small,
            onClick = { onEvent(CallEvent.EndAndAcceptSecondCall) },
        )
    }
}

private val BANNER_MAX_WIDTH = 520.dp
private val BANNER_CORNER = 14.dp
private val BANNER_ELEVATION = 10.dp
private val AVATAR = 36.dp
