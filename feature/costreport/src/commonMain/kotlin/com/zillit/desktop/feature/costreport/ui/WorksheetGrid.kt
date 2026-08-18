// One composable per grid row kind; the cell rules branch on the column.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrColumnGroup
import com.zillit.desktop.feature.costreport.domain.CrFigures
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrRow
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.FigureMode
import com.zillit.desktop.feature.costreport.domain.TreeToggles

internal val ACCOUNT_WIDTH: Dp = 320.dp
internal val VALUE_WIDTH: Dp = 108.dp
internal val TABLE_WIDTH: Dp = ACCOUNT_WIDTH + VALUE_WIDTH * CrColumn.entries.size
private val ROW_HEIGHT: Dp = 30.dp
private val BAR_WIDTH: Dp = 4.dp
private val SET_INDENT: Dp = 28.dp
private val NOMINAL_INDENT: Dp = 14.dp
private val CELL_PAD: Dp = 8.dp

private val ACTUALS_ACCENT = Color(0xFF0C7A6E)
private val COMMITS_ACCENT = Color(0xFF1D4ED8)
private val FORECAST_ACCENT = Color(0xFFB95B00)
private val OVER_ACCENT = Color(0xFFB91C1C)
private val GRAND_TOTAL = Color(0xFF0C7A6E)
private val SECTION_COLOURS = mapOf(
    "atl" to Color(0xFFC9A84C),
    "prod" to Color(0xFF60A5FA),
    "post" to Color(0xFF2DD4BF),
    "other" to Color(0xFFFB923C),
    "cont" to Color(0xFF7E8FA8),
    CrSection.UNCODED_SECTION_ID to Color(0xFFDC2626),
)

/** What the grid needs from whichever tab hosts it. */
internal data class GridActions(
    val onSection: (String) -> Unit,
    val onHeader: (String) -> Unit,
    val onNominal: (String) -> Unit,
    /** Null in the posted-snapshot table, which has no ledger. */
    val onLedger: ((CrNominal, CrColumn?) -> Unit)? = null,
)

/**
 * The worksheet: a sticky account column would need a custom layout, so this
 * is a plain wide table that scrolls sideways as one piece — the two-row
 * grouped header, the rows, and the Grand Total pinned beneath them.
 */
@Composable
internal fun WorksheetGrid(
    rows: List<CrRow>,
    mode: FigureMode,
    symbol: String,
    projectName: String,
    actions: GridActions,
    modifier: Modifier = Modifier,
) {
    val body = rows.filterNot { it is CrRow.GrandTotal }
    val total = rows.lastOrNull { it is CrRow.GrandTotal } as? CrRow.GrandTotal
    Box(modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(TABLE_WIDTH).fillMaxHeight()) {
            GridHeader(mode)
            ZillitDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(body) { _, row -> GridRow(row, mode, symbol, actions) }
            }
            total?.let {
                ZillitDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT + CELL_PAD).background(GRAND_TOTAL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = "Grand Total — ${projectName.uppercase()}",
                        style = ZillitTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.width(ACCOUNT_WIDTH).padding(horizontal = CELL_PAD),
                    )
                    FigureCells(it.figures, mode, symbol, bold = true, tint = Color.White, dashed = emptySet())
                }
            }
        }
    }
}

