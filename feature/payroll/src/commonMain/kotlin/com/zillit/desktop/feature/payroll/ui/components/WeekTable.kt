package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.EntryRow
import com.zillit.desktop.feature.payroll.domain.MergedEntries
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.WeekRow
import com.zillit.desktop.feature.payroll.domain.WeekView

/**
 * Which cadence the allowances column shows — the web's Daily | Weekly
 * toggle. Daily is the default on every payroll surface: they are read day by
 * day, and the per-day detail is the thing that had no entry point before.
 */
enum class AllowanceView { Daily, Weekly }

/** The table's fixed columns. The OT column takes the slack, as the web's does. */
internal object WeekColumns {
    val day: Dp = 168.dp
    val times: Dp = 136.dp
    val meal: Dp = 96.dp
    val ot: Dp = 196.dp
    val allowances: Dp = 248.dp

    /** The narrowest the table reads at; below it the pane scrolls sideways. */
    fun minWidth(mealColumns: Int, showAllowances: Boolean): Dp =
        day + times + times + meal * mealColumns + ot + if (showAllowances) allowances else 0.dp
}

/** The table's header — one row, sticky in spirit if not in layout. */
@Composable
internal fun WeekTableHeader(mealColumns: Int, allowanceHeader: (@Composable RowScope.() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .height(IntrinsicSize.Min),
    ) {
        HeaderCell(str(S.bs_day), WeekColumns.day)
        HeaderCell(listOf(str(S.desktop_payroll_unit_call), str(S.desktop_payroll_unit_wrap)), WeekColumns.times)
        HeaderCell(listOf(str(S.desktop_payroll_my_call), str(S.desktop_payroll_my_wrap)), WeekColumns.times)
        repeat(mealColumns) { index -> HeaderCell(str(S.desktop_payroll_meal_n, index + 1), WeekColumns.meal) }
        HeaderCell(str(S.desktop_payroll_ot_breakdown), null)
        allowanceHeader?.invoke(this)
    }
}

@Composable
private fun RowScope.HeaderCell(label: String, width: Dp?) = HeaderCell(listOf(label), width)

/**
 * A header cell. A null width is the OT column, which takes the table's slack
 * — and must take it the same way its body cells do, or every column after it
 * sits a few pixels off its own heading.
 */
