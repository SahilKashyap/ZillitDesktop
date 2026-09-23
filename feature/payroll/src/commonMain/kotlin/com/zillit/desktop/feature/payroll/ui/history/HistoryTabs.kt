package com.zillit.desktop.feature.payroll.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.AuditEvent
import com.zillit.desktop.feature.payroll.domain.BreakdownRow
import com.zillit.desktop.feature.payroll.domain.PayBreakdown
import com.zillit.desktop.feature.payroll.domain.PayBucket
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayslipPreview
import com.zillit.desktop.feature.payroll.ui.HistoryEvent
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.components.FigureCard
import com.zillit.desktop.feature.payroll.ui.components.FigureRow
import com.zillit.desktop.feature.payroll.ui.components.Rule
import com.zillit.desktop.feature.payroll.ui.components.SectionHead

/** The open timecard's breakdown, built the web's way, with the week's own currency. */
internal fun PayrollUiState.historyBreakdown(): PayBreakdown? = history.detail?.let { detail ->
    PayBreakdown.of(detail, history.deal, weeklyWord = str(S.ce_weekly), claimsWord = str(S.desktop_ce_claims))
}

private fun PayrollUiState.money(amount: Double, code: String? = null): String =
    Money.format(amount, code ?: history.detail?.currency)

/**
 * The seven cards over the tabs — Basic, OT, Premiums, Turnarounds,
 * Penalties, Allowances / Rental and the highlighted Gross Total — summed
 * from the breakdown so they can never disagree with the table under them.
 */
@Composable
internal fun HistoryFigures(state: PayrollUiState) {
    val breakdown = state.historyBreakdown()
    fun of(bucket: PayBucket) = state.money(breakdown?.total(bucket) ?: 0.0)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        val colors = ZillitTheme.colors
        FigureCard(str(S.desktop_payroll_basic), of(PayBucket.Basic), Modifier.weight(1f))
        FigureCard(str(S.desktop_payroll_ot), of(PayBucket.Overtime), Modifier.weight(1f), valueColor = colors.warning)
        FigureCard(str(S.dm_rates_premiums), of(PayBucket.Premium), Modifier.weight(1f), valueColor = colors.danger)
        FigureCard(
            str(S.dm_rates_turnarounds),
            of(PayBucket.Turnaround),
            Modifier.weight(1f),
            valueColor = colors.danger,
        )
        FigureCard(str(S.dm_rates_penalties), of(PayBucket.Penalty), Modifier.weight(1f), valueColor = colors.warning)
        FigureCard(
            str(S.desktop_payroll_allowances_rental),
            state.money(breakdown?.allowancesAndRentals ?: 0.0),
            Modifier.weight(1f),
            valueColor = colors.success,
        )
        FigureCard(
            str(S.ah_gross_total_label),
            state.money(breakdown?.gross ?: 0.0),
            Modifier.weight(1f),
            highlight = true,
        )
    }
}

/**
 * Pay Code Breakdown — every day's rates and allowances, the weekly lines and
 * the claims, with the nominal each posts to. Read-only, as the web's is: the
 * codes are set on the deal memo and in the Run's journal, not here.
 */
