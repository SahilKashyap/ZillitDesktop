package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * One person on the stage.
 *
 * Everything that says something about them is anchored to the avatar rather
 * than the tile, so the composition reads the same at 306 dp and at 560 dp —
 * the grid resizes tiles freely and chrome pinned to tile corners drifts away
 * from the face it describes.
 */
@Composable
fun CallTileView(
    tile: CallTile,
    avatarSize: Dp,
    showChip: Boolean,
    pulse: Float,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
) {
    val colors = ZillitTheme.colors
    val ringing = tile.presence == CallStatus.Ringing
    val media = tile.media

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(if (ringing) colors.surfaceSunken else colors.surface)
            .border(TILE_BORDER, colors.border, RoundedCornerShape(TILE_CORNER))
            // Someone who has not picked up yet is present but not here; the
            // whole tile recedes rather than growing a second visual language.
            .alpha(if (ringing) RINGING_ALPHA else 1f),
    ) {
        Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            if (!ringing && media != null) {
                SpeakingRing(speaking = media.speaking, avatarSize = avatarSize, pulse = pulse)
            }
            ZillitAvatar(name = tile.name, image = image, size = avatarSize)
            if (!ringing && media?.audioMuted == true) {
                MuteBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = avatarSize * BADGE_INSET, top = avatarSize * BADGE_INSET),
                )
            }
        }

        if (showChip) {
            NameChip(
                text = if (ringing) "Ringing…" else tile.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(ZillitTheme.spacing.sm),
            )
        }

        if (!ringing && media?.quality?.isTrouble == true) {
            NetworkPip(
                quality = media.quality,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ZillitTheme.spacing.sm),
            )
        }

        if (!ringing && media?.sharing == true) {
            SharingChip(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/**
 * The ring that means "this person is talking".
 *
 * Fades in fast and out slowly on purpose: speech has gaps between words, and
 * a symmetric fade turns an ordinary sentence into a strobing halo. The
 * outward pulse is driven by one animation shared across every tile, so a
 * twelve-person grid breathes together and costs one animation node.
 */
@Composable
private fun SpeakingRing(speaking: Boolean, avatarSize: Dp, pulse: Float) {
    val alpha by animateFloatAsState(
        targetValue = if (speaking) 1f else 0f,
        animationSpec = tween(if (speaking) RING_IN_MS else RING_OUT_MS),
        label = "speaking-ring",
    )
    if (alpha <= 0f) return
    val colour = ZillitTheme.colors.success
    val diameter = avatarSize + RING_GAP * 2
    Canvas(modifier = Modifier.size(diameter + HALO_ROOM)) {
        val radius = (avatarSize.toPx() / 2f) + RING_GAP.toPx()
        drawCircle(
            color = colour,
            radius = radius,
            alpha = alpha,
            style = Stroke(width = RING_STROKE.toPx()),
        )
        drawCircle(
            color = colour,
            radius = radius * (1f + HALO_GROWTH * pulse),
            alpha = HALO_ALPHA * (1f - pulse) * alpha,
            style = Stroke(width = RING_STROKE.toPx()),
        )
    }
}

@Composable
private fun MuteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(ZillitTheme.colors.danger),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.MicOff,
            contentDescription = "Muted",
            tint = Color.White,
            size = BADGE_ICON,
        )
    }
}

@Composable
private fun NameChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(ZillitTheme.colors.surfaceSunken.copy(alpha = CHIP_ALPHA))
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SharingChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = "Sharing",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
        )
    }
}

/** Below this the chip crowds the face out; the roster panel carries names instead. */
val NAME_MIN_WIDTH = 120.dp

private val TILE_CORNER = 16.dp
private val TILE_BORDER = 1.dp
private val RING_STROKE = 2.5.dp
private val RING_GAP = 4.dp
private val HALO_ROOM = 24.dp
private val BADGE_SIZE = 20.dp
private val BADGE_ICON = 12.dp
private val CHIP_CORNER = 8.dp
private const val CHIP_ALPHA = 0.85f
private const val RINGING_ALPHA = 0.55f
private const val RING_IN_MS = 90
private const val RING_OUT_MS = 480
private const val HALO_GROWTH = 0.12f
private const val HALO_ALPHA = 0.32f
private const val BADGE_INSET = 0.02f
