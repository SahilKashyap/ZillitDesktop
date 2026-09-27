package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
    pins: TilePins = TilePins(),
) {
    val ringing = tile.presence == CallStatus.Ringing
    val media = tile.media
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Their colour for THIS call — collision-free across the whole stage; see
    // CallTileColors. Letter-only when the tile is drawn outside a stage.
    val colour = LocalCallTileColors.current[tile.key] ?: CallTileColors.of(tile.name)

    Box(
        modifier = modifier
            .hoverable(hover)
            .clip(RoundedCornerShape(TILE_CORNER))
            // The camera-off tile is the person's own colour under a soft
            // white sheen, as the web's `gradientFor` paints it — not one flat
            // grey for everyone, which is what made a grid of avatars read as
            // one surface with faces floating on it. A tile still ringing keeps
            // the idle grey: they are not here yet, and colouring them in says
            // they are.
            .background(
                if (ringing) SolidColor(CallPalette.tileIdle) else tileWash(colour),
            )
            .border(TILE_BORDER, Color.Transparent, RoundedCornerShape(TILE_CORNER))
            // Someone who has not picked up yet is present but not here; the
            // whole tile recedes rather than growing a second visual language.
            .alpha(if (ringing) RINGING_ALPHA else 1f),
    ) {
        Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            if (!ringing && media != null) {
                SpeakingRing(speaking = media.speaking, avatarSize = avatarSize, pulse = pulse)
            }
            ZillitAvatar(name = tile.name, image = image, size = avatarSize, colour = colour)
            if (!ringing && media?.audioMuted == true) {
                MuteBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = avatarSize * BADGE_INSET, top = avatarSize * BADGE_INSET),
                )
            }
        }

        if (showChip) TileLabel(tile, ringing)

        // Directly under the avatar, where the eye already is: this person put
        // the call on hold and is deliberately silent, which reads as a
        // connection fault unless it is said.
        if (tile.onHold && !ringing) {
            HoldBadge(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = avatarSize * HOLD_BELOW_AVATAR),
            )
        }

        if (!ringing) {
            TileCornerChips(tile)
            pins.toggle?.let { toggle ->
                PinButton(
                    pinned = tile.key in pins.keys,
                    visible = hovered,
                    onClick = { toggle(tile.key) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(ZillitTheme.spacing.sm),
                )
            }
        }
    }
}

/**
 * The tile's name plate: who they are, and under it what they do.
 *
 * The designation is the web's `labelDesig` and it goes on its OWN line, never
 * beside the name — on a grid tile a second phrase alongside the name is the
 * first thing to be truncated, and it is the half that tells two people with
 * the same first name apart. A link guest has no job title in this production,
 * so the chip takes that line instead.
 */
@Composable
private fun BoxScope.TileLabel(tile: CallTile, ringing: Boolean) {
    NameChip(
        text = if (ringing) str(S.txt_ringing) else tile.name,
        detail = tile.designation.takeIf { it.isNotBlank() && !ringing && !tile.isGuest },
        guest = tile.isGuest && !ringing,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(ZillitTheme.spacing.sm),
    )
}

/**
 * Which tiles are pinned, and how to pin one — the stage's, handed to each
 * tile. A null [toggle] draws no pin at all (the pill's thumbnail).
 */
data class TilePins(
    val keys: List<String> = emptyList(),
    val toggle: ((String) -> Unit)? = null,
)

/**
 * The web tile's pin (`Tile.tsx`): shown on hover, and always once pinned
 * so a pinned tile says why it is big.
 *
 * Revealed by alpha, never composed on hover: a control that only exists
 * while the pointer is over it misses the press that arrives with it.
 */
@Composable
private fun PinButton(pinned: Boolean, visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .alpha(if (pinned || visible) 1f else 0f)
            .size(PIN_SIZE)
            .clip(CircleShape)
            .background(if (pinned) CallPalette.green else CallPalette.scrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.Pin,
            contentDescription = if (pinned) str(S.desktop_board_unpin) else str(S.desktop_board_pin),
            tint = Color.White,
            size = BADGE_ICON + 2.dp,
        )
    }
}

