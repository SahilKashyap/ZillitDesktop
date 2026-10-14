// The file is the gallery's grid; StillsPageCoords is only what it needs from the page.
@file:Suppress("MatchingDeclarationName")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a scrolling page's content is, so that something on it can tell how
 * far down the page it sits whatever the scroll — what the web gets for free
 * from `offsetTop`.
 */
internal class StillsPageCoords {
    private var content: LayoutCoordinates? = null

    /** Goes right after `verticalScroll`, so that it is the content that scrolls, not the window onto it. */
    val modifier: Modifier = Modifier.onPlaced { content = it }

    /** Where [of] is on the page, scroll aside; null until the page is placed. */
    fun offsetOf(of: LayoutCoordinates): Offset? {
        val page = content?.takeIf { it.isAttached } ?: return null
        return if (of.isAttached) page.localPositionOf(of, Offset.Zero) else null
    }
}

/**
 * The gallery below its heading: the cast list on the left, the filters on the
 * right, and the photos flowing round the list. While the list lasts they fill
 * the column beside it; once it ends they take the whole width. At 1000dp that
 * is two tiles beside the list and three under it.
 *
 * Each run of rows packs as many tiles as fit, `repeat(auto-fill, minmax([minCell],
 * 1fr))` as the web's grid does, so a tile beside the list is a little larger
 * than one under it. With no [side] (a narrow tool, where the list is a
 * dropdown in [head]) every row takes the whole width.
 *
 * The page scrolls as one sheet, as the web's `.stk-main` does, so this is not
 * a lazy grid: a LazyVerticalGrid has to be the scroller itself, and then the
 * list can only be a full-width row with the photos starting under it. Instead
 * the photos are windowed against the page's [scroll], as the web's
 * `VirtuosoGrid` is against its `customScrollParent`: only the rows in view and
 * [OVERSCAN] either side are composed. [onEnd] is its `endReached`: the last
 * row is composed, so the next page should load. It is asked again each time a
 * page arrives while the end is still in view.
 */
@Composable
internal fun <T> SFloatGrid(
    items: List<T>,
    keyOf: (T) -> Any,
    scroll: ScrollState,
    page: StillsPageCoords,
    side: (@Composable () -> Unit)?,
    head: @Composable () -> Unit,
    foot: @Composable () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    layout: FloatGridSizes = FloatGridSizes(),
    tile: @Composable (T) -> Unit,
) {
    // How far down the page this starts. Read on every frame the page moves;
    // only a change of layout above it changes it.
    var top by remember { mutableFloatStateOf(0f) }
    var atEnd by remember { mutableStateOf(false) }
    val ask by rememberUpdatedState(onEnd)
    LaunchedEffect(atEnd, items.size) { if (atEnd) ask() }

    // The tile lambda is read through a state, so that a tile's content can be
    // kept across frames (see TileContents) and picking still reaches it.
    val drawTile by rememberUpdatedState(tile)
    val contents = remember { TileContents<T> { item -> @Composable { drawTile(item) } } }
    val tracked = modifier.fillMaxWidth().onGloballyPositioned { at -> page.offsetOf(at)?.let { top = it.y } }

    SubcomposeLayout(tracked) { constraints ->
        val width = constraints.maxWidth
        val sideWidth = if (side != null) layout.side.roundToPx() else 0
        val headX = if (side != null) sideWidth + layout.sideGap.roundToPx() else 0

        val sidePlaced = side?.let { content ->
            subcompose(Slot.Side) { Column { content() } }.map { it.measure(Constraints.fixedWidth(sideWidth)) }
        }.orEmpty()
        val headPlaced = subcompose(Slot.Head) { Column { head() } }
            .map { it.measure(Constraints.fixedWidth(width - headX)) }
        val sideBottom = sidePlaced.sumOf { it.height }
        val headBottom = headPlaced.sumOf { it.height }

        val rows = FloatRows(
            count = items.size,
            width = width.toFloat(),
            besideX = headX.toFloat(),
            gap = layout.gap.toPx(),
            minCell = layout.minCell.toPx(),
            start = headBottom.toFloat(),
            clearAt = if (side != null) sideBottom + layout.sideGap.toPx() else 0f,
        )

        // The rows in view, and a margin either side.
        val overscan = OVERSCAN.toPx()
        val shown = rows.between(scroll.value - top - overscan, scroll.value + scroll.viewportSize - top + overscan)
        atEnd = rows.rows > 0 && shown.last == rows.rows - 1
        val tiles = contents.measure(this, rows, shown, items, keyOf)

        // "Loading more…" / "That's everything", under the last row and as wide as it.
        val footX = if (rows.rows > 0) rows.left(rows.rows - 1, rows.first(rows.rows - 1)).roundToInt() else headX
        val footPlaced = if (rows.rows > 0) {
            subcompose(Slot.Foot) { Box { foot() } }.map { it.measure(Constraints.fixedWidth(width - footX)) }
        } else {
            emptyList()
        }
        val gridBottom = rows.bottom.roundToInt()
        val height = max(max(sideBottom, headBottom), gridBottom + footPlaced.sumOf { it.height })

        layout(width, height) {
            var y = 0
            sidePlaced.forEach { it.place(0, y); y += it.height }
            y = 0
            headPlaced.forEach { it.place(headX, y); y += it.height }
            tiles.forEach { (placeable, x, tileY) -> placeable.place(x, tileY) }
            y = gridBottom
            footPlaced.forEach { it.place(footX, y); y += it.height }
        }
    }
}