@Composable
private fun RowScope.HeaderCell(labels: List<String>, width: Dp?) {
    Column(
        modifier = Modifier
            .cell(width)
            .then(if (width == null) Modifier.weight(1f) else Modifier)
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        labels.forEach { label ->
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.columnHeader,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** A fixed column, or the slack column when [width] is null. */
internal fun Modifier.cell(width: Dp?): Modifier =
    if (width != null) this.width(width) else this.widthIn(min = WeekColumns.ot)

/**
 * One day. A non-paid day — a day off, holiday or sick day — collapses into a
 * single cell across the time columns, as the web's does; a flat-rate day
 * keeps its columns but says its pay is automatic rather than timed.
 */
@Composable
internal fun WeekDayRow(
    row: WeekRow,
    mealColumns: Int,
    currency: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    dayCellExtra: (@Composable () -> Unit)?,
    allowanceCell: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .alpha(if (row.isMerged) MERGED_ALPHA else 1f),
    ) {
        DayCell(row, dayCellExtra)
        if (row.isMerged) {
            MergedCell(row)
        } else {
            TimesCell(
                first = str(S.desktop_payroll_unit_call) to row.unitCall,
                second = str(S.desktop_payroll_unit_wrap) to row.unitWrap,
                flat = row.isFlatPay,
                flatIsAuto = true,
            )
            TimesCell(
                first = str(S.desktop_payroll_my_call) to row.myCall,
                second = str(S.desktop_payroll_my_wrap) to row.myWrap,
                flat = row.isFlatPay,
                flatIsAuto = false,
            )
            repeat(mealColumns) { index -> MealCell(row, index) }
            OtCell(row, currency, expanded, onToggle)
        }
        allowanceCell?.invoke(this)
    }
}

/** The date, the day-type chip, and whatever the host wants under them. */
@Composable
private fun RowScope.DayCell(row: WeekRow, extra: (@Composable () -> Unit)?) {
    Column(
        modifier = Modifier.cell(WeekColumns.day).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitText(text = row.weekday, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            ZillitText(
                text = row.dayMonth,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        ZillitTag(
            label = row.dayType?.localised() ?: WeekView.DASH,
            tone = if (row.dayType == null) TagTone.Neutral else TagTone.Accent,
        )
        extra?.invoke()
    }
}

/** `Day Off`, `Holiday`, `Sick Day` — one statement across the whole week row. */
@Composable
private fun RowScope.MergedCell(row: WeekRow) {
    Box(
        modifier = Modifier.cell(null).weight(1f).fillMaxHeight().padding(ZillitTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = row.mergedLabel.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** A labelled pair of times — the unit's, or the crew member's own. */
@Composable
private fun RowScope.TimesCell(
    first: Pair<String, String>,
    second: Pair<String, String>,
    flat: Boolean,
    flatIsAuto: Boolean,
) {
    Column(
        modifier = Modifier.cell(WeekColumns.times).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (flat) {
            ZillitText(
                text = if (flatIsAuto) str(S.desktop_payroll_auto_daily_rate) else WeekView.DASH,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            TimeLine(first.first, first.second)
            TimeLine(second.first, second.second)
        }
    }
}

@Composable
private fun TimeLine(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.MealCell(row: WeekRow, index: Int) {
    val meal = row.meals.getOrNull(index)
    val start = PayPeriod.hhmm(meal?.start)
    val end = PayPeriod.hhmm(meal?.end)
    val blank = row.isFlatPay || (meal?.start == null && meal?.end == null)
    Column(
        modifier = Modifier.cell(WeekColumns.meal).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (blank) {
            ZillitText(
                text = WeekView.DASH,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            TimeLine(str(S.start), start)
            TimeLine(str(S.end), end)
        }
    }
}

/**
 * The day's OT headline and the way into its breakdown. The figure is money
 * here: these are the payroll surfaces, where pay is what the reader came for.
 */
@Composable
private fun RowScope.OtCell(row: WeekRow, currency: String?, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.cell(null).weight(1f).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (row.rates.isEmpty()) {
            ZillitText(
                text = WeekView.DASH,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitText(
                text = Money.format(row.otAmount, currency),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            ZillitText(
                text = str(S.desktop_payroll_ot_lines, row.rates.size),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = str(if (expanded) S.desktop_payroll_hide_breakdown else S.desktop_payroll_view_breakdown),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.clickable(onClick = onToggle),
        )
    }
}

// -- the allowances column -------------------------------------------------------------------

/** One day's allowances, rentals and upgrades — the Daily view's per-row cell. */
@Composable
internal fun RowScope.DayEntriesCell(entries: MergedEntries, currency: String?, onToggle: () -> Unit) {
    val rows = entries.all
    Column(
        modifier = Modifier.cell(WeekColumns.allowances).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (rows.isEmpty()) {
            ZillitText(
                text = WeekView.DASH,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitText(
                text = Money.format(entries.total, currency),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            ZillitText(
                text = itemCount(rows.size),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = str(S.desktop_payroll_view_breakdown),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.clickable(onClick = onToggle),
        )
    }
}

/** The week's entries as one list — the Weekly view's single tall cell. */
@Composable
internal fun WeekEntriesList(entries: MergedEntries, currency: String?, modifier: Modifier = Modifier) {
    val rows = entries.all
    Column(
        modifier = modifier.padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (rows.isEmpty()) {
            // A money figure announced for nothing reads as a load failure, so
            // an empty week says only that it is empty — the web's own guard.
            ZillitText(
                text = str(S.desktop_payroll_no_entries_this_week),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitText(
                text = Money.format(entries.total, currency),
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            ZillitText(
                text = str(S.desktop_payroll_week_items_total, rows.size),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        rows.forEach { entry -> EntryLine(entry, currency) }
    }
}

@Composable
private fun ColumnScope.EntryLine(entry: EntryRow, currency: String?) {
    ZillitDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = entry.label, style = ZillitTheme.typography.bodySmall)
            entry.sub?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            }
        }
        ZillitTag(label = str(if (entry.daily) S.daily else S.ce_weekly), tone = TagTone.Neutral)
        ZillitText(
            text = Money.format(entry.amount, entry.currency ?: currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
        )
    }
}

internal fun itemCount(size: Int): String =
    if (size == 1) str(S.desktop_ce_process_one_item) else str(S.desktop_payroll_items_count, size)

private const val MERGED_ALPHA = 0.6f

/** The accent a row's expanded panel is marked with. */
internal val ExpandedAccent: Color @Composable get() = ZillitTheme.colors.accent

/** A pay line's own chip, in the expanded panel. */
@Composable
internal fun RateLine(line: PayLine, currency: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = line.displayLabel,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        if (line.workDuration > 0) {
            ZillitText(
                text = WeekView.hoursLabel(line.workDuration),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = Money.format(line.rateAmount, line.currency ?: currency),
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.accentText,
        )
    }
}

/** A titled card inside the expanded day panel. */
@Composable
internal fun DetailCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = title.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        content()
    }
}

/** A `label … value` line inside a detail card. */
@Composable
internal fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric)
    }
}
