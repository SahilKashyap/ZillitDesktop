package com.zillit.desktop.feature.payroll.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingEvent
import com.zillit.desktop.feature.payroll.ui.components.tone

/**
 * Export Payroll CSV — the server's workbook, in the two scopes the web
 * offers on the current week: every outstanding week, or the current one.
 */
@Composable
fun ProcessingExportDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val open = state.processing.exportOpen
    ZillitDialogShell(
        title = str(S.desktop_payroll_export_csv_title),
        subtitle = str(S.desktop_payroll_export_csv_intro),
        icon = ZillitIcons.Download,
        visible = open,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(ProcessingEvent.Export(open = false)) },
    ) {
        ExportOption(
            title = str(S.desktop_payroll_all_outstanding),
            sub = str(S.desktop_payroll_all_outstanding_sub),
            onClick = { onEvent(ProcessingEvent.ExportScope(outstanding = true)) },
        )
        ExportOption(
            title = str(S.current_weekly),
            sub = PayPeriod.compactRangeLabel(state.currentWeek),
            onClick = { onEvent(ProcessingEvent.ExportScope(outstanding = false)) },
        )
    }
}

@Composable
private fun ExportOption(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Download, tint = ZillitTheme.colors.accent)
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
            ZillitText(text = sub, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

/**
 * One crew member's unsettled weeks, newest first — the web's
 * `OutstandingDetailModal`: each week's figures and its status, with claims
 * and deductions reachable per week.
 */
@Suppress("LongMethod") // One dialog: the totals, then a card per week.
@Composable
fun OutstandingDetailDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val detail = state.processing.detail
    val shown = remember(detail != null) { detail } ?: detail
    val week = state.processing.weekStarting ?: state.currentWeek
    val rows = shown?.weeks.orEmpty().map { ProcessingRow(it, week) }
    val currency = shown?.weeks?.firstNotNullOfOrNull { it.currency }
    ZillitDialogShell(
        title = shown?.let { str(S.desktop_payroll_outstanding_for, state.nameOf(it.userId)) }.orEmpty(),
        subtitle = str(S.desktop_payroll_outstanding_subtitle),
        icon = ZillitIcons.Clock,
        visible = detail != null,
        width = WIDE_DIALOG,
        onDismiss = { onEvent(ProcessingEvent.OpenOutstanding(null)) },
    ) {
        val open = detail ?: shown ?: return@ZillitDialogShell
        when {
            open.loading -> repeat(SKELETONS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
            open.error != null -> ZillitNotice(
                text = str(S.desktop_payroll_outstanding_failed),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
            rows.isEmpty() -> ZillitText(
                text = str(S.desktop_payroll_no_outstanding_weeks),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            else -> {
                ZillitStatusPill(
                    label = str(
                        S.desktop_payroll_weeks_total,
                        rows.size,
                        Money.format(rows.sumOf { it.totalPay }, currency),
                    ),
                    tone = StatusTone.Pending,
                )
                rows.forEachIndexed { index, row -> WeekCard(row, latest = index == 0, onEvent = onEvent) }
            }
        }
    }
}

@Composable
private fun WeekCard(row: ProcessingRow, latest: Boolean, onEvent: (PayrollEvent) -> Unit) {
    val timecard = row.timecard
    val code = timecard.currency
    Column(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = timecard.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty(),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (latest) ZillitStatusPill(label = str(S.cs_latest), tone = StatusTone.InTransit)
            ZillitStatusPill(label = timecard.status.label, tone = timecard.status.tone, dot = true)
        }
        ZillitText(
            text = listOf(
                "${str(S.desktop_payroll_basic)} ${Money.format(row.basicTotal, code)}",
                "${str(S.desktop_payroll_ots_premiums)} ${Money.format(row.otTotal, code)}",
                "${str(S.desktop_payroll_allowances_rental)} ${Money.format(row.allowanceTotal, code)}",
                "${str(S.desktop_ce_claims)} ${Money.format(timecard.claimsTotal, code)}",
                "${str(S.desktop_deductions)} −${Money.format(timecard.deductionsTotal, code)}",
            ).joinToString(" · "),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitButton(
                text = str(S.desktop_payroll_add_claims_plus),
                onClick = { onEvent(PayrollEvent.OpenAdjustments(AdjustmentKind.Claims, timecard)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_payroll_add_deduction_plus),
                onClick = { onEvent(PayrollEvent.OpenAdjustments(AdjustmentKind.Deductions, timecard)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = "${str(S.ah_total_label)} ${Money.format(row.totalPay, code)}",
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

private val DIALOG_WIDTH = 480.dp
private val WIDE_DIALOG = 680.dp
private const val SKELETONS = 3
