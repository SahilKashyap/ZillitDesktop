package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.Employment
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState

/**
 * One crew member's week, opened beside the grid — the web's `CrewDrawer`,
 * shared by the Run and Processing. The footer is the host's: each screen
 * passes the actions its own rules allow.
 */
@Composable
fun CrewDrawer(
    state: PayrollUiState,
    timecard: PayrollTimecard?,
    loading: Boolean,
    onClose: () -> Unit,
    onEvent: (PayrollEvent) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable RowScope.(PayrollTimecard) -> Unit,
) {
    Column(modifier.background(ZillitTheme.colors.surface)) {
        DrawerHeader(state, timecard, onClose)
        ZillitDivider()
        if (timecard == null) {
            Column(
                Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (loading) repeat(SKELETONS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
            }
            return@Column
        }
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            AdjustRow(timecard, onEvent)
            WeekHero(timecard)
            EmploymentBanner(timecard)
            DaysTable(timecard)
            ClaimsAndDeductions(timecard)
        }
        ZillitDivider()
        Row(
            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) { footer(timecard) }
    }
}

/** Face, name and status, role · department · week — or a skeleton while the week loads. */
@Composable
private fun DrawerHeader(state: PayrollUiState, timecard: PayrollTimecard?, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (timecard != null) {
            ZillitAvatar(name = state.nameOf(timecard.userId), userId = timecard.userId, size = AVATAR)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = state.nameOf(timecard.userId),
                        style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    ZillitStatusPill(label = timecard.status.label, tone = timecard.status.tone, dot = true)
                }
                ZillitText(
                    text = listOfNotNull(
                        state.roleOf(timecard.userId).ifBlank { str(S.crew) },
                        state.departmentOf(timecard.userId),
                        timecard.weekStarting?.let {
                            str(S.desktop_payroll_week_ending, PayPeriod.compactRangeLabel(it))
                        },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        } else {
            ZillitSkeletonBar(Modifier.weight(1f))
        }
        ZillitIconButton(icon = ZillitIcons.Close, contentDescription = str(S.close), onClick = onClose)
    }
}

/** The week's claims and deductions, each with its amount. */
@Composable
private fun ClaimsAndDeductions(timecard: PayrollTimecard) {
    if (timecard.claims.isNotEmpty()) {
        SectionHead(str(S.desktop_ce_claims))
        timecard.claims.forEach { claim ->
            val amount = Money.format(claim.amount, claim.currency ?: timecard.currency)
            FigureRow(claim.name, amount, ZillitTheme.colors.success)
        }
    }
    if (timecard.deductions.isNotEmpty()) {
        SectionHead(str(S.desktop_deductions))
        timecard.deductions.forEach { deduction ->
            val amount = "−" + Money.format(deduction.amount, timecard.currency)
            FigureRow(deduction.label, amount, ZillitTheme.colors.danger)
        }
    }
}

