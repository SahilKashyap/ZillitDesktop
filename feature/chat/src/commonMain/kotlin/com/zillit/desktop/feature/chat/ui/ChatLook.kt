package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/*
 * The Chat tool's surfaces, laid out the way WhatsApp Web lays them out —
 * grey bars over and under a papered thread, white rows and bubbles — and
 * coloured from the app's own theme, so the accent stays Zillit's orange.
 *
 * Kept in one place because the thread, the listing and the composer all
 * have to agree: a header bar one grey and the composer bar another reads as
 * two screens glued together.
 */

/** The bars: the thread's header, the composer, the empty right pane. */
internal val ZillitColors.chatPanel: Color get() = if (isDark) surfaceRaised else canvas

/** Behind the thread: warm paper in the light, near-black in the dark. */
internal val ZillitColors.chatWallpaper: Color get() = if (isDark) WALLPAPER_DARK else WALLPAPER_LIGHT

/** The doodles on the paper — present, never read. */
internal val ZillitColors.chatDoodle: Color get() = if (isDark) DOODLE_DARK else DOODLE_LIGHT

/** Their bubble: white on the paper, the raised grey in the dark. */
internal val ZillitColors.incomingBubble: Color get() = if (isDark) surfaceRaised else surface

/**
 * Our bubble: the accent laid over the incoming fill. Strong enough to tell
 * the two sides apart at a glance — the old OrangeSoft was so pale that a
 * thread read as one column of white.
 */
internal val ZillitColors.outgoingBubble: Color
    get() = accent.copy(alpha = if (isDark) OWN_DARK_ALPHA else OWN_LIGHT_ALPHA)
        .compositeOver(incomingBubble)

/** A listing row under the cursor. */
internal val ZillitColors.chatRowHover: Color get() = if (isDark) surfaceRaised else surfaceSunken

/**
 * The open conversation's row: the light theme's selected tint, and in the
 * dark a faint wash of the accent — the dark selected tint read as a brown bar.
 */
internal val ZillitColors.chatRowSelected: Color
    get() = if (isDark) accent.copy(alpha = SELECTED_DARK_ALPHA).compositeOver(surfaceRaised) else surfaceSelected

private val WALLPAPER_LIGHT = Color(0xFFEFEAE2)
private val WALLPAPER_DARK = Color(0xFF0F1115)
private val DOODLE_LIGHT = Color(0x1A7A5C3A)
private val DOODLE_DARK = Color(0x0FFFFFFF)
private const val OWN_LIGHT_ALPHA = 0.2f
private const val OWN_DARK_ALPHA = 0.3f
private const val SELECTED_DARK_ALPHA = 0.1f

/**
 * The thread's paper: a quiet field of production-flavoured doodles — the
 * camera, the clapper's clock, a call, a pin — scattered on a grid, each
 * nudged and turned by its cell so the tiling never reads as a grid.
 *
 * Drawn behind the list rather than as part of it, so scrolling moves the
 * messages over a still page, as WhatsApp's does.
 */
@Composable
internal fun ChatWallpaper(modifier: Modifier = Modifier) {
    val ink = ZillitTheme.colors.chatDoodle
    val icons = listOf(
        ZillitIcons.Camera, ZillitIcons.Chat, ZillitIcons.Phone, ZillitIcons.Smiley,
        ZillitIcons.Mic, ZillitIcons.StarOutline, ZillitIcons.Photo, ZillitIcons.Clock,
        ZillitIcons.Pin, ZillitIcons.Bell, ZillitIcons.Calendar, ZillitIcons.Send,
    ).map { rememberVectorPainter(it) }
    val filter = remember(ink) { ColorFilter.tint(ink) }

    Canvas(modifier.fillMaxSize()) {
        val cell = DOODLE_CELL.toPx()
        val glyph = DOODLE_SIZE.toPx()
        val columns = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (row in 0..rows) {
            for (column in 0..columns) {
                val seed = (row * PRIME_ROW + column * PRIME_COLUMN) and Int.MAX_VALUE
                val painter = icons[seed % icons.size]
                // Odd rows shift half a cell, then each glyph wanders inside
                // its cell: a brick bond, broken up.
                val shift = if (row % 2 == 1) cell / 2 else 0f
                val x = column * cell + shift + (seed % JITTER_STEPS) * cell / JITTER_DIVISOR
                val y = row * cell + (seed / JITTER_STEPS % JITTER_STEPS) * cell / JITTER_DIVISOR
                val turn = (seed % TURN_STEPS - TURN_STEPS / 2) * TURN_DEGREES
                translate(x, y) {
                    rotate(turn, pivot = androidx.compose.ui.geometry.Offset(glyph / 2, glyph / 2)) {
                        with(painter) { draw(Size(glyph, glyph), colorFilter = filter) }
                    }
                }
            }
        }
    }
}

