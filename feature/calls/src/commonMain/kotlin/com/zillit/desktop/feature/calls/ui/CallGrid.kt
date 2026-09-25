package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The participant grid.
 *
 * Every cell is the largest 16:9 box that fits, and the block is centred — one
 * rule that produces a deliberate-looking layout at any count without a
 * special case per size. Rows are plain `Row`s with centred arrangement, so a
 * short last row centres itself rather than hanging left under a spacer.
 */
@Composable
fun AvatarGrid(
    tiles: List<CallTile>,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    pins: TilePins = TilePins(),
) {
    // One transition for the whole grid: twelve tiles each running their own
    // would be twelve animation nodes drifting out of phase with each other.
    val breathing = rememberInfiniteTransition(label = "speaking-pulse")
    val pulse by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "speaking-pulse-value",
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val shown = tiles.take(GRID_CAP)
        val overflow = tiles.size - shown.size
        val cells = shown.size + if (overflow > 0) 1 else 0
        if (cells == 0) return@BoxWithConstraints

        val layout = gridLayout(cells, maxWidth, maxHeight)
        val columns = layout.columns
        val tileWidth = layout.tileWidth
        val tileHeight = layout.tileHeight
        val avatar = layout.avatar

        Column(
            verticalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            shown.chunkedWithOverflow(columns, overflow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally)) {
                    row.forEach { entry ->
                        when (entry) {
                            is GridEntry.Person -> CallTileView(
                                tile = entry.tile,
                                avatarSize = avatar,
                                showChip = tileWidth >= NAME_MIN_WIDTH,
                                pulse = pulse,
                                modifier = Modifier.size(tileWidth, tileHeight),
                                image = rememberTileFace(entry.tile.userId, loadAvatar),
                                pins = pins,
                            )
                            is GridEntry.Overflow -> OverflowCell(
                                count = entry.count,
                                modifier = Modifier.size(tileWidth, tileHeight),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Two people: the other one fills the stage and you float in the corner —
 * the web's two-person layout (`CallRoom.tsx:1673-1697`, `styles.css:414-459`),
 * which is also what every phone does.
 *
 * The grid drew two postcard-sized tiles side by side and left most of the
 * window empty. Here the big tile is the largest 16:9 box the stage holds, and
 * the small one is 128 dp tall at most, bottom-right. Clicking the small tile
 * swaps the two, as the web's pin on it does; the choice is this view's own,
 * so it lasts as long as the two-person layout does.
 */
@Composable
fun DuoStage(
    tiles: List<CallTile>,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    pins: TilePins = TilePins(),
) {
    val self = tiles.firstOrNull { it.isSelf } ?: return
    val other = tiles.firstOrNull { !it.isSelf } ?: return
    var selfBig by remember { mutableStateOf(false) }
    FocusStage(
        focus = listOf(if (selfBig) self else other),
        others = listOf(if (selfBig) other else self),
        modifier = modifier,
        loadAvatar = loadAvatar,
        pins = pins,
        onMiniClick = { selfBig = !selfBig },
    )
}

/**
 * Somebody pinned: the pinned tiles take the stage and everyone else moves
 * to the strip in the corner — the web's pin (`CallRoom.tsx` `focusedIds`).
 * The strip's tiles carry their own pins; clicking one does not swap it in,
 * because the stage is what the user pinned, not what they last clicked.
 */
@Composable
fun PinnedStage(
    tiles: List<CallTile>,
    pins: TilePins,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
) {
    val pinned = pinnedTiles(tiles, pins.keys)
    FocusStage(
        focus = pinned,
        others = tiles.filterNot { tile -> pinned.any { it.key == tile.key } },
        modifier = modifier,
        loadAvatar = loadAvatar,
        pins = pins,
        onMiniClick = null,
    )
}

/**
 * The big-and-small stage both layouts above draw.
 *
 * One focus tile is the largest 16:9 box the stage holds, with the strip
 * floating over its corner as the web's `.duoSelf` does. Several share the
 * stage as a grid above the strip, which gets a band of its own — floated,
 * it would cover the corner of the last pinned tile.
 */
@Composable
private fun FocusStage(
    focus: List<CallTile>,
    others: List<CallTile>,
    modifier: Modifier,
    loadAvatar: suspend (String) -> ImageBitmap?,
    pins: TilePins,
    onMiniClick: ((CallTile) -> Unit)?,
) {
    val pulse by rememberInfiniteTransition(label = "focus-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "focus-pulse-value",
    )
    BoxWithConstraints(modifier = modifier) {
        val smallHeight = (maxHeight * DUO_SMALL_SHARE).coerceIn(DUO_SMALL_MIN, DUO_SMALL_MAX)
        val band = if (focus.size > 1 && others.isNotEmpty()) smallHeight + DUO_CORNER_GAP * 2 else 0.dp
        val main = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight - band)
        if (focus.size == 1) {
            val big = focus.single()
            val bigWidth = minOf(maxWidth - DUO_INSET * 2, (maxHeight - band - DUO_INSET * 2) * TILE_ASPECT)
                .coerceAtLeast(1.dp)
            val bigHeight = bigWidth / TILE_ASPECT
            Box(modifier = main, contentAlignment = Alignment.Center) {
                CallTileView(
                    tile = big,
                    avatarSize = (bigHeight * DUO_AVATAR_RATIO).coerceIn(AVATAR_MIN, DUO_AVATAR_MAX),
                    showChip = true,
                    pulse = pulse,
                    modifier = Modifier.size(bigWidth, bigHeight),
                    image = rememberTileFace(big.userId, loadAvatar),
                    pins = pins,
                )
            }
        } else {
            AvatarGrid(tiles = focus, modifier = main, loadAvatar = loadAvatar, pins = pins)
        }
        FocusStrip(
            others = others,
            smallHeight = smallHeight,
            maxWidth = maxWidth,
            pulse = pulse,
            loadAvatar = loadAvatar,
            pins = pins,
            onMiniClick = onMiniClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(DUO_CORNER_GAP),
        )
    }
}

/** Everyone not on the stage, small, bottom-right; a crowd past the width is a "+N". */
@Suppress("LongParameterList") // Geometry handed down from the stage's constraints.
@Composable
private fun FocusStrip(
    others: List<CallTile>,
    smallHeight: Dp,
    maxWidth: Dp,
    pulse: Float,
    loadAvatar: suspend (String) -> ImageBitmap?,
    pins: TilePins,
    onMiniClick: ((CallTile) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (others.isEmpty()) return
    val smallWidth = smallHeight * TILE_ASPECT
    val fits = ((maxWidth * STRIP_SHARE) / (smallWidth + GAP)).toInt().coerceAtLeast(1)
    val shown = if (others.size > fits) others.take(fits - 1) else others
    val overflow = others.size - shown.size
    val corner = RoundedCornerShape(CELL_CORNER)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        shown.forEach { tile ->
            CallTileView(
                tile = tile,
                avatarSize = (smallHeight * AVATAR_RATIO).coerceIn(DUO_SMALL_AVATAR_MIN, AVATAR_MAX),
                showChip = smallWidth >= NAME_MIN_WIDTH,
                pulse = pulse,
                modifier = Modifier
                    .shadow(DUO_ELEVATION, corner)
                    .then(if (onMiniClick != null) Modifier.clickable { onMiniClick(tile) } else Modifier)
                    .size(smallWidth, smallHeight),
                image = rememberTileFace(tile.userId, loadAvatar),
                pins = pins,
            )
        }
        if (overflow > 0) {
            OverflowCell(
                count = overflow,
                modifier = Modifier.shadow(DUO_ELEVATION, corner).size(smallWidth, smallHeight),
            )
        }
    }
}

/** Exactly us and one other person: the call the two-person layout is for. */
fun isDuo(tiles: List<CallTile>): Boolean = tiles.size == 2 && tiles.count { it.isSelf } == 1

/** Where every tile goes, and how big it is. */
data class GridLayout(
    val columns: Int,
    val rows: Int,
    val tileWidth: Dp,
    val tileHeight: Dp,
    val avatar: Dp,
)

/**
 * The grid's whole geometry, as a pure function.
 *
 * Separated from the composable because this is the part that can be quietly
 * wrong — a twelve-person grid that overflows its box, or tiles that stop
 * being 16:9 — and none of it is observable from inside `BoxWithConstraints`
 * without a running window.
 */
fun gridLayout(cells: Int, maxWidth: Dp, maxHeight: Dp): GridLayout {
    val columns = columnsFor(cells)
    val rows = (cells + columns - 1) / columns
    val cellWidth = (maxWidth - GAP * (columns - 1)) / columns
    val cellHeight = (maxHeight - GAP * (rows - 1)) / rows
    val tileWidth = minOf(cellWidth, cellHeight * TILE_ASPECT, TILE_MAX_WIDTH)
    return GridLayout(
        columns = columns,
        rows = rows,
        tileWidth = tileWidth,
        tileHeight = tileWidth / TILE_ASPECT,
        avatar = (tileWidth / TILE_ASPECT * AVATAR_RATIO).coerceIn(AVATAR_MIN, AVATAR_MAX),
    )
}

/** The "+4" cell: a crowd past the cap is a number, not twelve more faces. */
@Composable
private fun OverflowCell(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CELL_CORNER))
            .background(CallPalette.control),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = "+$count",
            style = ZillitTheme.typography.titleMedium,
            color = CallPalette.text,
        )
    }
}

