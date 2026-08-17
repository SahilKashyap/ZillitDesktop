package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallSession

/**
 * The ring, incoming or outgoing.
 *
 * One card for both because visually it is one: the same person, the same
 * room, a different set of verbs underneath.
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
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                // Draggable, sharing the pill's offset: a ring that lands on
                // top of the thing you were reading can be pushed aside, and
                // the pill it becomes stays where you put it.
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        state.pillOffsetX.roundToInt(),
                        state.pillOffsetY.roundToInt(),
                    )
                }
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
                .background(colors.surfaceRaised)
                .padding(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            RipplingAvatar(session, incoming, loadAvatar)
            ZillitText(
                text = session.ringTitle,
                style = ZillitTheme.typography.titleLarge,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = session.ringSubtitle(incoming),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            RingActions(incoming, onEvent)
        }
    }
}

/** Accept and decline, or a lone cancel — the ring's whole vocabulary. */
@Composable
private fun RingActions(incoming: Boolean, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (incoming) {
            // Decline on the left: the destructive one should never be where
            // the hand lands when reaching for accept.
            CaptionedAction(
                icon = ZillitIcons.PhoneDown,
                caption = "Decline",
                background = colors.danger,
                onClick = { onEvent(CallEvent.Decline) },
            )
            CaptionedAction(
                icon = ZillitIcons.Phone,
                caption = "Accept",
                background = colors.success,
                onClick = { onEvent(CallEvent.Accept) },
            )
        } else {
            CaptionedAction(
                icon = ZillitIcons.PhoneDown,
                caption = "Cancel",
                background = colors.danger,
                onClick = { onEvent(CallEvent.HangUp) },
            )
        }
    }
}

/**
 * Expanding rings behind the face.
 *
 * Rings rather than a pulsing scale: scaling a photograph distorts a face
 * fifty times a second, and the thing that should read as "live" is the
 * signal, not the person.
 */
@Composable
private fun RipplingAvatar(
    session: CallSession,
    incoming: Boolean,
    loadAvatar: suspend (String) -> ImageBitmap?,
) {
    val colour = if (incoming) ZillitTheme.colors.success else ZillitTheme.colors.accent
    val transition = rememberInfiniteTransition(label = "ring-ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-ripple-phase",
    )
    val face by produceState<ImageBitmap?>(null, session.displayUserId) {
        value = session.displayUserId.takeIf(String::isNotBlank)?.let { loadAvatar(it) }
    }
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(RING_AVATAR + RIPPLE_ROOM)) {
            val base = RING_AVATAR.toPx() / 2f
            RIPPLE_OFFSETS.forEach { offset ->
                val progress = (phase + offset) % 1f
                drawCircle(
                    color = colour,
                    radius = base * (1f + RIPPLE_GROWTH * progress),
                    alpha = RIPPLE_ALPHA * (1f - progress),
                    style = Stroke(width = RIPPLE_STROKE.toPx()),
                )
            }
        }
        ZillitAvatar(name = session.ringTitle, image = face, size = RING_AVATAR)
    }
}

@Composable
private fun CaptionedAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    caption: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        RoundAction(
            icon = icon,
            label = caption,
            background = background,
            size = RING_BUTTON,
            onClick = onClick,
        )
        ZillitText(
            text = caption,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val CallSession.ringTitle: String
    get() = displayName.ifBlank { "Unknown caller" }

private fun CallSession.ringSubtitle(incoming: Boolean): String {
    if (!incoming) return "Calling…"
    val what = if (hasVideo) "Incoming video call" else "Incoming call"
    val where = if (mode == CallMode.Group) {
        title.takeIf { it.isNotBlank() && it != ringTitle }?.let { " · $it" }.orEmpty()
    } else {
        ""
    }
    return "$what$where"
}

private val CARD_WIDTH = 380.dp
private val CARD_CORNER = 24.dp
private val CARD_ELEVATION = 24.dp
private val RING_AVATAR = 96.dp
private val RING_BUTTON = 60.dp
private val RIPPLE_ROOM = 96.dp
private val RIPPLE_STROKE = 2.dp
private const val RIPPLE_MS = 2_200
private const val RIPPLE_GROWTH = 0.9f
private const val RIPPLE_ALPHA = 0.35f
private val RIPPLE_OFFSETS = listOf(0f, 0.33f, 0.66f)
