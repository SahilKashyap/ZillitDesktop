package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * Who is on the call, one row each.
 *
 * This is the surface that carries live per-person state during a *video*
 * call. The video rectangle belongs to the embedded browser and nothing drawn
 * on this side survives inside it, so speaking dots, mute badges and link
 * quality have to live somewhere outside it — which is also why the button
 * that opens this sits in the header rather than behind a menu.
 *
 * On Line 3 each row also carries the web's `CallUsersPanel` verbs
 * (`CallOverlays.tsx:262-280`): the ⋮ with mute-for-myself, don't-watch,
 * mute-for-everyone, and — for the host — block chat and remove; a Cancel
 * on a still-ringing invite; the Guest chip and the "On hold" line.
 */
@Composable
fun CallRosterPanel(
    tiles: List<CallTile>,
    modifier: Modifier = Modifier,
    state: CallUiState? = null,
    onEvent: (CallEvent) -> Unit = {},
) {
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
        ZillitLazyColumn(
            state = rosterState,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            items(tiles, key = CallTile::key) { tile ->
                Column {
                    RosterRow(tile, state, onEvent)
                    if (state != null && state.rosterMenuFor == tile.userId && tile.userId.isNotBlank()) {
                        RosterMenu(tile, state, onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterRow(tile: CallTile, state: CallUiState?, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val onHold = if (tile.isSelf) state?.onHold == true else tile.onHold
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = tile.name, userId = tile.userId, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = if (tile.isSelf) "You" else tile.name,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (tile.isGuest) ZillitTag("Guest", tone = TagTone.Neutral)
            }
            ZillitText(
                text = when {
                    onHold -> "On hold"
                    tile.designation.isNotBlank() && tile.presence == CallStatus.InCall -> tile.designation
                    else -> tile.presenceWord
                },
                style = ZillitTheme.typography.labelSmall,
                color = if (onHold) colors.warning else colors.textMuted,
                maxLines = 1,
            )
        }
        RosterBadges(tile, state)
        if (state?.session?.provider == CallProvider.LiveKit && !tile.isSelf) RosterActions(tile, onEvent)
    }
}

/** The row's standing facts: my own mute or hide of them first (amber — nobody else is told), then theirs. */
@Composable
private fun RosterBadges(tile: CallTile, state: CallUiState?) {
    val colors = ZillitTheme.colors
    if (state != null && tile.userId in state.line3.deafened) {
        // Amber, against the grey of their own mute below: the web's local badges are amber too.
        ZillitIcon(
            icon = ZillitIcons.MicOff,
            contentDescription = "Muted for you",
            tint = colors.warning,
            size = ROW_ICON,
        )
    }
    if (state != null && tile.userId in state.line3.hidden) {
        ZillitIcon(
            icon = ZillitIcons.Eye,
            contentDescription = "Video hidden by you",
            tint = colors.warning,
            size = ROW_ICON,
        )
    }
    if (tile.hand) {
        ZillitIcon(icon = ZillitIcons.Hand, contentDescription = "Hand raised", tint = colors.warning, size = ROW_ICON)
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
        ZillitIcon(icon = ZillitIcons.MicOff, contentDescription = "Muted", tint = colors.textMuted, size = ROW_ICON)
    }
    tile.media?.quality?.takeIf { it.isTrouble }?.let { NetworkPip(quality = it) }
}

/** Line 3, somebody else: Cancel on a ring still out, ⋮ on someone in the room. */
@Composable
private fun RosterActions(tile: CallTile, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    when {
        tile.presence == CallStatus.Ringing -> ZillitText(
            text = "Cancel",
            style = ZillitTheme.typography.labelSmall,
            color = colors.accent,
            modifier = Modifier.clickable { onEvent(CallEvent.CancelInvite(tile.userId)) },
        )
        tile.presence == CallStatus.InCall && tile.userId.isNotBlank() ->
            Box(modifier = Modifier.clickable { onEvent(CallEvent.ToggleRosterMenu(tile.userId)) }) {
                ZillitIcon(
                    icon = ZillitIcons.MoreHorizontal,
                    contentDescription = "Options",
                    tint = colors.textMuted,
                    size = ROW_ICON,
                )
            }
    }
}

/** The web's row menu, verbatim: local first, then what the host may do to them. */
@Composable
private fun RosterMenu(tile: CallTile, state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val id = tile.userId
    val pick: (CallEvent) -> Unit = { event ->
        onEvent(CallEvent.ToggleRosterMenu(""))
        onEvent(event)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_AVATAR + ZillitTheme.spacing.sm)
            .clip(RoundedCornerShape(MENU_CORNER))
            .background(ZillitTheme.colors.surface)
            .border(PANEL_BORDER, ZillitTheme.colors.border, RoundedCornerShape(MENU_CORNER)),
    ) {
        val hidden = id in state.line3.hidden
        val deafened = id in state.line3.deafened
        MenuRow(if (hidden) "Watch" else "Don't watch") { pick(CallEvent.SetWatch(id, hidden)) }
        MenuRow(if (deafened) "Unmute for myself" else "Mute for myself") { pick(CallEvent.SetListen(id, deafened)) }
        if (tile.media?.audioMuted != true) MenuRow("Mute for everyone") { pick(CallEvent.MuteForEveryone(id)) }
        if (tile.media?.videoOn == true) {
            MenuRow("Stop camera for everyone") { pick(CallEvent.StopCameraForEveryone(id)) }
        }
        if (state.isHost) {
            val blocked = state.line3.isChatBlocked(id)
            MenuRow(if (blocked) "Unblock chat" else "Block from chat") { pick(CallEvent.BlockChat(id, !blocked)) }
            MenuRow("Remove from call", danger = true) { pick(CallEvent.RemoveFromCall(id)) }
        }
    }
}

@Composable
private fun MenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    ZillitText(
        text = label,
        style = ZillitTheme.typography.bodySmall,
        color = if (danger) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    )
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
private val MENU_CORNER = 10.dp
