package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.calls.domain.CallPhase
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The calling surface, layered over the whole window.
 *
 * One composable routes every call state — the ring, the stage, the minimised
 * pill and the parting notice — because they are one feature with one
 * lifecycle, and splitting them across screens is how a ring outlives its call
 * on other clients.
 */
@Composable
fun CallOverlay(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /**
     * The media engine's own surface. Null on engines that carry no video;
     * the host decides what fills it, this overlay only places it.
     */
    videoSurface: (@Composable () -> Unit)? = null,
) {
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var slot by remember { mutableStateOf(Rect.Zero) }
    val onSlot: (LayoutCoordinates) -> Unit = { coords ->
        root?.takeIf { coords.isAttached }?.let { slot = it.localBoundingBoxOf(coords, false) }
    }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { root = it }) {
        when (state.phase) {
            CallPhase.Incoming -> CallRingCard(state, incoming = true, onEvent, loadAvatar)
            CallPhase.Outgoing -> CallRingCard(state, incoming = false, onEvent, loadAvatar)
            CallPhase.InCall, CallPhase.Ending ->
                if (state.expanded) {
                    CallStage(state, onEvent, loadAvatar, videoSurface != null, onSlot)
                } else {
                    CallPill(state, onEvent, videoSurface != null, onSlot)
                }
            CallPhase.Idle -> Unit
        }

        // ONE mount for the whole call, deliberately outside the branch above.
        // Taking a native browser out of the tree hands it back to the engine's
        // parking window mid-call, so minimising moves and resizes this instead
        // of re-creating it. The slot it follows is a real layout child, which
        // is what guarantees nothing Compose draws is ever inside it.
        if (videoSurface != null && state.videoMounted) {
            CallVideoLayer(slot, videoSurface)
        }

        state.endedNotice?.let { notice ->
            EndedNotice(
                text = notice,
                onDone = { onEvent(CallEvent.DismissNotice) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** The engine's surface, positioned onto whatever rectangle the chrome reserved. */
@Composable
private fun CallVideoLayer(slot: Rect, videoSurface: @Composable () -> Unit) {
    if (slot.width <= 0f || slot.height <= 0f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(slot.left.roundToInt(), slot.top.roundToInt()) }
            .size(
                width = with(density) { slot.width.toDp() },
                height = with(density) { slot.height.toDp() },
            )
            .clip(RoundedCornerShape(VIDEO_CORNER))
            .background(ZillitTheme.colors.canvas),
    ) {
        videoSurface()
    }
}

/**
 * The parting message.
 *
 * Drawn only once the phase is Idle, by which point the browser has already
 * unmounted — otherwise the notice would appear behind the video surface,
 * which is precisely where nobody would see it.
 */
@Composable
private fun EndedNotice(text: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        delay(NOTICE_MS)
        onDone()
    }
    Box(
        modifier = modifier
            .padding(top = ZillitTheme.spacing.xl)
            .shadow(NOTICE_ELEVATION, RoundedCornerShape(NOTICE_CORNER))
            .clip(RoundedCornerShape(NOTICE_CORNER))
            .background(ZillitTheme.colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

private val VIDEO_CORNER = 16.dp
private val NOTICE_CORNER = 16.dp
private val NOTICE_ELEVATION = 8.dp
private const val NOTICE_MS = 3_500L
