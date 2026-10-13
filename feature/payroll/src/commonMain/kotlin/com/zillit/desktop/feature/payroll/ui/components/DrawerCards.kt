package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DayCard
import com.zillit.desktop.feature.payroll.domain.DeductionLine
import com.zillit.desktop.feature.payroll.domain.DrawerBreakdown
import com.zillit.desktop.feature.payroll.domain.EntryRow
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.WeekView

/**
 * Everything the accountant's crew drawer lists below the day it has open:
 * every day on the document as a card, the week's merged allowance lines, the
 * claims, and the deductions — the web's `CrewDrawer` body (`PayrollRunModule.jsx`
 * 4448-4757). The open day's times and pay lines above answer "what made this
 * day"; these answer "what is on the whole week", in the same drawer.
 */
@Composable
internal fun DrawerBreakdownSections(timecard: PayrollTimecard) {
    DailyBreakdown(timecard)
    MergedSection(timecard)
    ClaimsSection(timecard)
    DeductionsSection(timecard)
}

/** One card per day on the document — in/out, basic, each overtime line, each allowance. */
@Composable
private fun DailyBreakdown(timecard: PayrollTimecard) {
    val cards = remember(timecard.days) { DrawerBreakdown.dayCards(timecard.days) }
    SectionHead(str(S.desktop_payroll_daily_breakdown))
    if (cards.isEmpty()) {
        ZillitText(
            text = str(S.desktop_payroll_no_day_data),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        cards.forEach { DayBreakdownCard(it, timecard.currency) }
    }
}

@Composable
private fun DayBreakdownCard(card: DayCard, currency: String?) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth()
            .alpha(if (card.isRest) REST_ALPHA else 1f)
            .cardSurface()
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = card.weekday,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            )
            ZillitText(text = card.dayMonth, style = ZillitTheme.typography.numeric, color = colors.textMuted)
            card.dayType?.let { ZillitTag(label = it.localised(), tone = TagTone.Neutral) }
            ZillitText(
                text = Money.format(card.total, currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (card.showsTimes) DayTimesLine(card)
        card.basic?.let { DayLine(it.displayLabelOr(str(S.desktop_td_eg_line_basic)), null, it, currency, null) }
        card.ots.forEach { DayLine(it.displayLabel, it.hoursOrNull(), it, currency, colors.accentText) }
        if (card.allowances.isNotEmpty()) ZillitDivider()
        card.allowances.forEach { line ->
            val label = if (line.isRental) {
                str(S.desktop_payroll_rental_suffix, line.displayLabel)
            } else {
                line.displayLabel
            }
            DayLine(label, null, line, currency, colors.success)
        }
    }
}

/** `In 07:30  Out 19:00  Worked 11h 30m`. */
@Composable
private fun DayTimesLine(card: DayCard) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        TimeWord(str(S.desktop_td_eg_g_in), card.callIn ?: WeekView.DASH)
        TimeWord(str(S.desktop_td_eg_g_out), card.callOut ?: WeekView.DASH)
        if (card.minutesWorked > 0) {
            TimeWord(str(S.desktop_day_type_worked), WeekView.hoursLabel(card.minutesWorked))
        }
    }
}

@Composable
private fun TimeWord(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = label, style = ZillitTheme.typography.numeric, color = ZillitTheme.colors.textMuted)
        ZillitText(text = value, style = ZillitTheme.typography.numeric)
    }
}

/** A label, its time where it has one, and its money. */
@Composable
private fun DayLine(label: String, time: String?, line: PayLine, currency: String?, tone: Color?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        time?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
        ZillitText(
            text = Money.format(line.rateAmount, line.currency ?: currency),
            style = ZillitTheme.typography.numeric,
            color = tone ?: ZillitTheme.colors.textPrimary,
        )
    }
}

/** Allowances, Rentals, Upgrades & Extras — the week's lines and every day's extras, each with its cadence. */
@Composable
private fun MergedSection(timecard: PayrollTimecard) {
    val merged = remember(timecard) { DrawerBreakdown.mergedEntries(timecard) }
    if (merged.all.isEmpty()) return
    SectionHead(str(S.desktop_payroll_allowances_rentals_upgrades_extras))
    Column(
        modifier = Modifier.fillMaxWidth().cardSurface().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MergedGroup(str(S.allowances_label), merged.allowances, timecard.currency, showKind = false)
        MergedGroup(str(S.dm_allow_card_rentals), merged.rentals, timecard.currency, showKind = false)
        MergedGroup(str(S.desktop_payroll_upgrades_extras), merged.extras, timecard.currency, showKind = true)
    }
}

