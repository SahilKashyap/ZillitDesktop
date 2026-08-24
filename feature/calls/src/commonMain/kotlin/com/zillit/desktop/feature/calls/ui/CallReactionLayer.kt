package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlin.math.abs

/**
 * Reactions rising over the picture.
 *
 * Drawn on this side rather than inside the engine's page, so it works the
 * same on an audio call, an avatar grid and a video surface — the browser
 * component owns its own rectangle and nothing composed here reaches into it,
 * but this floats above the whole stage instead.
 *
 * Nothing here is stored. Each emoji animates once, reports itself finished,
 * and leaves state entirely; a reaction that is missed because the window was
 * closed is simply missed, which is what every other client does too.
 */
@Composable
fun CallReactionLayer(
    reactions: List<CallReaction>,
    onExpired: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        reactions.forEach { reaction ->
            key(reaction.key) {
                FloatingReaction(reaction = reaction, onExpired = { onExpired(reaction.key) })
            }
        }
    }
}

/**
 * One emoji's flight: up and fading.
 *
 * The horizontal offset is derived from the key rather than drawn at random,
 * so two reactions arriving together take different paths without this needing
 * a source of randomness — and so the same reaction lands in the same place on
 * every recomposition.
 */
@Composable
private fun BoxScope.FloatingReaction(reaction: CallReaction, onExpired: () -> Unit) {
    val rise = remember(reaction.key) { Animatable(0f) }
    val lane = remember(reaction.key) {
        // Spread across the lower half of the bar's width, deterministically.
        (abs(reaction.key.hashCode()) % LANES) - (LANES / 2)
    }

    LaunchedEffect(reaction.key) {
        // Picks up where the reaction actually is, not at zero. One that
        // arrived while the call was a thumbnail has already spent some or all
        // of its flight, and restarting it would replay a backlog on the way
        // back to the stage.
        val elapsed = kotlin.time.Clock.System.now().toEpochMilliseconds() - reaction.receivedAtMillis
        val remaining = CallReactions.FLIGHT_MILLIS - elapsed
        if (remaining <= 0) {
            onExpired()
            return@LaunchedEffect
        }
        rise.snapTo((elapsed.coerceAtLeast(0L).toFloat() / CallReactions.FLIGHT_MILLIS))
        rise.animateTo(
            targetValue = 1f,
            animationSpec = tween(remaining.toInt(), easing = LinearEasing),
        )
        onExpired()
    }

    val progress = rise.value
    // Fades over the last two-thirds: a reaction that starts translucent reads
    // as a rendering fault rather than as something arriving.
    val fade = if (progress < FADE_START) 1f else 1f - ((progress - FADE_START) / (1f - FADE_START))

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = FLOOR)
            .offset(
                x = (lane * LANE_WIDTH.value).dp,
                y = -(RISE.value * progress).dp,
            )
            .alpha(fade),
    ) {
        ZillitText(
            text = reaction.emoji,
            style = ZillitTheme.typography.titleLarge.copy(fontSize = EMOJI_SIZE),
            color = ZillitTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (reaction.name.isNotBlank()) {
            ZillitText(
                text = reaction.name,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textOnAccent,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.scrim)
                    .padding(
                        horizontal = ZillitTheme.spacing.xs,
                        vertical = ZillitTheme.spacing.xxs,
                    ),
            )
        }
    }
}

/**
 * The eight faces, in a pill above the dock.
 *
 * A fixed set rather than the full picker: everyone on the call has to render
 * whatever is sent, and this is the set the phones and the web client share.
 */
@Composable
fun CallReactionBar(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .shadow(BAR_ELEVATION, CircleShape)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .padding(
                horizontal = ZillitTheme.spacing.sm,
                vertical = ZillitTheme.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        CallReactions.Palette.forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(BAR_BUTTON)
                    .clip(CircleShape)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = emoji,
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/** How many horizontal lanes a reaction can take. Odd, so one is dead centre. */
private const val LANES = 7
private const val FADE_START = 0.35f
private val LANE_WIDTH = 26.dp
private val RISE = 230.dp
private val FLOOR = 24.dp
private val EMOJI_SIZE = 34.sp
private val BAR_BUTTON = 36.dp
private val BAR_ELEVATION = 10.dp
