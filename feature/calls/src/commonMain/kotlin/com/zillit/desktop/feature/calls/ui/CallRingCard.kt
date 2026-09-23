package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlin.math.roundToInt

/**
 * The ring, incoming or outgoing — the web's callee card and incoming toast
 * (`OutgoingCall.tsx:29-77`, `CallOverlays.tsx:60-96`) on one dark card.
 *
 * A floating corner card, never a scrim: an outgoing ring can stand for
 * forty seconds and an incoming one for thirty, and locking the whole
 * workspace for the duration turned every call into a modal freeze — the
 * one complaint every desktop softphone learned to avoid. The card keeps
 * its prominence through elevation, not through taking the app hostage.
 */
@Composable
fun CallRingCard(
    state: CallUiState,
    incoming: Boolean,
    onEvent: (CallEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
) {
    val session = state.session ?: return
    Box(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                // Draggable, sharing the pill's offset: a ring that lands on
                // top of the thing you were reading can be pushed aside, and
                // the pill it becomes stays where you put it.
                .offset { IntOffset(state.pillOffsetX.roundToInt(), state.pillOffsetY.roundToInt()) }
                // The card floats over a live workspace; a drag moves it, and
                // any other press stops here rather than reaching the tool
                // underneath.
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onEvent(CallEvent.DragPill(dragAmount.x, dragAmount.y))
                    }
                }
                .width(CARD_WIDTH)
                .shadow(CARD_ELEVATION, RoundedCornerShape(CARD_CORNER))
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(CallPalette.surface)
                .padding(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            session.ringContext(incoming)?.let { context ->
                ZillitText(
                    text = context.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = CallPalette.muted,
                    maxLines = 1,
                )
            }
            PulsingAvatar(session, loadAvatar)
            ZillitText(
                text = session.ringTitle,
                style = ZillitTheme.typography.titleLarge.copy(fontSize = NAME_FONT, fontWeight = FontWeight.SemiBold),
                color = CallPalette.text,
                maxLines = 1,
            )
            ZillitText(
                text = if (incoming) session.incomingRingSubtitle() else session.outgoingRingStatus(),
                style = ZillitTheme.typography.bodySmall,
                color = CallPalette.muted,
                maxLines = 1,
            )
            RingActions(incoming, session.hasVideo, onEvent)
        }
    }
}

/** Accept and decline, or a lone cancel — the ring's whole vocabulary. */
@Composable
private fun RingActions(incoming: Boolean, video: Boolean, onEvent: (CallEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (incoming) {
            // Decline on the left: the destructive one should never be where
            // the hand lands when reaching for accept.
            CaptionedAction(
                icon = ZillitIcons.PhoneDown,
                caption = str(S.decline),
                background = CallPalette.danger,
                onClick = { onEvent(CallEvent.Decline) },
            )
            // Green and breathing, with the camera glyph for a video call —
            // the web's `incomingPulse` (`CallOverlays.tsx:84-93`).
            CaptionedAction(
                icon = if (video) ZillitIcons.Camera else ZillitIcons.Phone,
                caption = str(S.accept),
                background = CallPalette.green,
                pulsing = true,
                onClick = { onEvent(CallEvent.Accept) },
            )
        } else {
            CaptionedAction(
                icon = ZillitIcons.PhoneDown,
                caption = str(S.cancel),
                background = CallPalette.danger,
                onClick = { onEvent(CallEvent.HangUp) },
            )
        }
    }
}

/**
 * The face, with two green rings growing out of it (`styles.css:2218-2229`:
 * `outPulse` 1.8s, the second ring 0.9s behind).
 *
 * Rings rather than a pulsing scale: scaling a photograph distorts a face
 * fifty times a second, and the thing that should read as "live" is the
 * signal, not the person.
 */
@Composable
private fun PulsingAvatar(session: CallSession, loadAvatar: suspend (String) -> ImageBitmap?) {
    val transition = rememberInfiniteTransition(label = "ring-pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-pulse-phase",
    )
    val face by produceState<ImageBitmap?>(null, session.displayUserId) {
        value = session.displayUserId.takeIf(String::isNotBlank)?.let { loadAvatar(it) }
    }
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(RING_AVATAR + PULSE_ROOM)) {
            val base = RING_AVATAR.toPx() / 2f
            PULSE_OFFSETS.forEach { offset ->
                val progress = (phase + offset) % 1f
                drawCircle(
                    color = CallPalette.green,
                    radius = base * (1f + PULSE_GROWTH * progress),
                    alpha = PULSE_ALPHA * (1f - progress),
                    style = Stroke(width = PULSE_STROKE.toPx()),
                )
            }
        }
        ZillitAvatar(name = session.ringTitle, image = face, size = RING_AVATAR)
    }
}

@Composable
private fun CaptionedAction(
    icon: ImageVector,
    caption: String,
    background: Color,
    onClick: () -> Unit,
    pulsing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "accept-pulse")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) ACCEPT_BREATH else 1f,
        animationSpec = infiniteRepeatable(tween(ACCEPT_PULSE_MS), RepeatMode.Reverse),
        label = "accept-breath",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        RoundAction(
            icon = icon,
            label = caption,
            background = background,
            size = RING_BUTTON,
            modifier = Modifier.scale(breath),
            onClick = onClick,
        )
        ZillitText(
            text = caption,
            style = ZillitTheme.typography.labelSmall,
            color = CallPalette.muted,
        )
    }
}

internal val CallSession.ringTitle: String
    get() = displayName.ifBlank { str(S.desktop_unknown_caller) }

/**
 * Where the ring comes from, above the name: the room on a group ring
 * (`CallOverlays.tsx:34-54`); nothing on a 1:1 ring, where the name is the story.
 */
internal fun CallSession.ringContext(incoming: Boolean): String? {
    if (!incoming || mode != CallMode.Group) return null
    return title.takeIf { it.isNotBlank() && it != ringTitle }
}

/**
 * The web's monotonic outgoing status (`CallOverlays.tsx:132-142`): `Calling…`
 * until the far end has answered, `Joining…` once someone is in the room.
 */
internal fun CallSession.outgoingRingStatus(): String =
    if (participants.any { it.userId != selfUserId && it.status == CallStatus.InCall }) {
        str(S.desktop_call_joining)
    } else {
        str(S.desktop_call_calling_ellipsis)
    }

/** "Incoming [group ]{audio|video} call…" (`CallOverlays.tsx:39`). */
internal fun CallSession.incomingRingSubtitle(): String {
    val group = mode == CallMode.Group
    return when {
        group && hasVideo -> str(S.desktop_call_incoming_group_video)
        group -> str(S.desktop_call_incoming_group_audio)
        hasVideo -> str(S.desktop_call_incoming_video)
        else -> str(S.desktop_call_incoming_audio)
    }
}

private val CARD_WIDTH = 380.dp
private val CARD_CORNER = 18.dp
private val CARD_ELEVATION = 24.dp
private val RING_AVATAR = 96.dp
private val RING_BUTTON = 44.dp
private val NAME_FONT = 24.sp
private val PULSE_ROOM = 120.dp
private val PULSE_STROKE = 2.dp
private const val PULSE_MS = 1_800
private const val PULSE_GROWTH = 1.1f
private const val PULSE_ALPHA = 0.55f
private val PULSE_OFFSETS = listOf(0f, 0.5f)
private const val ACCEPT_PULSE_MS = 800
private const val ACCEPT_BREATH = 1.08f
