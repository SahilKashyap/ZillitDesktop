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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * Everyone the call is about, in one panel — the web's `CallUsersPanel`
 * (`CallOverlays.tsx:166-384`): who is in the call, who is being rung, who
 * left or declined, and everyone else who could be added, under one search.
 *
 * It used to be two panels — the roster behind People and an add-people
 * picker behind ⋮ — so adding someone meant closing the list of who was
 * here to open a list of who was not. One panel, the way every other
 * client draws it.
 *
 * This is also the surface that carries live per-person state during a
 * *video* call. The video rectangle belongs to the embedded browser and
 * nothing drawn on this side survives inside it, so speaking dots, mute
 * badges and link quality have to live somewhere outside it.
 *
 * On Line 3 each in-call row also carries the web's verbs
 * (`CallOverlays.tsx:262-280`): the ⋮ with mute-for-myself, don't-watch,
 * mute-for-everyone, and — for the host — block chat and remove; a Cancel
 * on a still-ringing invite; the Guest chip and the "On hold" line.
 */
@Composable
fun CallUsersPanel(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val sections = remember(state.tiles, state.session, state.addableCrew, query) {
        callUserSections(state, query)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(colors.surfaceRaised)
            .border(PANEL_BORDER, colors.border, RoundedCornerShape(PANEL_CORNER))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        CallPanelHeader(
            title = str(S.desktop_call_users),
            onClose = { onEvent(CallEvent.ToggleRoster) },
            tint = colors.textPrimary,
            icon = ZillitIcons.Users,
        )
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.desktop_transport_search_name_or_designation),
            modifier = Modifier.fillMaxWidth(),
        )
        UserSectionsList(sections, state, onEvent)
    }
}

/** The four sections, each headed with its count and left out when it is empty (bar "In call"). */
@Composable
private fun UserSectionsList(sections: CallUserSections, state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val listState = rememberLazyListState()
    ZillitLazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        item(key = "h-in-call") { SectionHeading(str(S.desktop_call_section_in_call, sections.inCall.size)) }
        items(sections.inCall, key = { "in-" + it.key }) { tile -> TileRow(tile, state, onEvent) }
        if (sections.ringing.isNotEmpty()) {
            item(key = "h-ringing") { SectionHeading(str(S.desktop_call_section_ringing, sections.ringing.size)) }
            items(sections.ringing, key = { "ring-" + it.key }) { tile -> TileRow(tile, state, onEvent) }
        }
        if (sections.dropped.isNotEmpty()) {
            item(key = "h-dropped") {
                SectionHeading(str(S.desktop_call_section_left_declined, sections.dropped.size))
            }
            items(sections.dropped, key = { "gone-" + it.userId }) { row ->
                PersonRow(
                    name = row.name,
                    userId = row.userId,
                    detail = row.status.droppedWord,
                    onAdd = if (sections.canAdd) {
                        { onEvent(CallEvent.AddPerson(row.asCrewEntry(state))) }
                    } else {
                        null
                    },
                )
            }
        }
        if (sections.addable.isNotEmpty()) {
            item(key = "h-users") { SectionHeading(str(S.desktop_call_section_users, sections.addable.size)) }
            items(sections.addable, key = { "add-" + it.userId }) { entry ->
                PersonRow(
                    name = entry.name,
                    userId = entry.userId,
                    detail = entry.designation,
                    onAdd = { onEvent(CallEvent.AddPerson(entry)) },
                )
            }
        }
    }
}

/** A roster tile's row, with its ⋮ menu opened under it. */
@Composable
private fun TileRow(tile: CallTile, state: CallUiState, onEvent: (CallEvent) -> Unit) {
    Column {
        RosterRow(tile, state, onEvent)
        if (state.rosterMenuFor == tile.userId && tile.userId.isNotBlank()) {
            RosterMenu(tile, state, onEvent)
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm, bottom = ZillitTheme.spacing.xxs),
    )
}