@Composable
private fun GridHeader(mode: FigureMode) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ACCOUNT_WIDTH))
            CrColumnGroup.entries.forEach { group ->
                val span = CrColumn.entries.count { it.group == group }
                Box(
                    modifier = Modifier.width(VALUE_WIDTH * span).padding(horizontal = CELL_PAD, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (group.label.isNotEmpty()) {
                        ZillitText(
                            text = group.label.uppercase(),
                            style = ZillitTheme.typography.labelSmall,
                            color = group.accent() ?: colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ZillitText(
                text = "Account",
                style = ZillitTheme.typography.label,
                color = colors.textSecondary,
                modifier = Modifier.width(ACCOUNT_WIDTH).padding(horizontal = CELL_PAD, vertical = 4.dp),
            )
            CrColumn.entries.forEach { column ->
                Column(
                    modifier = Modifier.width(VALUE_WIDTH).padding(horizontal = CELL_PAD, vertical = 4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    ZillitText(text = column.line1, style = ZillitTheme.typography.labelSmall,
                        color = colors.textSecondary, maxLines = 1, textAlign = TextAlign.End)
                    val frozen = mode == FigureMode.Snapshot && !column.inSnapshot
                    val second = if (frozen) "${column.line2} (—)" else column.line2
                    if (second.isNotBlank()) {
                        ZillitText(text = second, style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted, maxLines = 1, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridRow(row: CrRow, mode: FigureMode, symbol: String, actions: GridActions) {
    when (row) {
        is CrRow.Section -> SectionRow(row, actions)
        is CrRow.Header -> HeaderRow(row, mode, symbol, actions)
        is CrRow.HeaderTotal -> HeaderTotalRow(row, mode, symbol)
        is CrRow.Nominal -> NominalRow(row, mode, symbol, actions)
        is CrRow.Set -> SetRow(row, symbol)
        is CrRow.GrandTotal -> Unit
    }
}

@Composable
private fun SectionRow(row: CrRow.Section, actions: GridActions) {
    val colors = ZillitTheme.colors
    val accent = SECTION_COLOURS[row.section.id.lowercase()] ?: colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT + CELL_PAD)
            .background(accent.copy(alpha = 0.14f))
            .clickable { actions.onSection(row.section.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(BAR_WIDTH).fillMaxHeight().background(accent))
        ZillitIcon(
            icon = if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            tint = colors.textSecondary,
            size = 14.dp,
            modifier = Modifier.padding(start = CELL_PAD),
        )
        ZillitText(
            text = row.section.sec.uppercase(),
            style = ZillitTheme.typography.titleSmall,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = CELL_PAD),
        )
    }
}

@Composable
private fun HeaderRow(row: CrRow.Header, mode: FigureMode, symbol: String, actions: GridActions) {
    val colors = ZillitTheme.colors
    val over = row.figures.tv < 0
    val accent = if (over) OVER_ACCENT else colors.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(colors.surfaceSunken.copy(alpha = 0.6f))
            .clickable { actions.onHeader(TreeToggles.headerKey(row.sectionId, row.header)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(ACCOUNT_WIDTH).padding(horizontal = CELL_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = colors.textMuted,
                size = 12.dp,
            )
            ZillitText(
                text = "${row.header.code}  ${row.header.name}".uppercase(),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
            )
        }
        if (row.open) {
            Spacer(Modifier.width(VALUE_WIDTH * CrColumn.entries.size))
        } else {
            FigureCells(row.figures, mode, symbol, bold = true, tint = null, dashed = emptySet())
        }
    }
    ZillitDivider()
}

@Composable
private fun HeaderTotalRow(row: CrRow.HeaderTotal, mode: FigureMode, symbol: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).background(colors.surfaceSunken.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "Total",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            modifier = Modifier.width(ACCOUNT_WIDTH).padding(start = CELL_PAD + NOMINAL_INDENT, end = CELL_PAD),
        )
        FigureCells(row.figures, mode, symbol, bold = true, tint = null, dashed = emptySet())
    }
    ZillitDivider()
}

@Composable
private fun NominalRow(row: CrRow.Nominal, mode: FigureMode, symbol: String, actions: GridActions) {
    val colors = ZillitTheme.colors
    val ledger = actions.onLedger
    val nominal = row.nominal
    val canDrill = ledger != null && !nominal.isBucket
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.width(ACCOUNT_WIDTH).padding(start = CELL_PAD + NOMINAL_INDENT, end = CELL_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (nominal.hasSets) {
                ZillitIcon(
                    icon = if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    tint = colors.textMuted,
                    size = 12.dp,
                    modifier = Modifier.clickable { actions.onNominal(TreeToggles.nominalKey(row.header, nominal)) },
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
            ZillitText(
                text = nominal.code,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Medium),
                color = if (canDrill) colors.accentText else colors.textSecondary,
                maxLines = 1,
                modifier = if (canDrill) Modifier.clickable { ledger?.invoke(nominal, null) } else Modifier,
            )
            ZillitText(
                text = nominal.name,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f).then(
                    if (nominal.hasSets) {
                        Modifier.clickable { actions.onNominal(TreeToggles.nominalKey(row.header, nominal)) }
                    } else {
                        Modifier
                    },
                ),
            )
        }
        CrColumn.entries.forEach { column ->
            val drill = canDrill && (column.isActuals || column.isCommits)
            FigureCell(
                text = cellText(row.figures, column, mode, symbol, dashed = false),
                tone = cellTone(row.figures.value(column), column),
                bold = false,
                modifier = if (drill) Modifier.clickable { ledger?.invoke(nominal, column) } else Modifier,
            )
        }
    }
    ZillitDivider()
}

@Composable
private fun SetRow(row: CrRow.Set, symbol: String) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.width(ACCOUNT_WIDTH).padding(start = CELL_PAD + SET_INDENT, end = CELL_PAD),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(text = row.set.code, style = ZillitTheme.typography.numeric, color = colors.textMuted,
                maxLines = 1)
            ZillitText(text = row.set.name, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary,
                maxLines = 1, modifier = Modifier.weight(1f))
        }
        val line = row.set.line
        CrColumn.entries.forEach { column ->
            val value = when (column) {
                CrColumn.Atp -> line.atp
                CrColumn.Atd -> line.atd
                CrColumn.Po -> line.po
                CrColumn.Card -> line.card
                CrColumn.Cash -> line.cash
                CrColumn.Pr -> line.pr
                else -> null
            }
            FigureCell(
                text = if (column.onSets) CrFormat.grid(value, symbol, column) else CrFormat.DASH,
                tone = null,
                bold = false,
            )
        }
    }
    ZillitDivider()
}

/** The eleven value cells of a header, total or grand-total row. */
@Composable
private fun FigureCells(
    figures: CrFigures,
    mode: FigureMode,
    symbol: String,
    bold: Boolean,
    tint: Color?,
    dashed: Set<CrColumn>,
) {
    CrColumn.entries.forEach { column ->
        FigureCell(
            text = cellText(figures, column, mode, symbol, dashed = column in dashed),
            tone = tint ?: cellTone(figures.value(column), column),
            bold = bold,
        )
    }
}

@Composable
private fun FigureCell(text: String, tone: Color?, bold: Boolean, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier.width(VALUE_WIDTH).height(ROW_HEIGHT).padding(horizontal = CELL_PAD),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ZillitText(
            text = text,
            style = if (bold) {
                ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
            } else {
                ZillitTheme.typography.numeric
            },
            color = tone ?: colors.textPrimary,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

private fun cellText(figures: CrFigures, column: CrColumn, mode: FigureMode, symbol: String, dashed: Boolean): String {
    val blank = dashed || (mode == FigureMode.Snapshot && !column.inSnapshot)
    return if (blank) CrFormat.DASH else CrFormat.grid(figures.value(column), symbol, column)
}

/** Variance columns are red when over and green when under; the rest read in the theme's ink. */
@Composable
private fun cellTone(value: Double, column: CrColumn): Color? {
    val colors = ZillitTheme.colors
    if (!column.isVariance || value == 0.0) return null
    return if (value < 0) colors.danger else colors.success
}

@Composable
private fun CrColumnGroup.accent(): Color? = when (this) {
    CrColumnGroup.Actuals -> ACTUALS_ACCENT
    CrColumnGroup.Commitments -> COMMITS_ACCENT
    CrColumnGroup.Forecast -> FORECAST_ACCENT
    CrColumnGroup.Budget, CrColumnGroup.Analysis -> null
}
