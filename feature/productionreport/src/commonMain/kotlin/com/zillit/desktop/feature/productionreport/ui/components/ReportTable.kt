// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * The lists' table — the web's `DataTable` (uppercase 11 px header on the
 * elevated tint, 1 px row rules) or, with [roomy], the Drafts antd table
 * (14 px header, hover tint). Cells are the caller's.
 */
@Composable
internal fun <T> ReportTable(
    columns: List<TableColumn>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    roomy: Boolean = false,
    minWidth: Dp = 880.dp,
    cell: @Composable RowScope.(row: T, column: Int, index: Int) -> Unit,
) {
    val colors = ReportTheme.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (roomy) 0.dp else 12.dp))
            .background(colors.surface)
            .then(if (roomy) Modifier else Modifier.border(1.dp, colors.border, RoundedCornerShape(12.dp))),
    ) {
        val width = if (maxWidth < minWidth) minWidth else maxWidth
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(
            Modifier
                .then(if (maxWidth < minWidth) Modifier.horizontalScroll(scroll) else Modifier)
                .width(width),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(if (roomy) colors.elevated else colors.elevated),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { column ->
                    Box(
                        modifier = column.sized(this).padding(
                            horizontal = if (roomy) 12.dp else 16.dp,
                            vertical = 12.dp,
                        ),
                        contentAlignment = column.boxAlignment(),
                    ) {
                        Text(
                            if (roomy) column.title else column.title.uppercase(),
                            style = if (roomy) reportText(
                                13.sp,
                                FontWeight.SemiBold,
                            ) else reportText(11.sp, FontWeight.SemiBold)
                                .copy(letterSpacing = 0.5.sp),
                            color = if (roomy) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            rows.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                val (source, hovered) = rememberHover()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (roomy && hovered) colors.hover else Color.Transparent)
                        .hoverable(source),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEachIndexed { c, column ->
                        Box(
                            modifier = column.sized(this).padding(
                                horizontal = if (roomy) 12.dp else 16.dp,
                                vertical = 12.dp,
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

private fun TableColumn.sized(scope: RowScope): Modifier = with(scope) {
    if (width != null) Modifier.width(width) else Modifier.weight(weight)
}

private fun TableColumn.boxAlignment(): Alignment = when (alignment) {
    Alignment.CenterHorizontally -> Alignment.Center
    Alignment.End -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

/** A cell's two-line person: name, and the designation under it when there is one. */
@Composable
internal fun PersonCell(name: String, designation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            name.ifBlank { "-" },
            style = reportText(13.sp, FontWeight.Medium),
            color = ReportTheme.colors.textPrimary,
            maxLines = 1,
        )
        if (designation.isNotBlank()) {
            Text(designation, style = reportText(12.sp), color = ReportTheme.colors.textMeta, maxLines = 1)
        }
    }
}

/** Plain secondary text in a cell. */
@Composable
internal fun MetaCell(text: String, strong: Boolean = false) {
    Text(
        text,
        style = reportText(if (strong) 13.sp else 12.sp, if (strong) FontWeight.Medium else FontWeight.Normal),
        color = if (strong) ReportTheme.colors.textPrimary else ReportTheme.colors.textPrimary,
        maxLines = 2,
    )
}
