package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's scrollables, with the desktop furniture already on: the wheel
 * fix ([rememberWheelScroll]) and the [ZillitScrollRail] — scrollbar plus
 * up/down arrow buttons — overlaid on the right edge.
 *
 * Same shape as the raw containers so a call site swaps the name and keeps
 * its parameters; [modifier] lands on the outer box, so `weight`/`fillMax`
 * sizing behaves exactly as it did on the bare list. The rail paints nothing
 * while the content fits, so short lists look unchanged.
 */
@Composable
@Suppress("LongParameterList") // LazyColumn's own surface, passed through.
fun ZillitLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().then(rememberWheelScroll(state)),
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        ZillitScrollRail(state, Modifier.align(Alignment.CenterEnd), reverseLayout)
    }
}

/** [ZillitLazyColumn]'s grid sibling. */
@Composable
@Suppress("LongParameterList") // LazyVerticalGrid's own surface, passed through.
fun ZillitLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            modifier = Modifier.fillMaxWidth().then(rememberWheelScroll(state)),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
        ZillitScrollRail(state, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * A [Column] under [Modifier.zillitVerticalScroll], wearing the rail.
 *
 * For content built as a plain column — settings pages, detail panes. The
 * [contentPadding] is inside the scroller (so the rail hugs the true edge)
 * but outside the content, standing in for the padding call sites used to
 * chain after the scroll modifier.
 */
@Composable
fun ZillitScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zillitVerticalScroll(state)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        ZillitScrollRail(state, Modifier.align(Alignment.CenterEnd))
    }
}
