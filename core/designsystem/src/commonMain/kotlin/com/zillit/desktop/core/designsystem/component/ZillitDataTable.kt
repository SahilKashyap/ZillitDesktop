package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * How wide a column is: a fixed width, or a share of what is left over.
 *
 * Modelled rather than left as a nullable `Dp` so a column cannot specify
 * neither and silently collapse to nothing.
 */
sealed interface ColumnWidth {
    data class Fixed(val width: Dp) : ColumnWidth
    data class Weight(val weight: Float) : ColumnWidth
}

/**
 * One column of a [ZillitDataTable].
 *
 * [cell] renders the row's content rather than returning a string, so a column
 * can hold a status pill, an avatar or an action menu — which every real
 * finance table needs, and which a string-only table pushes into a second
 * component that then drifts.
 */
data class TableColumn<T>(
    val header: String,
    val width: ColumnWidth = ColumnWidth.Weight(1f),
    /** Money and counts read right-aligned; everything else left. */
    val numeric: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

/** A column of plain text — the common case. */
fun <T> textColumn(
    header: String,
    width: ColumnWidth = ColumnWidth.Weight(1f),
    numeric: Boolean = false,
    muted: Boolean = false,
    value: (T) -> String,
): TableColumn<T> = TableColumn(
    header = header,
    width = width,
    numeric = numeric,
    cell = { row ->
        ZillitText(
            text = value(row),
            style = if (numeric) ZillitTheme.typography.numeric else ZillitTheme.typography.bodyMedium,
            color = if (muted) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textPrimary,
            maxLines = 1,
            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    },
)

/**
 * The table every Account Hub queue and register is drawn with.
 *
 * ## Why one component rather than a table per page
 *
 * The web has eleven hand-built tables across these two modules, and they
 * disagree about row height, header case, hover feedback, striping, and what
 * an empty result looks like. Here the *columns* are data, so a new queue is a
 * list of [TableColumn]s and inherits every behaviour below for free:
 *
 *  - a header that stays above the body while it scrolls;
 *  - hover feedback on clickable rows, and none on rows that do nothing;
 *  - a selected row that stays visibly selected while a detail pane is open;
 *  - an empty state that says why it is empty rather than showing a void.
 *
 * Virtualised by default: a posting history runs to thousands of rows, and
 * composing them all is how a window stops scrolling. Set [virtualised] to
 * false for the short tables that sit inside an already-scrolling page — see
 * that parameter.
 */
@Composable
fun <T> ZillitDataTable(
    rows: List<T>,
    columns: List<TableColumn<T>>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    onRowClick: ((T) -> Unit)? = null,
    isSelected: ((T) -> Boolean)? = null,
    emptyTitle: String = str(S.desktop_nothing_here_yet),
    emptyMessage: String? = null,
    loading: Boolean = false,
    /**
     * Whether the table scrolls itself.
     *
     * False for a table inside a page that already scrolls — a dashboard's
     * "recent orders", a form's list of what you have already filed. Nesting a
     * scrolling list inside a scrolling column gives the list an *infinite*
     * height to measure against, which Compose refuses outright: the window
     * does not degrade, it throws. A short table composed in full is both
     * correct and cheap, so this is the fix rather than a workaround.
     */
    virtualised: Boolean = true,
    /**
     * How narrow a weighted column may be squeezed before the table scrolls
     * across instead.
     *
     * A table with more columns than its pane is wide used to shrink every
     * one of them until the text was unreadable. Below this, the columns keep
     * their width and the whole table — header and rows together — scrolls
     * sideways under a rail. Set it to zero to go back to squeezing.
     */
    minColumnWidth: Dp = MIN_COLUMN_WIDTH,
) {
    // A duplicate key would take the whole window down inside a LazyColumn.
    // Ids come from a server, and a server that repeats one is a bug worth a
    // report — but not worth an unusable queue, so the first row wins.
    val visible = if (key == null) rows else rows.distinctBy(key)
    val across = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // The content is as wide as the pane, or as wide as the columns need,
        // whichever is greater. A definite width matters: inside a horizontal
        // scroll the children are offered infinity, and `fillMaxWidth` there
        // measures against it.
        val pane = maxWidth
        val needed = columns.widthNeeded(minColumnWidth)
        // Only a table that genuinely overflows scrolls. Pinning the content
        // to the pane's own width instead rounds Dp back to pixels and can
        // land a fraction wide, which shows a rail with nothing behind it.
        val wide = needed > pane
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    // The weight leaves room for the horizontal rail below —
                    // but only where there is a height to divide. A
                    // non-virtualised table sits in a page that already
                    // scrolls, so its parent offers infinity, and a weighted
                    // child of an infinite column measures to nothing: the
                    // section drew its heading and then a void. Four modules
                    // put a short table inside a scrolling page.
                    .then(if (virtualised) Modifier.weight(1f, fill = false) else Modifier)
                    .then(if (wide) Modifier.horizontalScroll(across).width(needed) else Modifier.fillMaxWidth()),
            ) {
                TableBody(
                    visible = visible,
                    columns = columns,
                    key = key,
                    onRowClick = onRowClick,
                    isSelected = isSelected,
                    emptyTitle = emptyTitle,
                    emptyMessage = emptyMessage,
                    loading = loading,
                    virtualised = virtualised,
                )
            }
            if (wide) ZillitHorizontalScrollRail(across)
        }
    }
}

