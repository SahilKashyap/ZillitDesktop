package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.roundToInt

/**
 * The call, out of the way.
 *
 * Minimising must not interrupt the call, which rules out unmounting the video
 * surface: taking a native browser out of the tree hands it back to the
 * engine's parking window mid-conversation. So the pill reports a smaller slot
 * and the same surface simply shrinks into it.
 *
 * Draggable: it floats over the workspace, and a floating thing that cannot
 * be moved off what you need to see is an obstruction, not a convenience.
 * The offset is view-model state so it survives the pill's recomposition
 * and a round-trip through the stage.
 */
@Composable
fun CallPill(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    videoAvailable: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
) {
    val colors = ZillitTheme.colors
    val showsVideo = state.stage == CallStageKind.Video && videoAvailable && !state.pipOpen
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            modifier = Modifier
                .padding(ZillitTheme.spacing.lg)
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        state.pillOffsetX.roundToInt(),
                        state.pillOffsetY.roundToInt(),
                    )
                }
                .widthIn(min = PILL_MIN_WIDTH, max = PILL_MAX_WIDTH)
                .shadow(PILL_ELEVATION, RoundedCornerShape(PILL_CORNER))
                .clip(RoundedCornerShape(PILL_CORNER))
                .background(colors.surfaceRaised)
                // The whole pill is the handle: buttons take their own
                // presses, and a drag anywhere else moves it.
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onEvent(CallEvent.DragPill(dragAmount.x, dragAmount.y))
                    }
                }
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

/** Mute, end, pop out, expand — the verbs a minimised call needs. */
@Composable
private fun PillControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    RoundAction(
        icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
        label = if (state.micMuted) str(S.desktop_unmute) else str(S.desktop_mute),
        background = if (state.micMuted) colors.danger else colors.surfaceHover,
        tint = if (state.micMuted) Color.White else colors.textPrimary,
        size = PILL_BUTTON,
        onClick = { onEvent(CallEvent.ToggleMic) },
    )
    // Only a video call has a picture to pop out; the pill of an audio call
    // is already as small as the call gets.
    if (state.stage == CallStageKind.Video) {
        RoundAction(
            icon = ZillitIcons.Detach,
            label = if (state.pipOpen) str(S.desktop_call_bring_video_back) else str(S.desktop_call_pop_out_video),
            background = if (state.pipOpen) colors.surfaceSelected else colors.surfaceHover,
            tint = colors.textPrimary,
            size = PILL_BUTTON,
            onClick = { onEvent(CallEvent.TogglePip) },
        )
    }
    RoundAction(
        icon = ZillitIcons.PhoneDown,
        label = str(S.desktop_end_call),
        background = colors.danger,
        size = PILL_BUTTON,
        onClick = { onEvent(CallEvent.HangUp) },
    )
    RoundAction(
        icon = ZillitIcons.Maximize,
        label = str(S.desktop_call_expand_call),
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
            ZillitAvatar(name = tile.name, userId = tile.userId, size = FACE_SIZE)
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
