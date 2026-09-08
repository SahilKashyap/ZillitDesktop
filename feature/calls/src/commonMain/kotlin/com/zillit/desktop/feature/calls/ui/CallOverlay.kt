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
    /**
     * True when this overlay IS the call's own window, false when it is the
     * call drawn inside the main window.
     *
     * The two hosts want opposite things from the same state, and neither can
     * be inferred from it: the call window is always the full surface and
     * always owns the one browser component, while the main window must fall
     * back to the pill and keep its hands off the surface precisely when a
     * call window exists. Deriving this from `pipOpen` reads correctly in the
     * main window and backwards in the call window — where it is always true.
     */
    ownsCall: Boolean = false,
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
                if (drawsStage(ownsCall, state.pipOpen, state.expanded)) {
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
        //
        // Exactly one host mounts it, and [ownsCall] is which: there is a
        // single browser component, and a second host taking it would tear it
        // out of the first mid-call. The call window claims it whenever it is
        // open; the main window has it the rest of the time.
        val mounts = mountsVideo(ownsCall, state.pipOpen)
        if (videoSurface != null && state.videoMounted && mounts) {
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

/**
 * Whether this host draws the full stage rather than the pill.
 *
 * A pure function, and deliberately so: the same two surfaces read the same
 * state and want opposite things from it, and the last time that rule lived
 * only inside a Compose `if`, every video call rendered an empty rectangle
 * without a single test noticing.
 *
 * The call's own window is always the whole surface. Inside the main window, a
 * call that has its own window is just the pill — the way back to it — and the
 * stage is drawn only when the call actually lives here.
 */
internal fun drawsStage(ownsCall: Boolean, pipOpen: Boolean, expanded: Boolean): Boolean =
    ownsCall || (!pipOpen && expanded)

/**
 * Whether this host mounts the one browser component.
 *
 * There is a single surface and a second host taking it would tear it out of
 * the first mid-call, so exactly one of the two must answer true for any given
 * state: the call window claims it whenever it is open, the main window has it
 * the rest of the time.
 */
internal fun mountsVideo(ownsCall: Boolean, pipOpen: Boolean): Boolean =
    ownsCall || !pipOpen

/** The engine's surface, positioned onto whatever rectangle the chrome reserved. */
@Composable
private fun CallVideoLayer(slot: Rect, videoSurface: @Composable () -> Unit) {
    if (slot.width <= 0f || slot.height <= 0f) return
    val density = LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(slot.width.roundToInt(), slot.height.roundToInt()) {
        com.zillit.desktop.core.common.ZillitLog.i("Calls") {
            "video slot ${slot.width.roundToInt()}x${slot.height.roundToInt()} at ${slot.left.roundToInt()},${slot.top.roundToInt()}"
        }
    }
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
