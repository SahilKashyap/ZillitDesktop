package com.zillit.desktop.feature.payroll.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.domain.SendToRun
import com.zillit.desktop.feature.payroll.domain.payrollMoneyTotal
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.ui.OutstandingDetail
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingEvent
import com.zillit.desktop.feature.payroll.ui.components.DialogActions
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
    // `detail` first, `shown` only to keep the last week list on screen
    // during the dismiss fade (once `detail` itself goes null) — reading
    // `shown` alone here missed the fetch actually completing, since
    // `shown` never updates past its first (loading, empty) snapshot.
    val open = detail ?: shown
    val week = state.processing.weekStarting ?: state.currentWeek
    val rows = open?.weeks.orEmpty().map { ProcessingRow(it, week) }
    ZillitDialogShell(
        title = shown?.let { str(S.desktop_payroll_outstanding_for, state.nameOf(it.userId)) }.orEmpty(),
        subtitle = str(S.desktop_payroll_outstanding_subtitle),
        icon = ZillitIcons.Clock,
        visible = detail != null,
        width = WIDE_DIALOG,
        onDismiss = { onEvent(ProcessingEvent.OpenOutstanding(null)) },
        actions = open?.takeIf { state.viewer.seesAccountantViews && it.sendable.isNotEmpty() }
            ?.let { sendActions(it, onEvent) },
    ) {
        if (open == null) return@ZillitDialogShell
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
                val weeksTotal = payrollMoneyTotal(
                    rows.map { it.totalPay to it.timecard.currency },
                    state.currencyRates,
                )
                ZillitStatusPill(
                    label = str(
                        S.desktop_payroll_weeks_total,
                        rows.size,
                        Money.format(weeksTotal.first, weeksTotal.second),
                    ),
                    tone = StatusTone.Pending,
                )
                rows.forEachIndexed { index, row ->
                    WeekCard(
                        row = row,
                        latest = index == 0,
                        // A checkbox only on a week the Run would accept — never a disabled one.
                        pick = Pick(
                            offered = state.viewer.seesAccountantViews && SendToRun.canSend(row.timecard),
                            picked = row.timecard.id in open.picked,
                        ),
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/** Whether a week card carries a Send to Payroll Run checkbox, and whether it is ticked. */
private data class Pick(val offered: Boolean, val picked: Boolean)

/**
 * The footer: take every sendable week at once (or clear them), and send the
 * ticked ones. The web keeps the button quiet until a week is ticked; here it
 * is shown disabled with the hint, so the action can be found.
 */
private fun sendActions(open: OutstandingDetail, onEvent: (PayrollEvent) -> Unit): @Composable RowScope.() -> Unit = {
    val all = open.chosen.size == open.sendable.size
    ZillitText(
        text = str(S.desktop_payroll_pick_week_for_run),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.weight(1f),
    )
    ZillitButton(
        text = if (all) {
            str(S.desktop_ce_inbox_clear_selection)
        } else {
            str(S.desktop_select_all_count, open.sendable.size)
        },
        onClick = { onEvent(ProcessingEvent.PickAllWeeks) },
        variant = ButtonVariant.Tertiary,
    )
    ZillitButton(
        text = if (open.chosen.isEmpty()) {
            str(S.desktop_payroll_send_to_run)
        } else {
            str(S.desktop_payroll_send_to_run_count, open.chosen.size)
        },
        onClick = { onEvent(ProcessingEvent.AskSendToRun) },
        variant = ButtonVariant.Secondary,
        enabled = open.chosen.isNotEmpty() && open.send == null,
    )
}

@Composable
private fun WeekCard(row: ProcessingRow, latest: Boolean, pick: Pick, onEvent: (PayrollEvent) -> Unit) {
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
            if (pick.offered) {
                ZillitCheckbox(
                    checked = pick.picked,
                    onCheckedChange = { onEvent(ProcessingEvent.PickWeek(timecard.id)) },
                )
            }
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

/**
 * Send to Payroll Run's confirm — the web reuses `OverrideTimecardModal` with
 * its own words, so this does too: the weeks it names, an optional reason the
 * server records, and the batch's outcome reported week by week.
 */
@Composable
fun SendToRunDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val prompt = state.processing.detail?.send
    val shown = remember(prompt != null) { prompt } ?: prompt
    ZillitDialogShell(
        title = str(S.desktop_payroll_send_to_run),
        subtitle = shown?.let {
            listOf(state.nameOf(state.processing.detail?.userId.orEmpty()), it.weekLabels.joinToString(", "))
                .filter(String::isNotBlank).joinToString(" · ")
        },
        icon = ZillitIcons.Shield,
        visible = prompt != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(ProcessingEvent.DismissSendToRun) },
    ) {
        val open = prompt ?: shown ?: return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_payroll_send_to_run_explainer),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(ProcessingEvent.EditSendReason(it)) },
            label = str(S.av_reason_optional),
            placeholder = str(S.desktop_payroll_override_reason_placeholder),
            singleLine = false,
            enabled = !open.sending,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogActions(
            cancel = { onEvent(ProcessingEvent.DismissSendToRun) },
            confirmText = str(if (open.sending) S.desktop_inv_sending else S.desktop_payroll_send_to_run_confirm),
            confirm = { onEvent(ProcessingEvent.ConfirmSendToRun) },
            busy = open.sending,
        )
    }
}

private val DIALOG_WIDTH = 480.dp
private val WIDE_DIALOG = 680.dp
private const val SKELETONS = 3
