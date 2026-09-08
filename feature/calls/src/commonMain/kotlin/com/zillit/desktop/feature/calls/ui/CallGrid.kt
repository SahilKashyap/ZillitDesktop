package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText

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
            text = "Waiting for others to join",
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
private val TILE_MAX_WIDTH = 560.dp
private val AVATAR_MIN = 40.dp
private val AVATAR_MAX = 96.dp
private val CELL_CORNER = 12.dp
private const val TILE_ASPECT = 16f / 9f
private const val AVATAR_RATIO = 0.42f
private const val PULSE_MS = 1_400
