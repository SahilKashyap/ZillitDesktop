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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

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
    val showCamera = state.session?.hasVideo == true || state.stage == CallStageKind.Video
    Row(
        modifier = modifier
            .shadow(DOCK_ELEVATION, CircleShape)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        RoundAction(
            icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
            label = if (state.micMuted) "Unmute" else "Mute",
            background = if (state.micMuted) colors.danger else colors.surfaceHover,
            tint = if (state.micMuted) Color.White else colors.textPrimary,
            size = DOCK_BUTTON,
            onClick = { onEvent(CallEvent.ToggleMic) },
        )
        if (showCamera) {
            RoundAction(
                icon = if (state.cameraOn) ZillitIcons.Camera else ZillitIcons.CameraOff,
                label = if (state.cameraOn) "Turn camera off" else "Turn camera on",
                background = if (state.cameraOn) colors.surfaceHover else colors.danger,
                tint = if (state.cameraOn) colors.textPrimary else Color.White,
                size = DOCK_BUTTON,
                onClick = { onEvent(CallEvent.ToggleCamera) },
            )
        }
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
private val END_BUTTON = 60.dp
private val DOCK_ELEVATION = 12.dp
private const val HOVER_MS = 120
private const val HOVER_LIFT = 0.12f