private val DOODLE_CELL = 72.dp
private val DOODLE_SIZE = 22.dp
private const val PRIME_ROW = 7919
private const val PRIME_COLUMN = 104_729
private const val JITTER_STEPS = 5
private const val JITTER_DIVISOR = 12f
private const val TURN_STEPS = 7
private const val TURN_DEGREES = 8f

/**
 * A bubble with WhatsApp's tail: a small point at the top outer corner of a
 * run's first line, pointing at the writer's side of the thread.
 *
 * Every bubble reserves [tail] of width on its outer side, tail or not, so a
 * run's lines share one edge; the first line's point fills that margin.
 */
internal class BubbleShape(
    private val mine: Boolean,
    private val withTail: Boolean,
    private val radius: androidx.compose.ui.unit.Dp = BUBBLE_CORNER,
    private val tail: androidx.compose.ui.unit.Dp = BUBBLE_TAIL_WIDTH,
) : androidx.compose.ui.graphics.Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val r = with(density) { radius.toPx() }
        val t = with(density) { tail.toPx() }
        // The body, inset from the tail's margin.
        val left = if (mine) 0f else t
        val right = if (mine) size.width - t else size.width
        val path = androidx.compose.ui.graphics.Path()
        val body = androidx.compose.ui.geometry.RoundRect(
            left = left,
            top = 0f,
            right = right,
            bottom = size.height,
            topLeftCornerRadius = corner(if (withTail && !mine) 0f else r),
            topRightCornerRadius = corner(if (withTail && mine) 0f else r),
            bottomRightCornerRadius = corner(r),
            bottomLeftCornerRadius = corner(r),
        )
        path.addRoundRect(body)
        if (withTail) {
            // A point out of the square corner: along the top edge to the
            // margin's far side, then back down into the body's side.
            val tip = androidx.compose.ui.graphics.Path()
            if (mine) {
                tip.moveTo(right - 1f, 0f)
                tip.lineTo(size.width, 0f)
                tip.lineTo(right - 1f, t * TAIL_DROP)
            } else {
                tip.moveTo(left + 1f, 0f)
                tip.lineTo(0f, 0f)
                tip.lineTo(left + 1f, t * TAIL_DROP)
            }
            tip.close()
            path.addPath(tip)
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }

    private fun corner(px: Float) = androidx.compose.ui.geometry.CornerRadius(px, px)

    override fun equals(other: Any?): Boolean =
        other is BubbleShape && other.mine == mine && other.withTail == withTail &&
            other.radius == radius && other.tail == tail

    override fun hashCode(): Int = (mine.hashCode() * HASH + withTail.hashCode()) * HASH + radius.hashCode()
}

/** WhatsApp's bubble corner — rounder than a card, squarer than a pill. */
internal val BUBBLE_CORNER = 8.dp

/** The margin a bubble keeps on its outer side for the tail. */
internal val BUBBLE_TAIL_WIDTH = 8.dp

/** How far down the bubble's side the point comes back in, per tail width. */
private const val TAIL_DROP = 1.6f
private const val HASH = 31
