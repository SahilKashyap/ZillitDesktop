package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallType

/**
 * A second ring while we are on a call — the web's compact banner
 * (`Line2Presence.jsx`, `CallOverlays.tsx:100-130`): who, from where, and
 * two answers. Never the full ring card, never the full ringtone: a soft
 * chime and a strip, over a call that carries on until the user decides.
 */
@Composable
fun CallSecondCallBanner(waiting: CallSession, onEvent: (CallEvent) -> Unit, modifier: Modifier = Modifier) {
    val what = buildString {
        append(if (waiting.type == CallType.Video) "Video call" else "Call")
        val where = if (waiting.mode == CallMode.Group) waiting.title else ""
        if (where.isNotBlank()) append(" · ").append(where)
    }
    Row(
        modifier = modifier
            .widthIn(max = BANNER_MAX_WIDTH)
            .shadow(BANNER_ELEVATION, RoundedCornerShape(BANNER_CORNER))
            .clip(RoundedCornerShape(BANNER_CORNER))
            .background(CallPalette.menu)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = waiting.callerName.ifBlank { "?" }, userId = waiting.displayUserId, size = AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = "${waiting.callerName.ifBlank { "Someone" }} is calling",
                style = ZillitTheme.typography.titleSmall,
                color = CallPalette.text,
                maxLines = 1,
            )
            ZillitText(text = what, style = ZillitTheme.typography.labelSmall, color = CallPalette.muted, maxLines = 1)
        }
        ZillitButton(
            text = "Decline",
            size = ButtonSize.Small,
            variant = ButtonVariant.Tertiary,
            onClick = { onEvent(CallEvent.DeclineSecondCall) },
        )
        ZillitButton(
            text = "End & Accept",
            size = ButtonSize.Small,
            onClick = { onEvent(CallEvent.EndAndAcceptSecondCall) },
        )
    }
}

private val BANNER_MAX_WIDTH = 520.dp
private val BANNER_CORNER = 14.dp
private val BANNER_ELEVATION = 10.dp
private val AVATAR = 36.dp
