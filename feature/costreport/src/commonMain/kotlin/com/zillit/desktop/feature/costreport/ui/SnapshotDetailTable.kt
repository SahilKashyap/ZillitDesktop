// One composable per row kind of the frozen table.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import com.zillit.desktop.feature.costreport.domain.SnapshotTable
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.SnapshotFigures
import com.zillit.desktop.feature.costreport.domain.SnapshotRow
import com.zillit.desktop.feature.costreport.domain.buildSnapshotTable

private val CODE_WIDTH: Dp = 88.dp
private val NAME_MIN: Dp = 150.dp
private val VALUE_MIN: Dp = 72.dp
private const val VALUE_COLUMNS = 11
private val ACTUALS_INK = Color(0xFF0C6A3F)
private val COMMITS_INK = Color(0xFF1D4ED8)
private val EFC_INK = Color(0xFF8A5B00)
private val BAND = Color(0xFFFDF2E2)
private val BAND_RULE = Color(0xFFEA7A0E)
private val ACTUALS_WASH = Color(0xFFECF7F0)

/**
 * The snapshot page's table — the web's `CostReportDetailTable`: Code and
 * Name, then Actuals (ATP · ATD), Commits (PO · Card · Cash · Payroll), ETC,
 * EFC, Budget, and Variance (Period · Total). A snapshot freezes neither the
 * period's actuals, ETC nor the period's movement, so those columns read "—".
 */
