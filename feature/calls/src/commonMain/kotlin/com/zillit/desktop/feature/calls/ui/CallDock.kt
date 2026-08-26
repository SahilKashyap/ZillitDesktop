package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.MediaDevice

/**
 * The call's verbs, gathered in one pill.
 *
 * End sits after a deliberate gap and is the only control that is red at rest:
 * it is the one press in this UI that cannot be undone, and it should never be
 * hit while reaching for mute.
 */
@Composable
fun CallDock(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .shadow(DOCK_ELEVATION, CircleShape)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        MediaControls(state = state, onEvent = onEvent)
        SayingSomething(state = state, onEvent = onEvent)
        // Between the media toggles and the room controls: it belongs with
        // the things that change what the user hears, not who is present.
        AudioDevicePicker(state = state, onEvent = onEvent)
        RoomControls(state = state, onEvent = onEvent)
        Box(modifier = Modifier.width(ZillitTheme.spacing.lg))
        RoundAction(
            icon = ZillitIcons.PhoneDown,
            label = "End call",
            background = colors.danger,
            tint = Color.White,
            size = END_BUTTON,
            onClick = { onEvent(CallEvent.HangUp) },
        )
    }
}

/** Mute, camera, hand and share — everything that changes what others get. */
@Composable
private fun MediaControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
        RoundAction(
            icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
            label = if (state.micMuted) "Unmute" else "Mute",
            background = if (state.micMuted) colors.danger else colors.surfaceHover,
            tint = if (state.micMuted) Color.White else colors.textPrimary,
            size = DOCK_BUTTON,
            onClick = { onEvent(CallEvent.ToggleMic) },
        )
        // Always offered, audio calls included: enabling video mid-call is
        // how the phones work, and a call that joined as audio is exactly the
        // one where the user reaches for this button.
        RoundAction(
            icon = if (state.cameraOn) ZillitIcons.Camera else ZillitIcons.CameraOff,
            label = if (state.cameraOn) "Turn camera off" else "Turn camera on",
            background = if (state.cameraOn) colors.surfaceHover else colors.danger,
            tint = if (state.cameraOn) colors.textPrimary else Color.White,
            size = DOCK_BUTTON,
            onClick = { onEvent(CallEvent.ToggleCamera) },
        )
        HandAndShare(state = state, onEvent = onEvent)
}

/** Asking for the floor, and taking it. Both change what the call is looking at. */
@Composable
private fun HandAndShare(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    // Amber when up, matching the banner and the phones' own colour for it,
    // so the raised state reads at a glance from across a desk.
    RoundAction(
        icon = ZillitIcons.Hand,
        label = if (state.handRaised) "Lower hand" else "Raise hand",
        background = if (state.handRaised) colors.warning else colors.surfaceHover,
        tint = if (state.handRaised) Color.White else colors.textPrimary,
        size = SMALL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleHand) },
    )
    // Sharing replaces the camera feed while it runs, so it sits beside the
    // camera button rather than among the room controls.
    RoundAction(
        icon = ZillitIcons.Monitor,
        label = if (state.media.selfSharing) "Stop sharing" else "Share screen",
        background = if (state.media.selfSharing) colors.accent else colors.surfaceHover,
        tint = if (state.media.selfSharing) Color.White else colors.textPrimary,
        size = SMALL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleScreenShare) },
    )
    // One recording per call is the rule every platform enforces, so while
    // somebody else holds it the button steps aside and the banner explains.
    if (state.recordedBy.isBlank() || state.recording) {
        RoundAction(
            icon = ZillitIcons.Record,
            label = if (state.recording) "Stop recording" else "Record call",
            background = if (state.recording) colors.danger else colors.surfaceHover,
            tint = if (state.recording) Color.White else colors.textPrimary,
            size = SMALL_BUTTON,
            onClick = { onEvent(CallEvent.ToggleRecording) },
        )
    }
}

/** Who is here and who else could be. */
@Composable
private fun RoomControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
        RoundAction(
            icon = ZillitIcons.Users,
            label = "Participants",
            background = if (state.rosterOpen) colors.surfaceSelected else colors.surfaceHover,
            tint = colors.textPrimary,
            size = SMALL_BUTTON,
            onClick = { onEvent(CallEvent.ToggleRoster) },
        )
        // A support call goes to one agent; pulling bystanders in is not a
        // thing it offers (the coordinator refuses it too — this just agrees).
        if (state.session?.is247Call != true) {
            RoundAction(
                icon = ZillitIcons.UserPlus,
                label = "Add people",
                background = if (state.addPeopleOpen) colors.surfaceSelected else colors.surfaceHover,
                tint = colors.textPrimary,
                size = SMALL_BUTTON,
                onClick = { onEvent(CallEvent.ToggleAddPeople) },
            )
        }
}

/**
 * React and chat, the two ways to say something without taking the floor.
 *
 * Together and between the media toggles and the room controls: they change
 * neither what is heard nor who is present, and on the phones they share a
 * corner for the same reason.
 */
