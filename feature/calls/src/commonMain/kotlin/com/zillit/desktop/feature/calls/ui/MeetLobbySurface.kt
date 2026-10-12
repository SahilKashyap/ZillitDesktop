package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.livekit.MeetAttendee
import com.zillit.desktop.feature.calls.data.livekit.MeetLobbyState
import com.zillit.desktop.feature.calls.data.livekit.MeetLobbyStatus
import com.zillit.desktop.feature.calls.data.livekit.MeetProblem

/** The lobby's own colours — Meet's pre-join, as the phones and the web draw it. */
private object MeetLobbyPalette {
    val background = Color(0xFF202124)
    val card = Color(0xFF3C4043)
    val accent = Color(0xFF8AB4F8)
    val error = Color(0xFFEA4335)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF9AA0A6)

    /** Disabled, explicitly — the default disabled colours put a near-black spinner on a near-black pill. */
    val accentDisabled = accent.copy(alpha = 0.7f)
    val onAccent = Color(0xFF202124)
}

/**
 * A Line 1 meet's lobby, drawn where the call will be: the surface goes
 * lobby → joining → in-call without ever changing hands between screens.
 *
 * The camera preview is the call page itself, mounted into the rectangle
 * [onSlot] reports — a heavyweight surface Compose cannot paint over, which
 * is why the microphone and camera toggles sit in a band under the picture
 * rather than on top of it.
 */
@Composable
@Suppress("LongMethod") // One surface: header, picture, strip, status; split further it only scatters the layout.
internal fun MeetLobbySurface(
    lobby: MeetLobbyState,
    onEvent: (CallEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    hasVideoSurface: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
) {
    // Polling stops while the window is in the background.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { onEvent(MeetLobbyEvent.Focus(focused)) }

    val title = lobby.title.ifBlank { lobby.info?.title.orEmpty() }.ifBlank { str(S.desktop_cal_section_meeting) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeetLobbyPalette.background)
            // Nothing under the lobby is reachable while it is up.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(title, closeEnabled = lobby.status != MeetLobbyStatus.Joining && !lobby.handedOff, onEvent)
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= WIDE_LAYOUT
                val fullHeight = maxHeight
                val preview: @Composable () -> Unit = {
                    PreviewCard(lobby, loadAvatar, hasVideoSurface, onSlot, onEvent)
                }
                val side: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.widthIn(max = SIDE_WIDTH),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
                    ) {
                        ZillitText(
                            text = title,
                            style = ZillitTheme.typography.displayLarge,
                            color = MeetLobbyPalette.text,
                            maxLines = 2,
                            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                        )
                        ParticipantsStrip(lobby, loadAvatar, onEvent)
                        StatusArea(lobby, onEvent)
                    }
                }
                // Centred while it fits, scrolling when the window is too short for it.
                Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = fullHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (wide) {
                            Row(
                                modifier = Modifier.padding(ZillitTheme.spacing.xl),
                                horizontalArrangement = Arrangement.spacedBy(GAP),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                preview()
                                side()
                            }
                        } else {
                            Column(
                                modifier = Modifier.padding(ZillitTheme.spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                preview()
                                side()
                            }
                        }
                    }
                }
            }
        }
        if (lobby.everyoneOpen) EveryoneSheet(lobby.attendees, loadAvatar, onEvent)
    }
}

@Composable
private fun Header(title: String, closeEnabled: Boolean, onEvent: (CallEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(CLOSE_SIZE)
                .clip(CircleShape)
                .clickable(enabled = closeEnabled) { onEvent(MeetLobbyEvent.Close) },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Close,
                contentDescription = str(S.close),
                tint = if (closeEnabled) MeetLobbyPalette.text else MeetLobbyPalette.muted,
            )
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleMedium,
            color = MeetLobbyPalette.text,
            maxLines = 1,
        )
    }
}

