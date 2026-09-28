package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.DayTypes
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.WeekRow
import com.zillit.desktop.feature.payroll.domain.WeekView

/**
 * The drawer's week: seven tiles, one per day, and the selected day's times
 * and pay lines beneath them.
 *
 * A day at a time rather than the whole week at once, because the drawer is
 * narrow and because this is the question it exists to answer — which lines
 * make up a day's money. The read-only week view answers the other question,
 * the shape of the week, and has the room to.
 */
@Composable
internal fun DrawerWeek(
    timecard: PayrollTimecard,
    rows: List<WeekRow>,
    selected: Int,
    now: Long,
    onSelect: (Int) -> Unit,
) {
    val week = timecard.weekStarting ?: return
    SectionHead(str(S.desktop_payroll_days_week_ending, PayPeriod.weekEnding(week)))
    // Only a week that contains today has future days; every tile of a past
    // week stays readable, which is what the week navigator is for. Floored to
    // today's UTC midnight, because a day epoch is one.
    val today = now.floorDiv(PayPeriod.DAY_MILLIS) * PayPeriod.DAY_MILLIS
    val todayIndex = ((today - week) / PayPeriod.DAY_MILLIS).toInt()
        .takeIf { it in 0 until PayPeriod.DAYS_IN_WEEK }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        rows.forEach { row ->
            DayTile(
                row = row,
                currency = timecard.currency,
                active = row.index == selected,
                future = todayIndex != null && row.index > todayIndex,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onSelect = { onSelect(row.index) },
            )
        }
    }
    rows.getOrNull(selected)?.let { row ->
        DayTimes(row)
        DayPayLines(row, timecard.currency)
    }
}

/** One day: its weekday, its date, its type and what it paid. */
@Composable
private fun DayTile(
    row: WeekRow,
    currency: String?,
    active: Boolean,
    future: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = ZillitTheme.colors
    // A non-paid day is dimmed, from the canonical set rather than a REST
    // literal, so every new day type flows through without a change here.
    val rest = row.dayType in DayTypes.NON_PAID || row.dayType == null
    val total = row.day?.dayTotal ?: 0.0
    val ink = if (active) colors.accentText else colors.textPrimary
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .alpha(tileAlpha(future, rest, active))
            .clickable(enabled = !future, onClick = onSelect)
            .padding(horizontal = ZillitTheme.spacing.xxs, vertical = ZillitTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        TileLine(row.weekday.take(WEEKDAY_LETTERS).uppercase(), if (active) colors.accentText else colors.textMuted)
        TileLine(row.dayMonth.substringBefore(' '), ink, numeric = true)
        TileLine(
            text = row.dayType?.localised() ?: WeekView.DASH,
            colour = if (row.dayType == null) colors.textMuted else ink,
        )
        TileLine(
            text = if (total > 0) Money.format(total, currency) else WeekView.DASH,
            colour = if (total > 0) ink else colors.textMuted,
            numeric = true,
        )
    }
}

/** A future day is barely there; a non-paid one recedes unless it is open. */
private fun tileAlpha(future: Boolean, rest: Boolean, active: Boolean): Float = when {
    future -> FUTURE_ALPHA
    rest && !active -> REST_ALPHA
    else -> 1f
}

@Composable
private fun TileLine(text: String, colour: androidx.compose.ui.graphics.Color, numeric: Boolean = false) {
    ZillitText(
        text = text,
        style = if (numeric) {
            ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold)
        } else {
            ZillitTheme.typography.columnHeader
        },
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/** The selected day's four times and its meal breaks. */
@Composable
private fun DayTimes(row: WeekRow) {
    SectionHead(str(S.desktop_payroll_times))
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        FigureRow(str(S.desktop_payroll_unit_call), row.unitCall)
        FigureRow(str(S.desktop_payroll_unit_wrap), row.unitWrap)
        FigureRow(str(S.desktop_payroll_my_call), row.myCall)
        FigureRow(str(S.desktop_payroll_my_wrap), row.myWrap)
        row.meals.forEachIndexed { index, meal ->
            val start = PayPeriod.hhmm(meal.start)
            val end = PayPeriod.hhmm(meal.end)
            FigureRow(
                label = str(S.desktop_payroll_meal_n, index + 1),
                value = if (meal.start == null && meal.end == null) WeekView.DASH else "$start – $end",
            )
        }
    }
}

/**
 * Every line that made the selected day's money — each rate and overtime band
 * with the time it covers, then the allowances and rentals, then the day's
 * total.
 */
@Composable
private fun DayPayLines(row: WeekRow, currency: String?) {
    SectionHead(str(S.desktop_payroll_pay_breakdown))
    val day = row.day
    val rates = day?.rates.orEmpty()
    val allowances = day?.allowances.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
    ) {
        if (rates.isEmpty() && allowances.isEmpty()) {
            ZillitText(
                text = str(S.desktop_payroll_no_pay_lines_day),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            )
            return@Column
        }
        PayLineHeader()
        rates.forEach { line ->
            ZillitDivider()
            PayLineRow(
                label = line.displayLabel,
                quantity = if (line.workDuration > 0) WeekView.hoursLabel(line.workDuration) else WeekView.DASH,
                amount = line.rateAmount,
                currency = line.currency ?: currency,
                // Basic reads as ink; everything the day earned on top of it
                // wears the accent, so the eye finds the overtime.
                tone = if (line.isBasic) ZillitTheme.colors.textPrimary else ZillitTheme.colors.accentText,
            )
        }
        allowances.forEach { line ->
            ZillitDivider()
            PayLineRow(
                label = if (line.isRental) {
                    str(S.desktop_payroll_rental_suffix, line.displayLabel)
                } else {
                    line.displayLabel
                },
                quantity = WeekView.DASH,
                amount = line.rateAmount,
                currency = line.currency ?: currency,
                tone = ZillitTheme.colors.success,
            )
        }
        ZillitDivider()
        DayTotalRow(day?.dayTotal ?: 0.0, currency)
    }
}

@Composable
private fun DayTotalRow(total: Double, currency: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_payroll_day_total).uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = Money.format(total, currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN),
        )
    }
}

@Composable
private fun PayLineHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        listOf(
            str(S.desktop_line) to Modifier.weight(1f),
            str(S.ah_lbl_qty) to Modifier.width(QUANTITY_COLUMN),
            str(S.amount) to Modifier.width(AMOUNT_COLUMN),
        ).forEachIndexed { index, (label, modifier) ->
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.columnHeader,
                color = ZillitTheme.colors.textMuted,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PayLineRow(
    label: String,
    quantity: String,
    amount: Double,
    currency: String?,
    tone: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = quantity,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(QUANTITY_COLUMN),
        )
        ZillitText(
            text = if (amount > 0) Money.format(amount, currency) else WeekView.DASH,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = if (amount > 0) tone else ZillitTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN),
        )
    }
}

private const val WEEKDAY_LETTERS = 3
private const val FUTURE_ALPHA = 0.4f
private const val REST_ALPHA = 0.6f
private val QUANTITY_COLUMN = 84.dp
private val AMOUNT_COLUMN = 96.dp