/** Add Claims / Add Deduction — "Show …" over a week the server will not change. */
@Composable
private fun AdjustRow(timecard: PayrollTimecard, onEvent: (PayrollEvent) -> Unit) {
    val readOnly = timecard.status.refusesWrites
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = str(if (readOnly) S.desktop_payroll_show_claims else S.desktop_payroll_add_claims),
            onClick = { onEvent(PayrollEvent.OpenAdjustments(AdjustmentKind.Claims, timecard)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Receipt,
        )
        ZillitButton(
            text = str(if (readOnly) S.desktop_payroll_show_deductions else S.desktop_payroll_add_deduction),
            onClick = { onEvent(PayrollEvent.OpenAdjustments(AdjustmentKind.Deductions, timecard)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Minus,
        )
    }
}

/**
 * The week's total — basic, OT, allowances, rentals, extras and claims, less
 * deductions — with each part named under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekHero(timecard: PayrollTimecard) {
    val basic = timecard.days.sumOf { it.basicPay }
    val ots = timecard.days.sumOf { it.otTotal }
    val allowances = timecard.days.sumOf { day -> day.allowances.filterNot { it.isRental }.sumOf { it.rateAmount } } +
        timecard.weeklyAllowances.filterNot { it.isRental }.sumOf { it.lineAmount }
    val rentals = timecard.days.sumOf { day -> day.allowances.filter { it.isRental }.sumOf { it.rateAmount } } +
        timecard.weeklyAllowances.filter { it.isRental }.sumOf { it.lineAmount }
    val total = basic + ots + allowances + rentals + timecard.extrasTotal + timecard.claimsTotal -
        timecard.deductionsTotal
    val code = timecard.currency
    Column(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.large).background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(
                S.desktop_payroll_week_total,
                timecard.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty(),
            ).uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = Money.format(total, code),
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            listOf(
                str(S.desktop_payroll_basic) to basic,
                str(S.desktop_payroll_ots_premiums) to ots,
                str(S.allowances_label) to allowances,
                str(S.dm_allow_card_rentals) to rentals,
                str(S.desktop_payroll_upgrades_extras) to timecard.extrasTotal,
                str(S.desktop_ce_claims) to timecard.claimsTotal,
            ).forEach { (label, amount) ->
                ZillitText(
                    text = "$label ${Money.format(amount, code)}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitText(
                text = "${str(S.desktop_deductions)} −${Money.format(timecard.deductionsTotal, code)}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/** The web's employment flags: buy-out, loan-out and Schedule D each carry a warning. */
@Composable
private fun EmploymentBanner(timecard: PayrollTimecard) {
    val text = when (Employment.of(timecard.employmentStatus, timecard.dealType)) {
        Employment.BuyOut -> str(S.desktop_payroll_buyout_banner)
        Employment.LoanOut -> str(S.desktop_payroll_loanout_banner)
        Employment.ScheduleD -> str(S.desktop_payroll_schedule_d_banner)
        Employment.Paye -> null
    } ?: return
    ZillitNotice(text = text, tone = StatusTone.InTransit, icon = ZillitIcons.Info)
}

/** Each day's times and money — the web's "Days" and "Times" in one table. */
@Composable
private fun DaysTable(timecard: PayrollTimecard) {
    SectionHead(str(S.desktop_payroll_days_week))
    Column(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.large).border(
            1.dp,
            ZillitTheme.colors.border,
            ZillitTheme.shapes.large,
        ),
    ) {
        DayLine(
            listOf(
                str(S.bs_day), str(S.type), str(S.desktop_payroll_unit_call), str(S.desktop_payroll_my_call),
                str(S.desktop_payroll_unit_wrap), str(S.desktop_release), str(S.desktop_payroll_day_total),
            ),
            header = true,
        )
        timecard.days.sortedBy { it.date ?: 0L }.forEach { day ->
            ZillitDivider()
            DayLine(
                listOf(
                    day.date?.let(PayPeriod::dayLabel).orEmpty(),
                    day.dayType?.localised().orEmpty().ifBlank { "—" },
                    PayPeriod.hhmm(day.callTime),
                    PayPeriod.hhmm(day.loginTime),
                    PayPeriod.hhmm(day.wrapTime),
                    PayPeriod.hhmm(day.logoutTime),
                    Money.format(day.dayTotal, timecard.currency),
                ),
                header = false,
            )
        }
        if (timecard.days.isEmpty()) {
            ZillitText(
                text = str(S.desktop_payroll_no_pay_lines),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun DayLine(cells: List<String>, header: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (header) ZillitTheme.colors.surfaceSunken else ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    ) {
        cells.forEachIndexed { index, text ->
            ZillitText(
                text = if (header) text.uppercase() else text,
                style = if (header) ZillitTheme.typography.columnHeader else ZillitTheme.typography.numeric,
                color = if (header) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
                textAlign = if (index == cells.lastIndex) TextAlign.End else TextAlign.Start,
                modifier = Modifier.weight(if (index == 0) FIRST_WEIGHT else 1f),
                maxLines = 1,
            )
        }
    }
}

private val AVATAR = 40.dp
private const val SKELETONS = 5
private const val FIRST_WEIGHT = 1.4f
