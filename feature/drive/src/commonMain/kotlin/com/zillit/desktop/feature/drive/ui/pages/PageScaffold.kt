package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll

/**
 * A page whose own content scrolls — a listing, a table.
 *
 * The distinction from [ScrollingPage] is load-bearing: a `ZillitDataTable`
 * virtualises with a `LazyColumn`, and a `LazyColumn` inside a scrolling column
 * is measured against an infinite constraint, which Compose refuses outright
 * rather than degrading. Pages that hold a table use this; pages that stack
 * cards use the other.
 */
@Composable
fun FixedPage(
    modifier: Modifier = Modifier,
    /** The widget passes less; a 500px window has no room for a 32px gutter. */
    padding: Dp = ZillitTheme.spacing.xl,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.spacedBy(
            if (padding < ZillitTheme.spacing.xl) ZillitTheme.spacing.sm else ZillitTheme.spacing.lg,
        ),
        content = content,
    )
}

/** A page that stacks sections and scrolls as a whole. */
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
