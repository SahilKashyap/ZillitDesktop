package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * Who is on the call, one row each.
 *
 * This is the surface that carries live per-person state during a *video*
 * call. The video rectangle belongs to the embedded browser and nothing drawn
 * on this side survives inside it, so speaking dots, mute badges and link
 * quality have to live somewhere outside it — which is also why the button
 * that opens this sits in the header rather than behind a menu.
 */
@Composable
fun CallRosterPanel(tiles: List<CallTile>, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val connected = tiles.count { it.presence == CallStatus.InCall }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(colors.surfaceRaised)
            .border(PANEL_BORDER, colors.border, RoundedCornerShape(PANEL_CORNER))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "In call · $connected",
            style = ZillitTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
        val rosterState = rememberLazyListState()
        LazyColumn(
            state = rosterState,
            modifier = Modifier.then(rememberWheelScroll(rosterState)),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            items(tiles, key = CallTile::key) { tile -> RosterRow(tile) }
        }
    }
}

@Composable
private fun RosterRow(tile: CallTile) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = tile.name, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = if (tile.isSelf) "You" else tile.name,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = tile.presenceWord,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        if (tile.media?.speaking == true) {
            Box(
                modifier = Modifier
                    .size(SPEAKING_DOT)
                    .clip(CircleShape)
                    .background(colors.success),
            )
        }
        if (tile.media?.audioMuted == true) {
            ZillitIcon(
                icon = ZillitIcons.MicOff,
                contentDescription = "Muted",
                tint = colors.textMuted,
                size = ROW_ICON,
            )
        }
        tile.media?.quality?.takeIf { it.isTrouble }?.let { NetworkPip(quality = it) }
    }
}

private val CallTile.presenceWord: String
    get() = when (presence) {
        CallStatus.Ringing -> "Ringing…"
        CallStatus.InCall -> if (media == null) "In call" else "Connected"
        CallStatus.Caller -> "Calling"
        CallStatus.Declined -> "Declined"
        CallStatus.NotAnswered -> "No answer"
        CallStatus.Left -> "Left"
        CallStatus.Ended -> "Ended"
    }

val ROSTER_WIDTH = 280.dp
private val PANEL_CORNER = 16.dp
private val PANEL_BORDER = 1.dp
private val ROW_HEIGHT = 40.dp
private val ROW_AVATAR = 28.dp
private val ROW_ICON = 14.dp
private val SPEAKING_DOT = 6.dp
