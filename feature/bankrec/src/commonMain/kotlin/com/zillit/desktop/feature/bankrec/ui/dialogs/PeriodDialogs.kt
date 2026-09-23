package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.pages.BankLinesTable
import com.zillit.desktop.feature.bankrec.ui.pages.LedgerLinesTable
import com.zillit.desktop.feature.bankrec.ui.pages.PanelHeading
import com.zillit.desktop.feature.bankrec.ui.pages.PortalPreviewCard
import com.zillit.desktop.feature.bankrec.ui.pages.SplitRow

/**
 * The confirmation before periods go — in the web's words, because what it
 * says is the whole point: everything the statement produced goes with it,
 * and the invoices it matched are unmatched again.
 */
@Composable
internal fun DeletePeriodsDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val request = rememberLast(state.deleting) ?: return
    val count = request.ids.size
    ZillitDialogShell(
        title = if (count > 1) {
            str(S.desktop_br_delete_n_periods_q, count)
        } else {
            str(S.desktop_br_delete_this_period_q)
        },
        onDismiss = { if (!request.deleting) onEvent(BankRecEvent.DismissDeletePeriods) },
        visible = state.deleting != null,
        icon = ZillitIcons.Trash,
        width = 460.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(BankRecEvent.DismissDeletePeriods) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.deleting,
            )
            ZillitButton(
                text = when {
                    request.deleting -> str(S.ah_deleting)
                    count > 1 -> str(S.desktop_br_delete_n_periods, count)
                    else -> str(S.desktop_br_delete_period)
                },
                onClick = { onEvent(BankRecEvent.ConfirmDeletePeriods) },
                variant = ButtonVariant.Danger,
                loading = request.deleting,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_br_delete_periods_warning, request.label),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** Signed-off periods chosen for one PDF — only those, because only those are final. */
@Composable
internal fun ExportPdfDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val export = rememberLast(state.exportPdf) ?: return
    val periods = state.completedPeriods
    val selected = export.selected.intersect(periods.map { it.id }.toSet())
    ZillitDialogShell(
        title = str(S.desktop_br_export_reconciliation_pdf),
        subtitle = str(S.desktop_br_export_pdf_subtitle),
        onDismiss = { if (!export.exporting) onEvent(BankRecEvent.CloseExportPdf) },
        visible = state.exportPdf != null,
        icon = ZillitIcons.Download,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(BankRecEvent.CloseExportPdf) },
                variant = ButtonVariant.Tertiary,
                enabled = !export.exporting,
            )
            ZillitButton(
                text = when {
                    export.exporting -> str(S.drive_generating)
                    selected.isEmpty() -> str(S.asset_export)
                    else -> str(S.desktop_export_count, selected.size)
                },
                onClick = { onEvent(BankRecEvent.ConfirmExportPdf) },
                leadingIcon = ZillitIcons.File,
                loading = export.exporting,
                enabled = selected.isNotEmpty() && !export.exporting,
            )
        },
    ) {
        if (periods.isEmpty()) {
            ZillitText(
                str(S.desktop_br_no_completed_periods),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            )
            return@ZillitDialogShell
        }
        ZillitCheckbox(
            checked = selected.size == periods.size,
            onCheckedChange = { onEvent(BankRecEvent.ToggleAllExportPeriods) },
            label = str(S.desktop_br_select_all_count, periods.size),
        )
        ZillitDivider()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            periods.forEach { period ->
                ExportRow(period, period.id in selected) { onEvent(BankRecEvent.ToggleExportPeriod(period.id)) }
            }
        }
    }
}

@Composable
private fun ExportRow(period: BankPeriod, checked: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (checked) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitCheckbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            ZillitText(
                BankRecFormat.periodLabel(period),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                str(S.desktop_br_txns_signed, period.totalTxns, BankRecFormat.localDay(period.signedAtMillis)),
                style = mono(10.5.sp),
                color = colors.textMuted,
            )
        }
        BrBadge(str(S.dm_action_complete), BrTone.Green)
        ZillitIcon(ZillitIcons.Check, tint = if (checked) colors.accent else Color.Transparent, size = 14.dp)
    }
}

/**
 * A period, read-only: the summary a shared link would show, with the
 * statement's lines and the ledger entries it settled in place of the
 * summary's own transaction section.
 */
@Composable
internal fun PeriodDetailDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val detail = rememberLast(state.periodDetail) ?: return
    val period = state.period(detail.periodId)
    val accountCurrency = detail.preview?.bankAccountCurrency?.takeIf { it.isNotBlank() } ?: state.currencyOf(period)
    // Only what was settled: the entries a signed-off month marked paid.
    val paid = detail.ledger.filter { it.isPaid }
    ZillitDialogShell(
        title = str(S.desktop_period_details),
        subtitle = period?.let { row ->
            listOfNotNull(
                BankRecFormat.fullPeriodLabel(row),
                state.account(row.bankAccountId)?.displayName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        },
        onDismiss = { onEvent(BankRecEvent.ClosePeriodDetail) },
        visible = state.periodDetail != null,
        icon = ZillitIcons.Calendar,
        width = WIDE_DIALOG,
        maxHeight = TALL_DIALOG,
    ) {
        PortalPreviewCard(
            preview = detail.preview,
            loading = detail.loading,
            state = state,
            beforeNotes = {
                SplitRow(
                    padded = false,
                    left = {
                        Column {
                            PanelHeading(str(S.desktop_bank_transactions), detail.transactions.size, ZillitIcons.Bank)
                            BankLinesTable(detail.transactions, accountCurrency, detail = true)
                        }
                    },
                    right = {
                        Column {
                            PanelHeading(str(S.desktop_ledger_entries), paid.size, ZillitIcons.Ledger)
                            LedgerLinesTable(paid, state.projectCurrency, detail = true)
                        }
                    },
                )
                ZillitDivider()
            },
        )
    }
}

internal val WIDE_DIALOG = 1080.dp
internal val TALL_DIALOG = 880.dp
