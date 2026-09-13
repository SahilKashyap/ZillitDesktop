package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.TableColumn
import com.zillit.desktop.feature.costreport.domain.analytics.TableRow
import kotlin.math.max

/** `table`: a titled panel over a data table whose columns pick their cell renderer. */
@Composable
internal fun TableBlock(block: AnalyticsBlock.Table, context: BlockContext) {
    val colors = analyticsColors
    Panel {
        OptionalSectionLabel(
            dot = block.heading.dot?.let(colors::toneHex) ?: colors.amber,
            title = block.heading.title,
            sub = block.heading.sub,
            right = block.link?.let { link -> { TableLinkText(link.label, link.href, context, arrow = true) } },
        )
        DataTable(block.columns, block.rows, context)
    }
}

/**
 * An HTML table's automatic layout: every column gets its widest content,
 * extra width is shared in proportion, and when the panel is too narrow the
 * columns give up width down to their narrowest — body text wraps, headers
 * never do. All cells in a row share its height, so the rules line up.
 */
@Composable
private fun DataTable(columns: List<TableColumn>, rows: List<TableRow>, context: BlockContext) {
    if (columns.isEmpty()) return
    val colors = analyticsColors
    val policy = remember(columns.size) { autoTablePolicy(columns.size) }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        measurePolicy = policy,
        content = {
            columns.forEach { column ->
                Box(
                    modifier = Modifier
                        .bottomRule(true, colors.line)
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    contentAlignment = column.alignment(),
                ) {
                    ZillitText(
                        column.label.uppercase(),
                        style = AnalyticsType.mono(10f, FontWeight.Bold, 0.08f),
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
            }
            rows.forEachIndexed { r, row ->
                columns.forEach { column ->
                    Box(
                        modifier = Modifier
                            .bottomRule(r < rows.lastIndex, colors.line)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = column.alignment(),
                    ) {
                        TableCell(column, row, context)
                    }
                }
            }
        },
    )
}

/** Cells in row-major order, [count] to a row, the first row the headers. */
private fun autoTablePolicy(count: Int) = MeasurePolicy { measurables, constraints ->
    val widest = IntArray(count)
    val narrowest = IntArray(count)
    measurables.forEachIndexed { i, cell ->
        val c = i % count
        val max = cell.maxIntrinsicWidth(Constraints.Infinity)
        widest[c] = max(widest[c], max)
        // Headers do not wrap; their widest is also their narrowest.
        narrowest[c] = max(narrowest[c], if (i < count) max else cell.minIntrinsicWidth(Constraints.Infinity))
    }
    // Unbounded only when a parent asks how wide the table would like to be: its widest content.
    val available = if (constraints.hasBoundedWidth) constraints.maxWidth else widest.sum()
    val widths = columnWidths(widest, narrowest, available)
    val placed = measurables.chunked(count).map { cells ->
        val height = cells.mapIndexed { c, cell -> cell.maxIntrinsicHeight(widths[c]) }.max()
        cells.mapIndexed { c, cell -> cell.measure(Constraints.fixed(widths[c], height)) }
    }
    layout(max(available, widths.sum()), placed.sumOf { it.first().height }) {
        var y = 0
        placed.forEach { row ->
            var x = 0
            row.forEachIndexed { c, placeable ->
                placeable.place(x, y)
                x += widths[c]
            }
            y += row.first().height
        }
    }
}

/** Widest content plus a proportional share of spare width; squeezed towards the narrowest when short. */
internal fun columnWidths(widest: IntArray, narrowest: IntArray, available: Int): IntArray {
    val maxTotal = widest.sum()
    val minTotal = narrowest.sum()
    return when {
        maxTotal <= available -> {
            val spare = available - maxTotal
            IntArray(widest.size) { c ->
                widest[c] + if (maxTotal == 0) spare / widest.size else (spare.toLong() * widest[c] / maxTotal).toInt()
            }
        }
        minTotal < available -> {
            val give = maxTotal - minTotal
            val room = available - minTotal
            IntArray(widest.size) { c -> narrowest[c] + ((widest[c] - narrowest[c]).toLong() * room / give).toInt() }
        }
        else -> narrowest.copyOf()
    }
}

private fun TableColumn.alignment(): Alignment = when (align) {
    "right" -> Alignment.CenterEnd
    "center" -> Alignment.Center
    else -> Alignment.CenterStart
}

private fun TableColumn.textAlign(): TextAlign = when (align) {
    "right" -> TextAlign.End
    "center" -> TextAlign.Center
    else -> TextAlign.Start
}

/** `column.cell` → its renderer; any other cell prints the value, formatted when the column has a `fmt`. */
@Composable
private fun TableCell(column: TableColumn, row: TableRow, context: BlockContext) {
    val raw = row[column.key]
    when (column.cell) {
        "card-ref" -> CardRefCell(raw.orEmpty(), row["holder"])
        "progress" -> ProgressCell(raw, column, row, context)
        "status-pill" -> StatusPill(raw.orEmpty(), row["tone"] ?: "grey")
        "avatar" -> AvatarCell(raw.orEmpty(), row["role"] ?: row["sub"])
        "method-chip" -> MethodChip(raw.orEmpty())
        "link" -> TableLinkText(raw.orEmpty(), row["href"], context, arrow = false)
        else -> PlainCell(column, raw, context)
    }
}

/** `percent`, `mono`, `plain` and anything unknown: text in the column's face, formatted by its `fmt`. */
@Composable
private fun PlainCell(column: TableColumn, raw: String?, context: BlockContext) {
    val mono = column.mono || column.cell == "mono"
    val weight = when {
        column.bold -> FontWeight.Bold
        mono -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }
    val text = when {
        column.cell == "percent" -> AnalyticsFormat.pct(raw?.let(AnalyticsFormat::toNumber))
        column.fmt != null -> AnalyticsFormat.value(raw, column.fmt, context.currency)
        else -> raw.orEmpty()
    }
    ZillitText(
        text = text,
        style = if (mono) AnalyticsType.mono(13f, weight) else AnalyticsType.text(13f, weight),
        color = analyticsColors.ink,
        textAlign = column.textAlign(),
    )
}

/** A card number in bold mono, its holder under it. */
@Composable
private fun CardRefCell(reference: String, holder: String?) {
    val colors = analyticsColors
    Column {
        ZillitText(reference, style = AnalyticsType.mono(13f, FontWeight.Bold), color = colors.ink)
        holder?.let { ZillitText(it, style = AnalyticsType.text(11f), color = colors.ink3) }
    }
}

/** A figure over a bar of `row.pct` percent — amber, red past a hundred, or the row's tone. */
@Composable
private fun ProgressCell(raw: String?, column: TableColumn, row: TableRow, context: BlockContext) {
    val colors = analyticsColors
    val pct = (row.number("pct") ?: 0.0).coerceIn(0.0, PROGRESS_CEILING)
    val bar = row["tone"]?.let(colors::toneHex) ?: if (pct > FULL) colors.red else colors.amber
    Column(
        Modifier.widthIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        ZillitText(
            AnalyticsFormat.value(raw, column.fmt ?: "money", context.currency),
            style = AnalyticsType.mono(13f, FontWeight.Bold),
            color = colors.ink,
            textAlign = TextAlign.End,
        )
        RailBar((pct.coerceAtMost(FULL) / FULL).toFloat(), bar, Modifier.fillMaxWidth())
    }
}

/** Initials on amber, the name, and a role under it when there is one. */
@Composable
private fun AvatarCell(name: String, role: String?) {
    val colors = analyticsColors
    val amber = colors.tone("amber")
    val initials = name.split(' ').mapNotNull { it.firstOrNull() }.joinToString("").take(2).uppercase()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(amber.soft, RoundedCornerShape(9.dp))
                .border(1.dp, amber.ring, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(initials, style = AnalyticsType.mono(11f, FontWeight.ExtraBold), color = amber.ink)
        }
        Column {
            ZillitText(name, style = AnalyticsType.text(13f, FontWeight.Bold), color = colors.ink)
            role?.takeIf { it.isNotBlank() }?.let {
                ZillitText(it, style = AnalyticsType.text(11f), color = colors.ink3)
            }
        }
    }
}