/** What occupies one cell. */
private sealed interface GridEntry {
    data class Person(val tile: CallTile) : GridEntry
    data class Overflow(val count: Int) : GridEntry
}

private fun List<CallTile>.chunkedWithOverflow(
    columns: Int,
    overflow: Int,
): List<List<GridEntry>> {
    val entries = map<CallTile, GridEntry>(GridEntry::Person) +
        if (overflow > 0) listOf(GridEntry.Overflow(overflow)) else emptyList()
    return entries.chunked(columns)
}

/** The empty-stage line, so a one-person call is not a lone square. */
@Composable
fun WaitingForOthers(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ZillitText(
            text = str(S.desktop_call_waiting_for_others),
            style = ZillitTheme.typography.bodySmall,
            color = CallPalette.muted,
        )
    }
}

/** A face, fetched once per person and held for the tile's life. */
@Composable
private fun rememberTileFace(
    userId: String,
    load: suspend (String) -> ImageBitmap?,
): ImageBitmap? = produceState<ImageBitmap?>(null, userId) {
    value = userId.takeIf(String::isNotBlank)?.let { load(it) }
}.value

private val GAP = 8.dp
private val DUO_INSET = 8.dp
private val DUO_CORNER_GAP = 22.dp
private val DUO_SMALL_MIN = 72.dp
private val DUO_SMALL_MAX = 128.dp
private val DUO_SMALL_AVATAR_MIN = 28.dp
private val DUO_AVATAR_MAX = 150.dp
private val DUO_ELEVATION = 12.dp
private const val DUO_SMALL_SHARE = 0.22f
private const val STRIP_SHARE = 0.9f
private const val DUO_AVATAR_RATIO = 0.3f
private val TILE_MAX_WIDTH = 560.dp
private val AVATAR_MIN = 40.dp
private val AVATAR_MAX = 96.dp
private val CELL_CORNER = 12.dp
private const val TILE_ASPECT = 16f / 9f
private const val AVATAR_RATIO = 0.42f
private const val PULSE_MS = 1_400