/** Someone not in the call: a face, a line about them, and Add when they may be rung. */
@Composable
private fun PersonRow(name: String, userId: String, detail: String, onAdd: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, userId = userId, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
            )
            if (detail.isNotBlank()) {
                ZillitText(
                    text = detail,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (onAdd != null) {
            ZillitButton(
                text = str(S.add),
                onClick = onAdd,
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Phone,
            )
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
                    text = if (tile.isSelf) tile.selfLabel else tile.name,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (tile.isGuest) ZillitTag(str(S.txt_badge_guest), tone = TagTone.Neutral)
            }
            ZillitText(
                text = when {
                    onHold -> str(S.desktop_call_on_hold)
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
            contentDescription = str(S.desktop_call_muted_for_you),
            tint = colors.warning,
            size = ROW_ICON,
        )
    }
    if (state != null && tile.userId in state.line3.hidden) {
        ZillitIcon(
            icon = ZillitIcons.Eye,
            contentDescription = str(S.desktop_call_video_hidden_by_you),
            tint = colors.warning,
            size = ROW_ICON,
        )
    }
    if (tile.hand) {
        ZillitIcon(
            icon = ZillitIcons.Hand,
            contentDescription = str(S.desktop_call_hand_raised),
            tint = colors.warning,
            size = ROW_ICON,
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
            contentDescription = str(S.desktop_muted),
            tint = colors.textMuted,
            size = ROW_ICON,
        )
    }
    tile.media?.quality?.takeIf { it.isTrouble }?.let { NetworkPip(quality = it) }
}

/** Line 3, somebody else: Cancel on a ring still out, ⋮ on someone in the room. */
@Composable
private fun RosterActions(tile: CallTile, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    when {
        tile.presence == CallStatus.Ringing -> ZillitText(
            text = str(S.cancel),
            style = ZillitTheme.typography.labelSmall,
            color = colors.accent,
            modifier = Modifier.clickable { onEvent(CallEvent.CancelInvite(tile.userId)) },
        )
        tile.presence == CallStatus.InCall && tile.userId.isNotBlank() ->
            Box(modifier = Modifier.clickable { onEvent(CallEvent.ToggleRosterMenu(tile.userId)) }) {
                ZillitIcon(
                    icon = ZillitIcons.MoreHorizontal,
                    contentDescription = str(S.options),
                    tint = colors.textMuted,
                    size = ROW_ICON,
                )
            }
    }
}

/** The web's row menu, verbatim: local first, then what the host may do to them. */
@Composable
private fun RosterMenu(tile: CallTile, state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val pick: (CallEvent) -> Unit = { event ->
        onEvent(CallEvent.ToggleRosterMenu(""))
        onEvent(event)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_AVATAR + ZillitTheme.spacing.sm)
            .clip(RoundedCornerShape(MENU_CORNER))
            .background(ZillitTheme.colors.surface)
            .border(PANEL_BORDER, ZillitTheme.colors.border, RoundedCornerShape(MENU_CORNER)),
    ) {
        RosterMenuItems(tile, state, pick)
        PanelCloseButton(
            onClose = { onEvent(CallEvent.ToggleRosterMenu("")) },
            tint = ZillitTheme.colors.textMuted,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** The menu's rows: my own mutes first, then what anyone may do to them, then the host's. */
@Composable
private fun RosterMenuItems(tile: CallTile, state: CallUiState, pick: (CallEvent) -> Unit) {
    val id = tile.userId
    Column(modifier = Modifier.fillMaxWidth()) {
        val hidden = id in state.line3.hidden
        val deafened = id in state.line3.deafened
        MenuRow(if (hidden) str(S.desktop_call_watch) else str(S.desktop_call_dont_watch)) {
            pick(CallEvent.SetWatch(id, hidden))
        }
        val listenLabel = if (deafened) str(S.desktop_call_unmute_for_myself) else str(S.desktop_call_mute_for_myself)
        MenuRow(listenLabel) {
            pick(CallEvent.SetListen(id, deafened))
        }
        if (tile.media?.audioMuted != true) {
            MenuRow(str(S.desktop_call_mute_for_everyone)) { pick(CallEvent.MuteForEveryone(id)) }
        }
        if (tile.media?.videoOn == true) {
            MenuRow(str(S.desktop_call_stop_camera_for_everyone)) { pick(CallEvent.StopCameraForEveryone(id)) }
        }
        if (state.isHost) {
            val blocked = state.line3.isChatBlocked(id)
            MenuRow(if (blocked) str(S.desktop_call_unblock_chat) else str(S.desktop_call_block_from_chat)) {
                pick(CallEvent.BlockChat(id, !blocked))
            }
            MenuRow(str(S.desktop_call_remove_from_call), danger = true) { pick(CallEvent.RemoveFromCall(id)) }
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

/** "Sahil (you)" — the web's own-row label — or plain "You" when we have no name for ourselves. */
private val CallTile.selfLabel: String
    get() = if (name.isBlank()) str(S.you) else str(S.desktop_name_you_suffix, name)

/** Why a dropped-out person is in the Left / Declined section, as the web's chips word it. */
private val CallStatus.droppedWord: String
    get() = when (this) {
        CallStatus.Declined -> str(S.declined_events)
        CallStatus.NotAnswered -> str(S.desktop_no_answer)
        else -> str(S.left)
    }

/** A dropped-out participant as the invite the Add button sends; the crew knows their device best. */
private fun CallParticipant.asCrewEntry(state: CallUiState): CallCrewEntry {
    val crew = state.addableCrew.firstOrNull { it.userId == userId }
    return CallCrewEntry(
        userId = userId,
        deviceId = crew?.deviceId?.takeIf(String::isNotBlank) ?: deviceId,
        name = name.ifBlank { crew?.name.orEmpty() },
        designation = designation.ifBlank { crew?.designation.orEmpty() },
    )
}

private val CallTile.presenceWord: String
    get() = when (presence) {
        CallStatus.Ringing -> str(S.txt_ringing)
        CallStatus.InCall -> if (media == null) str(S.txt_badge_in_call) else str(S.connected)
        CallStatus.Caller -> str(S.desktop_call_calling)
        CallStatus.Declined -> str(S.declined_events)
        CallStatus.NotAnswered -> str(S.desktop_no_answer)
        CallStatus.Left -> str(S.left)
        CallStatus.Ended -> str(S.desktop_call_ended_status)
    }

val ROSTER_WIDTH = 300.dp
private val PANEL_CORNER = 16.dp
private val PANEL_BORDER = 1.dp
private val ROW_HEIGHT = 40.dp
private val ROW_AVATAR = 28.dp
private val ROW_ICON = 14.dp
private val SPEAKING_DOT = 6.dp
private val MENU_CORNER = 10.dp