/** The 3:4 picture, and the two round toggles in a band of their own underneath it. */
@Composable
private fun PreviewCard(
    lobby: MeetLobbyState,
    loadAvatar: suspend (String) -> ImageBitmap?,
    hasVideoSurface: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
    onEvent: (CallEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(PREVIEW_WIDTH)
            .clip(RoundedCornerShape(PREVIEW_CORNER))
            .background(MeetLobbyPalette.card),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_ASPECT), contentAlignment = Alignment.Center) {
            if (lobby.previewing && hasVideoSurface) {
                // The rectangle the call page is mounted into. Nothing Compose
                // draws here survives once it is.
                Box(modifier = Modifier.fillMaxSize().onGloballyPositioned(onSlot))
            } else {
                CameraOff(lobby, loadAvatar)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg, Alignment.CenterHorizontally),
        ) {
            RoundToggle(
                on = lobby.micOn,
                onIcon = ZillitIcons.Mic,
                offIcon = ZillitIcons.MicOff,
                label = str(if (lobby.micOn) S.desktop_meet_turn_mic_off else S.desktop_meet_turn_mic_on),
                enabled = !lobby.handedOff,
                onClick = { onEvent(MeetLobbyEvent.ToggleMic) },
            )
            RoundToggle(
                on = lobby.previewing,
                onIcon = ZillitIcons.Camera,
                offIcon = ZillitIcons.CameraOff,
                label = str(if (lobby.previewing) S.desktop_meet_turn_cam_off else S.desktop_meet_turn_cam_on),
                enabled = !lobby.handedOff,
                onClick = { onEvent(MeetLobbyEvent.ToggleCamera) },
            )
        }
    }
}

@Composable
private fun CameraOff(lobby: MeetLobbyState, loadAvatar: suspend (String) -> ImageBitmap?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val face by produceState<ImageBitmap?>(null, lobby.selfUserId) {
            value = lobby.selfUserId.takeIf { it.isNotBlank() }?.let { loadAvatar(it) }
        }
        if (face != null) ZillitAvatar(name = "", image = face, size = SELF_AVATAR)
        ZillitText(
            text = str(S.desktop_meet_camera_off),
            style = ZillitTheme.typography.bodyLarge,
            color = MeetLobbyPalette.text,
        )
    }
}

@Composable
private fun RoundToggle(
    on: Boolean,
    onIcon: androidx.compose.ui.graphics.vector.ImageVector,
    offIcon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TOGGLE_SIZE)
            .clip(CircleShape)
            .background(if (on) MeetLobbyPalette.background.copy(alpha = 0.6f) else MeetLobbyPalette.error)
            .border(BorderStroke(1.dp, if (on) MeetLobbyPalette.muted else MeetLobbyPalette.error), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = if (on) onIcon else offIcon, contentDescription = label, tint = Color.White)
    }
}

/**
 * Who is already in. An unknown roster is never asserted to be empty, and a
 * failed read keeps what was last shown (the controller only replaces the
 * list on a read that succeeded).
 */
@Composable
private fun ParticipantsStrip(
    lobby: MeetLobbyState,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (CallEvent) -> Unit,
) {
    val people = lobby.attendees
    if (people.isEmpty()) {
        val text = when {
            !lobby.rosterConfirmed -> str(S.desktop_meet_checking)
            lobby.waitsForHost -> str(S.desktop_meet_waiting_for_host)
            else -> str(S.desktop_meet_no_one_here)
        }
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .clip(RoundedCornerShape(CHIP_HEIGHT / 2))
                .background(MeetLobbyPalette.card)
                .padding(horizontal = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Info, tint = MeetLobbyPalette.muted, size = INFO_ICON)
            ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = MeetLobbyPalette.text)
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        AvatarStack(people, loadAvatar)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = meetNamesLine(people),
                style = ZillitTheme.typography.titleSmall,
                color = MeetLobbyPalette.text,
                maxLines = 2,
            )
            ZillitText(
                text = meetCountLine(people.size),
                style = ZillitTheme.typography.bodySmall,
                color = MeetLobbyPalette.muted,
            )
            if (people.size > STACKED) {
                ZillitText(
                    text = str(S.desktop_meet_see_all),
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = MeetLobbyPalette.accent,
                    modifier = Modifier.clickable { onEvent(MeetLobbyEvent.ToggleEveryone) },
                )
            }
        }
    }
}

/**
 * Three faces overlapping, then "+N" — the in-call avatar in its in-call
 * colour, so a person looks the same in the lobby and in the call.
 */
