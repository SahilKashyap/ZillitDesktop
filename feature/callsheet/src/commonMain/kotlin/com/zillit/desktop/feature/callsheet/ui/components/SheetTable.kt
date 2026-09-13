// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/** A table of caller-drawn cells; wider than its box, it scrolls sideways. */
@Composable
internal fun <T> SheetTable(
    columns: List<TableColumn>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    style: TableStyle = TableStyle.Data,
    minWidth: Dp = 880.dp,
    onRowClick: ((T) -> Unit)? = null,
    cell: @Composable RowScope.(row: T, column: Int, index: Int) -> Unit,
) {
    val colors = SheetTheme.colors
    val csc = style == TableStyle.Csc
    val shape = RoundedCornerShape(if (csc) 14.dp else 12.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(if (csc && !colors.isDark) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(if (csc) colors.dsCard else colors.surface)
            .border(1.dp, if (csc) colors.dsBorder else colors.border, shape),
    ) {
        val narrow = maxWidth < minWidth
        val width = if (narrow) minWidth else maxWidth
        val scroll = rememberScrollState()
        Column(Modifier.then(if (narrow) Modifier.horizontalScroll(scroll) else Modifier).width(width)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(if (csc) colors.dsBgSecondary else colors.elevated),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { column ->
                    Box(
                        modifier = column.sized(this).padding(horizontal = 16.dp, vertical = if (csc) 11.dp else 12.dp),
                        contentAlignment = column.boxAlignment(),
                    ) {
                        Text(
                            column.title.uppercase(),
                            style = sheetText(11.sp, FontWeight.SemiBold)
                                .copy(letterSpacing = if (csc) 0.88.sp else 0.55.sp),
                            color = if (csc) colors.dsTextMuted else colors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (csc) colors.dsBorder else colors.border))
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    val rule = if (csc) colors.dsBorderLight else colors.border
                    Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
                }
                val (source, hovered) = rememberHover()
                val tint by animateColorAsState(
                    if ((csc || onRowClick != null) && hovered) colors.dsBgHover else Color.Transparent,
                    tween(ROW_FADE_MS),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tint)
                        .hoverable(source)
                        .then(
                            if (onRowClick != null) {
                                Modifier.plainClick(source = source) { onRowClick(row) }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEachIndexed { c, column ->
                        Box(
                            modifier = column.sized(this).padding(
                                horizontal = 16.dp,
                                vertical = if (csc) 13.dp else 12.dp,
                            ),
                            contentAlignment = column.boxAlignment(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) { cell(row, c, index) }
                        }
                    }
                }
            }
        }
    }
}

private const val ROW_FADE_MS = 150

private fun TableColumn.sized(scope: RowScope): Modifier = with(scope) {
    if (width != null) Modifier.width(width) else Modifier.weight(weight)
}

private fun TableColumn.boxAlignment(): Alignment = when (alignment) {
    Alignment.CenterHorizontally -> Alignment.Center
    Alignment.End -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

/** A cell's two-line person: name, and the designation under it — `.csc` sizes with [csc]. */
@Composable
internal fun PersonCell(name: String, designation: String, csc: Boolean = false) {
    val colors = SheetTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            name.ifBlank { "-" },
            style = sheetText(13.sp, if (csc) FontWeight.SemiBold else FontWeight.Medium),
            color = if (csc) colors.dsText else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (designation.isNotBlank()) {
            Text(
                designation,
                style = sheetText(if (csc) 11.5.sp else 12.sp),
                color = if (csc) colors.dsTextMuted else colors.textMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Plain secondary text in a cell. */
@Composable
internal fun MetaCell(text: String, csc: Boolean = false) {
    Text(
        text,
        style = sheetText(if (csc) 12.5.sp else 12.sp),
        color = if (csc) SheetTheme.colors.dsTextSecondary else SheetTheme.colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `td.csc-td-name`: a bold name with its created time under it. */
@Composable
internal fun NameCell(name: String, sub: String?) {
    val colors = SheetTheme.colors
    Column {
        Text(
            name.ifBlank { "—" },
            style = sheetText(13.sp, FontWeight.SemiBold),
            color = colors.dsText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                style = sheetText(11.5.sp),
                color = colors.dsTextMuted,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** `.csc-chip`: the Day cell's outlined pill. */
@Composable
internal fun DayChip(text: String) {
    val colors = SheetTheme.colors
    Text(
        text,
        style = sheetText(12.sp),
        color = colors.dsTextMuted,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.dsCard)
            .border(1.dp, colors.dsBorder, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
