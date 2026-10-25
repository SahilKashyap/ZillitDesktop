package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn

/**
 * A page whose own content scrolls — a listing, a table.
 *
 * The distinction matters: a `LazyColumn` inside a scrolling column is measured
 * against infinite height, which Compose refuses outright rather than degrading.
 * Pages that stack cards use [ScrollingPage]; pages that hold a list use this.
 */
@Composable
fun FixedPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** A page that stacks sections and scrolls as a whole. */
@Composable
fun ScrollingPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/**
 * Widens a child by [horizontal] on each side and shifts it left by the same,
 * so a bar spans the page edge to edge from inside a padded page — the web's
 * full-bleed `.dd-topcard`, whose negative side margins cancel `.dd-page`'s
 * horizontal padding. Pass the page's own padding so the bar's content stays
 * on the same column as the listing below it.
 *
 * A modifier rather than a slot outside [FixedPage]'s padding: the bar sits
 * *below* the how-to banner, which keeps the column, so the two cannot simply
 * be reordered. The child reports the padded width, so nothing else in the
 * column moves. Same idiom as Transport Hub's `bleed`.
 */
internal fun Modifier.fullBleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val plain = measurable.measure(constraints)
        return@layout layout(plain.width, plain.height) { plain.place(0, 0) }
    }
    val h = horizontal.roundToPx()
    val wide = constraints.maxWidth + 2 * h
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-h, 0) }
}