@Composable
internal fun PayCodeTab(state: PayrollUiState) {
    val breakdown = state.historyBreakdown()
    Card {
        CardTitle(str(S.desktop_payroll_pay_code_breakdown_all_days))
        TableRow(
            listOf(str(S.bs_day), str(S.desktop_payroll_pay_code), str(S.dm_rule_nominal)),
            listOf(str(S.ah_lbl_qty), str(S.av_rate), str(S.desktop_gross)),
            header = true,
        )
        when {
            breakdown == null || breakdown.rows.isEmpty() -> ZillitText(
                text = str(
                    if (state.history.detailLoading) {
                        S.desktop_payroll_loading_timecard
                    } else {
                        S.desktop_payroll_no_pay_lines
                    },
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            )
            else -> {
                breakdown.rows.forEach { row -> BreakdownLine(state, row) }
                ZillitDivider()
                Row(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md)) {
                    ZillitText(
                        text = str(S.desktop_payroll_week_gross_total),
                        style = ZillitTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = state.money(breakdown.gross),
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownLine(state: PayrollUiState, row: BreakdownRow) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(DAY_WEIGHT), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = row.day,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            row.dayType?.let { ZillitTag(label = it.localised(), tone = TagTone.Warning) }
        }
        Row(Modifier.weight(CODE_WEIGHT), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = row.payCode,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = row.bucket.codeColor(),
                maxLines = 1,
            )
            row.source?.let { source ->
                val cash = source == PayBreakdown.CASH
                ZillitTag(
                    label = str(if (cash) S.desktop_payroll_tag_cash else S.desktop_payroll_tag_manual),
                    tone = if (cash) TagTone.Success else TagTone.Accent,
                )
            }
        }
        ZillitText(
            text = row.nominal.ifBlank { "—" },
            style = ZillitTheme.typography.numeric,
            color = if (row.nominal.isBlank()) colors.textMuted else colors.textPrimary,
            modifier = Modifier.weight(NOMINAL_WEIGHT),
        )
        NumberCell(row.qty)
        NumberCell(state.money(row.rate, row.currency))
        NumberCell(state.money(row.gross, row.currency), color = row.bucket.grossColor(), bold = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumberCell(
    text: String,
    color: Color = ZillitTheme.colors.textPrimary,
    bold: Boolean = false,
) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.numeric.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        color = color,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(NUMBER_WEIGHT),
        maxLines = 1,
    )
}

@Composable
private fun TableRow(left: List<String>, right: List<String>, header: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (header) ZillitTheme.colors.surfaceSunken else Color.Transparent)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        listOf(DAY_WEIGHT, CODE_WEIGHT, NOMINAL_WEIGHT).zip(left).forEach { (weight, text) ->
            ZillitText(
                text = text.uppercase(),
                style = ZillitTheme.typography.columnHeader,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(weight),
            )
        }
        right.forEach { text ->
            ZillitText(
                text = text.uppercase(),
                style = ZillitTheme.typography.columnHeader,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(NUMBER_WEIGHT),
            )
        }
    }
}

/**
 * Payslip Preview — the web's payslip card: the production and the paying
 * company, the period and the crew member, then earnings, non-taxable
 * allowances, claims and deductions down to net pay. The PDF is the server's,
 * from `/payroll/runs/payslip` — the accountant-payroll payslip route the
 * earlier port read does not exist.
 */
@Composable
internal fun PayslipTab(state: PayrollUiState, row: PayrollTimecard, onEvent: (PayrollEvent) -> Unit) {
    val detail = state.history.detail
    val preview = detail?.let(PayslipPreview::of)
    Card {
        Column(
            Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            PayslipHeader(state, row)
            PayslipEarnings(state, preview)
            PayslipAdditions(state, preview)
            PayslipDeductions(state, detail)
            Rule()
            FigureRow(
                str(S.desktop_payroll_net_pay),
                state.money((preview?.total ?: 0.0) - (detail?.deductions?.sumOf { it.amount } ?: 0.0)),
                ZillitTheme.colors.success,
                bold = true,
            )
            ZillitButton(
                text = if (state.history.payslipBusy) str(S.drive_generating) else str(S.ah_download_pdf),
                onClick = { onEvent(HistoryEvent.DownloadPayslip) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
                enabled = detail?.weekStarting != null && !state.history.payslipBusy,
                loading = state.history.payslipBusy,
            )
        }
    }
}

/** The production and the paying company on the left; the crew member on the right. */
@Composable
private fun PayslipHeader(state: PayrollUiState, row: PayrollTimecard) {
    val colors = ZillitTheme.colors
    val company = state.companies.firstOrNull { it.id == (state.history.detail?.companyId ?: row.companyId) }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = state.projectName.ifBlank { "—" },
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
            ZillitText(
                text = str(S.desktop_payroll_company_payslip, company?.name?.ifBlank { null } ?: "—"),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            company?.country?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
            state.history.weekStarting?.let {
                ZillitText(
                    text = str(S.desktop_payroll_period, PayPeriod.rangeLabel(it)),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            ZillitText(text = state.nameOf(row.userId), style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = listOf(state.roleOf(row.userId).ifBlank { str(S.crew) }, state.departmentOf(row.userId))
                    .joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/** Earnings per pay code with their hours, then the gross pay. */
@Composable
private fun PayslipEarnings(state: PayrollUiState, preview: PayslipPreview?) {
    val colors = ZillitTheme.colors
    SectionHead(str(S.desktop_payroll_earnings))
    if (preview == null || preview.earnings.isEmpty()) {
        ZillitText(
            text = str(
                if (state.history.detailLoading) S.desktop_payroll_loading_earnings else S.desktop_payroll_no_earnings,
            ),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    } else {
        preview.earnings.forEach { line ->
            val hours = PayBreakdown.hoursMinutes(line.minutes)
            FigureRow(
                label = if (hours.isEmpty()) line.label else "${line.label} ($hours)",
                value = state.money(line.amount),
                valueColor = if (line.bucket.isEnhanced) colors.warning else colors.textPrimary,
            )
        }
    }
    Rule()
    FigureRow(str(S.desktop_payroll_gross_pay), state.money(preview?.grossPay ?: 0.0), bold = true)
}

/** Non-taxable allowances, then claims — each claim with the nominal it posts to. */
@Composable
private fun PayslipAdditions(state: PayrollUiState, preview: PayslipPreview?) {
    val colors = ZillitTheme.colors
    val allowances = preview?.allowances.orEmpty()
    val claims = preview?.claims.orEmpty()
    if (allowances.isNotEmpty()) {
        SectionHead(str(S.desktop_payroll_non_taxable_allowances))
        allowances.forEach { FigureRow(it.label, state.money(it.amount, it.currency), colors.success) }
    }
    if (claims.isNotEmpty()) {
        SectionHead(str(S.desktop_ce_claims))
        claims.forEach { line ->
            val label = line.nominalCode?.let { "${line.label} · $it" } ?: line.label
            FigureRow(label, state.money(line.amount, line.currency), colors.success)
        }
    }
}

/** Each deduction with its nominal, or its rate where it is a percentage of basic. */
@Composable
private fun PayslipDeductions(state: PayrollUiState, detail: PayrollTimecard?) {
    val deductions = detail?.deductions.orEmpty()
    if (deductions.isEmpty()) return
    val colors = ZillitTheme.colors
    SectionHead(str(S.desktop_deductions))
    deductions.forEach { deduction ->
        val label = when {
            !deduction.nominalCode.isNullOrBlank() -> "${deduction.label} · ${deduction.nominalCode}"
            deduction.isPercentage && deduction.rateAmount > 0 ->
                str(S.desktop_payroll_percent_of_basic, deduction.label, trimNumber(deduction.rateAmount))
            else -> deduction.label
        }
        FigureRow(label, "−${state.money(deduction.amount)}", colors.danger)
    }
    val total = "−${state.money(detail?.deductionsTotal ?: 0.0)}"
    FigureRow(str(S.desktop_payroll_total_deductions), total, colors.danger, bold = true)
}

/**
 * Audit Trail — the timecard's own `history[]`, oldest first: when, who (and
 * their designation), and what, in the server's words where it wrote any.
 */
@Composable
internal fun AuditTab(state: PayrollUiState) {
    val events = state.history.detail?.history.orEmpty().sortedBy { it.at ?: 0L }
    Card {
        CardTitle(str(S.desktop_payroll_complete_event_log))
        if (events.isEmpty()) {
            ZillitText(
                text = str(if (state.history.detailLoading) S.docusign_audit_loading else S.desktop_payroll_no_history),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            )
        }
        events.forEachIndexed { index, event ->
            if (index > 0) ZillitDivider()
            AuditLine(state, event)
        }
    }
}

@Composable
private fun AuditLine(state: PayrollUiState, event: AuditEvent) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // An instant, not a calendar date: shown in the viewer's own zone, as the web does.
        ZillitText(
            text = com.zillit.desktop.core.common.EpochDate.dateTime(event.at).ifEmpty { "—" },
            style = ZillitTheme.typography.numeric,
            color = colors.textMuted,
            modifier = Modifier.width(TIME_WIDTH),
        )
        Row(Modifier.width(ACTOR_WIDTH), verticalAlignment = Alignment.CenterVertically) {
            val actor = event.byUserId?.let(state::nameOf) ?: str(S.desktop_language_system_short)
            event.byUserId?.let { ZillitAvatar(name = actor, userId = it, size = ACTOR_AVATAR) }
            Column(Modifier.padding(start = ZillitTheme.spacing.sm)) {
                ZillitText(
                    text = actor,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                event.byUserId?.let(state::roleOf)?.takeIf { it.isNotBlank() }?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        ZillitText(
            text = event.describe(fromWord = str(S.fromText), toWord = str(S.toText)),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    )
}

@Composable
private fun PayBucket.codeColor(): Color = when (this) {
    PayBucket.Basic -> ZillitTheme.colors.warning
    PayBucket.Turnaround, PayBucket.Penalty -> ZillitTheme.colors.danger
    PayBucket.Claim -> ZillitTheme.colors.success
    PayBucket.Other -> ZillitTheme.colors.textPrimary
    else -> ZillitTheme.colors.violet
}

@Composable
private fun PayBucket.grossColor(): Color = when {
    this == PayBucket.Allowance || this == PayBucket.Rental || this == PayBucket.Claim -> ZillitTheme.colors.success
    isEnhanced -> ZillitTheme.colors.warning
    else -> ZillitTheme.colors.textPrimary
}

private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private const val DAY_WEIGHT = 1.3f
private const val CODE_WEIGHT = 1.5f
private const val NOMINAL_WEIGHT = 0.9f
private const val NUMBER_WEIGHT = 0.9f
private val TIME_WIDTH = 150.dp
private val ACTOR_WIDTH = 190.dp
private val ACTOR_AVATAR = 28.dp