@Composable
internal fun SnapshotDetailTable(view: SnapshotView, callbacks: SnapshotCallbacks, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val detail = view.detail ?: return
    if (detail.lines.isEmpty()) {
        ZillitEmptyState(title = str(S.desktop_cr_no_line_data), modifier = modifier)
        return
    }
    val table = remember(view.sections, view.query, view.toggles) {
        buildSnapshotTable(view.sections, view.query, view.toggles)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitSearchField(
                value = view.query,
                onValueChange = callbacks.onSearch,
                placeholder = str(S.desktop_br_search_code_or_name),
                modifier = Modifier.width(320.dp),
            )
            if (table.search.active) {
                val n = table.search.matches
                ZillitText(
                    str(if (n == 1) S.desktop_cr_match_one else S.desktop_cr_match_many, n),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val widest = remember(table, view.symbol) { table.widestAmount(view.symbol) }
            val fitted = figureWidth(widest, amountStyle(FontWeight.Bold), AMOUNT_PAD * 2 + FIGURE_GUTTER)
            val longestCode = remember(table) { table.longestCode() }
            val codeWidth = minOf(CODE_MAX, maxOf(CODE_WIDTH, figureWidth(longestCode, codeStyle(), CODE_CHROME)))
            val valueWidth = maxOf(VALUE_MIN, fitted, (maxWidth - codeWidth - NAME_MIN) / VALUE_COLUMNS)
            val nameWidth = max(NAME_MIN, maxWidth - codeWidth - valueWidth * VALUE_COLUMNS)
            val scroll = rememberScrollState()
            val strip = remember(scroll, valueWidth, codeWidth) {
                SnapshotStrip(scroll, valueWidth * VALUE_COLUMNS, codeWidth)
            }
            CompositionLocalProvider(LocalSnapshotStrip provides strip) {
            Column(Modifier.fillMaxSize()) {
                HeadRow(nameWidth, valueWidth)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().slidesFigures(scroll)) {
                    items(table.rows, key = { it.key }) { row ->
                        when (row) {
                            is SnapshotRow.Section -> BandRow(
                                row,
                                table.search.needle,
                                nameWidth,
                                valueWidth,
                                view.symbol,
                            ) {
                                callbacks.onToggleSection(row.section.id)
                            }
                            is SnapshotRow.Header -> HeaderLine(
                                row,
                                table.search.needle,
                                nameWidth,
                                valueWidth,
                                view.symbol,
                            ) {
                                callbacks.onToggleHeader(row.header.code)
                            }
                            is SnapshotRow.Nominal -> FigureRow(
                                code = row.nominal.code,
                                name = row.nominal.name,
                                needle = table.search.needle,
                                figures = row.figures,
                                nameWidth = nameWidth,
                                valueWidth = valueWidth,
                                symbol = view.symbol,
                            )
                            is SnapshotRow.NoMatches -> Box(
                                Modifier.fillMaxWidth().padding(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ZillitText(
                                    str(S.desktop_cr_no_matches_query, row.query),
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
                TotalRow(table.total, nameWidth, valueWidth, view.symbol)
                ZillitHorizontalScrollRail(scroll, Modifier.padding(start = codeWidth + nameWidth))
            }
            }
        }
    }
}

/** The shared figures strip: the header's scroll, the eleven columns' width together, and the code column's. */
private class SnapshotStrip(val scroll: ScrollState, val width: Dp, val codeWidth: Dp)

/** The code column, as wide as its longest code up to [CODE_MAX]. */
@Composable
private fun codeColumn(): Dp = LocalSnapshotStrip.current.codeWidth

@Composable
private fun codeStyle() = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold)

/** The longest code or section id a row prints in the code column. */
private fun SnapshotTable.longestCode(): String = rows.mapNotNull { row ->
    when (row) {
        is SnapshotRow.Section -> row.section.id
        is SnapshotRow.Header -> row.header.code
        is SnapshotRow.Nominal -> row.nominal.code
        is SnapshotRow.NoMatches -> null
    }
}.maxByOrNull { it.length }.orEmpty()

private val CODE_MAX: Dp = 140.dp

/** The chevron, its gap and the cell's padding around a code. */
private val CODE_CHROME: Dp = 26.dp

private val LocalSnapshotStrip =
    staticCompositionLocalOf<SnapshotStrip> { error("SnapshotDetailTable provides the strip") }

@Composable
private fun RowScope.Figures(content: @Composable RowScope.() -> Unit) {
    val strip = LocalSnapshotStrip.current
    RowFigures(strip.scroll, strip.width, content = content)
}

@Composable
private fun amountStyle(weight: FontWeight) =
    ZillitTheme.typography.numeric.copy(fontWeight = weight, letterSpacing = (-0.2).sp)

/** The detail table's money: `−£1,234.56`. */
private fun amountText(amount: Double, symbol: String): String =
    (if (amount < 0) "−" else "") + symbol + Money.group(kotlin.math.abs(amount), 2)

/** The longest amount the table prints — what its value columns are sized to. */
private fun SnapshotTable.widestAmount(symbol: String): String {
    val figures = rows.mapNotNull { row ->
        when (row) {
            is SnapshotRow.Section -> row.figures
            is SnapshotRow.Header -> row.figures
            is SnapshotRow.Nominal -> row.figures
            is SnapshotRow.NoMatches -> null
        }
    } + total
    return figures
        .flatMap { listOf(it.atd, it.po, it.card, it.cash, it.pr, it.efc, it.budget, it.variance) }
        .filter { it != 0.0 }
        .map { amountText(it, symbol) }
        .maxByOrNull { it.length }
        .orEmpty()
}

private val AMOUNT_PAD: Dp = 4.dp

@Composable
private fun HeadRow(nameWidth: Dp, valueWidth: Dp) {
    val colors = ZillitTheme.colors
    val strip = LocalSnapshotStrip.current
    val style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    val sub = style.copy(fontSize = 10.sp)
    Column(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(Modifier.height(26.dp), verticalAlignment = Alignment.Bottom) {
                HeadCell(str(S.code).uppercase(), codeColumn(), style, colors.textSecondary, TextAlign.Start)
                HeadCell(str(S.name).uppercase(), nameWidth, style, colors.textSecondary, TextAlign.Start)
            }
            HeaderFigures(strip.scroll, strip.width) {
                Column {
                    Row(Modifier.height(26.dp), verticalAlignment = Alignment.Bottom) {
                        HeadCell(
                            str(S.desktop_actuals).uppercase(),
                            valueWidth * 2,
                            style,
                            ACTUALS_INK,
                            TextAlign.Center,
                            Color(0xFFECF7F0),
                        )
                        HeadCell(
                            str(S.desktop_cr_commits).uppercase(),
                            valueWidth * 4,
                            style,
                            COMMITS_INK,
                            TextAlign.Center,
                            Color(0xFFE9EFFF),
                        )
                        HeadCell(
                            str(S.desktop_cr_etc),
                            valueWidth,
                            style,
                            Color(0xFF7A4CD6),
                            TextAlign.End,
                            Color(0xFFF1EBFF),
                        )
                        HeadCell(str(S.desktop_cr_efc), valueWidth, style, EFC_INK, TextAlign.End, BAND)
                        HeadCell(str(S.budget_text).uppercase(), valueWidth, style, colors.textSecondary, TextAlign.End)
                        HeadCell(
                            str(S.desktop_variance).uppercase(),
                            valueWidth * 2,
                            style,
                            ACTUALS_INK,
                            TextAlign.Center,
                            Color(0xFFECF7F0),
                        )
                    }
                    Row(Modifier.height(22.dp), verticalAlignment = Alignment.Top) {
                        listOf(
                            "ATP",
                            "ATD",
                        ).forEach { HeadCell(it, valueWidth, sub, ACTUALS_INK, TextAlign.End, ACTUALS_WASH) }
                        listOf(
                            str(S.desktop_po),
                            str(S.ah_my_cards).uppercase(),
                            str(S.desktop_cr_cash).uppercase(),
                            str(S.dm_section_payroll).uppercase(),
                        ).forEach {
                            HeadCell(it, valueWidth, sub, COMMITS_INK, TextAlign.End, Color(0xFFE9EFFF))
                        }
                        HeadCell("", valueWidth, sub, colors.textMuted, TextAlign.End, Color(0xFFF1EBFF))
                        HeadCell("", valueWidth, sub, colors.textMuted, TextAlign.End, BAND)
                        HeadCell("", valueWidth, sub, colors.textMuted, TextAlign.End)
                        listOf(
                            str(S.cr_meta_period).uppercase(),
                            str(S.asset_total).uppercase(),
                        ).forEach { HeadCell(it, valueWidth, sub, ACTUALS_INK, TextAlign.End, ACTUALS_WASH) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.border))
    }
}

@Composable
private fun HeadCell(
    text: String,
    width: Dp,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    align: TextAlign,
    background: Color? = null,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeightSafe()
            .then(if (background != null && !ZillitTheme.colors.isDark) Modifier.background(background) else Modifier)
            .padding(horizontal = 4.dp),
        contentAlignment = when (align) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
    ) {
        ZillitText(text, style = style, color = color, maxLines = 1, textAlign = align)
    }
}

@Composable
private fun Modifier.fillMaxHeightSafe(): Modifier = this.then(Modifier.heightIn(min = 22.dp))

@Composable
private fun BandRow(
    row: SnapshotRow.Section,
    needle: String,
    nameWidth: Dp,
    valueWidth: Dp,
    symbol: String,
    onToggle: () -> Unit,
) {
    val ink = EFC_INK
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(BAND_RULE))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (ZillitTheme.colors.isDark) BAND_RULE.copy(alpha = 0.10f) else BAND)
                .clickable(onClick = onToggle)
                .heightIn(min = 38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.width(codeColumn()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight, tint = ink, size = 9.dp)
                Text(
                    highlighted(row.section.id, needle),
                    style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                highlighted(row.section.sec, needle),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = ink,
                modifier = Modifier.width(nameWidth).padding(horizontal = 4.dp),
            )
            Figures { Values(row.figures, valueWidth, symbol, weight = FontWeight.SemiBold, tint = ink) }
        }
    }
}

@Composable
private fun HeaderLine(
    row: SnapshotRow.Header,
    needle: String,
    nameWidth: Dp,
    valueWidth: Dp,
    symbol: String,
    onToggle: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onToggle)
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(codeColumn()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitIcon(
                if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = colors.textSecondary,
                size = 8.dp,
            )
            Text(
                highlighted(row.header.code, needle),
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            highlighted(row.header.name, needle),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
            color = colors.textPrimary,
            modifier = Modifier.width(nameWidth).padding(horizontal = 4.dp),
        )
        Figures { Values(row.figures, valueWidth, symbol, weight = FontWeight.SemiBold, tint = null) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun FigureRow(
    code: String,
    name: String,
    needle: String,
    figures: SnapshotFigures,
    nameWidth: Dp,
    valueWidth: Dp,
    symbol: String,
) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            highlighted(code, needle),
            style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp),
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.width(codeColumn()).padding(horizontal = 4.dp),
        )
        Text(
            highlighted(name, needle),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.width(nameWidth).padding(horizontal = 4.dp),
        )
        Figures { Values(figures, valueWidth, symbol, weight = FontWeight.Medium, tint = null) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

@Composable
private fun TotalRow(total: SnapshotFigures, nameWidth: Dp, valueWidth: Dp, symbol: String) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.border))
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(codeColumn()))
        ZillitText(
            str(S.grand_total).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            color = colors.textSecondary,
            modifier = Modifier.width(nameWidth).padding(horizontal = 4.dp),
        )
        Figures { Values(total, valueWidth, symbol, weight = FontWeight.Bold, tint = null) }
    }
}

