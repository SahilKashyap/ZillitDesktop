package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText

/** Where a column's content sits. */
internal enum class BrAlign { Start, End, Center }

/**
 * One column of a [BrTable].
 *
 * Unlike the design system's table, the header is a slot too: two of this
 * module's tables put a select-all checkbox there, and one sorts on click.
 */
internal class BrColumn<T>(
    val header: String,
    val width: Dp? = null,
    val weight: Float = 1f,
    val align: BrAlign = BrAlign.Start,
    val headerContent: (@Composable () -> Unit)? = null,
    val onHeaderClick: (() -> Unit)? = null,
    val cell: @Composable (T) -> Unit,
)

/**
 * The web's plain table: a sunken header of small caps, hairline rows that
 * light on hover, and nothing striped.
 *
 * Composed in full rather than virtualised — these sit in pages that already
 * scroll, where a lazy list is handed an infinite height and throws. When the
 * columns need more than the pane, header and rows scroll sideways together.
 */
@Composable
internal fun <T> BrTable(
    rows: List<T>,
    columns: List<BrColumn<T>>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    onRowClick: ((T) -> Unit)? = null,
    isSelected: (T) -> Boolean = { false },
    minWeightWidth: Dp = 120.dp,
    rowPadding: Dp = 12.dp,
    empty: (@Composable () -> Unit)? = null,
) {
    val needed = columns.fold(0.dp) { sum, c -> sum + (c.width ?: (minWeightWidth * c.weight)) } +
        CELL_GAP * (columns.size - 1).coerceAtLeast(0) + ROW_INSET * 2
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = needed > maxWidth
        val across = rememberScrollState()
        Column(Modifier.fillMaxWidth()) {
            Column(if (wide) Modifier.horizontalScroll(across).width(needed) else Modifier.fillMaxWidth()) {
                HeaderRow(columns)
                ZillitDivider()
                if (rows.isEmpty()) {
                    empty?.invoke()
                } else {
                    rows.distinctBy(key).forEach { row ->
                        BodyRow(row, columns, onRowClick, isSelected(row), rowPadding)
                        ZillitDivider()
                    }
                }
            }
            if (wide) BrHorizontalRail(across)
        }
    }
}

@Composable
private fun <T> HeaderRow(columns: List<BrColumn<T>>) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken)
            .padding(horizontal = ROW_INSET, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(
                modifier = cellWidth(column).then(
                    column.onHeaderClick?.let { Modifier.clickable(onClick = it) } ?: Modifier,
                ),
                contentAlignment = column.align.box(),
            ) {
                column.headerContent?.invoke() ?: ZillitText(
                    text = column.header.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.08.em,
                    ),
                    color = colors.textMuted,
                    maxLines = 1,
                    textAlign = column.align.text(),
                )
            }
        }
    }
}

@Composable
private fun <T> BodyRow(
    row: T,
    columns: List<BrColumn<T>>,
    onRowClick: ((T) -> Unit)?,
    selected: Boolean,
    padding: Dp,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.surfaceSelected
            hovered && onRowClick != null -> colors.surfaceHover
            else -> Color.Transparent
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth().background(background)
            .then(
                if (onRowClick != null) {
                    Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null) {
                        onRowClick(row)
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ROW_INSET, vertical = padding),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(cellWidth(column), contentAlignment = column.align.box()) { column.cell(row) }
        }
    }
}

private fun RowScope.cellWidth(column: BrColumn<*>): Modifier =
    column.width?.let { Modifier.width(it) } ?: Modifier.weight(column.weight)

private fun BrAlign.box(): Alignment = when (this) {
    BrAlign.Start -> Alignment.CenterStart
    BrAlign.End -> Alignment.CenterEnd
    BrAlign.Center -> Alignment.Center
}

private fun BrAlign.text(): TextAlign = when (this) {
    BrAlign.Start -> TextAlign.Start
    BrAlign.End -> TextAlign.End
    BrAlign.Center -> TextAlign.Center
}

private val CELL_GAP = 14.dp
private val ROW_INSET = 16.dp
