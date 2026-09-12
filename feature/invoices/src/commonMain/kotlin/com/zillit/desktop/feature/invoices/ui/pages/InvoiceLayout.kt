package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A row of headline figures that fills the pane — the web's `grid-cols-N`.
 *
 * Tiles share the width equally and a short last row is padded rather than
 * left ragged, because a `FlowRow` of fixed-width tiles leaves a gap on the
 * right of every screen in this module and wraps one tile onto a line of its
 * own the moment the pane narrows. Equal heights too, so a two-line label
 * does not make one tile taller than its neighbours.
 *
 * [columns] is the widest arrangement, not a fixed one. The tool is usually
 * read inside the Account Hub, where two sidebars leave the pane about half
 * the window: six columns there are ~125dp each, which turns "Total Invoices"
 * into "TOTAL INVOI…" and a six-figure total into "¥3,238,…". So the grid
 * drops a column at a time until each tile has [MIN_TILE] to work with.
 *
 * [tiles] is a list of composables rather than a `content` block because the
 * grid has to know how many there are to pad the last row.
 */
@Composable
internal fun StatGrid(
    columns: Int,
    tiles: List<@Composable RowScope.() -> Unit>,
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fits = (maxWidth / MIN_TILE).toInt().coerceAtLeast(MIN_COLUMNS)
        val across = columns.coerceAtLeast(1).coerceAtMost(fits)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            tiles.chunked(across).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    row.forEach { tile -> tile() }
                    // A short last row keeps its tiles the width of the ones
                    // above rather than stretching them across the pane.
                    repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Narrower than this and a tile clips its own label; below two columns it is a list. */
private val MIN_TILE = 186.dp
private const val MIN_COLUMNS = 2

/** What a tile in a [StatGrid] gets: an equal share of the row, at the row's height. */
internal val RowScope.statTile: Modifier get() = Modifier.weight(1f).fillMaxHeight()
