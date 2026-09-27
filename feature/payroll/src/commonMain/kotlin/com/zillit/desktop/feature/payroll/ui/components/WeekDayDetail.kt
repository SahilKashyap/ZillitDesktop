package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.WeekRow
import com.zillit.desktop.feature.payroll.domain.WeekView

/**
 * A day and, when it is open, the panel under it.
 *
 * The web's read-only table shipped without the expanded sub-row and had it
 * put back for parity with the crew member's own card — read-only: the same
 * three cards, with no inputs and nothing to remove.
 */
@Composable
internal fun DayRowWithDetail(
    row: WeekRow,
    currency: String?,
    mealColumns: Int,
    view: AllowanceView,
    expanded: Boolean,
    onToggle: () -> Unit,
    dayCellExtra: (@Composable (WeekRow) -> Unit)?,
) {
    // This day's own entries. The same call the panel makes, so the cell's
    // count and the panel's rows can never disagree.
    val dayEntries = remember(row.day) { WeekView.entries(listOfNotNull(row.day)) }
    WeekDayRow(
        row = row,
        mealColumns = mealColumns,
        currency = currency,
        expanded = expanded,
        onToggle = onToggle,
        dayCellExtra = dayCellExtra?.let { extra -> { extra(row) } },
        allowanceCell = if (view == AllowanceView.Daily) {
            { DayEntriesCell(dayEntries, currency, onToggle) }
        } else {
            null
        },
    )
    if (expanded) DayDetailPanel(row, currency)
}

/** Times, breakdown and the day's entries — three cards on a sunken well. */
@Composable
private fun DayDetailPanel(row: WeekRow, currency: String?) {
    val dayEntries = remember(row.day) { WeekView.entries(listOfNotNull(row.day)) }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(ZillitTheme.colors.surfaceSunken),
    ) {
        // The accent stripe down the left edge marks which row is open.
        Row(Modifier.width(ACCENT_WIDTH).fillMaxHeight().background(ZillitTheme.colors.accent)) {}
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            TimesCard(row, Modifier.weight(1f))
            BreakdownCard(row, currency, Modifier.weight(1f))
            EntriesCard(dayEntries, currency, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimesCard(row: WeekRow, modifier: Modifier) {
    DetailCard(str(S.desktop_payroll_times), modifier) {
        DetailLine(str(S.desktop_payroll_unit_call), row.unitCall)
        DetailLine(str(S.desktop_payroll_unit_wrap), row.unitWrap)
        DetailLine(str(S.desktop_payroll_my_call), row.myCall)
        DetailLine(str(S.desktop_payroll_my_wrap), row.myWrap)
        row.meals.forEachIndexed { index, meal ->
            val start = PayPeriod.hhmm(meal.start)
            val end = PayPeriod.hhmm(meal.end)
            DetailLine(
                label = str(S.desktop_payroll_meal_n, index + 1),
                value = if (meal.start == null && meal.end == null) WeekView.DASH else "$start – $end",
            )
        }
    }
}

@Composable
private fun BreakdownCard(row: WeekRow, currency: String?, modifier: Modifier) {
    DetailCard(str(S.desktop_payroll_breakdown), modifier) {
        if (row.rates.isEmpty()) {
            DetailLine(str(S.desktop_payroll_ot_premiums), str(S.none))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                row.rates.forEach { line -> RateLine(line, currency) }
            }
        }
    }
}

@Composable
private fun EntriesCard(
    entries: com.zillit.desktop.feature.payroll.domain.MergedEntries,
    currency: String?,
    modifier: Modifier,
) {
    DetailCard(str(S.desktop_payroll_allowances_rentals_upgrades), modifier) {
        val rows = entries.all
        if (rows.isEmpty()) {
            DetailLine(str(S.desktop_payroll_nothing_on_this_day), WeekView.DASH)
        } else {
            rows.forEach { entry -> DetailLine(entry.label, Money.format(entry.amount, entry.currency ?: currency)) }
            ZillitText(
                text = "${str(S.desktop_payroll_daily_total)}  ${Money.format(entries.total, currency)}",
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
            )
        }
    }
}

private val ACCENT_WIDTH = 3.dp
