package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The call, out of the way.
 *
 * Minimising must not interrupt the call, which rules out unmounting the video
 * surface: taking a native browser out of the tree hands it back to the
 * engine's parking window mid-conversation. So the pill reports a smaller slot
 * and the same surface simply shrinks into it.
 */
@Composable
fun CallPill(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    videoAvailable: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
) {
    val colors = ZillitTheme.colors
    val showsVideo = state.stage == CallStageKind.Video && videoAvailable
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            modifier = Modifier
                .padding(ZillitTheme.spacing.lg)
                .widthIn(min = PILL_MIN_WIDTH, max = PILL_MAX_WIDTH)
                .shadow(PILL_ELEVATION, RoundedCornerShape(PILL_CORNER))
                .clip(RoundedCornerShape(PILL_CORNER))
                .background(colors.surfaceRaised)
                .padding(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(THUMB_WIDTH, THUMB_HEIGHT)
                    .clip(RoundedCornerShape(THUMB_CORNER))
                    .background(colors.surfaceSunken)
                    .onGloballyPositioned(onSlot),
                contentAlignment = Alignment.Center,
            ) {
                if (!showsVideo) FaceStack(state.tiles)
            }
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = state.headerTitle,
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                ZillitText(
                    text = state.headerSubtitle,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            PillControls(state, onEvent)
        }
    }
}

/** Mute, end, expand — the only three verbs a minimised call needs. */
@Composable
private fun PillControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    RoundAction(
        icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
        label = if (state.micMuted) "Unmute" else "Mute",
        background = if (state.micMuted) colors.danger else colors.surfaceHover,
        tint = if (state.micMuted) Color.White else colors.textPrimary,
        size = PILL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleMic) },
    )
    RoundAction(
        icon = ZillitIcons.PhoneDown,
        label = "End call",
        background = colors.danger,
        size = PILL_BUTTON,
        onClick = { onEvent(CallEvent.HangUp) },
    )
    RoundAction(
        icon = ZillitIcons.Maximize,
        label = "Expand call",
        background = colors.surfaceHover,
        tint = colors.textPrimary,
        size = PILL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleStage) },
    )
}

/** Up to three faces, overlapped — enough to say who without a roster. */
@Composable
private fun FaceStack(tiles: List<CallTile>) {
    Row(horizontalArrangement = Arrangement.spacedBy(-FACE_OVERLAP)) {
        tiles.take(FACE_CAP).forEach { tile ->
            ZillitAvatar(name = tile.name, size = FACE_SIZE)
        }
    }
}

private val PILL_MIN_WIDTH = 280.dp
private val PILL_MAX_WIDTH = 360.dp
private val PILL_CORNER = 16.dp
private val PILL_ELEVATION = 12.dp
private val PILL_BUTTON = 36.dp
private val THUMB_WIDTH = 96.dp
private val THUMB_HEIGHT = 56.dp
private val THUMB_CORNER = 10.dp
private val FACE_SIZE = 28.dp
private val FACE_OVERLAP = 8.dp
private const val FACE_CAP = 3