@Composable
private fun MergedGroup(title: String, rows: List<EntryRow>, currency: String?, showKind: Boolean) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = title.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        rows.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = row.label,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showKind) row.kind?.let { ZillitTag(label = kindLabel(it), tone = TagTone.Neutral) }
                ZillitTag(label = str(if (row.daily) S.daily else S.ce_weekly), tone = TagTone.Neutral)
                Spacer(Modifier.weight(1f))
                ZillitText(
                    text = Money.format(row.amount, row.currency ?: currency),
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.success,
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String =
    str(if (kind == WeekView.UPGRADE) S.desktop_payroll_kind_upgrade else S.desktop_payroll_kind_extra)

/** Claims — name, nominal, a CASH / MANUAL pill and the amount. */
@Composable
private fun ClaimsSection(timecard: PayrollTimecard) {
    if (timecard.claims.isEmpty()) return
    SectionHead(str(S.desktop_ce_claims))
    Column(
        modifier = Modifier.fillMaxWidth().cardSurface().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        timecard.claims.forEach { claim -> ClaimRow(claim, timecard.currency) }
    }
}

@Composable
private fun ClaimRow(claim: ClaimLine, currency: String?) {
    val cash = DrawerBreakdown.isCash(claim)
    AdjustmentRow(
        label = claim.name,
        nominal = claim.nominalCode,
        pill = str(if (cash) S.desktop_payroll_tag_cash else S.desktop_payroll_tag_manual),
        pillTone = if (cash) TagTone.Success else TagTone.Accent,
        amount = Money.format(claim.amount, claim.currency ?: currency),
        amountTone = ZillitTheme.colors.success,
    )
}

/** Deductions — name, nominal, a FLAT / % pill and the amount taken off. */
@Composable
private fun DeductionsSection(timecard: PayrollTimecard) {
    if (timecard.deductions.isEmpty()) return
    SectionHead(str(S.desktop_deductions))
    Column(
        modifier = Modifier.fillMaxWidth().cardSurface().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // Deductions carry no per-row currency: everything is in the timecard's.
        timecard.deductions.forEach { deduction -> DeductionRow(deduction, timecard.currency) }
    }
}

@Composable
private fun DeductionRow(deduction: DeductionLine, currency: String?) {
    AdjustmentRow(
        label = deduction.label,
        nominal = deduction.nominalCode,
        pill = if (deduction.isPercentage) {
            DrawerBreakdown.percentLabel(deduction.rateAmount)
        } else {
            str(S.desktop_payroll_tag_flat)
        },
        pillTone = TagTone.Danger,
        amount = "−" + Money.format(deduction.amount, currency),
        amountTone = ZillitTheme.colors.danger,
    )
}

@Composable
private fun AdjustmentRow(
    label: String,
    nominal: String?,
    pill: String,
    pillTone: TagTone,
    amount: String,
    amountTone: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        nominal?.trim()?.takeIf { it.isNotEmpty() }?.let {
            ZillitText(text = "· $it", style = ZillitTheme.typography.numeric, color = ZillitTheme.colors.textMuted)
        }
        ZillitTag(label = pill, tone = pillTone)
        Spacer(Modifier.weight(1f))
        ZillitText(text = amount, style = ZillitTheme.typography.numeric, color = amountTone)
    }
}

private fun PayLine.hoursOrNull(): String? = workDuration.takeIf { it > 0 }?.let(WeekView::hoursLabel)

/** The line's own label, or [fallback] where the document gave it none. */
private fun PayLine.displayLabelOr(fallback: String): String = label?.takeIf { it.isNotBlank() } ?: fallback

@Composable
private fun Modifier.cardSurface(): Modifier = this
    .clip(ZillitTheme.shapes.medium)
    .background(ZillitTheme.colors.surface)
    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium)

private const val REST_ALPHA = 0.6f