/** The fixed measures of [SFloatGrid]: the side column, the gaps, and the smallest a tile may be. */
internal data class FloatGridSizes(
    val side: Dp = 240.dp,
    /** Between the side column and the rest; also how far under the column the full-width rows start. */
    val sideGap: Dp = 24.dp,
    val minCell: Dp = 230.dp,
    val gap: Dp = 14.dp,
)

/**
 * Where each row of square tiles goes: in the column beside the side list while
 * the list lasts (from [besideX]), then across the whole [width]. A row may only
 * take the whole width once it starts at or below [clearAt]; [start] is where
 * the first row starts. Each run packs as many tiles of at least [minCell] as
 * fit, and shares out what is left, as `minmax(…, 1fr)` does.
 */
internal class FloatRows(
    private val count: Int,
    width: Float,
    private val besideX: Float,
    private val gap: Float,
    minCell: Float,
    private val start: Float,
    clearAt: Float,
) {
    private val besideColumns = columnsIn(width - besideX, minCell, gap)
    private val besideCell = cellIn(width - besideX, besideColumns, gap)
    private val fullColumns = columnsIn(width, minCell, gap)
    private val fullCell = cellIn(width, fullColumns, gap)

    /** How many tiles sit beside the list: the rows that start above [clearAt], full or not. */
    private val besideCount = if (clearAt > start) {
        minOf(count, ceil((clearAt - start) / (besideCell + gap)).toInt() * besideColumns)
    } else {
        0
    }
    private val besideRows = (besideCount + besideColumns - 1) / besideColumns
    private val fullStart = start + besideRows * (besideCell + gap)

    val rows: Int = besideRows + (count - besideCount + fullColumns - 1) / fullColumns

    fun columns(row: Int): Int = if (row < besideRows) besideColumns else fullColumns

    fun cell(row: Int): Float = if (row < besideRows) besideCell else fullCell

    fun top(row: Int): Float = if (row < besideRows) {
        start + row * (besideCell + gap)
    } else {
        fullStart + (row - besideRows) * (fullCell + gap)
    }

    /** The first tile's index in [row]. */
    fun first(row: Int): Int = if (row < besideRows) {
        row * besideColumns
    } else {
        besideCount + (row - besideRows) * fullColumns
    }

    /** The tiles' indices in [row]. */
    fun indices(row: Int): IntRange {
        val end = if (row < besideRows) besideCount else count
        return first(row) until minOf(first(row) + columns(row), end)
    }

    /** Where tile [index] of [row] starts across. */
    fun left(row: Int, index: Int): Float {
        val x = if (row < besideRows) besideX else 0f
        return x + (index - first(row)) * (cell(row) + gap)
    }

    /** The bottom of the last row; [start] with no rows. */
    val bottom: Float get() = if (rows == 0) start else top(rows - 1) + cell(rows - 1)

    /** The rows that meet the stretch of page from [from] to [to], give or take one at each end. */
    fun between(from: Float, to: Float): IntRange = if (rows == 0) IntRange.EMPTY else rowAt(from)..rowAt(to)

    /** The last row starting at or above [y]; the first row above all of them. */
    fun rowAt(y: Float): Int {
        val row = if (besideRows > 0 && y < fullStart) {
            floor((y - start) / (besideCell + gap)).toInt()
        } else {
            besideRows + floor((y - fullStart) / (fullCell + gap)).toInt()
        }
        return row.coerceIn(0, max(rows - 1, 0))
    }

    private companion object {
        fun columnsIn(width: Float, minCell: Float, gap: Float): Int = max(1, ((width + gap) / (minCell + gap)).toInt())

        fun cellIn(width: Float, columns: Int, gap: Float): Float =
            ((width - gap * (columns - 1)) / columns).coerceAtLeast(0f)
    }
}

private enum class Slot { Side, Head, Foot }

/**
 * One content per tile, kept while the tile is in view. Handing a slot a fresh
 * lambda recomposes it, and the grid is measured on every frame of a scroll:
 * every tile on screen would be recomposed on every frame.
 */
private class TileContents<T>(private val contentOf: (T) -> @Composable () -> Unit) {
    private var kept: Map<Any, Pair<T, @Composable () -> Unit>> = emptyMap()

    /** Composes and measures the tiles of the [shown] rows: each with its place, as (tile, x, y). */
    fun measure(
        scope: SubcomposeMeasureScope,
        rows: FloatRows,
        shown: IntRange,
        items: List<T>,
        keyOf: (T) -> Any,
    ): List<Triple<Placeable, Int, Int>> {
        val now = HashMap<Any, Pair<T, @Composable () -> Unit>>()
        val tiles = shown.flatMap { row ->
            val cell = rows.cell(row).toInt()
            val y = rows.top(row).roundToInt()
            rows.indices(row).flatMap { index ->
                val item = items[index]
                val key = keyOf(item)
                // A tile whose photo changed gets a new content, and so is redrawn.
                val content = kept[key]?.takeIf { it.first == item } ?: (item to contentOf(item))
                now[key] = content
                val x = rows.left(row, index).roundToInt()
                scope.subcompose(key, content.second).map { Triple(it.measure(Constraints.fixed(cell, cell)), x, y) }
            }
        }
        kept = now
        return tiles
    }
}

/** Virtuoso's `overscan={800}`: rows this far out of view are composed already. */
private val OVERSCAN = 800.dp