/** The sum of what every column needs: a fixed column its width, a weighted one its share. */
private fun <T> List<TableColumn<T>>.widthNeeded(minColumnWidth: Dp): Dp = fold(0.dp) { total, column ->
    total + when (val width = column.width) {
        is ColumnWidth.Fixed -> width.width
        is ColumnWidth.Weight -> minColumnWidth * width.weight
    }
}

@Suppress("LongParameterList") // The table's own parameters, passed straight through.
@Composable
private fun <T> ColumnScope.TableBody(
    visible: List<T>,
    columns: List<TableColumn<T>>,
    key: ((T) -> Any)?,
    onRowClick: ((T) -> Unit)?,
    isSelected: ((T) -> Boolean)?,
    emptyTitle: String,
    emptyMessage: String?,
    loading: Boolean,
    virtualised: Boolean,
) {
    run {
        TableHeader(columns)
        ZillitDivider()
        when {
            loading && visible.isEmpty() -> TableSkeleton(columns)
            visible.isEmpty() -> ZillitEmptyState(title = emptyTitle, message = emptyMessage)
            virtualised -> {
                val rowState = rememberLazyListState()
                LazyColumn(
                    state = rowState,
                    modifier = Modifier.fillMaxWidth().then(rememberWheelScroll(rowState)),
                ) {
                    itemsIndexed(
                        items = visible,
                        key = key?.let { extractor -> { _, row -> extractor(row) } },
                    ) { index, row ->
                        TableRow(
                            row = row,
                            columns = columns,
                            striped = index % 2 == 1,
                            selected = isSelected?.invoke(row) == true,
                            onClick = onRowClick?.let { click -> { click(row) } },
                        )
                        ZillitDivider()
                    }
                }
            }

            else -> Column(modifier = Modifier.fillMaxWidth()) {
                visible.forEachIndexed { index, row ->
                    TableRow(
                        row = row,
                        columns = columns,
                        striped = index % 2 == 1,
                        selected = isSelected?.invoke(row) == true,
                        onClick = onRowClick?.let { click -> { click(row) } },
                    )
                    ZillitDivider()
                }
            }
        }
    }
}

@Composable
private fun <T> TableHeader(columns: List<TableColumn<T>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(modifier = cellModifier(column.width)) {
                ZillitText(
                    text = column.header.uppercase(),
                    style = ZillitTheme.typography.columnHeader,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                    textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun <T> TableRow(
    row: T,
    columns: List<TableColumn<T>>,
    striped: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        selected -> colors.surfaceSelected
        hovered && onClick != null -> colors.surfaceHover
        striped -> colors.surfaceSunken
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .then(if (onClick != null) Modifier.hoverable(interaction) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(
                modifier = cellModifier(column.width),
                contentAlignment = if (column.numeric) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                column.cell(row)
            }
        }
    }
}

/**
 * Grey bars where the rows will be.
 *
 * A skeleton rather than a spinner: these tables usually resolve in well under
 * a second, and a spinner that appears and vanishes reads as a flicker. The
 * shape also tells the reader what is arriving.
 */
@Composable
private fun <T> TableSkeleton(columns: List<TableColumn<T>>) {
    Column {
        repeat(SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 1) ZillitTheme.colors.surfaceSunken else Color.Transparent)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                columns.forEach { column ->
                    Box(modifier = cellModifier(column.width)) { ZillitSkeletonBar() }
                }
            }
        }
    }
}

/** Applies a [ColumnWidth] inside the table's row. */
private fun RowScope.cellModifier(width: ColumnWidth): Modifier = when (width) {
    is ColumnWidth.Fixed -> Modifier.width(width.width)
    is ColumnWidth.Weight -> Modifier.weight(width.weight)
}

private const val SKELETON_ROWS = 6

/** Narrower than this and a column's text is unreadable, so the table scrolls instead. */
private val MIN_COLUMN_WIDTH = 110.dp
