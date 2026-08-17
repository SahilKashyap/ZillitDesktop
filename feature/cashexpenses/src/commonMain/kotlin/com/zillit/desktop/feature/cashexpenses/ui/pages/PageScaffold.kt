package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ZillitStatTile

/**
 * The standard page body: scrolls, breathes, and stacks its sections.
 *
 * Pages that hold their own virtualised list (a queue, a register) use
 * [FixedPage] instead — nesting a `LazyColumn` inside a scrolling column gives
 * it unbounded height, which composes every row and defeats the point of
 * virtualising.
 */
@Composable
fun ScrollingPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .zillitVerticalScroll()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** As [ScrollingPage], for a page whose own content scrolls. */
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

/**
 * A row of KPI tiles that share the width evenly.
 *
 * Even shares rather than intrinsic widths: the figures change as data loads,
 * and tiles that resize themselves make the whole row jump on every refresh.
 */
@Composable
fun StatRow(
    tiles: List<StatTileSpec>,
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        tiles.forEach { tile ->
            ZillitStatTile(
                label = tile.label,
                value = tile.value,
                sub = tile.sub,
                tone = tile.tone,
                icon = tile.icon,
                onClick = tile.onClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One tile in a [StatRow]. */
data class StatTileSpec(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tone: com.zillit.desktop.core.designsystem.component.StatusTone? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: (() -> Unit)? = null,
)
