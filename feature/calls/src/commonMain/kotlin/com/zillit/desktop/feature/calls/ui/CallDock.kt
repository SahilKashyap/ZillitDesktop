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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallPhase

/**
 * The call's verbs, as the web's control bar lays them out (`CallRoom.tsx:1619-1881`).
 *
 * Left to right: the microphone and camera as split pills whose caret opens
 * the device list, raise hand, present, react, the overflow menu, and the
 * red hang-up pill. Chat is pinned to the far right with its unread count,
 * out of the centred run so a count never shifts the other controls.
 *
 * [connected] false is the ring: every control is drawn so the bar never
 * reflows, but the ones that need a room are inert at 40% with a tooltip
 * saying why (`CallRoom.tsx:754-759`).
 */
@Composable
fun CallDock(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DOCK_HEIGHT)
            .background(CallPalette.surface),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DOCK_GAP),
        ) {
            MediaPills(state, onEvent)
            RoomVerbs(state, onEvent, connected)
            OverflowMenu(state, onEvent, connected)
            HangUp(state, onEvent)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = DOCK_EDGE)) {
            ChatButton(state, onEvent, connected)
        }
    }
}

/**
 * Microphone and camera: a pill each, caret first, then the toggle (`CallRoom.tsx:1620-1672`).
 *
 * Held = deliberately silent both ways, so both toggles are off the table
 * until the user resumes: leaving them live would let them unmute into a
 * call the server has them marked as absent from.
 */
@Composable
private fun MediaPills(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    SplitPill(
        icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
        label = when {
            state.onHold -> "On hold — resume the call to use your microphone"
            state.micMuted -> "Unmute"
            else -> "Mute"
        },
        off = state.micMuted,
        caretLabel = "Audio settings",
        caretActive = state.audioPickerOpen,
        enabled = !state.onHold,
        onCaret = { onEvent(CallEvent.ToggleAudioPicker) },
        onClick = { onEvent(CallEvent.ToggleMic) },
    )
    // Always offered, audio calls included: enabling video mid-call is how
    // the phones work, and a call that joined as audio is exactly the one
    // where the user reaches for this button.
    SplitPill(
        icon = if (state.cameraOn) ZillitIcons.Camera else ZillitIcons.CameraOff,
        label = when {
            state.onHold -> "On hold — resume the call to use your camera"
            state.cameraOn -> "Turn camera off"
            else -> "Turn camera on"
        },
        off = !state.cameraOn,
        caretLabel = "Video settings",
        caretActive = state.audioPickerOpen,
        enabled = !state.onHold,
        onCaret = { onEvent(CallEvent.ToggleAudioPicker) },
        onClick = { onEvent(CallEvent.ToggleCamera) },
    )
}

/**
 * Hand, present, react — the things that need a connected room.
 *
 * Under a host policy a locked control stays drawn but says why
 * (`handRestricted`, `reactionsOn`), and Present follows the host's lock the
 * way the web hides it: shown and working when allowed, gone when not — the
 * host keeps it either way (`CallRoom.tsx:1714-1746`).
 */
@Composable
private fun RoomVerbs(state: CallUiState, onEvent: (CallEvent) -> Unit, connected: Boolean) {
    DockButton(
        icon = ZillitIcons.Hand,
        label = when {
            state.handsLocked -> "Raising hands is off"
            state.handRaised -> "Lower hand"
            else -> "Raise hand"
        },
        active = state.handRaised,
        enabled = connected,
        onClick = { onEvent(CallEvent.ToggleHand) },
    )
    if (!state.shareLocked) {
        DockButton(
            icon = ZillitIcons.Monitor,
            label = if (state.media.selfSharing) "Stop presenting" else "Present",
            active = state.media.selfSharing,
            enabled = connected,
            onClick = { onEvent(CallEvent.ToggleScreenShare) },
        )
    }
    DockButton(
        icon = ZillitIcons.Smiley,
        label = if (state.reactionsLocked) "Reactions are off" else "Send a reaction",
        active = state.reactionBarOpen,
        enabled = connected && !state.reactionsLocked,
        onClick = { onEvent(CallEvent.ToggleReactionBar) },
    )
}

/**
 * The ⋮ button: recording, people and where the window lives — the verbs
 * the web keeps off the bar (`CallRoom.tsx:1726-1826`), opened as a panel
 * beside the picture ([CallMorePanel]), never a popup over it.
 */
@Composable
private fun OverflowMenu(state: CallUiState, onEvent: (CallEvent) -> Unit, connected: Boolean) {
    DockButton(
        icon = ZillitIcons.MoreHorizontal,
        label = "More options",
        active = state.moreOpen,
        enabled = connected,
        onClick = { onEvent(CallEvent.ToggleMore) },
    )
}