/**
 * The tile's corner chips — link trouble, sharing, and a raised hand.
 *
 * The hand sits under the sharing chip's corner, but they rarely coexist: a
 * hand up marks the face on the grid the way the banner names it in prose.
 */
@Composable
private fun BoxScope.TileCornerChips(tile: CallTile) {
    val media = tile.media
    if (media?.quality?.isTrouble == true) {
        NetworkPip(
            quality = media.quality,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ZillitTheme.spacing.sm),
        )
    }
    if (media?.sharing == true) {
        SharingChip(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(ZillitTheme.spacing.sm),
        )
    }
    if (tile.hand) {
        HandChip(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(ZillitTheme.spacing.sm)
                .padding(top = if (media?.sharing == true) HAND_BELOW_SHARING else 0.dp),
        )
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
    val colour = CallPalette.green
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
            .background(CallPalette.scrim),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.MicOff,
            contentDescription = str(S.desktop_muted),
            tint = Color.White,
            size = BADGE_ICON,
        )
    }
}

@Composable
private fun NameChip(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    guest: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(CallPalette.scrim)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        Column {
            ZillitText(
                text = text,
                style = ZillitTheme.typography.labelSmall,
                color = CallPalette.text,
                maxLines = 1,
            )
            // Its own line under the name, never beside it: on a small tile a
            // designation next to the name is the first thing to be truncated,
            // and it is the half that identifies the person.
            if (guest) {
                ZillitText(
                    text = str(S.txt_badge_guest),
                    style = ZillitTheme.typography.labelSmall,
                    color = CallPalette.amber,
                    maxLines = 1,
                )
            } else if (detail != null) {
                ZillitText(
                    text = detail,
                    style = ZillitTheme.typography.labelSmall,
                    color = CallPalette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "On hold" under the avatar — the web's `holdBadge`. */
@Composable
private fun HoldBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(CallPalette.scrim)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_call_on_hold),
            style = ZillitTheme.typography.labelSmall,
            color = CallPalette.amber,
            maxLines = 1,
        )
    }
}

/**
 * The camera-off tile's background — the web's `gradientFor`: the person's
 * colour at low opacity with a white sheen from the top left, and no fade to
 * black. A flat fill of the same colour is far too loud at tile size; the
 * sheen is what keeps eight of them from looking like a paint chart.
 */
private fun tileWash(colour: Color): Brush = Brush.linearGradient(
    colors = listOf(colour.copy(alpha = WASH_STRONG), colour.copy(alpha = WASH_FAINT)),
)

@Composable
private fun HandChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BADGE_SIZE + 4.dp)
            .clip(CircleShape)
            .background(CallPalette.amber),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.Hand,
            contentDescription = str(S.desktop_call_hand_raised),
            tint = Color.White,
            size = BADGE_ICON + 2.dp,
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
            text = str(S.sharing),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
        )
    }
}

/** Below this the chip crowds the face out; the roster panel carries names instead. */
val NAME_MIN_WIDTH = 120.dp

private val TILE_CORNER = 12.dp
private val TILE_BORDER = 2.dp
private val RING_STROKE = 2.5.dp
private val RING_GAP = 4.dp
private val HALO_ROOM = 24.dp
private val BADGE_SIZE = 20.dp
private val BADGE_ICON = 12.dp
private val CHIP_CORNER = 8.dp
private const val RINGING_ALPHA = 0.55f
private const val RING_IN_MS = 90
private const val RING_OUT_MS = 480
private const val HALO_GROWTH = 0.12f
private const val HALO_ALPHA = 0.32f
private const val BADGE_INSET = 0.02f
private val HAND_BELOW_SHARING = 28.dp

/** The web's `gradientFor` washes the colour in at 0x73 and out at 0x3d. */
private const val WASH_STRONG = 0.45f
private const val WASH_FAINT = 0.24f

/** How far below the avatar's centre the hold badge sits, as a fraction of it. */
private const val HOLD_BELOW_AVATAR = 0.72f
private val PIN_SIZE = 26.dp