@Composable
private fun AvatarStack(people: List<MeetAttendee>, loadAvatar: suspend (String) -> ImageBitmap?) {
    val shown = people.take(STACKED)
    val extra = people.size - shown.size
    val discs = shown.size + if (extra > 0) 1 else 0
    Box(modifier = Modifier.width(STACK_AVATAR + (STACK_AVATAR - STACK_OVERLAP) * (discs - 1)).height(STACK_AVATAR)) {
        shown.forEachIndexed { index, person ->
            Box(
                modifier = Modifier
                    .offset(x = (STACK_AVATAR - STACK_OVERLAP) * index)
                    .size(STACK_AVATAR)
                    .clip(CircleShape)
                    .border(BorderStroke(STACK_RING, MeetLobbyPalette.background), CircleShape),
            ) {
                AttendeeAvatar(person, loadAvatar, STACK_AVATAR)
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .offset(x = (STACK_AVATAR - STACK_OVERLAP) * shown.size)
                    .size(STACK_AVATAR)
                    .clip(CircleShape)
                    .background(MeetLobbyPalette.card)
                    .border(BorderStroke(STACK_RING, MeetLobbyPalette.background), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(text = "+$extra", style = ZillitTheme.typography.label, color = MeetLobbyPalette.text)
            }
        }
    }
}

@Composable
private fun AttendeeAvatar(
    person: MeetAttendee,
    loadAvatar: suspend (String) -> ImageBitmap?,
    size: androidx.compose.ui.unit.Dp,
) {
    val name = person.displayName.ifBlank { str(S.desktop_meet_participant) }
    val face by produceState<ImageBitmap?>(null, person.userId) {
        value = if (person.isGuest) null else loadAvatar(person.userId)
    }
    ZillitAvatar(name = name, image = face, size = size, colour = CallTileColors.of(name))
}

/** Joining, knocking, the switch question, an error — and the button. */
@Composable
private fun StatusArea(lobby: MeetLobbyState, onEvent: (CallEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        if (lobby.camBlocked) {
            ZillitText(
                text = str(S.desktop_meet_camera_blocked),
                style = ZillitTheme.typography.bodySmall,
                color = MeetLobbyPalette.muted,
            )
        }
        val status = lobby.status
        when {
            status == MeetLobbyStatus.Waiting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitSpinner(color = MeetLobbyPalette.accent, size = SPINNER)
                ZillitText(
                    text = str(S.desktop_meet_asking),
                    color = MeetLobbyPalette.text,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextAction(str(S.cancel)) { onEvent(MeetLobbyEvent.Cancel) }
            }
            lobby.offersSwitch -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitText(text = str(S.desktop_meet_switch_question), color = MeetLobbyPalette.text)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    PillButton(str(S.desktop_call_switch_here), busy = false, enabled = true) {
                        onEvent(MeetLobbyEvent.SwitchHere)
                    }
                    TextAction(str(S.cancel)) { onEvent(MeetLobbyEvent.Cancel) }
                }
            }
            else -> {
                if (status is MeetLobbyStatus.Error) {
                    ZillitText(text = status.message(), color = MeetLobbyPalette.error)
                }
                val joining = status == MeetLobbyStatus.Joining || lobby.handedOff
                val fatal = status is MeetLobbyStatus.Error && status.fatal
                PillButton(
                    text = when {
                        joining -> str(S.desktop_call_joining)
                        lobby.offersStart -> str(S.desktop_meet_start)
                        else -> str(S.desktop_meet_join_now)
                    },
                    busy = joining,
                    enabled = !joining && !fatal,
                ) { onEvent(MeetLobbyEvent.Join) }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = PILL_HEIGHT)
            .clip(RoundedCornerShape(PILL_HEIGHT / 2))
            .background(if (enabled) MeetLobbyPalette.accent else MeetLobbyPalette.accentDisabled)
            // Ignored while joining or knocking: a second press is a second SFU identity.
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (busy) ZillitSpinner(color = MeetLobbyPalette.onAccent, size = SPINNER)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.button.copy(fontWeight = FontWeight.SemiBold),
            color = MeetLobbyPalette.onAccent,
        )
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.button.copy(fontWeight = FontWeight.SemiBold),
        color = MeetLobbyPalette.accent,
        modifier = Modifier.clip(RoundedCornerShape(TEXT_ACTION_CORNER)).clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    )
}

/** Everyone in the meeting — the strip's "See all". */
@Composable
private fun EveryoneSheet(
    people: List<MeetAttendee>,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (CallEvent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                onEvent(MeetLobbyEvent.ToggleEveryone)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(SHEET_WIDTH)
                .heightIn(max = SHEET_MAX_HEIGHT)
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .background(MeetLobbyPalette.card)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = str(S.desktop_meet_in_this_meeting),
                    style = ZillitTheme.typography.titleMedium,
                    color = MeetLobbyPalette.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(CLOSE_SIZE).clip(CircleShape)
                        .clickable { onEvent(MeetLobbyEvent.ToggleEveryone) },
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.close),
                        tint = MeetLobbyPalette.text,
                    )
                }
            }
            ZillitText(
                text = meetCountLine(people.size),
                style = ZillitTheme.typography.bodySmall,
                color = MeetLobbyPalette.muted,
            )
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                people.forEach { person -> EveryoneRow(person, loadAvatar) }
                Spacer(Modifier.height(ZillitTheme.spacing.xs))
            }
        }
    }
}