/** ATP · ATD · PO · Card · Cash · Payroll · ETC · EFC · Budget · Period · Total. */
@Composable
private fun Values(figures: SnapshotFigures, width: Dp, symbol: String, weight: FontWeight, tint: Color?) {
    Amount(null, width, symbol, weight, tint)
    Amount(figures.atd, width, symbol, weight, tint ?: ACTUALS_INK.takeIf { figures.atd > 0 })
    Amount(figures.po, width, symbol, weight, tint ?: COMMITS_INK.takeIf { figures.po > 0 })
    Amount(figures.card, width, symbol, weight, tint ?: COMMITS_INK.takeIf { figures.card > 0 })
    Amount(figures.cash, width, symbol, weight, tint ?: COMMITS_INK.takeIf { figures.cash > 0 })
    Amount(figures.pr, width, symbol, weight, tint ?: COMMITS_INK.takeIf { figures.pr > 0 })
    Amount(null, width, symbol, weight, tint)
    Amount(
        figures.efc,
        width,
        symbol,
        FontWeight.Bold.takeIf { tint != null } ?: weight,
        tint ?: EFC_INK.takeIf { figures.efc > 0 },
    )
    Amount(figures.budget, width, symbol, weight, tint)
    Amount(null, width, symbol, weight, tint)
    Amount(
        figures.variance,
        width,
        symbol,
        FontWeight.Bold,
        if (figures.variance < 0) Color(0xFFB22A2A) else ACTUALS_INK,
    )
}

/** The detail table's money: `−£1,234.56`, a quiet dash for zero or for a column a snapshot does not keep. */
@Composable
private fun Amount(value: Double?, width: Dp, symbol: String, weight: FontWeight, tint: Color?) {
    val colors = ZillitTheme.colors
    val blank = value == null || value == 0.0
    Box(
        Modifier.width(width).padding(horizontal = AMOUNT_PAD, vertical = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ZillitText(
            text = value?.takeIf { !blank }?.let { amountText(it, symbol) } ?: CrFormat.DASH,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = if (blank) FontWeight.Normal else weight,
                letterSpacing = (-0.2).sp,
            ),
            color = if (blank) colors.textMuted.copy(alpha = 0.6f) else tint ?: colors.textPrimary,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}