/** The red pill: 58×44, "Leave call" (`CallRoom.tsx:1856-1864`). */
@Composable
private fun HangUp(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val label = if (state.phase == CallPhase.Ending) "Close" else "Leave call"
    ZillitTooltip(label) {
        Box(
            modifier = Modifier
                .padding(start = ZillitTheme.spacing.sm)
                .size(width = HANGUP_WIDTH, height = DOCK_BUTTON)
                .clip(RoundedCornerShape(PILL_CORNER))
                .background(CallPalette.danger)
                .clickable { onEvent(CallEvent.HangUp) },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.PhoneDown, contentDescription = label, tint = Color.White, size = DOCK_ICON)
        }
    }
}

/** Chat, pinned right, with the unread count capped at "9+" (`CallRoom.tsx:1868-1881`). */
@Composable
private fun ChatButton(state: CallUiState, onEvent: (CallEvent) -> Unit, connected: Boolean) {
    Box(contentAlignment = Alignment.TopEnd) {
        DockButton(
            icon = ZillitIcons.Chat,
            label = "Chat",
            active = state.chatOpen,
            enabled = connected,
            onClick = { onEvent(CallEvent.ToggleChat) },
        )
        if (state.chatUnread > 0) {
            Box(
                modifier = Modifier
                    .size(BADGE)
                    .clip(CircleShape)
                    .background(CallPalette.danger),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = if (state.chatUnread > UNREAD_CAP) "$UNREAD_CAP+" else state.chatUnread.toString(),
                    style = ZillitTheme.typography.labelSmall
                        .copy(fontSize = BADGE_FONT, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A caret and a toggle in one pill (`.ctlGroup`, `styles.css:1436-1468`).
 * Off — muted, camera closed — turns the whole pill the web's soft red.
 */
@Composable
private fun SplitPill(
    icon: ImageVector,
    label: String,
    off: Boolean,
    caretLabel: String,
    caretActive: Boolean,
    onCaret: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val group = if (off) CallPalette.offPill else CallPalette.controlGroup
    val caret = if (off) CallPalette.offCaret else if (caretActive) CallPalette.control else CallPalette.caret
    val glyph = if (off) CallPalette.onOffPill else CallPalette.text
    // The caret sits on the pill's dark end: off, that end is deep red, and
    // the deep-red glyph the toggle wears vanished into it — the chevron
    // takes the pill's pale tone there instead.
    val caretGlyph = if (off) CallPalette.offPill else CallPalette.text
    Row(
        modifier = Modifier
            .height(DOCK_BUTTON)
            .alpha(if (enabled) 1f else INERT_ALPHA)
            .clip(RoundedCornerShape(PILL_CORNER))
            .background(group),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTooltip(caretLabel) {
            Box(
                modifier = Modifier
                    .width(CARET_WIDTH)
                    .height(DOCK_BUTTON)
                    .background(caret)
                    .clickable(onClick = onCaret),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = ZillitIcons.ChevronUp,
                    contentDescription = caretLabel,
                    tint = caretGlyph,
                    size = CARET_ICON,
                )
            }
        }
        ZillitTooltip(label) {
            Box(
                modifier = Modifier
                    .size(DOCK_BUTTON)
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = icon, contentDescription = label, tint = glyph, size = DOCK_ICON)
            }
        }
    }
}

/** A 44dp circle: grey at rest, the web's light blue when its state is on. */
@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tip = if (enabled) label else "Available once the call connects"
    ZillitTooltip(tip) {
        RoundAction(
            icon = icon,
            label = label,
            background = if (active) CallPalette.accent else CallPalette.control,
            tint = if (active) CallPalette.onAccent else CallPalette.text,
            size = DOCK_BUTTON,
            modifier = Modifier.alpha(if (enabled) 1f else INERT_ALPHA),
            onClick = { if (enabled) onClick() },
        )
    }
}

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
 * because these buttons are red, grey and light blue at rest and no single
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

/** The web's bar: 64px tall, 44px circles, 12px apart (`styles.css:881-929`). */
val DOCK_HEIGHT = 64.dp
private val DOCK_BUTTON = 44.dp
private val DOCK_ICON = 22.dp
private val DOCK_GAP = 12.dp
private val DOCK_EDGE = 16.dp
private val HANGUP_WIDTH = 58.dp
private val PILL_CORNER = 22.dp
private val CARET_WIDTH = 28.dp
private val CARET_ICON = 14.dp
private val BADGE = 18.dp
private val BADGE_FONT = 10.sp
private const val UNREAD_CAP = 9
private const val INERT_ALPHA = 0.4f
private const val HOVER_MS = 120
private const val HOVER_LIFT = 0.12f