@Composable
private fun EveryoneRow(person: MeetAttendee, loadAvatar: suspend (String) -> ImageBitmap?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        AttendeeAvatar(person, loadAvatar, STACK_AVATAR)
        Column {
            ZillitText(
                text = person.displayName.ifBlank { str(S.desktop_meet_participant) },
                color = MeetLobbyPalette.text,
                maxLines = 1,
            )
            if (person.designation.isNotBlank()) {
                ZillitText(
                    text = person.designation,
                    style = ZillitTheme.typography.bodySmall,
                    color = MeetLobbyPalette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * "A", "A and B", "A, B and C", "A, B, C and N others" — a blank name reads
 * "Participant". Pure, so the wording is pinned by a test.
 */
internal fun meetNamesLine(people: List<MeetAttendee>): String {
    val names = people.map { it.displayName.trim().ifBlank { str(S.desktop_meet_participant) } }
    val rest = names.size - STACKED
    return when {
        names.isEmpty() -> ""
        names.size == 1 -> names[0]
        names.size == 2 -> str(S.desktop_docdist_x_and_y, names[0], names[1])
        names.size == STACKED -> str(S.desktop_meet_names_three, names[0], names[1], names[2])
        rest == 1 -> str(S.desktop_meet_names_more_one, names[0], names[1], names[2])
        else -> str(S.desktop_meet_names_more_many, names[0], names[1], names[2], rest)
    }
}

/** "1 person is in the meeting" / "N people are in the meeting". */
internal fun meetCountLine(count: Int): String =
    if (count == 1) str(S.desktop_meet_count_one) else str(S.desktop_meet_count_many, count)

/**
 * What an error says. The three knock refusals stay three messages; an
 * unrecognised refusal names the server's word.
 */
internal fun MeetLobbyStatus.Error.message(): String =
    if (problem == MeetProblem.Other && detail.isNotBlank()) {
        str(S.desktop_meet_failed_with, detail)
    } else {
        str(PROBLEM_TEXT.getValue(problem))
    }

private val PROBLEM_TEXT: Map<MeetProblem, String> = mapOf(
    MeetProblem.NoMeeting to S.desktop_meet_no_meeting,
    MeetProblem.LinkInvalid to S.desktop_meet_link_invalid,
    MeetProblem.Cancelled to S.desktop_meet_cancelled,
    MeetProblem.NotAllowed to S.desktop_meet_not_allowed,
    MeetProblem.AlreadyOnAnotherCall to S.desktop_meet_on_another_call,
    MeetProblem.NotStarted to S.desktop_meet_not_started,
    MeetProblem.NoJoinDetails to S.desktop_meet_no_details,
    MeetProblem.Network to S.desktop_meet_network,
    MeetProblem.Other to S.desktop_meet_network,
    MeetProblem.KnockDeclined to S.desktop_meet_knock_declined,
    MeetProblem.KnockEnded to S.desktop_meet_knock_ended,
    MeetProblem.KnockLinkDisabled to S.desktop_meet_knock_link_disabled,
    MeetProblem.KnockRefused to S.desktop_meet_knock_refused,
    MeetProblem.MicrophoneDenied to S.desktop_meet_mic_needed,
)

private val WIDE_LAYOUT = 760.dp
private val SIDE_WIDTH = 400.dp
private val GAP = 56.dp
private val PREVIEW_WIDTH = 320.dp
private const val PREVIEW_ASPECT = 3f / 4f
private val PREVIEW_CORNER = 16.dp
private val CLOSE_SIZE = 36.dp
private val TOGGLE_SIZE = 48.dp
private val SELF_AVATAR = 72.dp
private val CHIP_HEIGHT = 36.dp
private val INFO_ICON = 18.dp
private val STACK_AVATAR = 40.dp
private val STACK_OVERLAP = 12.dp
private val STACK_RING = 2.dp
private const val STACKED = 3
private val PILL_HEIGHT = 44.dp
private val SPINNER = 18.dp
private val TEXT_ACTION_CORNER = 8.dp
private val SHEET_WIDTH = 380.dp
private val SHEET_MAX_HEIGHT = 520.dp
private const val SCRIM_ALPHA = 0.5f