/** A payment method's colour square and name. */
@Composable
private fun MethodChip(label: String) {
    val colors = analyticsColors
    val color = METHOD_COLORS[label] ?: colors.ink3
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        ZillitText(label, style = AnalyticsType.text(12f, FontWeight.SemiBold), color = colors.ink2)
    }
}

/** An amber link; without an href it is only text, as the web's prevented anchor is. */
@Composable
private fun TableLinkText(label: String, href: String?, context: BlockContext, arrow: Boolean) {
    val colors = analyticsColors
    Row(
        modifier = href?.takeIf { it.isNotBlank() }?.let { Modifier.clickable { context.onLink(it) } } ?: Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(label, style = AnalyticsType.text(12.5f, FontWeight.Bold), color = colors.amber)
        if (arrow) ZillitIcon(ZillitIcons.ArrowRight, tint = colors.amber, size = 12.dp)
    }
}

private val METHOD_COLORS = mapOf(
    "BACS" to Color(0xFF14A394),
    "Faster Payments" to Color(0xFF1AA463),
    "Wires / CHAPS" to Color(0xFF2862E0),
    "Cheques" to Color(0xFFD98324),
    "Payroll" to Color(0xFFE8861A),
    "Via Payroll" to Color(0xFFE8861A),
)

private const val PROGRESS_CEILING = 120.0
private const val FULL = 100.0