@Composable
private fun SayingSomething(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    RoundAction(
        icon = ZillitIcons.Smiley,
        label = "React",
        background = if (state.reactionBarOpen) colors.surfaceSelected else colors.surfaceHover,
        tint = colors.textPrimary,
        size = SMALL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleReactionBar) },
    )
    // The badge is drawn over the button rather than beside it: the dock is a
    // fixed row of circles, and a count that widens one of them shifts every
    // control to its right mid-call.
    Box(contentAlignment = Alignment.TopEnd) {
        RoundAction(
            icon = ZillitIcons.Chat,
            label = "Call chat",
            background = if (state.chatOpen) colors.surfaceSelected else colors.surfaceHover,
            tint = colors.textPrimary,
            size = SMALL_BUTTON,
            onClick = { onEvent(CallEvent.ToggleChat) },
        )
        if (state.chatUnread > 0) {
            Box(
                modifier = Modifier
                    .size(UNREAD_DOT)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}

/**
 * Microphone and output pickers, behind one control-bar button.
 *
 * Hidden when the machine offers nothing to choose between — a laptop with
 * only its built-in pair gets a bar with one less button rather than a menu
 * that can only confirm what is already true.
 *
 * Drawn as one menu with two labelled groups instead of two buttons: on a
 * desktop these are almost always changed together (plug in a headset, both
 * move), and the phones' one-button route picker sets the expectation.
 */
@Composable
private fun AudioDevicePicker(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val devices = state.devices
    // Absent hardware is not a reason to hide the button while a call is
    // live: labels arrive with media permission, which can land after the
    // first frame, so an empty list now may be populated a moment later.
    if (!devices.hasChoice && !state.audioPickerOpen) {
        RoundAction(
            icon = ZillitIcons.Settings,
            label = "Audio devices",
            background = colors.surfaceHover,
            tint = colors.textPrimary,
            size = SMALL_BUTTON,
            onClick = { onEvent(CallEvent.ToggleAudioPicker) },
        )
        return
    }

    Box {
        RoundAction(
            icon = ZillitIcons.Settings,
            label = "Audio devices",
            background = if (state.audioPickerOpen) colors.surfaceSelected else colors.surfaceHover,
            tint = colors.textPrimary,
            size = SMALL_BUTTON,
            onClick = { onEvent(CallEvent.ToggleAudioPicker) },
        )

        DropdownMenu(
            expanded = state.audioPickerOpen,
            onDismissRequest = { onEvent(CallEvent.ToggleAudioPicker) },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS)),
        ) {
            DeviceGroup(
                title = "Microphone",
                devices = devices.microphones,
                chosenId = devices.microphoneId,
                onChoose = { onEvent(CallEvent.ChooseMicrophone(it)) },
            )
            DeviceGroup(
                title = "Speaker",
                devices = devices.speakers,
                chosenId = devices.speakerId,
                onChoose = { onEvent(CallEvent.ChooseSpeaker(it)) },
            )
        }
    }
}

/**
 * One labelled list. An empty id is the OS default and is always offered:
 * it is the only way back after choosing a device that has since been
 * unplugged.
 */
@Composable
private fun DeviceGroup(
    title: String,
    devices: List<MediaDevice>,
    chosenId: String,
    onChoose: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = title,
        style = ZillitTheme.typography.labelSmall,
        color = colors.textSecondary,
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.xs,
        ),
    )
    DeviceRow("System default", chosenId.isBlank()) { onChoose("") }
    devices.forEach { device ->
        DeviceRow(device.displayName, device.id == chosenId) { onChoose(device.id) }
    }
}

@Composable
private fun DeviceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    DropdownMenuItem(
        onClick = onClick,
        modifier = Modifier.background(if (selected) colors.surfaceSelected else colors.surfaceRaised),
        text = {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium,
                color = if (selected) colors.accentText else colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * A circular control.
 *
 * Shared by the dock, the ring card and the pill so the three surfaces cannot
 * drift apart on size, hover or hit area.
 */
@Composable
fun RoundAction(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = DOCK_BUTTON,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        targetValue = if (hovered) background.lifted() else background,
        animationSpec = tween(HOVER_MS),
        label = "dock-hover",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, contentDescription = label, tint = tint, size = size / 2)
    }
}

/**
 * A hover that reads on any base colour.
 *
 * Compositing white over the fill rather than picking a second palette entry,
 * because these buttons are red, grey and selected-grey at rest and no single
 * named colour brightens all three.
 */
private fun Color.lifted(): Color = Color.White.copy(alpha = HOVER_LIFT).compositeOverColour(this)

private fun Color.compositeOverColour(base: Color): Color {
    val a = alpha
    return Color(
        red = red * a + base.red * (1 - a),
        green = green * a + base.green * (1 - a),
        blue = blue * a + base.blue * (1 - a),
        alpha = base.alpha,
    )
}

val DOCK_HEIGHT = 72.dp
private val DOCK_BUTTON = 52.dp
private val SMALL_BUTTON = 44.dp

/** Matches ZillitSelect's popup, so the two menus are the same object. */
private val MENU_RADIUS = 12.dp
private val END_BUTTON = 60.dp

/** A dot, not a number: on a control bar, "someone said something" is the message. */
private val UNREAD_DOT = 10.dp
private val DOCK_ELEVATION = 12.dp
private const val HOVER_MS = 120
private const val HOVER_LIFT = 0.12f
